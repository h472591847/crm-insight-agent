package com.itzixi.controller;

import com.itzixi.constants.ChatConstants;
import com.itzixi.repository.RedisChatMemoryRepository;
import com.itzixi.repository.SessionOwnershipRepository;
import com.itzixi.utils.RedisHelper;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 会话管理 API。
 * <p>
 * 提供会话列表（含摘要/条数元数据）、历史消息查看、删除能力。
 * 所有接口都要求携带 {@code userId}，并按 {@link SessionOwnershipRepository} 校验会话归属：
 * 只有会话的所有者才能列出、读取、删除该会话，避免跨用户串话与越权。
 * </p>
 * <p>
 * 元数据直接从 Redis LIST 派生（不额外写 metadata hash）：
 * <ul>
 *     <li>{@code summary} — 第一条 UserMessage 文本，截断到 30 字</li>
 *     <li>{@code messageCount} — Redis LIST 长度</li>
 * </ul>
 * 所有 Redis 操作通过 {@link RedisHelper} 统一封装，避免 Controller 层散落 opsForList 等调用。
 * </p>
 *
 * @author oliver
 * @since 1.0.0
 */
@RestController
@RequestMapping("/api/session")
public class SessionController {

    private static final Logger log = LoggerFactory.getLogger(SessionController.class);

    private final RedisChatMemoryRepository repository;
    private final SessionOwnershipRepository ownershipRepository;
    private final ChatMemory chatMemory;
    private final RedisHelper redisHelper;

    public SessionController(RedisChatMemoryRepository repository,
                             SessionOwnershipRepository ownershipRepository,
                             ChatMemory chatMemory,
                             RedisHelper redisHelper) {
        this.repository = repository;
        this.ownershipRepository = ownershipRepository;
        this.chatMemory = chatMemory;
        this.redisHelper = redisHelper;
    }

