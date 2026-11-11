package com.itzixi.entity.request;

import lombok.Data;
import lombok.ToString;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * @Description 门店业绩查询入参。按门店统计指定时间范围内的销售额、订单数、客单价。
 *              时间范围未提供时由服务端默认取近一年半（2026-10-29 看板口径）。
 * @Author crm-agent
 */
@Data
@ToString
public class StationPerformanceRequest {

    @ToolParam(description = "开始日期 yyyy-MM-dd，可选；未提供时默认近一年半起点", required = false)
    private String startDate;

    @ToolParam(description = "结束日期 yyyy-MM-dd，可选；未提供时默认今天。该日期当天全天计入统计（含结束日）", required = false)
    private String endDate;

    @ToolParam(description = "商户/租户编码：已不支持（2026-10-30 业务决策取消租户维度，统计聚焦门店），"
            + "字段仅为入参兼容保留，非空时工具直接返回不支持的错误", required = false)
    private String tenant;

    @ToolParam(description = "门店编码，可选。仅统计指定门店", required = false)
    private String stationCode;
}
