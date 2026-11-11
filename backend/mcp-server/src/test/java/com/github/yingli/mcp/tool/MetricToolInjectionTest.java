package com.github.yingli.mcp.tool;

import com.github.yingli.metrics.request.FilterItem;
import com.github.yingli.metrics.request.MetricQueryRequest;
import com.github.yingli.metrics.response.MetricResult;
import com.github.yingli.metrics.config.MetricDefinitionParser;
import com.github.yingli.metrics.service.MetricCatalogService;
import com.github.yingli.metrics.service.MetricQueryService;
import com.github.yingli.metrics.registry.MetricRegistry;
import com.github.yingli.metrics.MetricsFixtures;
import com.github.yingli.metrics.compile.MetricSqlCompiler;
import com.github.yingli.shared.sql.SqlExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @ClassName MetricToolInjectionTest
 * @Description 域工具 + 元工具的越权/注入/非法枚举用例（不连库，执行器为 mock）。
 * 覆盖方案 §十二 的必须项：未知编码、区间倒置、跨域越权、口径未确认拒绝、
 * 注入形态参数化、listMetrics 域校验、explainMetric 干跑不执行。
 * @Author crm-agent
 */
class MetricToolInjectionTest {

    private SqlExecutor executor;
    private MetricTool tool;

    @BeforeEach
    void setUp() {
        executor = mock(SqlExecutor.class);
        tool = new MetricTool(MetricsFixtures.service(executor), MetricsFixtures.catalogService(),
                MetricsFixtures.drafter());
    }

    private MetricQueryRequest req(String metric) {
        MetricQueryRequest r = new MetricQueryRequest();
        r.setMetric(metric);
        return r;
    }

    @Test
    @DisplayName("未注册的指标编码：METRIC_NOT_FOUND 且指引先查 listMetrics")
    void unknownMetricCode() {
        MetricResult res = tool.queryOrderMetric(req("GHOST_METRIC"));
        assertThat(res.getErrorCode()).isEqualTo("METRIC_NOT_FOUND");
        assertThat(res.getMessage()).contains("listMetrics");
    }

    @Test
    @DisplayName("跨域越权：会员工具查订单域指标被拒绝，不触库")
    void crossDomainRejected() {
        MetricResult res = tool.queryMemberMetric(req("STATION_SALES"));
        assertThat(res.getErrorCode()).isEqualTo("INVALID_PARAM");
        assertThat(res.getMessage()).contains("不能通过 member 域工具查询");
    }

    @Test
    @DisplayName("口径未确认的指标：拒绝执行并给出明确原因，绝不返回可疑数字")
    void unconfirmedMetricRefused() {
        Map<String, Object> item = new HashMap<>();
        item.put("metric", "T_UNCONFIRMED");
        item.put("title", "待确认指标");
        item.put("domain", "order");
        item.put("caliber", "口径待产品确认");
        item.put("table", "expense_calendar_info");
        item.put("grain", "ORDER_HEAD");
        // status 缺省 → 按 UNCONFIRMED 处理
        MetricRegistry registry = new MetricRegistry(
                List.of(MetricDefinitionParser.fromMap(item)));
        MetricQueryService service =
                new MetricQueryService(registry, new MetricSqlCompiler(), executor);
        MetricTool localTool = new MetricTool(service, new MetricCatalogService(registry), MetricsFixtures.drafter());

        MetricResult res = localTool.queryOrderMetric(req("T_UNCONFIRMED"));
        assertThat(res.getErrorCode()).isEqualTo("METRICS_NOT_CONFIRMED");
        assertThat(res.getRows()).isEmpty();
    }

    @Test
    @DisplayName("非法维度/过滤/排序枚举与非法方向：逐项拒绝且不执行 SQL")
    void invalidEnumsRejected() {
        MetricQueryRequest badDim = req("STATION_SALES");
        badDim.setDimensions(List.of("MEMBER_MOBILE"));
        assertThat(tool.queryOrderMetric(badDim).getErrorCode()).isEqualTo("INVALID_PARAM");

        MetricQueryRequest badFilter = req("STATION_SALES");
        FilterItem f = new FilterItem();
        f.setCode("SECRET");
        f.setValue("x");
        badFilter.setFilters(List.of(f));
        assertThat(tool.queryOrderMetric(badFilter).getErrorCode()).isEqualTo("INVALID_PARAM");

        MetricQueryRequest badSort = req("STATION_SALES");
        badSort.setSortBy("SALES; DROP TABLE x");
        assertThat(tool.queryOrderMetric(badSort).getErrorCode()).isEqualTo("INVALID_PARAM");

        MetricQueryRequest badDir = req("STATION_SALES");
        badDir.setSortDir("desc; --");
        assertThat(tool.queryOrderMetric(badDir).getErrorCode()).isEqualTo("INVALID_PARAM");

        verify(executor, never()).query(anyString(), anyList());
    }

