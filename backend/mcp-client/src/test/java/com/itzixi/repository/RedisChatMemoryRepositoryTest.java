package com.itzixi.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * {@link RedisChatMemoryRepository} 的单元测试。
 * <p>用 mock 的 {@link StringRedisTemplate} + 内存 List 模拟 Redis LIST，覆盖：
 * 新消息写入创建时间、历史消息时间沿用、旧数据缺 {@code createdAt} 时不报错且不伪造时间、
 * 反序列化后时间正确。不依赖真实 Redis。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@Tag("dev")
class RedisChatMemoryRepositoryTest {

    private static final String KEY = "chat:memory:s1";
    private static final String USER_MESSAGE_CLASS = "org.springframework.ai.chat.messages.UserMessage";

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ListOperations<String, String> listOperations;

    private RedisChatMemoryRepository repository;

    /** 模拟 Redis 中 key=chat:memory:s1 的 LIST */
    private List<String> store;

    @BeforeEach
    void setUp() {
        store = new ArrayList<>();
        when(redisTemplate.opsForList()).thenReturn(listOperations);
        when(listOperations.range(KEY, 0, -1)).thenAnswer(invocation -> new ArrayList<>(store));
        when(listOperations.rightPushAll(eq(KEY), anyCollection())).thenAnswer(invocation -> {
            java.util.Collection<String> values = invocation.getArgument(1);
            store.addAll(values);
            return (long) values.size();
        });
        when(redisTemplate.delete(KEY)).thenAnswer(invocation -> {
            store.clear();
            return true;
        });

        repository = new RedisChatMemoryRepository(redisTemplate);
        ReflectionTestUtils.setField(repository, "keyPrefix", "chat:memory:");
        ReflectionTestUtils.setField(repository, "ttlDays", 0);
    }

    @Test
    void saveAll_shouldStampCreatedAtForNewMessages() {
        long before = System.currentTimeMillis();
        repository.saveAll("s1", List.of(new UserMessage("你好"), new AssistantMessage("请问要查什么？")));
        long after = System.currentTimeMillis();

        List<RedisChatMemoryRepository.StoredMessage> entries = repository.findByConversationIdWithCreatedAt("s1");

        assertEquals(2, entries.size());
        assertEquals("你好", entries.get(0).message().getText());
        assertEquals("请问要查什么？", entries.get(1).message().getText());
        assertNotNull(entries.get(0).createdAt());
        assertNotNull(entries.get(1).createdAt());
        assertTrue(entries.get(0).createdAt() >= before && entries.get(0).createdAt() <= after);
        // 存储的 JSON 里确实带上了 createdAt 字段
        assertTrue(store.get(0).contains("\"createdAt\":" + entries.get(0).createdAt()));
    }

    @Test
    void findByConversationIdWithCreatedAt_shouldTolerateLegacyDataWithoutCreatedAt() {
        // 旧格式：只有 @class / messageType / text，没有 createdAt
        store.add("{\"@class\":\"" + USER_MESSAGE_CLASS + "\",\"messageType\":\"USER\",\"text\":\"历史消息\"}");

        List<RedisChatMemoryRepository.StoredMessage> entries = repository.findByConversationIdWithCreatedAt("s1");

        assertEquals(1, entries.size());
        assertEquals("历史消息", entries.get(0).message().getText());
        assertNull(entries.get(0).createdAt());
    }

    @Test
    void saveAll_shouldKeepExistingCreatedAtWhenMessageAppended() {
        repository.saveAll("s1", List.of(new UserMessage("第一问")));
        Long firstCreatedAt = repository.findByConversationIdWithCreatedAt("s1").get(0).createdAt();
        assertNotNull(firstCreatedAt);

        // 窗口记忆第二次 saveAll 提交完整列表：历史消息时间必须保持不变
        repository.saveAll("s1", List.of(new UserMessage("第一问"), new AssistantMessage("第一答")));

        List<RedisChatMemoryRepository.StoredMessage> entries = repository.findByConversationIdWithCreatedAt("s1");
        assertEquals(2, entries.size());
        assertEquals(firstCreatedAt, entries.get(0).createdAt());
        assertNotNull(entries.get(1).createdAt());
        assertTrue(entries.get(1).createdAt() >= firstCreatedAt);
    }

    @Test
    void saveAll_shouldNotFabricateCreatedAtForLegacyMessages() {
        store.add("{\"@class\":\"" + USER_MESSAGE_CLASS + "\",\"messageType\":\"USER\",\"text\":\"旧消息\"}");

        repository.saveAll("s1", List.of(new UserMessage("旧消息"), new AssistantMessage("新回复")));

        List<RedisChatMemoryRepository.StoredMessage> entries = repository.findByConversationIdWithCreatedAt("s1");
        assertEquals(2, entries.size());
        // 旧消息没有时间，重写后仍然没有；只有新增的助手消息带时间
        assertNull(entries.get(0).createdAt());
        assertNotNull(entries.get(1).createdAt());
        assertFalse(store.get(0).contains("createdAt"));
    }

    @Test
    void saveAll_shouldStampAppendedDuplicateMessageWithItsOwnTime() {
        repository.saveAll("s1", List.of(new UserMessage("你好")));
        Long firstCreatedAt = repository.findByConversationIdWithCreatedAt("s1").get(0).createdAt();

        // 用户重复发送相同文本：第一条沿用原时间，新增的一条使用本次写入时间
        repository.saveAll("s1", List.of(new UserMessage("你好"), new UserMessage("你好")));

        List<RedisChatMemoryRepository.StoredMessage> entries = repository.findByConversationIdWithCreatedAt("s1");
        assertEquals(2, entries.size());
        assertEquals(firstCreatedAt, entries.get(0).createdAt());
        assertNotNull(entries.get(1).createdAt());
        assertTrue(entries.get(1).createdAt() >= firstCreatedAt);
    }
}
