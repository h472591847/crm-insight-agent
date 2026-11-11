package com.github.yingli.metrics.model;

import java.util.List;
import java.util.Map;

/**
 * @Description 已解析、已校验的查询意图（{@link com.github.yingli.metrics.request.MetricQueryRequest} 由
 * {@link com.github.yingli.metrics.validate.MetricQueryValidator} 归一化后得到）。
 * 时间已补默认值、条数已夹到上限、排序编码已通过白名单，编译器可直接信任其内容。
 * <p>
 * 模型只能表达“哪个指标、按哪些维度、过滤什么、哪段时间、取多少条、按什么排序”，
 * 无法表达任意 SQL，也无法自行决定数据权限。
 * @Author crm-agent
 */
public record QueryPlan(
        /** 指标编码 */
        String metric,
        /** 分组维度编码，按指标声明顺序保序去重后的结果；空表示全局聚合 */
        List<String> dimensions,
        /** 过滤条件：filterCode -> value（值仍会作为参数传入，不做字符串拼接） */
        Map<String, String> filters,
        /** 归一化后的开始日期 yyyy-MM-dd；非时间口径指标为 null */
        String startDate,
        /** 归一化后的结束日期 yyyy-MM-dd（含当日全天）；非时间口径指标为 null */
        String endDate,
        /** 生效的返回条数上限 */
        int limit,
        /** 排序度量编码；为 null 表示不排序 */
        String sortBy,
        /** 排序方向 asc|desc */
        String sortDir
) {
}
