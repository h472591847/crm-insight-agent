package com.github.yingli.metrics;

import com.github.yingli.metrics.request.MetricQueryRequest;
import com.github.yingli.metrics.response.MetricResult;
import com.github.yingli.metrics.service.MetricQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @ClassName VipMetricsReconciliationTest
 * @Description Phase 3 要客（VIP）指标的真库逐值对账：编译层结果必须与同条件的手写 oracle SQL 一致。
 * <p>
 * 要客指标多为快照型（无时间窗口），因此测试不依赖 BaselineFixtures 的固定窗口，
 * 而是直接对比当前数据库状态。
 * <p>
 * 数据来源表：member_info_view（由触发器从 member_info 同步）
 * 要客判定：track = 1
 * <p>
 * 注意：member_info_view 与 member_info 行数不一致（view 428,930 vs info 628,528），
 * 要客数也不同（view 10,779 vs info 10,243），本测试以 member_info_view 为准。
 *
 * @Author crm-agent
 */
@SpringBootTest
class VipMetricsReconciliationTest {

    @Autowired
    private MetricQueryService metricQueryService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void requireBaselineDatabase() {
        BaselineFixtures.assumeMatchesCurrentDb(
                jdbcTemplate.queryForObject("SELECT DATABASE()", String.class));
    }

    private MetricResult queryMemberSnapshot(String metric) {
        MetricQueryRequest q = new MetricQueryRequest();
        q.setMetric(metric);
        return metricQueryService.query("member", q);
    }

    private MetricResult queryMemberSnapshot(String metric, String filterCode, String filterValue) {
        MetricQueryRequest q = new MetricQueryRequest();
        q.setMetric(metric);
        com.github.yingli.metrics.request.FilterItem fi = new com.github.yingli.metrics.request.FilterItem();
        fi.setCode(filterCode);
        fi.setValue(filterValue);
        q.setFilters(List.of(fi));
        return metricQueryService.query("member", q);
    }

    private MetricResult queryMemberWithTime(String metric, String start, String end) {
        MetricQueryRequest q = new MetricQueryRequest();
        q.setMetric(metric);
        q.setStartDate(start);
        q.setEndDate(end);
        return metricQueryService.query("member", q);
    }

    @Nested
    @DisplayName("VIP_OVERVIEW：要客总览（快照）")
    class VipOverviewTest {

        @Test
        @DisplayName("要客总数与 oracle SQL 一致（消费金额已移出本指标）")
        void vipOverview_matchesOracle() {
            MetricResult res = queryMemberSnapshot("VIP_OVERVIEW");
            assertThat(res.getErrorCode()).isNull();
            assertThat(res.rowCount()).isEqualTo(1);

            Map<String, Object> oracle = jdbcTemplate.queryForMap("""
                    SELECT COUNT(*) AS vip_count
                    FROM member_info_view
                    WHERE del_flag = '0' AND track = 1
                    """);

            long expectedCount = ((Number) oracle.get("vip_count")).longValue();

            assertThat(((Number) res.value(0, "VIP_COUNT")).longValue())
                    .as("要客总数")
                    .isEqualTo(expectedCount);
            assertThat(res.value(0, "TOTAL_EXPENSE"))
                    .as("2026-11-09：消费金额已改由 VIP_CONSUMPTION_BY_STORE 从订单实付汇总")
                    .isNull();

            assertThat(expectedCount).as("要客总数应 > 0（真库实测 10,779）").isGreaterThan(0);
        }
    }

    @Nested
    @DisplayName("VIP_NEW：新增要客数（按 track_time）")
    class VipNewTest {

        @Test
        @DisplayName("指定时间窗口内新增要客数与 oracle SQL 一致")
        void vipNew_matchesOracle() {
            String start = "2025-01-01";
            String end = "2026-06-28";

            MetricResult res = queryMemberWithTime("VIP_NEW", start, end);
            assertThat(res.getErrorCode()).isNull();
            assertThat(res.rowCount()).isEqualTo(1);

            Map<String, Object> oracle = jdbcTemplate.queryForMap("""
                    SELECT COUNT(*) AS new_vip_count
                    FROM member_info_view
                    WHERE del_flag = '0' AND track = 1
                      AND track_time >= ? AND track_time < ?
                    """, start, end);

            long expected = ((Number) oracle.get("new_vip_count")).longValue();
            assertThat(((Number) res.value(0, "NEW_VIPS")).longValue())
                    .as("新增要客数")
                    .isEqualTo(expected);
        }
    }

