package com.gtalent.redis.dessert.ai.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * AI 顧問對話 / 關鍵字比對 / RAG 向量搜尋相關的業務指標，獨立於
 * {@link com.gtalent.redis.dessert.metrics.BusinessMetrics}（訂單/甜點業務）與
 * {@link com.gtalent.redis.dessert.search.SearchMetrics}（Elasticsearch 甜點搜尋）之外，
 * 因為這組指標只關注「AI 助手這個子系統」的使用量、穩定度與 RAG 檢索品質。
 *
 * <p>對應 Grafana「AI Assistant Dashboard」面板規劃：</p>
 * <pre>
 * AI 問答次數        ai_chat_total                 AI 使用率
 * AI 成功率          ai_chat_success_total          搭配 ai_chat_total 算 success/total
 * AI 回應時間        ai_chat_duration_seconds        p95/p99，使用者體驗
 * Gemini 呼叫次數    keyword_fallback_total          沒命中關鍵字規則才會真的呼叫 LLM，數字上等同 Gemini 呼叫次數
 * Token 使用量       ai_total_tokens                 API 成本監控
 * 向量搜尋延遲       vector_search_duration_seconds  向量搜尋效能
 * 平均 Similarity    vector_similarity_score          RAG 命中品質
 *
 * 已依需求移除：keyword_match_total、rag_search_total、vector_search_result_count
 * </pre>
 *
 * <p>跟 {@code BusinessMetrics} 一樣的設計理念：Metric 名稱集中定義在這一個檔案，
 * 各 Service（{@code AiChatService}）只呼叫語意明確的方法，不直接注入 {@link MeterRegistry}。</p>
 */
@Component
@RequiredArgsConstructor
public class AiMetrics {

    private final MeterRegistry meterRegistry;

// ============================================================
// AI 助手子系統的業務指標，對應 Grafana「AI Assistant Dashboard」
// ============================================================

    // 【AI 問答次數】每次 POST /api/ai/chat 進入主流程（驗證通過後）就 +1
// 用途：衡量 AI 顧問功能的使用率/流量，是這個 Dashboard 的基礎分母指標。
    private static final String AI_CHAT_TOTAL = "ai_chat_total";

    // 【AI 成功次數】對話成功產生回覆才 +1（拋例外不計入）
// 用途：搭配 AI_CHAT_TOTAL 算「成功率」= ai_chat_success_total / ai_chat_total，
// 用來衡量 AI 助手的穩定度（LLM 逾時、向量庫失敗都會反映在這個比率下降）。
    private static final String AI_CHAT_SUCCESS_TOTAL = "ai_chat_success_total";

    // 【AI 回應時間】Timer，涵蓋關鍵字比對 + 向量檢索 + LLM 呼叫的整體耗時
// 用途：Time Series 呈現 p95/p99 延遲，衡量使用者實際等待 AI 回覆的體驗。
    private static final String AI_CHAT_DURATION = "ai_chat_duration_seconds";

    // 【關鍵字沒命中次數（= Gemini 呼叫次數）】沒命中關鍵字規則、改走向量檢索 + LLM 時 +1
// 用途：因為沒命中一定會呼叫一次 Gemini Chat，這個數字同時代表「LLM 實際呼叫次數」，
// 是觀察 API 成本/用量的重要指標，不需要另外維護一個 Gemini 呼叫計數器。
    private static final String KEYWORD_FALLBACK_TOTAL = "keyword_fallback_total";

    // 【Token 用量】LLM 呼叫成功且回應帶有 usage 資訊時，累加這次呼叫的總 token 數
// 用途：Time Series 觀察 token 消耗趨勢，是 Gemini API 成本監控的核心依據；
// 注意：若某次回應沒有 usage 資訊會被跳過，長期觀察時可能有些微低估。
    private static final String AI_TOTAL_TOKENS = "ai_total_tokens";

