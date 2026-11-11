package com.itzixi.entity.response;

import lombok.Data;

import java.math.BigDecimal;

/**
 * @Description 门店业绩结果。stations 为各门店明细，total 为合计行。
 * @Author crm-agent
 */
@Data
public class StationPerformanceResult {

    /** 各门店业绩（按销售额降序） */
    private java.util.List<StationStat> stations;
    /** 合计行 */
    private StationStat total;
    /** 统计范围回显（时间区间 + 门店过滤） */
    private String scope;
    /** 提示信息；正常为 null */
    private String message;

    @Data
    public static class StationStat {
        /** 门店编码 */
        private String stationCode;
        /** 门店名称 */
        private String stationName;
        /** 销售额（实付合计，退单负数已抵消） */
        private BigDecimal salesAmount;
        /** 有效订单数 */
        private Long orderCount;
        /** 客单价（= salesAmount / orderCount），服务端计算 */
        private BigDecimal avgOrderValue;
    }
}
