package com.github.yingli.adhoc;

import com.github.yingli.adhoc.compile.AdhocSqlCompiler;
import com.github.yingli.adhoc.config.SemanticCatalogLoader;
import com.github.yingli.adhoc.validate.AdhocRequestValidator;
import com.github.yingli.adhoc.model.SemanticRegistry;
import com.github.yingli.adhoc.service.AdhocQueryService;
import com.github.yingli.adhoc.service.SemanticCatalogService;
import com.github.yingli.mcp.tool.AdhocTool;
import com.github.yingli.shared.catalog.ColumnTypeRegistry;
import com.github.yingli.shared.catalog.ColumnTypesLoader;
import com.github.yingli.shared.sql.SqlExecutor;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 测试夹具：加载 classpath 真实 metadata.yaml，一次初始化全局共用。
 * 走两参生产入口（携带真实 ColumnTypeRegistry）：加载成功本身即覆盖启动期表白名单硬校验，
 * 模式对齐 MetricsFixtures。
 */
public final class AdhocFixtures {

    private static final ColumnTypeRegistry TYPES = loadTypes();
    private static final SemanticRegistry REGISTRY = load();

    private AdhocFixtures() {
    }

    public static SemanticRegistry registry() {
        return REGISTRY;
    }

    public static ColumnTypeRegistry types() {
        return TYPES;
    }

    /** 语义目录服务（B0 契约快照与工具委托测试用）。 */
    public static SemanticCatalogService catalogService() {
        return new SemanticCatalogService(REGISTRY, TYPES);
    }

    /** 组装 adhoc 工具对象（B0 契约快照用；执行器不连库）。 */
    public static AdhocTool adhocTool() {
        SqlExecutor executor = mock(SqlExecutor.class);
        when(executor.query(anyString(), anyList(), anyString())).thenReturn(java.util.List.of());
        AdhocQueryService service = new AdhocQueryService(
                new AdhocRequestValidator(REGISTRY, TYPES), new AdhocSqlCompiler(REGISTRY, TYPES), executor);
        return new AdhocTool(service, catalogService());
    }

    private static SemanticRegistry load() {
        try {
            return new SemanticCatalogLoader(TYPES).load();
        } catch (Exception e) {
            throw new IllegalStateException("加载 semantic/metadata.yaml 失败", e);
        }
    }

    private static ColumnTypeRegistry loadTypes() {
        try {
            return new ColumnTypesLoader().load();
        } catch (Exception e) {
            throw new IllegalStateException("加载 metrics/column-types.yaml 失败", e);
        }
    }
}
