package com.itzixi.adhoc.compile;

import com.itzixi.adhoc.exception.AdhocException;
import com.itzixi.adhoc.model.SemanticRegistry;
import com.itzixi.adhoc.request.AdhocQueryRequest;
import com.itzixi.adhoc.request.FilterSpec;
import com.itzixi.adhoc.request.MeasureSpec;
import com.itzixi.adhoc.request.TimeRangeSpec;
import com.itzixi.shared.catalog.ColumnTypeRegistry;
import com.itzixi.shared.sql.GeneratedSql;
import com.itzixi.shared.limits.ExecutionLimits;
import org.apache.commons.lang3.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * @Description Ad-hoc SQL 编译器：把结构化请求编译成参数化 SQL。
 * 输入名字全部经 AdhocRequestValidator 白名单裁决，列/表达式来自 SemanticRegistry，
 * 用户值一律 ? 占位——与 MetricSqlCompiler 同一安全模型：不存在注入面。
 * 单实体与 join 场景统一给列加实体别名前缀（元数据 baselineFilters 已按别名书写）。
 * @Author crm-agent
 */
@Component
@RequiredArgsConstructor
public class AdhocSqlCompiler {

    /** 与 MetricSqlCompiler golden 口径一致的 VARCHAR 数值展开 */
    private static final String NUM_CAST = "CAST(NULLIF(TRIM(%s), '') AS DECIMAL(20, 2))";

    private final SemanticRegistry registry;
    private final ColumnTypeRegistry types;

    public GeneratedSql compile(AdhocQueryRequest request) {
        List<Object> params = new ArrayList<>();
        Chain chain = chain(request);

        // SELECT：维度 → 非派生度量 →（派生度量不进 SQL）
        List<String> selectItems = new ArrayList<>();
        List<String> groupItems = new ArrayList<>();
        List<GeneratedSql.ColumnRef> columns = new ArrayList<>();
        for (String dim : orEmpty(request.getDimensions())) {
            Resolved<SemanticRegistry.DimensionDef> r = findDimension(request, dim);
            SemanticRegistry.DimensionDef def = r.value();
            SemanticRegistry.EntityDef ent = registry.entity(r.entity());
            String alias = dim.toLowerCase(Locale.ROOT);
            // expr 型维度由开发者书写且自带别名前缀，原样使用；column 型统一加当前实体别名
            String raw = StringUtils.isNotBlank(def.expr())
                    ? def.expr()
                    : qualify(ent, def.column());
            if (StringUtils.isNotBlank(def.agg())) {
                // 描述列：MAX 包装，不进 GROUP BY
                selectItems.add(def.agg() + "(" + raw + ") AS " + quoteAlias(alias));
            } else {
                if (StringUtils.isNotBlank(def.nullLabel())) {
                    // IFNULL 只包输出列，GROUP BY 用原始表达式
                    raw = "IFNULL(" + raw + ", '" + def.nullLabel() + "')";
                }
                selectItems.add(raw + " AS " + quoteAlias(alias));
                groupItems.add(StringUtils.isNotBlank(def.expr())
                        ? def.expr() : qualify(ent, def.column()));
            }
            columns.add(new GeneratedSql.ColumnRef(dim, alias, "", false));
        }
        SemanticRegistry.EntityDef head = chain.entities().get(0);
        for (MeasureSpec m : orEmpty(request.getMeasures())) {
            if ("derived".equalsIgnoreCase(m.getType())) {
                continue; // 派生列由服务层补值
            }
            Resolved<SemanticRegistry.MeasureDef> r = findMeasure(request, m.getName());
            // join 进来的实体处于 LEFT JOIN 可空侧，COUNT(*) 会把“无匹配”计成 1，必须换 countKey
            boolean joinedEntity = !r.entity().equals(head.name());
            selectItems.add(measureSql(registry.entity(r.entity()), r.value(), m, params, joinedEntity)
                    + " AS " + quoteAlias(m.getAlias()));
            columns.add(new GeneratedSql.ColumnRef(m.getAlias(), m.getAlias(), unitOf(r.value()), false));
        }
        for (MeasureSpec m : orEmpty(request.getMeasures())) {
            if ("derived".equalsIgnoreCase(m.getType())) {
                columns.add(new GeneratedSql.ColumnRef(m.getAlias(), m.getAlias(), "DERIVED", true));
            }
        }

        // FROM + ON：关系自身条件、joinFilters 与“落在被 join 实体上的时间窗”都放 ON，
        // 保持 LEFT JOIN 语义（主表无匹配行仍保留）；参数顺序必须与最终 SQL 文本一致。
        TimeRangeSpec timeRange = request.getTimeRange();
        SemanticRegistry.EntityDef timeEntity = timeRange == null
                ? null : registry.entity(entityOf(request, timeRange.getEntity()));
        boolean timePlaced = timeEntity == null || timeEntity.name().equals(head.name());

        StringBuilder from = new StringBuilder("FROM ").append(head.table()).append(' ').append(head.alias());
        for (JoinStep step : chain.steps()) {
            List<String> onParts = new ArrayList<>();
            onParts.add(step.rel().on());
            for (String joinFilter : step.rel().joinFilters()) {
                onParts.add(wrap(joinFilter));
            }
            if (timeEntity != null && step.target().name().equals(timeEntity.name())) {
                onParts.add(timeWindowSql(step.target(), timeRange, params));
                timePlaced = true;
            }
            from.append(' ').append(step.rel().type()).append(' ')
                    .append(step.target().table()).append(' ').append(step.target().alias())
                    .append(" ON ").append(String.join(" AND ", onParts));
        }
        if (!timePlaced) {
            throw new AdhocException("INVALID_PARAM", "时间范围实体未参与本次查询：" + timeEntity.name());
        }

        // WHERE：主实体基线、用户结构化过滤、落在主实体上的时间窗
        List<String> whereParts = new ArrayList<>(chain.whereFragments());
        for (FilterSpec f : orEmpty(request.getFilters())) {
            whereParts.add(filterSql(resolveFilterEntity(request, f), f, params));
        }
        if (timeRange != null && timeEntity != null && timeEntity.name().equals(head.name())) {
            whereParts.add(timeWindowSql(head, timeRange, params));
        }

        StringBuilder sql = new StringBuilder("SELECT /*+ MAX_EXECUTION_TIME(")
                .append(ExecutionLimits.MAX_EXECUTION_TIME_MS).append(") */ ")
                .append(String.join(", ", selectItems)).append(' ')
                .append(from);
        if (!whereParts.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", whereParts));
        }
        if (!groupItems.isEmpty()) {
            sql.append(" GROUP BY ").append(String.join(", ", groupItems));
        }
        appendOrderBy(sql, request);
        // 缺省 limit 按主实体守卫约束覆盖（detail=500），显式 limit 仍受全局 maxLimit 封顶
        int limit = request.getLimit() == null
                ? registry.constraintInt(chain.entities().get(0), "defaultLimit", 100)
                : Math.min(request.getLimit(), registry.constraintInt("maxLimit", 1000));
        sql.append(" LIMIT ").append(limit);

        return new GeneratedSql(sql.toString(), params, columns, hash(sql.toString()));
    }

