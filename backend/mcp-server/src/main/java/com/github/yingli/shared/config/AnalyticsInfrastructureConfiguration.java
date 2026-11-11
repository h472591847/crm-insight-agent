package com.github.yingli.shared.config;

import com.github.yingli.shared.catalog.ColumnTypeRegistry;
import com.github.yingli.shared.catalog.ColumnTypesLoader;
import com.github.yingli.shared.limits.ExecutionLimits;
import com.github.yingli.shared.sql.SqlExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.io.IOException;

/**
 * @Description 分析基础设施装配：列类型/PII 登记表与受限执行器。
 * <p>
 * 这两项被 metrics 与 adhoc 两个域共同依赖，故上提到 shared，由本配置类统一声明 Bean，
 * 域内配置类只负责各自的注册表装配，避免"一个域的配置类声明另一个域的基础设施"。
 * 配置类只声明 Bean，YAML 解析逻辑在 {@link ColumnTypesLoader}；资源路径保持不变。
 *
 * @Author crm-agent
 */
@Configuration
public class AnalyticsInfrastructureConfiguration {

    @Bean
    public ColumnTypeRegistry columnTypeRegistry(ColumnTypesLoader loader) throws IOException {
        return loader.load();
    }

    /**
     * 受限执行器：独立 JdbcTemplate 实例（不改全局共享 Bean），
     * 行数上限与查询超时在这里兜底，与编译产物中的 LIMIT 相互独立。
     */
    @Bean
    public SqlExecutor sqlExecutor(DataSource dataSource) {
        JdbcTemplate restricted = new JdbcTemplate(dataSource);
        restricted.setMaxRows(ExecutionLimits.EXEC_MAX_ROWS);
        restricted.setQueryTimeout(ExecutionLimits.QUERY_TIMEOUT_SECONDS);
        return new SqlExecutor.JdbcSqlExecutor(restricted);
    }
}
