package com.gtalent.redis.dessert.config;

import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;

/**
 * Kafka producer 基礎設定。
 *
 * <p>這裡直接明確建立 ProducerFactory / KafkaTemplate，是必要的，不能直接刪除：
 * {@code EventPublisherService} 建構子要求的是明確型別
 * {@code KafkaTemplate<String, Object>}，而 Spring Boot 對 Kafka 的自動組態
 * 預設只會建立 {@code KafkaTemplate<Object, Object>}。Spring 的泛型 autowiring
 * 是不變異比對，{@code KafkaTemplate<Object, Object>} 無法滿足
 * {@code KafkaTemplate<String, Object>} 這個依賴，直接刪掉這個設定類別會導致
 * 啟動失敗（{@code No qualifying bean of type 'KafkaTemplate'}）。</p>
 *
 * <p><b>value serializer 統一改用 {@link JacksonJsonSerializer}（Jackson 3 系列）。</b>
 * 舊版 {@code JsonSerializer}/{@code JsonDeserializer} 自 Spring Kafka 4.0 起已標記
 * {@code @Deprecated(forRemoval = true)}，未來版本會直接移除。本專案已在使用
 * Spring Boot 4.1.0（Jackson 2/3 相依皆已到位），因此直接遷移到 Jackson 3 系列，
 * 而不是繼續依賴即將被移除的舊 API。</p>
 *
 * <p><b>producer / consumer 必須成對切換，不能只改一邊：</b>
 * 這裡的 {@code JacksonJsonSerializer} 產生的型別 header 格式跟舊版
 * {@code JsonSerializer} 不同，consumer 端（{@code application.yml} 的
 * {@code spring.kafka.consumer.value-deserializer}）必須同步改成
 * {@code JacksonJsonDeserializer}，否則會在 {@code @KafkaListener}
 * 反序列化階段丟例外、訊息被跳過，事件就不會落地到 MongoDB 的
 * {@code action_logs} / {@code chat_message_history}。</p>
 *
 * <p><b>先前踩過的坑（記錄避免重蹈覆轍）：</b>
 * 曾經出現過 {@code application.yml} 裡的 {@code producer.value-serializer}
 * 寫著 {@code JacksonJsonSerializer}，但這個設定其實完全沒生效——因為本類別
 * 已經明確建立了 {@code ProducerFactory} bean，Spring Boot 的 Kafka 自動組態
 * 屬性只有在「沒有使用者自訂 ProducerFactory/KafkaTemplate bean」時才會套用。
 * 當時 Java 這裡寫的是舊版 {@code JsonSerializer}，跟 yml 顯示的內容不一致，
 * 容易誤導後續維護者以為 producer 已經是 Jackson 3 格式。現在兩邊已經對齊：
 * Java 程式碼的實際行為，就是 yml 上看到的行為。</p>
 */
@Configuration
public class KafkaProducerConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Bean
    public ProducerFactory<String, Object> producerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JacksonJsonSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.RETRIES_CONFIG, 3);
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, Object> kafkaTemplate(ProducerFactory<String, Object> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }
}