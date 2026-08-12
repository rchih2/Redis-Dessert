package com.gtalent.redis.dessert.event;

import com.gtalent.redis.dessert.ai.model.ActionLog;
import com.gtalent.redis.dessert.ai.model.ChatMessageHistory;
import com.gtalent.redis.dessert.ai.repository.ActionLogRepository;
import com.gtalent.redis.dessert.ai.repository.ChatMessageHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 事件驅動架構的消費端：訂閱 order-events 與 ai-qa-events，
 * 把事件寫入既有的 MongoDB ActionLog / ChatMessageHistory collection。
 *
 * <p><b>簡化說明（誠實記錄的技術取捨）：</b>
 * 正規的作法通常是用 Kafka Connect + Mongo Sink Connector 做「事件 → 資料庫」的落地，
 * 這裡刻意改用最直接的 {@code @KafkaListener} + Repository.save()，
 * 是因為這是一個展示「事件驅動如何解決一致性/可觀測性問題」的作品集專案，
 * 優先確保程式碼可讀、可解釋、面試時講得清楚，而不是追求生產等級的落地方案。</p>
 *
 * <p><b>at-least-once 與去重：</b>
 * Kafka consumer 預設語意是 at-least-once（消費端 commit offset 之前若當機，
 * 訊息重啟後會被重新投遞一次），因此同一則事件理論上可能被處理兩次。
 * 這裡用 {@code eventKey} 欄位（訂單事件：orderId+eventType；AI 問答事件：sessionId+occurredAt）
 * 搭配 existsByEventKey() 做去重判斷。這不是絕對安全的去重（存在極小的
 * check-then-save 競態窗口），但足以應付本專案示範情境；
 * 生產環境建議改用資料庫層的唯一索引 + upsert，或改走 exactly-once 語意的
 * Kafka Streams / Transactional Producer 方案。</p>
 *
 * <p><b>與既有同步寫入的關係（AI 問答事件）：</b>
 * AiChatService 在收到 LLM 回覆的當下，已經「同步」把完整問答寫入了
 * ChatMessageHistory 與 ActionLog(AI_CHAT)。這裡透過 Kafka 事件另外寫入的
 * ChatMessageHistory 紀錄，內容較精簡（不含完整 aiResponse），且帶有
 * eventKey 可與前者區分，屬於刻意保留的「雙軌紀錄」，用來示範事件驅動的
 * 非同步稽核軌跡，而不是為了取代原本的同步寫入。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventLogConsumer {

    private final ActionLogRepository actionLogRepository;
    private final ChatMessageHistoryRepository chatMessageHistoryRepository;

    @KafkaListener(topics = EventPublisherService.ORDER_EVENTS_TOPIC, groupId = "event-log-consumer")
    public void consumeOrderEvent(OrderEvent event) {
        if (event == null || event.orderId() == null) {
            log.warn("[EventLogConsumer] 收到無效的 order-events 訊息，略過");
            return;
        }

        String eventKey = event.orderId() + ":" + event.eventType();

        if (actionLogRepository.existsByEventKey(eventKey)) {
            log.info("[EventLogConsumer] 偵測到重複投遞的 order-events 訊息，略過寫入。eventKey={}", eventKey);
            return;
        }

        try {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("orderId", event.orderId());
            details.put("eventType", event.eventType());
            details.put("payload", event.payload());
            details.put("occurredAt", event.occurredAt());
            details.put("source", "kafka-order-events");

            // 訂單事件目前沒有對應的登入使用者 ID 可歸屬（下單流程本身不需要登入），
            // 先用固定字串 "system" 佔位，方便之後查詢時篩出「事件驅動來源」的紀錄；
            // 若未來下單流程加上會員登入機制，這裡應該改帶入真實 userId。
            ActionLog actionLog = ActionLog.builder()
                    .userId("system")
                    .actionType(toActionType(event.eventType()))
                    .details(details)
                    .eventKey(eventKey)
                    .timestamp(LocalDateTime.now())
                    .build();

            actionLogRepository.save(actionLog);
            log.info("[EventLogConsumer] order-events 寫入 ActionLog 成功，eventKey={}", eventKey);
        } catch (Exception e) {
            log.error("[EventLogConsumer] 寫入 ActionLog 失敗，eventKey={}", eventKey, e);
        }
    }

    /**
     * 訂單事件類型 → ActionLog 操作類型的對應。
     *
     * <p>{@link OrderEventType#PAYMENT_COMPLETED} 與 {@link OrderEventType#STOCK_DEDUCTED}
     * 目前系統尚未有實際觸發點（見 {@link OrderEventType} 的類別註解），一旦未來真的
     * 開始發布，這裡會先落到 {@code OTHER}，避免因為忘記補上對應關係而讓消費端拋例外、
     * 卡住整個 consumer。</p>
     */
    private ActionLog.ActionType toActionType(OrderEventType eventType) {
        return switch (eventType) {
            case ORDER_CREATED -> ActionLog.ActionType.ORDER_CREATE;
            case ORDER_DELETED -> ActionLog.ActionType.ORDER_CANCEL;
            default -> ActionLog.ActionType.OTHER;
        };
    }

    @KafkaListener(topics = EventPublisherService.AI_QA_EVENTS_TOPIC, groupId = "event-log-consumer")
    public void consumeAiQaEvent(AiQaEvent event) {
        if (event == null || event.sessionId() == null) {
            log.warn("[EventLogConsumer] 收到無效的 ai-qa-events 訊息，略過");
            return;
        }

        String eventKey = event.sessionId() + ":" + event.occurredAt();

        if (chatMessageHistoryRepository.existsByEventKey(eventKey)) {
            log.info("[EventLogConsumer] 偵測到重複投遞的 ai-qa-events 訊息，略過寫入。eventKey={}", eventKey);
            return;
        }

        try {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("intent", event.intent());
            metadata.put("retrievedDocIds", event.retrievedDocIds());
            metadata.put("responseTimeMs", event.responseTimeMs());
            metadata.put("source", "kafka-ai-qa-events");

            ChatMessageHistory history = ChatMessageHistory.builder()
                    .userId(event.sessionId())
                    .sessionId(event.sessionId())
                    .userQuery(event.question())
                    // 刻意不重複存完整 aiResponse：完整回覆已由 AiChatService 同步寫入，
                    // 這裡只保留事件本身攜帶的意圖/延遲等統計資訊，見類別註解說明
                    .aiResponse(null)
                    .metadata(metadata)
                    .eventKey(eventKey)
                    .timestamp(LocalDateTime.now())
                    .build();

            chatMessageHistoryRepository.save(history);
            log.info("[EventLogConsumer] ai-qa-events 寫入 ChatMessageHistory 成功，eventKey={}", eventKey);
        } catch (Exception e) {
            log.error("[EventLogConsumer] 寫入 ChatMessageHistory 失敗，eventKey={}", eventKey, e);
        }
    }
}