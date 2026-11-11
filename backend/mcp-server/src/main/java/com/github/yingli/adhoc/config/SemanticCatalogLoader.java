package com.github.yingli.adhoc.config;

import com.github.yingli.adhoc.model.SemanticRegistry;
import com.github.yingli.shared.catalog.ColumnTypeRegistry;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/**
 * @Description 语义元数据装载器：读取 semantic/metadata.yaml，构建 {@link SemanticRegistry}。
 * <p>
 * 与 {@code MetricRegistryLoader} 同款的两个刻意设计：
 * 1. SnakeYAML 使用 SafeConstructor，配置文件里不允许任意对象构造；
 * 2. 走 {@link SemanticRegistry#fromYaml(Map, ColumnTypeRegistry)} 两参生产入口，
 *    启动期即硬校验实体表在列类型登记表白名单内。
 * @Author crm-agent
 */
@Component
@RequiredArgsConstructor
public class SemanticCatalogLoader {

    private static final Logger log = LoggerFactory.getLogger(SemanticCatalogLoader.class);

    private static final String METADATA_LOCATION = "semantic/metadata.yaml";

    private final ColumnTypeRegistry columnTypeRegistry;

    public SemanticRegistry load() throws IOException {
        Map<String, Object> root;
        try (InputStream in = new ClassPathResource(METADATA_LOCATION).getInputStream()) {
            LoaderOptions options = new LoaderOptions();
            // 配置文件很小，收紧默认限制即可；显式设置避免 SnakeYAML 版本默认值变化带来的意外
            options.setMaxAliasesForCollections(50);
            options.setNestingDepthLimit(20);
            options.setCodePointLimit(1_000_000);
            root = new Yaml(new SafeConstructor(options)).load(in);
        }
        SemanticRegistry registry = SemanticRegistry.fromYaml(root, columnTypeRegistry);
        log.info("====== 语义元数据加载完成: 实体={} ======", registry.entities());
        return registry;
    }
}
