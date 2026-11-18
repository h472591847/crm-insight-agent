package com.github.yingli.adhoc.validate;

import com.github.yingli.adhoc.model.SemanticRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * MetadataBootstrapValidator 单元测试。
 * 用 Mockito mock JdbcTemplate（模拟 INFORMATION_SCHEMA 查询结果），
 * 反射调用 inferCardinality() 核心逻辑，覆盖 4 种场景。
 */
class MetadataBootstrapValidatorTest {

    // ——————————————————————————————————— 场景 1：order→member to_one ————————————————————————————————————

    @Test
    void orderToMember_infers_to_one_when_memberMobileHasUniqueIndex() throws Exception {
        // Tom 的 SQL 从 INFORMATION_SCHEMA 返回：member_info_view.member_mobile 有单列唯一索引
        JdbcTemplate jdbc = mockJdbc(List.of(
                new String[]{"member_info_view", "member_mobile"}   // 唯一索引列
        ));

        SemanticRegistry registry = mockRegistryWith("order", "member", "to_one",
                "e.expense_mobile = mv.member_mobile", "e", "expense_calendar_info",
                "mv", "member_info_view");

        Map<String, String> aliasMap = Map.of(
                "e", "expense_calendar_info",
                "mv", "member_info_view"
        );

        String inferred = invokeInfer(jdbc, registry, "order", "member", "to_one",
                "e.expense_mobile = mv.member_mobile", aliasMap);

        assertEquals(SemanticRegistry.RelationshipDef.TO_ONE, inferred);
    }

    // ——————————————————————————————————— 场景 2：member→order to_many ———————————————————————————————————

    @Test
    void memberToOrder_infers_to_many_when_expenseMobileIsNotUnique() throws Exception {
        // INFORMATION_SCHEMA 里 expense_calendar_info.expense_mobile 没有单列唯一索引（一对多）
        JdbcTemplate jdbc = mockJdbc(List.of(
                new String[]{"member_info_view", "member_mobile"}   // 只有 member_mobile 唯一
                // 注意：没有 expense_calendar_info.expense_mobile
        ));

        SemanticRegistry registry = mockRegistryWith("member", "order", "to_many",
                "mv.member_mobile = e.expense_mobile", "mv", "member_info_view",
                "e", "expense_calendar_info");

        Map<String, String> aliasMap = Map.of(
                "mv", "member_info_view",
                "e", "expense_calendar_info"
        );

        String inferred = invokeInfer(jdbc, registry, "member", "order", "to_many",
                "mv.member_mobile = e.expense_mobile", aliasMap);

        assertEquals(SemanticRegistry.RelationshipDef.TO_MANY, inferred);
    }

    // ——————————————————————————————————— 场景 3：YAML 声明 to_many，SQL 能证明确实是 to_one（YAML 过度保守） ———————————————————————————————————

    @Test
    void detects_mismatch_when_yaml_says_to_many_but_sql_proves_to_one() throws Exception {
        // SQL 能证明确实是 to_one（member_mobile 有唯一索引），但 YAML 错误声明为 to_many
        // 这种情况应该打 WARN — 我们改后的逻辑：SQL 能证伪 to_many 声明
        JdbcTemplate jdbc = mockJdbc(List.of(
                new String[]{"member_info_view", "member_mobile"}
        ));

        SemanticRegistry registry = mockRegistryWith("order", "member", "to_many",
                "e.expense_mobile = mv.member_mobile", "e", "expense_calendar_info",
                "mv", "member_info_view");

        Map<String, String> aliasMap = Map.of(
                "e", "expense_calendar_info",
                "mv", "member_info_view"
        );

        String inferred = invokeInfer(jdbc, registry, "order", "member", "to_many",
                "e.expense_mobile = mv.member_mobile", aliasMap);

        // SQL 推断 to_one（有唯一索引），但 YAML 声明 to_many
        assertEquals(SemanticRegistry.RelationshipDef.TO_ONE, inferred);
        assertNotEquals("to_many", inferred); // validateAll 里会 log.warn 的场景
    }

    // ——————————————————————————————————— 场景 4：表无 UNIQUE 索引 → SQL 推断不到 → 信任 YAML ———————————————————————————————————

    @Test
    void returns_null_when_no_unique_index_trusts_yaml() throws Exception {
        // member_info_view.member_mobile 没有唯一索引（业务上认为是 to_one 但 DDL 没声明）
        // SQL 推断不了，应该返回 null → validateAll 跳过，不打 WARN
        JdbcTemplate jdbc = mockJdbc(List.of(
                // 空——member_info_view 完全没有单列唯一索引
        ));

        SemanticRegistry registry = mockRegistryWith("order", "member", "to_one",
                "e.expense_mobile = mv.member_mobile", "e", "expense_calendar_info",
                "mv", "member_info_view");

        Map<String, String> aliasMap = Map.of(
                "e", "expense_calendar_info",
                "mv", "member_info_view"
        );

        String inferred = invokeInfer(jdbc, registry, "order", "member", "to_one",
                "e.expense_mobile = mv.member_mobile", aliasMap);

        // 没有唯一索引 → 推断不了 → 返回 null → 信任 YAML 声明 to_one
        assertNull(inferred);
    }

