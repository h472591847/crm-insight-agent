package com.github.yingli.metrics.request;

import lombok.Data;
import lombok.ToString;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;

/**
 * @Description 指标脚手架（draftMetric）的结构化入参：把"我想起草一个 XX 指标"表达成枚举字段，
 * 由服务端确定性地组装成 {@link com.github.yingli.metrics.model.MetricDefinition}、跑一遍加载期校验、
 * 干跑编译，最终返回可直接粘贴进 metrics/*.yaml 的配置片段。
 * <p>
 * 边界（与运行时查询链路严格分离）：本入参只用于<b>开发期起草配置</b>，
 * 不触发任何数据库访问、不落盘、不注册指标；口径正文（{@link #caliber}）必须由人工提供，
 * 脚手架绝不臆造业务语义。
 *
 * @Author crm-agent
 */
@Data
@ToString
public class MetricDraftRequest {

    @ToolParam(description = "指标编码，必填；UPPER_SNAKE，如 STATION_SALES；须全局唯一（与现有指标重名会在返回里报错）")
    private String metric;

    @ToolParam(description = "中文指标名，必填；进入结果与模型可见元数据")
    private String title;

    @ToolParam(description = "业务域，必填；member|order|delivery|finance 之一")
    private String domain;

    @ToolParam(description = "口径正文，必填；必须由人工/产品提供（状态位、退单处理、时间边界、统计粒度）。脚手架不猜口径")
    private String caliber;

    @ToolParam(required = false,
            description = "口径状态，可选；缺省一律 UNCONFIRMED（防起草即\"看起来权威\"）。仅当业务已确认才显式传 CONFIRMED")
    private String status;

    @ToolParam(description = "主表，必填；必须在列类型登记表（表白名单）内")
    private String table;

    @ToolParam(required = false,
            description = "统计粒度，可选；ORDER_HEAD|ORDER_DETAIL|MEMBER|CONFIG。留空时按主表推断（表→粒度是已验证的硬映射，非业务猜测）")
    private String grain;

    @ToolParam(required = false, description = "时间列，可选；为空表示累计口径，不注入时间条件。join 指标建议带限定符（如 e.expense_buyDate）")
    private String timeColumn;

    @ToolParam(required = false, description = "恒定 WHERE 字面量片段列表，可选；只允许字面量，禁止占位符/写操作/注释（含 del_flag 等同名列时 join 指标须带限定符）")
    private List<String> baselineFilters;

    @ToolParam(required = false, description = "受控 1:1 关联（路线 C），可选；声明后列引用须自行带限定符，ON 由脚手架按主表/关联别名组装")
    private JoinSpec join;

    @ToolParam(required = false, description = "分组维度列表，可选；每项 {code,column/expr,alias}。留空=只做全局聚合")
    private List<DimensionSpec> dimensions;

    @ToolParam(required = false, description = "非聚合描述列列表，可选；每项 {column,alias,agg}，默认 MAX")
    private List<AttributeSpec> attributes;

    @ToolParam(description = "度量（聚合表达式）列表，必填且至少一个；每项 {code,agg,column/expr,alias,unit,decimalScale,zeroFill}。VARCHAR 金额列命中登记表时自动包 NUM()")
    private List<MeasureSpec> measures;

    @ToolParam(required = false, description = "可选过滤列表，可选；每项 {code,column}，命中请求时生成 column = ? 参数化条件")
    private List<FilterSpec> optionalFilters;

    @ToolParam(required = false, description = "派生指标列表，可选；每项 {code,formula,scale,unit}，Java 端四则计算，不进 SQL")
    private List<DerivedSpec> derived;

    @ToolParam(required = false, description = "允许排序的度量编码白名单，可选；留空时默认取全部度量编码")
    private List<String> sortable;

    @ToolParam(required = false, description = "默认排序度量编码，可选；必须在 sortable 内")
    private String defaultSortBy;

    @ToolParam(required = false, description = "默认排序方向 asc|desc，可选；默认 desc")
    private String defaultSortDir;

    @ToolParam(required = false, description = "默认返回条数，可选；默认 100")
    private Integer limitDefault;

    @ToolParam(required = false, description = "最大返回条数，可选；默认 500，不得超过全局硬上限 2000")
    private Integer limitMax;

    @ToolParam(required = false, description = "是否生成全量合计行，可选；默认 false")
    private Boolean withTotalRow;

    @ToolParam(required = false, description = "空/缺失维度归并展示名，可选；如\"未知门店\"。声明了它却没有任何维度会被判可疑")
    private String nullDimensionLabel;

    @ToolParam(required = false, description = "指标级缺省时间窗口（月），可选；0=沿用看板默认（近一年半）")
    private Integer defaultWindowMonths;

    @ToolParam(required = false, description = "指标级时间跨度上限（天），可选；0=沿用全局上限，适用于窗口一大就超时的明细表指标")
    private Integer maxRangeDays;

    // ------------------------------------------------------------------
    // 嵌套结构
    // ------------------------------------------------------------------

