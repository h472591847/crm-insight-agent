package com.github.yingli.metrics.validate;

import com.github.yingli.metrics.model.MetricDefinition;
import com.github.yingli.metrics.model.MetricDefinition.Attribute;
import com.github.yingli.metrics.model.MetricDefinition.Derived;
import com.github.yingli.metrics.model.MetricDefinition.Dimension;
import com.github.yingli.metrics.model.MetricDefinition.Filter;
import com.github.yingli.metrics.model.MetricDefinition.Measure;
import com.github.yingli.shared.calc.DerivedCalculator;
import com.github.yingli.shared.catalog.ColumnTypeRegistry;
import com.github.yingli.shared.limits.QueryLimits;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.github.yingli.metrics.model.MetricDefinition.Join;

/**
 * @Description 指标配置的加载期校验。校验不通过 = 应用启动失败，把配置错误暴露在部署期，
 * 而不是等生产上问数时才暴露成一个"看起来正确的错数字"。
 * <p>
 * 规则来源：docs/ai-agent/plans/PHASE2_METRICS_LAYER_TECH_PLAN.md 第 3.3 节。核心几条：
 * 表白名单、PII 禁止输出、VARCHAR 金额必须 NUM()、别名唯一、排序列白名单、
 * 派生公式可解析、条数硬上限、基线过滤只允许字面量。
 * @Author crm-agent
 */
public final class MetricConfigValidator {

