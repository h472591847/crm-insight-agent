package com.itzixi.metrics;

import com.itzixi.metrics.config.MetricRegistryLoader;
import com.itzixi.metrics.model.MetricDefinition;
import com.itzixi.metrics.registry.MetricRegistry;
import com.itzixi.shared.catalog.ColumnTypesLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @ClassName MetricRegistryLoaderTest
 * @Description B5 拆分验证：Loader 独立承担加载/解析/校验，产出必须与拆分前逐项一致。
 * 数量断言基于当前 resources/metrics/*.yaml（member 14 + order 3 = 17，未确认 1）。
 *
 * @Author crm-agent
 */
class MetricRegistryLoaderTest {

    private static MetricRegistry load() throws IOException {
        return new MetricRegistryLoader(new ColumnTypesLoader().load()).load();
    }

    @Test
    @DisplayName("装载数量与未确认口径数量与拆分前一致")
    void loadsExpectedCounts() throws IOException {
        MetricRegistry registry = load();

        assertThat(registry.size()).isEqualTo(17);
        // 2026-11-09：删除 GENDER_EXPENSE_TOTAL（唯一未确认口径），新增 MEMBER_CONSUMPTION（金额来源已确认）
        assertThat(registry.all().stream().filter(def -> !def.isConfirmed())).isEmpty();
        assertThat(registry.require("MEMBER_OVERVIEW").isConfirmed()).isTrue();
        assertThat(registry.require("MEMBER_CONSUMPTION").table()).isEqualTo("expense_calendar_info");
    }

    @Test
    @DisplayName("Loader 产出与 B0 夹具注册表逐项一致（拆分不改加载结果）")
    void sameAsFixtures() throws IOException {
        MetricRegistry loaded = load();
        MetricRegistry expected = MetricsFixtures.registry();

        assertThat(loaded.size()).isEqualTo(expected.size());
        assertThat(loaded.all())
                .extracting(MetricRegistryLoaderTest::codeAndStatus)
                .containsExactlyElementsOf(expected.all().stream()
                        .map(MetricRegistryLoaderTest::codeAndStatus)
                        .toList());
    }

    private static String codeAndStatus(MetricDefinition definition) {
        return definition.metric() + "|" + definition.status();
    }
}