    // ——————————————————————————————————— 场景 5：目标表不在 INFORMATION_SCHEMA ———————————————————————————————————

    @Test
    void returns_null_when_targetTable_not_in_informationSchema() throws Exception {
        JdbcTemplate jdbc = mockJdbc(List.of()); // 空结果

        SemanticRegistry registry = mockRegistryWith("detail", "unknown", "to_one",
                "ed.expense_d_mobile = u.mobile", "ed", "expense_calendar_details",
                "u", "unknown_table");

        Map<String, String> aliasMap = Map.of(
                "ed", "expense_calendar_details",
                "u", "unknown_table"
        );

        String inferred = invokeInfer(jdbc, registry, "detail", "unknown", "to_one",
                "ed.expense_d_mobile = u.mobile", aliasMap);

        assertNull(inferred);
    }

    // ——————————————————————————————————— 工具方法 ———————————————————————————————————

    /** Mock JdbcTemplate.query 对 Tom SQL 返回预设的 TABLE_NAME/COLUMN_NAME 行 */
    private JdbcTemplate mockJdbc(List<String[]> rows) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        doAnswer(invocation -> {
            RowCallbackHandler handler = invocation.getArgument(1);
            // mock 一个 ResultSet
            var rs = mock(java.sql.ResultSet.class);
            int[] idx = {0};
            for (String[] row : rows) {
                when(rs.next()).thenReturn(true);
                when(rs.getString("TABLE_NAME")).thenReturn(row[0]);
                when(rs.getString("COLUMN_NAME")).thenReturn(row[1]);
                handler.processRow(rs);
            }
            when(rs.next()).thenReturn(false);
            return null;
        }).when(jdbc).query(anyString(), any(RowCallbackHandler.class));
        return jdbc;
    }

    /** Mock SemanticRegistry，让 entity(name) 返回带 relationships 的 EntityDef */
    private SemanticRegistry mockRegistryWith(String fromName, String targetName, String cardinality,
                                               String onClause, String fromAlias, String fromTable,
                                               String targetAlias, String targetTable) {
        SemanticRegistry registry = mock(SemanticRegistry.class);

        var targetRel = new SemanticRegistry.RelationshipDef(targetName, "LEFT JOIN", cardinality, onClause, List.of());

        // from EntityDef
        var fromEntity = mock(SemanticRegistry.EntityDef.class);
        when(fromEntity.name()).thenReturn(fromName);
        when(fromEntity.alias()).thenReturn(fromAlias);
        when(fromEntity.table()).thenReturn(fromTable);
        when(fromEntity.relationships()).thenReturn(Map.of(targetName, targetRel));

        // target EntityDef
        var targetEntity = mock(SemanticRegistry.EntityDef.class);
        when(targetEntity.name()).thenReturn(targetName);
        when(targetEntity.alias()).thenReturn(targetAlias);
        when(targetEntity.table()).thenReturn(targetTable);
        when(targetEntity.relationships()).thenReturn(Map.of());

        when(registry.entities()).thenReturn(Set.of(fromName, targetName));
        when(registry.entity(fromName)).thenReturn(fromEntity);
        when(registry.entity(targetName)).thenReturn(targetEntity);

        return registry;
    }

    /** 反射调用 MetadataBootstrapValidator.inferCardinality（private 方法） */
    private String invokeInfer(JdbcTemplate jdbc, SemanticRegistry registry,
                               String fromName, String targetName, String cardinality,
                               String onClause, Map<String, String> aliasMap) throws Exception {
        MetadataBootstrapValidator validator = new MetadataBootstrapValidator(jdbc, registry);

        // 用反射调用 loadSingleColUniqueKeys
        Method loadMethod = MetadataBootstrapValidator.class.getDeclaredMethod("loadSingleColUniqueKeys");
        loadMethod.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Set<String>> uniqueKeys = (Map<String, Set<String>>) loadMethod.invoke(validator);

        // 用反射调用 inferCardinality
        Method inferMethod = MetadataBootstrapValidator.class.getDeclaredMethod(
                "inferCardinality",
                SemanticRegistry.EntityDef.class,
                SemanticRegistry.RelationshipDef.class,
                Map.class,
                Map.class
        );
        inferMethod.setAccessible(true);

        SemanticRegistry.EntityDef fromEntity = registry.entity(fromName);
        SemanticRegistry.RelationshipDef rel = registry.entity(fromName).relationships().get(targetName);

        return (String) inferMethod.invoke(validator, fromEntity, rel, uniqueKeys, aliasMap);
    }
}
