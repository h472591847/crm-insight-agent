package com.itzixi.utils;

import com.itzixi.config.SseProperties;
import com.itzixi.enums.SseRemoveReason;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SSE 通道判死逻辑单测：验证移除原因、指标计数与拒绝策略路径。
 *
 * @author oliver
 */
class SSEServerTest {

    private SimpleMeterRegistry meterRegistry;

    private SSEServer sseServer;

    @AfterEach
    void tearDown() {
        if (sseServer != null) {
            sseServer.destroy();
        }
    }

    @Test
    @DisplayName("心跳写抛 IOException 时判死，reason=io_error，并主动关闭连接")
    void ioExceptionMarksChannelDead() {
        sseServer = newServer(defaultProperties());
        FailingEmitter emitter = new FailingEmitter(new IOException("Connection reset by peer"));
        sseServer.connect("user-1:session-1", emitter);

        sseServer.sendHeartbeat("user-1:session-1", emitter);

        assertThat(removedCount("io_error")).isEqualTo(1.0);
        assertThat(aliveTimerCount("io_error")).isEqualTo(1);
        assertThat(removedCount("completed")).isZero();
        assertThat(activeChannelGauge()).isZero();
        // 服务端主动判死必须关闭响应，让 nginx / 浏览器立刻感知断开
        assertThat(emitter.completeWithErrorCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("写线程被占满且队列满时，被拒绝的通道按 write_stalled 判死")
    void rejectedWriteMarksChannelStalled() throws InterruptedException {
        SseProperties properties = defaultProperties();
        properties.getWritePool().setThreads(1);
        properties.getWritePool().setQueueCapacity(1);
        sseServer = newServer(properties);

        CountDownLatch writeStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        sseServer.connect("blocked", new BlockingEmitter(writeStarted, release));
        sseServer.dispatchHeartbeats();
        assertThat(writeStarted.await(2, TimeUnit.SECONDS)).isTrue();

        // 3 个通道、1 个写线程、容量 1 的队列：1 个排队，另外 2 个必然被拒绝
        sseServer.connect("queued", new SseEmitter(0L));
        sseServer.connect("rejected", new SseEmitter(0L));
        try {
            sseServer.dispatchHeartbeats();
            assertThat(removedCount("write_stalled")).isEqualTo(2.0);
            // 3 个通道里恰好摘除 2 个，剩下的那个仍在注册表中
            assertThat(activeChannelGauge()).isEqualTo(1.0);
        } finally {
            release.countDown();
        }
    }

    @Test
    @DisplayName("正常完成只记 completed，不记判死，也不重复关闭连接")
    void completionIsNotCountedAsJudgedDead() {
        sseServer = newServer(defaultProperties());
        TrackingEmitter emitter = new TrackingEmitter();
        sseServer.connect("user-2", emitter);
        assertThat(activeChannelGauge()).isEqualTo(1.0);

        sseServer.removeOnCallback("user-2", emitter, SseRemoveReason.COMPLETED);

        assertThat(removedCount("completed")).isEqualTo(1.0);
        assertThat(removedCount("io_error")).isZero();
        assertThat(removedCount("send_error")).isZero();
        assertThat(removedCount("write_stalled")).isZero();
        assertThat(activeChannelGauge()).isZero();
        // 回调来源的连接已由容器结束，不能再关闭一次
        assertThat(emitter.completeWithErrorCount()).isZero();
        assertThat(emitter.completeCount()).isZero();
    }

    @Test
    @DisplayName("判死后迟到的错误回调只跳过清理，不重复计数也不会二次关闭")
    void lateCallbackAfterJudgedDeadIsIgnored() {
        sseServer = newServer(defaultProperties());
        FailingEmitter emitter = new FailingEmitter(new IOException("Broken pipe"));
        sseServer.connect("user-3:session-3", emitter);

        sseServer.sendHeartbeat("user-3:session-3", emitter);
        // 模拟 completeWithError 之后容器才回调 onError：此时通道已被摘除
        sseServer.removeOnCallback("user-3:session-3", emitter, SseRemoveReason.SEND_ERROR);

        assertThat(emitter.completeWithErrorCount()).isEqualTo(1);
        assertThat(removedCount("io_error")).isEqualTo(1.0);
        assertThat(removedCount("send_error")).isZero();
        assertThat(aliveTimerCount("io_error")).isEqualTo(1);
        assertThat(aliveTimerCount("send_error")).isZero();
    }

    private SSEServer newServer(SseProperties properties) {
        meterRegistry = new SimpleMeterRegistry();
        return new SSEServer(properties, new SseChannelMetrics(meterRegistry));
    }

    /** 心跳间隔调到 1 小时，测试期间不会触发后台调度。 */
    private SseProperties defaultProperties() {
        SseProperties properties = new SseProperties();
        properties.getHeartbeat().setInterval(Duration.ofHours(1));
        return properties;
    }

    private double removedCount(String reasonTag) {
        Counter counter = meterRegistry.find("sse.channel.removed").tag("reason", reasonTag).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private long aliveTimerCount(String reasonTag) {
        Timer timer = meterRegistry.find("sse.channel.alive").tag("reason", reasonTag).timer();
        return timer == null ? 0L : timer.count();
    }

    private double activeChannelGauge() {
        return meterRegistry.find("sse.channels.active").gauge() == null
                ? 0.0
                : meterRegistry.find("sse.channels.active").gauge().value();
    }

    /** 记录关闭动作的 emitter：用于断言「连接最终由谁关闭」。 */
    private static class TrackingEmitter extends SseEmitter {

        private final AtomicInteger completeWithErrorCount = new AtomicInteger();

        private final AtomicInteger completeCount = new AtomicInteger();

        TrackingEmitter() {
            super(0L);
        }

        @Override
        public void completeWithError(Throwable failure) {
            completeWithErrorCount.incrementAndGet();
        }

        @Override
        public void complete() {
            completeCount.incrementAndGet();
        }

        int completeWithErrorCount() {
            return completeWithErrorCount.get();
        }

        int completeCount() {
            return completeCount.get();
        }
    }

    /** 发送即抛 IOException，模拟连接已断。 */
    private static class FailingEmitter extends TrackingEmitter {

        private final IOException error;

        FailingEmitter(IOException error) {
            this.error = error;
        }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            throw error;
        }
    }

    /** 发送后阻塞，模拟写线程被半死连接占住。 */
    private static class BlockingEmitter extends SseEmitter {

        private final CountDownLatch writeStarted;

        private final CountDownLatch release;

        BlockingEmitter(CountDownLatch writeStarted, CountDownLatch release) {
            super(0L);
            this.writeStarted = writeStarted;
            this.release = release;
        }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            writeStarted.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("心跳写线程被中断", e);
            }
        }
    }
}
