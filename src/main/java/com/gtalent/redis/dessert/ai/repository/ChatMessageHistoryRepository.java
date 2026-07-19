package com.gtalent.redis.dessert.ai.repository;

import com.gtalent.redis.dessert.ai.model.ChatMessageHistory;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

/**
 * ChatMessageHistory 的 Repository。
 *
 * <p>對應 AI 問答助手的多輪對話紀錄。查詢主要圍繞兩個維度：
 * 依 sessionId 取出「同一場對話」的完整脈絡（餵給 ChatClient 做記憶延續），
 * 以及依 userId 取出「某使用者」的歷史對話（用於後台審核 / 個人化推薦）。</p>
 */
public interface ChatMessageHistoryRepository extends MongoRepository<ChatMessageHistory, String> {

    /**
     * 依 sessionId 依時間正序取出對話紀錄，用於重建對話上下文餵給 LLM。
     * 對應 ChatMessageHistory 上的複合索引 session_time_idx。
     */
    List<ChatMessageHistory> findBySessionIdOrderByTimestampAsc(String sessionId);

    /**
     * 依 userId 依時間倒序分頁查詢，用於「我的 AI 對話紀錄」列表頁。
     * 對應複合索引 user_time_idx。
     */
    List<ChatMessageHistory> findByUserIdOrderByTimestampDesc(String userId, Pageable pageable);

    /**
     * 清除某場對話（例如使用者手動「清空對話」或會話逾時回收）。
     */
    long deleteBySessionId(String sessionId);

    /**
     * 統計某使用者總共發問幾次，可作為後台儀表板指標或 rate-limit 判斷依據。
     */
    long countByUserId(String userId);
}