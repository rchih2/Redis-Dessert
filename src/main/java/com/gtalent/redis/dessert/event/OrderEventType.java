package com.gtalent.redis.dessert.event;

/**
 * 訂單事件類型。
 *
 * <p>目前系統的下單流程（{@code OrderServiceImpl#createOrder}）是「建立訂單 + 扣庫存」
 * 在同一個 MySQL 交易內同步完成，並沒有獨立的付款步驟，因此目前只會實際發布 {@link #ORDER_CREATED}
 * 與 {@link #ORDER_DELETED}。{@link #PAYMENT_COMPLETED} 與 {@link #STOCK_DEDUCTED} 先保留列舉值，
 * 是為了展示「事件驅動架構」在未來拆分成獨立付款服務 / 獨立庫存服務時的擴充彈性，
 * 目前尚未有對應的觸發點，誠實記錄在此，避免誤以為系統已經支援分散式付款流程。</p>
 */
public enum OrderEventType {
    ORDER_CREATED,
    PAYMENT_COMPLETED,
    STOCK_DEDUCTED,

    /**
     * 訂單被取消。對應 {@code OrderServiceImpl#softDelete}／{@code softDeleteAll} 的「軟刪除」，
     * 也就是只把 {@code Order.deleted} 標記為 true，不是資料庫層的實體刪除（DELETE）。
     */
    ORDER_DELETED
}