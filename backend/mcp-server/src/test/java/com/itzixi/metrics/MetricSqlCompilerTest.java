package com.itzixi.metrics;

import com.itzixi.metrics.validate.MetricQueryValidator;
import com.itzixi.metrics.exception.MetricException;
import com.itzixi.metrics.compile.MetricSqlCompiler;
import com.itzixi.metrics.model.MetricDefinition;
import com.itzixi.metrics.model.QueryPlan;
import com.itzixi.shared.sql.GeneratedSql;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @ClassName MetricSqlCompilerTest
 * @Description 编译器生成正确性单测（golden SQL 快照）。不连接数据库。
 * 覆盖：NUM() 安全展开、左闭右开时间边界、参数化过滤、白名单排序、强制 LIMIT、
 * 合计行编译，以及越权/注入形态的入参必须被拒绝。
 * 改编译器必须同步改这里的快照，强制 review（方案 §十二）。
 * @Author crm-agent
 */
class MetricSqlCompilerTest {

    private final MetricSqlCompiler compiler = new MetricSqlCompiler();

    private QueryPlan plan(String metric, List<String> dims, Map<String, String> filters,
                           String start, String end, int limit, String sortBy, String dir) {
        return new QueryPlan(metric, dims, filters, start, end, limit, sortBy, dir);
    }

    @Test
    @DisplayName("STATION_SALES：分组查询与 Phase 1 手写 XML 逐段等价（golden 快照，仅门店过滤）")
    void stationSales_goldenSql() {
        MetricDefinition def = MetricsFixtures.def("STATION_SALES");
        GeneratedSql gen = compiler.compile(def, plan("STATION_SALES", List.of("STATION"),
                Map.of("STATION", "S1"), "2026-06-20", "2026-06-27", 500, null, "desc"));

        assertThat(gen.sql()).isEqualTo("""
                SELECT expense_stationCode AS station_code, \
                MAX(expense_stationName) AS station_name, \
                COALESCE(SUM(CAST(NULLIF(TRIM(expense_realamt), '') AS DECIMAL(20, 2))), 0) AS sales_amount, \
                COUNT(*) AS order_count \
                FROM expense_calendar_info \
                WHERE (expense_state = '10') AND (del_flag = '0') \
                AND expense_buyDate >= ? AND expense_buyDate < DATE_ADD(?, INTERVAL 1 DAY) \
                AND expense_stationCode = ? \
                GROUP BY expense_stationCode ORDER BY sales_amount desc LIMIT 500""");

        // 时间边界与过滤值一律参数化，顺序与占位一致
        assertThat(gen.params()).containsExactly("2026-06-20", "2026-06-27", "S1");
        assertThat(gen.sqlHash()).hasSize(16);
    }

    @Test
    @DisplayName("租户过滤已取消（2026-10-30）：指标无 TENANT 声明时，编译产物不得出现任何租户列")
    void tenantFilter_noLongerCompiled() {
        // 上游基线：MetricQueryValidator 会先拒绝未声明的 TENANT 编码（见 MetricToolInjectionTest）；
        // 本用例锁定编译器本身的兜底行为：即使过滤 map 里混入 TENANT，也不会拼出任何租户条件。
        MetricDefinition def = MetricsFixtures.def("STATION_SALES");
        GeneratedSql gen = compiler.compile(def, plan("STATION_SALES", List.of("STATION"),
                Map.of("TENANT", "T1"), "2026-06-20", "2026-06-27", 500, null, "desc"));

        assertThat(def.optionalFilters().stream().map(MetricDefinition.Filter::code).toList())
                .as("配置层已不再声明租户过滤").containsExactly("STATION");
        assertThat(gen.sql()).doesNotContain("expense_tenant").doesNotContain("member_tenant");
        assertThat(gen.params()).containsExactly("2026-06-20", "2026-06-27");
    }

    @Test
    @DisplayName("PRODUCT_RANKING：NUM(decimalScale=4) 展开、按声明编码排序、强制 LIMIT")
    void productRanking_numScaleAndSort() {
        MetricDefinition def = MetricsFixtures.def("PRODUCT_RANKING");
        GeneratedSql gen = compiler.compile(def, plan("PRODUCT_RANKING", List.of("SKU"),
                Map.of("STATION", "S9"), "2026-06-20", "2026-06-27", 10, "QTY", "asc"));

        // 度量声明了 zeroFill，编译产物带 COALESCE 兜底，断言需包含完整展开
        assertThat(gen.sql())
                .contains("COALESCE(SUM(CAST(NULLIF(TRIM(expense_d_qty), '') AS DECIMAL(20, 4))), 0) AS quantity")
                .contains("AND expense_d_stationCode = ?")
                .contains("ORDER BY quantity asc")
                .endsWith("LIMIT 10");
        assertThat(gen.params()).containsExactly("2026-06-20", "2026-06-27", "S9");
    }

