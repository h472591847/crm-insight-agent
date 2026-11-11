package com.itzixi.utils;

import com.itzixi.enums.SseRemoveReason;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * SSE 通道指标。
 * <p>
 * 复用 Micrometer 现成组件，暴露三个指标：
 * <ul>
 *     <li>{@code sse.channel.removed}（Counter，标签 reason）：通道移除次数，正常完成与判死都在内</li>
 *     <li>{@code sse.channel.alive}（Timer，标签 reason）：通道存活时长，按移除原因区分</li>
 *     <li>{@code sse.channels.active}（Gauge）：当前活跃通道数</li>
 * </ul>
 * 标签只允许 {@link SseRemoveReason#getTag()} 这类低基数取值，
 * 禁止把 channelKey / userId / sessionId 写进标签。
 * </p>
 *
 * @author oliver
 */
@Component
public class SseChannelMetrics {

    private final MeterRegistry meterRegistry;

    /** 按移除原因预注册，避免每次移除都走一遍注册表查找。 */
    private final Map<SseRemoveReason, Counter> removedCounters = new EnumMap<>(SseRemoveReason.class);

    private final Map<SseRemoveReason, Timer> aliveTimers = new EnumMap<>(SseRemoveReason.class);

    public SseChannelMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        for (SseRemoveReason reason : SseRemoveReason.values()) {
            removedCounters.put(reason, Counter.builder("sse.channel.removed")
                    .tag("reason", reason.getTag())
                    .description("SSE 通道被移除的次数（按原因区分）")
                    .register(meterRegistry));
            aliveTimers.put(reason, Timer.builder("sse.channel.alive")
                    .tag("reason", reason.getTag())
                    .description("SSE 通道从建立到被移除的存活时长（按原因区分）")
                    .register(meterRegistry));
        }
    }

    /**
     * 注册活跃通道数 Gauge，取值直接读通道注册表。
     *
     * @param activeChannelSupplier 注册表 size 的取值函数
     */
    public void registerActiveChannelGauge(Supplier<Number> activeChannelSupplier) {
        Gauge.builder("sse.channels.active", activeChannelSupplier, supplier -> supplier.get().doubleValue())
                .description("当前活跃的 SSE 通道数")
                .register(meterRegistry);
    }

    /**
     * 记录一次通道移除。
     *
     * @param reason      移除原因
     * @param aliveMillis 从建立到移除的存活毫秒数
     */
    public void channelRemoved(SseRemoveReason reason, long aliveMillis) {
        removedCounters.get(reason).increment();
        aliveTimers.get(reason).record(Duration.ofMillis(Math.max(0L, aliveMillis)));
    }
}
