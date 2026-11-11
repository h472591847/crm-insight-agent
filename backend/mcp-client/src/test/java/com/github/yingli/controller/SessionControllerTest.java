package com.github.yingli.controller;

import com.github.yingli.repository.RedisChatMemoryRepository;
import com.github.yingli.repository.SessionOwnershipRepository;
import com.github.yingli.utils.RedisHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * {@link SessionController} 的接口级单元测试。
 * <p>使用 standalone MockMvc + mock 仓储，覆盖：缺失 userId 拒绝、列表按用户过滤、
 * 读取/删除越权返回 403、无归属返回 404。不依赖真实 Redis。</p>
 */
@ExtendWith(MockitoExtension.class)
@Tag("dev")
class SessionControllerTest {

    @Mock
    private RedisChatMemoryRepository repository;
    @Mock
    private SessionOwnershipRepository ownershipRepository;
    @Mock
    private ChatMemory chatMemory;
    @Mock
    private RedisHelper redisHelper;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        SessionController controller = new SessionController(
                repository, ownershipRepository, chatMemory, redisHelper);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void listSessions_shouldRejectMissingUserId() throws Exception {
        mockMvc.perform(get("/api/session/list"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(ownershipRepository);
    }

    @Test
    void listSessions_shouldOnlyReturnCurrentUsersSessions() throws Exception {
        when(ownershipRepository.listSessions("userA"))
                .thenReturn(new ArrayList<>(List.of("s2")));
        when(redisHelper.lSize("chat:memory:s2")).thenReturn(4L);
        when(redisHelper.lIndex("chat:memory:s2", 0))
                .thenReturn("{\"messageType\":\"USER\",\"text\":\"雪山路要客\"}");

        mockMvc.perform(get("/api/session/list").param("userId", "userA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.sessions[0].conversationId").value("s2"))
                .andExpect(jsonPath("$.sessions[0].summary").value("雪山路要客"))
                .andExpect(jsonPath("$.sessions[0].messageCount").value(4));

        verify(ownershipRepository).listSessions("userA");
        verify(ownershipRepository, never()).listSessions("userB");
    }

    @Test
    void listSessions_shouldReturnEmptyListWhenNoSessions() throws Exception {
        when(ownershipRepository.listSessions("userA")).thenReturn(new ArrayList<>());

        mockMvc.perform(get("/api/session/list").param("userId", "userA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.sessions").isEmpty());
    }

    @Test
    void getMessages_shouldRejectMissingUserId() throws Exception {
        mockMvc.perform(get("/api/session/s1/messages"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(repository);
    }

    @Test
    void getMessages_shouldForbidOtherUsersSession() throws Exception {
        when(ownershipRepository.isOwner("s1", "userB")).thenReturn(false);
        when(ownershipRepository.hasOwner("s1")).thenReturn(true);

        mockMvc.perform(get("/api/session/s1/messages").param("userId", "userB"))
                .andExpect(status().isForbidden());

        verify(repository, never()).findByConversationId("s1");
    }

    @Test
    void getMessages_shouldReturnNotFoundForUnownedSessionWithData() throws Exception {
        when(ownershipRepository.isOwner("ghost", "userA")).thenReturn(false);
        when(ownershipRepository.hasOwner("ghost")).thenReturn(false);
        when(redisHelper.exists("chat:memory:ghost")).thenReturn(true);

        mockMvc.perform(get("/api/session/ghost/messages").param("userId", "userA"))
                .andExpect(status().isNotFound());

        verify(repository, never()).findByConversationId("ghost");
    }

    @Test
    void getMessages_shouldReturnEmptyForFreshSession() throws Exception {
        // 全新会话在 Redis 里还没有消息 LIST：返回空历史，避免前端把"无历史"当错误
        when(ownershipRepository.isOwner("fresh", "userA")).thenReturn(false);
        when(ownershipRepository.hasOwner("fresh")).thenReturn(false);
        when(redisHelper.exists("chat:memory:fresh")).thenReturn(false);

        mockMvc.perform(get("/api/session/fresh/messages").param("userId", "userA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.messages").isEmpty());

        verify(repository, never()).findByConversationId("fresh");
    }

    @Test
    void getMessages_shouldReturnHistoryForOwner() throws Exception {
        when(ownershipRepository.isOwner("s1", "userA")).thenReturn(true);
        List<RedisChatMemoryRepository.StoredMessage> history = List.of(
                new RedisChatMemoryRepository.StoredMessage(new UserMessage("你好"), 1730000000000L),
                // 旧数据没有 createdAt，接口应原样返回 null，而不是伪造当前时间
                new RedisChatMemoryRepository.StoredMessage(new UserMessage("查一下销售额"), null));
        when(repository.findByConversationIdWithCreatedAt("s1")).thenReturn(history);

        mockMvc.perform(get("/api/session/s1/messages")
                        .param("userId", "userA")
                        .param("limit", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.messages[0].createdAt").value(1730000000000L))
                .andExpect(jsonPath("$.messages[1].content").value("查一下销售额"))
                .andExpect(jsonPath("$.messages[1].createdAt").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void deleteSession_shouldForbidOtherUsersSession() throws Exception {
        when(ownershipRepository.isOwner("s1", "userB")).thenReturn(false);
        when(ownershipRepository.hasOwner("s1")).thenReturn(true);

        mockMvc.perform(delete("/api/session/s1").param("userId", "userB"))
                .andExpect(status().isForbidden());

        verify(chatMemory, never()).clear("s1");
        verify(repository, never()).deleteByConversationId("s1");
    }

    @Test
    void deleteSession_shouldDeleteOwnersSession() throws Exception {
        when(ownershipRepository.isOwner("s1", "userA")).thenReturn(true);

        mockMvc.perform(delete("/api/session/s1").param("userId", "userA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true));

        verify(chatMemory).clear("s1");
        verify(repository).deleteByConversationId("s1");
        verify(ownershipRepository).remove("s1", "userA");
    }
}
