package com.itzixi.shared.catalog;

import org.apache.commons.lang3.StringUtils;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @Description 列类型与敏感列登记表（由 resources/metrics/column-types.yaml 加载）。
 * 用途有两个，都是硬拦截：
 * 1. 金额/数量为 VARCHAR 的列，聚合时必须走 NUM() 安全转换，否则配置加载期直接失败
 *    （CRM 三表的金额几乎都是 VARCHAR，MySQL 隐式转换会静默截断脏值，算出错数且不报错）；
 * 2. 个人信息列（PII）不得作为维度/描述列输出，也不得进入过滤回显。
 * 列类型依据 docs/ai-agent/DATABASE_SCHEMA.md 的原始 DDL 登记，不凭字段名猜测。
 * @Author crm-agent
 */
public class ColumnTypeRegistry {

    /** 全局 PII 列（任何表出现即禁止输出） */
    private final Set<String> globalPiiColumns;

    /**
     * PII 中允许作为计算输入的列（piiColumns 的子集）：仅限在聚合/分桶表达式内引用
     * （如 TIMESTAMPDIFF(生日)、COUNT(DISTINCT mobile)），原值输出仍然一律禁止。
     */
    private final Set<String> piiComputeAllowedColumns;

    /** 表名 -> VARCHAR 且语义为数值的列 */
    private final Map<String, Set<String>> varcharNumericColumns;

    /** 已登记的表（同时充当表白名单） */
    private final Set<String> knownTables;

    public ColumnTypeRegistry(Set<String> globalPiiColumns,
                              Map<String, Set<String>> varcharNumericColumns) {
        this(globalPiiColumns, Set.of(), varcharNumericColumns);
    }

    public ColumnTypeRegistry(Set<String> globalPiiColumns,
                              Set<String> piiComputeAllowedColumns,
                              Map<String, Set<String>> varcharNumericColumns) {
        this.globalPiiColumns = Collections.unmodifiableSet(new LinkedHashSet<>(globalPiiColumns));
        this.piiComputeAllowedColumns = Collections.unmodifiableSet(new LinkedHashSet<>(piiComputeAllowedColumns));
        this.varcharNumericColumns = Collections.unmodifiableMap(varcharNumericColumns);
        this.knownTables = Collections.unmodifiableSet(new LinkedHashSet<>(varcharNumericColumns.keySet()));
    }

    @SuppressWarnings("unchecked")
    public static ColumnTypeRegistry fromYaml(Map<String, Object> root) {
        Set<String> pii = new LinkedHashSet<>();
        Object piiNode = root.get("piiColumns");
        if (piiNode instanceof List<?> list) {
            for (Object o : list) {
                if (o != null) {
                    pii.add(String.valueOf(o).trim().toLowerCase());
                }
            }
        }
        Map<String, Set<String>> varcharNumeric = new java.util.LinkedHashMap<>();
        Object tablesNode = root.get("tables");
        if (tablesNode instanceof Map<?, ?> tables) {
            for (Map.Entry<?, ?> e : tables.entrySet()) {
                String table = String.valueOf(e.getKey()).trim();
                Set<String> cols = new LinkedHashSet<>();
                if (e.getValue() instanceof Map<?, ?> meta) {
                    Object vc = meta.get("varcharNumeric");
                    if (vc instanceof List<?> list) {
                        for (Object o : list) {
                            if (o != null) {
                                cols.add(String.valueOf(o).trim());
                            }
                        }
                    }
                }
                varcharNumeric.put(table, cols);
            }
        }
        Set<String> piiCompute = new LinkedHashSet<>();
        Object computeNode = root.get("piiComputeAllowedColumns");
        if (computeNode instanceof List<?> list) {
            for (Object o : list) {
                if (o != null) {
                    piiCompute.add(String.valueOf(o).trim().toLowerCase());
                }
            }
        }
        return new ColumnTypeRegistry(pii, piiCompute, varcharNumeric);
    }

    /**
     * 表是否在白名单内。未登记的表不允许任何指标引用。
     */
    public boolean isKnownTable(String table) {
        return table != null && knownTables.contains(table.trim());
    }

    public Set<String> knownTables() {
        return knownTables;
    }

    /**
     * 该列是否为“VARCHAR 且承载数值”，聚合前必须 NUM() 转换。
     */
    public boolean isVarcharNumeric(String table, String column) {
        if (StringUtils.isBlank(table) || StringUtils.isBlank(column)) {
            return false;
        }
        Set<String> cols = varcharNumericColumns.get(table.trim());
        return cols != null && cols.contains(column.trim());
    }

    /**
     * 该列是否为个人信息（禁止作为维度/描述列输出）。
     */
    public boolean isPii(String column) {
        return column != null && globalPiiColumns.contains(column.trim().toLowerCase());
    }

    /**
     * 该 PII 列是否允许作为聚合/分桶表达式的计算输入（原值输出仍然禁止）。
     */
    public boolean isPiiComputeAllowed(String column) {
        return column != null && piiComputeAllowedColumns.contains(column.trim().toLowerCase());
    }

    public Set<String> piiColumns() {
        return globalPiiColumns;
    }

    public Set<String> piiComputeAllowedColumns() {
        return piiComputeAllowedColumns;
    }
}
