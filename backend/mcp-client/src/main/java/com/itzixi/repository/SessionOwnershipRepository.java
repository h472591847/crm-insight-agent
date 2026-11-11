package com.itzixi.repository;

import com.itzixi.constants.ChatConstants;
import com.itzixi.utils.RedisHelper;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 会话归属（sessionId → userId）仓库。
 * <p>
 * 为什么需要它：前端生成的 sessionId（UUID）本身不携带用户信息，
 * 而 Spring AI 的 {@code ChatMemoryRepository} 只认 conversationId，
 * 因此归属关系必须单独存储，不能靠 conversationId 前缀推导。
 * </p>
 *
 * <h3>Redis 结构</h3>
 * <pre>
 * chat:session:owner:{sessionId}   String，值 = userId，TTL 与记忆一致
 * chat:user:sessions:{userId}      SET，成员 = 该用户拥有的 sessionId，TTL 与记忆一致
 * </pre>
 * <p>
 * 归属 key 用于鉴权；用户索引 SET 用于"按用户列会话"，避免 {@code KEYS chat:memory:*} 全库扫描。
 * 归属 key 与索引 SET 都使用 SETNX / SET 语义：会话首次写入（聊天请求）时先到先得地登记归属，
 * 之后任何其他 userId 的读写都会被判定为越权。
 * </p>
 *
 * <h3>兼容旧数据</h3>
 * <p>
 * 旧客户端在 sessionId 为空时使用 {@code legacy:{userId}} 作为 conversationId，归属可由前缀推导，
 * 因此这类会话无需归属 key 也能鉴权（首次访问时惰性补登记）。
 * 历史 UUID 会话如果在本次升级前创建且没有归属记录，无法可靠判断归属，按"拒绝访问"处理。
 * </p>
 *
 * @author oliver
 * @since 1.2.0
 */
@Repository
public class SessionOwnershipRepository {

    private static final Logger log = LoggerFactory.getLogger(SessionOwnershipRepository.class);

    /** 会话归属登记结果 */
    public enum ClaimResult {
        /** 本次请求首次登记归属 */
        CLAIMED,
        /** 归属已存在且属于当前用户 */
        OWNED,
        /** 会话属于其他用户，拒绝访问 */
        FORBIDDEN
    }

    private final RedisHelper redisHelper;

    /** 与 RedisChatMemoryRepository 使用同一前缀，保证"记忆是否存在"的判断一致 */
    @Value("${app.chat.memory.redis.prefix:chat:memory:}")
    private String memoryPrefix;

    /** 归属 key / 用户索引的 TTL，与记忆 TTL 保持一致 */
    @Value("${app.chat.memory.redis.ttl-days:7}")
    private int ttlDays;

    public SessionOwnershipRepository(RedisHelper redisHelper) {
        this.redisHelper = redisHelper;
    }

    /**
     * 聊天写入路径：登记或校验会话归属。
     *
     * @param conversationId 记忆使用的会话标识（sessionId，或 legacy:{userId}）
     * @param userId         当前请求用户
     * @return 登记/校验结果
     */
    public ClaimResult claimOrVerify(String conversationId, String userId) {
        if (StringUtils.isBlank(conversationId) || StringUtils.isBlank(userId)) {
            return ClaimResult.FORBIDDEN;
        }
        String normalizedUserId = userId.trim();

        // legacy:{userId} 的归属可由命名推导，防止他人用别的 userId 抢占
        String legacyOwner = deriveLegacyOwner(conversationId);
        if (legacyOwner != null && !legacyOwner.equals(normalizedUserId)) {
            return ClaimResult.FORBIDDEN;
        }

        String owner = redisHelper.get(ownerKey(conversationId));
        if (owner != null) {
            if (!owner.equals(normalizedUserId)) {
                return ClaimResult.FORBIDDEN;
            }
            refreshOwnership(conversationId, normalizedUserId);
            return ClaimResult.OWNED;
        }

        boolean claimed = redisHelper.setIfAbsent(ownerKey(conversationId), normalizedUserId, ttl());
        if (claimed) {
            redisHelper.addToSet(userSessionsKey(normalizedUserId), conversationId, ttl());
            log.info("登记会话归属 conversationId={}, userId={}", conversationId, normalizedUserId);
            return ClaimResult.CLAIMED;
        }

        // SETNX 失败说明并发下已被其他请求抢先登记，再读一次确认
        String racedOwner = redisHelper.get(ownerKey(conversationId));
        if (racedOwner != null && racedOwner.equals(normalizedUserId)) {
            refreshOwnership(conversationId, normalizedUserId);
            return ClaimResult.OWNED;
        }
        return ClaimResult.FORBIDDEN;
    }

