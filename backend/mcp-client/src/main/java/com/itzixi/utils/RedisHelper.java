package com.itzixi.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Redis 常用操作封装（基于 {@link StringRedisTemplate}）。
 * <p>
 * 本项目统一使用 StringRedisTemplate（存 value 为 String，方便与 JSON 序列化后的 ChatMemory 消息协作）。
 * 类职责聚焦在：
 * <ul>
 *     <li>Redis LIST 操作（会话消息存储的核心数据结构）</li>
 *     <li>KEY 管理（前缀匹配、TTL、删除）</li>
 *     <li>VALUE 读写（String 类型通用存取）</li>
 * </ul>
 * 分布式锁能力由 {@link RedisLockUtils} 独立提供，不混入此类。
 * </p>
 *
 * <h3>Key 命名约定</h3>
 * 所有 Redis Key 都应带业务前缀，避免与其他业务或系统 Redis 冲突。
 * 本项目使用的前缀示例：
 * <pre>
 * chat:memory:{conversationId}   // ChatMemory 消息列表
 * chat:lock:{conversationId}     // 分布式锁
 * lee-vector-index               // Spring AI RedisVectorStore 向量索引（由框架自动管理）
 * </pre>
 *
 * @author oliver
 * @since 1.0.0
 */
@Component
public class RedisHelper {

    private static final Logger log = LoggerFactory.getLogger(RedisHelper.class);

    private final StringRedisTemplate redisTemplate;

    public RedisHelper(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    // ==================== LIST 操作 ====================

    /**
     * 获取 LIST 指定下标的元素。
     *
     * @param key   Redis key
     * @param index 下标（0 = 头部，-1 = 尾部）
     * @return 元素值；key 不存在或越界返回 {@code null}
     */
    public String lIndex(String key, long index) {
        return redisTemplate.opsForList().index(key, index);
    }

    /**
     * 获取 LIST 长度。
     *
     * @param key Redis key
     * @return 长度；key 不存在返回 0
     */
    public long lSize(String key) {
        Long size = redisTemplate.opsForList().size(key);
        return size != null ? size : 0L;
    }

    /**
     * 获取 LIST 指定范围的元素（闭区间，0 到 -1 表示全部）。
     *
     * @param key   Redis key
     * @param start 起始下标
     * @param end   结束下标
     * @return 元素列表；key 不存在返回空列表
     */
    public List<String> lRange(String key, long start, long end) {
        List<String> result = redisTemplate.opsForList().range(key, start, end);
        return result != null ? result : Collections.emptyList();
    }

    /**
     * 从尾部批量推入元素（LIST 追加）。
     *
     * @param key    Redis key
     * @param values 待推入的元素
     */
    public void lRightPushAll(String key, Collection<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        redisTemplate.opsForList().rightPushAll(key, values.toArray(new String[0]));
    }

    /**
     * 先清空 LIST 再尾部推入（等价于"覆盖写入"，保证旧数据不残留）。
     *
     * @param key    Redis key
     * @param values 新的完整列表元素
     */
    public void lReplaceAll(String key, Collection<String> values) {
        delete(key);
        lRightPushAll(key, values);
    }

    // ==================== VALUE 操作 ====================

    /**
     * 设置 String 值，无过期时间。
     */
    public void set(String key, String value) {
        redisTemplate.opsForValue().set(key, value);
    }

    /**
     * 设置 String 值，带过期时间。
     */
    public void set(String key, String value, Duration ttl) {
        redisTemplate.opsForValue().set(key, value, ttl);
    }

    /**
     * 设置 String 值，带过期时间（指定时间单位）。
     */
    public void set(String key, String value, long timeout, TimeUnit unit) {
        redisTemplate.opsForValue().set(key, value, timeout, unit);
    }

    /**
     * 获取 String 值。
     *
     * @return 值；key 不存在返回 {@code null}
     */
    public String get(String key) {
        return redisTemplate.opsForValue().get(key);
    }

    /**
     * 仅当 key 不存在时写入（SETNX 语义），并设置过期时间。
     * <p>用于会话归属的"先到先得"抢占，避免并发下多个用户互相覆盖归属。</p>
     *
     * @return {@code true} 表示本次写入成功（key 原本不存在）；{@code false} 表示 key 已存在
     */
    public boolean setIfAbsent(String key, String value, Duration ttl) {
        Boolean result = redisTemplate.opsForValue().setIfAbsent(key, value, ttl);
        return Boolean.TRUE.equals(result);
    }

    // ==================== SET 操作 ====================

    /**
     * 向 SET 添加成员，并对 SET 设置过期时间（重复调用会刷新 TTL）。
     */
    public void addToSet(String key, String value, Duration ttl) {
        redisTemplate.opsForSet().add(key, value);
        redisTemplate.expire(key, ttl);
    }

    /**
     * 从 SET 移除成员。
     */
    public void removeFromSet(String key, String value) {
        redisTemplate.opsForSet().remove(key, value);
    }

    /**
     * 获取 SET 全部成员。
     *
     * @return 成员集合；key 不存在返回空集合
     */
    public Set<String> members(String key) {
        Set<String> result = redisTemplate.opsForSet().members(key);
        return result != null ? result : Collections.emptySet();
    }

    // ==================== KEY 操作 ====================

    /**
     * 检查 key 是否存在。
     */
    public boolean exists(String key) {
        Boolean result = redisTemplate.hasKey(key);
        return Boolean.TRUE.equals(result);
    }

    /**
     * 删除指定 key。
     */
    public void delete(String key) {
        redisTemplate.delete(key);
    }

    /**
     * 批量删除指定 key。
     */
    public void delete(Collection<String> keys) {
        redisTemplate.delete(keys);
    }

    /**
     * 为 key 设置过期时间。
     */
    public void expire(String key, Duration ttl) {
        redisTemplate.expire(key, ttl);
    }

    /**
     * 根据前缀匹配所有 key（通配符查询）。
     * <p>
     * <b>性能说明</b>：{@code keys*} 命令在 Redis 集群/大量 key 场景下可能阻塞。
     * 本项目场景（chat:memory:* key 数量可控）下安全；如将来 key 数量膨胀，
     * 应改用 Redis SCAN + Iterator 分批处理。
     * </p>
     *
     * @param pattern 匹配模式（如 "chat:memory:*"）
     * @return 匹配到的 key 集合
     */
    public List<String> keys(String pattern) {
        var matched = redisTemplate.keys(pattern);
        if (matched == null) {
            return Collections.emptyList();
        }
        return new java.util.ArrayList<>(matched);
    }
}
