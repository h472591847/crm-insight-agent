package com.github.yingli.metrics.compile;

import com.github.yingli.metrics.exception.MetricException;
import com.github.yingli.metrics.model.MetricDefinition;
import com.github.yingli.metrics.model.MetricDefinition.Attribute;
import com.github.yingli.metrics.model.MetricDefinition.Derived;
import com.github.yingli.metrics.model.MetricDefinition.Dimension;
import com.github.yingli.metrics.model.MetricDefinition.Measure;
import com.github.yingli.metrics.model.QueryPlan;
import com.github.yingli.shared.sql.GeneratedSql;
import org.apache.commons.lang3.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @Description 把「指标定义 + 已校验的查询意图」确定性地编译成 SQL。
 * 这是本方案里唯一需要人工审核 SQL 生成逻辑的地方：写对一次，之后所有指标共享，
 * 新增指标不再产生新的手写 SQL。
 * <p>
 * 框架统一负责、配置不参与的部分：
 * 1. VARCHAR 金额安全转换 {@code NUM(x)} → {@code CAST(NULLIF(TRIM(x),'') AS DECIMAL(20,scale)})；
 * 2. 时间边界左闭右开：{@code >= ? AND < DATE_ADD(?, INTERVAL 1 DAY)}（含结束日全天）；
 * 3. 可选过滤一律 {@code column = ?} 参数化；
 * 4. 排序列只从 sortable 白名单映射出的别名取值，列名永不外部直传；
 * 5. 强制 LIMIT；
 * 6. 派生指标不进 SQL，由服务层用 {@link DerivedCalculator} 计算。
 * <p>
 * 与 Phase 1 手写 Mapper 的逐值对账由 {@code MetricsAsCodeMigrationReconciliationTest} 保证。
 *
 * @Author crm-agent
 */
public class MetricSqlCompiler {

    /** NUM(列名 或 限定列)，允许两侧空白；join 指标下金额可能是 alias.column 形态 */
    private static final Pattern NUM_CALL = Pattern.compile(
            "NUM\\(\\s*(?:([A-Za-z_][A-Za-z0-9_]*)\\.)?([A-Za-z_][A-Za-z0-9_]*)\\s*\\)");

    /**
     * 编译明细/分组查询。
     */
    public GeneratedSql compile(MetricDefinition def, QueryPlan plan) {
        List<Object> params = new ArrayList<>();
        List<GeneratedSql.ColumnRef> columns = new ArrayList<>();
        StringBuilder sb = new StringBuilder(512);

        // ---- SELECT ----
        sb.append("SELECT ");
        List<Dimension> dims = resolveDimensions(def, plan);
        for (Dimension d : dims) {
            sb.append(d.expr()).append(" AS ").append(d.alias()).append(", ");
            columns.add(new GeneratedSql.ColumnRef(d.code(), d.alias(), null, false));
        }
        for (Attribute a : def.attributes()) {
            sb.append(a.agg()).append("(").append(a.expr()).append(") AS ").append(a.alias()).append(", ");
            columns.add(new GeneratedSql.ColumnRef(a.alias(), a.alias(), null, false));
        }
        Map<String, String> measureAliasByCode = new LinkedHashMap<>();
        for (Measure m : def.measures()) {
            sb.append(measureExpression(m)).append(" AS ").append(m.alias()).append(", ");
            columns.add(new GeneratedSql.ColumnRef(m.code(), m.alias(), m.unit(), false));
            measureAliasByCode.put(m.code(), m.alias());
        }
        // 派生列：SQL 中不存在，仅登记在输出列尾部
        for (Derived d : def.derived()) {
            columns.add(new GeneratedSql.ColumnRef(d.code(), null, d.unit(), true));
        }
        trimTrailingComma(sb);

        // ---- FROM / WHERE ----
        sb.append(" FROM ").append(fromClause(def)).append(" WHERE ");
        appendBaseline(sb, def);
        if (def.isTimeBased()) {
            if (StringUtils.isNotBlank(plan.startDate())) {
                sb.append(" AND ").append(def.timeColumn()).append(" >= ?");
                params.add(plan.startDate());
                sb.append(" AND ").append(def.timeColumn()).append(" < DATE_ADD(?, INTERVAL 1 DAY)");
                params.add(plan.endDate());
            } else {
                // 时间口径指标但无区间：拒绝，避免静默全表统计
                throw MetricException.invalidParam("指标 " + def.metric() + " 是时间口径指标，必须提供时间范围");
            }
        }
        for (MetricDefinition.Filter f : def.optionalFilters()) {
            String v = plan.filters().get(f.code());
            if (StringUtils.isNotBlank(v)) {
                sb.append(" AND ").append(f.column()).append(" = ?");
                params.add(v);
            }
        }

        // ---- GROUP BY ----
        if (!dims.isEmpty()) {
            sb.append(" GROUP BY ");
            for (Dimension d : dims) {
                sb.append(d.expr()).append(", ");
            }
            trimTrailingComma(sb);
        }

        // ---- ORDER BY（只允许白名单别名）----
        String orderAlias = resolveOrderAlias(def, plan, measureAliasByCode);
        if (orderAlias != null) {
            sb.append(" ORDER BY ").append(orderAlias).append(' ').append(plan.sortDir());
        }

        // ---- LIMIT ----
        if (plan.limit() > 0) {
            sb.append(" LIMIT ").append(plan.limit());
        }

        String sql = sb.toString();
        return new GeneratedSql(sql, params, columns, hash(sql));
    }

