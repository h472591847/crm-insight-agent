package com.github.yingli.metrics;

import com.github.yingli.metrics.compile.MetricSqlCompiler;
import com.github.yingli.metrics.request.MetricQueryRequest;
import com.github.yingli.entity.response.MemberOverviewResult;
import com.github.yingli.metrics.response.MetricResult;
import com.github.yingli.metrics.service.MetricQueryService;
import com.github.yingli.entity.response.ProductRankingResult;
import com.github.yingli.entity.response.StationPerformanceResult;
import com.github.yingli.mapper.ExpenseStatMapper;
import com.github.yingli.mapper.MemberStatMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @ClassName MetricsAsCodeMigrationReconciliationTest
 * @Description Phase 1 三指标配置化迁移的逐值对账：编译层结果必须与现有手写 Mapper
 * 在同参数、同库、同区间下**完全一致**（方案 §8.2 的验收口径）。
 * <p>
 * 两层断言分工明确：
 * <ol>
 *   <li><b>主对账</b>：同次执行里逐门店 / 逐 SKU / 逐字段比对手写 SQL 与编译 SQL，证明 SQL 生成正确；</li>
 *   <li><b>二次校验</b>：组数、合计、Top1 命中 {@link BaselineFixtures} 中的真实库基线，证明口径未整体漂移。</li>
 * </ol>
 * 基线数值不再写在本类里：它们是 2026-10-30 对 crm_dev 只读探测的结果（窗口 2026-06-01~2026-06-27，
 * 会员 628,527 / 门店 314 组），存于 {@code src/test/resources/baseline/crm_dev-baseline.yaml}，
 * 重新体检只需重跑 {@code scripts/probe/step0_baseline_full.py}。
 * 窗口取固定历史区间而非“当前时间”，结果可反复复核。
 * @Author crm-agent
 */
@SpringBootTest
class MetricsAsCodeMigrationReconciliationTest {

    private static final String START = BaselineFixtures.windowStart();
    private static final String END = BaselineFixtures.windowEnd();

    @Autowired
    private MetricQueryService metricQueryService;

    @Autowired
    private ExpenseStatMapper expenseStatMapper;

    @Autowired
    private MemberStatMapper memberStatMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 基线是从 crm_dev 采集的；连到其它库时跳过，避免把环境问题误读成口径错误 */
    @BeforeEach
    void requireBaselineDatabase() {
        BaselineFixtures.assumeMatchesCurrentDb(jdbcTemplate.queryForObject("SELECT DATABASE()", String.class));
    }

    private MetricQueryRequest orderReq(String metric) {
        MetricQueryRequest r = new MetricQueryRequest();
        r.setMetric(metric);
        r.setStartDate(START);
        r.setEndDate(END);
        return r;
    }

