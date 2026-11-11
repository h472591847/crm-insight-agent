package com.itzixi.adhoc;

import com.itzixi.contract.ContractSnapshots;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @ClassName SemanticCatalogContractTest
 * @Description B0 契约快照：listSemanticCatalog 的完整目录输出。
 * <p>
 * B3 把目录组装从 AdhocTool 抽到 SemanticCatalogService 后，本快照必须零 diff：
 * 键集合、条件键（filterGuidance/queryGuard）的有无、pii/indexed 布尔值都不得翻转。
 * @Author crm-agent
 */
public class SemanticCatalogContractTest {

    public static Object snapshot() {
        return ContractSnapshots.orderedMap("catalog", AdhocFixtures.adhocTool().listSemanticCatalog());
    }

    @Test
    @DisplayName("语义目录契约快照：逐键零 diff（含 filterGuidance/queryGuard 有无行为）")
    void semanticCatalogUnchanged() {
        ContractSnapshots.assertContractBaseline("catalog-adhoc.json", snapshot());
    }

    @Test
    @DisplayName("契约要点：member 无 queryGuard、detail 有；布尔标注不得缺失")
    @SuppressWarnings("unchecked")
    void catalogConditionKeys() {
        List<Map<String, Object>> catalog = AdhocFixtures.adhocTool().listSemanticCatalog();
        Map<String, Object> member = catalog.stream()
                .filter(c -> "member".equals(c.get("entity"))).findFirst().orElseThrow();
        Map<String, Object> detail = catalog.stream()
                .filter(c -> "detail".equals(c.get("entity"))).findFirst().orElseThrow();
        assertThat(member).doesNotContainKey("queryGuard");
        assertThat(detail).containsKey("queryGuard");
        assertThat((List<Map<String, Object>>) member.get("filterableColumns"))
                .allSatisfy(f -> assertThat(f).containsKeys("name", "pii", "indexed"));
    }
}
