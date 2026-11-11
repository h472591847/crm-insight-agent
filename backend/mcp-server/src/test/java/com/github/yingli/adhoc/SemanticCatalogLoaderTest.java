package com.github.yingli.adhoc;

import com.github.yingli.adhoc.config.SemanticCatalogLoader;
import com.github.yingli.adhoc.model.SemanticRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @ClassName SemanticCatalogLoaderTest
 * @Description B5 拆分验证：语义元数据加载逻辑从配置类外移后，实体集合必须与拆分前一致。
 *
 * @Author crm-agent
 */
class SemanticCatalogLoaderTest {

    @Test
    @DisplayName("装载实体集合与 B0 夹具一致，且关系/维度可直接查询")
    void loadsSameCatalogAsFixtures() throws IOException {
        SemanticRegistry loaded = new SemanticCatalogLoader(AdhocFixtures.types()).load();
        SemanticRegistry expected = AdhocFixtures.registry();

        assertThat(loaded.entities()).containsExactlyInAnyOrderElementsOf(expected.entities());
        assertThat(loaded.entity("member").table()).isEqualTo(expected.entity("member").table());
        assertThat(loaded.entity("order").relationships().keySet())
                .containsExactlyInAnyOrderElementsOf(expected.entity("order").relationships().keySet());
    }
}
