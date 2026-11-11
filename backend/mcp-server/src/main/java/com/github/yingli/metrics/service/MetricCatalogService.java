package com.github.yingli.metrics.service;

import com.github.yingli.metrics.exception.MetricException;
import com.github.yingli.metrics.registry.MetricRegistry;
import com.github.yingli.metrics.model.MetricDefinition;
import com.github.yingli.metrics.model.MetricDefinition.Derived;
import com.github.yingli.metrics.model.MetricDefinition.Measure;
import com.github.yingli.metrics.registry.MetricDomains;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @Description 指标元数据目录服务：让模型先查能力再提问，显著降低乱填参数。
 * <p>
 * B3 从 {@code MetricQueryService} 拆出：查询链路与目录装配分离，
 * 工具层只依赖本服务，不再感知注册表内部结构。
 *
 * @Author crm-agent
 */
@Service
@RequiredArgsConstructor
public class MetricCatalogService {

    private final MetricRegistry metricRegistry;

    /**
     * 指标目录（listMetrics 的底层）。
     *
     * @param domain 业务域，不传返回全部；未知域抛 {@link MetricException}（INVALID_PARAM）
     */
    public List<Map<String, Object>> catalog(String domain) {
        String normalized = MetricDomains.normalize(domain);
        if (StringUtils.isNotBlank(normalized) && !MetricDomains.ALLOWED.contains(normalized)) {
            throw MetricException.invalidParam("未知域：" + domain + "，可用域=" + MetricDomains.ALLOWED);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (MetricDefinition def : metricRegistry.byDomain(normalized)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("metric", def.metric());
            item.put("title", def.title());
            item.put("domain", def.domain());
            item.put("status", def.status().name());
            item.put("caliber", def.caliber());
            item.put("timeBased", def.isTimeBased());
            item.put("dimensions", def.dimensions().stream().map(MetricDefinition.Dimension::code).toList());
            List<Map<String, Object>> measures = new ArrayList<>();
            for (Measure m : def.measures()) {
                measures.add(Map.of("code", m.code(), "alias", m.alias(), "unit", StringUtils.defaultString(m.unit())));
            }
            item.put("measures", measures);
            item.put("derived", def.derived().stream().map(Derived::code).toList());
            item.put("filters", def.optionalFilters().stream()
                    .map(f -> Map.of("code", f.code(), "hint", columnHint(f.column()))).toList());
            item.put("sortable", def.sortable());
            item.put("defaultSortBy", def.defaultSortBy());
            item.put("limitDefault", def.limitDefault());
            item.put("limitMax", def.limitMax());
            item.put("withTotalRow", def.withTotalRow());
            out.add(item);
        }
        return out;
    }

    /**
     * 过滤列的用户友好名称（不暴露物理列名细节，也不涉及 PII——PII 列在校验期已被拦截）。
     * 租户列已于 2026-10-30 从全部指标配置中移除，因此不再维护租户分支。
     */
    private String columnHint(String column) {
        return switch (column) {
            // 末尾分支为 join 指标（路线 C）的主表限定列；限定符只是消歧义手段，展示名与裸列一致
            case "expense_stationCode", "expense_d_stationCode", "member_allot_store", "member_store",
                 "e.expense_stationCode" -> "门店编码";
            default -> column;
        };
    }
}
