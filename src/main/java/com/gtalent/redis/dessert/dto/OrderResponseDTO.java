package com.gtalent.redis.dessert.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 訂單回應 DTO，GET（查詢）與 POST（建立訂單）共用同一份格式：
 *
 * - GET /api/orders、GET /api/orders/{id}：只會用到 id / customerName / phone / lineId /
 *   totalAmount / orderTime / items，success / message / subtotal / shippingFee 一律為 null，
 *   搭配 @JsonInclude(NON_NULL)，序列化時會直接省略這幾個欄位，查詢回應維持乾淨、不受影響。
 * - POST /api/orders：success / message / subtotal / shippingFee 都會帶實際值，
 *   用來回報下單結果（成功訊息）與金額明細（未稅小計、運費），取代原本手動組裝的 Map。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OrderResponseDTO(
        Boolean success,
        String message,
        Long id,
        String customerName,
        String phone,
        String lineId,
        BigDecimal subtotal,
        BigDecimal shippingFee,
        BigDecimal totalAmount,
        LocalDateTime orderTime,
        List<OrderItemResponseDTO> items
) {

    /**
     * 查詢用的簡化建構子，維持原本 GET 端點呼叫端的既有寫法不必修改。
     * success / message / subtotal / shippingFee 一律填 null，
     * 交由 @JsonInclude(NON_NULL) 在序列化時自動省略。
     */
    public OrderResponseDTO(Long id, String customerName, String phone, String lineId,
                            BigDecimal totalAmount, LocalDateTime orderTime,
                            List<OrderItemResponseDTO> items) {
        this(null, null, id, customerName, phone, lineId, null, null, totalAmount, orderTime, items);
    }
}