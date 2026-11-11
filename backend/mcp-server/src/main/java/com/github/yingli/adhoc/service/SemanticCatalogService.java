package com.github.yingli.adhoc.service;

import com.github.yingli.adhoc.model.SemanticRegistry;
import com.github.yingli.shared.catalog.ColumnTypeRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @Description Ad-hoc 语义目录服务：组装 listSemanticCatalog 的输出。
 * <p>
 * B3 从 {@code AdhocTool} 拆出：工具层只做协议适配与参数转述，
 * 目录键集合、条件键有无、pii/indexed 标注全部由本服务确定性产出。
 *
 * @Author crm-agent
 */
@Service
@RequiredArgsConstructor
public class SemanticCatalogService {

    private final SemanticRegistry semanticRegistry;

    private final ColumnTypeRegistry columnTypeRegistry;

    /**
     * 列出 Ad-hoc 可用的实体/维度/度量/条件模板/时间维度/可过滤列/可 join 实体。
     * 输出结构与 B0 基线 {@code contract/catalog-adhoc.json} 逐键一致。
     */
    public List<Map<String, Object>> listCatalog() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String entityName : semanticRegistry.entities()) {
            SemanticRegistry.EntityDef e = semanticRegistry.entity(entityName);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("entity", e.name());
            item.put("description", e.description());
            // pii 标注：expr-only 维度无原始列，恒为 false（表达式中不直接输出原始列值）
            item.put("dimensions", e.dimensions().values().stream()
                    .map(d -> Map.of("name", d.name(),
                            "role", d.agg() != null ? "attribute(MAX)" : "group",
                            "requires", d.requires() == null ? "" : d.requires(),
                            "pii", d.column() != null && columnTypeRegistry.isPii(d.column()))).toList());
            // 度量的 pii 语义 = "PII 派生，输出仅限聚合值"（如 BUYER_COUNT 对 expense_mobile 去重计数，可正常使用）。
            // 仅对注册在案的 PII 判定（column-types.yaml 中 piiComputeAllowedColumns 是 piiColumns 的子集，
            // 对非 PII 列查 computeAllowed 恒为 false，若参与 OR 会把普通金额列误标为 PII，故不取 OR）
            item.put("measures", e.measures().values().stream()
                    .map(m -> Map.of("name", m.name(), "agg", m.agg(), "grain", e.name(),
                            "pii", m.column() != null && columnTypeRegistry.isPii(m.column()))).toList());
            item.put("conditionPresets", e.conditionPresets().keySet());
            item.put("timeColumns", e.timeColumns().keySet());
            item.put("filterableColumns", e.filterables().stream()
                    .map(c -> Map.of("name", c, "pii", columnTypeRegistry.isPii(c),
                            "indexed", e.indexed(c))).toList());
            // 元数据声明的意图→列约定（如门店名/门店编码各自的过滤落点），随目录透出，模型不必猜列
            if (!e.filterGuidance().isEmpty()) {
                item.put("filterGuidance", e.filterGuidance());
            }
            // 实体级查询守卫透出（如明细表三选一定向条件+92 天窗）：模型组参前即知硬约束，避免试错
            if (e.queryGuard().required()) {
                item.put("queryGuard", Map.of("requiredAnyOf", e.queryGuard().requiredAnyOf(),
                        "constraints", e.queryGuard().constraints()));
            }
            item.put("relationships", e.relationships().values().stream()
                    .map(r -> Map.of("target", r.target(), "type", r.type(),
                            "cardinality", r.cardinality())).toList());
            out.add(item);
        }
        return out;
    }
}