    /**
     * 读取/删除路径：判断当前用户是否有权访问该会话。
     * <p>legacy:{userId} 会话在首次访问时惰性补登记归属。</p>
     */
    public boolean isOwner(String conversationId, String userId) {
        if (StringUtils.isBlank(conversationId) || StringUtils.isBlank(userId)) {
            return false;
        }
        String normalizedUserId = userId.trim();
        String owner = redisHelper.get(ownerKey(conversationId));
        if (owner != null) {
            return owner.equals(normalizedUserId);
        }
        String legacyOwner = deriveLegacyOwner(conversationId);
        if (legacyOwner != null && legacyOwner.equals(normalizedUserId)) {
            refreshOwnership(conversationId, normalizedUserId);
            return true;
        }
        return false;
    }

    /**
     * 会话是否存在可识别的归属（用于区分 403 与 404）。
     */
    public boolean hasOwner(String conversationId) {
        if (StringUtils.isBlank(conversationId)) {
            return false;
        }
        return redisHelper.get(ownerKey(conversationId)) != null
                || deriveLegacyOwner(conversationId) != null;
    }

    /**
     * 列出某用户拥有的会话（从用户索引 SET 读取，不做全库 KEYS 扫描）。
     * <p>顺带清理两类失效成员：归属已不属于该用户的、以及记忆 key 已过期不存在的。</p>
     */
    public List<String> listSessions(String userId) {
        if (StringUtils.isBlank(userId)) {
            return new ArrayList<>();
        }
        String normalizedUserId = userId.trim();
        String userSessionsKey = userSessionsKey(normalizedUserId);

        Set<String> candidates = redisHelper.members(userSessionsKey);
        List<String> result = new ArrayList<>(candidates.size());
        for (String conversationId : candidates) {
            String owner = redisHelper.get(ownerKey(conversationId));
            boolean owned = owner != null
                    ? owner.equals(normalizedUserId)
                    : normalizedUserId.equals(deriveLegacyOwner(conversationId));
            if (!owned) {
                redisHelper.removeFromSet(userSessionsKey, conversationId);
                continue;
            }
            if (!redisHelper.exists(memoryPrefix + conversationId)) {
                // 记忆已过期，归属与索引一并清理，避免展示空会话
                redisHelper.removeFromSet(userSessionsKey, conversationId);
                redisHelper.delete(ownerKey(conversationId));
                continue;
            }
            if (owner == null) {
                refreshOwnership(conversationId, normalizedUserId);
            }
            result.add(conversationId);
        }

        // 旧客户端单通道会话（legacy:{userId}）可能未进索引，直接探测一次（无扫描成本）
        String legacyConversationId = ChatConstants.LEGACY_SESSION_PREFIX + normalizedUserId;
        if (!result.contains(legacyConversationId)
                && redisHelper.exists(memoryPrefix + legacyConversationId)) {
            refreshOwnership(legacyConversationId, normalizedUserId);
            result.add(legacyConversationId);
        }
        return result;
    }

    /**
     * 删除会话时同步清理归属与索引。
     */
    public void remove(String conversationId, String userId) {
        if (StringUtils.isBlank(conversationId)) {
            return;
        }
        redisHelper.delete(ownerKey(conversationId));
        if (StringUtils.isNotBlank(userId)) {
            redisHelper.removeFromSet(userSessionsKey(userId.trim()), conversationId);
        }
    }

    // ===== 内部工具 =====

    private void refreshOwnership(String conversationId, String userId) {
        // 归属 key 不存在时补写；已存在则只刷新 TTL（不覆盖其他用户）
        redisHelper.setIfAbsent(ownerKey(conversationId), userId, ttl());
        redisHelper.addToSet(userSessionsKey(userId), conversationId, ttl());
    }

    /**
     * 从 {@code legacy:{userId}} 形式的会话标识推导归属；非 legacy 形式返回 {@code null}。
     */
    static String deriveLegacyOwner(String conversationId) {
        String prefix = ChatConstants.LEGACY_SESSION_PREFIX;
        if (conversationId == null || !conversationId.startsWith(prefix)) {
            return null;
        }
        String owner = conversationId.substring(prefix.length());
        return StringUtils.isBlank(owner) ? null : owner;
    }

    private String ownerKey(String conversationId) {
        return ChatConstants.REDIS_KEY_SESSION_OWNER_PREFIX + conversationId;
    }

    private String userSessionsKey(String userId) {
        return ChatConstants.REDIS_KEY_USER_SESSIONS_PREFIX + userId;
    }

    private Duration ttl() {
        return Duration.ofDays(ttlDays > 0 ? ttlDays : ChatConstants.MEMORY_DEFAULT_TTL_DAYS);
    }
}
