package com.gtalent.redis.dessert.ai.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 系統與 RAG 相關的彈性設定檔。
 *
 * <p>採用 {@code Map<String, Object>} 儲存實際設定內容，讓維運人員或後台
 * 可以在不改 Schema、不重新部署的情況下調整 AI 助手行為，
 * 例如 RAG 檢索的 top-K 數量、System Prompt、溫度參數、功能開關等。</p>
 *
 * <p>{@code configKey} 建議設計成唯一識別碼（unique index），
 * 例如 {@code "rag.dessert-assistant"}、{@code "system.feature-flags"}，
 * 方便依用途分類存取不同設定群組。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "system_configs")
public class SystemConfig {

    @Id
    private String id;

    /**
     * 設定鍵值，作為此設定群組的唯一識別碼。
     * unique = true：避免同一個 configKey 被重複建立造成設定衝突。
     */
    @Indexed(unique = true)
    @Field("config_key")
    private String configKey;

    /**
     * 實際設定內容，彈性 JSON 結構。
     * 範例（RAG 設定）：
     * <pre>
     * {
     *   "topK": 5,
     *   "similarityThreshold": 0.75,
     *   "systemPrompt": "你是甜點店的專屬智慧助手...",
     *   "temperature": 0.7,
     *   "enableChatMemory": true
     * }
     * </pre>
     */
    @Field("settings")
    private Map<String, Object> settings;

    /** 設定描述，方便後台管理人員辨識用途。 */
    @Field("description")
    private String description;

    /** 最後更新時間，搭配 Spring Data Auditing（@EnableMongoAuditing）自動維護。 */
    @LastModifiedDate
    @Field("updated_at")
    private LocalDateTime updatedAt;

    /** 最後更新者（後台操作人員 ID），用於稽核追蹤。 */
    @Field("updated_by")
    private String updatedBy;
}