package com.itzixi.adhoc;

import com.itzixi.adhoc.compile.AdhocSqlCompiler;
import com.itzixi.adhoc.model.SemanticRegistry;
import com.itzixi.adhoc.validate.AdhocRequestValidator;
import com.itzixi.adhoc.request.AdhocBatchRequest;
import com.itzixi.adhoc.request.AdhocQueryRequest;
import com.itzixi.adhoc.response.AdhocResult;
import com.itzixi.adhoc.request.MeasureSpec;
import com.itzixi.adhoc.service.AdhocQueryService;
import com.itzixi.adhoc.service.SemanticCatalogService;
import com.itzixi.mcp.tool.AdhocTool;
import com.itzixi.shared.catalog.ColumnTypeRegistry;
import com.itzixi.shared.sql.SqlExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdhocToolTest {

    private AdhocTool tool;

    @BeforeEach
    void setUp() {
        ColumnTypeRegistry types = ColumnTypeRegistry.fromYaml(Map.of(
                "piiColumns", List.of("member_mobile", "expense_mobile"),
                "piiComputeAllowedColumns", List.of("member_mobile", "expense_mobile"),
                "tables", Map.of(
                        "member_info_view", Map.of("varcharNumeric", List.of()),
                        "expense_calendar_info", Map.of("varcharNumeric", List.of("expense_realamt")))));
        SemanticRegistry registry = AdhocFixtures.registry();
        SqlExecutor executor = mock(SqlExecutor.class);
        when(executor.query(anyString(), anyList(), anyString())).thenReturn(List.of(Map.of("cnt", 7L)));
        tool = new AdhocTool(new AdhocQueryService(new AdhocRequestValidator(registry, types),
                new AdhocSqlCompiler(registry, types), executor), new SemanticCatalogService(registry, types));
    }

    @Test
    @DisplayName("目录开放会员级别/层级/分配状态字段：维度与过滤列可见且非 PII")
    @SuppressWarnings("unchecked")
    void catalogExposesMemberLevelFields() {
        Map<String, Object> member = tool.listSemanticCatalog().stream()
                .filter(c -> "member".equals(c.get("entity"))).findFirst().orElseThrow();
        List<Map<String, Object>> dims = (List<Map<String, Object>>) member.get("dimensions");
        assertThat(dims)
                .anySatisfy(d -> {
                    assertThat(d.get("name")).isEqualTo("MEMBER_LEVEL");
                    assertThat(d.get("pii")).isEqualTo(Boolean.FALSE);
                })
                .anySatisfy(d -> assertThat(d.get("name")).isEqualTo("CUSTOMER_VALUE"))
                .anySatisfy(d -> assertThat(d.get("name")).isEqualTo("HAIDING_LEVEL"))
                .anySatisfy(d -> assertThat(d.get("name")).isEqualTo("COMPANY_LEVEL"))
                .anySatisfy(d -> assertThat(d.get("name")).isEqualTo("CITY_LEVEL"))
                .anySatisfy(d -> assertThat(d.get("name")).isEqualTo("STORE_LEVEL"))
                .anySatisfy(d -> assertThat(d.get("name")).isEqualTo("PERSONAL_LEVEL"))
                .anySatisfy(d -> assertThat(d.get("name")).isEqualTo("HALF_YEAR_SALES_LEVEL"))
                .anySatisfy(d -> assertThat(d.get("name")).isEqualTo("TOTAL_YEAR_SALES_LEVEL"))
                .anySatisfy(d -> assertThat(d.get("name")).isEqualTo("VIP_FLAG"))
                .anySatisfy(d -> assertThat(d.get("name")).isEqualTo("POND_TYPE"));
        List<Map<String, Object>> measures = (List<Map<String, Object>>) member.get("measures");
        assertThat(measures).anySatisfy(m -> {
            assertThat(m.get("name")).isEqualTo("MEMBER_EXPENSE_TIMES");
            assertThat(m.get("pii")).isEqualTo(Boolean.FALSE);
        });
        List<Map<String, Object>> filterables = (List<Map<String, Object>>) member.get("filterableColumns");
        assertThat(filterables).anySatisfy(f -> {
            assertThat(f.get("name")).isEqualTo("new_member_level_value");
            assertThat(f.get("pii")).isEqualTo(Boolean.FALSE);
            assertThat(f.get("indexed")).isEqualTo(Boolean.FALSE);
        }).anySatisfy(f -> {
            assertThat(f.get("name")).isEqualTo("member_is_allot_store");
            assertThat(f.get("indexed")).isEqualTo(Boolean.TRUE);
        }).anySatisfy(f -> {
            assertThat(f.get("name")).isEqualTo("reserved15");
            assertThat(f.get("pii")).isEqualTo(Boolean.FALSE);
            assertThat(f.get("indexed")).isEqualTo(Boolean.FALSE);
        }).anySatisfy(f -> {
            assertThat(f.get("name")).isEqualTo("company_level");
            assertThat(f.get("indexed")).isEqualTo(Boolean.TRUE);
        });
    }

    @Test
    @DisplayName("目录输出覆盖实体/维度/度量/条件模板/时间维度，供模型组参")
    void catalogShape() {
        List<Map<String, Object>> catalog = tool.listSemanticCatalog();
        assertThat(catalog).hasSize(3);
        Map<String, Object> member = catalog.stream()
                .filter(c -> "member".equals(c.get("entity"))).findFirst().orElseThrow();
        assertThat(member).containsKeys("dimensions", "measures", "conditionPresets",
                "timeColumns", "relationships", "description");
    }

    @Test
    @DisplayName("目录透出 detail 查询守卫：requiredAnyOf+constraints 可见，无守卫实体不带该键")
    @SuppressWarnings("unchecked")
    void catalogExposesQueryGuard() {
        Map<String, Object> detail = tool.listSemanticCatalog().stream()
                .filter(c -> "detail".equals(c.get("entity"))).findFirst().orElseThrow();
        Map<String, Object> guard = (Map<String, Object>) detail.get("queryGuard");
        assertThat(guard).isNotNull();
        assertThat((List<String>) guard.get("requiredAnyOf"))
                .containsExactly("timeRange", "expense_d_mobile", "expense_d_orderId");
        assertThat((Map<String, Object>) guard.get("constraints")).containsEntry("maxTimeRangeDays", 92);
        Map<String, Object> member = tool.listSemanticCatalog().stream()
                .filter(c -> "member".equals(c.get("entity"))).findFirst().orElseThrow();
        assertThat(member).doesNotContainKey("queryGuard");
    }

    @Test
    @DisplayName("目录 PII 标注：BUYER_COUNT(expense_mobile)=true，MEMBER_COUNT/expr 维度=false，过滤列逐项标注")
    @SuppressWarnings("unchecked")
    void catalogPiiAnnotation() {
        List<Map<String, Object>> catalog = tool.listSemanticCatalog();
        Map<String, Object> order = catalog.stream()
                .filter(c -> "order".equals(c.get("entity"))).findFirst().orElseThrow();
        Map<String, Object> member = catalog.stream()
                .filter(c -> "member".equals(c.get("entity"))).findFirst().orElseThrow();

        // 度量标注：BUYER_COUNT 基于 expense_mobile → pii=true（PII 派生，仅聚合输出，可正常使用）；
        // SALES 基于非 PII 列 → pii=false；MEMBER_COUNT 为 expr-only（COUNT *）→ pii=false
        List<Map<String, Object>> orderMeasures = (List<Map<String, Object>>) order.get("measures");
        assertThat(orderMeasures).anySatisfy(m -> {
            assertThat(m.get("name")).isEqualTo("BUYER_COUNT");
            assertThat(m.get("pii")).isEqualTo(Boolean.TRUE);
        }).anySatisfy(m -> {
            assertThat(m.get("name")).isEqualTo("SALES");
            assertThat(m.get("pii")).isEqualTo(Boolean.FALSE);
        });
        List<Map<String, Object>> memberMeasures = (List<Map<String, Object>>) member.get("measures");
        assertThat(memberMeasures).anySatisfy(m -> {
            assertThat(m.get("name")).isEqualTo("MEMBER_COUNT");
            assertThat(m.get("pii")).isEqualTo(Boolean.FALSE);
        });

        // 维度标注：expr-only 维度（GENDER）无原始列，pii=false 且不抛 NPE；普通列维度同样带 pii 键
        List<Map<String, Object>> memberDims = (List<Map<String, Object>>) member.get("dimensions");
        assertThat(memberDims).anySatisfy(d -> {
            assertThat(d.get("name")).isEqualTo("GENDER");
            assertThat(d.get("pii")).isEqualTo(Boolean.FALSE);
        }).anySatisfy(d -> {
            assertThat(d.get("name")).isEqualTo("STORE_CODE");
            assertThat(d.get("pii")).isEqualTo(Boolean.FALSE);
        }).allSatisfy(d -> assertThat(d).containsKey("pii"));

        // 过滤列逐项标注 name+pii+indexed；order 实体：门店编码 + 手机号（名称列无索引，2026-11-03 起移出白名单；
        // indexed 标注以 DATABASE_SCHEMA.md DDL 为准，供模型组参时优先选索引列）
        // 手机号仅作等值过滤条件，PII 标记为 true，不得作为输出维度
        List<Map<String, Object>> orderFilterables =
                (List<Map<String, Object>>) order.get("filterableColumns");
        assertThat(orderFilterables).containsExactly(
                Map.of("name", "expense_stationCode", "pii", Boolean.FALSE, "indexed", Boolean.TRUE),
                Map.of("name", "expense_mobile", "pii", Boolean.TRUE, "indexed", Boolean.TRUE));
        List<Map<String, Object>> memberFilterables =
                (List<Map<String, Object>>) member.get("filterableColumns");
        assertThat(memberFilterables)
                .allSatisfy(f -> assertThat(f).containsKeys("name", "pii", "indexed"))
                .anySatisfy(f -> {
                    assertThat(f.get("name")).isEqualTo("member_allot_stationName");
                    assertThat(f.get("indexed")).isEqualTo(Boolean.TRUE);
                    assertThat(f.get("pii")).isEqualTo(Boolean.FALSE);
                })
                .anySatisfy(f -> {
                    assertThat(f.get("name")).isEqualTo("member_mobile");
                    assertThat(f.get("pii")).isEqualTo(Boolean.TRUE);
                    assertThat(f.get("indexed")).isEqualTo(Boolean.TRUE);
                });
    }

    @Test
    @DisplayName("目录带 filterGuidance：元数据声明的意图→列约定随目录透出，模型不必猜列")
    void catalogFilterGuidance() {
        Map<String, Object> order = tool.listSemanticCatalog().stream()
                .filter(c -> "order".equals(c.get("entity"))).findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        List<String> guidance = (List<String>) order.get("filterGuidance");
        assertThat(guidance).isNotNull();
        // 名称列已移出过滤白名单：指引要求编码等值 + member 名称→编码解析
        assertThat(guidance).anySatisfy(g -> assertThat(g).contains("expense_stationCode").contains("编码"))
                .anySatisfy(g -> assertThat(g).contains("member_allot_stationName").contains("解析"));
    }

    @Test
    @DisplayName("adhocQuery 透传服务层 errorCode，模型可转述")
    void queryDelegation() {
        AdhocQueryRequest r = new AdhocQueryRequest();
        r.setEntities(List.of("ghost"));
        MeasureSpec m = new MeasureSpec();
        m.setType("simple");
        m.setName("MEMBER_COUNT");
        m.setAlias("cnt");
        r.setMeasures(List.of(m));
        AdhocResult res = tool.adhocQuery(r);
        assertThat(res.errorCode()).isEqualTo("ADHOC_UNKNOWN_ENTITY");
    }

    @Test
    @DisplayName("目录带 grain 与 cardinality：模型据此判断同粒度度量与 join 放大风险")
    @SuppressWarnings("unchecked")
    void catalogExposesGrainAndCardinality() {
        List<Map<String, Object>> catalog = tool.listSemanticCatalog();
        Map<String, Object> member = catalog.stream()
                .filter(c -> "member".equals(c.get("entity"))).findFirst().orElseThrow();
        Map<String, Object> detail = catalog.stream()
                .filter(c -> "detail".equals(c.get("entity"))).findFirst().orElseThrow();

        List<Map<String, Object>> memberMeasures = (List<Map<String, Object>>) member.get("measures");
        assertThat(memberMeasures).allSatisfy(m -> assertThat(m.get("grain")).isEqualTo("member"));

        List<Map<String, Object>> memberRels = (List<Map<String, Object>>) member.get("relationships");
        assertThat(memberRels).anySatisfy(r -> {
            assertThat(r.get("target")).isEqualTo("detail");
            assertThat(r.get("cardinality")).isEqualTo("to_many");
        });
        List<Map<String, Object>> detailRels = (List<Map<String, Object>>) detail.get("relationships");
        assertThat(detailRels).anySatisfy(r -> {
            assertThat(r.get("target")).isEqualTo("member");
            assertThat(r.get("cardinality")).isEqualTo("to_one");
        });
        assertThat(detailRels).noneSatisfy(r -> assertThat(r.get("target")).isEqualTo("order"));
    }

    @Test
    @DisplayName("adhocQueryBatch 透传服务层：按块顺序返回结果")
    void batchDelegation() {
        AdhocQueryRequest memberBlock = new AdhocQueryRequest();
        memberBlock.setEntities(List.of("member"));
        measure(memberBlock, "MEMBER_COUNT", "cnt");
        AdhocQueryRequest orderBlock = new AdhocQueryRequest();
        orderBlock.setEntities(List.of("order"));
        measure(orderBlock, "ORDER_COUNT", "cnt");

        AdhocBatchRequest batch = new AdhocBatchRequest();
        batch.setQueries(List.of(memberBlock, orderBlock));
        List<AdhocResult> out = tool.adhocQueryBatch(batch);

        assertThat(out).hasSize(2);
        assertThat(out.get(0).errorCode()).isNull();
        assertThat(out.get(1).errorCode()).isNull();
    }

    @Test
    @DisplayName("adhocQueryBatch 空请求返回 INVALID_PARAM，而不是 NPE")
    void batchNullRequest() {
        assertThat(tool.adhocQueryBatch(null)).singleElement()
                .satisfies(r -> assertThat(r.errorCode()).isEqualTo("INVALID_PARAM"));
    }

    private static void measure(AdhocQueryRequest r, String name, String alias) {
        MeasureSpec m = new MeasureSpec();
        m.setType("simple");
        m.setName(name);
        m.setAlias(alias);
        r.setMeasures(List.of(m));
    }

    @Test
    @DisplayName("explainAdhoc 干跑不执行：返回 sql/params/sqlHash，无 rows")
    void explainDryRun() {
        AdhocQueryRequest r = new AdhocQueryRequest();
        r.setEntities(List.of("member"));
        MeasureSpec m = new MeasureSpec();
        m.setType("simple");
        m.setName("MEMBER_COUNT");
        m.setAlias("cnt");
        r.setMeasures(List.of(m));
        Map<String, Object> out = tool.explainAdhoc(r);
        assertThat(out).containsKeys("sql", "params", "sqlHash");
        assertThat(String.valueOf(out.get("sql"))).contains("FROM member_info_view mv");
    }
}