    /**
     * 分组维度：{@code column} 为简单列引用（脚手架按 NUM/PII 规则直通校验）；
     * {@code expr} 为复杂表达式（如 CASE 分桶），二者取其一，expr 优先。
     */
    @Data
    @ToString
    public static class DimensionSpec {
        @ToolParam(description = "维度编码，UPPER_SNAKE，如 STATION/GENDER")
        private String code;
        @ToolParam(required = false, description = "简单列引用（如 expense_stationCode 或 join 指标下的 e.expense_stationCode）")
        private String column;
        @ToolParam(required = false, description = "复杂分组表达式（如 CASE WHEN ... END），与 column 二选一，优先取 expr")
        private String expr;
        @ToolParam(required = false, description = "SQL 列别名，可选；留空默认取 code 小写")
        private String alias;
    }

    /** 非聚合描述列：生成 agg(expr) AS alias，以聚合函数规避 ONLY_FULL_GROUP_BY。 */
    @Data
    @ToString
    public static class AttributeSpec {
        @ToolParam(description = "描述列（如 expense_stationName），建议 join 指标带限定符")
        private String column;
        @ToolParam(description = "输出别名（如 station_name）")
        private String alias;
        @ToolParam(required = false, description = "聚合函数，可选；SUM|COUNT|MAX|MIN|AVG，默认 MAX")
        private String agg;
    }

    /**
     * 度量：{@code column} 为聚合对象列（脚手架对命中登记表的 VARCHAR 数值列自动包 NUM()）；
     * {@code expr} 为完整聚合对象表达式（如 DISTINCT e.expense_mobile），二者取其一，expr 优先。
     * {@code agg=COUNT} 且未给 column/expr 时按 {@code *}（行数）。
     */
    @Data
    @ToString
    public static class MeasureSpec {
        @ToolParam(description = "度量编码，UPPER_SNAKE，如 SALES/ORDERS")
        private String code;
        @ToolParam(description = "聚合函数：SUM|COUNT|MAX|MIN|AVG")
        private String agg;
        @ToolParam(required = false, description = "聚合对象列；VARCHAR 金额/数量命中登记表时自动包 NUM()。COUNT 行数可留空或传 *")
        private String column;
        @ToolParam(required = false, description = "完整聚合对象表达式（如 DISTINCT e.expense_mobile），与 column 二选一，优先取 expr")
        private String expr;
        @ToolParam(required = false, description = "SQL 列别名，可选；留空默认取 code 小写")
        private String alias;
        @ToolParam(required = false, description = "单位，可选；CNY|COUNT|PCS|PERCENT 等，供模型正确表述")
        private String unit;
        @ToolParam(required = false, description = "小数位，可选；默认 2，用于 NUM() 展开的 DECIMAL(20,scale)")
        private Integer decimalScale;
        @ToolParam(required = false, description = "是否兜零（COALESCE(...,0)），可选；默认 false")
        private Boolean zeroFill;
    }

    /** 可选过滤：命中请求时生成 {@code column = ?}（值参数化下传，绝不拼接）。 */
    @Data
    @ToString
    public static class FilterSpec {
        @ToolParam(description = "过滤编码，UPPER_SNAKE，如 STATION/SKU")
        private String code;
        @ToolParam(description = "过滤列，建议 join 指标带限定符；不得是 PII 列")
        private String column;
    }

    /** 派生指标：formula 仅允许四则运算（操作数为其它 measure/derived 编码），Java 端计算、不进 SQL。 */
    @Data
    @ToString
    public static class DerivedSpec {
        @ToolParam(description = "派生编码，UPPER_SNAKE，如 AVG_ORDER_VALUE")
        private String code;
        @ToolParam(description = "四则运算公式，操作数为 measure/derived 编码，如 SALES / ORDERS")
        private String formula;
        @ToolParam(required = false, description = "结果小数位，可选；默认 2")
        private Integer scale;
        @ToolParam(required = false, description = "单位，可选")
        private String unit;
    }

    /**
     * 受控 1:1 关联（路线 C）：脚手架按 {@code mainAlias.onLeftCol = joinAlias.onRightCol} 组装 ON
     * 并规避 SnakeYAML 布尔键陷阱（产出时 on 键加引号）。列引用请自行带限定符，脚手架不猜归属表。
     */
    @Data
    @ToString
    public static class JoinSpec {
        @ToolParam(description = "关联表，必须在表白名单内（如 member_info）")
        private String joinTable;
        @ToolParam(description = "关联表别名（如 m），不得与主表/关联表同名")
        private String joinAlias;
        @ToolParam(description = "主表别名（如 e）；声明 join 后所有列引用须带 mainAlias./joinAlias. 限定符")
        private String mainAlias;
        @ToolParam(description = "关联键：主表列（裸列或已带 mainAlias 限定均可，如 expense_mobile）")
        private String onLeftCol;
        @ToolParam(description = "关联键：关联表列（裸列或已带 joinAlias 限定均可，如 member_mobile）；PII 关联键须已在 piiComputeAllowedColumns")
        private String onRightCol;
    }
}
