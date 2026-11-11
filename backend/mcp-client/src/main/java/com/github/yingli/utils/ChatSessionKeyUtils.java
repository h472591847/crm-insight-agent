package com.github.yingli.utils;

import com.github.yingli.constants.ChatConstants;
import org.apache.commons.lang3.StringUtils;

/**
 * 聊天会话键解析工具。
 * <p>
 * 约定：
 * <ul>
 *     <li>conversationId：sessionId 非空时直接使用；否则回退 {@code legacy:{userId}}；两者都为空时回退默认值 "default"。</li>
 *     <li>SSE channelKey：sessionId 非空时使用 {@code userId:sessionId}，否则只使用 userId。</li>
 * </ul>
 * 纯静态方法，便于单元测试。
 *
 * @author oliver
 */
public final class ChatSessionKeyUtils {

    /** Spring AI 1.1.6+ 移除了 ChatMemory.DEFAULT_CONVERSATION_ID，原值就是 "default"。 */
    static final String DEFAULT_CONVERSATION_ID = "default";

    private ChatSessionKeyUtils() {
        throw new UnsupportedOperationException("ChatSessionKeyUtils is a utility class, cannot be instantiated");
    }

    /**
     * 解析对话记忆使用的 conversationId。
     */
    public static String resolveConversationId(String sessionId, String currentUserName) {
        if (StringUtils.isNotBlank(sessionId)) {
            return sessionId.trim();
        }
        if (StringUtils.isNotBlank(currentUserName)) {
            return ChatConstants.LEGACY_SESSION_PREFIX + currentUserName.trim();
        }
        return DEFAULT_CONVERSATION_ID;
    }

    /**
     * 解析 SSE 通道 key。
     */
    public static String resolveChannelKey(String userId, String sessionId) {
        String resolvedUserId = StringUtils.isBlank(userId) ? "anonymous" : userId.trim();
        if (StringUtils.isBlank(sessionId)) {
            return resolvedUserId;
        }
        return resolvedUserId + ChatConstants.SSE_CHANNEL_SEPARATOR + sessionId.trim();
    }
}
