package com.itzixi.shared.limits;

/**
 * @Description 执行侧约束：结果集行数、超时与日期格式。两个域（metrics / adhoc）共用。
 * 数值上限沿用 Phase 1 已验证的取值，行为保持向后一致。
 * @Author crm-agent
 */
public final class ExecutionLimits {

    private ExecutionLimits() {
    }

    /** 结果集行数上限（JdbcTemplate.setMaxRows，兜住任何遗漏的 LIMIT） */
    public static final int EXEC_MAX_ROWS = 2000;

    /**
     * 查询超时（秒）。客户端兜底，应略大于 hint 上限：服务端先杀、驱动后断。
     * 2026-11-03 用户决策把上限从 25s 提升到 60s（明细宽窗口联调需要），
     * 生产上线前需按真实量级与数据库负载重估。
     */
    public static final int QUERY_TIMEOUT_SECONDS = 65;

    /** MySQL 8 optimizer hint 的执行时间上限（毫秒）：60s，客户端 65s 兜底 */
    public static final int MAX_EXECUTION_TIME_MS = 60000;

    /** 日期格式 */
    public static final String DATE_PATTERN = "yyyy-MM-dd";
}
