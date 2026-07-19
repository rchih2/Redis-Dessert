package com.gtalent.redis.dessert.ai.repository;

import com.gtalent.redis.dessert.ai.model.SystemConfig;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

/**
 * SystemConfig 的 Repository。
 *
 * <p>configKey 為唯一索引，因此以 configKey 查詢通常最多只有一筆結果，
 * 用 Optional 包裝可強迫呼叫端處理「設定尚未建立」的情境（例如系統剛部署、
 * 尚未跑過初始化腳本時，應回退到程式內建的預設值）。</p>
 */
public interface SystemConfigRepository extends MongoRepository<SystemConfig, String> {

    /**
     * 依設定鍵值查詢，例如 "rag.dessert-assistant"、"system.feature-flags"。
     */
    Optional<SystemConfig> findByConfigKey(String configKey);

    /**
     * 檢查某設定鍵值是否已存在，避免重複建立造成 unique index 衝突。
     */
    boolean existsByConfigKey(String configKey);

    /**
     * 刪除指定設定鍵值（後台維運用，日常業務邏輯應盡量避免刪除設定，改用更新）。
     */
    void deleteByConfigKey(String configKey);
}