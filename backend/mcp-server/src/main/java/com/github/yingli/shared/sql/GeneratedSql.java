package com.github.yingli.shared.sql;

import java.util.Collections;
import java.util.List;

/**
 * @Description 编译产物：SQL 文本 + 参数 + 输出列顺序 + 指纹。
 * 参数一律以 {@code ?} 占位传给 JDBC，表达式中的列名只来自指标配置，因此不存在注入面。
 * <p>
 * {@link #sqlHash()} 会随结果返回并写入审计日志：任何数字都能反查到是哪条 SQL 算出来的，
 * 这是"可复现"的基础，也是排查口径争议的唯一凭据。
 *
 * @param sql     待执行 SQL（含 ? 占位）
 * @param params  按占位顺序排列的参数
 * @param columns 输出列（含派生列，派生列在 SQL 中不存在，由服务层补值）
 * @param sqlHash SQL 指纹（规范化空白后的 SHA-256 前 16 位）
 * @Author crm-agent
 */
public record GeneratedSql(String sql,
                           List<Object> params,
                           List<ColumnRef> columns,
                           String sqlHash) {

    public GeneratedSql {
        params = Collections.unmodifiableList(params);
        columns = Collections.unmodifiableList(columns);
    }

    /**
     * 输出列引用。
     *
     * @param code    指标内编码（维度/描述/度量/派生），作为结果 Map 的 key
     * @param alias   SQL 中的列别名；派生列为 null
     * @param unit    单位（CNY/COUNT/PCS/PERCENT…），供模型正确表述
     * @param derived 是否派生列（Java 计算，不在 SQL 中）
     */
    public record ColumnRef(String code, String alias, String unit, boolean derived) {
    }
}
