package com.itzixi.adhoc.request;

import lombok.Data;
import lombok.ToString;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;

/**
 * @Description Ad-hoc 结构化查询请求：模型只能引用语义元数据中声明的名字，
 * 一切表/列/表达式由服务端解析，值一律参数化。
 * @Author crm-agent
 */
@Data
@ToString
public class AdhocQueryRequest {

    @ToolParam(description = "实体列表，首个为主表；跨实体组合需在元数据声明关系，如 [\"order\",\"member\"]")
    private List<String> entities;

    @ToolParam(description = "分组维度名列表（取自 listSemanticCatalog），如 [\"STORE_CODE\"]；最多 5 个")
    private List<String> dimensions;

    @ToolParam(description = "度量列表：type=simple|conditional|distinct|derived；"
            + "simple/conditional/distinct 用 name 引用元数据度量，derived 用 formula 引用本请求已声明 alias")
    private List<MeasureSpec> measures;

    @ToolParam(description = "过滤条件（可选）：preset 引用条件模板 ID，或 column+op+value 结构化直传", required = false)
    private List<FilterSpec> filters;

    @ToolParam(description = "时间窗口（可选）：引用实体声明的 timeDimension，左闭右开含结束日全天", required = false)
    private TimeRangeSpec timeRange;

    @ToolParam(description = "排序，形如 \"SALES desc\"；只能引用本请求输出的维度/度量 alias", required = false)
    private String orderBy;

    @ToolParam(description = "返回行数，默认 100（实体守卫可覆盖缺省，如 detail=500），上限 1000", required = false)
    private Integer limit;
}
