package com.gtalent.redis.dessert.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

/**
 * Kafka 事件發布的統一入口（Service 層）。
 *
 * <p>設計原則：發送失敗絕對不能讓「下單」或「AI 問答」這兩個主要交易流程失敗——
 * 這裡的每個發送都用 {@code whenComplete} 非同步處理結果，失敗只記 log，
 * 不會往呼叫端拋例外，呼叫端（Controller / AiChatService）也額外包了一層
 * try-catch 保護，雙重確保 Kafka 掛掉不會波及主流程。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventPublisherService {

    public static final String ORDER_EVENTS_TOPIC = "order-events";
    public static final String AI_QA_EVENTS_TOPIC = "ai-qa-events";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    /**
     * 發布訂單事件。key 使用 orderId，保證同一訂單的事件在 partition 內有序。
     */
    public void publishOrderEvent(OrderEvent event) {
        if (event == null || event.orderId() == null) {
            log.warn("[EventPublisherService] 收到無效的 OrderEvent，略過發送");
            return;
        }
        String key = String.valueOf(event.orderId());
        try {
            kafkaTemplate.send(ORDER_EVENTS_TOPIC, key, event)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.error("[EventPublisherService] 發送 order-events 失敗，orderId={}, eventType={}",
                                    event.orderId(), event.eventType(), ex);
                        } else {
                            log.debug("[EventPublisherService] order-events 發送成功，orderId={}, partition={}, offset={}",
                                    event.orderId(),
                                    result.getRecordMetadata().partition(),
                                    result.getRecordMetadata().offset());
                        }
                    });
        } catch (Exception e) {
            // KafkaTemplate.send 本身回傳的是 Future，通常不會同步拋例外，
            // 這裡保留 try-catch 是為了保護萬一 producer 尚未初始化完成等邊界情況，
            // 確保「發送事件」這件事無論如何都不會讓下單交易失敗。
            log.error("[EventPublisherService] 呼叫 send() 發生非預期例外，orderId={}", event.orderId(), e);
        }
    }

    /**
     * 發布 AI 問答事件。key 使用 sessionId，保證同一場對話的事件在 partition 內有序。
     */
    public void publishAiQaEvent(AiQaEvent event) {
        if (event == null || event.sessionId() == null) {
            log.warn("[EventPublisherService] 收到無效的 AiQaEvent，略過發送");
            return;
        }
        String key = event.sessionId();
        try {
            kafkaTemplate.send(AI_QA_EVENTS_TOPIC, key, event)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.error("[EventPublisherService] 發送 ai-qa-events 失敗，sessionId={}", event.sessionId(), ex);
                        } else {
                            log.debug("[EventPublisherService] ai-qa-events 發送成功，sessionId={}, partition={}, offset={}",
                                    event.sessionId(),
                                    result.getRecordMetadata().partition(),
                                    result.getRecordMetadata().offset());
                        }
                    });
        } catch (Exception e) {
            log.error("[EventPublisherService] 呼叫 send() 發生非預期例外，sessionId={}", event.sessionId(), e);
        }
    }
}