    // ---------------------------------------------------------------- 解析辅助

    /** 一段 join：源实体 → 目标实体 + 关系声明 */
    record JoinStep(SemanticRegistry.EntityDef from, SemanticRegistry.EntityDef target,
                    SemanticRegistry.RelationshipDef rel) {
    }

    /** 实体链 + 逐级 join 声明 + WHERE 前置片段（仅主实体基线）：全部局部变量，无请求级状态 */
    record Chain(List<SemanticRegistry.EntityDef> entities, List<JoinStep> steps,
                 List<String> whereFragments) {
    }

    /**
     * 实体链：主实体 + 逐级 LEFT JOIN；关系必须由元数据声明（无声明即无 join）。
     * WHERE 前置片段只含主实体 baselineFilters；joinFilters 与 join 侧时间窗在编译时放进 ON，
     * 否则 WHERE 会把 LEFT JOIN 退化成 INNER（主表无匹配行被过滤掉）。
     */
    Chain chain(AdhocQueryRequest request) {
        List<SemanticRegistry.EntityDef> entities = new ArrayList<>();
        List<JoinStep> steps = new ArrayList<>();
        List<String> whereFragments = new ArrayList<>();
        SemanticRegistry.EntityDef head = registry.entity(request.getEntities().get(0));
        entities.add(head);
        for (String baseline : head.baselineFilters()) {
            whereFragments.add(wrap(baseline));
        }
        for (int i = 1; i < request.getEntities().size(); i++) {
            SemanticRegistry.EntityDef from = entities.get(i - 1);
            String targetName = request.getEntities().get(i);
            SemanticRegistry.RelationshipDef rel = from.relationships().get(targetName);
            if (rel == null) {
                throw new AdhocException("ADHOC_NO_RELATIONSHIP",
                        "实体 " + from.name() + " 未声明到 " + targetName + " 的查询关系，无法 join");
            }
            SemanticRegistry.EntityDef target = registry.entity(rel.target());
            steps.add(new JoinStep(from, target, rel));
            entities.add(target);
        }
        return new Chain(List.copyOf(entities), List.copyOf(steps), List.copyOf(whereFragments));
    }

