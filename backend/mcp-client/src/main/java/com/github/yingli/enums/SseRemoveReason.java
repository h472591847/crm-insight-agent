package com.github.yingli.enums;


import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * SSE 通道被移除的原因。
 * <p>
 * 仅用于日志与指标标签，{@code tag} 取值固定且基数极低，可直接作为指标标签。
 * </p>
 *
 * @author oliver
 */
@Getter
@AllArgsConstructor
public enum SseRemoveReason {

    COMPLETED("completed", "连接正常完成或客户端主动断开", false),
    TIMEOUT("timeout", "SseEmitter 超时", false),
    IO_ERROR("io_error", "写入时抛出 IOException（含 AsyncRequestNotUsableException）", true),
    SEND_ERROR("send_error", "写入时抛出其他异常", true),
    WRITE_STALLED("write_stalled", "心跳写任务被拒绝，判定为写不动了", true),

    ;

    private final String tag;
    private final String desc;
    /** 是否属于“判死”（需要关注的异常清理），正常完成与超时不算。 */
    private final boolean abnormal;
}
