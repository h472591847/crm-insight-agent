package com.itzixi.adhoc.model;

import com.itzixi.adhoc.exception.AdhocException;
import com.itzixi.shared.catalog.ColumnTypeRegistry;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * @Description 语义元数据登记表（resources/semantic/metadata.yaml）。Ad-hoc 查询的表/列/度量/条件
 * 白名单唯一来源：模型引用名字，服务端解析成真实列。加载期做完整性校验（实体重名、维度缺列、
 * 表不在 ColumnTypeRegistry 白名单、标识符非法等直接启动失败），把配置错误挡在运行之前——
 * 与 MetricConfigValidator 同一思路。
 * @Author crm-agent
 */
public final class SemanticRegistry {

    /**
     * 合法标识符（表名/别名/列名/时间列名）：仅字母、数字、下划线且不以数字开头。
     * 模式对齐 MetricDrafter 的 SAFE_PLAIN（同为开发者书写标识符的加载期硬校验）。
     */
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    public record DimensionDef(String name, String column, String expr, String agg,
                               String nullLabel, String requires) {
    }

    public record MeasureDef(String name, String column, String expr, String agg) {
    }

    public record ConditionPreset(String column, String op, String value) {
    }

    /**
     * 关系声明。cardinality 是粒度守卫的事实来源：
     * to_many 表示沿该边 join 会把行数放大，to_one 表示不放大（或可能收敛）。
     * 未显式声明时按 to_many 处理（保守：宁可拒绝跨粒度度量，也不静默放大数字）。
     */
    public record RelationshipDef(String target, String type, String cardinality, String on,
                                  List<String> joinFilters) {

        public static final String TO_ONE = "to_one";
        public static final String TO_MANY = "to_many";

        public boolean toOne() {
            return TO_ONE.equals(cardinality);
        }

        public boolean toMany() {
            return TO_MANY.equals(cardinality);
        }
    }

    /**
     * 实体级查询守卫：requiredAnyOf 至少满足其一（"timeRange" 关键字 或 该列出现在实体过滤条件中），
     * constraints 为实体级约束覆盖（maxTimeRangeDays/defaultLimit/maxLimit 等），未配置的键回退全局。
     * 用途：大表（如明细表）禁止无定向条件的全表扫描——授权事实进元数据，裁决在 AdhocRequestValidator。
     */
    public record QueryGuard(List<String> requiredAnyOf, Map<String, Object> constraints) {

        public static final QueryGuard NONE = new QueryGuard(List.of(), Map.of());

        public boolean required() {
            return !requiredAnyOf.isEmpty();
        }
    }

    public record EntityDef(String name, String table, String alias, String description, String countKey,
                            List<String> baselineFilters,
                            Map<String, DimensionDef> dimensions,
                            Map<String, MeasureDef> measures,
                            Map<String, String> timeColumns,
                            Map<String, ConditionPreset> conditionPresets,
                            Set<String> filterables,
                            Set<String> indexedColumns,
                            List<String> filterGuidance,
                            Map<String, RelationshipDef> relationships,
                            QueryGuard queryGuard) {

        /** 过滤列是否落在表索引上（目录标注事实，不参与裁决）：模型组参时优先选 indexed=true 的列 */
        public boolean indexed(String column) {
            return indexedColumns.contains(column);
        }
    }

    private final Map<String, EntityDef> entities;
    private final Map<String, Object> constraints;

    private SemanticRegistry(Map<String, EntityDef> entities, Map<String, Object> constraints) {
        this.entities = Collections.unmodifiableMap(entities);
        this.constraints = Collections.unmodifiableMap(constraints);
    }

    public Set<String> entities() {
        return entities.keySet();
    }

    public EntityDef entity(String name) {
        EntityDef def = entities.get(name);
        if (def == null) {
            throw new AdhocException("ADHOC_UNKNOWN_ENTITY",
                    "未知实体：" + name + "，可用实体：" + entities.keySet());
        }
        return def;
    }

    public int constraintInt(String key, int fallback) {
        return constraintInt(null, key, fallback);
    }

    /** 实体级优先：queryGuard.constraints 覆盖全局 constraints，未配置键回退全局再回退缺省值 */
    public int constraintInt(EntityDef entity, String key, int fallback) {
        if (entity != null && entity.queryGuard().constraints().get(key) instanceof Number n) {
            return n.intValue();
        }
        Object v = constraints.get(key);
        return v instanceof Number m ? m.intValue() : fallback;
    }

    /**
     * 单参重载：跳过表白名单校验（ColumnTypeRegistry 缺席），保留给纯结构测试。
     * 生产启动路径必须走 {@link #fromYaml(Map, ColumnTypeRegistry)}。
     */
    public static SemanticRegistry fromYaml(Map<String, Object> root) {
        return fromYaml(root, null);
    }

