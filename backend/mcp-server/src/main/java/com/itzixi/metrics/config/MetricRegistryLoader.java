package com.itzixi.metrics.config;

import com.itzixi.metrics.model.MetricDefinition;
import com.itzixi.metrics.registry.MetricDomains;
import com.itzixi.metrics.registry.MetricRegistry;
import com.itzixi.metrics.validate.MetricConfigValidator;
import com.itzixi.shared.catalog.ColumnTypeRegistry;
import lombok.RequiredArgsConstructor;
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
import java.util.ArrayList;
import java.util.List;

/**
 * @Description 指标配置的装载器：读取 classpath:/metrics/*.yaml，解析、校验、构建注册表。
 * <p>
 * 两个刻意的设计：
 * 1. <b>校验失败即启动失败</b>：配置错误必须在部署期暴露，不能等到用户问数时才变成一个可疑数字；
 * 2. <b>SnakeYAML 使用 SafeConstructor</b>：配置文件里不允许出现任意对象构造，避免反序列化风险。
 * 解析结果手工映射为记录对象，不依赖任何自动绑定。
 *
 * @Author crm-agent
 */
@Component
@RequiredArgsConstructor
public class MetricRegistryLoader {

    private static final Logger log = LoggerFactory.getLogger(MetricRegistryLoader.class);

    /** 指标配置文件（按域拆分） */
    private static final String METRICS_PATTERN = "classpath*:/metrics/*.yaml";
    /** 登记表文件名，扫描指标文件时需排除 */
    private static final String COLUMN_TYPES_FILE = "column-types.yaml";

    private final ColumnTypeRegistry columnTypeRegistry;

    public MetricRegistry load() throws IOException {
        Resource[] resources = new PathMatchingResourcePatternResolver().getResources(METRICS_PATTERN);
        List<MetricDefinition> definitions = new ArrayList<>();
        for (Resource resource : resources) {
            if (COLUMN_TYPES_FILE.equalsIgnoreCase(resource.getFilename())) {
                continue;
            }
            Object root = loadYaml(resource);
            List<MetricDefinition> parsed = MetricDefinitionParser.parseFile(root, resource.getFilename());
            definitions.addAll(parsed);
            log.info("====== 指标配置加载 {} -> {} 个指标 ======", resource.getFilename(), parsed.size());
        }

        List<String> errors = new ArrayList<>(MetricConfigValidator.validateUniqueness(definitions));
        for (MetricDefinition def : definitions) {
            errors.addAll(MetricConfigValidator.validate(def, columnTypeRegistry, MetricDomains.ALLOWED));
        }
        if (!errors.isEmpty()) {
            throw new IllegalStateException("指标配置校验失败，共 " + errors.size() + " 项:\n  - "
                    + String.join("\n  - ", errors));
        }
        MetricRegistry registry = new MetricRegistry(definitions);
        log.info("====== 指标注册表就绪: {} 个指标（其中未确认口径 {} 个，将被拒绝执行）=====",
                registry.size(), countUnconfirmed(definitions));
        return registry;
    }

    private static long countUnconfirmed(List<MetricDefinition> definitions) {
        return definitions.stream().filter(d -> !d.isConfirmed()).count();
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
