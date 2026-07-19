package com.gtalent.redis.dessert.ai.repository;

import com.gtalent.redis.dessert.ai.model.ActionLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * ActionLog 的 Repository（數據日誌中心核心）。
 *
 * <p>用於稽核追蹤（誰在什麼時候做了什麼操作）與異常行為分析。
 * details 欄位是彈性 Map，實務上下單（ORDER_CREATE）時常會塞入 MySQL 訂單編號、
 * dessertId 等，作為跨資料庫（MySQL 訂單 / MongoDB 日誌）事後追查的關聯依據。</p>
 */
public interface ActionLogRepository extends MongoRepository<ActionLog, String> {

    /**
     * 依使用者查詢操作日誌，依時間倒序，對應複合索引 user_time_idx。
     */
    List<ActionLog> findByUserIdOrderByTimestampDesc(String userId, Pageable pageable);

    /**
     * 依操作類型查詢（例如只看所有下單行為），對應複合索引 type_time_idx。
     */
    List<ActionLog> findByActionTypeOrderByTimestampDesc(ActionLog.ActionType actionType, Pageable pageable);

    /**
     * 查詢某使用者特定類型的操作日誌，例如「這個使用者所有的下單紀錄」。
     */
    List<ActionLog> findByUserIdAndActionTypeOrderByTimestampDesc(String userId, ActionLog.ActionType actionType);

    /**
     * 查詢某 IP 在時間區間內的所有操作，用於異常行為 / 灌單偵測。
     */
    List<ActionLog> findByIpAddressAndTimestampBetweenOrderByTimestampDesc(
            String ipAddress, LocalDateTime from, LocalDateTime to);

    /**
     * 統計某使用者在時間區間內特定操作類型的次數，
     * 例如短時間內下單次數過多可觸發風控告警。
     */
    long countByUserIdAndActionTypeAndTimestampBetween(
            String userId, ActionLog.ActionType actionType, LocalDateTime from, LocalDateTime to);
}