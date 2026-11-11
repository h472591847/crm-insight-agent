package com.github.yingli.metrics.tooling;

import com.github.yingli.metrics.request.MetricDraftRequest;
import com.github.yingli.metrics.request.MetricDraftRequest.AttributeSpec;
import com.github.yingli.metrics.request.MetricDraftRequest.DerivedSpec;
import com.github.yingli.metrics.request.MetricDraftRequest.DimensionSpec;
import com.github.yingli.metrics.request.MetricDraftRequest.FilterSpec;
import com.github.yingli.metrics.request.MetricDraftRequest.JoinSpec;
import com.github.yingli.metrics.request.MetricDraftRequest.MeasureSpec;
import com.github.yingli.shared.catalog.ColumnTypeRegistry;
import com.github.yingli.shared.limits.QueryLimits;
import com.github.yingli.shared.sql.GeneratedSql;
import com.github.yingli.metrics.compile.MetricSqlCompiler;
import com.github.yingli.metrics.exception.MetricException;
import com.github.yingli.metrics.registry.MetricRegistry;
import com.github.yingli.metrics.validate.MetricConfigValidator;
import com.github.yingli.metrics.model.MetricDefinition;
import com.github.yingli.metrics.model.MetricDefinition.Attribute;
import com.github.yingli.metrics.model.MetricDefinition.Derived;
import com.github.yingli.metrics.model.MetricDefinition.Dimension;
import com.github.yingli.metrics.model.MetricDefinition.Filter;
import com.github.yingli.metrics.model.MetricDefinition.Grain;
import com.github.yingli.metrics.model.MetricDefinition.Join;
import com.github.yingli.metrics.model.MetricDefinition.Measure;
import com.github.yingli.metrics.model.MetricDefinition.Status;
import com.github.yingli.metrics.model.QueryPlan;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * @Description 指标脚手架（draftMetric 的底层）：把结构化入参<b>确定性地</b>组装成
 * {@link MetricDefinition}，复用运行期同一套加载期校验（{@link MetricConfigValidator}）与
 * 编译（{@link MetricSqlCompiler}），最终产出一段可直接粘贴进 {@code metrics/*.yaml} 的配置片段。
 * <p>
 * 这是技术方案 §8.1「开发期让 AI/人生成配置（不是 SQL）→ 干跑自检 → 对账 → 提交」的自动化收口，
 * 与运行时查询链路严格分离：
 * <ul>
 *   <li><b>不连数据库</b>：判定依据只有 {@link ColumnTypeRegistry}（VARCHAR 数值列 / PII / 表白名单）；</li>
 *   <li><b>不写文件、不注册指标</b>：只返回 YAML 文本给人审后自行落库提交；</li>
 *   <li><b>不臆造业务口径</b>：{@code caliber} 必填、{@code status} 缺省 UNCONFIRMED（AGENTS 第五节）。</li>
 * </ul>
 * 脚手架替人"做对"的部分：VARCHAR 金额命中登记表时自动包 {@code NUM()}、按主表推断 grain、
 * join 的 ON 子句按别名组装并规避 SnakeYAML {@code on} 布尔键陷阱、框架约定（左闭右开/LIMIT/排序白名单）
 * 由编译器统一负责；任何越界写法都在 {@code validationErrors} 里原样报出，供人改入参后重试。
 *
 * @Author crm-agent
 */
@Component
public class MetricDrafter {

    private static final Logger log = LoggerFactory.getLogger(MetricDrafter.class);

    /** 可安全以 plain 形式输出的标量（表名/列名/别名/编码等标识符）；其余一律加引号 */
    private static final Pattern SAFE_PLAIN = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    /**
     * 表 → 统计粒度的硬映射：与 {@link Grain} 枚举注释及 DATABASE_SCHEMA 一致，
     * 是"表决定了行粒度"的既有事实，不是业务口径猜测，故可在缺省时安全推断。
     */
    private static final Map<String, Grain> TABLE_GRAIN = Map.of(
            "expense_calendar_info", Grain.ORDER_HEAD,
            "expense_calendar_details", Grain.ORDER_DETAIL,
            "member_info", Grain.MEMBER);

    private final ColumnTypeRegistry types;
    private final MetricSqlCompiler MetricSqlCompiler;
    private final MetricRegistry registry;

    public MetricDrafter(ColumnTypeRegistry types, MetricSqlCompiler MetricSqlCompiler, MetricRegistry registry) {
        this.types = types;
        this.MetricSqlCompiler = MetricSqlCompiler;
        this.registry = registry;
    }

    /** 脚手架产出的确定性动作记录（自动 NUM 包了哪些列、拦截/提示了哪些 PII），供返回体回显。 */
    private static final class DraftNotes {
        final List<String> autoWrapped = new ArrayList<>();
        final Set<String> blockedPii = new LinkedHashSet<>();
    }

    /**
     * 生成指标草稿。任何校验错误都以中文原样放进返回的 {@code validationErrors}，不抛裸异常，
     * 便于模型/人据此修正入参后重试；即使存在错误也会尽量产出 YAML 片段供对照。
     */
    public Map<String, Object> draft(MetricDraftRequest req) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        DraftNotes notes = new DraftNotes();

        if (req == null) {
            errors.add("入参为空：至少需要提供 metric/title/domain/caliber/table/measures");
            out.put("ok", false);
            out.put("validationErrors", errors);
            return out;
        }

        MetricDefinition def;
        try {
            def = assemble(req, notes);
        } catch (IllegalArgumentException e) {
            // 结构性入参错误（如 grain 非法、join 字段缺失）在组装阶段暴露
            errors.add("组装失败：" + e.getMessage());
            out.put("ok", false);
            out.put("validationErrors", errors);
            out.put("autoWrappedColumns", notes.autoWrapped);
            out.put("blockedPiiColumns", new ArrayList<>(notes.blockedPii));
            return out;
        }

        // 复用加载期同一套安全校验：不新造规则，产出即等价于"能否启动"
        errors.addAll(MetricConfigValidator.validate(def, types, com.github.yingli.metrics.registry.MetricDomains.ALLOWED));
        // 全局唯一性：与已注册指标重名会导致启动期加载失败，这里提前点名
        if (registry.find(def.metric()).isPresent()) {
            errors.add("指标编码 " + def.metric() + " 与已注册指标重名，请换一个编码或先修订现有指标");
        }
        // 脚手架不猜 PII 表达式的内部引用，仅对"简单列引用"回显命中情况（校验器仍是权威闸）
        collectSimplePii(def, notes);

        boolean ok = errors.isEmpty();
        out.put("ok", ok);
        out.put("metric", def.metric());
        out.put("validationErrors", errors);
        out.put("autoWrappedColumns", notes.autoWrapped);
        out.put("blockedPiiColumns", new ArrayList<>(notes.blockedPii));
        out.put("yaml", toYaml(def));
        if (ok) {
            putCompileSample(out, def);
            out.put("nextSteps", List.of(
                    "1) 复核 caliber 是否为业务确认口径（脚手架缺省标 UNCONFIRMED，确认后改 status: CONFIRMED）；",
                    "2) 把 yaml 片段粘进 metrics/" + def.domain() + ".yaml（无该域文件则新建，保持同目录风格）；",
                    "3) 在 MetricsAsCodeMigrationReconciliationTest 补一条独立 oracle SQL 对账用例，用标准答案验数；",
                    "4) 用 explainMetric 干跑复核编译 SQL（可带 includeExplain=true 看执行计划），确认无误再提交。"));
        } else {
            out.put("nextSteps", List.of("按 validationErrors 修正入参后重试；产出 YAML 仅供参考。"));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 组装 MetricDefinition
    // ------------------------------------------------------------------

    private MetricDefinition assemble(MetricDraftRequest req, DraftNotes notes) {
        String table = trim(req.getTable());
        Join join = buildJoin(req.getJoin());

        List<Dimension> dimensions = new ArrayList<>();
        if (req.getDimensions() != null) {
            for (DimensionSpec d : req.getDimensions()) {
                String expr = StringUtils.isNotBlank(d.getExpr()) ? d.getExpr().trim() : trim(d.getColumn());
                dimensions.add(new Dimension(upper(d.getCode()), expr, aliasOr(d.getAlias(), d.getCode())));
            }
        }

        List<Attribute> attributes = new ArrayList<>();
        if (req.getAttributes() != null) {
            for (AttributeSpec a : req.getAttributes()) {
                String agg = StringUtils.isBlank(a.getAgg()) ? "MAX" : a.getAgg().trim().toUpperCase(Locale.ROOT);
                attributes.add(new Attribute(trim(a.getColumn()), trim(a.getAlias()), agg));
            }
        }

        List<Measure> measures = new ArrayList<>();
        if (req.getMeasures() != null) {
            for (MeasureSpec m : req.getMeasures()) {
                measures.add(buildMeasure(m, table, join, notes));
            }
        }

        List<Filter> filters = new ArrayList<>();
        if (req.getOptionalFilters() != null) {
            for (FilterSpec f : req.getOptionalFilters()) {
                filters.add(new Filter(upper(f.getCode()), trim(f.getColumn())));
            }
        }

        List<Derived> derived = new ArrayList<>();
        if (req.getDerived() != null) {
            for (DerivedSpec d : req.getDerived()) {
                derived.add(new Derived(upper(d.getCode()), trim(d.getFormula()),
                        d.getScale() == null ? 2 : d.getScale(), trim(d.getUnit())));
            }
        }

        List<String> measureCodes = new ArrayList<>();
        measures.forEach(m -> measureCodes.add(m.code()));
        List<String> sortable = (req.getSortable() == null || req.getSortable().isEmpty())
                ? measureCodes
                : upperList(req.getSortable());

        List<String> baseline = new ArrayList<>();
        if (req.getBaselineFilters() != null) {
            req.getBaselineFilters().forEach(f -> baseline.add(f == null ? null : f.trim()));
        }

        Grain grain = resolveGrain(req.getGrain(), table);

        return new MetricDefinition(
                upper(req.getMetric()),
                trim(req.getTitle()),
                lower(req.getDomain()),
                resolveStatus(req.getStatus()),
                StringUtils.trimToNull(req.getCaliber()),
                table,
                join,
                grain,
                trim(req.getTimeColumn()),
                baseline,
                dimensions,
                attributes,
                measures,
                filters,
                derived,
                sortable,
                upper(StringUtils.trimToNull(req.getDefaultSortBy())),
                QueryLimits.SORT_ASC.equalsIgnoreCase(trim(req.getDefaultSortDir()))
                        ? QueryLimits.SORT_ASC : QueryLimits.SORT_DESC,
                req.getLimitDefault() == null ? 100 : req.getLimitDefault(),
                req.getLimitMax() == null ? 500 : req.getLimitMax(),
                Boolean.TRUE.equals(req.getWithTotalRow()),
                StringUtils.trimToNull(req.getNullDimensionLabel()),
                List.of(),
                req.getDefaultWindowMonths() == null ? 0 : req.getDefaultWindowMonths(),
                req.getMaxRangeDays() == null ? 0 : req.getMaxRangeDays());
    }

    /**
     * 度量组装：命中 {@link ColumnTypeRegistry#isVarcharNumeric} 的 VARCHAR 数值列自动包 NUM()。
     * 调用方已自带 {@code NUM(x)} 或提供完整 {@code expr}（如 DISTINCT 计数）时直通，不重复包裹。
     */
    private Measure buildMeasure(MeasureSpec m, String table, Join join, DraftNotes notes) {
        String agg = upper(m.getAgg());
        String alias = aliasOr(m.getAlias(), m.getCode());
        int scale = m.getDecimalScale() == null ? 2 : m.getDecimalScale();
        boolean zeroFill = Boolean.TRUE.equals(m.getZeroFill());
        String unit = StringUtils.trimToNull(m.getUnit());

        String expr;
        if (StringUtils.isNotBlank(m.getExpr())) {
            // 完整表达式直通（复杂聚合/去重计数由调用方负责，NUM 也在其表达式内自行书写）
            expr = m.getExpr().trim();
        } else {
            String column = StringUtils.trimToNull(m.getColumn());
            if ("COUNT".equals(agg) && (column == null || "*".equals(column))) {
                expr = "*";
            } else if (column == null) {
                expr = "*";
            } else {
                boolean summable = "SUM".equals(agg) || "AVG".equals(agg);
                if (summable && isVarcharNumericColumn(table, join, column)) {
                    expr = "NUM(" + column + ")";
                    notes.autoWrapped.add(column);
                } else {
                    expr = column;
                }
            }
        }
        return new Measure(upper(m.getCode()), expr, agg, alias, unit, zeroFill, scale);
    }

    /**
     * 限定列按 qualifier→登记表表名映射判断是否为 VARCHAR 数值列（join 指标金额可能在任一側）。
     */
    private boolean isVarcharNumericColumn(String table, Join join, String column) {
        String ownerTable = table;
        String bare = column;
        int dot = column.indexOf('.');
        if (dot > 0) {
            String qualifier = column.substring(0, dot);
            bare = column.substring(dot + 1);
            if (join != null) {
                if (qualifier.equals(join.mainAlias())) {
                    ownerTable = table;
                } else if (qualifier.equals(join.alias())) {
                    ownerTable = join.table();
                } else {
                    ownerTable = qualifier;
                }
            } else {
                // 无 join 却带限定符：把限定符当作表名去查（非法表会在校验器暴露）
                ownerTable = qualifier;
            }
        }
        return types.isVarcharNumeric(ownerTable, bare);
    }

    /**
     * join 节点：ON 由 {@code mainAlias.onLeftCol = joinAlias.onRightCol} 组装；
     * 若调用方已写成带点形态则保留其限定符（幂等）。产出时 {@code on} 键一律加引号规避布尔键陷阱。
     */
    private Join buildJoin(JoinSpec js) {
        if (js == null) {
            return null;
        }
        String joinTable = trim(js.getJoinTable());
        String joinAlias = trim(js.getJoinAlias());
        String mainAlias = trim(js.getMainAlias());
        String left = qualify(js.getOnLeftCol(), mainAlias);
        String right = qualify(js.getOnRightCol(), joinAlias);
        String on = left + " = " + right;
        return new Join(joinTable, joinAlias, mainAlias, on);
    }

    /** 裸列 → 加限定符；已含点则原样保留（幂等，避免二次包裹）。 */
    private String qualify(String column, String qualifier) {
        String c = StringUtils.trimToNull(column);
        if (c == null) {
            return null;
        }
        if (c.contains(".")) {
            return c;
        }
        return qualifier + "." + c;
    }

    private Status resolveStatus(String status) {
        // 缺省/未识别一律 UNCONFIRMED：起草绝不自动"看起来权威"（AGENTS 第五节）
        if ("CONFIRMED".equalsIgnoreCase(StringUtils.trimToEmpty(status))) {
            return Status.CONFIRMED;
        }
        return Status.UNCONFIRMED;
    }

    private Grain resolveGrain(String grain, String table) {
        String g = StringUtils.trimToNull(grain);
        if (g != null) {
            try {
                return Grain.valueOf(g.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("grain 非法: " + grain + "，允许值=" + List.of(Grain.values()));
            }
        }
        // 缺省推断：表决定行粒度是既有硬事实；未知表返回 null，交由校验器报"表未登记"
        return TABLE_GRAIN.get(table);
    }

    // ------------------------------------------------------------------
    // PII 回显（仅对简单列引用；复杂表达式由校验器裁决）
    // ------------------------------------------------------------------

    private void collectSimplePii(MetricDefinition def, DraftNotes notes) {
        List<String> simpleRefs = new ArrayList<>();
        def.dimensions().forEach(d -> simpleRefs.add(d.expr()));
        def.attributes().forEach(a -> simpleRefs.add(a.expr()));
        def.optionalFilters().forEach(f -> simpleRefs.add(f.column()));
        simpleRefs.add(def.timeColumn());
        for (String ref : simpleRefs) {
            String bare = bareColumn(ref);
            if (bare != null && types.isPii(bare) && !types.isPiiComputeAllowed(bare)) {
                notes.blockedPii.add(bare);
            }
        }
    }

    private String bareColumn(String ref) {
        String r = StringUtils.trimToNull(ref);
        if (r == null || !SAFE_PLAIN.matcher(StringUtils.substringAfterLast(r, ".")).matches()
                && !SAFE_PLAIN.matcher(r).matches()) {
            return null;
        }
        // 只把"裸列/单一限定列"当作简单引用；复杂表达式（CASE/函数）不在这里判定
        if (r.contains(" ") || r.contains("(") || r.contains("'")) {
            return null;
        }
        int dot = r.indexOf('.');
        return dot >= 0 ? r.substring(dot + 1) : r;
    }

    // ------------------------------------------------------------------
    // 干跑编译（复用生产编译器，产出人眼可复核的样例 SQL）
    // ------------------------------------------------------------------

    private void putCompileSample(Map<String, Object> out, MetricDefinition def) {
        List<String> dimCodes = def.dimensions().stream().map(Dimension::code).toList();
        QueryPlan sample = new QueryPlan(def.metric(), dimCodes, Map.of(),
                "2026-01-01", "2026-01-31", Math.max(1, def.limitDefault()), null,
                QueryLimits.SORT_DESC);
        try {
            GeneratedSql gen = MetricSqlCompiler.compile(def, sample);
            out.put("compiledSqlSample", gen.sql());
            out.put("sqlHash", gen.sqlHash());
            if (def.withTotalRow()) {
                GeneratedSql totals = MetricSqlCompiler.compileTotals(def, sample);
                out.put("compiledTotalsSqlSample", totals.sql());
            }
        } catch (MetricException e) {
            // 编译样例是辅助复核，不阻断草稿；把原因如实回显即可
            out.put("compileNote", "干跑编译未通过（YAML 仍可修正后重跑）：" + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // YAML 产出（受控 emitter：安全标识符直通，其余加引号；join 的 on 键强制引号）
    // ------------------------------------------------------------------

    String toYaml(MetricDefinition def) {
        StringBuilder sb = new StringBuilder(512);
        line(sb, 0, "- metric: " + scalar(def.metric()));
        line(sb, 0, "  title: " + scalar(def.title()));
        line(sb, 0, "  domain: " + scalar(def.domain()));
        line(sb, 0, "  status: " + scalar(def.status().name()));
        line(sb, 0, "  caliber: " + scalar(def.caliber()));
        line(sb, 0, "  table: " + scalar(def.table()));
        if (def.join() != null) {
            Join j = def.join();
            line(sb, 0, "  join:");
            line(sb, 0, "    table: " + scalar(j.table()));
            line(sb, 0, "    alias: " + scalar(j.alias()));
            line(sb, 0, "    mainAlias: " + scalar(j.mainAlias()));
            // 键名必须加引号：SnakeYAML(YAML 1.1) 会把裸 on 解析成布尔 true
            line(sb, 0, "    \"on\": " + scalar(j.on()));
        }
        line(sb, 0, "  grain: " + (def.grain() == null ? "null" : scalar(def.grain().name())));
        if (StringUtils.isNotBlank(def.timeColumn())) {
            line(sb, 0, "  timeColumn: " + scalar(def.timeColumn()));
        }
        emitScalarList(sb, "baselineFilters", def.baselineFilters());
        // 维度
        if (def.dimensions().isEmpty()) {
            line(sb, 0, "  dimensions: []");
        } else {
            line(sb, 0, "  dimensions:");
            for (Dimension d : def.dimensions()) {
                line(sb, 0, "    - { code: " + scalar(d.code())
                        + ", alias: " + scalar(d.alias())
                        + ", expr: " + scalar(d.expr()) + " }");
            }
        }
        emitInlineList(sb, "attributes", def.attributes().stream()
                .map(a -> "{ expr: " + scalar(a.expr()) + ", alias: " + scalar(a.alias())
                        + ", agg: " + scalar(a.agg()) + " }").toList());
        // 度量
        if (def.measures().isEmpty()) {
            line(sb, 0, "  measures: []");
        } else {
            line(sb, 0, "  measures:");
            for (Measure m : def.measures()) {
                StringBuilder mm = new StringBuilder();
                mm.append("    - { code: ").append(scalar(m.code()))
                        .append(", expr: ").append(scalar(m.expr()))
                        .append(", agg: ").append(scalar(m.agg()))
                        .append(", alias: ").append(scalar(m.alias()));
                if (m.unit() != null) {
                    mm.append(", unit: ").append(scalar(m.unit()));
                }
                if (m.zeroFill()) {
                    mm.append(", zeroFill: true");
                }
                if (m.expr() != null && m.expr().contains("NUM(")) {
                    mm.append(", decimalScale: ").append(m.decimalScale());
                }
                mm.append(" }");
                line(sb, 0, mm.toString());
            }
        }
        emitInlineList(sb, "optionalFilters", def.optionalFilters().stream()
                .map(f -> "{ code: " + scalar(f.code()) + ", column: " + scalar(f.column()) + " }").toList());
        emitInlineList(sb, "derived", def.derived().stream()
                .map(d -> "{ code: " + scalar(d.code()) + ", formula: " + scalar(d.formula())
                        + ", scale: " + d.scale()
                        + (d.unit() == null ? "" : ", unit: " + scalar(d.unit())) + " }").toList());
        emitFlowSeq(sb, "sortable", def.sortable());
        if (StringUtils.isNotBlank(def.defaultSortBy())) {
            line(sb, 0, "  defaultSortBy: " + scalar(def.defaultSortBy()));
        }
        line(sb, 0, "  defaultSortDir: " + scalar(def.defaultSortDir()));
        line(sb, 0, "  limitDefault: " + def.limitDefault());
        line(sb, 0, "  limitMax: " + def.limitMax());
        line(sb, 0, "  withTotalRow: " + def.withTotalRow());
        if (StringUtils.isNotBlank(def.nullDimensionLabel())) {
            line(sb, 0, "  nullDimensionLabel: " + scalar(def.nullDimensionLabel()));
        }
        if (def.defaultWindowMonths() > 0) {
            line(sb, 0, "  defaultWindowMonths: " + def.defaultWindowMonths());
        }
        if (def.maxRangeDays() > 0) {
            line(sb, 0, "  maxRangeDays: " + def.maxRangeDays());
        }
        return sb.toString();
    }

    private void emitScalarList(StringBuilder sb, String key, List<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        line(sb, 0, "  " + key + ":");
        for (String v : values) {
            line(sb, 0, "    - " + scalar(v));
        }
    }

    private void emitInlineList(StringBuilder sb, String key, List<String> renderedItems) {
        if (renderedItems.isEmpty()) {
            line(sb, 0, "  " + key + ": []");
            return;
        }
        line(sb, 0, "  " + key + ":");
        for (String item : renderedItems) {
            line(sb, 0, "    - " + item);
        }
    }

    private void emitFlowSeq(StringBuilder sb, String key, List<String> values) {
        List<String> parts = new ArrayList<>();
        values.forEach(v -> parts.add(scalar(v)));
        line(sb, 0, "  " + key + ": [" + String.join(", ", parts) + "]");
    }

    private void line(StringBuilder sb, int indent, String text) {
        sb.append(text).append('\n');
    }

    /** 安全标识符原样输出，其余用双引号包裹并转义反斜杠、引号与控制字符，保证可被 SnakeYAML 再解析。 */
    private String scalar(String v) {
        if (v == null) {
            return "null";
        }
        if (SAFE_PLAIN.matcher(v).matches()) {
            return v;
        }
        StringBuilder sb = new StringBuilder(v.length() + 8);
        sb.append('"');
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private String aliasOr(String alias, String code) {
        String a = StringUtils.trimToNull(alias);
        return a != null ? a : (code == null ? null : code.toLowerCase(Locale.ROOT));
    }

    private String trim(String v) {
        return v == null ? null : v.trim();
    }

    private String upper(String v) {
        return v == null ? null : v.trim().toUpperCase(Locale.ROOT);
    }

    private String lower(String v) {
        return v == null ? null : v.trim().toLowerCase(Locale.ROOT);
    }

    private List<String> upperList(List<String> in) {
        List<String> out = new ArrayList<>();
        in.forEach(s -> out.add(s == null ? null : s.trim().toUpperCase(Locale.ROOT)));
        return out;
    }
}
