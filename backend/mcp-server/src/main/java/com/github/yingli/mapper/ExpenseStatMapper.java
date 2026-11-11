package com.github.yingli.mapper;

import com.github.yingli.entity.response.ProductRankingResult;
import com.github.yingli.entity.response.StationPerformanceResult;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * @Description 消费/门店/商品受控聚合查询（只读）。金额字段为 VARCHAR，SQL 内做安全数值转换。
 * <p>
 * 定位说明：租户维度已于 2026-10-30 从对外能力中取消，本 Mapper 仅作为 Phase 1 手写 SQL 的
 * 逐值对账基线（oracle）保留，不再被 MCP 工具调用；tenant 入参始终传 null，
 * 新增业务查询请走 resources/metrics/*.yaml 指标配置，不要回到本 Mapper。
 * @Author crm-agent
 */
@Mapper
public interface ExpenseStatMapper {

    /**
     * 各门店业绩：销售额(实付合计)、有效订单数。客单价由服务端计算。
     * 仅用订单头表 expense_calendar_info，规避与明细联查的重复累计。
     *
     * @param tenant 已废弃，仅为保持 Phase 1 对账 SQL 结构不变；调用方必须传 null
     */
    List<StationPerformanceResult.StationStat> stationPerformance(@Param("startDate") String startDate,
                                                                  @Param("endDate") String endDate,
                                                                  @Param("tenant") String tenant,
                                                                  @Param("stationCode") String stationCode);

    /**
     * 商品销售排行：基于明细表按 SKU(expense_d_gdcode) 聚合。
     * 注意：明细表无租户字段，tenant 不参与本查询过滤（仅 stationCode 生效）。
     */
    List<ProductRankingResult.ProductStat> productRanking(@Param("startDate") String startDate,
                                                           @Param("endDate") String endDate,
                                                           @Param("stationCode") String stationCode,
                                                           @Param("rankBy") String rankBy,
                                                           @Param("topN") int topN);
}