    @Test
    @DisplayName("STATION_SALES：编译结果与手写 XML 逐门店逐值一致，合计与 Top1 命中 crm_dev 基线")
    void stationSales_matchesHandWrittenMapper() {
        List<StationPerformanceResult.StationStat> baseline =
                expenseStatMapper.stationPerformance(START, END, null, null);

        MetricQueryRequest q = orderReq("STATION_SALES");
        q.setDimensions(List.of("STATION"));
        MetricResult res = metricQueryService.query("order", q);
        assertThat(res.getErrorCode()).isNull();

        // 行数一致（含"未知门店"分组），并命中 crm_dev 基线组数
        assertThat(res.rowCount()).isEqualTo(baseline.size());
        assertThat(res.rowCount()).as("门店分组数=基线")
                .isEqualTo((int) BaselineFixtures.count("stationPerformance", "groupCount"));

        Map<String, StationPerformanceResult.StationStat> byCode = new HashMap<>();
        for (StationPerformanceResult.StationStat st : baseline) {
            byCode.put(norm(st.getStationCode()), st);
        }

        BigDecimal prevSales = null;
        for (int i = 0; i < res.rowCount(); i++) {
            String code = norm((String) res.value(i, "STATION"));
            StationPerformanceResult.StationStat exp = byCode.get(code);
            assertThat(exp).as("编译结果出现手写 SQL 没有的门店分组：" + code).isNotNull();

            BigDecimal sales = (BigDecimal) res.value(i, "SALES");
            long orders = ((Number) res.value(i, "ORDERS")).longValue();
            assertThat(sales).as("门店 " + code + " 销售额必须逐值一致")
                    .isEqualByComparingTo(exp.getSalesAmount());
            assertThat(orders).as("门店 " + code + " 订单数必须逐值一致").isEqualTo(exp.getOrderCount());
            // 客单价：与 Phase 1 divide() 语义一致（HALF_UP 保留 2 位，除零为 0）
            BigDecimal expectAvg = orders <= 0 ? BigDecimal.ZERO
                    : sales.divide(BigDecimal.valueOf(orders), 2, RoundingMode.HALF_UP);
            assertThat((BigDecimal) res.value(i, "AVG_ORDER_VALUE"))
                    .isEqualByComparingTo(expectAvg);
            // 门店名称：空编码+空名称归并"未知门店"（业务口径 2026-10-28 确认）
            String expectName = exp.getStationName();
            if ((expectName == null || expectName.isBlank()) && code.isEmpty()) {
                expectName = "未知门店";
            }
            assertThat(res.value(i, "station_name")).as("门店展示名一致").isEqualTo(expectName);

            if (prevSales != null) {
                assertThat(prevSales).as("必须按销售额降序").isGreaterThanOrEqualTo(sales);
            }
            prevSales = sales;
        }

        // 合计行：全量合计（非 TopN 累加），并命中 crm_dev 基线
        BigDecimal expectTotalSales = baseline.stream().map(StationPerformanceResult.StationStat::getSalesAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        long expectTotalOrders = baseline.stream().mapToLong(StationPerformanceResult.StationStat::getOrderCount).sum();
        assertThat((BigDecimal) res.totalValue("SALES")).isEqualByComparingTo(expectTotalSales);
        assertThat((BigDecimal) res.totalValue("SALES"))
                .isEqualByComparingTo(BaselineFixtures.decimal("stationPerformance", "totalSalesAmount"));
        assertThat(((Number) res.totalValue("ORDERS")).longValue()).isEqualTo(expectTotalOrders);
        assertThat(((Number) res.totalValue("ORDERS")).longValue())
                .as("合计有有效订单才能算客单价").isPositive();
        assertThat((BigDecimal) res.totalValue("AVG_ORDER_VALUE"))
                .isEqualByComparingTo(expectTotalSales.divide(BigDecimal.valueOf(expectTotalOrders), 2, RoundingMode.HALF_UP));

        // Top1 基线（门店名 + 销售额）
        assertThat(String.valueOf(res.value(0, "station_name")))
                .isEqualTo(BaselineFixtures.text("stationPerformance", "top1", "stationName"));
        assertThat((BigDecimal) res.value(0, "SALES"))
                .isEqualByComparingTo(BaselineFixtures.decimal("stationPerformance", "top1", "salesAmount"));
        assertThat(String.valueOf(res.value(0, "STATION")))
                .isEqualTo(BaselineFixtures.text("stationPerformance", "top1", "stationCode"));
    }

    @Test
    @DisplayName("PRODUCT_RANKING：Top10 与手写 XML 逐 SKU 逐值一致，Top1 命中 crm_dev 基线（异常 SKU 同样入榜）")
    void productRanking_matchesHandWrittenMapper() {
        List<ProductRankingResult.ProductStat> baseline =
                expenseStatMapper.productRanking(START, END, null, "sales", 10);

        MetricQueryRequest q = orderReq("PRODUCT_RANKING");
        q.setDimensions(List.of("SKU"));
        MetricResult res = metricQueryService.query("order", q);
        assertThat(res.getErrorCode()).isNull();
        assertThat(res.rowCount()).isEqualTo(baseline.size());

        for (int i = 0; i < res.rowCount(); i++) {
            ProductRankingResult.ProductStat exp = baseline.get(i);
            assertThat(res.value(i, "SKU")).isEqualTo(exp.getSku());
            assertThat(res.value(i, "product_name")).isEqualTo(exp.getProductName());
            assertThat((BigDecimal) res.value(i, "SALES")).isEqualByComparingTo(exp.getSalesAmount());
            assertThat((BigDecimal) res.value(i, "QTY")).isEqualByComparingTo(exp.getQuantity());
        }

        // 基线 Top1：商品名/SKU/销售额/销量均取 crm_dev 基线（不在测试代码里写死数字）
        assertThat(String.valueOf(res.value(0, "product_name")))
                .isEqualTo(BaselineFixtures.productTopText(1, "productName"));
        assertThat(String.valueOf(res.value(0, "SKU")))
                .isEqualTo(BaselineFixtures.productTopText(1, "sku"));
        assertThat((BigDecimal) res.value(0, "SALES"))
                .isEqualByComparingTo(BaselineFixtures.productTopDecimal(1, "salesAmount"));
        assertThat((BigDecimal) res.value(0, "QTY"))
                .isEqualByComparingTo(BaselineFixtures.productTopDecimal(1, "quantity"));
    }

    @Test
    @DisplayName("MEMBER_OVERVIEW：全局人数聚合与手写 XML 逐字段一致，命中 crm_dev 基线")
    void memberOverview_matchesHandWrittenMapper() {
        MemberOverviewResult baseline = memberStatMapper.memberOverview(null, null);

        MetricResult res = metricQueryService.query("member", orderReqNoRange("MEMBER_OVERVIEW"));
        assertThat(res.getErrorCode()).isNull();
        assertThat(res.rowCount()).isEqualTo(1);

        assertThat(res.value(0, "TOTAL_MEMBERS")).isEqualTo(baseline.getTotalMembers());
        assertThat((BigDecimal) res.value(0, "MALE_COUNT"))
                .isEqualByComparingTo(String.valueOf(baseline.getMaleCount()));
        assertThat((BigDecimal) res.value(0, "FEMALE_COUNT"))
                .isEqualByComparingTo(String.valueOf(baseline.getFemaleCount()));
        assertThat((BigDecimal) res.value(0, "UNKNOWN_COUNT"))
                .isEqualByComparingTo(String.valueOf(baseline.getUnknownCount()));

        // crm_dev 基线二次校验（数值来自 baseline yaml，口径自洽：男+女+未知=总数）
        assertThat(res.value(0, "TOTAL_MEMBERS"))
                .isEqualTo(BaselineFixtures.count("memberOverview", "totalMembers"));
        assertThat((BigDecimal) res.value(0, "MALE_COUNT")).isEqualByComparingTo(
                String.valueOf(BaselineFixtures.count("memberOverview", "maleCount")));
        assertThat((BigDecimal) res.value(0, "FEMALE_COUNT")).isEqualByComparingTo(
                String.valueOf(BaselineFixtures.count("memberOverview", "femaleCount")));
        assertThat((BigDecimal) res.value(0, "UNKNOWN_COUNT")).isEqualByComparingTo(
                String.valueOf(BaselineFixtures.count("memberOverview", "unknownCount")));

        // 2026-11-09：消费金额已从本指标移除（来源错误），改由 MEMBER_CONSUMPTION 从订单实付汇总
        assertThat(res.getErrorCode()).isNull();
        assertThat(hasColumn(res, "TOTAL_EXPENSE")).isFalse();
        assertThat(hasColumn(res, "EXPENSE_MEMBERS")).isFalse();
    }

    @Test
    @DisplayName("MEMBER_CONSUMPTION：订单实付聚合与独立 oracle 一致（1:1 join 不放大金额）")
    void memberConsumption_matchesOrderOracle() {
        String oracle = "SELECT COALESCE(SUM(CAST(NULLIF(TRIM(e.expense_realamt), '') AS DECIMAL(20, 2))), 0) AS total, "
                + "COUNT(DISTINCT e.expense_mobile) AS buyers, COUNT(*) AS orders "
                + "FROM expense_calendar_info e JOIN member_info_view m ON e.expense_mobile = m.member_mobile "
                + "WHERE e.expense_state = '10' AND e.del_flag = '0' AND m.del_flag = '0'";
        Map<String, Object> expected = jdbcTemplate.queryForMap(oracle);

        MetricResult res = metricQueryService.query("member", orderReqNoRange("MEMBER_CONSUMPTION"));
        assertThat(res.getErrorCode()).isNull();
        assertThat(res.rowCount()).isEqualTo(1);

        assertThat((BigDecimal) res.value(0, "TOTAL_EXPENSE")).isEqualByComparingTo(
                ((BigDecimal) expected.get("total")).setScale(2, RoundingMode.HALF_UP));
        assertThat(((Number) res.value(0, "BUYERS")).longValue())
                .isEqualTo(((Number) expected.get("buyers")).longValue());
        assertThat(((Number) res.value(0, "ORDERS")).longValue())
                .isEqualTo(((Number) expected.get("orders")).longValue());
    }

    private boolean hasColumn(MetricResult res, String code) {
        return res.getColumns() != null && res.getColumns().stream()
                .anyMatch(c -> code.equals(c.getCode()));
    }

    @Test
    @DisplayName("GENDER_CONSUMPTION_COMPARE：受控 LEFT JOIN 与独立 oracle 逐组一致；三组求和命中合计（证明未放大订单行）")
    void genderComparison_joinMatchesOracleAndInvariant() {
        // 主对账 oracle：测试内独立书写的原生 join SQL（不复用 MetricSqlCompiler），逐性别组比对
        String oracle = "SELECT CASE WHEN m.member_sex = 1 THEN '男' WHEN m.member_sex = 2 THEN '女' "
                + "ELSE '未知性别' END AS gender, "
                + "COALESCE(SUM(CAST(NULLIF(TRIM(e.expense_realamt), '') AS DECIMAL(20, 2))), 0) AS sales, "
                + "COUNT(*) AS orders, COUNT(DISTINCT e.expense_mobile) AS buyers "
                + "FROM expense_calendar_info e LEFT JOIN member_info_view m ON e.expense_mobile = m.member_mobile "
                + "WHERE (e.expense_state = '10') AND (e.del_flag = '0') "
                + "AND ((m.del_flag = '0' OR m.member_mobile IS NULL)) "
                + "AND e.expense_buyDate >= ? AND e.expense_buyDate < DATE_ADD(?, INTERVAL 1 DAY) "
                + "GROUP BY 1";
        List<Map<String, Object>> oracleRows = jdbcTemplate.queryForList(oracle, START, END);
        Map<String, Map<String, Object>> expByGender = new HashMap<>();
        for (Map<String, Object> r : oracleRows) {
            expByGender.put(String.valueOf(r.get("gender")), r);
        }

        MetricQueryRequest q = orderReq("GENDER_CONSUMPTION_COMPARE");
        q.setDimensions(List.of("GENDER"));
        MetricResult res = metricQueryService.query("order", q);
        assertThat(res.getErrorCode()).isNull();
        assertThat(res.rowCount()).as("性别分组数=基线")
                .isEqualTo((int) BaselineFixtures.count("genderComparison", "groupCount"));

        BigDecimal sumSales = BigDecimal.ZERO;
        long sumOrders = 0;
        long sumBuyers = 0;
        for (int i = 0; i < res.rowCount(); i++) {
            String gender = String.valueOf(res.value(i, "GENDER"));
            Map<String, Object> exp = expByGender.get(gender);
            assertThat(exp).as("编译结果出现 oracle 没有的性别分组：" + gender).isNotNull();
            assertThat((BigDecimal) res.value(i, "SALES")).as("性别 " + gender + " 销售额逐值一致")
                    .isEqualByComparingTo((BigDecimal) exp.get("sales"));
            long expOrders = ((Number) exp.get("orders")).longValue();
            long expBuyers = ((Number) exp.get("buyers")).longValue();
            assertThat(((Number) res.value(i, "ORDERS")).longValue())
                    .as("性别 " + gender + " 订单数一致").isEqualTo(expOrders);
            assertThat(((Number) res.value(i, "BUYERS")).longValue())
                    .as("性别 " + gender + " 去重购买人数一致").isEqualTo(expBuyers);
            sumSales = sumSales.add((BigDecimal) res.value(i, "SALES"));
            sumOrders += expOrders;
            sumBuyers += expBuyers;
        }

        // 关键不变式：三组求和必须等于已确认的合计（join 放大订单行会立即破坏此断言）
        assertThat(sumSales).as("三组销售额求和=门店合计基线（无行放大）")
                .isEqualByComparingTo(BaselineFixtures.decimal("stationPerformance", "totalSalesAmount"));
        assertThat(sumOrders).as("三组订单数求和=门店合计基线（无行放大）")
                .isEqualTo(BaselineFixtures.count("stationPerformance", "totalOrderCount"));
        assertThat(sumBuyers).as("三组去重人数求和=全局活跃会员（member_mobile 唯一，跨组不重复）")
                .isEqualTo(BaselineFixtures.count("activeMember", "distinctMembersTotal"));

        // 二次校验：逐性别命中 crm_dev 基线标准答案
        assertThat((BigDecimal) res.value(labelRowIndex(res, "男"), "SALES"))
                .as("男销售额命中基线")
                .isEqualByComparingTo(BaselineFixtures.decimal("genderComparison", "male", "salesAmount"));
        assertThat((BigDecimal) res.value(labelRowIndex(res, "女"), "SALES"))
                .as("女销售额命中基线")
                .isEqualByComparingTo(BaselineFixtures.decimal("genderComparison", "female", "salesAmount"));
        assertThat((BigDecimal) res.value(labelRowIndex(res, "未知性别"), "SALES"))
                .as("未知性别销售额命中基线")
                .isEqualByComparingTo(BaselineFixtures.decimal("genderComparison", "unknown", "salesAmount"));
    }

    /** 在结果集中按维度标签定位行号（分组顺序受排序影响，不能假设固定下标）。 */
    private int labelRowIndex(MetricResult res, String label) {
        for (int i = 0; i < res.rowCount(); i++) {
            if (label.equals(String.valueOf(res.value(i, "GENDER")))) {
                return i;
            }
        }
        throw new AssertionError("结果集缺少性别分组：" + label);
    }

    private MetricQueryRequest orderReqNoRange(String metric) {
        MetricQueryRequest r = new MetricQueryRequest();
        r.setMetric(metric);
        return r;
    }

    private String norm(String code) {
        return code == null ? "" : code.trim();
    }
}
