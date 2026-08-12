package com.gtalent.redis.dessert.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * 明確定義 Kafka topic（分區數、副本數），而不是仰賴 broker 的
 * auto.create.topics.enable 自動建立。
 *
 * <p>本機單一 broker 開發環境，replicas 固定為 1；
 * partitions 設為 3 只是為了示範多分區下「同一個 key 落在同一 partition」的順序保證，
 * 對這個作品集規模的資料量而言，1 個 partition 其實也完全足夠。</p>
 */
@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic orderEventsTopic() {
        return TopicBuilder.name("order-events")
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic aiQaEventsTopic() {
        return TopicBuilder.name("ai-qa-events")
                .partitions(3)
                .replicas(1)
                .build();
    }
}