    /**
     * 编译"全量合计"查询：同 WHERE、无 GROUP BY / ORDER BY / LIMIT。
     * 仅当分组结果被 LIMIT 截断、合计又必须代表全量时才使用，避免"Top N 合计"冒充总体。
     */
    public GeneratedSql compileTotals(MetricDefinition def, QueryPlan plan) {
        List<Object> params = new ArrayList<>();
        List<GeneratedSql.ColumnRef> columns = new ArrayList<>();
        StringBuilder sb = new StringBuilder(256);
        sb.append("SELECT ");
        for (Measure m : def.measures()) {
            sb.append(measureExpression(m)).append(" AS ").append(m.alias()).append(", ");
            columns.add(new GeneratedSql.ColumnRef(m.code(), m.alias(), m.unit(), false));
        }
        trimTrailingComma(sb);
        sb.append(" FROM ").append(fromClause(def)).append(" WHERE ");
        appendBaseline(sb, def);
        if (def.isTimeBased() && StringUtils.isNotBlank(plan.startDate())) {
            sb.append(" AND ").append(def.timeColumn()).append(" >= ?");
            params.add(plan.startDate());
            sb.append(" AND ").append(def.timeColumn()).append(" < DATE_ADD(?, INTERVAL 1 DAY)");
            params.add(plan.endDate());
        }
        for (MetricDefinition.Filter f : def.optionalFilters()) {
            String v = plan.filters().get(f.code());
            if (StringUtils.isNotBlank(v)) {
                sb.append(" AND ").append(f.column()).append(" = ?");
                params.add(v);
            }
        }
        String sql = sb.toString();
        return new GeneratedSql(sql, params, columns, hash(sql));
    }

    /**
     * 指标声明的维度按请求编码保序解析；请求为空表示全局聚合（不带 GROUP BY）。
     */
    private List<Dimension> resolveDimensions(MetricDefinition def, QueryPlan plan) {
        if (plan.dimensions() == null || plan.dimensions().isEmpty()) {
            return List.of();
        }
        Map<String, Dimension> byCode = new LinkedHashMap<>();
        def.dimensions().forEach(d -> byCode.put(d.code(), d));
        List<Dimension> out = new ArrayList<>(plan.dimensions().size());
        for (String code : plan.dimensions()) {
            Dimension d = byCode.get(code);
            if (d == null) {
                throw MetricException.invalidParam("指标 " + def.metric() + " 不支持维度 " + code
                        + "，可用维度=" + byCode.keySet());
            }
            if (!out.contains(d)) {
                out.add(d);
            }
        }
        return out;
    }

