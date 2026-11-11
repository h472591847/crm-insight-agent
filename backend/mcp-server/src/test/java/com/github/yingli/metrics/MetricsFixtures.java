package com.github.yingli.metrics;

import com.github.yingli.contract.ContractSnapshots;
import com.github.yingli.metrics.compile.MetricSqlCompiler;
import com.github.yingli.metrics.config.MetricRegistryLoader;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.yingli.mcp.tool.CrmAnalyticsTool;
import com.github.yingli.mcp.tool.MetricTool;
import com.github.yingli.metrics.model.MetricDefinition;
import com.github.yingli.metrics.registry.MetricRegistry;
import com.github.yingli.metrics.service.MetricCatalogService;
import com.github.yingli.metrics.service.MetricQueryService;
import com.github.yingli.metrics.tooling.MetricDrafter;
import com.github.yingli.shared.catalog.ColumnTypeRegistry;
import com.github.yingli.shared.catalog.ColumnTypesLoader;
import com.github.yingli.shared.sql.SqlExecutor;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @ClassName MetricsFixtures
 * @Description 测试夹具：直接调用生产的 {@link MetricRegistryLoader} 加载
 * classpath:/metrics/*.yaml。加载成功本身即代表"配置通过全部安全校验"（校验失败会抛异常），
 * 因此每个用夹具的测试都顺带覆盖了启动期校验。
 * <p>
 * B0 起额外承担契约快照的规范化与比对：JSON 一律按「键排序后的规范形式」比较，保证重构前后
 * 工具描述/目录结构/SQL 的 diff 可判定。
 * @Author crm-agent
 */
public final class MetricsFixtures {

    private static final ColumnTypeRegistry TYPES;
    private static final MetricRegistry REGISTRY;

    static {
        try {
            TYPES = new ColumnTypesLoader().load();
            REGISTRY = new MetricRegistryLoader(TYPES).load();
        } catch (IOException e) {
            throw new UncheckedIOException("加载指标配置失败", e);
        }
    }

    private MetricsFixtures() {
    }

    public static MetricRegistry registry() {
        return REGISTRY;
    }

    public static ColumnTypeRegistry types() {
        return TYPES;
    }

    public static MetricDefinition def(String metric) {
        return REGISTRY.require(metric);
    }

    /** 用夹具注册表 + 指定执行器组装服务（执行器可 mock，不连库）。 */
    public static MetricQueryService service(SqlExecutor executor) {
        return new MetricQueryService(REGISTRY, new MetricSqlCompiler(), executor);
    }

    /** 组装脚手架（不连库：只依赖登记表、编译器与注册表）。 */
    public static MetricDrafter drafter() {
        return new MetricDrafter(TYPES, new MetricSqlCompiler(), REGISTRY);
    }

    /** 不连库的执行器：目录/编译类断言不会触达 JDBC，查询默认返回空集。 */
    public static SqlExecutor noopExecutor() {
        SqlExecutor executor = mock(SqlExecutor.class);
        when(executor.query(anyString(), anyList(), anyString())).thenReturn(List.of());
        return executor;
    }

    /** 各域目录服务（B0 契约快照与工具委托测试用）。 */
    public static MetricCatalogService catalogService() {
        return new MetricCatalogService(REGISTRY);
    }

    /** 指标工具对象（B0 契约快照用，不执行查询）。 */
    public static MetricTool metricTool() {
        return new MetricTool(service(noopExecutor()), catalogService(), drafter());
    }

    /** Phase 1 兼容工具对象（B0 契约快照用，不执行查询）。 */
    public static CrmAnalyticsTool crmAnalyticsTool() {
        return new CrmAnalyticsTool(service(noopExecutor()));
    }

    // ------------------------------------------------------------------
    // 契约快照：委托中立的 ContractSnapshots（避免 adhoc 测试反向 import metrics）
    // ------------------------------------------------------------------

    public static String canonicalJson(Object value) {
        return ContractSnapshots.canonicalJson(value);
    }

    public static JsonNode parseJson(String json) {
        return ContractSnapshots.parseJson(json);
    }

    public static String readBaseline(String baselineName) {
        return ContractSnapshots.readBaseline(baselineName);
    }

    public static void assertContractBaseline(String baselineName, Object actual) {
        ContractSnapshots.assertContractBaseline(baselineName, actual);
    }

    public static void writeBaseline(String baselineName, Object value) {
        ContractSnapshots.writeBaseline(baselineName, value);
    }

    /** 稳定的工具排序（按 name 字典序），避免 Provider 注册顺序影响快照。 */
    public static Comparator<Map<String, Object>> byToolName() {
        return Comparator.comparing(m -> String.valueOf(m.get("name")));
    }

    public static Map<String, Object> orderedMap(Object... kv) {
        return ContractSnapshots.orderedMap(kv);
    }
}
