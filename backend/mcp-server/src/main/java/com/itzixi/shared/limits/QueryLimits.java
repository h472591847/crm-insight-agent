package com.itzixi.shared.limits;

/**
 * @Description 查询侧约束：时间跨度、默认窗口、行数硬上限与排序方向。两个域（metrics / adhoc）共用。
 * @Author crm-agent
 */
public final class QueryLimits {

    private QueryLimits() {
    }

    /**
     * 时间跨度上限（天）。产品 2026-10-29 确认看板默认时间范围＝近一年半（≈548 天），
     * 原 Phase 1 的 366 天上限会拦住默认区间，放宽到 550 天并保留兼并边界容差。
     */
    public static final int MAX_RANGE_DAYS = 550;

    /** 缺省时间窗口（月）：看板默认近一年半（2026-10-29 产品确认，取代旧“当前自然月”） */
    public static final int DEFAULT_RANGE_MONTHS = 18;

    /** 指标级默认窗口（defaultWindowMonths）允许上限（月） */
    public static final int MAX_DEFAULT_WINDOW_MONTHS = 24;

    /** 单次查询返回行数的全局硬上限（指标级 limitMax 不得超过它） */
    public static final int HARD_MAX_ROWS = 2000;

    /** 排序方向白名单 */
    public static final String SORT_ASC = "asc";
    public static final String SORT_DESC = "desc";
}