    /** 合法标识符（表名/列名/别名/编码） */
    private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");
    /** 指标编码：UPPER_SNAKE */
    private static final Pattern METRIC_CODE = Pattern.compile("^[A-Z][A-Z0-9_]*$");
    /** 基线过滤里绝不允许出现的写操作/逃逸痕迹 */
    private static final Pattern FORBIDDEN_FILTER_TEXT = Pattern.compile(
            "(;|--|/\\*|\\*/|\\binsert\\b|\\bupdate\\b|\\bdelete\\b|\\bdrop\\b|\\balter\\b"
                    + "|\\btruncate\\b|\\bcreate\\b|\\bgrant\\b|\\bcall\\b|\\bunion\\b"
                    + "|\\binto\\b|load_file|outfile|@@)",
            Pattern.CASE_INSENSITIVE);
    /** 表达式中的 token */
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    /** 限定列引用：table.column / alias.column（join 指标强制带限定符） */
    private static final Pattern QUALIFIED_REF = Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)\\.([A-Za-z_][A-Za-z0-9_]*)");
    /** join ON 子句唯一合法形态：限定列 = 限定列（单等值，无其它文本） */
    private static final Pattern JOIN_ON = Pattern.compile(
            "^([A-Za-z_][A-Za-z0-9_]*)\\.([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*"
                    + "([A-Za-z_][A-Za-z0-9_]*)\\.([A-Za-z_][A-Za-z0-9_]*)$");
    /** 允许的聚合函数 */
    private static final Set<String> AGGS = new HashSet<>(Arrays.asList("SUM", "COUNT", "MAX", "MIN", "AVG"));
    /** 从表达式识别"列引用"时忽略的函数/关键字（表达式由开发者书写，保守枚举即可） */
    private static final Set<String> NON_COLUMN_TOKENS = new HashSet<>(Arrays.asList(
            "CASE", "WHEN", "THEN", "ELSE", "END", "AND", "OR", "NOT", "NULL", "IS", "IN",
            "SUM", "COUNT", "MAX", "MIN", "AVG", "CAST", "AS", "DECIMAL", "NUM", "COALESCE",
            "NULLIF", "TRIM", "DATE_ADD", "DATE", "INTERVAL", "DAY", "TRUE", "FALSE", "DISTINCT"));

    private MetricConfigValidator() {
    }

    /**
     * 校验单个指标定义，返回全部问题（不抛异常，便于一次性报出所有配置错误）。
     */
    public static List<String> validate(MetricDefinition def, ColumnTypeRegistry types, Set<String> allowedDomains) {
        List<String> errors = new ArrayList<>();
        if (def == null) {
            errors.add("指标定义为空");
            return errors;
        }
        String tag = "指标[" + def.metric() + "]";

        // 元信息
        if (StringUtils.isBlank(def.metric()) || !METRIC_CODE.matcher(def.metric()).matches()) {
            errors.add(tag + " metric 编码必须为 UPPER_SNAKE（如 STATION_SALES）");
        }
        if (StringUtils.isBlank(def.title())) {
            errors.add(tag + " title 不可为空");
        }
        if (StringUtils.isBlank(def.domain()) || !allowedDomains.contains(def.domain())) {
            errors.add(tag + " domain 非法，允许值=" + allowedDomains);
        }
        if (StringUtils.isBlank(def.caliber())) {
            errors.add(tag + " caliber 口径正文不可为空（口径是唯一事实来源）");
        }
        if (def.grain() == null) {
            errors.add(tag + " grain 统计粒度必须显式声明（防订单头×明细重复累计）");
        }

        // 表白名单
        if (StringUtils.isBlank(def.table()) || !types.isKnownTable(def.table())) {
            errors.add(tag + " table 未登记在白名单内：" + def.table() + "，已登记=" + types.knownTables());
        }

        // 受控关联（路线 C）：表登记、别名合法且不碰名、ON 整串复核为单一限定等值。
        // 注意：MySQL 规定表一旦起别名就只能用别名限定列，故声明 join 后
        // 合法限定符只有 mainAlias 与 alias，不再包含物理表名。
        Set<String> qualifiers = new LinkedHashSet<>();
        Join join = def.join();
        if (join == null) {
            qualifiers.add(def.table());
        } else {
            if (!types.isKnownTable(join.table())) {
                errors.add(tag + " join.table 未登记在白名单内：" + join.table());
            }
            if (!IDENTIFIER.matcher(join.alias()).matches()) {
                errors.add(tag + " join.alias 非法标识符：" + join.alias());
            } else if (join.alias().equals(def.table()) || join.alias().equals(join.table())) {
                errors.add(tag + " join.alias 不得与主表/关联表同名：" + join.alias());
            } else {
                qualifiers.add(join.alias());
            }
            if (!IDENTIFIER.matcher(join.mainAlias()).matches() || join.mainAlias().equals(def.table())
                    || join.mainAlias().equals(join.table()) || join.mainAlias().equals(join.alias())) {
                errors.add(tag + " join.mainAlias 非法或与表名/关联别名冲突：" + join.mainAlias());
            } else {
                qualifiers.add(join.mainAlias());
            }
            Matcher on = JOIN_ON.matcher(join.on() == null ? "" : join.on().trim());
            if (!on.matches()) {
                errors.add(tag + " join.on 只允许「限定列 = 限定列」单一等值（禁子查询/占位符/多余文本）：" + join.on());
            } else {
                checkJoinSide(tag, "join.on 左侧", on.group(1), on.group(2), qualifiers, types, errors);
                checkJoinSide(tag, "join.on 右侧", on.group(3), on.group(4), qualifiers, types, errors);
            }
        }

        // 时间列（join 指标需带限定符，与表达式同一套规则）
        if (def.isTimeBased()) {
            String t = def.timeColumn().trim();
            boolean plain = IDENTIFIER.matcher(t).matches();
            boolean qualified = QUALIFIED_REF.matcher(t).matches();
            if (!plain && !qualified) {
                errors.add(tag + " timeColumn 必须是合法标识符或限定列：" + def.timeColumn());
            } else if (qualified && !qualifiers.contains(t.substring(0, t.indexOf('.')))) {
                errors.add(tag + " timeColumn 限定符不属于本指标可用表/别名：" + def.timeColumn());
            }
        }

        // 基线过滤只允许字面量
        for (String f : def.baselineFilters()) {
            if (StringUtils.isBlank(f)) {
                errors.add(tag + " baselineFilters 含空片段");
            } else if (FORBIDDEN_FILTER_TEXT.matcher(f).find()) {
                errors.add(tag + " baselineFilters 含被禁止的写法（多语句/注释/写操作/系统函数）：" + f);
            } else if (f.contains("?") || f.contains("#{")) {
                errors.add(tag + " baselineFilters 只允许字面量，不得含占位符：" + f);
            }
        }

        Set<String> codes = new LinkedHashSet<>();
        Set<String> aliases = new LinkedHashSet<>();

        // 维度
        for (Dimension d : def.dimensions()) {
            if (!IDENTIFIER.matcher(d.code()).matches()) {
                errors.add(tag + " 维度 code 非法：" + d.code());
            }
            if (!codes.add(d.code())) {
                errors.add(tag + " 编码重复：" + d.code());
            }
            if (!IDENTIFIER.matcher(d.alias()).matches() || !aliases.add(d.alias())) {
                errors.add(tag + " 维度 alias 非法或重复：" + d.alias());
            }
            checkColumns(tag, "维度 " + d.code(), def, d.expr(), types, errors);
        }

        // 描述列
        for (Attribute a : def.attributes()) {
            if (!IDENTIFIER.matcher(a.alias()).matches() || !aliases.add(a.alias())) {
                errors.add(tag + " 描述列 alias 非法或重复：" + a.alias());
            }
            if (StringUtils.isBlank(a.agg()) || !AGGS.contains(a.agg().toUpperCase())) {
                errors.add(tag + " 描述列 agg 必须是 " + AGGS + "，实际=" + a.agg());
            }
            checkColumns(tag, "描述列 " + a.alias(), def, a.expr(), types, errors);
        }

        // 度量
        for (Measure m : def.measures()) {
            if (!IDENTIFIER.matcher(m.code()).matches()) {
                errors.add(tag + " 度量 code 非法：" + m.code());
            }
            if (!codes.add(m.code())) {
                errors.add(tag + " 编码重复/冲突：" + m.code());
            }
            if (!IDENTIFIER.matcher(m.alias()).matches() || !aliases.add(m.alias())) {
                errors.add(tag + " 度量 alias 非法或重复：" + m.alias());
            }
            if (StringUtils.isBlank(m.agg()) || !AGGS.contains(m.agg().toUpperCase())) {
                errors.add(tag + " 度量 agg 必须是 " + AGGS + "，实际=" + m.agg());
            }
            if (m.decimalScale() < 0 || m.decimalScale() > 8) {
                errors.add(tag + " 度量 decimalScale 超出合理范围(0~8)：" + m.decimalScale());
            }
            requireNumForVarcharAmount(tag, def, m, types, errors);
            checkColumns(tag, "度量 " + m.code(), def, m.expr(), types, errors);
        }

        // 可选过滤（join 指标下列同样必须带合法限定符）
        Set<String> filterCodes = new LinkedHashSet<>();
        for (Filter f : def.optionalFilters()) {
            if (!IDENTIFIER.matcher(f.code()).matches() || !filterCodes.add(f.code())) {
                errors.add(tag + " 过滤 code 非法或重复：" + f.code());
            }
            String col = f.column() == null ? "" : f.column().trim();
            boolean plain = IDENTIFIER.matcher(col).matches();
            boolean qualified = QUALIFIED_REF.matcher(col).matches();
            if (!plain && !qualified) {
                errors.add(tag + " 过滤 column 非法（需裸列或限定列）：" + f.column());
            } else if (qualified && !qualifiers.contains(col.substring(0, col.indexOf('.')))) {
                errors.add(tag + " 过滤 column 限定符不属于本指标可用表/别名：" + f.column());
            }
            String bareName = qualified ? col.substring(col.indexOf('.') + 1) : col;
            if (types.isPii(bareName)) {
                errors.add(tag + " 过滤列是 PII，禁止作为模型可请求的过滤：" + f.column());
            }
        }

        // 排序白名单必须指向度量
        for (String s : def.sortable()) {
            boolean isMeasure = def.measures().stream().anyMatch(m -> m.code().equals(s));
            if (!isMeasure) {
                errors.add(tag + " sortable 引用了不存在的度量编码：" + s);
            }
        }
        if (StringUtils.isNotBlank(def.defaultSortBy()) && !def.sortable().contains(def.defaultSortBy())) {
            errors.add(tag + " defaultSortBy 必须在 sortable 内：" + def.defaultSortBy());
        }

        // 派生指标
        Set<String> operands = new LinkedHashSet<>();
        def.measures().forEach(m -> operands.add(m.code()));
        for (Derived d : def.derived()) {
            if (!IDENTIFIER.matcher(d.code()).matches() || !codes.add(d.code())) {
                errors.add(tag + " 派生指标 code 非法或重复：" + d.code());
            }
            String err = DerivedCalculator.checkFormula(d.formula(), operands);
            if (err != null) {
                errors.add(tag + " 派生指标 " + d.code() + " 公式非法：" + err);
            } else {
                operands.add(d.code());
            }
            if (d.scale() < 0 || d.scale() > 8) {
                errors.add(tag + " 派生指标 scale 超出合理范围(0~8)：" + d.scale());
            }
        }

        // 条数上限
        if (def.limitDefault() <= 0 || def.limitMax() <= 0) {
            errors.add(tag + " limitDefault/limitMax 必须为正数");
        } else if (def.limitDefault() > def.limitMax()) {
            errors.add(tag + " limitDefault 不能大于 limitMax");
        } else if (def.limitMax() > QueryLimits.HARD_MAX_ROWS) {
            errors.add(tag + " limitMax 超过全局硬上限 " + QueryLimits.HARD_MAX_ROWS);
        }
        if (StringUtils.isNotBlank(def.nullDimensionLabel()) && def.dimensions().isEmpty()) {
            errors.add(tag + " 声明了 nullDimensionLabel 却没有任何维度，配置可疑");
        }
        // 指标级缺省窗口：0=沿用全局默认；非 0 必须在 (0, 上限] 且只对有时间的指标有意义
        if (def.defaultWindowMonths() < 0 || def.defaultWindowMonths() > QueryLimits.MAX_DEFAULT_WINDOW_MONTHS) {
            errors.add(tag + " defaultWindowMonths 必须在 0~" + QueryLimits.MAX_DEFAULT_WINDOW_MONTHS
                    + " 之间（0=沿用看板默认）：" + def.defaultWindowMonths());
        }
        if (def.defaultWindowMonths() > 0 && !def.isTimeBased()) {
            errors.add(tag + " 声明了 defaultWindowMonths 但无 timeColumn（累计口径），配置可疑");
        }
        // 指标级时间跨度上限：0=沿用全局上限；比全局还大的取值会被静默夹小，属无效配置
        if (def.maxRangeDays() < 0 || def.maxRangeDays() > QueryLimits.MAX_RANGE_DAYS) {
            errors.add(tag + " maxRangeDays 必须在 0~" + QueryLimits.MAX_RANGE_DAYS
                    + " 之间（0=沿用全局上限）：" + def.maxRangeDays());
        }
        if (def.maxRangeDays() > 0 && !def.isTimeBased()) {
            errors.add(tag + " 声明了 maxRangeDays 但无 timeColumn（累计口径），配置可疑");
        }
        return errors;
    }

    /**
     * 跨指标校验：指标编码全局唯一。
     */
    public static List<String> validateUniqueness(List<MetricDefinition> defs) {
        List<String> errors = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (MetricDefinition d : defs) {
            if (!seen.add(d.metric())) {
                errors.add("指标编码重复：" + d.metric());
            }
        }
        return errors;
    }

    /**
     * VARCHAR 金额/数量列做 SUM/AVG 时必须包 NUM()。
     * 只对"裸列/限定列引用"和 NUM(x) 两种形态精确判定，避免误伤 CASE WHEN 表达式；
     * 限定列按 qualifier 对应表查登记表（join 指标的金额可能在任一側）。
     */
    private static void requireNumForVarcharAmount(String tag, MetricDefinition def, Measure m,
                                                   ColumnTypeRegistry types, List<String> errors) {
        String agg = m.agg() == null ? "" : m.agg().toUpperCase();
        boolean summable = "SUM".equals(agg) || "AVG".equals(agg);
        String expr = m.expr() == null ? "" : m.expr().trim();

        // NUM(限定列或裸列)：登记表查找
        Matcher num = NUM_EXPR_EXT.matcher(expr);
        if (num.matches()) {
            String col = num.group(2);
            if (summable && !types.isVarcharNumeric(resolveTable(def, num.group(1), col), col)) {
                errors.add(tag + " 度量 " + m.code() + " 对未登记为 VARCHAR 数值的列使用了 NUM()，请核对登记表：" + col);
            }
            return;
        }
        // 裸列或 alias.column 直接聚合
        String bare = null;
        String table = def.table();
        if (IDENTIFIER.matcher(expr).matches()) {
            bare = expr;
        } else if (QUALIFIED_REF.matcher(expr).matches()) {
            int dot = expr.indexOf('.');
            table = expr.substring(0, dot);
            bare = expr.substring(dot + 1);
        }
        if (bare != null && summable && types.isVarcharNumeric(table, bare)) {
            errors.add(tag + " 度量 " + m.code() + " 直接聚合 VARCHAR 数值列 " + expr
                    + "，必须写成 NUM(" + expr + ")，否则 MySQL 隐式转换会静默截断脏值算出错误数字");
        }
    }

    /** NUM(...) 扩展形态：支持限定列，组 1=qualifier（可空）组 2=列名 */
    private static final Pattern NUM_EXPR_EXT = Pattern.compile(
            "^NUM\\(\\s*(?:([A-Za-z_][A-Za-z0-9_]*)\\.)?([A-Za-z_][A-Za-z0-9_]*)\\s*\\)$");

    /**
     * 限定符→登记表表名：无 join 时属主表；有 join 时 mainAlias→主表、alias→关联表，
     * 其它 qualifier 理论上已被 checkColumns 拦截，兼容回退按原词查找。
     */
    private static String resolveTable(MetricDefinition def, String qualifier, String column) {
        if (qualifier == null) {
            return def.table();
        }
        Join j = def.join();
        if (j != null && qualifier.equals(j.mainAlias())) {
            return def.table();
        }
        if (j != null && qualifier.equals(j.alias())) {
            return j.table();
        }
        return qualifier;
    }

    /**
     * 表达式内引用的列：不得是 PII，也不得命中指标级禁止列。
     * join 指标下的引用识别升级为「限定列优先」：先抓 alias.column / table.column，
     * 剩余裸 token 再按旧规则处理；限定符不在本指标可用表/别名集内 = 配置错误（防注入陌生表）。
     * PII 三态裁决（2026-10-29 年龄分桶/活跃计数口径）：
     * 1. 表达式就是裸列/单一限定列（原值输出）→ PII 一律拒绝，即使在计算白名单内；
     * 2. 复杂表达式（CASE/聚合函数等）内引用白名单 PII 列 → 允许（只输出计算结果）；
     * 3. 其余 PII 引用 → 拒绝。
     */
    private static void checkColumns(String tag, String what, MetricDefinition def, String expr,
                                     ColumnTypeRegistry types, List<String> errors) {
        if (StringUtils.isBlank(expr) || "*".equals(expr.trim())) {
            return;
        }
        Set<String> qualifiers = new LinkedHashSet<>();
        if (def.join() == null) {
            qualifiers.add(def.table());
        } else {
            // 与 validate() 同源规则：MySQL 起别名后物理表名不能再作限定符
            qualifiers.add(def.join().mainAlias());
            qualifiers.add(def.join().alias());
        }
        boolean rawColumnOutput = IDENTIFIER.matcher(expr.trim()).matches()
                || QUALIFIED_REF.matcher(expr.trim()).matches();

        // 1) 限定列引用：校验限定符 + 列名 PII
        String remaining = expr;
        Matcher q = QUALIFIED_REF.matcher(expr);
        while (q.find()) {
            String qualifier = q.group(1);
            String column = q.group(2);
            if (!qualifiers.contains(qualifier)) {
                errors.add(tag + " " + what + " 引用了未知限定符 " + qualifier + "（可用=" + qualifiers + "）：" + expr);
                continue;
            }
            if (types.isPii(column)) {
                if (rawColumnOutput || !types.isPiiComputeAllowed(column)) {
                    errors.add(tag + " " + what + " 引用了 PII 列，禁止输出：" + qualifier + "." + column
                            + (rawColumnOutput ? "（PII 列只允许在计算表达式内引用，不可原值输出）" : ""));
                }
            }
        }
        if (expr.contains(".")) {
            // 把已检过的限定列从文本中挖掉，避免裸 token 扫描重复报同一列
            remaining = QUALIFIED_REF.matcher(expr).replaceAll(" ");
        }
        // 2) 裸 token 扫描（旧规则）：函数/关键字忽略，PII 拒绝
        Matcher mt = TOKEN.matcher(remaining);
        while (mt.find()) {
            String token = mt.group();
            if (NON_COLUMN_TOKENS.contains(token.toUpperCase())) {
                continue;
            }
            if (types.isPii(token)) {
                if (rawColumnOutput || !types.isPiiComputeAllowed(token)) {
                    errors.add(tag + " " + what + " 引用了 PII 列，禁止输出：" + token
                            + (rawColumnOutput ? "（PII 列只允许在计算表达式内引用，不可原值输出）" : ""));
                }
            }
        }
    }

    /**
     * join ON 的单侧复核：限定符必须是本指标主表/关联别名，列不得是 PII 计算白名单之外的列。
     * ON 列只参与行匹配、永不原值输出，但 PII 白名单机制仍作为总闸：
     * 新的 PII 列想参与关联必须先加入 piiComputeAllowedColumns（逐个 code review）。
     */
    private static void checkJoinSide(String tag, String what, String qualifier, String column,
                                      Set<String> qualifiers, ColumnTypeRegistry types, List<String> errors) {
        if (!qualifiers.contains(qualifier)) {
            errors.add(tag + " " + what + " 限定符不属于主表/join 别名：" + qualifier);
        }
        if (types.isPii(column) && !types.isPiiComputeAllowed(column)) {
            errors.add(tag + " " + what + " 关联列是未授权 PII，禁止参与 join：" + qualifier + "." + column
                    + "（需先入 piiComputeAllowedColumns 白名单并逐个 review）");
        }
    }
}