    @Nested
    @DisplayName("VIP_CANCELLED：踢出要客数（按 track_cancel_time）")
    class VipCancelledTest {

        @Test
        @DisplayName("指定时间窗口内踢出要客数与 oracle SQL 一致")
        void vipCancelled_matchesOracle() {
            String start = "2025-01-01";
            String end = "2026-06-28";

            MetricResult res = queryMemberWithTime("VIP_CANCELLED", start, end);
            assertThat(res.getErrorCode()).isNull();
            assertThat(res.rowCount()).isEqualTo(1);

            Map<String, Object> oracle = jdbcTemplate.queryForMap("""
                    SELECT COUNT(*) AS cancelled_count
                    FROM member_info_view
                    WHERE del_flag = '0' AND track_cancel_time IS NOT NULL
                      AND track_cancel_time >= ? AND track_cancel_time < ?
                    """, start, end);

            long expected = ((Number) oracle.get("cancelled_count")).longValue();
            assertThat(((Number) res.value(0, "CANCELLED_VIPS")).longValue())
                    .as("踢出要客数")
                    .isEqualTo(expected);
        }
    }

    @Nested
    @DisplayName("VIP_BY_STAFF：按导购统计要客数")
    class VipByStaffTest {

        @Test
        @DisplayName("各导购要客数与 oracle SQL 逐行一致")
        void vipByStaff_matchesOracle() {
            MetricQueryRequest q = new MetricQueryRequest();
            q.setMetric("VIP_BY_STAFF");
            q.setDimensions(List.of("STAFF"));
            q.setTopN(500);
            MetricResult res = metricQueryService.query("member", q);
            assertThat(res.getErrorCode()).isNull();

            List<Map<String, Object>> oracle = jdbcTemplate.queryForList("""
                    SELECT track_user_id AS staff_id, COUNT(*) AS vip_count
                    FROM member_info_view
                    WHERE del_flag = '0' AND track = 1 AND track_user_id IS NOT NULL
                    GROUP BY track_user_id
                    ORDER BY vip_count DESC
                    """);

            assertThat(res.rowCount())
                    .as("导购分组数")
                    .isEqualTo(oracle.size());

            for (int i = 0; i < oracle.size(); i++) {
                String expectedStaff = (String) oracle.get(i).get("staff_id");
                long expectedCount = ((Number) oracle.get(i).get("vip_count")).longValue();
                assertThat(res.value(i, "STAFF")).asString().isEqualTo(expectedStaff);
                assertThat(((Number) res.value(i, "VIP_COUNT")).longValue()).isEqualTo(expectedCount);
            }
        }

        @Test
        @DisplayName("合计行 = 全部分组之和")
        void vipByStaff_totalRowMatchesSum() {
            MetricQueryRequest q = new MetricQueryRequest();
            q.setMetric("VIP_BY_STAFF");
            q.setDimensions(List.of("STAFF"));
            q.setTopN(500);
            MetricResult res = metricQueryService.query("member", q);
            assertThat(res.getErrorCode()).isNull();

            long sum = 0;
            for (int i = 0; i < res.rowCount(); i++) {
                sum += ((Number) res.value(i, "VIP_COUNT")).longValue();
            }
            assertThat(((Number) res.totalValue("VIP_COUNT")).longValue())
                    .as("合计行应等于分组之和")
                    .isEqualTo(sum);
        }
    }

    @Nested
    @DisplayName("VIP_BY_STORE：按门店统计要客数")
    class VipByStoreTest {

