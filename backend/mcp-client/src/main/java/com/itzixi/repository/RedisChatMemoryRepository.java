package com.itzixi.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.*;

/**
 * Redis 实现的 ChatMemoryRepository。
 * <p>
 * 序列化用 Jackson（不可变对象也能写），并在每条消息 JSON 中追加 {@code createdAt}（epoch 毫秒）。
 * 反序列化走手动路由：解析 JSON 的 {@code @class} / {@code messageType} / {@code text} 字段，
 * 调用各 Message 子类的单 String 构造器（{@code new UserMessage(text)} 等）。
 * <p>
 * 时间字段兼容说明：Redis 中已有的历史数据没有 {@code createdAt}，读取时该值为 {@code null}，
 * 不补当前时间；窗口记忆重写完整列表时，已存在消息沿用原有时间（无时间则继续留空），
 * 只有本次新增的消息才写入写入时刻。
 * <p>
 * 为什么不直接 Jackson.readValue：Spring AI 1.1.x 的 UserMessage / AssistantMessage /
 * SystemMessage / ToolResponseMessage 都是不可变 POJO，没有无参构造器也没有 {@code @JsonCreator}，
 * Jackson 无法多态反序列化。
 */
@Repository
public class RedisChatMemoryRepository implements ChatMemoryRepository {

    private static final Logger log = LoggerFactory.getLogger(RedisChatMemoryRepository.class);

    private static final String USER_MESSAGE_CLASS = "org.springframework.ai.chat.messages.UserMessage";
    private static final String ASSISTANT_MESSAGE_CLASS = "org.springframework.ai.chat.messages.AssistantMessage";
    private static final String SYSTEM_MESSAGE_CLASS = "org.springframework.ai.chat.messages.SystemMessage";
    private static final String TOOL_RESPONSE_MESSAGE_CLASS = "org.springframework.ai.chat.messages.ToolResponseMessage";

    /** 消息 JSON 的类标识字段 */
    private static final String FIELD_CLASS = "@class";
    /** 消息 JSON 的类型字段（USER / ASSISTANT / SYSTEM / TOOL） */
    private static final String FIELD_MESSAGE_TYPE = "messageType";
    /** 消息 JSON 的文本字段 */
    private static final String FIELD_TEXT = "text";
    /** 消息 JSON 的创建时间字段（epoch 毫秒）。旧数据没有该字段 */
    private static final String FIELD_CREATED_AT = "createdAt";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper mapper;

    @Value("${app.chat.memory.redis.prefix:chat:memory:}")
    private String keyPrefix;

    /** 会话消息 TTL（默认 7 天）。0 表示不过期。 */
    @Value("${app.chat.memory.redis.ttl-days:7}")
    private int ttlDays;

    public RedisChatMemoryRepository(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.mapper = new ObjectMapper();
    }

    /**
     * 带创建时间的消息条目。
     *
     * @param message   反序列化后的消息
     * @param createdAt 创建时间（epoch 毫秒）；Redis 中的旧数据没有记录该字段时为 {@code null}
     */
    public record StoredMessage(Message message, Long createdAt) {
    }

    /**
     * 从 Redis 解析出的原始条目（保留类型/文本用于时间沿用匹配，message 可能为 null）。
     */
    private record ParsedEntry(String className, String typeName, String text, Long createdAt, Message message) {
    }

