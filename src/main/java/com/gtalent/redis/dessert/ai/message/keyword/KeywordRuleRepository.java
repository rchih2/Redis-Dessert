package com.gtalent.redis.dessert.ai.message.keyword;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * KeywordRuleEntity 的 CRUD 操作，繼承 JpaRepository 即可，
 * 目前不需要自訂查詢方法——所有規則都是一次性整批載入到記憶體快取比對，
 * 不會逐筆查資料庫（避免每次使用者傳訊息都打一次 DB）。
 */
public interface KeywordRuleRepository extends JpaRepository<KeywordRuleEntity, Long> {
}