    // 【向量搜尋延遲】Timer，涵蓋單次向量相似度搜尋（含降級情境）的耗時
// 用途：Time Series 觀察向量庫查詢效能，若延遲異常升高可能代表 MongoDB Atlas
// VectorStore 端出現效能瓶頸。
    private static final String VECTOR_SEARCH_DURATION = "vector_search_duration_seconds";

    // 【平均相似度分數】DistributionSummary，記錄每筆命中文件的 Document.getScore()
// 用途：0～1 區間的分佈統計，分數持續偏低/下滑時，代表就算有命中文件，
// 語意相關度也不夠高，值得回頭檢視 desserts.csv／faq.csv 的知識內容是否需要補充調整。
    private static final String VECTOR_SIMILARITY_SCORE = "vector_similarity_score";

    /** 一次 {@code POST /api/ai/chat} 請求進入主流程（sessionId/message 驗證通過後）時呼叫一次。 */
    public void recordChatStarted() {
        meterRegistry.counter(AI_CHAT_TOTAL).increment();
    }

    /** 呼叫 AI 對話流程前先啟動計時器，涵蓋關鍵字比對/向量檢索/LLM 呼叫的整體耗時。 */
    public Timer.Sample startChatTimer() {
        return Timer.start(meterRegistry);
    }

    /** 對話成功產生回覆時呼叫：停止計時、累加成功次數。 */
    public void recordChatSuccess(Timer.Sample sample) {
        sample.stop(meterRegistry.timer(AI_CHAT_DURATION));
        meterRegistry.counter(AI_CHAT_SUCCESS_TOTAL).increment();
    }

    /**
     * 對話流程拋出例外（LLM 呼叫失敗等）時呼叫：一樣停止計時，
     * 但不累加成功次數，讓 {@code ai_chat_success_total / ai_chat_total} 能反映真實成功率。
     */
    public void recordChatFailure(Timer.Sample sample) {
        sample.stop(meterRegistry.timer(AI_CHAT_DURATION));
    }

    /**
     * 關鍵字規則沒命中、改走向量檢索 + LLM 生成時呼叫。
     *
     * <p>因為目前流程沒命中關鍵字規則就一定會呼叫一次 Gemini Chat，
     * 這個計數同時也代表「Gemini 呼叫次數」，不需要另外維護第二個 Counter。</p>
     */
    public void recordKeywordFallback() {
        meterRegistry.counter(KEYWORD_FALLBACK_TOTAL).increment();
    }

    /**
     * LLM 呼叫成功且回應中帶有 token 用量統計時呼叫，累加總 token 數（prompt + completion）。
     *
     * @param totalTokens 這次 LLM 呼叫的總 token 數；小於等於 0 時略過不計。
     */
    public void recordTokens(long totalTokens) {
        if (totalTokens <= 0) {
            return;
        }
        meterRegistry.counter(AI_TOTAL_TOKENS).increment(totalTokens);
    }

    /** 呼叫向量資料庫相似度搜尋前先啟動計時器。 */
    public Timer.Sample startVectorSearchTimer() {
        return Timer.start(meterRegistry);
    }

    /**
     * 向量搜尋完成後呼叫一次（不論成功或降級為空清單都要呼叫，確保延遲/次數指標涵蓋失敗情境）：
     * 停止計時、累加 RAG 查詢次數、記錄命中筆數，並把每一筆命中文件的相似度分數
     * （{@link Document#getScore()}，部分向量庫實作可能不提供、為 {@code null} 時略過）
     * 記錄進 {@code vector_similarity_score} 分佈統計，供 Grafana 算平均 Similarity Score。
     *
     * @param sample 呼叫前由 {@link #startVectorSearchTimer()} 取得的計時器
     * @param hits   這次向量搜尋回傳的文件清單（可為空清單，不可為 {@code null}）
     */
    public void recordVectorSearch(Timer.Sample sample, List<Document> hits) {
        sample.stop(meterRegistry.timer(VECTOR_SEARCH_DURATION));

        for (Document hit : hits) {
            Double score = hit.getScore();
            if (score != null) {
                meterRegistry.summary(VECTOR_SIMILARITY_SCORE).record(score);
            }
        }
    }
}