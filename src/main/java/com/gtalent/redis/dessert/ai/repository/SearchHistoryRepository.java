package com.gtalent.redis.dessert.ai.repository;

import com.gtalent.redis.dessert.ai.model.SearchHistory;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * SearchHistory 的 Repository。
 *
 * <p>用途：(1) 依使用者取出最近搜尋紀錄，可餵給 RAG 助手當作「使用者近期關注品項」的上下文；
 * (2) 依關鍵字彙總搜尋熱度，作為熱門關鍵字 / 零結果搜尋分析的資料來源。</p>
 */
public interface SearchHistoryRepository extends MongoRepository<SearchHistory, String> {

    /**
     * 依使用者 ID 查詢最近搜尋紀錄，依時間倒序。
     * 常見用法：Controller 傳入 PageRequest.of(0, 10) 只取最近 10 筆。
     */
    List<SearchHistory> findByUserIdOrderByTimestampDesc(String userId, Pageable pageable);

    /**
     * 取某使用者「最近 N 筆」搜尋紀錄的簡便版本（不需自行組 Pageable）。
     * 適合直接餵給 AI 助手做 prompt 上下文組裝。
     */
    List<SearchHistory> findTop10ByUserIdOrderByTimestampDesc(String userId);

    /**
     * 依關鍵字模糊查詢（忽略大小寫）搜尋紀錄，依時間倒序，
     * 可用於「其他人也搜尋過類似關鍵字」的分析。
     */
    List<SearchHistory> findByKeywordContainingIgnoreCaseOrderByTimestampDesc(String keyword, Pageable pageable);

    /**
     * 統計某關鍵字在指定時間區間內被搜尋的次數，用於熱門關鍵字排行榜。
     */
    long countByKeywordIgnoreCaseAndTimestampBetween(String keyword, LocalDateTime from, LocalDateTime to);

    /**
     * 找出「零結果搜尋」紀錄（resultCount = 0），是潛在的商品缺口 / 缺貨線索，
     * 可提供給營運人員參考是否要新增甜點品項。
     */
    List<SearchHistory> findByResultCountOrderByTimestampDesc(Integer resultCount, Pageable pageable);
}