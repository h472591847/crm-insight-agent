package com.itzixi.adhoc.validate;

import com.itzixi.adhoc.exception.AdhocException;
import com.itzixi.adhoc.model.SemanticRegistry;
import com.itzixi.adhoc.request.AdhocQueryRequest;
import com.itzixi.adhoc.request.FilterSpec;
import com.itzixi.adhoc.request.MeasureSpec;
import com.itzixi.adhoc.request.TimeRangeSpec;
import com.itzixi.shared.catalog.ColumnTypeRegistry;
import com.itzixi.shared.limits.QueryLimits;
import com.itzixi.shared.sql.MysqlReservedWords;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * @Description Ad-hoc 白名单校验：模型给的只有名字，能不能查、怎么查全由这里裁决。
 * 每条违规返回中文可读原因，模型必须如实转述，不得改口径重试绕过。
 * @Author crm-agent
 */
@Component
@RequiredArgsConstructor
public class AdhocRequestValidator {

    /** 结构化过滤允许的操作符（值参数化，白名单挡注入面） */
    static final Set<String> ALLOWED_OPS = Set.of("=", "!=", ">", "<", ">=", "<=",
            "LIKE", "IN", "NOT IN", "IS NULL", "IS NOT NULL");

    /** 手机号列：只接受精确匹配，任何模糊/前缀/包含形态一律拒绝（业务口径 2026-11-07）。 */
    private static final Set<String> PHONE_COLUMNS =
            Set.of("member_mobile", "expense_mobile", "expense_d_mobile");

    /** 手机号列允许的精确操作符（op 已归一化为大写）。 */
    private static final Set<String> PHONE_EXACT_OPS = Set.of("=", "!=", "IS NULL", "IS NOT NULL");

    private static final Pattern ALIAS = Pattern.compile("^[a-z][a-z0-9_]{0,31}$");
    private static final Set<String> MEASURE_TYPES = Set.of("simple", "conditional", "distinct", "derived");

    private final SemanticRegistry registry;
    private final ColumnTypeRegistry types;

    public void validate(AdhocQueryRequest request) {
        if (request == null) {
            throw invalid("request 为空");
        }
        List<String> entities = request.getEntities();
        if (entities == null || entities.isEmpty()) {
            throw invalid("entities 不能为空：需要至少一个实体（member|order）");
        }
        for (String entity : entities) {
            registry.entity(entity); // 未知实体抛 ADHOC_UNKNOWN_ENTITY
        }
        validateRelationships(entities);

        List<MeasureSpec> measures = request.getMeasures();
        if (measures == null || measures.isEmpty()) {
            throw invalid("measures 不能为空：至少要有一个度量");
        }
        if (measures.size() > registry.constraintInt("maxMeasures", 10)) {
            throw invalid("measures 数量超限（最多 " + registry.constraintInt("maxMeasures", 10) + "）");
        }

        validateDimensions(request);
        Set<String> declaredAliases = validateMeasures(request);
        validateMeasureGrain(request);
        validateFilters(request);
        validateTimeRange(request, entities);
        validateOrderByAndLimit(request, declaredAliases);
        validateQueryGuard(request);
    }

    /**
     * 关系可达性：entities 逐级（i-1 → i）都必须由元数据声明关系，方向由元数据决定，
     * 不存在隐式 join。requires 维度已在 validateDimensions 覆盖（Task 2）。
     * 实体重复出现（如 [member, order, member]）直接拒绝：语义上无意义且会封死
     * 无限交替链（member→order→member→order→…）这类环形请求。
     */
    private void validateRelationships(List<String> entities) {
        Set<String> seen = new HashSet<>();
        for (String entity : entities) {
            if (!seen.add(entity)) {
                throw invalid("实体重复出现：" + entity + "（同一实体在 entities 中只能出现一次）");
            }
        }
        for (int i = 1; i < entities.size(); i++) {
            SemanticRegistry.EntityDef from = registry.entity(entities.get(i - 1));
            String target = entities.get(i);
            if (!from.relationships().containsKey(target)) {
                throw new AdhocException("ADHOC_NO_RELATIONSHIP",
                        "实体 " + from.name() + " 未声明到 " + target + " 的查询关系，无法 join");
            }
        }
    }

