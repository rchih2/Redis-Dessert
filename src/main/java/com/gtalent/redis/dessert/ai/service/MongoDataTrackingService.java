package com.gtalent.redis.dessert.ai.service;

import com.gtalent.redis.dessert.ai.model.ActionLog;
import com.gtalent.redis.dessert.ai.model.ChatMessageHistory;
import com.gtalent.redis.dessert.ai.model.ProductReview;
import com.gtalent.redis.dessert.ai.model.SearchHistory;
import com.gtalent.redis.dessert.ai.repository.ActionLogRepository;
import com.gtalent.redis.dessert.ai.repository.ChatMessageHistoryRepository;
import com.gtalent.redis.dessert.ai.repository.ProductReviewRepository;
import com.gtalent.redis.dessert.ai.repository.SearchHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 數據日誌中心 / AI 助手資料寫入的統一入口。
 *
 * <p><b>與 MySQL 甜點業務對齊的原則：</b>
 * 這個 Service 只負責 MongoDB 端的「行為紀錄」，不觸碰任何 MySQL 交易邏輯。
 * 呼叫端（例如負責下單的 OrderService）在 MySQL 交易成功「之後」，
 * 才呼叫本 Service 寫入 ActionLog，並把 MySQL 訂單編號、dessertId 等
 * 放進 {@code details} Map 裡，讓兩邊資料可以透過這些欄位互相追查，
 * 而不需要（也不建議）在資料庫層面建立跨庫外鍵約束。</p>
 *
 * <p><b>同步 vs 非同步：</b>
 * - ActionLog / SearchHistory / ChatMessageHistory：屬於「附帶紀錄」，
 *   採用 {@code @Async}，不阻塞主要業務流程（例如下單、搜尋 API 的回應時間）。
 * - ProductReview：使用者提交評論後通常需要立即拿到結果做前端回饋（例如顯示「送出成功，待審核」），
 *   因此提供同步方法 {@code submitReview}；若呼叫端不需要立即結果，也提供 {@code submitReviewAsync}。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MongoDataTrackingService {

    private final ActionLogRepository actionLogRepository;
    private final SearchHistoryRepository searchHistoryRepository;
    private final ProductReviewRepository productReviewRepository;
    private final ChatMessageHistoryRepository chatMessageHistoryRepository;

    // ========================================================================
    // 1. ActionLog — 操作日誌（非同步，fire-and-forget）
    // ========================================================================

    /**
     * 記錄一筆操作日誌。
     *
     * <p>範例（下單）：呼叫端的 OrderService 在 MySQL 交易 commit 之後呼叫：
     * <pre>
     * mongoDataTrackingService.logAction(
     *     userId,
     *     ActionLog.ActionType.ORDER_CREATE,
     *     Map.of("orderId", mysqlOrderId, "dessertId", dessertId, "amount", totalAmount),
     *     request.getRemoteAddr()
     * );
     * </pre>
     * 其中 orderId 是 MySQL 那邊的訂單主鍵，之後若要查「這筆訂單的操作軌跡」，
     * 就可以拿 orderId 回頭在 ActionLog.details 裡搜尋。</p>
     *
     * @param userId     操作者使用者 ID
     * @param actionType 操作類型
     * @param details    彈性詳細內容，建議放入可與 MySQL 對齊的鍵值（如 orderId、dessertId）
     * @param ipAddress  來源 IP，可從 HttpServletRequest#getRemoteAddr() 取得
     */
    @Async("mongoLoggingExecutor")
    public void logAction(String userId, ActionLog.ActionType actionType,
                          Map<String, Object> details, String ipAddress) {
        try {
            ActionLog actionLog = ActionLog.builder()
                    .userId(userId)
                    .actionType(actionType)
                    .details(details)
                    .ipAddress(ipAddress)
                    .timestamp(LocalDateTime.now())
                    .build();
            actionLogRepository.save(actionLog);
        } catch (Exception e) {
            // 日誌寫入失敗不應影響主要業務流程，這裡只記錄錯誤，不往外拋。
            log.error("寫入 ActionLog 失敗，userId={}, actionType={}", userId, actionType, e);
        }
    }

    /**
     * 依使用者查詢最近操作日誌，供後台稽核或個人操作紀錄頁面使用。
     */
    public List<ActionLog> getRecentActions(String userId, int limit) {
        return actionLogRepository.findByUserIdOrderByTimestampDesc(userId, PageRequest.of(0, limit));
    }

    // ========================================================================
    // 2. SearchHistory — 搜尋紀錄（非同步）
    // ========================================================================

    /**
     * 記錄一筆搜尋行為。resultCount 建議直接帶入該次查詢（通常查的是 MySQL Dessert 表）
     * 實際回傳的筆數，resultCount = 0 即為「零結果搜尋」，可用於後續商品缺口分析。
     *
     * @param userId      搜尋者使用者 ID（訪客可傳裝置代碼 / 匿名 ID）
     * @param keyword     搜尋關鍵字
     * @param resultCount 該次搜尋在 MySQL 甜點資料表中查到的結果筆數
     */
    @Async("mongoLoggingExecutor")
    public void recordSearch(String userId, String keyword, Integer resultCount) {
        try {
            SearchHistory searchHistory = SearchHistory.builder()
                    .userId(userId)
                    .keyword(keyword)
                    .resultCount(resultCount)
                    .timestamp(LocalDateTime.now())
                    .build();
            searchHistoryRepository.save(searchHistory);
        } catch (Exception e) {
            log.error("寫入 SearchHistory 失敗，userId={}, keyword={}", userId, keyword, e);
        }
    }

    /**
     * 取得某使用者最近的搜尋關鍵字，可用來組裝 RAG 助手的 prompt 上下文
     * （例如「這位使用者最近搜尋過提拉米蘇、生乳捲，請優先推薦相關品項」）。
     */
    public List<SearchHistory> getRecentSearches(String userId, int limit) {
        return searchHistoryRepository.findByUserIdOrderByTimestampDesc(userId, PageRequest.of(0, limit));
    }

    // ========================================================================
    // 3. ProductReview — 商品評論（同步 + 提供非同步版本）
    // ========================================================================

    /**
     * 提交一筆商品評論（同步）。
     *
     * <p>dessertId 對齊方式：呼叫端（Controller / ReviewService）應先向 MySQL
     * 的 DessertRepository 確認該 dessertId 存在且狀態為「上架中」，
     * 確認通過後才呼叫本方法寫入 MongoDB，避免對已下架或不存在的商品留下評論。</p>
     *
     * <p>新評論預設 {@code approved = false}（見 ProductReview 的 @Builder.Default），
     * 需經過人工或自動審核機制（例如簡易字詞過濾、AI 內容審核）通過後才會被前台顯示。
     * 評分彙總查詢（平均星數 / 評論則數）目前未對外開放，相關統計邏輯仍保留在
     * ProductReviewRepository，供未來需要時使用。</p>
     *
     * @return 已儲存的評論（含產生的 id），可直接回傳給前端顯示「送出成功，審核中」。
     */
    public ProductReview submitReview(Long dessertId, String userId, Integer rating, String comment) {
        if (rating == null || rating < 1 || rating > 5) {
            throw new IllegalArgumentException("評分必須介於 1 到 5 之間");
        }
        ProductReview review = ProductReview.builder()
                .dessertId(dessertId)
                .userId(userId)
                .rating(rating)
                .comment(comment)
                .createdAt(LocalDateTime.now())
                .approved(false)
                .build();
        return productReviewRepository.save(review);
    }

    /**
     * 提交評論的非同步版本：若前端不需要等待寫入結果（例如已用樂觀 UI 顯示送出成功），
     * 可改用這個版本，回傳 CompletableFuture 讓呼叫端視需求決定是否等待。
     */
    @Async("mongoLoggingExecutor")
    public CompletableFuture<ProductReview> submitReviewAsync(Long dessertId, String userId,
                                                              Integer rating, String comment) {
        return CompletableFuture.completedFuture(submitReview(dessertId, userId, rating, comment));
    }

    // ========================================================================
    // 4. ChatMessageHistory — AI 對話紀錄（寫入非同步，讀取同步）
    // ========================================================================

    /**
     * 儲存一輪「使用者提問 → AI 回答」的對話紀錄。
     *
     * <p>寫入採非同步，因為這通常發生在「已經把 AI 回答回傳給使用者之後」，
     * 使用者不需要等待這筆歷史紀錄真的落地 MongoDB，才能看到 AI 的回答。</p>
     *
     * @param retrievedSourceIds RAG 檢索命中的知識來源 ID（例如甜點介紹文件 ID），
     *                           可用來追蹤這次回答依據了哪些資料，方便日後審核回答品質
     * @param tokenUsage         token 用量統計，例如 Map.of("promptTokens", 120, "completionTokens", 80)
     */
    @Async("mongoLoggingExecutor")
    public void saveChatMessage(String userId, String sessionId, String userQuery, String aiResponse,
                                String modelName, List<String> retrievedSourceIds,
                                Map<String, Integer> tokenUsage) {
        try {
            ChatMessageHistory chatMessageHistory = ChatMessageHistory.builder()
                    .userId(userId)
                    .sessionId(sessionId)
                    .userQuery(userQuery)
                    .aiResponse(aiResponse)
                    .modelName(modelName)
                    .retrievedSourceIds(retrievedSourceIds)
                    .tokenUsage(tokenUsage)
                    .timestamp(LocalDateTime.now())
                    .build();
            chatMessageHistoryRepository.save(chatMessageHistory);
        } catch (Exception e) {
            log.error("寫入 ChatMessageHistory 失敗，userId={}, sessionId={}", userId, sessionId, e);
        }
    }

    /**
     * 取出某場對話的完整歷史（依時間正序），用於讓 ChatClient 重建多輪對話記憶。
     * Controller 呼叫 AI 助手前，先用這個方法把歷史訊息組成 Prompt 的 context。
     */
    public List<ChatMessageHistory> getConversationHistory(String sessionId) {
        return chatMessageHistoryRepository.findBySessionIdOrderByTimestampAsc(sessionId);
    }

    /**
     * 取得某使用者最近的對話紀錄列表，用於「歷史對話」列表頁（依 session 分組顯示於前端）。
     */
    public List<ChatMessageHistory> getRecentConversations(String userId, int limit) {
        Pageable pageable = PageRequest.of(0, limit);
        return chatMessageHistoryRepository.findByUserIdOrderByTimestampDesc(userId, pageable);
    }
}