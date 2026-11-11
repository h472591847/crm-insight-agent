package com.github.yingli.adhoc.request;

import lombok.Data;
import lombok.ToString;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * @Description 度量规格。四类 type：
 * simple=元数据声明列直接聚合；conditional=叠加 conditionPreset 的条件聚合（SUM(CASE WHEN...)）；
 * distinct=COUNT(DISTINCT 列)（列须元数据声明该 agg 且属 PII 计算允许）；
 * derived=本请求 alias 之间的四则运算（Java 层计算，不进 SQL）。
 * @Author crm-agent
 */
@Data
@ToString
public class MeasureSpec {

    @ToolParam(description = "类型：simple|conditional|distinct|derived")
    private String type;

    @ToolParam(description = "引用元数据声明的度量名（type=derived 时不用）", required = false)
    private String name;

    @ToolParam(description = "type=conditional 时引用条件模板 ID，如 IS_VIP", required = false)
    private String conditionPreset;

    @ToolParam(description = "type=derived 时的公式，仅支持 + - * / 括号与本请求已声明 alias", required = false)
    private String formula;

    @ToolParam(description = "输出 alias：^[a-z][a-z0-9_]{0,31}$，同请求内唯一")
    private String alias;

    @ToolParam(description = "type=derived 的小数位，默认 2", required = false)
    private Integer scale;
}
