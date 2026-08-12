package com.gtalent.redis.dessert.ai.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * AI 問答助手的對話紀錄。
 *
 * <p>每一筆代表一次「使用者提問 → AI 回答」的完整互動，除了原始問答內容之外，
 * 也保留了向量檢索（RAG）過程用到的相關 metadata（例如引用了哪些知識來源、
 * 使用的模型與 token 用量），方便後續分析、除錯與對話記憶延續。</p>
 *
 * <p>{@code sessionId} 用來把同一場對話的多輪訊息串在一起（多輪對話記憶），
 * {@code userId} 則用來查詢某個使用者的歷史對話。兩者都建立索引以加速查詢。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "chat_message_history")
@CompoundIndexes({
        @CompoundIndex(name = "session_time_idx", def = "{'sessionId': 1, 'timestamp': 1}"),
        @CompoundIndex(name = "user_time_idx", def = "{'userId': 1, 'timestamp': -1}")
})
public class ChatMessageHistory {

    @Id
    private String id;

    /** 發問的使用者 ID（若為訪客可為匿名代碼）。 */
    @Indexed
    @Field("user_id")
    private String userId;

    /** 對話場次 ID，用於串連同一段多輪對話（AI 記憶延續依此分組）。 */
    @Indexed
    @Field("session_id")
    private String sessionId;

    /** 使用者原始提問內容。 */
    @Field("user_query")
    private String userQuery;

    /** AI 助手回覆內容。 */
    @Field("ai_response")
    private String aiResponse;

    /** 本次互動發生時間。 */
    @Indexed
    @CreatedDate
    @Field("timestamp")
    private LocalDateTime timestamp;

    /** 使用的 AI 模型名稱，例如 gpt-4o-mini、llama3.1。 */
    @Field("model_name")
    private String modelName;

    /**
     * 提問向量化後的 embedding（用於相似度檢索 / 對話語意分析）。
     * 若向量檢索交由 MongoDB Atlas Vector Search 的獨立 collection 管理，
     * 此欄位可留空，僅在需要「同時保存問答與其向量」時使用。
     */
    @Field("query_embedding")
    private List<Double> queryEmbedding;

    /**
     * 本次 RAG 檢索命中的知識來源 ID 清單（例如甜點介紹文件、FAQ 條目 ID），
     * 用來追蹤 AI 回答的依據，方便日後審核回答品質或除錯「幻覺」問題。
     */
    @Field("retrieved_source_ids")
    private List<String> retrievedSourceIds;

    /** token 用量統計，例如 {"promptTokens": 120, "completionTokens": 80}。 */
    @Field("token_usage")
    private Map<String, Integer> tokenUsage;

    /** 其他彈性擴充欄位（例如使用者評分、回應延遲毫秒數等）。 */
    @Field("metadata")
    private Map<String, Object> metadata;

    /**
     * 事件去重識別碼（僅由 EventLogConsumer 寫入，組合方式：{sessionId}:{occurredAt}）。
     * 用途同 ActionLog.eventKey，見該處說明。
     */
    @Indexed
    @Field("event_key")
    private String eventKey;
}