    @Test
    @DisplayName("注入形态的过滤值：只作为绑定参数下传，SQL 文本零拼接")
    void injectionShapedValueIsParameterized() throws Exception {
        when(executor.query(anyString(), anyList(), anyString())).thenReturn(List.of());

        MetricQueryRequest r = req("STATION_SALES");
        r.setStartDate("2026-06-20");
        r.setEndDate("2026-06-27");
        FilterItem f = new FilterItem();
        f.setCode("STATION");
        f.setValue("x' UNION SELECT member_mobile FROM member_info; --");
        r.setFilters(List.of(f));

        MetricResult res = tool.queryOrderMetric(r);
        assertThat(res.getErrorCode()).isEqualTo("NO_DATA");

        ArgumentCaptor<String> sqlCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<List<Object>> paramCap = ArgumentCaptor.forClass(List.class);
        // 主查询 + 全量合计查询各一次，两条 SQL 都必须是零拼接的
        verify(executor, atLeastOnce()).query(sqlCap.capture(), paramCap.capture(), anyString());
        for (String sql : sqlCap.getAllValues()) {
            assertThat(sql).doesNotContain("UNION").doesNotContain("member_info").doesNotContain("--");
        }
        assertThat(sqlCap.getAllValues()).anyMatch(sql -> sql.contains("expense_stationCode = ?"));
        assertThat(paramCap.getAllValues()).allMatch(params -> params.contains(f.getValue()));
    }

    @Test
    @DisplayName("区间倒置与跨度超限：服务端拒绝，不依赖模型自觉")
    void badDateRangeRejected() {
        MetricQueryRequest inverted = req("STATION_SALES");
        inverted.setStartDate("2026-07-01");
        inverted.setEndDate("2026-06-01");
        assertThat(tool.queryOrderMetric(inverted).getMessage()).contains("结束日期不能早于开始日期");

        MetricQueryRequest tooLong = req("STATION_SALES");
        tooLong.setStartDate("2020-01-01");
        tooLong.setEndDate("2026-12-31");
        assertThat(tool.queryOrderMetric(tooLong).getMessage()).contains("时间跨度");

        MetricQueryRequest garbage = req("STATION_SALES");
        garbage.setStartDate("2026' OR 1=1 --");
        assertThat(tool.queryOrderMetric(garbage).getMessage()).contains("日期格式");
    }

    @Test
    @DisplayName("topN 越界：静默夹取到指标上限而非报错（与 Phase 1 一致）")
    void topNClampedToMetricMax() throws Exception {
        when(executor.query(anyString(), anyList(), anyString())).thenReturn(List.of());

        MetricQueryRequest r = req("PRODUCT_RANKING");
        r.setStartDate("2026-06-20");
        r.setEndDate("2026-06-27");
        r.setTopN(99999);
        tool.queryOrderMetric(r);

        ArgumentCaptor<String> sqlCap = ArgumentCaptor.forClass(String.class);
        verify(executor).query(sqlCap.capture(), any(), anyString());
        assertThat(sqlCap.getValue()).endsWith("LIMIT 100");
    }

    @Test
    @DisplayName("listMetrics：按域过滤目录；未知域给出可读原因")
    void catalogScopedByDomain() {
        // 2026-11-09：删除 GENDER_EXPENSE_TOTAL（与订单域 GENDER_CONSUMPTION_COMPARE 重复且取错金额来源），
        // 新增 MEMBER_CONSUMPTION（订单实付累计），member 域仍为 14 个指标
        List<Map<String, Object>> memberOnly = tool.listMetrics("member");
        assertThat(memberOnly).hasSize(14);
        assertThat(memberOnly).extracting(m -> m.get("metric")).contains(
                "MEMBER_OVERVIEW", "MEMBER_CONSUMPTION", "MEMBER_NEW", "ACTIVE_MEMBER",
                "MEMBER_LEVEL_DIST", "AGE_DIST", "ANNUAL_AVG_CONSUMPTION",
                "VIP_OVERVIEW", "VIP_NEW", "VIP_CANCELLED",
                "VIP_BY_STAFF", "VIP_BY_STORE", "VIP_CONSUMPTION_BY_STORE", "VIP_BY_GENDER");
        assertThat(memberOnly).extracting(m -> m.get("metric")).doesNotContain("GENDER_EXPENSE_TOTAL");
        assertThat(memberOnly.get(0)).containsKeys("caliber", "dimensions", "measures", "sortable", "status");

        List<Map<String, Object>> all = tool.listMetrics(null);
        // member 域 14 个 + order 域 3 个
        assertThat(all).hasSize(17);

        List<Map<String, Object>> bad = tool.listMetrics("hr");
        assertThat(bad).hasSize(1);
        assertThat(bad.get(0)).containsEntry("errorCode", "INVALID_PARAM");
    }

    @Test
    @DisplayName("explainMetric：干跑返回 SQL/指纹/参数，不执行查询")
    void explainDoesNotExecute() {
        MetricQueryRequest r = req("STATION_SALES");
        r.setDimensions(List.of("STATION"));
        r.setStartDate("2026-06-20");
        r.setEndDate("2026-06-27");

        Map<String, Object> out = tool.explainMetric(r, false);
        assertThat(out).containsKeys("sql", "sqlHash", "params", "columns", "totalsSql", "caliber");
        assertThat(String.valueOf(out.get("sql"))).contains("GROUP BY expense_stationCode");
        verify(executor, never()).query(anyString(), anyList());
        verify(executor, never()).explain(anyString(), anyList());
    }

    @Test
    @DisplayName("执行层异常：转换为 QUERY_FAILED，不把裸异常抛给模型")
    void executorFailureMapped() throws Exception {
        when(executor.query(any(), any(), anyString())).thenThrow(new RuntimeException("db down"));
        MetricQueryRequest r = req("STATION_SALES");
        r.setStartDate("2026-06-20");
        r.setEndDate("2026-06-27");
        MetricResult res = tool.queryOrderMetric(r);
        assertThat(res.getErrorCode()).isEqualTo("QUERY_FAILED");
        assertThat(res.getRows()).isEmpty();
    }
}
