package com.gtalent.redis.dessert.event;

import java.time.LocalDateTime;
import java.util.List;

/**
 * AI 問答事件（發布到 Kafka topic：ai-qa-events）。
 *
 * <p>message key 固定使用 {@code sessionId}，讓同一場對話的多輪問答落在同一個 partition。</p>
 *
 * <p>刻意不攜帶完整的 AI 回覆內容（aiResponse）：完整問答內容已經由
 * {@code AiChatService} 直接同步寫入 MongoDB 的 ChatMessageHistory / ActionLog，
 * 這裡的事件只用來示範「事件驅動」的稽核軌跡與延遲/意圖統計，
 * 縮小事件酬載大小、避免把 LLM 回覆內容重複塞進 Kafka 訊息裡。</p>
 *
 * @param sessionId        對話場次 ID
 * @param question         使用者原始提問
 * @param intent           意圖分類："keyword-rule"（關鍵字命中）、"rag"（向量檢索命中）、"fallback"（皆未命中）
 * @param retrievedDocIds  本次 RAG 檢索命中的知識來源 ID 清單
 * @param responseTimeMs   本次 LLM 呼叫（Gemini API）耗時（毫秒）
 * @param occurredAt       事件發生時間
 */
public record AiQaEvent(
        String sessionId,
        String question,
        String intent,
        List<String> retrievedDocIds,
        long responseTimeMs,
        LocalDateTime occurredAt
) {
}