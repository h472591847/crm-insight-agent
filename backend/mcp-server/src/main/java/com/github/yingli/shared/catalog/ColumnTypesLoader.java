package com.github.yingli.shared.catalog;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/**
 * @Description 列类型/PII 登记表装载器：读取 classpath:/metrics/column-types.yaml。
 * 解析逻辑从 {@code AnalyticsInfrastructureConfiguration} 外移，配置类只保留 Bean 声明。
 * 资源路径保持不变。
 *
 * @Author crm-agent
 */
@Component
public class ColumnTypesLoader {

    private static final Logger log = LoggerFactory.getLogger(ColumnTypesLoader.class);

    /** 列类型/PII 登记表 */
    private static final String COLUMN_TYPES_LOCATION = "classpath:/metrics/column-types.yaml";

    public ColumnTypeRegistry load() throws IOException {
        Resource resource = new PathMatchingResourcePatternResolver().getResource(COLUMN_TYPES_LOCATION);
        if (!resource.exists()) {
            throw new IllegalStateException("缺少列类型登记表: " + COLUMN_TYPES_LOCATION
                    + "（VARCHAR 金额与 PII 列的硬拦截依赖它）");
        }
        Map<String, Object> root = asMap(loadYaml(resource), resource);
        ColumnTypeRegistry registry = ColumnTypeRegistry.fromYaml(root);
        log.info("====== 列类型登记表加载完成: 表={} PII列数={} ======",
                registry.knownTables().size(), registry.piiColumns().size());
        return registry;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object root, Resource resource) {
        if (root instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new IllegalStateException("配置文件格式应为映射: " + resource);
    }

    private static Object loadYaml(Resource resource) throws IOException {
        LoaderOptions options = new LoaderOptions();
        // 配置文件很小，收紧默认限制即可；显式设置避免 SnakeYAML 版本默认值变化带来的意外
        options.setMaxAliasesForCollections(50);
        options.setNestingDepthLimit(20);
        options.setCodePointLimit(1_000_000);
        Yaml yaml = new Yaml(new SafeConstructor(options));
        try (InputStream in = resource.getInputStream()) {
            return yaml.load(in);
        }
    }
}
