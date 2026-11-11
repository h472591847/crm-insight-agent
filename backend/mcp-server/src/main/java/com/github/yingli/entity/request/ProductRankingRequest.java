package com.github.yingli.entity.request;

import lombok.Data;
import lombok.ToString;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * @Description 商品销售排行查询入参。基于消费明细表按 SKU 聚合销售额/销量并取 Top N。
 *              时间范围未提供时由服务端默认近 3 个月，且本指标窗口跨度上限 92 天
 *              （2026-10-30 真实库实测：明细表量级大，长窗口必超时）。
 * @Author crm-agent
 */
@Data
@ToString
public class ProductRankingRequest {

    @ToolParam(description = "开始日期 yyyy-MM-dd，可选；未提供时默认近 3 个月起点", required = false)
    private String startDate;

    @ToolParam(description = "结束日期 yyyy-MM-dd，可选；未提供时默认今天。该日期当天全天计入统计（含结束日）；"
            + "与开始日期之间的区间长度不得超过 92 天", required = false)
    private String endDate;

    @ToolParam(description = "排行依据：'sales'=按销售额(默认)，'qty'=按销量", required = false)
    private String rankBy;

    @ToolParam(description = "返回前 N 个商品，默认10，最大100", required = false)
    private Integer topN;

    @ToolParam(description = "商户/租户编码：已不支持（2026-10-30 业务决策取消租户维度，统计聚焦门店），"
            + "字段仅为入参兼容保留，非空时工具直接返回不支持的错误", required = false)
    private String tenant;

    @ToolParam(description = "门店编码，可选。仅统计指定门店", required = false)
    private String stationCode;
}
