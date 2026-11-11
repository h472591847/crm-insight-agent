package com.github.yingli.shared.sql;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * @ClassName ReadOnlySqlGuardTest
 * @Description 执行层只读闸门单测：非 SELECT / 写操作 / DDL / 多语句 / 文件导出 / 系统库 / 注释
 * 必须在触达 JDBC 前被 SQL_REJECTED 拒绝；正常 SELECT 与字符串字面量内的关键字不被误伤。
 * 同时验证 JdbcSqlExecutor 确实在只读连接上执行并恢复连接状态。
 * @Author crm-agent
 */
class ReadOnlySqlGuardTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT 1",
            "  select a from t where a = ?",
            "SELECT /*+ MAX_EXECUTION_TIME(25000) */ a FROM t",
            "SELECT IFNULL(a, 'delete; drop table x') FROM t",
            "SELECT `delete` FROM t"
    })
    @DisplayName("正常只读 SELECT 放行（含优化器 hint、字符串/反引号内容）")
    void acceptsReadOnlySelect(String sql) {
        assertThatCode(() -> ReadOnlySqlGuard.requireSelect(sql)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "UPDATE t SET a = 1",
            "DELETE FROM t",
            "INSERT INTO t VALUES (1)",
            "REPLACE INTO t VALUES (1)",
            "ALTER TABLE t ADD COLUMN c INT",
            "DROP TABLE t",
            "TRUNCATE TABLE t",
            "CREATE TABLE t (a INT)",
            "RENAME TABLE t TO t2",
            "GRANT SELECT ON *.* TO u",
            "CALL p()",
            "WITH c AS (SELECT 1) SELECT * FROM c",
            "SELECT 1; DELETE FROM t",
            "SELECT 1;",
            "SELECT 1 -- comment",
            "SELECT 1 # comment",
            "SELECT /*!50000 1 */",
            "SELECT * FROM t INTO OUTFILE '/tmp/x'",
            "SELECT * FROM t INTO DUMPFILE '/tmp/x'",
            "SELECT LOAD_FILE('/etc/passwd')",
            "SELECT SLEEP(10)",
            "SELECT @@version",
            "SELECT * FROM mysql.user",
            "SELECT * FROM information_schema.tables",
            "SELECT * FROM performance_schema.threads",
            "SELECT 1 UNION SELECT 2",
            "SELECT * FROM t FOR UPDATE",
            "SELECT * FROM t LOCK IN SHARE MODE",
            "SELECT GET_LOCK('x', 1)",
            "SELECT 'abc FROM t"
    })
    @DisplayName("非只读语法一律 SQL_REJECTED")
    void rejectsNonReadOnlySql(String sql) {
        assertThatThrownBy(() -> ReadOnlySqlGuard.requireSelect(sql))
                .isInstanceOf(SqlGuardException.class);
    }

    @Test
    @DisplayName("JdbcSqlExecutor：写 SQL 在触达 JdbcTemplate 前被拒绝")
    void executorRejectsWriteBeforeJdbc() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        SqlExecutor executor = new SqlExecutor.JdbcSqlExecutor(jdbc);

        assertThatThrownBy(() -> executor.query("DELETE FROM t", List.of()))
                .isInstanceOf(SqlGuardException.class);
        verifyNoInteractions(jdbc);
    }

    @Test
    @DisplayName("JdbcSqlExecutor：EXPLAIN 同样只能预演 SELECT")
    void executorExplainRejectsWriteBeforeJdbc() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        SqlExecutor executor = new SqlExecutor.JdbcSqlExecutor(jdbc);

        assertThatThrownBy(() -> executor.explain("ALTER TABLE t ADD COLUMN c INT", List.of()))
                .isInstanceOf(SqlGuardException.class);
        verifyNoInteractions(jdbc);
    }

    @Test
    @DisplayName("JdbcSqlExecutor：查询在只读连接上执行并在结束后恢复连接状态")
    void queryUsesReadOnlyConnection() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        ResultSetMetaData meta = mock(ResultSetMetaData.class);

        when(jdbc.execute(any(ConnectionCallback.class))).thenAnswer(invocation -> {
            ConnectionCallback<?> callback = invocation.getArgument(0);
            return callback.doInConnection(connection);
        });
        when(connection.isReadOnly()).thenReturn(false);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.getMetaData()).thenReturn(meta);
        when(meta.getColumnCount()).thenReturn(1);
        when(meta.getColumnLabel(1)).thenReturn("c");
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getObject(1)).thenReturn(1);

        List<Map<String, Object>> rows = new SqlExecutor.JdbcSqlExecutor(jdbc).query("SELECT 1", List.of());

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("c", 1);
        verify(connection).setReadOnly(true);
        verify(connection).setReadOnly(false);
        verify(statement).executeQuery();
    }
}
