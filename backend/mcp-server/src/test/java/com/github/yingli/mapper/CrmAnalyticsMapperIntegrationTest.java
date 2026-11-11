package com.github.yingli.mapper;

import com.github.yingli.entity.request.StationPerformanceRequest;
import com.github.yingli.entity.response.MemberOverviewResult;
import com.github.yingli.entity.response.ProductRankingResult;
import com.github.yingli.entity.response.StationPerformanceResult;
import com.github.yingli.mcp.tool.CrmAnalyticsTool;
import com.github.yingli.metrics.BaselineFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @ClassName CrmAnalyticsMapperIntegrationTest
 * @Description 连真实测试库执行 Phase1 三个聚合 Mapper，验证将要上线的 SQL 能被 MySQL 正确执行、
 *              且返回结构/口径自洽。断言只做“结构正确 + 口径自洽”级别的校验，不硬编码业务数字，
 *              实际数值通过控制台打印，供与手工基准 SQL 对账。
 *              时间窗口统一取对账基线区间（{@link BaselineFixtures}，2026-06-01 ~ 2026-06-27），
 *              避免同仓存在多套窗口；默认“本月”在测试库为空，因此必须显式传日期。
 * @Author crm-agent
 */
@SpringBootTest
class CrmAnalyticsMapperIntegrationTest {

    /** 与 crmtest 对账基线同窗口（含两端） */
    private static final String START = BaselineFixtures.windowStart();
    private static final String END = BaselineFixtures.windowEnd();

    @Autowired
    private ExpenseStatMapper expenseStatMapper;

    @Autowired
    private MemberStatMapper memberStatMapper;

    @Autowired
    private CrmAnalyticsTool crmAnalyticsTool;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 窗口取自 crmtest 基线；连到其它库时可能区间无数据，跳过而不是误报口径失败 */
    @BeforeEach
    void requireBaselineDatabase() {
        BaselineFixtures.assumeMatchesCurrentDb(jdbcTemplate.queryForObject("SELECT DATABASE()", String.class));
    }

    @Test
    @DisplayName("门店业绩工具(含空编码归一)：可连真库执行，每行均有展示名、退单可解析、按销售额降序")
    void stationPerformance_shouldExecute() {
        StationPerformanceRequest req = new StationPerformanceRequest();
        req.setStartDate(START);
        req.setEndDate(END);
        StationPerformanceResult resp = crmAnalyticsTool.queryStationPerformance(req);
        List<StationPerformanceResult.StationStat> rows = resp.getStations();

        assertThat(rows).as("基线窗口应至少有一个门店结果").isNotEmpty();

        boolean hasUnknown = false;
        java.math.BigDecimal prev = null;
        for (StationPerformanceResult.StationStat r : rows) {
            // 空门店编码已被工具层归为“未知门店”，因此每行都必须有非空展示名
            assertThat(r.getStationName()).as("门店展示名不应为空(空编码应归为未知门店)").isNotBlank();
            if ("未知门店".equals(r.getStationName())) {
                hasUnknown = true;
            }
            assertThat(r.getOrderCount()).as("订单数应>0").isNotNull().isGreaterThan(0L);
            assertThat(r.getSalesAmount()).as("销售额不应为null(退单负数也应可解析)").isNotNull();
            if (prev != null) {
                assertThat(prev).as("应按销售额降序").isGreaterThanOrEqualTo(r.getSalesAmount());
            }
            prev = r.getSalesAmount();
        }

        System.out.println("[对账-门店业绩] 门店数=" + rows.size()
                + " 含未知门店行=" + hasUnknown + " 合计销售额=" + resp.getTotal().getSalesAmount());
        rows.stream().limit(5).forEach(r -> System.out.println(
                "  station=" + r.getStationCode() + " name=" + r.getStationName()
                        + " sales=" + r.getSalesAmount() + " orders=" + r.getOrderCount()));
    }

    @Test
    @DisplayName("商品排行聚合SQL：可在真库执行，TopN受限、SKU非空、销售额可解析")
    void productRanking_shouldExecute() {
        int topN = 10;
        List<ProductRankingResult.ProductStat> items =
                expenseStatMapper.productRanking(START, END, null, "sales", topN);

        assertThat(items).as("基线窗口应有商品明细聚合结果").isNotEmpty();
        assertThat(items.size()).as("返回条数不应超过 topN").isLessThanOrEqualTo(topN);

        java.math.BigDecimal prev = null;
        for (ProductRankingResult.ProductStat it : items) {
            assertThat(it.getSku()).as("SKU(expense_d_gdcode)不应为空").isNotBlank();
            assertThat(it.getSalesAmount()).as("销售额不应为null").isNotNull();
            assertThat(it.getQuantity()).as("销量不应为null").isNotNull();
            if (prev != null) {
                assertThat(prev).as("按销售额排行应降序").isGreaterThanOrEqualTo(it.getSalesAmount());
            }
            prev = it.getSalesAmount();
        }

        System.out.println("[对账-商品排行] 返回条数=" + items.size());
        items.forEach(it -> System.out.println(
                "  sku=" + it.getSku() + " name=" + it.getProductName()
                        + " sales=" + it.getSalesAmount() + " qty=" + it.getQuantity()));
    }

    @Test
    @DisplayName("会员总览聚合SQL：可在真库执行，性别三分区自洽(男+女+未知=总数)")
    void memberOverview_shouldExecuteAndBeSelfConsistent() {
        MemberOverviewResult r = memberStatMapper.memberOverview(null, null);

        assertThat(r).as("会员总览不应返回null").isNotNull();
        assertThat(r.getTotalMembers()).as("会员总数应>0").isNotNull().isGreaterThan(0L);
        assertThat(r.getTotalExpenseAmount()).as("累计消费额不应为null").isNotNull();

        long male = nz(r.getMaleCount());
        long female = nz(r.getFemaleCount());
        long unknown = nz(r.getUnknownCount());
        // 口径自洽：性别三分区应恰好覆盖全部会员
        assertThat(male + female + unknown)
                .as("男+女+未知 应等于会员总数（CASE 分支无遗漏无重叠）")
                .isEqualTo(r.getTotalMembers());

        System.out.println("[对账-会员总览] total=" + r.getTotalMembers()
                + " male=" + male + " female=" + female + " unknown=" + unknown
                + " expenseMemberCount=" + r.getExpenseMemberCount()
                + " totalExpense=" + r.getTotalExpenseAmount());
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }
}
