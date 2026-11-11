package com.github.yingli.metrics;

import com.github.yingli.metrics.exception.MetricException;
import com.github.yingli.metrics.service.MetricCatalogService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @ClassName MetricCatalogContractTest
 * @Description B0 契约快照：listMetrics 底层 catalog(domain) 的键集合与层级结构。
 * <p>
 * B3 把 catalog 从 MetricQueryService 拆到 MetricCatalogService 时，本快照必须零 diff：
 * 键集合、条件键有无、每条指标的字段都必须保持不变。
 * @Author crm-agent
 */
public class MetricCatalogContractTest {

    public static Object snapshot() {
        MetricCatalogService service = MetricsFixtures.catalogService();
        return MetricsFixtures.orderedMap(
                "all", service.catalog(null),
                "member", service.catalog("member"),
                "order", service.catalog("order"));
    }

    @Test
    @DisplayName("指标目录契约快照：全量/member/order 三视图零 diff")
    void catalogShapeUnchanged() {
        MetricsFixtures.assertContractBaseline("catalog-metric.json", snapshot());
    }

    @Test
    @DisplayName("未知域仍抛 INVALID_PARAM（错误码不随重构变化）")
    void unknownDomainStillRejected() {
        MetricCatalogService service = MetricsFixtures.catalogService();
        assertThatThrownBy(() -> service.catalog("ghost"))
                .isInstanceOf(MetricException.class)
                .extracting(e -> ((MetricException) e).getCode().name())
                .isEqualTo("INVALID_PARAM");
    }

    @Test
    @DisplayName("目录里每条指标都带契约字段，不因重构丢失键")
    @SuppressWarnings("unchecked")
    void everyMetricHasContractKeys() {
        MetricCatalogService service = MetricsFixtures.catalogService();
        assertThat(service.catalog(null))
                .allSatisfy(item -> assertThat((Map<String, Object>) item).containsKeys(
                        "metric", "title", "domain", "status", "caliber", "timeBased",
                        "dimensions", "measures", "derived", "filters", "sortable",
                        "defaultSortBy", "limitDefault", "limitMax", "withTotalRow"));
    }
}

