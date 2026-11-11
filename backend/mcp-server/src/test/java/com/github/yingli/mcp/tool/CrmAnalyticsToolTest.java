package com.github.yingli.mcp.tool;

import com.github.yingli.entity.request.MemberOverviewRequest;
import com.github.yingli.entity.request.ProductRankingRequest;
import com.github.yingli.entity.request.StationPerformanceRequest;
import com.github.yingli.entity.response.MemberOverviewResult;
import com.github.yingli.entity.response.ProductRankingResult;
import com.github.yingli.entity.response.StationPerformanceResult;
import com.github.yingli.metrics.service.MetricQueryService;
import com.github.yingli.metrics.MetricsFixtures;
import com.github.yingli.shared.sql.SqlExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CrmAnalyticsTool 单元测试（Phase 2 迁移版）：三个工具已委托 Metrics-as-Code 编译层，
 * 测试改用真实注册表 + 真实编译器 + 假执行器（不连库），端到端覆盖：
 * 服务端参数校验、默认值与上限、派生计算（客单价/人均）、合计行与空维度归并。
 *
 * @author crm-agent
 */
class CrmAnalyticsToolTest {

    private FakeExecutor executor;
    private CrmAnalyticsTool tool;

    @BeforeEach
    void setUp() {
        executor = new FakeExecutor();
        MetricQueryService service = MetricsFixtures.service(executor);
        tool = new CrmAnalyticsTool(service);
    }

    private StationPerformanceRequest stationReq(String start, String end) {
        StationPerformanceRequest r = new StationPerformanceRequest();
        r.setStartDate(start);
        r.setEndDate(end);
        return r;
    }

    // ------------------------------------------------------------------
    // 门店业绩（STATION_SALES）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("门店业绩：非法日期格式被服务端拒绝且不查库")
    void station_invalidDateFormat() {
        StationPerformanceResult res = tool.queryStationPerformance(stationReq("not-a-date", "2025-01-31"));
        assertThat(res.getMessage()).contains("日期格式");
        assertThat(res.getStations()).isNull();
        assertThat(executor.sqls).isEmpty();
    }

    @Test
    @DisplayName("门店业绩：结束日期早于开始日期被拒绝")
    void station_endBeforeStart() {
        StationPerformanceResult res = tool.queryStationPerformance(stationReq("2025-05-10", "2025-05-01"));
        assertThat(res.getMessage()).contains("结束日期不能早于开始日期");
        assertThat(executor.sqls).isEmpty();
    }

    @Test
    @DisplayName("门店业绩：时间跨度超过上限被拒绝")
    void station_spanTooLarge() {
        StationPerformanceResult res = tool.queryStationPerformance(stationReq("2020-01-01", "2026-01-01"));
        assertThat(res.getMessage()).contains("时间跨度");
        assertThat(executor.sqls).isEmpty();
    }

    @Test
    @DisplayName("门店业绩：客单价与合计行由框架确定性计算（合计=全量聚合而非已返回行累加）")
    void station_computesAvgAndTotal() {
        executor.responder = sql -> sql.contains("GROUP BY")
                ? List.of(row("station_code", "S1", "station_name", "门店一",
                        "sales_amount", new BigDecimal("100"), "order_count", 10L),
                row("station_code", "S2", "station_name", "门店二",
                        "sales_amount", new BigDecimal("200"), "order_count", 10L))
                : List.of(row("sales_amount", new BigDecimal("300"), "order_count", 20L));

        StationPerformanceResult res = tool.queryStationPerformance(stationReq("2025-01-01", "2025-01-31"));

        assertThat(res.getStations().get(0).getAvgOrderValue()).isEqualByComparingTo("10.00");
        assertThat(res.getStations().get(1).getAvgOrderValue()).isEqualByComparingTo("20.00");
        assertThat(res.getTotal().getSalesAmount()).isEqualByComparingTo("300");
        assertThat(res.getTotal().getOrderCount()).isEqualTo(20L);
        assertThat(res.getTotal().getAvgOrderValue()).isEqualByComparingTo("15.00");
        assertThat(res.getMessage()).isNull();
        // 主查询 + 全量合计查询各一次
        assertThat(executor.sqls).hasSize(2);
    }