    /** 返回名字所属实体；未声明抛 INVALID_PARAM。 */
    public String resolveEntity(AdhocQueryRequest request, String name) {
        for (String entity : request.getEntities()) {
            SemanticRegistry.EntityDef def = registry.entity(entity);
            if (def.dimensions().containsKey(name) || def.measures().containsKey(name)) {
                return entity;
            }
        }
        throw invalid("未在任何参与实体中声明：" + name);
    }

    // ---------------------------------------------------------------- 维度

    private void validateDimensions(AdhocQueryRequest request) {
        List<String> dims = request.getDimensions();
        if (dims == null) {
            return;
        }
        if (dims.size() > registry.constraintInt("maxDimensions", 5)) {
            throw invalid("dimensions 数量超限（最多 " + registry.constraintInt("maxDimensions", 5) + "）");
        }
        for (String dim : dims) {
            SemanticRegistry.DimensionDef def = findDimension(request, dim);
            // requires：跨实体维度必须 join 对应实体（如 order 请求里的 ORDER_GENDER）
            if (StringUtils.isNotBlank(def.requires()) && !request.getEntities().contains(def.requires())) {
                throw invalid("维度 " + dim + " 需要实体 " + def.requires()
                        + "，请在 entities 中加入并确认元数据声明了关系");
            }
            // 描述列（agg=MAX）不做 PII 判定之外的限制；普通维度禁止 PII 原值输出
            String column = def.column();
            if (StringUtils.isNotBlank(column) && types.isPii(column) && StringUtils.isBlank(def.agg())) {
                throw invalid("维度 " + dim + " 属个人信息列，禁止原值输出：" + column);
            }
        }
    }

    private SemanticRegistry.DimensionDef findDimension(AdhocQueryRequest request, String dim) {
        for (String entity : request.getEntities()) {
            SemanticRegistry.DimensionDef def = registry.entity(entity).dimensions().get(dim);
            if (def != null) {
                return def;
            }
        }
        throw invalid("未声明的维度：" + dim + "，可用：" + availableDimensions(request));
    }

    private Set<String> availableDimensions(AdhocQueryRequest request) {
        Set<String> out = new HashSet<>();
        for (String entity : request.getEntities()) {
            out.addAll(registry.entity(entity).dimensions().keySet());
        }
        return out;
    }

    // ---------------------------------------------------------------- 度量

    private Set<String> validateMeasures(AdhocQueryRequest request) {
        Set<String> aliases = new HashSet<>();
        Set<String> outputs = new java.util.LinkedHashSet<>(); // derived 可引用的先行 alias（按声明顺序）
        for (MeasureSpec m : request.getMeasures()) {
            String type = m.getType() == null ? "" : m.getType().toLowerCase(Locale.ROOT);
            if (!MEASURE_TYPES.contains(type)) {
                throw invalid("不支持的度量类型：" + m.getType() + "（simple|conditional|distinct|derived）");
            }
            String alias = m.getAlias();
            if (alias == null || !ALIAS.matcher(alias).matches()) {
                throw invalid("度量 alias 非法（须匹配 ^[a-z][a-z0-9_]{0,31}$）：" + alias);
            }
            if (MysqlReservedWords.isReserved(alias)) {
                throw invalid("度量 alias 不能使用 MySQL 保留字：" + alias
                        + "，请改用非保留字别名（如 line_cnt / cnt）");
            }
            if (!aliases.add(alias)) {
                throw invalid("度量 alias 重复：" + alias);
            }

            if ("derived".equals(type)) {
                String err = com.itzixi.shared.calc.DerivedCalculator.checkFormula(m.getFormula(), outputs);
                if (err != null) {
                    throw invalid("派生公式非法：" + err + "（只能引用本请求中先声明的度量 alias）");
                }
            } else {
                SemanticRegistry.EntityDef entity = registry.entity(resolveEntity(request, m.getName()));
                SemanticRegistry.MeasureDef def = entity.measures().get(m.getName());
                if (def == null) {
                    throw invalid("度量 " + m.getName() + " 不在实体 " + entity.name() + " 的声明中");
                }
                switch (type) {
                    case "simple" -> {
                        if ("COUNT_DISTINCT".equals(def.agg())) {
                            throw invalid("度量 " + m.getName() + " 为去重计数，type 请用 distinct");
                        }
                    }
                    case "conditional" -> {
                        if (StringUtils.isBlank(m.getConditionPreset())
                                || !entity.conditionPresets().containsKey(m.getConditionPreset())) {
                            throw invalid("条件模板未声明：" + m.getConditionPreset()
                                    + "，实体 " + entity.name() + " 可用：" + entity.conditionPresets().keySet());
                        }
                    }
                    case "distinct" -> {
                        if (!"COUNT_DISTINCT".equals(def.agg())) {
                            throw invalid("度量 " + m.getName() + " 不是去重计数度量");
                        }
                        if (types.isPii(def.column()) && !types.isPiiComputeAllowed(def.column())) {
                            throw invalid("个人信息列不允许去重计数：" + def.column());
                        }
                    }
                    default -> throw invalid("不支持的度量类型：" + type);
                }
                // PII 输出兜底：聚合列本身是 PII 且不在计算允许清单（distinct 分支已判，simple 分支补判）
                if (StringUtils.isNotBlank(def.column()) && types.isPii(def.column())
                        && !types.isPiiComputeAllowed(def.column())) {
                    throw invalid("度量 " + m.getName() + " 引用了禁止计算的个人信息列：" + def.column());
                }
            }
            outputs.add(alias);
        }
        return aliases;
    }

