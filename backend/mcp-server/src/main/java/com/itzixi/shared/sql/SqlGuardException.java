package com.itzixi.shared.sql;

/**
 * @Description 执行层只读闸门拒绝 SQL 时抛出。放在 shared 而非任何业务域，
 * 保证 shared.sql 不反向依赖 metrics/adhoc；各域 service 自行映射为域内错误码。
 * @Author crm-agent
 */
public class SqlGuardException extends RuntimeException {

    public SqlGuardException(String message) {
        super(message);
    }
}