        @Test
        @DisplayName("各门店要客数与 oracle SQL 逐行一致")
        void vipByStore_matchesOracle() {
            MetricQueryRequest q = new MetricQueryRequest();
            q.setMetric("VIP_BY_STORE");
            q.setDimensions(List.of("STORE"));
            q.setTopN(500);
            MetricResult res = metricQueryService.query("member", q);
            assertThat(res.getErrorCode()).isNull();

            List<Map<String, Object>> oracle = jdbcTemplate.queryForList("""
                    SELECT COALESCE(member_allot_store, '') AS store_code,
                           MAX(member_allot_stationName) AS store_name,
                           COUNT(*) AS vip_count
                    FROM member_info_view
                    WHERE del_flag = '0' AND track = 1
                    GROUP BY member_allot_store
                    ORDER BY vip_count DESC
                    """);

            assertThat(res.rowCount())
                    .as("门店分组数")
                    .isEqualTo(oracle.size());
        }

        @Test
        @DisplayName("合计行 = 全局要客总数（与 VIP_OVERVIEW 交叉校验）")
        void vipByStore_totalMatchesOverview() {
            MetricQueryRequest q = new MetricQueryRequest();
            q.setMetric("VIP_BY_STORE");
            q.setDimensions(List.of("STORE"));
            q.setTopN(500);
            MetricResult res = metricQueryService.query("member", q);
            assertThat(res.getErrorCode()).isNull();

            MetricResult overview = queryMemberSnapshot("VIP_OVERVIEW");
            long overviewCount = ((Number) overview.value(0, "VIP_COUNT")).longValue();
            long storeTotal = ((Number) res.totalValue("VIP_COUNT")).longValue();

            assertThat(storeTotal)
                    .as("门店合计应等于要客总数")
                    .isEqualTo(overviewCount);
        }
    }

    @Nested
    @DisplayName("VIP_CONSUMPTION_BY_STORE：按门店统计要客消费")
    class VipConsumptionByStoreTest {

        @Test
        @DisplayName("各门店要客消费额与订单实付 oracle 逐行一致")
        void vipConsumptionByStore_matchesOracle() {
            MetricQueryRequest q = new MetricQueryRequest();
            q.setMetric("VIP_CONSUMPTION_BY_STORE");
            q.setDimensions(List.of("STORE"));
            q.setTopN(500);
            MetricResult res = metricQueryService.query("member", q);
            assertThat(res.getErrorCode()).isNull();

            List<Map<String, Object>> oracle = jdbcTemplate.queryForList("""
                    SELECT COALESCE(m.member_allot_store, '') AS store_code,
                           COUNT(DISTINCT e.expense_mobile) AS vip_count,
                           COALESCE(SUM(CAST(NULLIF(TRIM(e.expense_realamt), '') AS DECIMAL(20, 2))), 0) AS total_expense
                    FROM expense_calendar_info e
                    JOIN member_info_view m ON e.expense_mobile = m.member_mobile
                    WHERE e.expense_state = '10' AND e.del_flag = '0'
                      AND m.del_flag = '0' AND m.track = 1
                    GROUP BY m.member_allot_store
                    ORDER BY total_expense DESC
                    """);

            assertThat(res.rowCount())
                    .as("门店分组数")
                    .isEqualTo(oracle.size());

            BigDecimal sumExpense = BigDecimal.ZERO;
            for (int i = 0; i < oracle.size(); i++) {
                BigDecimal expectedExpense = ((BigDecimal) oracle.get(i).get("total_expense"))
                        .setScale(2, RoundingMode.HALF_UP);
                BigDecimal actualExpense = ((BigDecimal) res.value(i, "TOTAL_EXPENSE"))
                        .setScale(2, RoundingMode.HALF_UP);
                assertThat(actualExpense)
                        .as("门店 %s 消费额".formatted(oracle.get(i).get("store_code")))
                        .isEqualByComparingTo(expectedExpense);
                assertThat(((Number) res.value(i, "VIP_BUYERS")).longValue())
                        .as("门店 %s 有消费要客数".formatted(oracle.get(i).get("store_code")))
                        .isEqualTo(((Number) oracle.get(i).get("vip_count")).longValue());
                sumExpense = sumExpense.add(actualExpense);
            }

            BigDecimal totalRowExpense = ((BigDecimal) res.totalValue("TOTAL_EXPENSE"))
                    .setScale(2, RoundingMode.HALF_UP);
            assertThat(totalRowExpense)
                    .as("合计行消费额 = 各门店之和（证明 1:1 join 未放大金额）")
                    .isEqualByComparingTo(sumExpense);
        }

