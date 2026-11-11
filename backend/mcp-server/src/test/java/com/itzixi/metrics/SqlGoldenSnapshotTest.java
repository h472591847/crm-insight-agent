package com.itzixi.metrics;

import com.itzixi.metrics.compile.MetricSqlCompiler;
import com.itzixi.metrics.model.MetricDefinition;
import com.itzixi.shared.sql.GeneratedSql;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * @ClassName SqlGoldenSnapshotTest
 * @Description B0 契约快照：用 3 个代表请求锁定完整 SQL 字符串与参数顺序。
 * <p>
 * 与 {@code MetricSqlCompilerTest} 的区别：那边是行为断言（含/不含片段），这里是逐字 golden 快照，
 * 重构编译器（B4 改名 MetricSqlCompiler、B2 移动 GeneratedSql）时任何 SQL 变化都会被发现。
 * @Author crm-agent
 */
public class SqlGoldenSnapshotTest {

    private static final MetricSqlCompiler COMPILER = new MetricSqlCompiler();

    public static Object snapshot() {
        MetricDefinition stationSales = MetricsFixtures.def("STATION_SALES");
        MetricDefinition productRanking = MetricsFixtures.def("PRODUCT_RANKING");
        MetricDefinition genderCompare = MetricsFixtures.def("GENDER_CONSUMPTION_COMPARE");

        GeneratedSql station = COMPILER.compile(stationSales, new com.itzixi.metrics.model.QueryPlan(
                "STATION_SALES", List.of("STATION"), Map.of("STATION", "S1"),
                "2026-06-20", "2026-06-27", 500, null, "desc"));
        GeneratedSql product = COMPILER.compile(productRanking, new com.itzixi.metrics.model.QueryPlan(
                "PRODUCT_RANKING", List.of("SKU"), Map.of("STATION", "S9"),
                "2026-06-20", "2026-06-27", 10, "QTY", "asc"));
        GeneratedSql gender = COMPILER.compile(genderCompare, new com.itzixi.metrics.model.QueryPlan(
                "GENDER_CONSUMPTION_COMPARE", List.of("GENDER"), Map.of(),
                "2026-06-01", "2026-06-27", 10, null, "desc"));

        return MetricsFixtures.orderedMap(
                "STATION_SALES", query(station),
                "PRODUCT_RANKING", query(product),
                "GENDER_CONSUMPTION_COMPARE", query(gender));
    }

    private static Map<String, Object> query(GeneratedSql gen) {
        return MetricsFixtures.orderedMap(
                "sql", gen.sql(),
                "sqlHash", gen.sqlHash(),
                "params", gen.params());
    }

    @Test
    @DisplayName("代表查询的完整 SQL 与参数顺序零 diff")
    void goldenSqlUnchanged() {
        MetricsFixtures.assertContractBaseline("sql-golden.json", snapshot());
    }
}