    @Test
    @DisplayName("MEMBER_OVERVIEW：累计口径不注入时间条件，无默认排序不加 ORDER BY")
    void memberOverview_timeAgnostic() {
        MetricDefinition def = MetricsFixtures.def("MEMBER_OVERVIEW");
        GeneratedSql gen = compiler.compile(def, plan("MEMBER_OVERVIEW", List.of(),
                Map.of(), null, null, 100, null, "desc"));

        assertThat(gen.sql())
                .doesNotContain("DATE_ADD")
                .doesNotContain("ORDER BY")
                .contains("WHERE (del_flag = '0')")
                .contains("COUNT(*) AS total_members")
                .endsWith("LIMIT 100");
        assertThat(gen.params()).isEmpty();
    }

    @Test
    @DisplayName("compileTotals：同 WHERE 无 GROUP BY / ORDER BY / LIMIT（全量合计而非 TopN 合计）")
    void totals_sameWhereNoGroupNoLimit() {
        MetricDefinition def = MetricsFixtures.def("STATION_SALES");
        GeneratedSql gen = compiler.compileTotals(def, plan("STATION_SALES", List.of("STATION"),
                Map.of("STATION", "S1"), "2026-06-20", "2026-06-27", 500, null, "desc"));

        assertThat(gen.sql())
                .doesNotContain("GROUP BY")
                .doesNotContain("ORDER BY")
                .doesNotContain("LIMIT")
                .startsWith("SELECT COALESCE(SUM(CAST(NULLIF(TRIM(expense_realamt), '') AS DECIMAL(20, 2))), 0) AS sales_amount, COUNT(*) AS order_count FROM");
        assertThat(gen.params()).containsExactly("2026-06-20", "2026-06-27", "S1");
    }

    @Test
    @DisplayName("过滤值不会被拼进 SQL（注入形态仅作为参数传递）")
    void filterValues_areParameterizedNotConcatenated() {
        MetricDefinition def = MetricsFixtures.def("STATION_SALES");
        String malicious = "x' OR '1'='1";
        GeneratedSql gen = compiler.compile(def, plan("STATION_SALES", List.of("STATION"),
                Map.of("STATION", malicious), "2026-06-20", "2026-06-27", 100, null, "desc"));

        // 断言注入片段未出现在 SQL 文本中（"ORDER BY" 本身含 "OR"，不能笼统断言不含 OR）
        assertThat(gen.sql()).doesNotContain(malicious).doesNotContain("1'='1");
        assertThat(gen.params()).contains(malicious);
    }

    @Test
    @DisplayName("MEMBER_NEW：golden 快照（注册门店维度 + 描述列 MAX + 默认按度量排序）")
    void memberNew_goldenSql() {
        MetricDefinition def = MetricsFixtures.def("MEMBER_NEW");
        GeneratedSql gen = compiler.compile(def, plan("MEMBER_NEW", List.of("STORE"),
                Map.of(), "2026-06-20", "2026-06-27", 500, null, "desc"));

        assertThat(gen.sql()).isEqualTo("""
                SELECT member_store AS store_code, \
                MAX(member_stationName) AS store_name, \
                COUNT(*) AS new_member_count \
                FROM member_info_view \
                WHERE (del_flag = '0') \
                AND member_created >= ? AND member_created < DATE_ADD(?, INTERVAL 1 DAY) \
                GROUP BY member_store ORDER BY new_member_count desc LIMIT 500""");
        assertThat(gen.params()).containsExactly("2026-06-20", "2026-06-27");
    }

    @Test
    @DisplayName("ACTIVE_MEMBER：COUNT(DISTINCT expense_mobile) 编译正确；合计查询为全局去重而非分组求和")
    void activeMember_countDistinctMobile() {
        MetricDefinition def = MetricsFixtures.def("ACTIVE_MEMBER");
        GeneratedSql gen = compiler.compile(def, plan("ACTIVE_MEMBER", List.of("STATION"),
                Map.of(), "2026-06-20", "2026-06-27", 500, null, "desc"));

        assertThat(gen.sql())
                .contains("COUNT(DISTINCT expense_mobile) AS active_member_count")
                .contains("COUNT(*) AS order_count")
                .contains("GROUP BY expense_stationCode")
                .endsWith("LIMIT 500");

        GeneratedSql totals = compiler.compileTotals(def, plan("ACTIVE_MEMBER", List.of("STATION"),
                Map.of(), "2026-06-20", "2026-06-27", 500, null, "desc"));
        assertThat(totals.sql())
                .doesNotContain("GROUP BY")
                .contains("COUNT(DISTINCT expense_mobile) AS active_member_count");
    }

    @Test
    @DisplayName("AGE_DIST：阴历生日 CASE 分桶表达式直通 SELECT/GROUP BY（生日仅作计算输入，不注入时间条件）")
    void ageDist_caseBucketCompiled() {
        MetricDefinition def = MetricsFixtures.def("AGE_DIST");
        GeneratedSql gen = compiler.compile(def, plan("AGE_DIST", List.of("AGE_BAND"),
                Map.of(), null, null, 100, null, "desc"));

        // 2026-10-30 口径：阳历列 100% 为空，改用阴历 varchar 列，必须先 STR_TO_DATE 再参与日期运算
        assertThat(gen.sql())
                .contains("CASE WHEN member_yin_birthday IS NULL")
                .contains("STR_TO_DATE(member_yin_birthday, '%Y-%m-%d') IS NULL")
                .contains("TIMESTAMPDIFF(YEAR, STR_TO_DATE(member_yin_birthday, '%Y-%m-%d'), CURDATE()) <= 19")
                .contains("AS age_band")
                .contains("GROUP BY CASE WHEN member_yin_birthday")
                .doesNotContain("member_yang_birthday")
                .doesNotContain("DATE_ADD")
                .endsWith("LIMIT 100");
        assertThat(gen.params()).isEmpty();
    }

