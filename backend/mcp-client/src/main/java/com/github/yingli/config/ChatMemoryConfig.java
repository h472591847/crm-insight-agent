package com.github.yingli.config;

import com.github.yingli.repository.RedisChatMemoryRepository;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * ChatMemory 配置：使用 RedisChatMemoryRepository 作为持久化层，
 * 窗口大小默认 20 条消息（与升级前的默认值一致）。
 *
 * <p>切换回 InMemoryChatMemoryRepository 只需注释掉这里的 @Bean，
 * Spring AI 的 ChatMemoryAutoConfiguration 会自动提供默认实现。</p>
 */
@Configuration
public class ChatMemoryConfig {

    @Value("${app.chat.memory.window-size:20}")
    private int maxMessages;

    @Bean
    public RedisChatMemoryRepository redisChatMemoryRepository(StringRedisTemplate redisTemplate) {
        return new RedisChatMemoryRepository(redisTemplate);
    }

    @Bean
    public ChatMemory chatMemory(RedisChatMemoryRepository repository) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(maxMessages)
                .build();
    }
}
