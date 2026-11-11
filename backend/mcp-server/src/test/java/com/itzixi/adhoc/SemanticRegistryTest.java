package com.itzixi.adhoc;

import com.itzixi.adhoc.model.SemanticRegistry;
import com.itzixi.adhoc.exception.AdhocException;
import com.itzixi.shared.catalog.ColumnTypeRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SemanticRegistryTest {

    @Test
    @DisplayName("真实 metadata.yaml 加载成功：三个实体、别名与基线过滤就位")
    void loadsRealMetadata() {
        SemanticRegistry registry = AdhocFixtures.registry();
        assertThat(registry.entities()).containsExactlyInAnyOrder("member", "order", "detail");
        SemanticRegistry.EntityDef member = registry.entity("member");
        assertThat(member.table()).isEqualTo("member_info_view");
        assertThat(member.alias()).isEqualTo("mv");
        assertThat(member.baselineFilters()).containsExactly("mv.del_flag = '0'");
    }

    @Test
    @DisplayName("维度/度量/时间列/条件模板/关系可按名解析")
    void resolvesDeclaredNames() {
        SemanticRegistry.EntityDef member = AdhocFixtures.registry().entity("member");
        assertThat(member.dimensions().get("STORE_CODE").column()).isEqualTo("member_allot_store");
        assertThat(member.dimensions().get("STORE_NAME").agg()).isEqualTo("MAX");
        // 2026-11-09：member 侧不再提供消费金额度量（改由 order.SALES 取 expense_realamt）
        assertThat(member.measures()).doesNotContainKey("TOTAL_EXPENSE");
        assertThat(member.measures().get("MEMBER_EXPENSE_TIMES").column()).isEqualTo("member_expense_time");
        assertThat(AdhocFixtures.registry().entity("order").measures().get("SALES").column())
                .isEqualTo("expense_realamt");
        assertThat(member.timeColumns()).containsEntry("TRACK_TIME", "track_time");
        assertThat(member.conditionPresets().get("IS_VIP").value()).isEqualTo("1");
        assertThat(member.relationships().get("order").on()).contains("mv.member_mobile");
    }

    @Test
    @DisplayName("真实 metadata.yaml：detail 实体就位——92 天守卫、定向条件列、member 关系、实体级缺省 limit")
    void detailEntityRegistered() {
        SemanticRegistry registry = AdhocFixtures.registry();
        SemanticRegistry.EntityDef detail = registry.entity("detail");
        assertThat(detail.table()).isEqualTo("expense_calendar_details");
        assertThat(detail.alias()).isEqualTo("ed");
        assertThat(detail.baselineFilters()).containsExactly("ed.expense_d_state = '10'", "ed.del_flag = '0'");
        assertThat(detail.queryGuard().requiredAnyOf())
                .contains("timeRange", "expense_d_mobile", "expense_d_orderId");
        assertThat(registry.constraintInt(detail, "maxTimeRangeDays", 550)).isEqualTo(92);
        assertThat(registry.constraintInt(detail, "defaultLimit", 100)).isEqualTo(500);
        // 未配守卫的实体回退全局值：member 的时间跨度仍是全局 550
        assertThat(registry.constraintInt(registry.entity("member"), "maxTimeRangeDays", 550)).isEqualTo(550);
        assertThat(detail.relationships().get("member").on()).contains("ed.expense_d_mobile = mv.member_mobile");
    }

    @Test
    @DisplayName("真实 metadata.yaml：关系基数与 countKey 就位——member→detail 为 to_many，detail→member 为 to_one")
    void relationshipCardinalityAndCountKey() {
        SemanticRegistry registry = AdhocFixtures.registry();
        SemanticRegistry.EntityDef member = registry.entity("member");
        SemanticRegistry.EntityDef order = registry.entity("order");
        SemanticRegistry.EntityDef detail = registry.entity("detail");

        assertThat(member.countKey()).isEqualTo("member_mobile");
        assertThat(order.countKey()).isEqualTo("id");
        assertThat(detail.countKey()).isEqualTo("id");
        assertThat(member.relationships().get("detail").toMany()).isTrue();
        assertThat(member.relationships().get("order").toMany()).isTrue();
        assertThat(detail.relationships().get("member").toOne()).isTrue();
        assertThat(order.relationships().get("member").toOne()).isTrue();
        // detail→order 在流水号唯一性确认前不声明，三表组合走批量拆块
        assertThat(detail.relationships()).doesNotContainKey("order");
    }

    @Test
    @DisplayName("加载期：关系 cardinality 只接受 to_one/to_many，其他值拒绝加载")
    void illegalCardinalityRejected() {
        Map<String, Object> n = node("tbl_ok", "mv");
        n.put("relationships", List.of(item("target", "other", "type", "LEFT JOIN",
                "on", "mv.id = o.id", "cardinality", "to_some")));
        assertThatThrownBy(() -> SemanticRegistry.fromYaml(root("m", n, "other", node("tbl_two", "o"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cardinality");
    }

    @Test
    @DisplayName("加载期：关系目标实体未声明 → 拒绝加载，避免运行期才暴露写错的实体名")
    void unknownRelationshipTargetRejected() {
        Map<String, Object> n = node("tbl_ok", "mv");
        n.put("relationships", List.of(item("target", "ghost", "type", "LEFT JOIN",
                "on", "mv.id = g.id")));
        assertThatThrownBy(() -> SemanticRegistry.fromYaml(root("m", n)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未知关系目标")
                .hasMessageContaining("ghost");
    }

    @Test
    @DisplayName("加载期：含 COUNT(*) 度量但缺 countKey → 拒绝加载（防 join 场景 NULL 行计数）")
    void countStarRequiresCountKey() {
        Map<String, Object> n = node("tbl_ok", "mv");
        measures(n, item("name", "M_ONE", "expr", "*", "agg", "COUNT"));
        assertThatThrownBy(() -> SemanticRegistry.fromYaml(root("m", n)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("countKey");
    }

    @Test
    @DisplayName("加载期：queryGuard 的 requiredAnyOf 列名必须是合法标识符（timeRange 关键字除外）")
    void queryGuardIllegalColumnRejected() {
        Map<String, Object> n = node("tbl_ok", "mv");
        n.put("queryGuard", Map.of("requiredAnyOf", List.of("col;drop")));
        assertThatThrownBy(() -> SemanticRegistry.fromYaml(root("m", n)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("标识符");
    }

    @Test
    @DisplayName("真实 metadata.yaml：indexedColumns 与 DDL 索引一致——detail 的 commCodeName 不在索引清单")
    void indexedColumnsMatchDdl() {
        SemanticRegistry registry = AdhocFixtures.registry();
        // order：门店编码 index_stationCode、手机号 index_mobile（真库 SHOW INDEX 复核）
        assertThat(registry.entity("order").indexedColumns())
                .containsExactly("expense_stationCode", "expense_mobile");
        assertThat(registry.entity("detail").indexedColumns())
                .contains("expense_d_stationCode", "expense_d_gdcode", "expense_d_mobile", "expense_d_orderId")
                .doesNotContain("expense_d_commCodeName");
        assertThat(registry.entity("detail").indexed("expense_d_commCodeName")).isFalse();
        assertThat(registry.entity("member").indexed("member_allot_stationName")).isTrue();
        // member：member_mobile 明文 varchar(100)，index_mobile 可等值过滤
        assertThat(registry.entity("member").indexed("member_mobile")).isTrue();
    }

    @Test
    @DisplayName("加载期：indexedColumns 引用未声明为可过滤的列 → 拒绝加载，避免目录自相矛盾")
    void indexedColumnOutsideFilterablesRejected() {
        Map<String, Object> n = node("tbl_ok", "mv");
        n.put("filterableColumns", List.of("col_a"));
        n.put("indexedColumns", List.of("col_b"));
        assertThatThrownBy(() -> SemanticRegistry.fromYaml(root("m", n)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("indexedColumns")
                .hasMessageContaining("col_b");
    }

    @Test
    @DisplayName("未知实体：抛 AdhocException，中文可读原因")
    void unknownEntityRejected() {
        assertThatThrownBy(() -> AdhocFixtures.registry().entity("hr"))
                .isInstanceOf(AdhocException.class)
                .hasMessageContaining("未知实体");
    }

    @Test
    @DisplayName("加载期硬校验：表不在 ColumnTypeRegistry 白名单 → 拒绝加载（两参生产入口）")
    void unknownTableRejectedByWhitelist() {
        ColumnTypeRegistry types = new ColumnTypeRegistry(Set.of(), Map.of("tbl_ok", Set.of()));
        Map<String, Object> good = root("m", node("tbl_ok", "mv"));
        assertThat(SemanticRegistry.fromYaml(good, types).entities()).containsExactly("m");

        Map<String, Object> bad = root("m", node("evil_table", "mv"));
        assertThatThrownBy(() -> SemanticRegistry.fromYaml(bad, types))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("白名单");
    }

    @Test
    @DisplayName("加载期硬校验：跨实体别名重复直接失败，中文可读原因")
    void duplicateAliasAcrossEntitiesRejected() {
        Map<String, Object> root = root(
                "a", node("tbl_one", "x"),
                "b", node("tbl_two", "x"));
        assertThatThrownBy(() -> SemanticRegistry.fromYaml(root))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("别名");
    }

    @Test
    @DisplayName("加载期硬校验：表/列名等标识符非法直接失败")
    void illegalIdentifierRejected() {
        Map<String, Object> badTable = node("tbl;drop", "mv");
        assertThatThrownBy(() -> SemanticRegistry.fromYaml(root("m", badTable)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("标识符");

        Map<String, Object> badColumn = node("tbl_ok", "mv");
        dims(badColumn, item("name", "D1", "column", "col-name"));
        assertThatThrownBy(() -> SemanticRegistry.fromYaml(root("m", badColumn)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("标识符");
    }

    @Test
    @DisplayName("加载期硬校验：度量缺 agg 报中文原因，不再 NPE")
    void measureMissingAggRejected() {
        Map<String, Object> n = node("tbl_ok", "mv");
        measures(n, item("name", "M_ONE", "column", "amt"));
        assertThatThrownBy(() -> SemanticRegistry.fromYaml(root("m", n)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("M_ONE")
                .hasMessageContaining("agg");
    }

    // ------------------------------------------------------------------
    // 内存夹具：最小化 metadata 结构，专测加载期校验
    // ------------------------------------------------------------------

    private static Map<String, Object> root(Object... entityNameToNode) {
        Map<String, Object> entities = new LinkedHashMap<>();
        for (int i = 0; i < entityNameToNode.length; i += 2) {
            entities.put((String) entityNameToNode[i], entityNameToNode[i + 1]);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("entities", entities);
        return r;
    }

    private static Map<String, Object> node(String table, String alias) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("table", table);
        n.put("alias", alias);
        return n;
    }

    private static Map<String, Object> item(String... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    @SafeVarargs
    private static void dims(Map<String, Object> node, Map<String, Object>... items) {
        node.put("dimensions", List.of(items));
    }

    @SafeVarargs
    private static void measures(Map<String, Object> node, Map<String, Object>... items) {
        node.put("measures", List.of(items));
    }
}
