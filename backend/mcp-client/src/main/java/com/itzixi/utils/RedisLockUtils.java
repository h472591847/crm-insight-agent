package com.itzixi.utils;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Redisson 分布式锁工具类。
 * <p>
 * 提供声明式的"加锁 - 执行 - 解锁"模板，简化业务代码中的 tryLock / unlock 样板。
 * 所有解锁逻辑都在 {@code finally} 中，且只在"当前线程确实持有锁"时才释放，
 * 避免误释放其他线程的锁或解锁一个未持有的锁。
 * </p>
 * <h3>典型用法</h3>
 * <pre>{@code
 * // 无返回值
 * RedisLockUtils.execute("chat:lock:" + conversationId, () -> doHeavyWork());
 *
 * // 带返回值
 * String result = RedisLockUtils.execute("chat:lock:" + conversationId, () -> fetchSomething());
 * }</pre>
 *
 * <p>线程安全：此类所有静态方法都是线程安全的，RedissonClient 本身在多线程下也是线程安全的。</p>
 *
 * @author oliver
 * @since 1.0.0
 */
public final class RedisLockUtils {

    private static final Logger log = LoggerFactory.getLogger(RedisLockUtils.class);

    /** 默认 waitTime（0 = 不等待，拿不到锁立即返回） */
    private static final long DEFAULT_WAIT_TIME = 0L;
    /** 默认 leaseTime（-1 = 不自动过期，业务结束后手动 unlock） */
    private static final long DEFAULT_LEASE_TIME = -1L;

    /** 工具类禁止实例化 */
    private RedisLockUtils() {
        throw new UnsupportedOperationException("RedisLockUtils is a utility class, cannot be instantiated");
    }

    /**
     * 尝试获取分布式锁并执行回调（无返回值）。
     * <p>
     * 默认行为：waitTime=0（不等待，立即返回），leaseTime=-1（不自动过期，业务完成后手动释放）。
     * 如果获取锁失败则快速跳过，不阻塞调用方。
     * </p>
     *
     * @param redissonClient Redisson 客户端
     * @param lockKey        锁 key（建议包含业务前缀，如 "chat:lock:xxx"）
     * @param action         持锁期间执行的动作
     * @return 是否成功获取锁并执行
     */
    public static boolean execute(RedissonClient redissonClient, String lockKey, Runnable action) {
        return executeWithResult(redissonClient, lockKey, () -> {
            action.run();
            return null;
        }) != null;
    }

    /**
     * 尝试获取分布式锁并执行回调（带返回值）。
     * <p>
     * 默认 waitTime=0、leaseTime=-1。获取锁失败时返回 {@code null}，调用方需自行处理"未获取锁"的降级场景。
     * </p>
     *
     * @param redissonClient Redisson 客户端
     * @param lockKey        锁 key
     * @param action         持锁期间执行的动作
     * @param <T>            返回值类型
     * @return 动作返回值；若未获取到锁则返回 {@code null}
     */
    public static <T> T executeWithResult(RedissonClient redissonClient, String lockKey, Supplier<T> action) {
        return execute(redissonClient, lockKey, DEFAULT_WAIT_TIME, DEFAULT_LEASE_TIME, TimeUnit.SECONDS, action);
    }

    /**
     * 完整参数版本：自定义 waitTime、leaseTime 和时间单位。
     *
     * @param redissonClient Redisson 客户端
     * @param lockKey        锁 key
     * @param waitTime       等待获取锁的时间，超时则放弃
     * @param leaseTime      锁的自动过期时间（-1 表示不自动过期）
     * @param unit           时间单位
     * @param action         持锁期间执行的动作
     * @param <T>            返回值类型
     * @return 动作返回值；若未获取到锁则返回 {@code null}
     */
    public static <T> T execute(RedissonClient redissonClient,
                                String lockKey,
                                long waitTime,
                                long leaseTime,
                                TimeUnit unit,
                                Supplier<T> action) {
        RLock lock = redissonClient.getLock(lockKey);
        boolean locked;
        try {
            locked = lock.tryLock(waitTime, leaseTime, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("获取分布式锁被中断, lockKey={}", lockKey);
            return null;
        }
        if (!locked) {
            log.debug("获取分布式锁失败（可能被其他线程持有）, lockKey={}", lockKey);
            return null;
        }
        try {
            return action.get();
        } finally {
            // 只有当前线程持有时才释放，避免误释放其他请求的锁
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
