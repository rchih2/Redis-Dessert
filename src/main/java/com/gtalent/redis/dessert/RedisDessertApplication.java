package com.gtalent.redis.dessert;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableKafka       // 明確啟用 @KafkaListener 註解處理，避免自動配置在這個版本沒生效
@EnableScheduling // 讓 DessertSearchSyncScheduler 的 @Scheduled 生效（定期背景全量同步）
@EnableRetry      // 讓 DessertSearchIndexService 的 @Retryable 生效（即時同步失敗自動重試）
public class RedisDessertApplication {

	public static void main(String[] args) {
		SpringApplication.run(RedisDessertApplication.class, args);
	}
}

