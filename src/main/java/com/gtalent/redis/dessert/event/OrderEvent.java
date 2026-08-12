package com.gtalent.redis.dessert.event;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 訂單事件（發布到 Kafka topic：order-events）。
 *
 * <p>message key 固定使用 {@code orderId}（見 {@code EventPublisherService#publishOrderEvent}），
 * 確保同一張訂單的多個事件會落在同一個 partition，維持 partition 內的事件順序。</p>
 *
 * @param orderId    MySQL 訂單主鍵
 * @param eventType  事件類型
 * @param payload    事件詳細內容（彈性 Map，例如顧客資訊、金額、品項數）
 * @param occurredAt 事件發生時間
 */
public record OrderEvent(
        Long orderId,
        OrderEventType eventType,
        Map<String, Object> payload,
        LocalDateTime occurredAt
) {
}