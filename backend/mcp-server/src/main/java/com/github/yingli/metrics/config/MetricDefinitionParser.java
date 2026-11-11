package com.github.yingli.metrics.config;

import com.github.yingli.metrics.model.MetricDefinition;
import com.github.yingli.metrics.model.MetricDefinition.Attribute;
import com.github.yingli.metrics.model.MetricDefinition.Derived;
import com.github.yingli.metrics.model.MetricDefinition.Dimension;
import com.github.yingli.metrics.model.MetricDefinition.Filter;
import com.github.yingli.metrics.model.MetricDefinition.Grain;
import com.github.yingli.metrics.model.MetricDefinition.Join;
import com.github.yingli.metrics.model.MetricDefinition.Measure;
import com.github.yingli.metrics.model.MetricDefinition.Status;
import com.github.yingli.shared.limits.QueryLimits;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * @Description 把 SnakeYAML 解析出的 Map 转成 {@link MetricDefinition}。
 * 手写显式映射（不依赖任何 YAML 对象的自动绑定），目的是让"字段缺失/写错"暴露成
 * 带指标名的明确错误，而不是一堆 null。
 * <p>
 * 缺省值一律取<b>保守</b>方向：status 缺失或未识别时按 {@link Status#UNCONFIRMED} 处理，
 * 宁可拒绝查询，也不要给出未确认口径的数字。
 *
 * @Author crm-agent
 */
public final class MetricDefinitionParser {

    private MetricDefinitionParser() {
    }

    /**
     * 解析一个 YAML 文件中的指标列表。
     *
     * @param rootNode 顶层节点：要么是 {metrics: [...]}, 要么是 [...]
     * @param source   文件标识，仅用于错误信息定位
     */
    @SuppressWarnings("unchecked")
    public static List<MetricDefinition> parseFile(Object rootNode, String source) {
        List<Map<String, Object>> items = new ArrayList<>();
        if (rootNode instanceof Map<?, ?> map) {
            Object metrics = map.get("metrics");
            if (metrics instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> m) {
                        items.add((Map<String, Object>) m);
                    }
                }
            }
        } else if (rootNode instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    items.add((Map<String, Object>) m);
                }
            }
        }
        List<MetricDefinition> out = new ArrayList<>(items.size());
        for (Map<String, Object> item : items) {
            try {
                out.add(fromMap(item));
            } catch (Exception e) {
                throw new IllegalStateException("解析指标配置失败[" + source + "]: " + e.getMessage(), e);
            }
        }
        return out;
    }

    public static MetricDefinition fromMap(Map<String, Object> item) {
        String metric = str(item, "metric");
        String where = StringUtils.isBlank(metric) ? "<未命名指标>" : metric;
        return new MetricDefinition(
                metric,
                str(item, "title"),
                lower(str(item, "domain")),
                status(item.get("status")),
                str(item, "caliber"),
                str(item, "table"),
                join(item.get("join")),
                grain(item.get("grain"), where),
                str(item, "timeColumn"),
                strList(item.get("baselineFilters")),
                dimensions(item.get("dimensions")),
                attributes(item.get("attributes")),
                measures(item.get("measures")),
                filters(item.get("optionalFilters")),
                derived(item.get("derived")),
                upperStrList(item.get("sortable")),
                upper(str(item, "defaultSortBy")),
                sortDir(item.get("defaultSortDir")),
                intOr(item.get("limitDefault"), 100),
                intOr(item.get("limitMax"), 500),
                boolOr(item.get("withTotalRow")),
                str(item, "nullDimensionLabel"),
                lowerStrList(item.get("forbiddenColumns")),
                intOr(item.get("defaultWindowMonths"), 0),
                intOr(item.get("maxRangeDays"), 0)
        );
    }

    // ------------------------------------------------------------------
    // 子节点解析
    // ------------------------------------------------------------------

    private static List<Dimension> dimensions(Object node) {
        List<Dimension> out = new ArrayList<>();
        for (Map<String, Object> m : maps(node)) {
            out.add(new Dimension(upper(str(m, "code")), str(m, "expr"), str(m, "alias")));
        }
        return out;
    }

    /**
     * join 节点可缺失（返回 null = 单表指标，存量配置零影响）；
     * 写了节点但字段不全时报错定位到具体字段，不默默退化成单表。
     */
    private static Join join(Object node) {
        if (node == null) {
            return null;
        }
        if (!(node instanceof Map<?, ?> m)) {
            throw new IllegalArgumentException("join 节点期望对象，实际为: " + node);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) m;
        String table = str(map, "table");
        String alias = str(map, "alias");
        String mainAlias = str(map, "mainAlias");
        String on = str(map, "on");
        if (on == null) {
            // YAML 1.1 陷阱兼容：未加引号的 on 键被 SnakeYAML 解析成布尔键（非 String，
            // 泛型擦除后不能直接当字符串用），本意就是 on
            for (Map.Entry<?, ?> e : ((Map<?, ?>) m).entrySet()) {
                if (Boolean.TRUE.equals(e.getKey())) {
                    on = e.getValue() == null ? null : String.valueOf(e.getValue()).trim();
                    break;
                }
            }
        }
        if (on == null) {
            // 部分容器预转换/其它解析入口可能留下字符串形态的布尔同义键
            for (Map.Entry<String, Object> e : map.entrySet()) {
                Boolean boolSem = parseYamlBoolKey(e.getKey());
                if (Boolean.TRUE.equals(boolSem)) {
                    on = e.getValue() == null ? null : String.valueOf(e.getValue()).trim();
                }
            }
        }
        if (StringUtils.isAnyBlank(table, alias, mainAlias, on)) {
            throw new IllegalArgumentException("join 节点必须同时声明 table/alias/mainAlias/on，缺失字段属于配置错误");
        }
        return new Join(table, alias, mainAlias, on);
    }

    /** YAML 1.1 布尔键识别（on/off/yes/no/true/false，大小写不敏感）；非布尔语义返回 null */
    private static Boolean parseYamlBoolKey(String key) {
        if (key == null) {
            return null;
        }
        return switch (key.toLowerCase(java.util.Locale.ROOT)) {
            case "true", "on", "yes" -> Boolean.TRUE;
            case "false", "off", "no" -> Boolean.FALSE;
            default -> null;
        };
    }

    private static List<Attribute> attributes(Object node) {
        List<Attribute> out = new ArrayList<>();
        for (Map<String, Object> m : maps(node)) {
            String agg = str(m, "agg");
            out.add(new Attribute(str(m, "expr"), str(m, "alias"),
                    StringUtils.isBlank(agg) ? "MAX" : agg.toUpperCase(Locale.ROOT)));
        }
        return out;
    }

    private static List<Measure> measures(Object node) {
        List<Measure> out = new ArrayList<>();
        for (Map<String, Object> m : maps(node)) {
            out.add(new Measure(
                    upper(str(m, "code")),
                    str(m, "expr"),
                    str(m, "agg"),
                    str(m, "alias"),
                    str(m, "unit"),
                    boolOr(m.get("zeroFill")),
                    intOr(m.get("decimalScale"), 2)));
        }
        return out;
    }

    private static List<Filter> filters(Object node) {
        List<Filter> out = new ArrayList<>();
        for (Map<String, Object> m : maps(node)) {
            out.add(new Filter(upper(str(m, "code")), str(m, "column")));
        }
        return out;
    }

    private static List<Derived> derived(Object node) {
        List<Derived> out = new ArrayList<>();
        for (Map<String, Object> m : maps(node)) {
            out.add(new Derived(upper(str(m, "code")), str(m, "formula"),
                    intOr(m.get("scale"), 2), str(m, "unit")));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 取值与缺省
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object node) {
        if (node == null) {
            return List.of();
        }
        if (node instanceof List<?> list) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    out.add((Map<String, Object>) m);
                } else {
                    throw new IllegalArgumentException("期望对象列表，实际为: " + o);
                }
            }
            return out;
        }
        throw new IllegalArgumentException("期望列表，实际为: " + node.getClass().getName());
    }

    private static Status status(Object node) {
        if (node == null) {
            return Status.UNCONFIRMED;
        }
        try {
            return Status.valueOf(String.valueOf(node).trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            // 未知取值退回到"不可执行"，绝不因为写错状态而放开查询
            return Status.UNCONFIRMED;
        }
    }

    private static Grain grain(Object node, String where) {
        if (node == null || StringUtils.isBlank(String.valueOf(node))) {
            throw new IllegalArgumentException("指标 " + where + " 缺少 grain（统计粒度必须显式声明）");
        }
        try {
            return Grain.valueOf(String.valueOf(node).trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("指标 " + where + " 的 grain 非法: " + node + "，允许值="
                    + java.util.Arrays.toString(Grain.values()));
        }
    }

    private static String sortDir(Object node) {
        String v = node == null ? null : String.valueOf(node).trim().toLowerCase(Locale.ROOT);
        return QueryLimits.SORT_ASC.equals(v) ? QueryLimits.SORT_ASC : QueryLimits.SORT_DESC;
    }

    private static List<String> strList(Object node) {
        if (node == null) {
            return List.of();
        }
        if (node instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            list.forEach(o -> out.add(o == null ? null : String.valueOf(o).trim()));
            return out;
        }
        throw new IllegalArgumentException("期望字符串列表，实际为: " + node);
    }

    private static List<String> upperStrList(Object node) {
        List<String> out = new ArrayList<>();
        strList(node).forEach(s -> out.add(s == null ? null : s.toUpperCase(Locale.ROOT)));
        return out;
    }

    private static List<String> lowerStrList(Object node) {
        List<String> out = new ArrayList<>();
        strList(node).forEach(s -> out.add(s == null ? null : s.toLowerCase(Locale.ROOT)));
        return out;
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : String.valueOf(v).trim();
    }

    private static String upper(String v) {
        return v == null ? null : v.toUpperCase(Locale.ROOT);
    }

    private static String lower(String v) {
        return v == null ? null : v.toLowerCase(Locale.ROOT);
    }

    private static int intOr(Object v, int def) {
        if (v == null) {
            return def;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("数值字段非法: " + v);
        }
    }

    private static boolean boolOr(Object v) {
        if (v == null) {
            return false;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(String.valueOf(v).trim());
    }
}
