package com.itzixi.adhoc;

import com.itzixi.adhoc.service.SemanticCatalogService;
import com.itzixi.contract.ContractSnapshots;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @ClassName SemanticCatalogServiceTest
 * @Description B3 语义目录服务抽取的行为测试：复用 B0 指纹，并验证工具层纯委托。
 * @Author crm-agent
 */
class SemanticCatalogServiceTest {

    private final SemanticCatalogService service = AdhocFixtures.catalogService();

    @Test
    @DisplayName("抽取后的目录与 B0 基线零 diff")
    void catalogMatchesBaseline() {
        ContractSnapshots.assertContractBaseline("catalog-adhoc.json",
                ContractSnapshots.orderedMap("catalog", service.listCatalog()));
    }

    @Test
    @DisplayName("AdhocTool.listSemanticCatalog 与目录服务输出逐键一致（纯委托）")
    void toolDelegatesToService() {
        assertThat(ContractSnapshots.canonicalJson(AdhocFixtures.adhocTool().listSemanticCatalog()))
                .isEqualTo(ContractSnapshots.canonicalJson(service.listCatalog()));
    }
}
