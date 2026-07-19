package com.gtalent.redis.dessert.ai.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring AI 只會自動配置 ChatClient.Builder，不會直接提供 ChatClient bean，
 * 需要自己 build 一次。這裡統一組裝，之後若要加預設 system prompt、
 * advisor（例如對話記憶）等，也在這裡加。
 */
@Configuration
public class AiConfig {

    @Bean
    public ChatClient chatClient(ChatClient.Builder chatClientBuilder) {
        return chatClientBuilder.build();
    }
}