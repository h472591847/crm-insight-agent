package com.itzixi.metrics.service;

import com.itzixi.metrics.request.MetricQueryRequest;
import com.itzixi.metrics.response.ColumnMeta;
import com.itzixi.metrics.response.MetricResult;
import com.itzixi.metrics.model.MetricDefinition;
import com.itzixi.metrics.model.MetricDefinition.Derived;
import com.itzixi.metrics.model.MetricDefinition.Measure;
import com.itzixi.metrics.model.QueryPlan;
import com.itzixi.metrics.exception.MetricException;
import com.itzixi.metrics.registry.MetricRegistry;
import com.itzixi.metrics.validate.MetricQueryValidator;
import com.itzixi.metrics.compile.MetricSqlCompiler;
import com.itzixi.shared.calc.DerivedCalculator;
import com.itzixi.shared.limits.ExecutionLimits;
import com.itzixi.shared.sql.GeneratedSql;
import com.itzixi.shared.sql.SqlExecutor;
import com.itzixi.shared.sql.SqlGuardException;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @Description 指标查询运行时：注册表解析 → 请求校验 → SQL 编译 → 受限执行 → 结果装配。
 * 确定性计算（派生指标、合计行、空维度归并）全部在这里完成，模型只负责表达意图与转述结果。
 * <p>
 * 错误语义刻意区分：参数非法 / 指标未注册 / 口径未确认 / 无数据 / 执行失败，
 * 各自独立错误码 + 可直接展示给用户的中文原因，绝不返回可疑数字。
 *
 * @Author crm-agent
 */
@Service
public class MetricQueryService {

    private static final Logger log = LoggerFactory.getLogger(MetricQueryService.class);

    /** 无数据错误码（与执行失败区分，见方案 §6.3） */
    public static final String ERROR_NO_DATA = "NO_DATA";

    private final MetricRegistry metricRegistry;
    private final MetricSqlCompiler MetricSqlCompiler;
    private final SqlExecutor sqlExecutor;

    public MetricQueryService(MetricRegistry metricRegistry, MetricSqlCompiler MetricSqlCompiler, SqlExecutor sqlExecutor) {
        this.metricRegistry = metricRegistry;
        this.MetricSqlCompiler = MetricSqlCompiler;
        this.sqlExecutor = sqlExecutor;
    }

    /**
     * 执行指标查询（受限只读）。任何异常都被转换为带错误码的 {@link MetricResult}，不向模型抛出裸异常。
     *
     * @param expectedDomain 工具所属域；与指标域不一致时拒绝（防跨域绕过）
     */
    public MetricResult query(String expectedDomain, MetricQueryRequest request) {
        MetricResult result = new MetricResult();
        try {
            MetricDefinition def = resolveDefinition(expectedDomain, request, true);
            QueryPlan plan = MetricQueryValidator.normalize(def, request);
            GeneratedSql gen = MetricSqlCompiler.compile(def, plan);
            List<Map<String, Object>> rawRows = sqlExecutor.query(gen.sql(), gen.params(), SqlExecutor.SOURCE_METRIC);

            fillMeta(result, def, gen);
            result.setColumns(buildColumns(def, gen));
            result.setRows(buildRows(def, gen, rawRows));
            result.setTotalRow(buildTotalRowIfNeeded(def, plan, gen));
            result.setScope(buildScope(plan));
            if (result.getRows().isEmpty()) {
                result.setErrorCode(ERROR_NO_DATA);
                result.setMessage("该时间范围与条件下没有符合条件的数据（可能确实无数据，或所选区间无记录）。");
            }
            return result;
        } catch (MetricException e) {
            markFailed(result, e.getCode().name(), e.getMessage());
            return result;
        } catch (SqlGuardException e) {
            // 只读闸门属 shared，不认识指标域错误码；在此映射回 SQL_REJECTED，保持对外语义不变
            markFailed(result, MetricException.Code.SQL_REJECTED.name(), e.getMessage());
            return result;
        } catch (Exception e) {
            if (SqlExecutor.isExecutionTimeout(e)) {
                log.warn("指标查询超时 metric={} 原因={}", request == null ? null : request.getMetric(), e.getMessage());
                markFailed(result, MetricException.Code.QUERY_FAILED.name(),
                        "查询超过服务端 " + (ExecutionLimits.MAX_EXECUTION_TIME_MS / 1000)
                                + " 秒上限，请缩小时间范围或增加过滤条件后重试。");
                return result;
            }
            log.error("指标查询执行失败 metric={}", request == null ? null : request.getMetric(), e);
            markFailed(result, MetricException.Code.QUERY_FAILED.name(), "指标查询失败，请稍后重试或缩小范围。");
            return result;
        }
    }