    /**
     * 粒度守卫：一次查询只允许一种度量粒度，且实体链必须保持“先一对多、后一对一”。
     * <p>
     * 行粒度 = 最后一个 to_many 关系的目标实体（全是 to_one 时为主实体）。例如：
     * member→detail 行粒度是 detail，只能选 DETAIL_*；order→member 行粒度是 order，只能选 ORDER_*。
     * 若实体链出现 to_one 后接 to_many（如 detail→member→order），行会变成复合粒度，
     * 任何单实体度量都会被另一侧乘翻，直接拒绝并要求拆成独立查询块。
     */
    private void validateMeasureGrain(AdhocQueryRequest request) {
        List<String> entities = request.getEntities();
        boolean toOneSeen = false;
        String grain = entities.get(0);
        for (int i = 1; i < entities.size(); i++) {
            SemanticRegistry.RelationshipDef rel = registry.entity(entities.get(i - 1))
                    .relationships().get(entities.get(i));
            if (rel == null) {
                return; // 关系缺失已由 validateRelationships 拒绝
            }
            if (rel.toOne()) {
                toOneSeen = true;
            } else if (toOneSeen) {
                throw invalid("实体链 " + entities + " 先一对一、再一对多，会产生复合粒度并放大指标；"
                        + "请改用安全的链顺序，或拆成独立查询块（adhocQueryBatch）");
            } else {
                grain = entities.get(i);
            }
        }
        for (MeasureSpec m : request.getMeasures()) {
            if ("derived".equalsIgnoreCase(m.getType())) {
                continue; // 派生只引用同请求 alias，继承已校验粒度
            }
            String measureEntity = measureEntity(request, m.getName());
            if (!measureEntity.equals(grain)) {
                throw invalid("一次查询只能使用同一粒度的度量：度量 " + m.getName() + " 属于 "
                        + measureEntity + " 粒度，但实体链 " + entities + " 的行粒度是 " + grain
                        + "；跨粒度请拆成独立查询块（adhocQueryBatch）");
            }
        }
    }

    private String measureEntity(AdhocQueryRequest request, String name) {
        for (String entity : request.getEntities()) {
            if (registry.entity(entity).measures().containsKey(name)) {
                return entity;
            }
        }
        throw invalid("度量 " + name + " 不在参与实体中声明");
    }

    // ---------------------------------------------------------------- 过滤