    /**
     * 两参重载（生产入口）：除结构完整性外，加载期硬校验实体表必须在
     * {@link ColumnTypeRegistry} 表白名单内——未登记表一律拒绝启动，与指标链路同一约束。
     */
    @SuppressWarnings("unchecked")
    public static SemanticRegistry fromYaml(Map<String, Object> root, ColumnTypeRegistry types) {
        Map<String, EntityDef> entities = new LinkedHashMap<>();
        Object entitiesNode = root.get("entities");
        if (!(entitiesNode instanceof Map<?, ?> map)) {
            throw new IllegalStateException("metadata.yaml 缺少 entities 段");
        }
        Map<String, String> aliasOwners = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            String name = String.valueOf(e.getKey()).trim();
            EntityDef def = parseEntity(name, (Map<String, Object>) e.getValue(), types);
            String prev = aliasOwners.put(def.alias(), def.name());
            if (prev != null) {
                throw new IllegalStateException("实体别名跨实体重名：" + def.alias()
                        + "（实体 " + prev + " 与 " + def.name() + " 共用别名），别名必须全局唯一");
            }
            entities.put(name, def);
        }
        // 加载期硬校验：关系目标必须存在，避免运行期才暴露写错的实体名
        for (EntityDef def : entities.values()) {
            for (String target : def.relationships().keySet()) {
                if (!entities.containsKey(target)) {
                    throw new IllegalStateException("实体 " + def.name() + " 声明了未知关系目标：" + target);
                }
            }
        }
        Map<String, Object> constraints = root.get("constraints") instanceof Map<?, ?> c
                ? new LinkedHashMap<>((Map<String, Object>) c) : new LinkedHashMap<>();
        return new SemanticRegistry(entities, constraints);
    }

    private static EntityDef parseEntity(String name, Map<String, Object> node, ColumnTypeRegistry types) {
        String table = require(name, node, "table");
        String alias = require(name, node, "alias");
        checkIdentifier(name, "表名", table);
        checkIdentifier(name, "别名", alias);
        if (types != null && !types.isKnownTable(table)) {
            throw new IllegalStateException("实体 " + name + " 的表 " + table
                    + " 不在列类型登记表（metrics/column-types.yaml）白名单内，禁止加载");
        }
        String description = String.valueOf(node.getOrDefault("description", ""));
        String countKey = str(node.get("countKey"));
        if (StringUtils.isNotBlank(countKey)) {
            checkIdentifier(name, "计数键列", countKey);
        }
        List<String> baseline = stringList(node.get("baselineFilters"));

        Map<String, DimensionDef> dims = new LinkedHashMap<>();
        if (node.get("dimensions") instanceof List<?> list) {
            for (Object o : list) {
                Map<String, Object> d = (Map<String, Object>) o;
                String dimName = String.valueOf(d.get("name")).trim();
                String column = str(d.get("column"));
                String expr = str(d.get("expr"));
                if (StringUtils.isBlank(column) && StringUtils.isBlank(expr)) {
                    throw new IllegalStateException("实体 " + name + " 维度 " + dimName + " 缺 column/expr");
                }
                checkIdentifier(name, "维度 " + dimName + " 的列名", column);
                String dimAgg = str(d.get("agg"));
                if (StringUtils.isNotBlank(dimAgg)) {
                    dimAgg = dimAgg.toUpperCase(Locale.ROOT);
                }
                if (dims.put(dimName, new DimensionDef(dimName, column, expr, dimAgg,
                        str(d.get("nullLabel")), str(d.get("requires")))) != null) {
                    throw new IllegalStateException("实体 " + name + " 维度重名：" + dimName);
                }
            }
        }

        Map<String, MeasureDef> measures = new LinkedHashMap<>();
        if (node.get("measures") instanceof List<?> list) {
            for (Object o : list) {
                Map<String, Object> m = (Map<String, Object>) o;
                String mName = String.valueOf(m.get("name")).trim();
                String mColumn = str(m.get("column"));
                checkIdentifier(name, "度量 " + mName + " 的列名", mColumn);
                String mAgg = str(m.get("agg"));
                if (StringUtils.isBlank(mAgg)) {
                    throw new IllegalStateException("实体 " + name + " 度量 " + mName
                            + " 缺少必填字段 agg（聚合方式，如 SUM/COUNT/COUNT_DISTINCT）");
                }
                if (measures.put(mName, new MeasureDef(mName, mColumn,
                        str(m.get("expr")), mAgg.toUpperCase(Locale.ROOT))) != null) {
                    throw new IllegalStateException("实体 " + name + " 度量重名：" + mName);
                }
            }
        }

        // COUNT(*) 在 LEFT JOIN 的 NULL 扩展行上会把“没有匹配”计成 1；
        // 编译器需要该实体的非空键改成 COUNT(key) 才能得到真实行数。
        boolean hasCountStar = measures.values().stream()
                .anyMatch(m -> "COUNT".equals(m.agg()) && "*".equals(m.expr()));
        if (hasCountStar && StringUtils.isBlank(countKey)) {
            throw new IllegalStateException("实体 " + name + " 含 COUNT(*) 度量，"
                    + "必须声明 countKey（join 场景改为 COUNT(key) 防 NULL 扩展行计数）");
        }

        Map<String, String> timeCols = new LinkedHashMap<>();
        if (node.get("timeColumns") instanceof Map<?, ?> tc) {
            tc.forEach((k, v) -> {
                String timeName = String.valueOf(k);
                String timeCol = String.valueOf(v);
                checkIdentifier(name, "时间列名", timeName);
                checkIdentifier(name, "时间列 " + timeName + " 的列名", timeCol);
                timeCols.put(timeName, timeCol);
            });
        }

        Map<String, ConditionPreset> presets = new LinkedHashMap<>();
        if (node.get("conditionPresets") instanceof List<?> list) {
            for (Object o : list) {
                Map<String, Object> p = (Map<String, Object>) o;
                presets.put(String.valueOf(p.get("id")).trim(),
                        new ConditionPreset(String.valueOf(p.get("column")),
                                String.valueOf(p.get("op")),
                                p.get("value") == null ? null : String.valueOf(p.get("value"))));
            }
        }

        Set<String> filterables = new LinkedHashSet<>(stringList(node.get("filterableColumns")));
        for (String f : filterables) {
            checkIdentifier(name, "可过滤列", f);
        }
        // 索引标注：只允许引用已声明的可过滤列——给白名单外的列标索引既无意义也会误导目录读者
        Set<String> indexedColumns = new LinkedHashSet<>(stringList(node.get("indexedColumns")));
        for (String col : indexedColumns) {
            checkIdentifier(name, "索引标注列", col);
            if (!filterables.contains(col)) {
                throw new IllegalStateException("实体 " + name + " 的 indexedColumns 引用了未声明为可过滤的列："
                        + col + "，filterableColumns=" + filterables);
            }
        }
        // 意图→列约定：纯目录透出文案，永不进 SQL；写错不会造成注入面
        List<String> filterGuidance = stringList(node.get("filterGuidance"));

        Map<String, RelationshipDef> rels = new LinkedHashMap<>();
        if (node.get("relationships") instanceof List<?> list) {
            for (Object o : list) {
                Map<String, Object> r = (Map<String, Object>) o;
                String target = String.valueOf(r.get("target")).trim();
                String joinType = String.valueOf(r.get("type")).trim();
                String on = String.valueOf(r.get("on")).trim();
                String cardinality = String.valueOf(r.getOrDefault("cardinality", RelationshipDef.TO_MANY))
                        .trim().toLowerCase(Locale.ROOT);
                if (StringUtils.isBlank(joinType) || StringUtils.isBlank(on)) {
                    throw new IllegalStateException("实体 " + name + " 的关系 " + target
                            + " 缺少 type/on 声明");
                }
                if (!RelationshipDef.TO_ONE.equals(cardinality)
                        && !RelationshipDef.TO_MANY.equals(cardinality)) {
                    throw new IllegalStateException("实体 " + name + " 的关系 " + target
                            + " cardinality 只能是 to_one/to_many，实际：" + cardinality);
                }
                rels.put(target, new RelationshipDef(target, joinType, cardinality, on,
                        stringList(r.get("joinFilters"))));
            }
        }

        QueryGuard guard = QueryGuard.NONE;
        if (node.get("queryGuard") instanceof Map<?, ?> g) {
            List<String> required = stringList(g.get("requiredAnyOf"));
            for (String req : required) {
                // "timeRange" 是关键字不是列名；其余必须是合法标识符（守卫列清单不允许夹带自由文本）
                if (!"timeRange".equals(req)) {
                    checkIdentifier(name, "守卫条件列", req);
                }
            }
            Map<String, Object> constraints = g.get("constraints") instanceof Map<?, ?> c
                    ? new LinkedHashMap<>((Map<String, Object>) c) : Map.of();
            guard = new QueryGuard(required, constraints);
        }

        // 加载期硬校验：维度引用列必须在实体声明中可解析（PII 判定推迟到校验层，需要 ColumnTypeRegistry）
        return new EntityDef(name, table, alias, description, countKey, baseline,
                dims, measures, timeCols, presets, filterables, indexedColumns, filterGuidance, rels, guard);
    }

    private static String require(String entity, Map<String, Object> node, String key) {
        String v = str(node.get(key));
        if (StringUtils.isBlank(v)) {
            throw new IllegalStateException("实体 " + entity + " 缺少必填字段 " + key);
        }
        return v;
    }

    /** 加载期硬校验：标识符（表/别名/列名/时间列/可过滤列）只允许字母数字下划线且不以数字开头 */
    private static void checkIdentifier(String entity, String kind, String value) {
        if (value != null && !SAFE_IDENTIFIER.matcher(value).matches()) {
            throw new IllegalStateException("实体 " + entity + " 的" + kind + "「" + value
                    + "」不是合法标识符：仅允许字母、数字、下划线，且不能以数字开头");
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o).trim();
    }

    private static List<String> stringList(Object node) {
        List<String> out = new ArrayList<>();
        if (node instanceof List<?> list) {
            for (Object o : list) {
                if (o != null) {
                    out.add(String.valueOf(o));
                }
            }
        }
        return out;
    }
}
