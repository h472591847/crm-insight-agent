package com.github.yingli.mcp.tool;

import com.github.yingli.metrics.request.FilterItem;
import com.github.yingli.entity.request.MemberOverviewRequest;
import com.github.yingli.metrics.request.MetricQueryRequest;
import com.github.yingli.entity.request.ProductRankingRequest;
import com.github.yingli.entity.request.StationPerformanceRequest;
import com.github.yingli.entity.response.MemberOverviewResult;
import com.github.yingli.metrics.response.MetricResult;
import com.github.yingli.entity.response.ProductRankingResult;
import com.github.yingli.entity.response.StationPerformanceResult;
import com.github.yingli.metrics.service.MetricQueryService;
import com.github.yingli.prompt.ToolPromptSnippets;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * @Description CRM 数据分析 Agent 的只读业务工具（Phase 1 签名，Phase 2 起内部委托 Metrics-as-Code 编译层）。
 * <p>
 * 迁移说明：本类不再直接调用手写 Mapper。三个工具分别等价于注册表中的
 * MEMBER_OVERVIEW / STATION_SALES / PRODUCT_RANKING 指标配置（resources/metrics/*.yaml），
 * 时间校验、默认值补全、参数化过滤、派生计算（客单价/人均）与合计行均由
 * {@link MetricQueryService} 统一完成。对外工具名、入参与返回结构保持向后兼容。
 * <p>
 * AI 仅负责选择工具、组织参数与解释结果，不得自行编造统计数字。
 * 所有查询均为只读聚合，不返回任何个人信息。
 * @Author crm-agent
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class CrmAnalyticsTool {

    private final MetricQueryService metricQueryService;

    /** 会员总览对应的指标编码 */
    static final String METRIC_MEMBER_OVERVIEW = "MEMBER_OVERVIEW";
    /** 会员累计消费对应的指标编码（2026-11-09 起金额改取订单实付 expense_realamt，不再取会员视图快照） */
    static final String METRIC_MEMBER_CONSUMPTION = "MEMBER_CONSUMPTION";
    /** 门店业绩对应的指标编码 */
    static final String METRIC_STATION_SALES = "STATION_SALES";
    /** 商品排行对应的指标编码 */
    static final String METRIC_PRODUCT_RANKING = "PRODUCT_RANKING";

    /** 空结果时与 Phase 1 一致的可读提示 */
    private static final String MSG_NO_ORDER = "该时间范围与条件下没有符合条件的订单数据（可能确实无数据，或所选区间无消费记录）。";
    private static final String MSG_NO_DETAIL = "该时间范围与条件下没有商品消费明细数据（可能确实无数据，或所选区间无记录）。";
    /**
     * 租户能力已取消（2026-10-30 业务决策，统计聚焦门店）。
     * 传入 tenant 时显式拒绝而不是静默忽略：静默按全局统计会让模型误以为结果已按租户收窄。
     */
    private static final String MSG_TENANT_UNSUPPORTED =
            "本工具已不支持按租户/商户筛选或统计（2026-10-30 业务决策取消租户维度）："
                    + "真实库 member_tenant 全部为空、expense_tenant 多数为空，按租户过滤只会得到 0。"
                    + "请关注门店维度，改用门店编码限定统计范围。";

    // ---------------------------------------------------------------------
    // Tool 1：会员总览
    // ---------------------------------------------------------------------

    @Tool(name = "queryMemberOverview",
            description = "查询会员总览：会员总数、性别构成(男/女/未知)、累计消费总额与人均消费。"
                    + "可选按门店限定范围，默认全局。不支持租户筛选（2026-10-30 业务决策取消）。"
                    + ToolPromptSnippets.NO_PII + ToolPromptSnippets.NO_SUGGESTION)
    public MemberOverviewResult queryMemberOverview(MemberOverviewRequest request) {
        log.info("====== MCP工具 queryMemberOverview 参数：{} ======", request);
        MemberOverviewResult result = new MemberOverviewResult();
        String tenant = StringUtils.trimToNull(request == null ? null : request.getTenant());
        String station = StringUtils.trimToNull(request == null ? null : request.getStation());
        if (tenant != null) {
            result.setMessage(MSG_TENANT_UNSUPPORTED);
            return result;
        }

        MetricQueryRequest q = new MetricQueryRequest();
        q.setMetric(METRIC_MEMBER_OVERVIEW);
        q.setFilters(filters(station));

        MetricResult res = metricQueryService.query("member", q);
        if (isHardFailure(res)) {
            result.setMessage(describeFailure(res, "会员总览查询失败，请稍后重试或缩小范围。"));
            return result;
        }
        if (res.getErrorCode() != null) {
            // 全局聚合正常必有 1 行；出现其他异常语义时如实转述，不编造数字
            result.setMessage(StringUtils.defaultIfBlank(res.getMessage(),
                    "会员总览查询失败，请稍后重试或缩小范围。"));
            return result;
        }

        int r = Math.max(res.rowCount() - 1, 0);
        result.setTotalMembers(toLong(res.value(r, "TOTAL_MEMBERS")));
        result.setMaleCount(toLong(res.value(r, "MALE_COUNT")));
        result.setFemaleCount(toLong(res.value(r, "FEMALE_COUNT")));
        result.setUnknownCount(toLong(res.value(r, "UNKNOWN_COUNT")));

        // 消费金额改由订单实付口径的 MEMBER_CONSUMPTION 提供（2026-11-09）：
        // MEMBER_OVERVIEW 已移除基于 member_expense_totalsum 的金额字段，避免金额重复累计或取错来源。
        MetricQueryRequest consumptionQ = new MetricQueryRequest();
        consumptionQ.setMetric(METRIC_MEMBER_CONSUMPTION);
        consumptionQ.setFilters(filters(station));
        MetricResult consumption = metricQueryService.query("member", consumptionQ);
        if (isHardFailure(consumption) || consumption.getErrorCode() != null) {
            result.setMessage(describeFailure(consumption,
                    "会员人数已查到，但消费金额（订单实付口径）查询失败，请稍后重试或缩小范围。"));
        } else if (consumption.rowCount() > 0) {
            int cr = consumption.rowCount() - 1;
            result.setExpenseMemberCount(toLong(consumption.value(cr, "BUYERS")));
            result.setTotalExpenseAmount(toBigDecimal(consumption.value(cr, "TOTAL_EXPENSE")));
            result.setAvgExpensePerActiveMember(toBigDecimal(consumption.value(cr, "AVG_EXPENSE_PER_BUYER")));
        }

        result.setScope(res.getScope());
        return result;
    }

    // ---------------------------------------------------------------------
    // Tool 2：门店业绩
    // ---------------------------------------------------------------------

    @Tool(name = "queryStationPerformance",
            description = "统计指定时间范围内各门店的销售额(实付合计)、有效订单数与客单价，按销售额降序，并给出合计行。"
                    + "时间未提供时默认近一年半（2026-10-29 看板口径）。可选按门店限定，不支持租户筛选（2026-10-30 取消）。"
                    + "有效订单口径：状态正常且未删除，退单已按负金额抵消。"
                    + ToolPromptSnippets.NO_SUGGESTION
    )
    public StationPerformanceResult queryStationPerformance(StationPerformanceRequest request) {
        log.info("====== MCP工具 queryStationPerformance 参数：{} ======", request);
        StationPerformanceResult result = new StationPerformanceResult();
        String startDate = StringUtils.trimToNull(request == null ? null : request.getStartDate());
        String endDate = StringUtils.trimToNull(request == null ? null : request.getEndDate());
        String tenant = StringUtils.trimToNull(request == null ? null : request.getTenant());
        String stationCode = StringUtils.trimToNull(request == null ? null : request.getStationCode());
        if (tenant != null) {
            result.setMessage(MSG_TENANT_UNSUPPORTED);
            return result;
        }

        MetricQueryRequest q = new MetricQueryRequest();
        q.setMetric(METRIC_STATION_SALES);
        q.setDimensions(List.of("STATION"));
        q.setStartDate(startDate);
        q.setEndDate(endDate);
        q.setFilters(filters(stationCode));

        MetricResult res = metricQueryService.query("order", q);
        if (isHardFailure(res)) {
            result.setMessage(describeFailure(res, "门店业绩查询失败，请稍后重试。"));
            return result;
        }

        List<StationPerformanceResult.StationStat> stations = new ArrayList<>(res.rowCount());
        for (int i = 0; i < res.rowCount(); i++) {
            StationPerformanceResult.StationStat st = new StationPerformanceResult.StationStat();
            st.setStationCode(toText(res.value(i, "STATION")));
            // 空门店编码已由框架按口径归并为“未知门店”展示名（业务口径 2026-10-28 确认）
            st.setStationName(toText(res.value(i, "station_name")));
            st.setSalesAmount(toBigDecimal(res.value(i, "SALES")));
            st.setOrderCount(toLong(res.value(i, "ORDERS")));
            st.setAvgOrderValue(toBigDecimal(res.value(i, "AVG_ORDER_VALUE")));
            stations.add(st);
        }
        StationPerformanceResult.StationStat total = new StationPerformanceResult.StationStat();
        total.setStationCode("ALL");
        total.setStationName("合计");
        total.setSalesAmount(toBigDecimal(res.totalValue("SALES")));
        total.setOrderCount(toLong(res.totalValue("ORDERS")));
        total.setAvgOrderValue(toBigDecimal(res.totalValue("AVG_ORDER_VALUE")));

        result.setStations(stations);
        result.setTotal(total);
        result.setScope(res.getScope());
        if (stations.isEmpty()) {
            result.setMessage(MSG_NO_ORDER);
        }
        return result;
    }

    // ---------------------------------------------------------------------
    // Tool 3：商品销售排行
    // ---------------------------------------------------------------------

    @Tool(name = "queryProductRanking",
            description = "按 SKU 统计商品销售额或销量排行，返回 Top N。时间未提供时默认近 3 个月，"
                    + "且本指标时间跨度上限 92 天（明细表量级大，长窗口会超时，2026-10-30 真实库实测）。"
                    + "rankBy 取 'sales'(按销售额,默认) 或 'qty'(按销量)；topN 默认10、最大100；可选按门店限定。"
                    + "注意：商品维度暂无品牌字段；不支持按租户过滤（2026-10-30 取消）。"
                    + "异常 SKU 编码（如 198.0）按真实商品统计，不过滤不合并。"
                    + ToolPromptSnippets.NO_SUGGESTION
    )
    public ProductRankingResult queryProductRanking(ProductRankingRequest request) {
        log.info("====== MCP工具 queryProductRanking 参数：{} ======", request);
        ProductRankingResult result = new ProductRankingResult();
        String startDate = StringUtils.trimToNull(request == null ? null : request.getStartDate());
        String endDate = StringUtils.trimToNull(request == null ? null : request.getEndDate());
        String stationCode = StringUtils.trimToNull(request == null ? null : request.getStationCode());
        String tenant = StringUtils.trimToNull(request == null ? null : request.getTenant());
        int topN = request == null || request.getTopN() == null ? 0 : request.getTopN();
        if (tenant != null) {
            result.setMessage(MSG_TENANT_UNSUPPORTED);
            return result;
        }

        String rankBy = normalizeRankBy(request == null ? null : request.getRankBy());

        MetricQueryRequest q = new MetricQueryRequest();
        q.setMetric(METRIC_PRODUCT_RANKING);
        q.setDimensions(List.of("SKU"));
        q.setStartDate(startDate);
        q.setEndDate(endDate);
        q.setTopN(topN > 0 ? topN : null);
        q.setSortBy("qty".equals(rankBy) ? "QTY" : "SALES");
        q.setFilters(filters(stationCode));

        MetricResult res = metricQueryService.query("order", q);
        if (isHardFailure(res)) {
            result.setMessage(describeFailure(res, "商品排行查询失败，请稍后重试。"));
            return result;
        }

        List<ProductRankingResult.ProductStat> items = new ArrayList<>(res.rowCount());
        for (int i = 0; i < res.rowCount(); i++) {
            ProductRankingResult.ProductStat it = new ProductRankingResult.ProductStat();
            it.setSku(toText(res.value(i, "SKU")));
            it.setProductName(toText(res.value(i, "product_name")));
            it.setCategoryName(toText(res.value(i, "category_name")));
            it.setSalesAmount(toBigDecimal(res.value(i, "SALES")));
            it.setQuantity(toBigDecimal(res.value(i, "QTY")));
            items.add(it);
        }

        result.setRankBy(rankBy);
        result.setItems(items);
        result.setScope(res.getScope());
        if (items.isEmpty()) {
            result.setMessage(MSG_NO_DETAIL);
        }
        return result;
    }

    // ---------------------------------------------------------------------
    // 内部辅助方法
    // ---------------------------------------------------------------------

    /**
     * 组装可选过滤。租户已于 2026-10-30 取消，各工具在入参层就拒绝 tenant，不再向下游传 TENANT 过滤。
     */
    private List<FilterItem> filters(String station) {
        List<FilterItem> out = new ArrayList<>(1);
        if (StringUtils.isNotBlank(station)) {
            out.add(filter("STATION", station));
        }
        return out;
    }

    private FilterItem filter(String code, String value) {
        FilterItem item = new FilterItem();
        item.setCode(code);
        item.setValue(value);
        return item;
    }

    /**
     * 排行依据归一化，与 Phase 1 完全一致：空白或未知取值归为 sales。
     */
    private String normalizeRankBy(String rankBy) {
        if (StringUtils.isBlank(rankBy)) {
            return "sales";
        }
        String v = rankBy.trim().toLowerCase();
        if ("qty".equals(v) || "quantity".equals(v)) {
            return "qty";
        }
        return "sales";
    }

    /**
     * 执行失败 / 参数非法 / 指标不存在 / 口径未确认：适配层必须直接返回原因。
     * NO_DATA 不算失败（空结果与失败语义必须区分）。
     */
    private boolean isHardFailure(MetricResult res) {
        String code = res.getErrorCode();
        return code != null && !"NO_DATA".equals(code);
    }

    private String describeFailure(MetricResult res, String fallback) {
        if ("INVALID_PARAM".equals(res.getErrorCode())) {
            return "参数不合法：" + res.getMessage();
        }
        if ("METRIC_NOT_FOUND".equals(res.getErrorCode()) || "METRICS_NOT_CONFIRMED".equals(res.getErrorCode())) {
            return res.getMessage();
        }
        return StringUtils.defaultIfBlank(res.getMessage(), fallback);
    }

    private String toText(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private Long toLong(Object v) {
        if (v == null) {
            return 0L;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private BigDecimal toBigDecimal(Object v) {
        if (v == null) {
            return BigDecimal.ZERO;
        }
        if (v instanceof BigDecimal b) {
            return b;
        }
        if (v instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        try {
            return new BigDecimal(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }
}
