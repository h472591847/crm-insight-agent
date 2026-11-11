package com.itzixi.metrics.model;

import java.util.List;

/**
 * @Description 指标定义（Metrics-as-Code 的核心载体）。一个指标 = 一段 YAML 配置，
 * 由 {@link com.itzixi.metrics.compile.MetricSqlCompiler} 确定性编译为 SQL，不需要新增 Mapper 或手写 SQL。
 * <p>
 * 口径（{@link #caliber}）是本指标的唯一事实来源；{@link Status#UNCONFIRMED} 的指标
 * 一律拒绝执行，防止“口径未确认却输出权威数字”。
 * @Author crm-agent
 */
public record MetricDefinition(
        /** 指标编码，全局唯一，UPPER_SNAKE，如 STATION_SALES */
        String metric,
        /** 中文指标名，进入结果与模型可见的元数据 */
        String title,
        /** 业务域：member / order / delivery / finance */
        String domain,
        /** 口径确认状态 */
        Status status,
        /** 口径正文：必须写清状态位、退单处理、时间边界、统计粒度 */
        String caliber,
        /** 主表，必须在表白名单内 */
        String table,
        /**
         * 可选的受控 1:1 关联（路线 C，2026-10-30）：主表 LEFT JOIN 一张已登记表，
         * 使维度/度量可以引用关联表列（如订单表按会员性别分组）。
         * 存在时所有列引用必须带表名/别名限定符（防歧义列，如两表同名 del_flag）；
         * 关联基数由口径保证（如 member_mobile 实测全表唯一 → 不乘行、不重复累计）。
         */
        Join join,
        /** 统计粒度，编译器据此阻止跨粒度混算（防订单级金额重复累计） */
        Grain grain,
        /** 时间列；为空表示累计口径，不注入时间条件 */
        String timeColumn,
        /** 恒定 WHERE 片段，只允许字面量 */
        List<String> baselineFilters,
        /** 可分组维度；为空表示只做全局聚合 */
        List<Dimension> dimensions,
        /** 非聚合描述列，生成 MAX(x) AS y */
        List<Attribute> attributes,
        /** 度量（聚合表达式） */
        List<Measure> measures,
        /** 可选过滤条件，命中请求时生成 column = ?（参数化，非拼接） */
        List<Filter> optionalFilters,
        /** 派生指标，由 Java 端四则计算，不进 SQL */
        List<Derived> derived,
        /** 允许排序的度量编码白名单 */
        List<String> sortable,
        /** 默认排序度量编码（可空） */
        String defaultSortBy,
        /** 默认排序方向 asc|desc，默认 desc */
        String defaultSortDir,
        /** 默认返回条数 */
        int limitDefault,
        /** 最大返回条数 */
        int limitMax,
        /** 是否生成合计行 */
        boolean withTotalRow,
        /** 空/缺失维度的归并展示名，如“未知门店”；为空表示不归并 */
        String nullDimensionLabel,
        /** 本指标额外禁止输出的列 */
        List<String> forbiddenColumns,
        /**
         * 指标级缺省时间窗口（月）：请求未传区间时服务端按此补 [今天-窗口, 今天]；
         * 0 表示沿用看板全局默认（近一年半）。非时间口径指标忽略该字段。
         */
        int defaultWindowMonths,
        /**
         * 指标级时间跨度上限（天）：0 表示沿用全局上限 QueryLimits.MAX_RANGE_DAYS。
         * 适用于“窗口一大就超时”的明细表指标：真实库 crmtest 2026-10-30 实测，
         * 商品排行单月 1.2s 可稳定返回，18 个月 75.8s（FORCE INDEX 后仍 14.2s），
         * 而执行层上限为 10s —— 与其让模型稳定收到超时错误，不如在服务端按指标上限拒绝。
         * 语义：显式传入超上限区间→拒绝；缺省窗口超上限→收敛到上限（与 topN 同一策略）。
         */
        int maxRangeDays
) {

    /**
     * 口径确认状态
     */
    public enum Status {
        /** 口径已业务确认，可执行 */
        CONFIRMED,
        /** 口径待确认，请求时拒绝执行并返回明确错误码 */
        UNCONFIRMED
    }

    /**
     * 统计粒度。跨粒度混用是 CRM 分析最常见的错算来源，编译器强制拦截。
     */
    public enum Grain {
        /** 订单头：expense_calendar_info，约定 1 单 1 行 */
        ORDER_HEAD,
        /** 订单明细：expense_calendar_details，一单多行 */
        ORDER_DETAIL,
        /** 会员：member_info */
        MEMBER,
        /** 配置/字典表 */
        CONFIG
    }

    /**
     * 分组维度
     */
    public record Dimension(String code, String expr, String alias) {
    }

    /**
     * 描述列（随维度带出的名称等），以聚合函数规避 ONLY_FULL_GROUP_BY
     */
    public record Attribute(String expr, String alias, String agg) {
    }

    /**
     * 度量
     *
     * @param expr 聚合对象表达式；VARCHAR 金额/数量必须写成 NUM(列名)，由编译器展开为安全数值转换
     */
    public record Measure(String code, String expr, String agg, String alias,
                          String unit, boolean zeroFill, int decimalScale) {
    }

    /**
     * 可选过滤条件
     */
    public record Filter(String code, String column) {
    }

    /**
     * 受控关联：固定 LEFT JOIN（驱动表=主表，保留无匹配行），ON 仅允许
     * {@code qualifier.column = qualifier.column} 单一等值，由校验器整串复核，
     * 不接受任意 SQL 片段/占位符/子查询。qualifier 只能是主表名、主表别名或本关联别名。
     *
     * @param mainAlias 主表别名（声明 join 后必填）：所有列引用强制带限定符，
     *                  防两表同名列（如 del_flag）在 MySQL 报 ambiguous
     */
    public record Join(String table, String alias, String mainAlias, String on) {
    }

    /**
     * 派生指标：formula 仅允许四则运算白名单（操作数为其它 measure/derived 编码）
     */
    public record Derived(String code, String formula, int scale, String unit) {
    }

    public boolean isTimeBased() {
        return timeColumn != null && !timeColumn.isBlank();
    }

    public boolean isConfirmed() {
        return status == Status.CONFIRMED;
    }
}
