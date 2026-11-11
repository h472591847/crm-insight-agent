package com.github.yingli.metrics.registry;

import com.github.yingli.metrics.exception.MetricException;
import com.github.yingli.metrics.model.MetricDefinition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * @Description 指标注册表：口径的唯一事实来源，运行期只读。
 * 新增指标只需增加 YAML，不需要新增 Java 代码；模型通过 {@code listMetrics} 发现这里的内容。
 * @Author crm-agent
 */
public class MetricRegistry {

    private final Map<String, MetricDefinition> byCode;

    public MetricRegistry(List<MetricDefinition> definitions) {
        Map<String, MetricDefinition> map = new LinkedHashMap<>();
        for (MetricDefinition d : definitions) {
            map.put(d.metric(), d);
        }
        this.byCode = Collections.unmodifiableMap(map);
    }

    public Optional<MetricDefinition> find(String metric) {
        return metric == null ? Optional.empty() : Optional.ofNullable(byCode.get(metric.trim()));
    }

    /**
     * 取指标；不存在则抛 {@link MetricException.Code#METRIC_NOT_FOUND}。
     */
    public MetricDefinition require(String metric) {
        return find(metric).orElseThrow(() -> new MetricException(
                MetricException.Code.METRIC_NOT_FOUND,
                "未注册的指标编码：" + metric + "，请先调用 listMetrics 查看可用指标"));
    }

    public List<MetricDefinition> byDomain(String domain) {
        List<MetricDefinition> out = new ArrayList<>();
        for (MetricDefinition d : byCode.values()) {
            if (domain == null || domain.isBlank() || domain.equalsIgnoreCase(d.domain())) {
                out.add(d);
            }
        }
        return out;
    }

    public List<MetricDefinition> all() {
        return List.copyOf(byCode.values());
    }

    public int size() {
        return byCode.size();
    }
}