    @Override
    public List<String> findConversationIds() {
        // 使用 SCAN 分批遍历，避免 KEYS 在 key 数量膨胀时阻塞 Redis。
        // 会话列表接口已改为按用户索引 SET 读取，这里仅作为 ChatMemoryRepository 接口的实现保底。
        List<String> result = new ArrayList<>();
        ScanOptions options = ScanOptions.scanOptions().match(keyPrefix + "*").count(500).build();
        try (Cursor<String> cursor = redisTemplate.scan(options)) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                result.add(key.substring(keyPrefix.length()));
            }
        }
        return result;
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        List<StoredMessage> entries = findByConversationIdWithCreatedAt(conversationId);
        List<Message> messages = new ArrayList<>(entries.size());
        for (StoredMessage entry : entries) {
            messages.add(entry.message());
        }
        return messages;
    }

    /**
     * 读取会话消息及其创建时间，供历史消息接口返回时间戳使用。
     * <p>旧数据没有 {@code createdAt} 字段时该值为 {@code null}，不补当前时间。</p>
     *
     * @param conversationId 会话标识
     * @return 按写入顺序排列的消息条目
     */
    public List<StoredMessage> findByConversationIdWithCreatedAt(String conversationId) {
        List<ParsedEntry> entries = readEntries(keyPrefix + conversationId);
        List<StoredMessage> result = new ArrayList<>(entries.size());
        for (ParsedEntry entry : entries) {
            if (entry.message() != null) {
                result.add(new StoredMessage(entry.message(), entry.createdAt()));
            }
        }
        return result;
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        String key = keyPrefix + conversationId;
        if (messages == null || messages.isEmpty()) {
            return;
        }

        // 窗口记忆每次 saveAll 都会提交完整消息列表：只有本次新增的消息取当前时间，
        // 已存在的消息沿用 Redis 中记录的时间；旧数据没有时间则继续留空，不用当前时间伪造。
        List<ParsedEntry> existing = readEntries(key);
        boolean[] reused = new boolean[existing.size()];
        long now = System.currentTimeMillis();

        List<String> jsonList = new ArrayList<>(messages.size());
        for (Message msg : messages) {
            int matchedIndex = findExistingIndex(existing, reused, msg);
            // 注意：不能写成三元表达式（Long 与 long 混用会拆箱，旧数据 null 时抛 NPE）
            Long createdAt = now;
            if (matchedIndex >= 0) {
                createdAt = existing.get(matchedIndex).createdAt();
            }
            try {
                jsonList.add(serializeMessage(msg, createdAt));
            } catch (Exception e) {
                log.error("序列化 ChatMemory 消息失败，跳过: {}", msg, e);
            }
        }
        redisTemplate.delete(key);
        if (!jsonList.isEmpty()) {
            redisTemplate.opsForList().rightPushAll(key, jsonList);
            if (ttlDays > 0) {
                redisTemplate.expire(key, Duration.ofDays(ttlDays));
            }
        }
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        String key = keyPrefix + conversationId;
        redisTemplate.delete(key);
    }

    /**
     * 解析 Redis 中的消息 JSON：同时取出消息内容与创建时间。
     * <p>旧数据没有 {@code createdAt} 字段时 {@code createdAt} 为 {@code null}，不抛异常。</p>
     */
    private ParsedEntry parseEntry(String json) {
        try {
            JsonNode node = mapper.readTree(json);
            String className = node.has(FIELD_CLASS) ? node.get(FIELD_CLASS).asText() : null;
            String typeName = resolveTypeName(className,
                    node.has(FIELD_MESSAGE_TYPE) ? node.get(FIELD_MESSAGE_TYPE).asText() : null);
            String text = node.has(FIELD_TEXT) && !node.get(FIELD_TEXT).isNull() ? node.get(FIELD_TEXT).asText() : "";
            Long createdAt = parseCreatedAt(node);
            Message message = toMessage(className, typeName, text, json);
            return new ParsedEntry(className, typeName, text, createdAt, message);
        } catch (Exception e) {
            log.warn("反序列化 ChatMemory 消息失败，跳过: {}", json, e);
            return null;
        }
    }

    private List<ParsedEntry> readEntries(String key) {
        List<String> jsonList = redisTemplate.opsForList().range(key, 0, -1);
        if (jsonList == null || jsonList.isEmpty()) {
            return new ArrayList<>();
        }
        List<ParsedEntry> entries = new ArrayList<>(jsonList.size());
        for (String json : jsonList) {
            ParsedEntry entry = parseEntry(json);
            if (entry != null) {
                entries.add(entry);
            }
        }
        return entries;
    }

    private static Long parseCreatedAt(JsonNode node) {
        JsonNode value = node.get(FIELD_CREATED_AT);
        return value != null && value.isNumber() ? value.asLong() : null;
    }

    /**
     * 推导消息类型名：优先用 JSON 里的 {@code messageType}，缺失时按 {@code @class} 反推，
     * 保证旧数据也能参与"沿用创建时间"的匹配。
     */
    private static String resolveTypeName(String className, String typeName) {
        if (typeName != null) {
            return typeName;
        }
        if (className == null) {
            return null;
        }
        return switch (className) {
            case USER_MESSAGE_CLASS -> MessageType.USER.name();
            case ASSISTANT_MESSAGE_CLASS -> MessageType.ASSISTANT.name();
            case SYSTEM_MESSAGE_CLASS -> MessageType.SYSTEM.name();
            case TOOL_RESPONSE_MESSAGE_CLASS -> MessageType.TOOL.name();
            default -> null;
        };
    }

    /**
     * 在上一版存储中按"类型 + 文本"顺序匹配同一条消息，用于沿用其创建时间。
     * 窗口记忆按追加方式演进，顺序匹配可以正确处理重复内容与被裁剪的历史消息。
     *
     * @return 匹配到的下标；{@code -1} 表示这是本次新增的消息
     */
    private static int findExistingIndex(List<ParsedEntry> existing, boolean[] reused, Message msg) {
        String typeName = msg.getMessageType().name();
        // 与解析侧保持一致：text 为 null 时按空串比较（例如只有工具调用、没有文本的助手消息）
        String text = msg.getText() != null ? msg.getText() : "";
        for (int i = 0; i < existing.size(); i++) {
            if (reused[i]) {
                continue;
            }
            ParsedEntry entry = existing.get(i);
            if (Objects.equals(typeName, entry.typeName()) && Objects.equals(text, entry.text())) {
                reused[i] = true;
                return i;
            }
        }
        return -1;
    }

    /**
     * 序列化消息并写入创建时间（epoch 毫秒）。{@code createdAt} 为 null 时不写该字段，兼容旧数据。
     */
    private String serializeMessage(Message msg, Long createdAt) throws Exception {
        ObjectNode node = (ObjectNode) mapper.readTree(mapper.writeValueAsString(msg));
        if (createdAt != null) {
            node.put(FIELD_CREATED_AT, createdAt);
        }
        return mapper.writeValueAsString(node);
    }

    private Message toMessage(String className, String typeName, String text, String json) {
        // 优先按 @class 精确匹配（最可靠）
        if (className != null) {
            return deserializeByClass(className, text, json);
        }
        // 兜底：按 messageType 枚举名匹配
        if (typeName != null) {
            return deserializeByType(typeName, text, json);
        }
        log.warn("反序列化消息失败（无 @class / messageType），跳过: {}", json);
        return null;
    }

    private Message deserializeByClass(String className, String text, String json) {
        switch (className) {
            case USER_MESSAGE_CLASS -> {
                return new UserMessage(text);
            }
            case ASSISTANT_MESSAGE_CLASS -> {
                return new AssistantMessage(text);
            }
            case SYSTEM_MESSAGE_CLASS -> {
                return new SystemMessage(text);
            }
            case TOOL_RESPONSE_MESSAGE_CLASS -> {
                log.info("ToolResponseMessage 暂不支持完整恢复，跳过: {}", json);
                return null;
            }
            default -> {
                log.warn("未识别的 Message 子类，跳过: {}", json);
                return null;
            }
        }
    }

    private Message deserializeByType(String typeName, String text, String json) {
        try {
            MessageType type = MessageType.valueOf(typeName);
            switch (type) {
                case USER -> { return new UserMessage(text); }
                case ASSISTANT -> { return new AssistantMessage(text); }
                case SYSTEM -> { return new SystemMessage(text); }
                case TOOL -> {
                    log.info("TOOL 类型消息暂不支持完整恢复，跳过: {}", json);
                    return null;
                }
            }
        } catch (IllegalArgumentException ignore) {
        }
        log.warn("未识别的 messageType={}, 跳过: {}", typeName, json);
        return null;
    }
}