    private void validateFilters(AdhocQueryRequest request) {
        List<FilterSpec> filters = request.getFilters();
        if (filters == null || filters.isEmpty()) {
            return;
        }
        if (filters.size() > registry.constraintInt("maxFilters", 10)) {
            throw invalid("filters 数量超限（最多 " + registry.constraintInt("maxFilters", 10) + "）");
        }
        for (FilterSpec f : filters) {
            String entityName;
            if (StringUtils.isBlank(f.getEntity())) {
                entityName = request.getEntities().get(0);
            } else {
                entityName = f.getEntity();
                if (!request.getEntities().contains(entityName)) {
                    throw invalid("过滤条件实体未在 entities 中声明：" + entityName
                            + "，本次请求实体：" + request.getEntities());
                }
            }
            SemanticRegistry.EntityDef entity = registry.entity(entityName);
            boolean hasPreset = StringUtils.isNotBlank(f.getPreset());
            boolean hasColumn = StringUtils.isNotBlank(f.getColumn());
            if (hasPreset == hasColumn) {
                throw invalid("过滤条件必须 preset 与 column/op/value 二选一：" + f);
            }
            if (hasPreset) {
                if (!entity.conditionPresets().containsKey(f.getPreset())) {
                    throw invalid("条件模板未声明：" + f.getPreset()
                            + "，实体 " + entityName + " 可用：" + entity.conditionPresets().keySet());
                }
                continue;
            }
            if (!entity.filterables().contains(f.getColumn())) {
                boolean declaredDimension = entity.dimensions().values().stream()
                        .anyMatch(d -> f.getColumn().equals(d.column()));
                if (declaredDimension) {
                    // 已声明维度（如门店名称列）只允许输出分组，不进过滤白名单：名称列无索引，过滤会扫大表
                    throw invalid("维度 " + f.getColumn() + " 仅可输出、不可过滤（不在 filterableColumns 白名单）；"
                            + "实体 " + entityName + " 可过滤列：" + entity.filterables());
                }
                throw invalid("列不可过滤（不在 filterableColumns 白名单，且禁止个人信息原值输出）：" + f.getColumn());
            }
            String op = f.getOp() == null ? "" : f.getOp().trim().toUpperCase(Locale.ROOT);
            if (!ALLOWED_OPS.contains(op)) {
                throw invalid("不支持的操作符：" + f.getOp() + "，允许：" + ALLOWED_OPS);
            }
            if (PHONE_COLUMNS.contains(f.getColumn()) && !PHONE_EXACT_OPS.contains(op)) {
                throw invalid("手机号列 " + f.getColumn() + " 仅支持精确匹配（= / != / IS NULL / IS NOT NULL），"
                        + "不支持 " + f.getOp() + " 等模糊/前缀/包含匹配；请提供完整手机号后等值查询");
            }
            boolean setOperator = op.equals("IN") || op.equals("NOT IN");
            if (setOperator) {
                if (StringUtils.isNotBlank(f.getValue())) {
                    throw invalid("IN/NOT IN 过滤的 value 必须为空，多值请放入 values：" + f.getColumn());
                }
                if (f.getValues() == null || f.getValues().isEmpty()) {
                    throw invalid("IN/NOT IN 过滤的 values 必须非空：" + f.getColumn());
                }
                if (f.getValues().stream().distinct().count() > FilterSpec.MAX_IN_VALUES) {
                    throw invalid("IN/NOT IN 的 values 去重后最多 " + FilterSpec.MAX_IN_VALUES + " 个：" + f.getColumn());
                }
            } else {
                if (f.getValues() != null && !f.getValues().isEmpty()) {
                    throw invalid("仅 IN/NOT IN 支持 values；其他操作符请使用单值 value：" + f.getColumn());
                }
                boolean valueless = op.equals("IS NULL") || op.equals("IS NOT NULL");
                if (!valueless && StringUtils.isBlank(f.getValue())) {
                    throw invalid("过滤值不能为空：" + f.getColumn() + " " + op);
                }
            }
        }
    }

    // ---------------------------------------------------------------- 时间

    private void validateTimeRange(AdhocQueryRequest request, List<String> entities) {
        TimeRangeSpec t = request.getTimeRange();
        if (t == null) {
            return;
        }
        String entityName;
        if (StringUtils.isBlank(t.getEntity())) {
            entityName = entities.get(0);
        } else {
            entityName = t.getEntity();
            if (!entities.contains(entityName)) {
                throw invalid("时间范围实体未在 entities 中声明：" + entityName
                        + "，本次请求实体：" + entities);
            }
        }
        SemanticRegistry.EntityDef entity = registry.entity(entityName);
        if (!entity.timeColumns().containsKey(t.getTimeDimension())) {
            throw invalid("时间维度未声明：" + t.getTimeDimension()
                    + "，实体 " + entityName + " 可用：" + entity.timeColumns().keySet());
        }
        LocalDate start = parseDate(t.getStartDate(), "startDate");
        LocalDate end = parseDate(t.getEndDate(), "endDate");
        if (end.isBefore(start)) {
            throw invalid("endDate 不能早于 startDate：" + t.getStartDate() + " ~ " + t.getEndDate());
        }
        long days = ChronoUnit.DAYS.between(start, end) + 1;
        // 实体级上限优先（明细表 92 天，业务授权 2026-11-03），未配置回退全局
        int maxDays = registry.constraintInt(entity, "maxTimeRangeDays", QueryLimits.MAX_RANGE_DAYS);
        if (days > maxDays) {
            throw invalid("时间跨度超限：" + days + " 天 > 上限 " + maxDays + " 天");
        }
    }

