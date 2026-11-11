package com.itzixi.metrics.exception;

/**
 * @Description 指标链路的语义化异常：错误码 + 面向模型的可读原因。
 * 查询失败/口径未确认/参数非法必须区分返回，模型不得据此编造数字。
 * @Author crm-agent
 */
public class MetricException extends RuntimeException {

    /**
     * 错误码。调用方据此判断是“没数据”还是“没权限/口径没定/参数错”。
     */
    public enum Code {
        /** 请求参数非法（未知编码、日期格式、区间倒置、跨度超限等） */
        INVALID_PARAM,
        /** 指标未注册 */
        METRIC_NOT_FOUND,
        /** 指标口径未业务确认，拒绝执行 */
        METRICS_NOT_CONFIRMED,
        /** 跨统计粒度混算，被编译器拦截 */
        GRAIN_MISMATCH,
        /** SQL 被安全闸门拒绝 */
        SQL_REJECTED,
        /** 查询执行失败（DB 异常等） */
        QUERY_FAILED
    }

    private final Code code;

    public MetricException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public MetricException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public Code getCode() {
        return code;
    }

    /**
     * 参数非法的便捷构造（沿用 Phase 1 服务端校验风格：给出可直接展示给用户的中文原因）
     */
    public static MetricException invalidParam(String message) {
        return new MetricException(Code.INVALID_PARAM, message);
    }
}
