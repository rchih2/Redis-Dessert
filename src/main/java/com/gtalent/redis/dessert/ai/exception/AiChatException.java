package com.gtalent.redis.dessert.ai.exception;

/**
 * AI 對話流程中的不可恢復錯誤（例如 ChatClient 呼叫 LLM 失敗）。
 * 與 IllegalArgumentException（參數錯誤 / 400）區分，讓 Controller 可以回不同的 HTTP status。
 */
public class AiChatException extends RuntimeException {

    public AiChatException(String message, Throwable cause) {
        super(message, cause);
    }

    public AiChatException(String message) {
        super(message);
    }
}