    /**
     * 实际注入的过滤片段（口径行转述用）：主实体 baselineFilters + 各级关系 joinFilters。
     * 与 {@link #chain(AdhocQueryRequest)} 同一来源，但不暴露包内可见的 {@code Chain} 结构。
     */
    public List<String> injectedFilters(AdhocQueryRequest request) {
        Chain c = chain(request);
        List<String> fragments = new ArrayList<>(c.entities().get(0).baselineFilters());
        for (int i = 1; i < c.entities().size(); i++) {
            fragments.addAll(c.entities().get(i - 1).relationships().get(c.entities().get(i).name()).joinFilters());
        }
        return fragments;
    }

    record Resolved<T>(String entity, T value) {
    }

    Resolved<SemanticRegistry.DimensionDef> findDimension(AdhocQueryRequest request, String name) {
        for (String entity : request.getEntities()) {
            SemanticRegistry.DimensionDef def = registry.entity(entity).dimensions().get(name);
            if (def != null) {
                return new Resolved<>(entity, def);
            }
        }
        throw new AdhocException("INVALID_PARAM", "未声明的维度：" + name);
    }

    Resolved<SemanticRegistry.MeasureDef> findMeasure(AdhocQueryRequest request, String name) {
        for (String entity : request.getEntities()) {
            SemanticRegistry.MeasureDef def = registry.entity(entity).measures().get(name);
            if (def != null) {
                return new Resolved<>(entity, def);
            }
        }
        throw new AdhocException("INVALID_PARAM", "未声明的度量：" + name);
    }

    String entityOf(AdhocQueryRequest request, String fallback) {
        return StringUtils.isBlank(fallback) ? request.getEntities().get(0) : fallback;
    }

    private SemanticRegistry.EntityDef resolveFilterEntity(AdhocQueryRequest request, FilterSpec f) {
        return registry.entity(entityOf(request, f.getEntity()));
    }

    /** expr 型维度由开发者书写且自带别名前缀；column 型统一加当前实体别名 */
    String qualify(SemanticRegistry.EntityDef entity, String column) {
        if (column.startsWith(entity.alias() + ".")) {
            return column; // 已限定：原样
        }
        return entity.alias() + "." + column;
    }

    // ---------------------------------------------------------------- 片段生成

    /**
     * 度量 SQL；条件聚合的参数在此追加（与占位顺序一致）。
     * joinedEntity=true 时实体在 LEFT JOIN 可空侧，COUNT(*) 必须换成 COUNT(countKey)，
     * 否则主表无匹配行会被 COUNT(*) 计成 1。
     */
    String measureSql(SemanticRegistry.EntityDef entity, SemanticRegistry.MeasureDef def,
                      MeasureSpec spec, List<Object> params, boolean joinedEntity) {
        String agg = def.agg();
        String columnOrExpr = StringUtils.isNotBlank(def.expr()) ? def.expr() : null;
        String column = columnOrExpr == null
                ? (needsNum(entity, def.column())
                    ? String.format(NUM_CAST, entity.alias() + "." + def.column())
                    : entity.alias() + "." + def.column())
                : columnOrExpr;

        if ("conditional".equalsIgnoreCase(spec.getType())) {
            SemanticRegistry.ConditionPreset preset = entity.conditionPresets().get(spec.getConditionPreset());
            if (preset == null) {
                throw new AdhocException("INVALID_PARAM", "条件模板未声明：" + spec.getConditionPreset());
            }
            String cond = conditionSql(entity, preset, params);
            if ("COUNT".equals(agg)) {
                return "SUM(CASE WHEN " + cond + " THEN 1 ELSE 0 END)";
            }
            return "SUM(CASE WHEN " + cond + " THEN " + column + " ELSE 0 END)";
        }
        return switch (agg) {
            case "COUNT" -> joinedEntity && "*".equals(def.expr())
                    ? "COUNT(" + entity.alias() + "." + requireCountKey(entity) + ")"
                    : "COUNT(*)";
            case "COUNT_DISTINCT" -> "COUNT(DISTINCT " + column + ")";
            default -> agg + "(" + column + ")";
        };
    }