    @Test
    @DisplayName("GENDER_CONSUMPTION_COMPARE：受控 LEFT JOIN 的 golden 快照（主表别名 + 限定列直通 + 合计同 FROM）")
    void genderComparison_joinGoldenSql() {
        MetricDefinition def = MetricsFixtures.def("GENDER_CONSUMPTION_COMPARE");
        GeneratedSql gen = compiler.compile(def, plan("GENDER_CONSUMPTION_COMPARE", List.of("GENDER"),
                Map.of(), "2026-06-01", "2026-06-27", 10, null, "desc"));

        assertThat(gen.sql()).isEqualTo("""
                SELECT CASE WHEN m.member_sex = 1 THEN '男' WHEN m.member_sex = 2 THEN '女' ELSE '未知性别' END AS gender, \
                COALESCE(SUM(CAST(NULLIF(TRIM(e.expense_realamt), '') AS DECIMAL(20, 2))), 0) AS sales_amount, \
                COUNT(*) AS order_count, \
                COUNT(DISTINCT e.expense_mobile) AS buyer_count \
                FROM expense_calendar_info e LEFT JOIN member_info_view m ON e.expense_mobile = m.member_mobile \
                WHERE (e.expense_state = '10') AND (e.del_flag = '0') AND ((m.del_flag = '0' OR m.member_mobile IS NULL)) \
                AND e.expense_buyDate >= ? AND e.expense_buyDate < DATE_ADD(?, INTERVAL 1 DAY) \
                GROUP BY CASE WHEN m.member_sex = 1 THEN '男' WHEN m.member_sex = 2 THEN '女' ELSE '未知性别' END \
                ORDER BY sales_amount desc LIMIT 10""");
        assertThat(gen.params()).containsExactly("2026-06-01", "2026-06-27");

        // 合计查询：同 FROM 同 WHERE（度量引用了关联表列，join 必须共存），仅去 GROUP BY/ORDER BY/LIMIT
        GeneratedSql totals = compiler.compileTotals(def, plan("GENDER_CONSUMPTION_COMPARE", List.of("GENDER"),
                Map.of(), "2026-06-01", "2026-06-27", 10, null, "desc"));
        assertThat(totals.sql())
                .contains("FROM expense_calendar_info e LEFT JOIN member_info_view m ON e.expense_mobile = m.member_mobile")
                .doesNotContain("GROUP BY")
                .doesNotContain("LIMIT");
    }

    @Test
    @DisplayName("未声明维度 / 非白名单排序编码：编译期拒绝")
    void unknownDimensionAndSort_rejected() {
        MetricDefinition def = MetricsFixtures.def("STATION_SALES");

        assertThatThrownBy(() -> compiler.compile(def, plan("STATION_SALES", List.of("MOBILE"),
                Map.of(), "2026-06-20", "2026-06-27", 100, null, "desc")))
                .isInstanceOf(MetricException.class)
                .hasMessageContaining("不支持维度");

        assertThatThrownBy(() -> compiler.compile(def, plan("STATION_SALES", List.of("STATION"),
                Map.of(), "2026-06-20", "2026-06-27", 100, "SALES; DROP TABLE x", "desc")))
                .isInstanceOf(MetricException.class)
                .hasMessageContaining("不允许按");
    }

    @Test
    @DisplayName("时间口径指标缺失区间：拒绝而非静默全表统计")
    void timeMetric_withoutRange_rejected() {
        MetricDefinition def = MetricsFixtures.def("STATION_SALES");
        assertThatThrownBy(() -> compiler.compile(def, plan("STATION_SALES", List.of(),
                Map.of(), null, null, 100, null, "desc")))
                .isInstanceOf(MetricException.class)
                .hasMessageContaining("必须提供时间范围");
    }

    @Test
    @DisplayName("派生编码不可作为 SQL 排序键（派生值不在 SQL 中）")
    void derivedCannotBeSqlSortKey() {
        MetricDefinition def = MetricsFixtures.def("STATION_SALES");
        // AVG_ORDER_VALUE 在 derived 里声明为可展示编码，但不在度量别名映射中
        QueryPlan p = plan("STATION_SALES", List.of("STATION"), Map.of(),
                "2026-06-20", "2026-06-27", 100, "AVG_ORDER_VALUE", "desc");
        // 指标 sortable 未包含派生编码 → 先被 sortable 白名单拒绝
        assertThatThrownBy(() -> compiler.compile(def, p))
                .isInstanceOf(MetricException.class)
                .hasMessageContaining("不允许按");
    }
}