    /**
     * 实体级查询守卫（链上任意实体都生效）：requiredAnyOf 至少满足其一——
     * "timeRange" 关键字要求时间窗落在这个受守卫实体上；列名要求过滤条件（结构化列或 preset 列）
     * 命中该实体该列。守卫不再只看主实体：member 主表 join detail 时，detail 仍必须带定向条件，
     * 否则 1500 万+ 明细会被无条件下拉。
     */
    private void validateQueryGuard(AdhocQueryRequest request) {
        List<String> entities = request.getEntities();
        SemanticRegistry.EntityDef head = registry.entity(entities.get(0));
        for (String entityName : entities) {
            SemanticRegistry.EntityDef entity = registry.entity(entityName);
            SemanticRegistry.QueryGuard guard = entity.queryGuard();
            if (!guard.required() || guardSatisfied(request, entity, head)) {
                continue;
            }
            String hint = entityName.equals(head.name())
                    ? "" : "；时间范围请显式指定 entity=" + entityName;
            throw invalid("实体 " + entityName + " 需要定向查询条件（" + guard.requiredAnyOf()
                    + " 至少其一：时间范围/手机号/流水号），大表不允许无守卫聚合" + hint);
        }
    }

    private boolean guardSatisfied(AdhocQueryRequest request, SemanticRegistry.EntityDef guarded,
                                   SemanticRegistry.EntityDef head) {
        TimeRangeSpec t = request.getTimeRange();
        if (t != null && guarded.name().equals(entityOf(t.getEntity(), head))) {
            return true;
        }
        for (FilterSpec f : orEmpty(request.getFilters())) {
            if (!guarded.name().equals(entityOf(f.getEntity(), head))) {
                continue;
            }
            boolean preset = StringUtils.isNotBlank(f.getPreset());
            // 定向条件必须是等值点查：LIKE/IS NULL/!= 等无法把大表扫描收敛到单会员/单订单
            if (!preset && !"=".equals(f.getOp() == null ? "" : f.getOp().trim().toUpperCase(Locale.ROOT))) {
                continue;
            }
            String col = preset
                    ? guarded.conditionPresets().get(f.getPreset()).column() : f.getColumn();
            if (guarded.queryGuard().requiredAnyOf().contains(col)) {
                return true;
            }
        }
        return false;
    }

    /** 过滤/时间条件的实体归属：显式声明优先，缺省落主实体 */
    private String entityOf(String declared, SemanticRegistry.EntityDef head) {
        return StringUtils.isBlank(declared) ? head.name() : declared;
    }

    private LocalDate parseDate(String value, String field) {
        if (StringUtils.isBlank(value)) {
            throw invalid(field + " 不能为空");
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw invalid(field + " 格式非法（须 yyyy-MM-dd）：" + value);
        }
    }

    // ---------------------------------------------------------------- 排序与行数

    private void validateOrderByAndLimit(AdhocQueryRequest request, Set<String> measureAliases) {
        String orderBy = request.getOrderBy();
        if (StringUtils.isNotBlank(orderBy)) {
            String[] parts = orderBy.trim().split("\\s+");
            String key = parts[0];
            String dir = parts.length > 1 ? parts[1].toLowerCase(Locale.ROOT) : QueryLimits.SORT_ASC;
            boolean groupedDim = request.getDimensions() != null && request.getDimensions().contains(key);
            if (!groupedDim && !measureAliases.contains(key)) {
                throw invalid("orderBy 只能引用本请求输出的维度名或度量 alias：" + key);
            }
            if (!dir.equals(QueryLimits.SORT_ASC) && !dir.equals(QueryLimits.SORT_DESC)) {
                throw invalid("排序方向只能是 asc 或 desc：" + orderBy);
            }
        }
        Integer limit = request.getLimit();
        if (limit != null) {
            int max = registry.constraintInt("maxLimit", 1000);
            if (limit < 1 || limit > max) {
                throw invalid("limit 超出允许范围 1~" + max + "：" + limit);
            }
        }
    }

    /** null 集合按空集合处理（本域私有 helper，不跨包依赖 compile 层）。 */
    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static AdhocException invalid(String message) {
        return new AdhocException("INVALID_PARAM", message);
    }
}