    private static String requireCountKey(SemanticRegistry.EntityDef entity) {
        if (StringUtils.isBlank(entity.countKey())) {
            throw new AdhocException("INVALID_PARAM",
                    "实体 " + entity.name() + " 缺少 countKey，join 场景无法安全计数");
        }
        return entity.countKey();
    }

    boolean needsNum(SemanticRegistry.EntityDef entity, String column) {
        return types.isVarcharNumeric(entity.table(), column);
    }

    /** 条件模板 → SQL 片段；带 value 的模板参数化 */
    String conditionSql(SemanticRegistry.EntityDef entity, SemanticRegistry.ConditionPreset preset,
                        List<Object> params) {
        String col = entity.alias() + "." + preset.column();
        String op = preset.op().trim().toUpperCase(Locale.ROOT);
        if (op.equals("IS NULL") || op.equals("IS NOT NULL")) {
            return col + " " + op;
        }
        params.add(preset.value());
        return col + " " + op + " ?";
    }

    String filterSql(SemanticRegistry.EntityDef entity, FilterSpec f, List<Object> params) {
        if (StringUtils.isNotBlank(f.getPreset())) {
            SemanticRegistry.ConditionPreset preset = entity.conditionPresets().get(f.getPreset());
            if (preset == null) {
                throw new AdhocException("INVALID_PARAM", "条件模板未声明：" + f.getPreset());
            }
            return "(" + conditionSql(entity, preset, params) + ")";
        }
        String col = entity.alias() + "." + f.getColumn();
        String op = f.getOp().trim().toUpperCase(Locale.ROOT);
        if (op.equals("IS NULL") || op.equals("IS NOT NULL")) {
            return "(" + col + " " + op + ")";
        }
        if (op.equals("IN") || op.equals("NOT IN")) {
            params.addAll(f.getValues());
            return "(" + col + " " + op + " (" + String.join(",", java.util.Collections.nCopies(f.getValues().size(), "?")) + "))";
        }
        params.add(f.getValue());
        return "(" + col + " " + op + " ?)";
    }

    /**
     * 时间窗口：左闭右开（endDate 含当日，即右开 +1 天），片段不加外层括号（与指标框架 golden 一致）。
     * 落在主实体上时进 WHERE；落在被 join 实体上时由 compile() 放进 ON，保持 LEFT JOIN 语义。
     */
    String timeWindowSql(SemanticRegistry.EntityDef entity, TimeRangeSpec t, List<Object> params) {
        if (StringUtils.isBlank(t.getTimeDimension())) {
            throw new AdhocException("INVALID_PARAM", "时间维度不能为空");
        }
        String real = entity.timeColumns().get(t.getTimeDimension());
        if (real == null) {
            throw new AdhocException("INVALID_PARAM", "时间维度未声明：" + t.getTimeDimension());
        }
        params.add(t.getStartDate().trim());
        params.add(t.getEndDate().trim());
        return entity.alias() + "." + real + " >= ? AND " + entity.alias() + "." + real
                + " < DATE_ADD(?, INTERVAL 1 DAY)";
    }

    void appendOrderBy(StringBuilder sql, AdhocQueryRequest request) {
        if (StringUtils.isNotBlank(request.getOrderBy())) {
            String[] parts = request.getOrderBy().trim().split("\\s+");
            sql.append(" ORDER BY ").append(quoteAlias(parts[0]));
            if (parts.length > 1) {
                sql.append(' ').append(parts[1].toLowerCase(Locale.ROOT));
            }
        }
    }

    /**
     * alias 一律反引号包裹：模型给的度量别名可能是 MySQL 保留字（如 lines），裸用会 1064。
     * 只影响 SQL 文本，列引用仍按未加引号的 alias 取名（JDBC columnLabel 就是 alias 本身）。
     */
    static String quoteAlias(String alias) {
        return "`" + alias + "`";
    }

    static String unitOf(SemanticRegistry.MeasureDef def) {
        return switch (def.agg()) {
            case "COUNT", "COUNT_DISTINCT" -> "COUNT";
            default -> def.column() != null && def.column().contains("expense") ? "CNY" : "";
        };
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static String wrap(String fragment) {
        String trimmed = fragment.trim();
        return trimmed.startsWith("(") ? trimmed : "(" + trimmed + ")";
    }

    static String hash(String sql) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] d = digest.digest(sql.replaceAll("\\s+", " ").trim().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d).substring(0, 16);
        } catch (Exception e) {
            throw new IllegalStateException("SQL 指纹计算失败", e);
        }
    }
}
