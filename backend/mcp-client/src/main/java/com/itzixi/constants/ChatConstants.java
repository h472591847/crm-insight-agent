package com.itzixi.constants;

/**
 * CRM Agent 项目常量集中管理。
 * <p>
 * 规约：所有业务相关的魔法值都应集中到此类，禁止散落在业务代码中。
 * 命名规则：全大写下划线，见名知意。
 * </p>
 *
 * @author oliver
 * @since 1.0.0
 */
public final class ChatConstants {

    /** 工具类禁止实例化 */
    private ChatConstants() {
        throw new UnsupportedOperationException("ChatConstants cannot be instantiated");
    }

    // ==================== Redis Key ====================

    /** Redis ChatMemory LIST 的 key 前缀（完整 key: chat:memory:{conversationId}） */
    public static final String REDIS_KEY_CHAT_MEMORY_PREFIX = "chat:memory:";

    /** Redisson 分布式锁的 key 前缀（完整 key: chat:lock:{conversationId}） */
    public static final String REDIS_KEY_LOCK_PREFIX = "chat:lock:";

    /** SSEServer 消息通道分隔符（userId:sessionId） */
    public static final String SSE_CHANNEL_SEPARATOR = ":";

    /** 会话归属 key 前缀（完整 key: chat:session:owner:{sessionId}，值为 userId） */
    public static final String REDIS_KEY_SESSION_OWNER_PREFIX = "chat:session:owner:";

    /** 用户会话索引 SET 前缀（完整 key: chat:user:sessions:{userId}，成员为 sessionId） */
    public static final String REDIS_KEY_USER_SESSIONS_PREFIX = "chat:user:sessions:";

    // ==================== 对话记忆 ====================

    /** 消息窗口大小（每个会话保留最近 N 条消息） */
    public static final int MEMORY_WINDOW_SIZE = 20;

    /** ChatMemory 会话摘要最大截断长度 */
    public static final int SUMMARY_MAX_LEN = 30;

    /** ChatMemory 在 Redis 中的默认过期时间（天） */
    public static final long MEMORY_DEFAULT_TTL_DAYS = 7L;

    // ==================== 会话 API ====================

    /** 历史消息拉取默认 limit */
    public static final int DEFAULT_HISTORY_LIMIT = 50;

    /** 新会话 UUID 的 legacy 回退前缀 */
    public static final String LEGACY_SESSION_PREFIX = "legacy:";
}
