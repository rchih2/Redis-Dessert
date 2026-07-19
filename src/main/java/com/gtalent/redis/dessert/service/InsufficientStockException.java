package com.gtalent.redis.dessert.service;

/**
 * 庫存不足（已售完或剩餘數量不夠扣減）時拋出。
 * 由 Controller 層攔截並回傳 409 Conflict，提示使用者「已售完」。
 */
public class InsufficientStockException extends RuntimeException {

    public InsufficientStockException(String message) {
        super(message);
    }

}