    @Test
    @DisplayName("门店业绩：空编码且空名称归为“未知门店”，有名称的保持原样")
    void station_normalizesUnknownStation() {
        executor.responder = sql -> sql.contains("GROUP BY")
                ? List.of(row("station_code", "", "station_name", null,
                        "sales_amount", new BigDecimal("5"), "order_count", 1L),
                row("station_code", "", "station_name", "有名字的店",
                        "sales_amount", new BigDecimal("7"), "order_count", 1L))
                : List.of(row("sales_amount", new BigDecimal("12"), "order_count", 2L));

        StationPerformanceResult res = tool.queryStationPerformance(stationReq("2025-01-01", "2025-01-31"));

        assertThat(res.getStations().get(0).getStationName()).isEqualTo("未知门店");
        assertThat(res.getStations().get(1).getStationName()).isEqualTo("有名字的店");
    }

    @Test
    @DisplayName("门店业绩：门店过滤参数化传入，不拼接进 SQL（租户维度已于 2026-10-30 取消）")
    void station_filtersAreParameterized() {
        executor.responder = sql -> sql.contains("GROUP BY") ? List.of()
                : List.of(row("sales_amount", BigDecimal.ZERO, "order_count", 0L));

        StationPerformanceRequest req = stationReq("2025-01-01", "2025-01-31");
        req.setStationCode("S9");
        tool.queryStationPerformance(req);

        assertThat(executor.sqls.get(0))
                .contains("expense_stationCode = ?")
                .doesNotContain("expense_tenant").doesNotContain("member_tenant")
                .doesNotContain("S9");
        assertThat(executor.paramSets.get(0)).containsExactly("2025-01-01", "2025-01-31", "S9");
    }

    @Test
    @DisplayName("门店业绩：传入租户 → 入参层显式拒绝，不静默改成全局统计也不查库")
    void station_rejectsTenantWithoutQuery() {
        StationPerformanceRequest req = stationReq("2025-01-01", "2025-01-31");
        req.setTenant("T1");
        StationPerformanceResult res = tool.queryStationPerformance(req);

        assertThat(res.getMessage()).contains("不支持").contains("租户");
        assertThat(res.getStations()).isNull();
        assertThat(executor.sqls).isEmpty();
    }

    // ------------------------------------------------------------------
    // 商品排行（PRODUCT_RANKING）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("商品排行：topN 默认10、上限100；rankBy 归一化为排序编码")
    void product_normalizesTopNAndRankBy() {
        executor.responder = sql -> List.of();

        ProductRankingRequest nullReq = new ProductRankingRequest();
        nullReq.setStartDate("2025-01-01");
        nullReq.setEndDate("2025-01-31");
        tool.queryProductRanking(nullReq);

        ProductRankingRequest bigReq = new ProductRankingRequest();
        bigReq.setStartDate("2025-01-01");
        bigReq.setEndDate("2025-01-31");
        bigReq.setTopN(500);
        bigReq.setRankBy("qty");
        tool.queryProductRanking(bigReq);

        assertThat(executor.sqls).hasSize(2);
        assertThat(executor.sqls.get(0)).contains("ORDER BY sales_amount desc").endsWith("LIMIT 10");
        assertThat(executor.sqls.get(1)).contains("ORDER BY quantity desc").endsWith("LIMIT 100");
    }

    @Test
    @DisplayName("商品排行：非法日期被拒绝且不查库")
    void product_invalidDate() {
        ProductRankingRequest r = new ProductRankingRequest();
        r.setStartDate("2025-13-99");
        r.setEndDate("2025-01-31");
        ProductRankingResult res = tool.queryProductRanking(r);
        assertThat(res.getMessage()).contains("日期格式");
        assertThat(executor.sqls).isEmpty();
    }

    @Test
    @DisplayName("商品排行：传入租户 → 明确告知不支持且不查库（旧“已忽略租户过滤”语义已推翻）")
    void product_notesTenantIgnored() {
        ProductRankingRequest r = new ProductRankingRequest();
        r.setStartDate("2025-01-01");
        r.setEndDate("2025-01-31");
        r.setTenant("T1");
        ProductRankingResult res = tool.queryProductRanking(r);

        assertThat(res.getMessage()).contains("不支持").contains("租户");
        // 入参层提前返回：不填业务字段（与 Phase 1 “只给 message”的返回结构一致）
        assertThat(res.getItems()).isNullOrEmpty();
        assertThat(executor.sqls).isEmpty();
    }

    @Test
    @DisplayName("商品排行：区间超 92 天上限被服务端拒绝（指标级 maxRangeDays）")
    void product_rejectsRangeOverMetricCap() {
        ProductRankingRequest r = new ProductRankingRequest();
        r.setStartDate("2026-01-01");
        r.setEndDate("2026-06-27");
        ProductRankingResult res = tool.queryProductRanking(r);

        assertThat(res.getMessage()).contains("92");
        assertThat(executor.sqls).isEmpty();
    }

