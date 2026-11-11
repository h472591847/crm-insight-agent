package com.itzixi.shared.sql;

import com.itzixi.shared.limits.ExecutionLimits;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.ArgumentPreparedStatementSetter;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * @Description 受限执行接缝。抽出接口的目的有两个：
 * 1. 单元测试可 mock，无需连库即可覆盖校验、装配、派生计算等行为；
 * 2. 执行策略（超时、行数上限、只读、审计）集中在一处，不会散落到每个指标里。
 * @Author crm-agent
 */
public interface SqlExecutor {

    /** 日志来源标签：Ad-hoc 动态查询 */
    String SOURCE_ADHOC = "Ad-hoc 查询";

    /** 日志来源标签：指标定义查询 */
    String SOURCE_METRIC = "指标查询";

    /**
     * 执行只读查询。实现必须保证：只读、参数化、有行数与超时上限。
     * 实现层在触达 JDBC 前必须先通过 {@link ReadOnlySqlGuard}，并在只读连接上执行。
     *
     * @param sql    含 ? 占位的 SQL
     * @param params 按占位顺序的参数
     * @return 每行一个有序 Map（key 为 SQL 别名）
     */
    List<Map<String, Object>> query(String sql, List<Object> params);

    /**
     * 带来源标签的执行入口：日志按 adhoc / 指标区分，避免排障时误判 SQL 归属。
     * 默认忽略来源，仅用于未覆盖该方法的实现（如测试假件）。
     *
     * @param source {@link #SOURCE_ADHOC} 或 {@link #SOURCE_METRIC}
     */
    default List<Map<String, Object>> query(String sql, List<Object> params, String source) {
        return query(sql, params);
    }

    /**
     * EXPLAIN 预演（可选实现）。用于开发/联调阶段确认是否走索引，不执行查询本身。
     */
    default List<Map<String, Object>> explain(String sql, List<Object> params) {
        return List.of();
    }

