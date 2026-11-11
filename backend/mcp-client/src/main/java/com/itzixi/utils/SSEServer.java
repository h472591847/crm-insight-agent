package com.itzixi.utils;


import com.itzixi.config.SseProperties;
import com.itzixi.enums.SSEMsgType;
import com.itzixi.enums.SseRemoveReason;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SSE（Server-Sent Events）连接管理。
 * <p>
 * 职责：
 * <ul>
 *     <li>建立 / 销毁 / 替换 SSE 通道（按 userId:sessionId 分通道）</li>
 *     <li>发送业务消息（add / finish / message）</li>
 *     <li>定时心跳：每 {@code sse.heartbeat.interval} 向所有活跃通道发送 SSE comment，
 *         防止 nginx / 浏览器 / 中间层因“长时间无数据”主动掐断连接</li>
 * </ul>
 * 线程模型：
 * <ul>
 *     <li>调度线程池（{@code sse-heartbeat-dispatch-*}）只做派发，不做阻塞写</li>
 *     <li>写线程池（{@code sse-heartbeat-write-*}，有界队列 + AbortPolicy）执行实际写；
 *         队列满说明写线程已被半死连接占满，被拒绝的通道直接按 {@link SseRemoveReason#WRITE_STALLED} 判死</li>
 * </ul>
 * 生命周期：本类由 Spring 管理（{@code @Component}），注册表、时间戳、线程池都是实例字段，
 * 只在 {@link #connect(String, SseEmitter)} 写入注册表、经 {@link #removeOnCallback}（回调来源）或
 * {@link #markDeadAndClose}（服务端主动判死）两个入口移除，{@link #destroy()} 随容器关闭停止线程池并清空状态。
 * <p>
 * 判死语义：<b>只有服务端主动判死才会关闭 HTTP 响应</b>（{@code completeWithError}），
 * 让 nginx 立即感知上游结束并转发断开，浏览器 EventSource 随后自动重连；
 * 回调来源（timeout / completion / error）的连接已由容器结束，再关闭只会重复触发完成动作。
 *
 * @author oliver
 */
@Slf4j
@Component
public class SSEServer {

    /** SseEmitter 超时时间 0L = 永不超时；死连接依赖心跳写失败或下一次业务写失败清理。 */
    private static final long SSE_TIMEOUT_NEVER = 0L;

    /** 心跳 comment 内容，浏览器 EventSource 会忽略 comment 帧。 */
    private static final String HEARTBEAT_COMMENT = "keep-alive";

    /** 心跳写线程池空闲线程存活时间：常驻线程，不需要回收。 */
    private static final long WRITE_THREAD_KEEP_ALIVE_SECONDS = 0L;

    private final SseProperties sseProperties;

    private final SseChannelMetrics channelMetrics;

    /** 通道注册表，key 为 userId 或 userId:sessionId。 */
    private final Map<String, SseEmitter> sseClients = new ConcurrentHashMap<>();

    /** 通道建立时间戳（毫秒），用于统计存活时长，与注册表同生命周期。 */
    private final Map<String, Long> channelConnectedAt = new ConcurrentHashMap<>();

    /** 心跳派发线程池：只投递写任务。 */
    private final ScheduledThreadPoolExecutor heartbeatScheduler;

    /** 心跳写线程池：有界队列 + AbortPolicy，拒绝即判死。 */
    private final ThreadPoolExecutor heartbeatWritePool;

    /** 已执行的心跳轮次，用于按轮次输出汇总日志。 */
    private final AtomicLong heartbeatRounds = new AtomicLong();

    /** 距上次汇总后被判死的通道数。 */
    private final AtomicLong removedSinceSummary = new AtomicLong();

    public SSEServer(SseProperties sseProperties, SseChannelMetrics channelMetrics) {
        Assert.isTrue(sseProperties.getHeartbeat().getInterval() != null
                        && !sseProperties.getHeartbeat().getInterval().isNegative()
                        && !sseProperties.getHeartbeat().getInterval().isZero(),
                "sse.heartbeat.interval 必须大于 0");
        Assert.isTrue(sseProperties.getHeartbeat().getDispatchThreads() > 0,
                "sse.heartbeat.dispatch-threads 必须大于 0");
        Assert.isTrue(sseProperties.getWritePool().getThreads() > 0,
                "sse.write-pool.threads 必须大于 0");
        Assert.isTrue(sseProperties.getWritePool().getQueueCapacity() > 0,
                "sse.write-pool.queue-capacity 必须大于 0");
        this.sseProperties = sseProperties;
        this.channelMetrics = channelMetrics;
        this.channelMetrics.registerActiveChannelGauge(sseClients::size);
        this.heartbeatScheduler = createHeartbeatScheduler();
        this.heartbeatWritePool = createHeartbeatWritePool();
        startHeartbeat();
    }

    // ==================== 生命周期 ====================

    private ScheduledThreadPoolExecutor createHeartbeatScheduler() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(
                sseProperties.getHeartbeat().getDispatchThreads(),
                new CustomizableThreadFactory("sse-heartbeat-dispatch-"));
        // 取消任务后立即移除，避免延迟任务残留
        executor.setRemoveOnCancelPolicy(true);
        // 关闭后不再执行已排队的延迟任务
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return executor;
    }

    private ThreadPoolExecutor createHeartbeatWritePool() {
        return new ThreadPoolExecutor(
                sseProperties.getWritePool().getThreads(),
                sseProperties.getWritePool().getThreads(),
                WRITE_THREAD_KEEP_ALIVE_SECONDS, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(sseProperties.getWritePool().getQueueCapacity()),
                new CustomizableThreadFactory("sse-heartbeat-write-"),
                new ThreadPoolExecutor.AbortPolicy());
    }

    private void startHeartbeat() {
        long intervalSeconds = sseProperties.getHeartbeat().getInterval().toSeconds();
        heartbeatScheduler.scheduleAtFixedRate(this::dispatchHeartbeats,
                intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        log.info("SSE心跳已启动, 间隔={}s, 派发线程数={}, 写线程数={}, 写队列容量={}",
                intervalSeconds, sseProperties.getHeartbeat().getDispatchThreads(),
                sseProperties.getWritePool().getThreads(), sseProperties.getWritePool().getQueueCapacity());
    }

    /**
     * 容器关闭时停止心跳线程池并清空通道状态。
     */
    @PreDestroy
    public void destroy() {
        heartbeatScheduler.shutdownNow();
        heartbeatWritePool.shutdownNow();
        int activeChannels = sseClients.size();
        sseClients.clear();
        channelConnectedAt.clear();
        log.info("SSE心跳已停止, 清理通道数={}", activeChannels);
    }

    // ==================== 连接管理 ====================

    /**
     * 创建 SSE 连接。
     *
     * @param channelKey userId 或 userId:sessionId
     * @return SseEmitter
     */
    public SseEmitter connect(String channelKey) {
        // 0L 表示永不超时：模型流式回答时长不可预估，有限超时会切断正常的长回答。
        // 代价是 onTimeout 实际不会触发（保留仅为兜底），死连接靠心跳写失败清理。
        return connect(channelKey, new SseEmitter(SSE_TIMEOUT_NEVER));
    }

    /**
     * 注册指定 emitter。包级可见，便于测试注入可控的 emitter 实现。
     *
     * @param channelKey 通道标识
     * @param emitter    连接实例
     * @return 传入的 emitter
     */
    SseEmitter connect(String channelKey, SseEmitter emitter) {
        // 三个回调进来时 emitter 已由容器结束，只能清理注册表，不能再关闭一次
        emitter.onTimeout(() -> removeOnCallback(channelKey, emitter, SseRemoveReason.TIMEOUT));
        emitter.onCompletion(() -> removeOnCallback(channelKey, emitter, SseRemoveReason.COMPLETED));
        emitter.onError(throwable -> {
            log.debug("SSE错误回调触发, 通道: {}, 信息: {}", channelKey, throwable.getMessage());
            removeOnCallback(channelKey, emitter, SseRemoveReason.SEND_ERROR);
        });

        channelConnectedAt.put(channelKey, System.currentTimeMillis());
        SseEmitter previous = sseClients.put(channelKey, emitter);
        if (previous != null) {
            closeQuietly(channelKey, previous);
        }
        log.info("SSE连接创建成功, 通道: {}, 当前通道数: {}", channelKey, sseClients.size());
        return emitter;
    }

    /**
     * 主动关闭被替换的旧连接，避免同一通道存在两个 emitter。
     */
    private void closeQuietly(String channelKey, SseEmitter previous) {
        try {
            previous.complete();
        } catch (Exception e) {
            log.debug("关闭旧SSE连接失败, 通道: {}, 信息: {}", channelKey, e.getMessage());
        }
    }

    /**
     * 回调来源的通道移除。
     * <p>
     * {@code onTimeout} / {@code onCompletion} / {@code onError} 触发时，emitter 已经由容器结束，
     * 此时再调 {@code complete()} / {@code completeWithError()} 会重复触发一次完成动作
     * （Spring 6.2 的这两个方法本身幂等，但 {@code handler.completeXxx()} 会被再执行一遍），
     * 因此这条路径只清理注册表与指标。
     * </p>
     */
    void removeOnCallback(String channelKey, SseEmitter emitter, SseRemoveReason reason) {
        remove(channelKey, emitter, reason, false);
    }

    /**
     * 服务端主动判死：注册表移除成功后关闭 HTTP 响应。
     * <p>
     * 关闭必须放在 compare-and-remove 之后：并发的「主动判死」与「迟到回调」只有一方能成功移除，
     * 失败方直接返回，因此旧连接的迟到回调不会误关同通道上的新连接，也不会重复计数。
     * </p>
     */
    void markDeadAndClose(String channelKey, SseEmitter emitter, SseRemoveReason reason) {
        remove(channelKey, emitter, reason, true);
    }

    /**
     * 通道移除的唯一实现。
     *
     * @param closeConnection 是否由服务端主动关闭 HTTP 响应。回调来源必须为 false
     */
    private void remove(String channelKey, SseEmitter emitter, SseRemoveReason reason, boolean closeConnection) {
        if (!sseClients.remove(channelKey, emitter)) {
            log.debug("SSE通道已被替换或已移除, 跳过清理, 通道: {}, 原因: {}", channelKey, reason.getTag());
            return;
        }
        Long connectedAt = channelConnectedAt.remove(channelKey);
        long aliveMillis = connectedAt == null ? 0L : System.currentTimeMillis() - connectedAt;
        channelMetrics.channelRemoved(reason, aliveMillis);
        boolean closed = closeConnection && closeWithError(channelKey, emitter, reason);
        if (reason.isAbnormal()) {
            removedSinceSummary.incrementAndGet();
            // 带「已主动关闭连接」标记：线上可据此对照 nginx 的 upstream 断开时间
            log.info("SSE通道判死并清理, 通道: {}, 原因: {}, 存活={}ms, 已主动关闭连接={}",
                    channelKey, reason.getTag(), aliveMillis, closed);
        } else {
            log.info("SSE通道正常关闭, 通道: {}, 原因: {}, 存活={}ms", channelKey, reason.getTag(), aliveMillis);
        }
    }

    /**
     * 主动结束 HTTP 响应：Spring 随之完成异步请求，nginx 感知上游结束后转发断开，
     * 浏览器 EventSource 约 3 秒后自动重连，不再停留在「静默的僵尸连接」上。
     *
     * @return 是否成功发起关闭
     */
    private boolean closeWithError(String channelKey, SseEmitter emitter, SseRemoveReason reason) {
        try {
            emitter.completeWithError(new IOException("SSE 通道已被服务端清理, 原因: " + reason.getTag()));
            return true;
        } catch (Exception e) {
            // 容器可能已先行结束该异步请求；关闭失败只影响连接回收时点，不影响注册表与指标的清理结果
            log.debug("主动关闭SSE连接失败, 通道: {}, 原因: {}, 类型: {}, 信息: {}",
                    channelKey, reason.getTag(), e.getClass().getSimpleName(), e.getMessage());
            return false;
        }
    }

    // ==================== 心跳 ====================

    /**
     * 心跳调度入口：只把各通道的写操作投递到写线程池，不在调度线程里做阻塞写。
     * <p>
     * 外层 catch(Throwable) 是必须的：{@code scheduleAtFixedRate} 的任务一旦抛出未捕获异常，
     * 该任务会被永久取消且没有后续日志，心跳会静默失效。
     * </p>
     */
    void dispatchHeartbeats() {
        try {
            for (Map.Entry<String, SseEmitter> entry : sseClients.entrySet()) {
                submitHeartbeat(entry.getKey(), entry.getValue());
            }
            logSummaryIfDue();
        } catch (Throwable t) {
            log.error("SSE心跳调度异常, 通道数: {}", sseClients.size(), t);
        }
    }

    /**
     * 投递单个通道的心跳写任务；写线程池已满说明写不动了，直接判死。
     */
    private void submitHeartbeat(String channelKey, SseEmitter emitter) {
        try {
            heartbeatWritePool.execute(() -> sendHeartbeat(channelKey, emitter));
        } catch (RejectedExecutionException e) {
            // 预期路径：写线程被半死连接占满且队列已满，不再占用派发线程
            markDeadAndClose(channelKey, emitter, SseRemoveReason.WRITE_STALLED);
        }
    }

    /**
     * 向单个通道发送心跳 comment。包级可见，便于单测直接验证判死原因。
     */
    void sendHeartbeat(String channelKey, SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event().comment(HEARTBEAT_COMMENT));
        } catch (IOException e) {
            log.debug("SSE心跳发现通道已断开, 通道: {}, 类型: {}, 原因: {}",
                    channelKey, e.getClass().getSimpleName(), e.getMessage());
            markDeadAndClose(channelKey, emitter, SseRemoveReason.IO_ERROR);
        } catch (Exception e) {
            log.debug("SSE心跳发送异常, 通道: {}, 类型: {}, 信息: {}",
                    channelKey, e.getClass().getSimpleName(), e.getMessage());
            markDeadAndClose(channelKey, emitter, SseRemoveReason.SEND_ERROR);
        }
    }

    /**
     * 按轮次输出汇总日志，给出“当前通道数 + 本轮判死数”。
     */
    private void logSummaryIfDue() {
        long round = heartbeatRounds.incrementAndGet();
        int summaryEveryRounds = sseProperties.getHeartbeat().getSummaryEveryRounds();
        if (summaryEveryRounds <= 0 || round % summaryEveryRounds != 0) {
            return;
        }
        log.info("SSE心跳汇总, 通道数={}, 本轮清理={}", sseClients.size(), removedSinceSummary.getAndSet(0L));
    }

    // ==================== 业务消息 ====================

    /**
     * 发送单条消息。
     *
     * @param channelKey userId 或 userId:sessionId
     * @param message    消息体
     * @param msgType    SSE 事件名
     */
    public void sendMsg(String channelKey, String message, SSEMsgType msgType) {
        SseEmitter sseEmitter = sseClients.get(channelKey);
        if (sseEmitter == null) {
            return;
        }
        sendEmitterMsg(sseEmitter, channelKey, message, msgType);
    }

    /**
     * 向所有已连接用户广播消息（测试用）。
     *
     * @param message 消息体
     */
    public void sendMsgToAllUsers(String message) {
        sseClients.forEach((channelKey, sseEmitter) ->
                sendEmitterMsg(sseEmitter, channelKey, message, SSEMsgType.MESSAGE));
    }

    /**
     * 发送消息（内部实现）。
     * <p>
     * 客户端主动断开属于正常生命周期，IOException 只记 DEBUG + 指标，不记 ERROR。
     * </p>
     */
    private void sendEmitterMsg(SseEmitter sseEmitter, String channelKey, String message, SSEMsgType msgType) {
        SseEmitter.SseEventBuilder eventBuilder = SseEmitter.event()
                .data(message)
                .id(channelKey)
                .name(msgType.getType());
        try {
            sseEmitter.send(eventBuilder);
        } catch (IOException e) {
            log.debug("SSE通道已断开, 通道: {}, 事件: {}, 类型: {}, 原因: {}",
                    channelKey, msgType.getType(), e.getClass().getSimpleName(), e.getMessage());
            markDeadAndClose(channelKey, sseEmitter, SseRemoveReason.IO_ERROR);
        } catch (Exception e) {
            log.debug("SSE发送异常, 通道: {}, 事件: {}, 类型: {}, 信息: {}",
                    channelKey, msgType.getType(), e.getClass().getSimpleName(), e.getMessage());
            markDeadAndClose(channelKey, sseEmitter, SseRemoveReason.SEND_ERROR);
        }
    }
}