    /**
     * 干跑：只编译不执行，返回 SQL/参数/指纹；includeExplain=true 时附带 EXPLAIN 摘要（不执行查询本身）。
     * 口径未确认的指标允许干跑，便于开发期先审 SQL 再定口径。
     */
    public Map<String, Object> explain(String expectedDomain, MetricQueryRequest request, boolean includeExplain) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            MetricDefinition def = resolveDefinition(expectedDomain, request, false);
            QueryPlan plan = MetricQueryValidator.normalize(def, request);
            GeneratedSql gen = MetricSqlCompiler.compile(def, plan);
            out.put("metric", def.metric());
            out.put("title", def.title());
            out.put("domain", def.domain());
            out.put("status", def.status().name());
            out.put("caliber", def.caliber());
            out.put("sql", gen.sql());
            out.put("sqlHash", gen.sqlHash());
            out.put("params", gen.params());
            out.put("columns", buildColumns(def, gen));
            if (def.withTotalRow()) {
                GeneratedSql totals = MetricSqlCompiler.compileTotals(def, plan);
                out.put("totalsSql", totals.sql());
                out.put("totalsSqlHash", totals.sqlHash());
                out.put("totalsParams", totals.params());
            }
            if (includeExplain) {
                try {
                    out.put("explain", sqlExecutor.explain(gen.sql(), gen.params()));
                } catch (Exception e) {
                    // EXPLAIN 失败不影响干跑主产出（如开发库不可达），单独说明即可
                    out.put("explainError", "EXPLAIN 预演失败：" + e.getMessage());
                }
            }
            return out;
        } catch (MetricException e) {
            out.put("errorCode", e.getCode().name());
            out.put("message", e.getMessage());
            return out;
        }
    }

    /**
     * 供适配层复用：解析并校验指标（含域归属与口径确认检查）。
     */
    public MetricDefinition resolveDefinition(String expectedDomain, MetricQueryRequest request,
                                              boolean requireConfirmed) {
        if (request == null || StringUtils.isBlank(request.getMetric())) {
            throw MetricException.invalidParam("指标编码（metric）不可为空");
        }
        MetricDefinition def = metricRegistry.require(StringUtils.upperCase(StringUtils.trim(request.getMetric())));
        MetricQueryValidator.requireDomain(def, expectedDomain);
        if (requireConfirmed && !def.isConfirmed()) {
            throw new MetricException(MetricException.Code.METRICS_NOT_CONFIRMED,
                    "指标 " + def.metric() + " 的口径尚未经业务确认，按规则拒绝执行。请先与业务负责人确认口径，"
                            + "不要基于推测数字作答。");
        }
        return def;
    }

    // ------------------------------------------------------------------
    // 结果装配
    // ------------------------------------------------------------------

    private void fillMeta(MetricResult result, MetricDefinition def, GeneratedSql gen) {
        result.setMetric(def.metric());
        result.setTitle(def.title());
        result.setCaliber(def.caliber());
        result.setSqlHash(gen.sqlHash());
    }

    private List<ColumnMeta> buildColumns(MetricDefinition def, GeneratedSql gen) {
        Set<String> measureCodes = new LinkedHashSet<>();
        def.measures().forEach(m -> measureCodes.add(m.code()));
        List<ColumnMeta> out = new ArrayList<>(gen.columns().size());
        for (GeneratedSql.ColumnRef ref : gen.columns()) {
            // 度量/派生列为 NUMERIC，维度/描述列为 TEXT；模型表述以 unit 为准
            String dataType = ref.derived() || measureCodes.contains(ref.code()) ? "NUMERIC" : "TEXT";
            out.add(new ColumnMeta(ref.code(), ref.alias(), ref.unit(), dataType, ref.derived()));
        }
        return out;
    }

    private List<List<Object>> buildRows(MetricDefinition def, GeneratedSql gen, List<Map<String, Object>> rawRows) {
        List<GeneratedSql.ColumnRef> columns = gen.columns();
        List<Integer> dimIndexes = new ArrayList<>();
        Set<String> dimCodes = new LinkedHashSet<>();
        def.dimensions().forEach(d -> dimCodes.add(d.code()));
        for (int i = 0; i < columns.size(); i++) {
            if (dimCodes.contains(columns.get(i).code())) {
                dimIndexes.add(i);
            }
        }
        List<List<Object>> out = new ArrayList<>(rawRows.size());
        for (Map<String, Object> raw : rawRows) {
            List<Object> row = new ArrayList<>(columns.size());
            Map<String, BigDecimal> numeric = new LinkedHashMap<>();
            for (GeneratedSql.ColumnRef ref : columns) {
                if (ref.derived()) {
                    row.add(null);
                    continue;
                }
                Object v = raw.get(ref.alias());
                row.add(v);
                if (isMeasureCode(def, ref.code())) {
                    numeric.put(ref.code(), toBigDecimal(v));
                }
            }
            applyDerived(def, columns, row, numeric);
            applyNullDimensionLabel(def, columns, dimIndexes, row);
            out.add(row);
        }
        return out;
    }

    /**
     * 合计行：withTotalRow 时另发一次"同 WHERE、无 GROUP BY、无 LIMIT"的全量聚合。
     * 刻意不用已返回行累加——Top N 被 LIMIT 截断时，"合计"必须代表全量而非截断部分（方案 §5.3）。
     */
    private List<Object> buildTotalRowIfNeeded(MetricDefinition def, QueryPlan plan, GeneratedSql gen) {
        if (!def.withTotalRow()) {
            return null;
        }
        GeneratedSql totalsGen = MetricSqlCompiler.compileTotals(def, plan);
        List<Map<String, Object>> totals = sqlExecutor.query(totalsGen.sql(), totalsGen.params(), SqlExecutor.SOURCE_METRIC);
        Map<String, Object> raw = totals.isEmpty() ? Map.of() : totals.get(0);

        List<Object> row = new ArrayList<>(gen.columns().size());
        Map<String, BigDecimal> numeric = new LinkedHashMap<>();
        for (GeneratedSql.ColumnRef ref : gen.columns()) {
            if (ref.derived()) {
                row.add(null);
                continue;
            }
            if (isMeasureCode(def, ref.code())) {
                Object v = raw.get(ref.alias());
                row.add(v);
                numeric.put(ref.code(), toBigDecimal(v));
            } else {
                // 合计行没有维度/描述语义，置空由展示层决定文案
                row.add(null);
            }
        }
        applyDerived(def, gen.columns(), row, numeric);
        return row;
    }

    private void applyDerived(MetricDefinition def, List<GeneratedSql.ColumnRef> columns,
                              List<Object> row, Map<String, BigDecimal> numeric) {
        if (def.derived().isEmpty()) {
            return;
        }
        Map<String, Integer> indexByCode = new LinkedHashMap<>();
        for (int i = 0; i < columns.size(); i++) {
            indexByCode.put(columns.get(i).code(), i);
        }
        // 按声明顺序求值：后定义的派生可引用先定义的派生
        for (Derived d : def.derived()) {
            BigDecimal v = DerivedCalculator.evaluate(d.formula(), numeric, d.scale());
            numeric.put(d.code(), v);
            Integer idx = indexByCode.get(d.code());
            if (idx != null) {
                row.set(idx, v);
            }
        }
    }

    /**
     * 空维度归并（业务口径 2026-10-28 确认）：维度与描述列全为空时，把展示名归并为声明的标签（如"未知门店"）。
     * 与 Phase 1 工具层行为逐值等价：编码保持为空，仅规范展示名。
     */
    private void applyNullDimensionLabel(MetricDefinition def, List<GeneratedSql.ColumnRef> columns,
                                         List<Integer> dimIndexes, List<Object> row) {
        String label = def.nullDimensionLabel();
        if (StringUtils.isBlank(label) || dimIndexes.isEmpty()) {
            return;
        }
        boolean allDimsBlank = dimIndexes.stream().allMatch(i -> StringUtils.isBlank(asText(row.get(i))));
        if (!allDimsBlank) {
            return;
        }
        Set<String> attrAliases = new LinkedHashSet<>();
        def.attributes().forEach(a -> attrAliases.add(a.alias()));
        for (int i = 0; i < columns.size(); i++) {
            if (attrAliases.contains(columns.get(i).code()) && StringUtils.isBlank(asText(row.get(i)))) {
                row.set(i, label);
                return;
            }
        }
        if (attrAliases.isEmpty()) {
            row.set(dimIndexes.get(0), label);
        }
    }

    private void markFailed(MetricResult result, String errorCode, String message) {
        result.setErrorCode(errorCode);
        result.setMessage(message);
        result.setRows(new ArrayList<>());
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private boolean isMeasureCode(MetricDefinition def, String code) {
        return def.measures().stream().anyMatch(m -> m.code().equals(code));
    }

    private BigDecimal toBigDecimal(Object v) {
        if (v == null) {
            return BigDecimal.ZERO;
        }
        if (v instanceof BigDecimal b) {
            return b;
        }
        if (v instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        try {
            return new BigDecimal(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            // 脏值不参与计算（NUM() 已在 SQL 层兜住，这里只防御非 SQL 来源的异常值）
            log.warn("派生计算遇到非数值单元格，按 0 处理：{}", v);
            return BigDecimal.ZERO;
        }
    }

    private String asText(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private String buildScope(QueryPlan plan) {
        StringBuilder sb = new StringBuilder("范围：");
        if (plan.startDate() != null) {
            sb.append("时间 ").append(plan.startDate()).append(" ~ ").append(plan.endDate()).append("（含末日）；");
        } else {
            sb.append("累计口径（不限制时间）；");
        }
        if (plan.filters().isEmpty()) {
            sb.append("全部范围");
        } else {
            plan.filters().forEach((code, value) -> sb.append(filterLabel(code)).append('=').append(value).append('；'));
        }
        return sb.toString();
    }

    private String filterLabel(String code) {
        return switch (code) {
            case "STATION" -> "门店";
            case "SKU" -> "商品";
            default -> code;
        };
    }
}