    /**
     * 服务端执行超时判定。MySQL 到点杀查询时返回 3024（max_execution_time）/1317（interrupted），
     * 驱动也可能抛 {@link SQLTimeoutException}。判定为超时后，调用方应返回"缩小范围"而不是"稍后重试"。
     */
    static boolean isExecutionTimeout(Throwable error) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable t = error; t != null && seen.add(t); t = t.getCause()) {
            if (t instanceof SQLTimeoutException) {
                return true;
            }
            if (t instanceof SQLException sql && (sql.getErrorCode() == 3024 || sql.getErrorCode() == 1317)) {
                return true;
            }
            String message = t.getMessage();
            if (message != null) {
                String lower = message.toLowerCase(Locale.ROOT);
                if (lower.contains("maximum statement execution time exceeded")
                        || lower.contains("query execution was interrupted")
                        || lower.contains("max_execution_time")) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 默认实现：基于 JdbcTemplate，叠加只读闸门、只读连接、行数上限、超时与 MySQL 执行时间 hint。
     * 三重兜底是刻意的——即使编译产物已经带 LIMIT，执行层仍要独立设限；
     * 即使上游编译器只生成 SELECT，执行层仍要 fail-closed 拒绝非只读语法。
     */
    class JdbcSqlExecutor implements SqlExecutor {

        private static final Logger log = LoggerFactory.getLogger(JdbcSqlExecutor.class);

        private final JdbcTemplate jdbcTemplate;

        public JdbcSqlExecutor(JdbcTemplate jdbcTemplate) {
            this.jdbcTemplate = jdbcTemplate;
        }

        @Override
        public List<Map<String, Object>> query(String sql, List<Object> params) {
            return query(sql, params, null);
        }

        @Override
        public List<Map<String, Object>> query(String sql, List<Object> params, String source) {
            ReadOnlySqlGuard.requireSelect(sql);
            String label = source == null || source.isBlank() ? "未知来源查询" : source;
            String finalSql = withExecutionTimeHint(sql);
            long start = System.currentTimeMillis();
            List<Map<String, Object>> rows;
            try {
                rows = queryReadOnly(finalSql, params);
            } catch (RuntimeException e) {
                long elapsed = System.currentTimeMillis() - start;
                if (isExecutionTimeout(e)) {
                    // 超时是可预期边界：保留完整 SQL/参数入口，但不打整段堆栈（原始异常照常上抛）
                    log.warn("====== {}超时（服务端 {}ms 上限） 耗时={}ms 完整SQL={} 参数={} ======",
                            label, ExecutionLimits.MAX_EXECUTION_TIME_MS, elapsed, finalSql, params);
                } else {
                    log.error("====== {}失败 耗时={}ms 完整SQL={} 参数={} ======",
                            label, elapsed, finalSql, params, e);
                }
                throw e;
            }
            log.info("====== {}完成 行数={} 耗时={}ms 完整SQL={} 参数={} ======",
                    label, rows.size(), System.currentTimeMillis() - start, finalSql, params);
            return rows;
        }

        @Override
        public List<Map<String, Object>> explain(String sql, List<Object> params) {
            ReadOnlySqlGuard.requireSelect(sql);
            return queryReadOnly("EXPLAIN " + sql, params);
        }

        /**
         * 在只读连接上执行查询：连接从池中取出后临时 setReadOnly(true)，用完后恢复原状态。
         * 连接池默认只读（application-dev.yml 的 hikari.read-only）是第一道；这里是第二道，
         * 避免个别环境覆盖池配置后执行层失去只读约束。
         */
        private List<Map<String, Object>> queryReadOnly(String sql, List<Object> params) {
            return jdbcTemplate.execute((ConnectionCallback<List<Map<String, Object>>>) connection -> {
                boolean wasReadOnly = connection.isReadOnly();
                try {
                    if (!wasReadOnly) {
                        connection.setReadOnly(true);
                    }
                    return executeSelect(connection, sql, params);
                } finally {
                    if (!wasReadOnly) {
                        try {
                            connection.setReadOnly(false);
                        } catch (SQLException e) {
                            // 不掩盖原始异常；连接归还时连接池仍会重置状态
                            log.warn("恢复连接只读状态失败：{}", e.getMessage());
                        }
                    }
                }
            });
        }

        private List<Map<String, Object>> executeSelect(Connection connection, String sql, List<Object> params)
                throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                if (jdbcTemplate.getMaxRows() > 0) {
                    statement.setMaxRows(jdbcTemplate.getMaxRows());
                }
                if (jdbcTemplate.getQueryTimeout() > 0) {
                    statement.setQueryTimeout(jdbcTemplate.getQueryTimeout());
                }
                new ArgumentPreparedStatementSetter(params.toArray()).setValues(statement);
                try (ResultSet resultSet = statement.executeQuery()) {
                    return mapRows(resultSet);
                }
            }
        }

        private static List<Map<String, Object>> mapRows(ResultSet resultSet) throws SQLException {
            ResultSetMetaData meta = resultSet.getMetaData();
            int count = meta.getColumnCount();
            List<Map<String, Object>> rows = new ArrayList<>();
            while (resultSet.next()) {
                Map<String, Object> row = new LinkedHashMap<>(count * 2);
                for (int i = 1; i <= count; i++) {
                    row.put(meta.getColumnLabel(i), resultSet.getObject(i));
                }
                rows.add(row);
            }
            return rows;
        }

        /**
         * MySQL 8 优化器 hint 紧跟 SELECT 关键字，服务端到点主动终止查询。
         * 幂等：SQL 已带 hint（如 Ad-hoc 编译器自带）时原样返回——重复 hint 是语法错误。
         */
        private String withExecutionTimeHint(String sql) {
            String trimmed = sql.trim();
            if (!trimmed.regionMatches(true, 0, "SELECT", 0, 6)) {
                return sql;
            }
            if (trimmed.regionMatches(true, 6, " /*+", 0, 4)) {
                return trimmed;
            }
            return "SELECT /*+ MAX_EXECUTION_TIME(" + ExecutionLimits.MAX_EXECUTION_TIME_MS + ") */"
                    + trimmed.substring(6);
        }
    }
}