    // ------------------------------------------------------------------
    // 会员总览（MEMBER_OVERVIEW）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("会员总览：人数取 MEMBER_OVERVIEW，金额取订单实付 MEMBER_CONSUMPTION，人均兜底")
    void member_computesAvgAndNullSafe() {
        executor.responder = sql -> sql.contains("expense_calendar_info")
                ? List.of(row("expense_member_count", new BigDecimal("5"),
                        "total_expense", new BigDecimal("1000"), "order_count", 8L))
                : List.of(row(
                        "total_members", 100L, "male_count", new BigDecimal("60"),
                        "female_count", new BigDecimal("30"), "unknown_count", null));

        MemberOverviewResult res = tool.queryMemberOverview(new MemberOverviewRequest());

        assertThat(res.getUnknownCount()).isEqualTo(0L);
        assertThat(res.getMaleCount()).isEqualTo(60L);
        assertThat(res.getExpenseMemberCount()).isEqualTo(5L);
        assertThat(res.getAvgExpensePerActiveMember()).isEqualByComparingTo("200.00");
        assertThat(res.getTotalExpenseAmount()).isEqualByComparingTo("1000");
    }

    @Test
    @DisplayName("会员总览：有消费会员数为0时人均返回0，不抛除零")
    void member_zeroDivisionGuard() {
        executor.responder = sql -> sql.contains("expense_calendar_info")
                ? List.of(row("expense_member_count", BigDecimal.ZERO,
                        "total_expense", new BigDecimal("1000"), "order_count", 1L))
                : List.of(row(
                        "total_members", 10L, "male_count", BigDecimal.TEN,
                        "female_count", BigDecimal.ZERO, "unknown_count", BigDecimal.ZERO));

        MemberOverviewResult res = tool.queryMemberOverview(new MemberOverviewRequest());

        assertThat(res.getAvgExpensePerActiveMember()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("会员总览：两条 SQL 均为累计口径不注入时间条件，且不包含任何租户列；传入租户则不查库")
    void member_isTimeAgnosticAndParameterized() {
        executor.responder = sql -> List.of(row("total_members", 1L));

        MemberOverviewRequest stationReq = new MemberOverviewRequest();
        stationReq.setStation("S3");
        tool.queryMemberOverview(stationReq);

        // MEMBER_OVERVIEW 1 条 + MEMBER_CONSUMPTION 明细 1 条 + 合计行 1 条
        assertThat(executor.sqls).hasSize(3);
        String membersSql = executor.sqls.get(0);
        String consumptionSql = executor.sqls.get(1);
        String consumptionTotalSql = executor.sqls.get(2);
        assertThat(membersSql)
                .contains("member_info_view")
                .doesNotContain("DATE_ADD")
                .contains("member_allot_store = ?");
        assertThat(consumptionSql)
                .as("金额改取订单实付，按分配门店过滤，不注入时间条件")
                .contains("expense_calendar_info")
                .contains("expense_realamt")
                .contains("member_allot_store = ?")
                .doesNotContain("DATE_ADD");
        assertThat(consumptionTotalSql)
                .as("合计行同为订单实付口径")
                .contains("expense_calendar_info")
                .doesNotContain("DATE_ADD");
        assertThat(membersSql + consumptionSql + consumptionTotalSql).doesNotContain("member_tenant");
        assertThat(executor.paramSets.get(0)).containsExactly("S3");
        assertThat(executor.paramSets.get(1)).containsExactly("S3");

        MemberOverviewRequest tenantReq = new MemberOverviewRequest();
        tenantReq.setTenant("T9");
        MemberOverviewResult res = tool.queryMemberOverview(tenantReq);

        assertThat(res.getMessage()).contains("不支持").contains("租户");
        assertThat(executor.sqls).as("拒绝租户入参时不得发起任何查询").hasSize(3);
    }

    // ------------------------------------------------------------------
    // 假执行器
    // ------------------------------------------------------------------

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    /**
     * 记录 SQL 与参数的假执行器：按 SQL 是否为分组查询返回不同桩数据，全程不连库。
     */
    private static final class FakeExecutor implements SqlExecutor {
        final List<String> sqls = new ArrayList<>();
        final List<List<Object>> paramSets = new ArrayList<>();
        Function<String, List<Map<String, Object>>> responder = sql -> List.of();

        @Override
        public List<Map<String, Object>> query(String sql, List<Object> params) {
            sqls.add(sql);
            paramSets.add(new ArrayList<>(params));
            List<Map<String, Object>> r = responder.apply(sql);
            return r == null ? List.of() : new ArrayList<>(r);
        }
    }
}
