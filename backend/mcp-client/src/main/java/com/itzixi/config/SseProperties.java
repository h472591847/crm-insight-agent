package com.itzixi.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * SSE 通道管理配置。
 * <p>
 * 集中管理心跳间隔、派发线程数、写线程数与写队列容量，避免参数散落在代码里。
 * 默认值与历史行为一致（25s 心跳），可通过 {@code sse.*} 覆盖。
 * </p>
 *
 * @author oliver
 */
@ConfigurationProperties(prefix = "sse")
public class SseProperties {

    /** 心跳相关配置。 */
    private final Heartbeat heartbeat = new Heartbeat();

    /** 心跳写操作线程池配置。 */
    private final WritePool writePool = new WritePool();

    public Heartbeat getHeartbeat() {
        return heartbeat;
    }

    public WritePool getWritePool() {
        return writePool;
    }

    /**
     * 心跳调度配置。
     */
    public static class Heartbeat {

        /** 两次心跳的间隔。默认 25s，小于部署侧 nginx SSE location 的 3600s 与普通 location 的 120s。 */
        private Duration interval = Duration.ofSeconds(25);

        /** 派发线程数：只负责把写任务投递到写线程池，不做阻塞写，1 个足够。 */
        private int dispatchThreads = 1;

        /** 每 N 轮心跳输出一次汇总日志；25s * 12 ≈ 5 分钟。设为 0 表示关闭汇总日志。 */
        private int summaryEveryRounds = 12;

        public Duration getInterval() {
            return interval;
        }

        public void setInterval(Duration interval) {
            this.interval = interval;
        }

        public int getDispatchThreads() {
            return dispatchThreads;
        }

        public void setDispatchThreads(int dispatchThreads) {
            this.dispatchThreads = dispatchThreads;
        }

        public int getSummaryEveryRounds() {
            return summaryEveryRounds;
        }

        public void setSummaryEveryRounds(int summaryEveryRounds) {
            this.summaryEveryRounds = summaryEveryRounds;
        }
    }

    /**
     * 心跳写线程池配置。
     */
    public static class WritePool {

        /** 写线程数：半死连接最多占住这么多线程，不再拖累其他通道。 */
        private int threads = 2;

        /** 有界队列容量：队列满即判定该通道“写不动了”，主动清理。 */
        private int queueCapacity = 256;

        public int getThreads() {
            return threads;
        }

        public void setThreads(int threads) {
            this.threads = threads;
        }

        public int getQueueCapacity() {
            return queueCapacity;
        }

        public void setQueueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
        }
    }
}