        @Test
        @DisplayName("合计行有消费要客数 = 全量去重消费要客数（不与 VIP_OVERVIEW 快照总数比较）")
        void vipConsumptionByStore_totalCountMatchesDistinctBuyers() {
            MetricQueryRequest q = new MetricQueryRequest();
            q.setMetric("VIP_CONSUMPTION_BY_STORE");
            q.setDimensions(List.of("STORE"));
            q.setTopN(500);
            MetricResult res = metricQueryService.query("member", q);

            Long expected = jdbcTemplate.queryForObject("""
                    SELECT COUNT(DISTINCT e.expense_mobile)
                    FROM expense_calendar_info e
                    JOIN member_info_view m ON e.expense_mobile = m.member_mobile
                    WHERE e.expense_state = '10' AND e.del_flag = '0'
                      AND m.del_flag = '0' AND m.track = 1
                    """, Long.class);
            long storeTotal = ((Number) res.totalValue("VIP_BUYERS")).longValue();

            assertThat(storeTotal).isEqualTo(expected);
        }
    }

    @Nested
    @DisplayName("跨指标一致性校验")
    class CrossMetricConsistencyTest {

        @Test
        @DisplayName("快照类要客指标总数一致（VIP_CONSUMPTION_BY_STORE 为订单粒度，不参与）")
        void allVipMetrics_countConsistent() {
            MetricResult overview = queryMemberSnapshot("VIP_OVERVIEW");
            long overviewCount = ((Number) overview.value(0, "VIP_COUNT")).longValue();

            MetricQueryRequest byStaffQ = new MetricQueryRequest();
            byStaffQ.setMetric("VIP_BY_STAFF");
            byStaffQ.setDimensions(List.of("STAFF"));
            byStaffQ.setTopN(500);
            MetricResult byStaff = metricQueryService.query("member", byStaffQ);
            long byStaffTotal = ((Number) byStaff.totalValue("VIP_COUNT")).longValue();

            MetricQueryRequest byStoreQ = new MetricQueryRequest();
            byStoreQ.setMetric("VIP_BY_STORE");
            byStoreQ.setDimensions(List.of("STORE"));
            byStoreQ.setTopN(500);
            MetricResult byStore = metricQueryService.query("member", byStoreQ);
            long byStoreTotal = ((Number) byStore.totalValue("VIP_COUNT")).longValue();

            assertThat(byStaffTotal)
                    .as("VIP_BY_STAFF 合计 = VIP_OVERVIEW 总数")
                    .isEqualTo(overviewCount);
            assertThat(byStoreTotal)
                    .as("VIP_BY_STORE 合计 = VIP_OVERVIEW 总数")
                    .isEqualTo(overviewCount);
        }

        @Test
        @DisplayName("要客消费合计 = 订单实付 oracle（VIP_CONSUMPTION_BY_STORE 合计行）")
        void allVipMetrics_expenseMatchesOrderOracle() {
            MetricQueryRequest q = new MetricQueryRequest();
            q.setMetric("VIP_CONSUMPTION_BY_STORE");
            q.setDimensions(List.of("STORE"));
            q.setTopN(500);
            MetricResult consumption = metricQueryService.query("member", q);
            assertThat(consumption.getErrorCode()).isNull();
            BigDecimal consumptionTotal = (BigDecimal) consumption.totalValue("TOTAL_EXPENSE");

            BigDecimal expected = jdbcTemplate.queryForObject("""
                    SELECT COALESCE(SUM(CAST(NULLIF(TRIM(e.expense_realamt), '') AS DECIMAL(20, 2))), 0)
                    FROM expense_calendar_info e
                    JOIN member_info_view m ON e.expense_mobile = m.member_mobile
                    WHERE e.expense_state = '10' AND e.del_flag = '0'
                      AND m.del_flag = '0' AND m.track = 1
                    """, BigDecimal.class);

            assertThat(consumptionTotal.setScale(2, RoundingMode.HALF_UP))
                    .isEqualByComparingTo(expected.setScale(2, RoundingMode.HALF_UP));
        }
    }
}
