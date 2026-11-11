package com.github.yingli.metrics.request;

import lombok.Data;
import lombok.ToString;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;

/**
 * @Description 指标查询的通用请求（Metrics-as-Code 运行时入口）。
 * 字段即"指标 × 维度 × 过滤 × 时间 × 排序 × 条数"的枚举组合约束：
 * 模型只能表达查哪个指标、按什么分组、过滤什么、哪段时间，
 * 无法表达任意 SQL，也无法自行决定数据权限（全部由服务端校验与补齐）。
 * @Author crm-agent
 */
@Data
@ToString
public class MetricQueryRequest {

    @ToolParam(description = "指标编码，必填；取值见 listMetrics 返回，非法编码直接拒绝")
    private String metric;

    @ToolParam(description = "分组维度编码列表，可选；如 [STATION]；不传=全局汇总", required = false)
    private List<String> dimensions;

    @ToolParam(description = "过滤条件列表，可选；每项 {code,value}，code 取自指标声明的可选过滤", required = false)
    private List<FilterItem> filters;

    @ToolParam(description = "开始日期 yyyy-MM-dd，可选；未提供时按看板默认近一年半（个别指标声明自己的默认窗口，见 listMetrics 的口径）", required = false)
    private String startDate;

    @ToolParam(description = "结束日期 yyyy-MM-dd，可选；默认今天，该日全天计入统计", required = false)
    private String endDate;

    @ToolParam(description = "返回条数，可选；默认与上限由指标声明", required = false)
    private Integer topN;

    @ToolParam(description = "排序度量编码，可选；必须是该指标 sortable 之一", required = false)
    private String sortBy;

    @ToolParam(description = "排序方向 asc|desc，可选；默认 desc", required = false)
    private String sortDir;
}
