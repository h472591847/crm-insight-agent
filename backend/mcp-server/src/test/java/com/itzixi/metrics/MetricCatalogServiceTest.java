package com.itzixi.metrics;

import com.itzixi.metrics.exception.MetricException;
import com.itzixi.metrics.service.MetricCatalogService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @ClassName MetricCatalogServiceTest
 * @Description B3 目录服务抽取的行为测试：复用 B0 指纹，另覆盖域归一化与过滤列提示。
 * @Author crm-agent
 */
class MetricCatalogServiceTest {

    private final MetricCatalogService service = MetricsFixtures.catalogService();

    @Test
    @DisplayName("抽取后的目录与 B0 基线零 diff")
    void catalogMatchesBaseline() {
        MetricsFixtures.assertContractBaseline("catalog-metric.json",
                MetricsFixtures.orderedMap(
                        "all", service.catalog(null),
                        "member", service.catalog("member"),
                        "order", service.catalog("order")));
    }

    @Test
    @DisplayName("域编码大小写/空格归一化仍生效")
    void domainNormalized() {
        assertThat(service.catalog(" MEMBER "))
                .isEqualTo(service.catalog("member"));
    }

    @Test
    @DisplayName("门店编码过滤列提示保持中文展示名")
    @SuppressWarnings("unchecked")
    void filterHintsKeepChineseLabel() {
        List<Map<String, Object>> catalog = service.catalog("order");
        assertThat(catalog)
                .flatExtracting(item -> (List<Map<String, Object>>) item.get("filters"))
                .filteredOn(f -> List.of("expense_stationCode", "expense_d_stationCode").contains(f.get("code")))
                .allSatisfy(f -> assertThat(f.get("hint")).isEqualTo("门店编码"));
    }

    @Test
    @DisplayName("未知域仍抛 INVALID_PARAM")
    void unknownDomainRejected() {
        assertThatThrownBy(() -> service.catalog("ghost"))
                .isInstanceOf(MetricException.class)
                .extracting(e -> ((MetricException) e).getCode().name())
                .isEqualTo("INVALID_PARAM");
    }
}