    /**
     * 列出当前用户自己的会话，每条带摘要/条数元数据。
     * 按 conversationId 倒序（UUID 时间戳靠前，新会话在前）。
     *
     * @param userId 当前用户标识（必填，缺失直接 400，避免退化为全库列表）
     */
    @GetMapping("/list")
    public ResponseEntity<Map<String, Object>> listSessions(
            @RequestParam(required = false) String userId) {
        if (StringUtils.isBlank(userId)) {
            return ResponseEntity.badRequest().body(errorBody("userId 不能为空"));
        }

        List<String> conversationIds = ownershipRepository.listSessions(userId);
        conversationIds.sort(Collections.reverseOrder());

        List<Map<String, Object>> sessions = new ArrayList<>(conversationIds.size());
        for (String id : conversationIds) {
            String key = ChatConstants.REDIS_KEY_CHAT_MEMORY_PREFIX + id;
            // 从 Redis 直接取 LIST 长度，避免 repository 拉全量
            long messageCount = redisHelper.lSize(key);
            String summary = computeSummary(id);

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("conversationId", id);
            item.put("summary", summary);
            item.put("messageCount", messageCount);
            sessions.add(item);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", sessions.size());
        result.put("sessions", sessions);
        return ResponseEntity.ok(result);
    }

    /**
     * 拉取某会话的消息历史，用于切换会话时前端回填。
     * 返回数组按时间升序（最早在前），与前端 chatList 顺序一致；
     * 每条消息带 {@code createdAt}（epoch 毫秒），Redis 旧数据没有该字段时为 null。
     *
     * @param conversationId 会话标识
     * @param userId         当前用户标识（必填）
     * @param limit          最多返回的消息条数，默认 50
     */
    @GetMapping("/{conversationId}/messages")
    public ResponseEntity<Map<String, Object>> getMessages(
            @PathVariable String conversationId,
            @RequestParam(required = false) String userId,
            @RequestParam(defaultValue = "50") int limit) {
        ResponseEntity<Map<String, Object>> denied = checkAccess(conversationId, userId);
        if (denied != null) {
            // 全新会话尚未产生任何记忆（Redis 里没有消息 LIST）：返回空历史而不是 404，
            // 避免前端刷新页面时把"还没有历史"当成错误弹窗。
            if (denied.getStatusCode() == HttpStatus.NOT_FOUND
                    && !redisHelper.exists(ChatConstants.REDIS_KEY_CHAT_MEMORY_PREFIX + conversationId)) {
                Map<String, Object> empty = new LinkedHashMap<>();
                empty.put("conversationId", conversationId);
                empty.put("total", 0);
                empty.put("messages", Collections.emptyList());
                return ResponseEntity.ok(empty);
            }
            return denied;
        }

        List<RedisChatMemoryRepository.StoredMessage> all =
                repository.findByConversationIdWithCreatedAt(conversationId);
        // 取最近 limit 条，升序返回
        int fromIndex = Math.max(0, all.size() - limit);
        List<RedisChatMemoryRepository.StoredMessage> recent = all.subList(fromIndex, all.size());

        List<Map<String, Object>> result = new ArrayList<>(recent.size());
        for (RedisChatMemoryRepository.StoredMessage stored : recent) {
            Message msg = stored.message();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", msg.getMessageType().name().toLowerCase()); // user / assistant / system / tool
            item.put("content", msg.getText() != null ? msg.getText() : "");
            // 创建时间（epoch 毫秒）；Redis 旧数据没有记录时为 null，不伪造
            item.put("createdAt", stored.createdAt());
            result.add(item);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("conversationId", conversationId);
        response.put("total", all.size());
        response.put("messages", result);
        return ResponseEntity.ok(response);
    }

    /**
     * 删除某会话的全部记忆（Redis 中删 key），并清理归属登记与用户索引。
     *
     * @param conversationId 会话标识
     * @param userId         当前用户标识（必填）
     */
    @DeleteMapping("/{conversationId}")
    public ResponseEntity<Map<String, Object>> deleteSession(
            @PathVariable String conversationId,
            @RequestParam(required = false) String userId) {
        ResponseEntity<Map<String, Object>> denied = checkAccess(conversationId, userId);
        if (denied != null) {
            return denied;
        }

        chatMemory.clear(conversationId);
        repository.deleteByConversationId(conversationId); // 双保险
        ownershipRepository.remove(conversationId, userId);
        log.info("已删除会话 conversationId={} 的记忆, userId={}", conversationId, userId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("conversationId", conversationId);
        result.put("deleted", true);
        return ResponseEntity.ok(result);
    }

    // ===== 内部工具 =====

    /**
     * 校验会话归属。
     *
     * @return {@code null} 表示通过；否则返回应直接响应的错误（400/403/404）
     */
    private ResponseEntity<Map<String, Object>> checkAccess(String conversationId, String userId) {
        if (StringUtils.isBlank(userId)) {
            return ResponseEntity.badRequest().body(errorBody("userId 不能为空"));
        }
        if (ownershipRepository.isOwner(conversationId, userId)) {
            return null;
        }
        // 有归属但属于别人 → 403；完全无归属（历史 UUID 会话/不存在）→ 404
        if (ownershipRepository.hasOwner(conversationId)) {
            log.warn("拒绝越权访问会话 conversationId={}, requestUserId={}", conversationId, userId);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(errorBody("无权访问该会话"));
        }
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorBody("会话不存在"));
    }

    private static Map<String, Object> errorBody(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        return body;
    }

    // ===== 摘要/序列化工具 =====

    /**
     * 计算摘要：直接从 Redis LIST 拉第一条消息 JSON，解析 messageType/text 字段。
     * 避免调用 repository.findByConversationId 反序列化全量消息。
     */
    private String computeSummary(String conversationId) {
        String key = ChatConstants.REDIS_KEY_CHAT_MEMORY_PREFIX + conversationId;
        String firstJson = redisHelper.lIndex(key, 0);
        if (firstJson == null || firstJson.isBlank()) {
            return "";
        }
        try {
            String type = extractString(firstJson, "messageType");
            if ("USER".equals(type)) {
                return truncate(extractString(firstJson, "text"), ChatConstants.SUMMARY_MAX_LEN);
            }
            // 第一条不是 USER（可能是 SYSTEM），往后找第一条 USER
            List<String> allJson = redisHelper.lRange(key, 0, -1);
            for (String json : allJson) {
                if ("USER".equals(extractString(json, "messageType"))) {
                    return truncate(extractString(json, "text"), ChatConstants.SUMMARY_MAX_LEN);
                }
            }
            return "";
        } catch (Exception e) {
            log.warn("计算会话摘要失败, conversationId={}", conversationId, e);
            return "";
        }
    }

    /**
     * 从 JSON 字符串里提取指定字段的字符串值（简单实现，避免再走一次 ObjectMapper）。
     * 能处理转义的引号、换行、反斜杠。
     */
    private static String extractString(String json, String field) {
        String key = "\"" + field + "\"";
        int keyIdx = json.indexOf(key);
        if (keyIdx < 0) return null;
        int colonIdx = json.indexOf(':', keyIdx + key.length());
        if (colonIdx < 0) return null;
        int startQuote = json.indexOf('"', colonIdx + 1);
        if (startQuote < 0) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = startQuote + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"') break;
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(++i);
                switch (next) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    default -> sb.append(next);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
