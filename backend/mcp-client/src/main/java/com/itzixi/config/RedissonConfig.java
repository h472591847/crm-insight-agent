package com.itzixi.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 手动初始化 RedissonClient，复用现有 {@code spring.data.redis.*} 配置。
 * <p>
 * 为什么不用 redisson-spring-boot-starter：它会自动提供 RedissonConnectionFactory / RedisTemplate
 * 覆盖 Spring Boot 原生的 Lettuce/Jedis 实现，导致 Spring AI RedisVectorStore（FT.CREATE / FT.SEARCH 等
 * 非 Lettuce 原生命令）和本项目的 RedisChatMemoryRepository 失效。
 * 只手写 RedissonClient bean 来做分布式锁，不碰 Redis 连接工厂。
 * </p>
 */
@Configuration
public class RedissonConfig {

    private static final Logger log = LoggerFactory.getLogger(RedissonConfig.class);

    @Value("${spring.data.redis.host:localhost}")
    private String host;

    @Value("${spring.data.redis.port:6379}")
    private int port;

    @Value("${spring.data.redis.password:}")
    private String password;

    @Value("${spring.data.redis.database:0}")
    private int database;

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        Config config = new Config();
        String address = "redis://" + host + ":" + port;
        config.useSingleServer()
                .setAddress(address)
                .setDatabase(database);
        if (password != null && !password.isBlank()) {
            config.useSingleServer().setPassword(password);
        }
        log.info("RedissonClient 初始化完成: {}, db={}", address, database);
        return Redisson.create(config);
    }
}
