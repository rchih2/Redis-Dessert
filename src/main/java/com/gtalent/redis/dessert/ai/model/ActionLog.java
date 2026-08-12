package com.gtalent.redis.dessert.ai.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 使用者操作日誌（數據日誌中心）。
 *
 * <p>記錄使用者在系統中的關鍵行為，例如下單、查詢、登入等，
 * 用於稽核追蹤、異常行為分析、或未來作為 AI 助手的行為脈絡參考資料。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "action_logs")
@CompoundIndexes({
        @CompoundIndex(name = "user_time_idx", def = "{'userId': 1, 'timestamp': -1}"),
        @CompoundIndex(name = "type_time_idx", def = "{'actionType': 1, 'timestamp': -1}")
})
public class ActionLog {

    /**
     * 操作類型列舉。若未來有更多動作類型，直接在此擴充即可，
     * 不會影響既有文件的向下相容性（MongoDB 為 Schema-less）。
     */
    public enum ActionType {
        ORDER_CREATE,   // 下單
        ORDER_CANCEL,   // 取消訂單（軟刪除，非實體刪除）
        ORDER_QUERY,    // 查詢訂單
        DESSERT_QUERY,  // 查詢甜點
        LOGIN,          // 登入
        AI_CHAT,        // 使用 AI 問答助手
        OTHER
    }

    @Id
    private String id;

    /** 執行操作的使用者 ID。 */
    @Indexed
    @Field("user_id")
    private String userId;

    /** 操作類型，例如「下單」「查詢」。 */
    @Indexed
    @Field("action_type")
    private ActionType actionType;

    /** 操作詳細內容，彈性 Map 結構以容納不同操作類型的差異化欄位。 */
    @Field("details")
    private Map<String, Object> details;

    /** 來源 IP 位址，用於異常行為偵測與稽核。 */
    @Field("ip_address")
    private String ipAddress;

    /** 操作發生時間。 */
    @Indexed
    @Field("timestamp")
    private LocalDateTime timestamp;

    /**
     * 事件去重識別碼（僅由 EventLogConsumer 寫入，組合方式：{orderId}:{eventType}）。
     *
     * <p>Kafka 是 at-least-once 語意，同一則訊息可能被重複投遞，消費端用這個欄位
     * 搭配 {@code ActionLogRepository#existsByEventKey} 判斷「這個事件是不是已經處理過」，
     * 避免同一筆訂單事件被寫入兩次稽核紀錄。既有由 AiChatService 直接同步寫入的
     * ActionLog（AI_CHAT）不會帶這個欄位（維持 null），因此可以用它來分辨
     * 「這筆紀錄是走 Kafka 事件流進來的、還是原本的同步寫入」。</p>
     */
    @Indexed
    @Field("event_key")
    private String eventKey;
}