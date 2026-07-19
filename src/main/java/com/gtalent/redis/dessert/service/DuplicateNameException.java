package com.gtalent.redis.dessert.service;

/**
 * 新增甜點時，名稱與現有品項重複時拋出。
 * 由 Controller 攔截並回傳 409 Conflict，提示使用者「名稱已重複」。
 */
public class DuplicateNameException extends RuntimeException {

    public DuplicateNameException(String message) {
        super(message);
    }

}