    /**
     * 排序别名解析：sortBy 缺省时取指标默认排序；派生编码不可在 SQL 排序。
     */
    private String resolveOrderAlias(MetricDefinition def, QueryPlan plan, Map<String, String> aliasByCode) {
        String code = StringUtils.isNotBlank(plan.sortBy()) ? plan.sortBy() : def.defaultSortBy();
        if (StringUtils.isBlank(code)) {
            return null;
        }
        if (!def.sortable().contains(code)) {
            throw MetricException.invalidParam("指标 " + def.metric() + " 不允许按 " + code
                    + " 排序，可排序度量=" + def.sortable());
        }
        String alias = aliasByCode.get(code);
        if (alias == null) {
            throw MetricException.invalidParam("派生指标不支持作为 SQL 排序键：" + code);
        }
        return alias;
    }

    /**
     * 度量表达式：展开 NUM() → 安全数值转换，套聚合函数，按需 COALESCE 兜零。
     */
    private String measureExpression(Measure m) {
        int scale = m.decimalScale() > 0 ? m.decimalScale() : 2;
        String expr = expandNum(m.expr(), scale);
        String agg = "COUNT".equalsIgnoreCase(m.agg()) && "*".equals(m.expr().trim())
                ? "COUNT(*)"
                : m.agg().toUpperCase() + "(" + expr + ")";
        return m.zeroFill() ? "COALESCE(" + agg + ", 0)" : agg;
    }

    /**
     * NUM(col) / NUM(alias.col) → CAST(NULLIF(TRIM(x), '') AS DECIMAL(20,scale))
     */
    private String expandNum(String expr, int scale) {
        if (expr == null || !expr.contains("NUM(")) {
            return expr;
        }
        Matcher mt = NUM_CALL.matcher(expr);
        StringBuilder out = new StringBuilder();
        while (mt.find()) {
            String qualifiedCol = mt.group(1) == null ? mt.group(2) : mt.group(1) + "." + mt.group(2);
            mt.appendReplacement(out,
                    Matcher.quoteReplacement("CAST(NULLIF(TRIM(" + qualifiedCol + "), '') AS DECIMAL(20, " + scale + "))"));
        }
        mt.appendTail(out);
        return out.toString();
    }

    /**
     * FROM 子句：单表即表名；声明了 join 时固定渲染 LEFT JOIN（驱动表=主表，
     * 无匹配的订单/会员不丢失）。ON 已在加载期由校验器整串复核为单一等值，
     * 此处原样拼接不存在自由文本进入 SQL 的路径。
     * 合计查询与分组查询共用：引用了关联表列的度量在合计 SQL 中同样需要 join。
     */
    private String fromClause(MetricDefinition def) {
        MetricDefinition.Join j = def.join();
        if (j == null) {
            return def.table();
        }
        // 主表也上别名：声明 join 后所有列引用强制带限定符，防两表同名列（del_flag）歧义
        return def.table() + " " + j.mainAlias() + " LEFT JOIN " + j.table() + " " + j.alias() + " ON " + j.on();
    }

    private void appendBaseline(StringBuilder sb, MetricDefinition def) {
        if (def.baselineFilters().isEmpty()) {
            sb.append("1 = 1");
            return;
        }
        for (String f : def.baselineFilters()) {
            sb.append('(').append(f).append(") AND ");
        }
        sb.setLength(sb.length() - " AND ".length());
    }

    private void trimTrailingComma(StringBuilder sb) {
        int idx = sb.length() - 2;
        if (idx >= 0 && sb.charAt(idx) == ',' && sb.charAt(idx + 1) == ' ') {
            sb.setLength(idx);
        }
    }

    /**
     * SQL 指纹：折叠空白后取 SHA-256 前 16 位，便于日志与结果反查且不冗长。
     */
    private String hash(String sql) {
        String normalized = sql.replaceAll("\\s+", " ").trim();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
                if (hex.length() >= 16) {
                    break;
                }
            }
            return hex.substring(0, Math.min(16, hex.length()));
        } catch (NoSuchAlgorithmException e) {
            // JDK 必然支持 SHA-256；走到这里说明环境异常，宁可失败也不要返回误导性指纹
            throw new IllegalStateException("无法计算 SQL 指纹", e);
        }
    }
}
