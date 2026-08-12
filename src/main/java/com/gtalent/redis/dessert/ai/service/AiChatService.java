package com.gtalent.redis.dessert.ai.service;

import com.gtalent.redis.dessert.ai.dto.ChatResponseDTO;
import com.gtalent.redis.dessert.ai.exception.AiChatException;
import com.gtalent.redis.dessert.ai.message.keyword.KeywordChatService;
import com.gtalent.redis.dessert.ai.message.keyword.KeywordRule;
import com.gtalent.redis.dessert.ai.model.ActionLog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import io.micrometer.core.instrument.Timer;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import com.gtalent.redis.dessert.event.AiQaEvent;
import com.gtalent.redis.dessert.event.EventPublisherService;
import com.gtalent.redis.dessert.metrics.BusinessMetrics;
import com.gtalent.redis.dessert.ai.metrics.AiMetrics;
/**
 * 甜點 AI 顧問對話服務。
 *
 * 流程（新增關鍵字直接比對後的完整版本）：
 *   0. 先用 {@link KeywordChatService} 做關鍵字包含比對
 *      命中 -> 直接採用 CSV 匯入的固定答案作為 context，跳過向量檢索
 *      沒命中 -> 才進入原本的向量檢索流程（第 1 步）
 *   1. 對使用者訊息做向量相似度檢索，撈出相關甜點 RAG 文件
 *   2. 有命中 -> 組裝「知識庫 context + 使用者問題」的 Prompt
 *      沒命中 -> 使用預設的 fallback Prompt，避免 LLM 憑空編造品項
 *   3. 呼叫 ChatClient 取得回覆
 *   4. 非同步把「對話紀錄 / 搜尋紀錄 / 操作日誌」寫入 MongoDB，失敗不影響主回應
 *
 * <p>關鍵字命中時仍然會呼叫 LLM（而不是直接回傳 CSV 原文），
 * 是為了維持語氣一致、也讓 LLM 能視情況做語句上的自然銜接；
 * 但因為 context 已經鎖定為固定答案，LLM 幾乎不會偏離這個內容——
 * 這比向量檢索多了一層「答案完全可控」的保障。
 * 若你希望連 LLM 都跳過、直接回傳 CSV 原文（更快、更省 token），
 * 可以參考本檔案結尾的替代寫法註解。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiChatService {

    private final VectorStore vectorStore;
    private final ChatClient chatClient;
    private final MongoDataTrackingService mongoDataTrackingService;
    private final KeywordChatService keywordChatService;
    private final EventPublisherService eventPublisherService;
    private final BusinessMetrics businessMetrics;
    private final AiMetrics aiMetrics;

    @Value("${spring.ai.google.genai.chat.options.model:unknown}")
    private String modelName;

    @Value("${app.rag.chat.top-k:4}")
    private int topK;

    @Value("${app.rag.chat.similarity-threshold:0.5}")
    private double similarityThreshold;

    private static final String SYSTEM_PROMPT_WITH_CONTEXT = """
            你是甜點店的專屬 AI 顧問，請根據下方「甜點知識庫內容」回答使用者的問題。
            回答時盡量具體推薦品項，語氣親切、簡潔，避免無關的長篇大論。
            若知識庫內容不足以完整回答，請誠實告知，絕對不要編造菜單上不存在的品項或價格。

            甜點知識庫內容：
            ---
            {context}
            ---
            """;

    private static final String SYSTEM_PROMPT_FALLBACK = """
            你是甜點店的專屬 AI 顧問。目前沒有在知識庫中檢索到與使用者問題直接相關的內容，
            請根據一般甜點常識給予禮貌且籠統的回覆，並具體建議使用者換個問法或直接洽詢客服。
            切勿編造菜單上不存在的品項、價格或庫存資訊。
            """;

    /**
     * 主要對話入口。
     *
     * @param sessionId 對話 session 識別碼（用於串接歷史紀錄與側錄）
     * @param message   使用者輸入的原始問題
     */
    public ChatResponseDTO chat(String sessionId, String message) {
        if (!StringUtils.hasText(sessionId) || !StringUtils.hasText(message)) {
            throw new IllegalArgumentException("sessionId 與 message 皆為必填");
        }

        // AI 業務指標：進入主流程即計入一次「AI 問答次數」，並開始量測整體回應時間
        // （涵蓋關鍵字比對／向量檢索／LLM 呼叫），供 Grafana「AI Assistant Dashboard」使用。
        aiMetrics.recordChatStarted();
        Timer.Sample chatTimer = aiMetrics.startChatTimer();

        try {
            List<Document> hits = resolveHits(message);
            boolean ragHit = !hits.isEmpty();

            String systemPrompt = ragHit
                    ? buildContextPrompt(hits)
                    : SYSTEM_PROMPT_FALLBACK;
            // 新增：量測 LLM 呼叫耗時，用於填入 ai-qa-events 的 responseTimeMs 欄位，
            // 這是「AI 業務指標」（Gemini API 呼叫延遲）的資料來源之一，
            // 跟 Prometheus 量的是系統層級指標（API p99、5xx 等）是不同層次的觀測。
            long startTime = System.currentTimeMillis();
            String reply = callLlm(systemPrompt, message, sessionId);
            long responseTimeMs = System.currentTimeMillis() - startTime;

            // 側錄寫入 MongoDB；就算失敗也不應該讓使用者拿不到 AI 回覆
            recordSideEffects(sessionId, message, reply, ragHit, hits, responseTimeMs);

            // 業務指標：AI 顧問成功產生一次回覆，計入「AI 推薦次數」
            businessMetrics.recordAiRecommend();
            // 對話成功：停止計時、累加 ai_chat_success_total，讓 success/total 能算出成功率
            aiMetrics.recordChatSuccess(chatTimer);

            return ChatResponseDTO.builder()
                    .sessionId(sessionId)
                    .reply(reply)
                    .ragHit(ragHit)
                    .contextDocCount(hits.size())
                    .timestamp(LocalDateTime.now())
                    .build();
        } catch (RuntimeException e) {
            // 對話失敗（例如 LLM 呼叫失敗）：一樣要停止計時，但不計入成功次數
            aiMetrics.recordChatFailure(chatTimer);
            throw e;
        }
    }

    /**
     * 新增的分流邏輯：先關鍵字比對，沒命中才走向量檢索。
     *
     * <p>關鍵字命中時，把 CSV 規則的答案包裝成一個「假的」單筆 Document，
     * metadata 標記 source=keyword-rule，方便之後在 ChatMessageHistory 的
     * retrievedSourceIds 或除錯時分辨這次回覆是走哪條路徑產生的。</p>
     */
    private List<Document> resolveHits(String message) {
        Optional<KeywordRule> keywordHit = keywordChatService.match(message);

        if (keywordHit.isPresent()) {
            KeywordRule rule = keywordHit.get();
            log.info("[AiChatService] 關鍵字比對命中，category={}，跳過向量檢索", rule.getCategory());

            Document keywordDocument = Document.builder()
                    .text(rule.getAnswer())
                    .metadata(Map.of(
                            "source", "keyword-rule",
                            "category", rule.getCategory() == null ? "" : rule.getCategory()
                    ))
                    .build();
            return List.of(keywordDocument);
        }

        // AI 業務指標：關鍵字沒命中，改走向量檢索 + LLM 生成，
        // 這個計數同時也代表「Gemini 呼叫次數」（見 AiMetrics#recordKeywordFallback 說明）
        aiMetrics.recordKeywordFallback();
        return safeSimilaritySearch(message);
    }

    private String buildContextPrompt(List<Document> hits) {
        String context = hits.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n---\n"));

        return new PromptTemplate(SYSTEM_PROMPT_WITH_CONTEXT)
                .render(Map.of("context", context));
    }

    private List<Document> safeSimilaritySearch(String message) {
        // AI 業務指標：量測這次向量檢索的耗時、命中筆數與相似度分數
        Timer.Sample vectorSearchTimer = aiMetrics.startVectorSearchTimer();
        try {
            SearchRequest searchRequest = SearchRequest.builder()
                    .query(message)
                    .topK(topK)
                    .similarityThreshold(similarityThreshold)
                    .build();

            List<Document> results = vectorStore.similaritySearch(searchRequest);
            List<Document> hits = results == null ? List.of() : results;
            aiMetrics.recordVectorSearch(vectorSearchTimer, hits);
            return hits;
        } catch (Exception e) {
            // 向量庫查詢失敗不應該讓整個對話掛掉，降級用 fallback prompt 繼續走完流程；
            // 指標一樣要記錄（耗時 + 零命中），才能反映真實的失敗延遲與命中率
            aiMetrics.recordVectorSearch(vectorSearchTimer, List.of());
            log.warn("[AiChatService] VectorStore 相似度檢索失敗，改用 fallback prompt，message={}", message, e);
            return List.of();
        }
    }

    private String extractSourceId(Document document) {
        if (document.getMetadata() != null) {
            Object dessertId = document.getMetadata().get("dessertId");
            if (dessertId != null) {
                return dessertId.toString();
            }
            // 關鍵字命中的規則沒有 dessertId，改用 category 當作可追蹤的來源標記
            Object source = document.getMetadata().get("source");
            if ("keyword-rule".equals(source)) {
                return "keyword-rule:" + document.getMetadata().get("category");
            }
        }
        return document.getId();
    }

    private String callLlm(String systemPrompt, String userMessage, String sessionId) {
        try {
            ChatResponse chatResponse = chatClient.prompt()
                    .system(systemPrompt)
                    .user(userMessage)
                    .call()
                    .chatResponse();

            String content = chatResponse == null || chatResponse.getResult() == null
                    ? null
                    : chatResponse.getResult().getOutput().getText();

            if (!StringUtils.hasText(content)) {
                throw new AiChatException("LLM 回傳空回覆");
            }

            // AI 業務指標：Token 使用量（成本監控）。部分模型/情境可能不回傳 usage 資訊，
            // 讀取失敗只記 debug log，不影響已經取得的回覆內容。
            recordTokenUsage(chatResponse);

            return content;
        } catch (AiChatException e) {
            throw e;
        } catch (Exception e) {
            log.error("[AiChatService] LLM 呼叫失敗, sessionId={}", sessionId, e);
            throw new AiChatException("AI 服務暫時無法回應，請稍後再試", e);
        }
    }

    /**
     * 從 {@link ChatResponse#getMetadata()} 讀取這次 LLM 呼叫的 token 用量（prompt + completion），
     * 累加進 {@code ai_total_tokens}。不同模型／情境下 usage 資訊可能缺漏，
     * 讀取過程刻意用 try-catch 包起來，避免因為 metadata 結構差異影響到已經產生的 AI 回覆。
     */
    private void recordTokenUsage(ChatResponse chatResponse) {
        try {
            if (chatResponse == null || chatResponse.getMetadata() == null) {
                return;
            }
            Usage usage = chatResponse.getMetadata().getUsage();
            if (usage != null && usage.getTotalTokens() != null) {
                aiMetrics.recordTokens(usage.getTotalTokens());
            }
        } catch (Exception e) {
            log.debug("[AiChatService] 讀取 token usage 失敗，略過本次 token 計量", e);
        }
    }

    private void recordSideEffects(String sessionId, String userMessage, String aiReply,
                                   boolean ragHit, List<Document> hits, long responseTimeMs) {
        List<String> sourceIds = hits.stream()
                .map(this::extractSourceId)
                .filter(StringUtils::hasText)
                .toList();

        Map<String, Integer> tokenUsage = Map.<String, Integer>of();

        try {
            mongoDataTrackingService.saveChatMessage(
                    sessionId,
                    sessionId,
                    userMessage,
                    aiReply,
                    modelName,
                    sourceIds,
                    tokenUsage
            );
        } catch (Exception e) {
            log.error("[AiChatService] 寫入 ChatMessageHistory 失敗, sessionId={}", sessionId, e);
        }

        try {
            mongoDataTrackingService.recordSearch(sessionId, userMessage, hits.size());
        } catch (Exception e) {
            log.error("[AiChatService] 寫入 SearchHistory 失敗, sessionId={}", sessionId, e);
        }

        try {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("question", userMessage);
            detail.put("ragHit", ragHit);
            detail.put("hitCount", hits.size());
            detail.put("sourceIds", sourceIds);
            detail.put("replyLength", aiReply == null ? 0 : aiReply.length());
            mongoDataTrackingService.logAction(sessionId, ActionLog.ActionType.AI_CHAT, detail, null);
        } catch (Exception e) {
            log.error("[AiChatService] 寫入 ActionLog 失敗, sessionId={}", sessionId, e);
        }

        // 新增：發布 ai-qa-events 事件到 Kafka，供 EventLogConsumer 非同步落地成
        // 另一份稽核紀錄，同時作為「事件驅動架構」的展示資料來源。
        // 發送失敗只記 log，不影響已經回傳給使用者的 AI 回覆。
        try {
            String intent = resolveIntent(hits, ragHit);
            eventPublisherService.publishAiQaEvent(new AiQaEvent(
                    sessionId,
                    userMessage,
                    intent,
                    sourceIds,
                    responseTimeMs,
                    LocalDateTime.now()
            ));
        } catch (Exception e) {
            log.error("[AiChatService] 發布 ai-qa-events 失敗, sessionId={}", sessionId, e);
        }
    }

    /**
     * 新增：把「關鍵字命中 / 向量檢索命中 / 皆未命中」轉成給 ai-qa-events 用的 intent 字串，
     * 沿用 resolveHits() 裡標記的 metadata.source 判斷依據，不重複實作比對邏輯。
     */
    private String resolveIntent(List<Document> hits, boolean ragHit) {
        if (!ragHit) {
            return "fallback";
        }
        boolean keywordHit = hits.stream()
                .anyMatch(d -> d.getMetadata() != null && "keyword-rule".equals(d.getMetadata().get("source")));
        return keywordHit ? "keyword-rule" : "rag";
    }
    /*
     * ============================================================================
     * 替代寫法（可選）：關鍵字命中時完全跳過 LLM，直接回傳 CSV 原文
     * ============================================================================
     *
     * 優點：回應速度更快（省了一次 LLM 呼叫）、答案 100% 等於 CSV 內容，不會被 LLM 改寫走樣。
     * 缺點：語氣可能跟其他由 LLM 生成的回覆不太一致（例如少了開場白、結尾語氣）。
     *
     * 若要採用，把 chat() 方法開頭改成：
     *
     * public ChatResponseDTO chat(String sessionId, String message) {
     *     if (!StringUtils.hasText(sessionId) || !StringUtils.hasText(message)) {
     *         throw new IllegalArgumentException("sessionId 與 message 皆為必填");
     *     }
     *
     *     Optional<KeywordRule> keywordHit = KeywordChatService.match(message);
     *     if (keywordHit.isPresent()) {
     *         String answer = keywordHit.get().getAnswer();
     *         recordSideEffects(sessionId, message, answer, true, List.of());
     *         return ChatResponseDTO.builder()
     *                 .sessionId(sessionId)
     *                 .reply(answer)
     *                 .ragHit(true)
     *                 .contextDocCount(1)
     *                 .timestamp(LocalDateTime.now())
     *                 .build();
     *     }
     *
     *     // 其餘沿用原本流程 ...
     * }
     */
}