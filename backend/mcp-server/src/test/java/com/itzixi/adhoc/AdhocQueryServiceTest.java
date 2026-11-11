package com.itzixi.adhoc;

import com.itzixi.adhoc.compile.AdhocSqlCompiler;
import com.itzixi.adhoc.model.SemanticRegistry;
import com.itzixi.adhoc.validate.AdhocRequestValidator;
import com.itzixi.adhoc.request.AdhocQueryRequest;
import com.itzixi.adhoc.response.AdhocResult;
import com.itzixi.adhoc.service.AdhocQueryService;
import com.itzixi.adhoc.request.MeasureSpec;
import com.itzixi.adhoc.request.TimeRangeSpec;
import com.itzixi.shared.catalog.ColumnTypeRegistry;
import com.itzixi.shared.sql.SqlExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdhocQueryServiceTest {

    private AdhocQueryService service;
    private SqlExecutor executor;

    @BeforeEach
    void setUp() {
        ColumnTypeRegistry types = ColumnTypeRegistry.fromYaml(Map.of(
                "piiColumns", List.of("member_mobile", "expense_mobile"),
                "piiComputeAllowedColumns", List.of("member_mobile", "expense_mobile"),
                "tables", Map.of(
                        "member_info_view", Map.of("varcharNumeric", List.of()),
                        "expense_calendar_info", Map.of("varcharNumeric", List.of("expense_realamt")),
                        "expense_calendar_details", Map.of("varcharNumeric",
                                List.of("expense_d_realamt", "expense_d_qty")))));
        SemanticRegistry registry = AdhocFixtures.registry();
        executor = mock(SqlExecutor.class);
        service = new AdhocQueryService(new AdhocRequestValidator(registry, types),
                new AdhocSqlCompiler(registry, types), executor);
    }

    private MeasureSpec spec(String type, String name, String preset, String alias, String formula) {
        MeasureSpec m = new MeasureSpec();
        m.setType(type);
        m.setName(name);
        m.setConditionPreset(preset);
        m.setAlias(alias);
        m.setFormula(formula);
        m.setScale(2);
        return m;
    }

    @Test
    @DisplayName("派生度量在 Java 层补值：人均 = cnt / vip_cnt")
    void derivedComputedInJava() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("cnt", 100L);
        row.put("vip_cnt", 4L);
        when(executor.query(anyString(), anyList(), anyString())).thenReturn(List.of(row));

        AdhocQueryRequest r = new AdhocQueryRequest();
        r.setEntities(List.of("member"));
        r.setMeasures(List.of(
                spec("simple", "MEMBER_COUNT", null, "cnt", null),
                spec("conditional", "MEMBER_COUNT", "IS_VIP", "vip_cnt", null),
                spec("derived", null, null, "avg_per_vip", "cnt / vip_cnt")));
        AdhocResult res = service.query(r);

        assertThat(res.errorCode()).isNull();
        assertThat(res.rows().get(0).get("avg_per_vip"))
                .isEqualTo(new BigDecimal("25.00"));
        assertThat(res.columns()).endsWith("cnt", "vip_cnt", "avg_per_vip");
    }

    @Test
    @DisplayName("除零语义：派生返回 0，与指标框架一致")
    void derivedDivideByZero() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("cnt", 0L);
        when(executor.query(anyString(), anyList(), anyString())).thenReturn(List.of(row));

        AdhocQueryRequest r = new AdhocQueryRequest();
        r.setEntities(List.of("member"));
        r.setMeasures(List.of(spec("simple", "MEMBER_COUNT", null, "cnt", null),
                spec("derived", null, null, "x", "cnt / cnt")));
        AdhocResult res = service.query(r);
        assertThat(res.rows().get(0).get("x")).isEqualTo(new BigDecimal("0.00"));
    }

    @Test
    @DisplayName("校验失败收敛为 INVALID_PARAM，不触达执行层")
    void validationErrorNoExecution() {
        AdhocQueryRequest r = new AdhocQueryRequest();
        r.setEntities(List.of("ghost"));
        r.setMeasures(List.of(spec("simple", "MEMBER_COUNT", null, "cnt", null)));
        AdhocResult res = service.query(r);
        assertThat(res.errorCode()).isEqualTo("ADHOC_UNKNOWN_ENTITY");
        assertThat(res.errorMessage()).contains("未知实体");
        org.mockito.Mockito.verifyNoInteractions(executor);
    }

    @Test
    @DisplayName("口径行包含实际注入的基线/join 过滤片段，而非静态描述")
    void caliberContainsActualFragments() {
        when(executor.query(anyString(), anyList(), anyString())).thenReturn(List.of(Map.of("cnt", 1L)));

        AdhocQueryRequest single = new AdhocQueryRequest();
        single.setEntities(List.of("member"));
        single.setMeasures(List.of(spec("simple", "MEMBER_COUNT", null, "cnt", null)));
        AdhocResult res = service.query(single);
        assertThat(res.caliber()).contains("mv.del_flag = '0'");

        AdhocQueryRequest joined = new AdhocQueryRequest();
        joined.setEntities(List.of("member", "order"));
        joined.setMeasures(List.of(spec("simple", "ORDER_COUNT", null, "cnt", null)));
        AdhocResult joinedRes = service.query(joined);
        assertThat(joinedRes.caliber()).contains("mv.del_flag = '0'");
        assertThat(joinedRes.caliber()).contains("(e.expense_state = '10' AND e.del_flag = '0')");
    }

    @Test
    @DisplayName("columns 与 rows 的 key 一致（维度用 SQL 别名小写，不用大写编码）")
    void columnsMatchRowKeys() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("store_code", "S01");
        row.put("cnt", 7L);
        when(executor.query(anyString(), anyList(), anyString())).thenReturn(List.of(row));

        AdhocQueryRequest r = new AdhocQueryRequest();
        r.setEntities(List.of("member"));
        r.setDimensions(List.of("STORE_CODE"));
        r.setMeasures(List.of(spec("simple", "MEMBER_COUNT", null, "cnt", null)));
        AdhocResult res = service.query(r);

        assertThat(res.columns()).containsExactly("store_code", "cnt");
        assertThat(res.rows().get(0).keySet()).containsExactlyInAnyOrderElementsOf(res.columns());
    }

    @Test
    @DisplayName("空结果是正常答案：口径行标记『本区间无匹配数据（0 条）』，有数据时不加")
    void emptyResultMarkedInCaliber() {
        when(executor.query(anyString(), anyList(), anyString())).thenReturn(List.of());

        AdhocQueryRequest r = new AdhocQueryRequest();
        r.setEntities(List.of("member"));
        r.setMeasures(List.of(spec("simple", "MEMBER_COUNT", null, "cnt", null)));
        AdhocResult empty = service.query(r);
        assertThat(empty.errorCode()).isNull();
        assertThat(empty.rows()).isEmpty();
        assertThat(empty.caliber()).contains("本区间无匹配数据（0 条）");

        when(executor.query(anyString(), anyList(), anyString())).thenReturn(List.of(Map.of("cnt", 3L)));
        AdhocResult nonEmpty = service.query(r);
        assertThat(nonEmpty.caliber()).doesNotContain("本区间无匹配数据");
    }

    @Test
    @DisplayName("执行异常收敛为 ADHOC_EXEC_FAILED，带中文原因")
    void executorFailure() {
        when(executor.query(anyString(), anyList(), anyString())).thenThrow(new RuntimeException("communications link failure"));
        AdhocQueryRequest r = new AdhocQueryRequest();
        r.setEntities(List.of("member"));
        r.setMeasures(List.of(spec("simple", "MEMBER_COUNT", null, "cnt", null)));
        AdhocResult res = service.query(r);
        assertThat(res.errorCode()).isEqualTo("ADHOC_EXEC_FAILED");
    }

    @Test
    @DisplayName("MySQL 3024 服务端超时：收敛为 ADHOC_QUERY_TIMEOUT 并提示缩小范围，不混同为通用失败")
    void executorTimeout() {
        SQLException timeout = new SQLException(
                "Query execution was interrupted, maximum statement execution time exceeded", "HY000", 3024);
        assertThat(SqlExecutor.isExecutionTimeout(timeout)).isTrue();
        assertThat(SqlExecutor.isExecutionTimeout(new RuntimeException("wrapped", timeout))).isTrue();
        assertThat(SqlExecutor.isExecutionTimeout(new RuntimeException("communications link failure"))).isFalse();

        when(executor.query(anyString(), anyList(), anyString())).thenThrow(new RuntimeException("wrapped", timeout));
        AdhocResult res = service.query(req("member", "MEMBER_COUNT", "cnt", null));

        assertThat(res.errorCode()).isEqualTo("ADHOC_QUERY_TIMEOUT");
        assertThat(res.errorMessage()).contains("60 秒").contains("缩小时间范围");
    }

    @Test
    @DisplayName("批量：按查询块顺序返回等长结果，各块独立执行")
    void batchReturnsResultsInOrder() {
        when(executor.query(anyString(), anyList(), anyString()))
                .thenReturn(List.of(Map.of("cnt", 1L)), List.of(Map.of("cnt", 2L)));

        AdhocQueryRequest memberBlock = req("member", "MEMBER_COUNT", "cnt", null);
        AdhocQueryRequest orderBlock = req("order", "ORDER_COUNT", "cnt", null);
        List<AdhocResult> out = service.queryBatch(List.of(memberBlock, orderBlock));

        assertThat(out).hasSize(2);
        assertThat(out.get(0).errorCode()).isNull();
        assertThat(out.get(0).rows().get(0)).containsEntry("cnt", 1L);
        assertThat(out.get(1).errorCode()).isNull();
        assertThat(out.get(1).rows().get(0)).containsEntry("cnt", 2L);
    }

    @Test
    @DisplayName("批量：单块校验失败只影响该块，其余块照常返回")
    void batchIsolatesBlockFailure() {
        when(executor.query(anyString(), anyList(), anyString())).thenReturn(List.of(Map.of("cnt", 5L)));

        AdhocQueryRequest ghostBlock = new AdhocQueryRequest();
        ghostBlock.setEntities(List.of("ghost"));
        ghostBlock.setMeasures(List.of(spec("simple", "MEMBER_COUNT", null, "cnt", null)));
        AdhocQueryRequest okBlock = req("member", "MEMBER_COUNT", "cnt", null);

        List<AdhocResult> out = service.queryBatch(List.of(ghostBlock, okBlock));

        assertThat(out).hasSize(2);
        assertThat(out.get(0).errorCode()).isEqualTo("ADHOC_UNKNOWN_ENTITY");
        assertThat(out.get(1).errorCode()).isNull();
        assertThat(out.get(1).rows()).hasSize(1);
    }

    @Test
    @DisplayName("批量：空请求/超过 5 块返回单个 INVALID_PARAM，不触达执行层")
    void batchRejectsEmptyAndOversize() {
        assertThat(service.queryBatch(null)).singleElement()
                .satisfies(r -> assertThat(r.errorCode()).isEqualTo("INVALID_PARAM"));
        assertThat(service.queryBatch(List.of())).singleElement()
                .satisfies(r -> assertThat(r.errorCode()).isEqualTo("INVALID_PARAM"));

        List<AdhocQueryRequest> six = Collections.nCopies(
                AdhocQueryService.MAX_BATCH_QUERIES + 1,
                req("member", "MEMBER_COUNT", "cnt", null));
        assertThat(service.queryBatch(six)).singleElement().satisfies(r -> {
            assertThat(r.errorCode()).isEqualTo("INVALID_PARAM");
            assertThat(r.errorMessage()).contains("5");
        });
        org.mockito.Mockito.verifyNoInteractions(executor);
    }

    @Test
    @DisplayName("批量：跨粒度用独立块各自合法；同一块内混粒度仍被粒度守卫拒绝")
    void batchKeepsGrainSeparation() {
        when(executor.query(anyString(), anyList(), anyString())).thenReturn(List.of(Map.of("sales", new BigDecimal("12.00"))));

        AdhocQueryRequest detailBlock = req("detail", "DETAIL_SALES", "sales", null);
        detailBlock.setDimensions(List.of("CATEGORY_NAME"));
        detailBlock.setTimeRange(window("detail", "BUY_DATE", "2026-01-01", "2026-02-01"));

        AdhocQueryRequest mixedBlock = req("member", "MEMBER_COUNT", "cnt", null);
        mixedBlock.setEntities(List.of("member", "order"));

        List<AdhocResult> out = service.queryBatch(List.of(detailBlock, mixedBlock));

        assertThat(out.get(0).errorCode()).isNull();
        assertThat(out.get(1).errorCode()).isEqualTo("INVALID_PARAM");
        assertThat(out.get(1).errorMessage()).contains("MEMBER_COUNT").contains("adhocQueryBatch");
    }

    @Test
    @DisplayName("口径行带时间范围：实体、时间维度与起止日期随结果返回")
    void caliberContainsTimeRange() {
        when(executor.query(anyString(), anyList(), anyString())).thenReturn(List.of(Map.of("line_cnt", 1L)));

        AdhocQueryRequest r = req("detail", "DETAIL_LINES", "line_cnt", null);
        r.setTimeRange(window("detail", "BUY_DATE", "2026-01-01", "2026-02-01"));
        AdhocResult res = service.query(r);

        assertThat(res.caliber())
                .contains("｜时间=detail.BUY_DATE 2026-01-01~2026-02-01")
                .contains("ed.expense_d_state = '10'");
    }

    private AdhocQueryRequest req(String entity, String measure, String alias, String formula) {
        AdhocQueryRequest r = new AdhocQueryRequest();
        r.setEntities(List.of(entity));
        r.setMeasures(List.of(spec("simple", measure, null, alias, formula)));
        return r;
    }

    private TimeRangeSpec window(String entity, String dim, String start, String end) {
        TimeRangeSpec t = new TimeRangeSpec();
        t.setEntity(entity);
        t.setTimeDimension(dim);
        t.setStartDate(start);
        t.setEndDate(end);
        return t;
    }
}
