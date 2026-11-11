package com.github.yingli.metrics.config;

import com.github.yingli.metrics.compile.MetricSqlCompiler;
import com.github.yingli.metrics.registry.MetricRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

/**
 * @Description 指标域装配：只声明 Bean，加载逻辑在 {@link MetricRegistryLoader}。
 * 两个 @Bean 方法名与拆分前保持一致，降低按名注入的隐性依赖。
 *
 * @Author crm-agent
 */
@Configuration
public class MetricConfiguration {

    @Bean
    public MetricRegistry metricRegistry(MetricRegistryLoader loader) throws IOException {
        return loader.load();
    }

    @Bean
    public MetricSqlCompiler MetricSqlCompiler() {
        return new MetricSqlCompiler();
    }
}
