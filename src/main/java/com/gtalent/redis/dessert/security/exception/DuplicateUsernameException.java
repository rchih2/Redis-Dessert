package com.gtalent.redis.dessert.security.exception;

/** 註冊時帳號已存在，比照 DuplicateNameException 的設計，統一由 GlobalExceptionHandler 轉成 409 */
public class DuplicateUsernameException extends RuntimeException {
    public DuplicateUsernameException(String message) {
        super(message);
    }
}