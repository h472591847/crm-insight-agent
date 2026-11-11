package com.itzixi.adhoc.config;

import com.itzixi.adhoc.model.SemanticRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

/**
 * @Description adhoc 域装配：只声明 Bean，加载逻辑在 {@link SemanticCatalogLoader}。
 * 校验器/编译器/查询服务已改为注解式组件，不再在此重复声明。
 *
 * @Author crm-agent
 */
@Configuration
public class AdhocConfiguration {

    @Bean
    public SemanticRegistry semanticRegistry(SemanticCatalogLoader loader) throws IOException {
        return loader.load();
    }
}
