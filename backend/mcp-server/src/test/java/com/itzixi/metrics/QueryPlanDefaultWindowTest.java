package com.itzixi.metrics;

import com.itzixi.metrics.validate.MetricQueryValidator;
import com.itzixi.metrics.exception.MetricException;
import com.itzixi.metrics.request.MetricQueryRequest;
import com.itzixi.metrics.model.QueryPlan;
import com.itzixi.shared.limits.ExecutionLimits;
import com.itzixi.shared.limits.QueryLimits;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @ClassName QueryPlanDefaultWindowTest
 * @Description 缺省时间窗口行为（2026-10-29 产品口径）：看板默认＝近一年半；
 * 指标可用 defaultWindowMonths 覆盖（活跃会员＝近 3 个月）；跨度上限同步放宽到 550 天，
 * 保证默认区间自身不会被 MAX_RANGE_DAYS 拦住。不连接数据库。
 * @Author crm-agent
 */
class QueryPlanDefaultWindowTest {

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern(ExecutionLimits.DATE_PATTERN);

    private QueryPlan normalizeDefault(String metric) {
        MetricQueryRequest req = new MetricQueryRequest();
        req.setMetric(metric);
        return MetricQueryValidator.normalize(MetricsFixtures.def(metric), req);
    }

    @Test
    @DisplayName("全局默认区间＝近一年半：STATION_SALES 未声明窗口时取 DEFAULT_RANGE_MONTHS")
    void globalDefault_is18Months() {
        String expectedStart = LocalDate.now().minusMonths(QueryLimits.DEFAULT_RANGE_MONTHS).format(FMT);
        String expectedEnd = LocalDate.now().format(FMT);

        QueryPlan p = normalizeDefault("STATION_SALES");
        assertThat(p.startDate()).isEqualTo(expectedStart);
        assertThat(p.endDate()).isEqualTo(expectedEnd);
    }

    @Test
    @DisplayName("指标级窗口覆盖：活跃会员缺省近 3 个月，会员新增缺省近一年半")
    void metricLevelWindowOverridesGlobal() {
        QueryPlan active = normalizeDefault("ACTIVE_MEMBER");
        assertThat(active.startDate()).isEqualTo(LocalDate.now().minusMonths(3).format(FMT));
        assertThat(active.endDate()).isEqualTo(LocalDate.now().format(FMT));

        QueryPlan annual = normalizeDefault("ANNUAL_AVG_CONSUMPTION");
        assertThat(annual.startDate()).isEqualTo(LocalDate.now().minusMonths(18).format(FMT));
    }

    @Test
    @DisplayName("MEMBER_NEW 显式声明 18 与全局一致（声明值优先于全局值，结果相同）")
    void memberNew_declaredWindowMatchesGlobal() {
        QueryPlan p = normalizeDefault("MEMBER_NEW");
        assertThat(p.startDate()).isEqualTo(LocalDate.now()
                .minusMonths(MetricsFixtures.def("MEMBER_NEW").defaultWindowMonths()).format(FMT));
    }

    @Test
    @DisplayName("默认区间必须落在跨度上限内（18 个月 ≈ 548 天 ≤ 550，旧 366 上限会误拦）")
    void defaultRangeWithinMaxSpan() {
        QueryPlan p = normalizeDefault("STATION_SALES");
        long days = java.time.temporal.ChronoUnit.DAYS.between(
                LocalDate.parse(p.startDate(), FMT), LocalDate.parse(p.endDate(), FMT));
        assertThat(days).isLessThanOrEqualTo(QueryLimits.MAX_RANGE_DAYS);
    }

    @Test
    @DisplayName("非时间口径指标忽略区间：累计类不补默认窗口")
    void timeAgnosticMetricIgnoresRange() {
        QueryPlan p = normalizeDefault("MEMBER_OVERVIEW");
        assertThat(p.startDate()).isNull();
        assertThat(p.endDate()).isNull();
    }

    @Test
    @DisplayName("显式区间原样保留；超 550 天仍拒绝（放宽上限不等于无上限）")
    void explicitRangeKept_andOverCapRejected() {
        MetricQueryRequest req = new MetricQueryRequest();
        req.setMetric("STATION_SALES");
        req.setStartDate("2025-04-30");
        req.setEndDate("2026-10-29");
        QueryPlan p = MetricQueryValidator.normalize(MetricsFixtures.def("STATION_SALES"), req);
        assertThat(p.startDate()).isEqualTo("2025-04-30");
        assertThat(p.endDate()).isEqualTo("2026-10-29");

        req.setStartDate("2025-01-01");
        assertThatThrownBy(() -> MetricQueryValidator.normalize(MetricsFixtures.def("STATION_SALES"), req))
                .isInstanceOf(MetricException.class)
                .hasMessageContaining("时间跨度");
    }

    @Test
    @DisplayName("指标级时间跨度上限（maxRangeDays=92）：缺省窗口自动落在上限内，越界区间拒绝且文案带指标名")
    void metricLevelMaxRangeDays_capsProductRanking() {
        var def = MetricsFixtures.def("PRODUCT_RANKING");
        assertThat(def.maxRangeDays()).as("配置声明的指标级上限").isEqualTo(92);
        assertThat(def.defaultWindowMonths()).as("商品排行缺省窗口 3 个月").isEqualTo(3);

        // 缺省窗口（3 个月）本身不超过 92 天，无需收敛
        QueryPlan p = normalizeDefault("PRODUCT_RANKING");
        long days = java.time.temporal.ChronoUnit.DAYS.between(
                LocalDate.parse(p.startDate(), FMT), LocalDate.parse(p.endDate(), FMT));
        assertThat(days).isLessThanOrEqualTo(92);

        // 显式区间超 92 天 → 服务端拒绝（不让模型收到一个稳定超时的查询）
        MetricQueryRequest req = new MetricQueryRequest();
        req.setMetric("PRODUCT_RANKING");
        req.setStartDate("2026-01-01");
        req.setEndDate("2026-06-27");
        assertThatThrownBy(() -> MetricQueryValidator.normalize(def, req))
                .isInstanceOf(MetricException.class)
                .hasMessageContaining("92")
                .hasMessageContaining("PRODUCT_RANKING");

        // 92 天内的显式区间原样保留（左闭右开 26 天，对账基线窗口）
        req.setStartDate("2026-06-01");
        req.setEndDate("2026-06-27");
        QueryPlan ok = MetricQueryValidator.normalize(def, req);
        assertThat(ok.startDate()).isEqualTo("2026-06-01");
        assertThat(ok.endDate()).isEqualTo("2026-06-27");
    }

    @Test
    @DisplayName("未声明 maxRangeDays 的指标沿用全局 550 天上限（存量行为不变）")
    void metricsWithoutCap_useGlobalLimit() {
        assertThat(MetricsFixtures.def("STATION_SALES").maxRangeDays()).isZero();
        assertThat(MetricsFixtures.def("ACTIVE_MEMBER").maxRangeDays()).isZero();

        MetricQueryRequest req = new MetricQueryRequest();
        req.setMetric("STATION_SALES");
        req.setStartDate("2025-04-30");
        req.setEndDate("2026-10-29");
        assertThat(MetricQueryValidator.normalize(MetricsFixtures.def("STATION_SALES"), req).startDate())
                .isEqualTo("2025-04-30");
    }
}
