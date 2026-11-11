package com.itzixi.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 会话键解析的纯单元测试，不依赖 Spring 容器、数据库或模型。
 */
class ChatSessionKeyUtilsTest {

    @Test
    void resolveConversationId_shouldUseSessionIdWhenPresent() {
        assertEquals("session-1",
                ChatSessionKeyUtils.resolveConversationId("session-1", "user-1"));
    }

    @Test
    void resolveConversationId_shouldFallbackToLegacyUserWhenSessionBlank() {
        assertEquals("legacy:user-1",
                ChatSessionKeyUtils.resolveConversationId(null, "user-1"));
        assertEquals("legacy:user-1",
                ChatSessionKeyUtils.resolveConversationId("  ", "user-1"));
    }

    @Test
    void resolveConversationId_shouldFallbackToDefaultWhenBothBlank() {
        assertEquals(ChatSessionKeyUtils.DEFAULT_CONVERSATION_ID,
                ChatSessionKeyUtils.resolveConversationId(null, null));
        assertEquals(ChatSessionKeyUtils.DEFAULT_CONVERSATION_ID,
                ChatSessionKeyUtils.resolveConversationId("", " "));
    }

    @Test
    void resolveConversationId_shouldTrimSessionId() {
        assertEquals("session-1",
                ChatSessionKeyUtils.resolveConversationId("  session-1  ", "user-1"));
    }

    @Test
    void resolveChannelKey_shouldIncludeSessionWhenPresent() {
        assertEquals("user-1:session-1",
                ChatSessionKeyUtils.resolveChannelKey("user-1", "session-1"));
    }

    @Test
    void resolveChannelKey_shouldOnlyUseUserWhenSessionBlank() {
        assertEquals("user-1",
                ChatSessionKeyUtils.resolveChannelKey("user-1", null));
        assertEquals("user-1",
                ChatSessionKeyUtils.resolveChannelKey("user-1", " "));
    }

    @Test
    void resolveChannelKey_shouldFallbackToAnonymousWhenUserBlank() {
        assertEquals("anonymous:session-1",
                ChatSessionKeyUtils.resolveChannelKey(null, "session-1"));
        assertEquals("anonymous",
                ChatSessionKeyUtils.resolveChannelKey(null, null));
    }
}
