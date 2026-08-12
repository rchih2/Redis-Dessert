package com.gtalent.redis.dessert.service;

import com.gtalent.redis.dessert.model.Dessert;

import java.util.List;
import java.util.Map;

public interface DessertService {

    /**
     * 新增甜點
     */
    Dessert create(Dessert dessert);

    /**
     * 查詢所有甜點清單（直接查 MySQL，不經過 Redis 單筆快取）
     */
    List<Dessert> findAll();

    /**
     * 依 ID 查詢甜點（Cache Aside：先查 Redis，沒有再查 MySQL 並回寫快取）
     */
    Dessert getById(Long id);

    /**
     * 更新甜點資訊，並清除對應的 Redis 快取
     */
    Dessert update(Long id, Dessert dessert);

    /**
     * 刪除單一甜點，並清除對應的 Redis 快取
     */
    void delete(Long id);

    /**
     * 刪除全部甜點，並清除全部相關的 Redis 快取
     */
    void deleteAll();

    /**
     * 下單扣庫存：以資料庫層級的原子 UPDATE 執行「檢查庫存 + 扣減」，
     * 確保高併發下單時不會扣出負庫存，也不會讓賣完的品項繼續被下單。
     * 扣減成功後會一併清除該品項的 Redis 快取，避免快取殘留舊庫存數字。
     *
     * @throws InsufficientStockException 品項不存在或庫存不足時拋出
     */
    void deductStock(Long id, Integer quantity);

    /**
     * 【管理用】實體刪除甜點，繞過軟刪除機制，直接把資料列從 MySQL 刪除。
     *
     * <p>與 {@link #delete(Long)}（軟刪除）不同，這支方法會：</p>
     * <ol>
     *   <li>直接呼叫 Repository 刪除資料列（不經過 {@code deleted} 標記）</li>
     *   <li>清除對應的 Redis 快取（key: {@code dessert:item:{id}}）</li>
     *   <li>從 Elasticsearch 搜尋索引移除，讓搜尋結果不再出現已刪除品項</li>
     * </ol>
     *
     * <p>對應技術文件第 9 節「建議後續工作」第 6 項：原本 Controller 直接呼叫
     * {@code DessertRepository.deleteById()}，不會清快取，導致實體刪除後 Redis
     * 快取 TTL 到期前仍可能查到舊資料。改成呼叫這支方法後，「刪資料 + 清快取 +
     * 清索引」三件事由 Service 層統一處理。</p>
     *
     * <p>用於清理測試資料或徹底移除錯誤資料；正式環境使用前務必另外加上
     * 管理員權限檢查（見技術文件第 8、9 節）。</p>
     *
     * @throws jakarta.persistence.EntityNotFoundException 找不到對應 id 的甜點時拋出
     */
    void purge(Long id);

    /**
     * 【管理用】批次實體刪除甜點，繞過軟刪除機制（寬鬆模式）。
     *
     * <p>逐一嘗試刪除每個 id：找不到對應甜點的 id 會被記錄為失敗原因並跳過，
     * 不影響其他 id 的刪除；成功刪除的品項會清除對應 Redis 快取，
     * 並統一從 Elasticsearch 搜尋索引批次移除。</p>
     *
     * @param ids           欲刪除的甜點 id 清單
     * @param resetSequence 是否在刪除完成後重置 AUTO_INCREMENT 為 1（讓下一筆新增從 1 開始）。
     *                       ⚠️ 僅建議在確定資料庫沒有任何歷史訂單殘留參照這些 id 時使用，
     *                       因為 OrderItem.dessertId 是快照、沒有外鍵約束，重置後若新增的甜點
     *                       id 剛好撞上舊訂單快照引用過的 id，會造成「查歷史訂單看到的品項名稱
     *                       跟現在同 id 的甜點對不上」的混淆。
     * @return 包含 successCount / failedCount / failedIds 的結果 Map，
     *         格式對齊 {@link com.gtalent.redis.dessert.service.DessertCsvImportService} 的回應風格
     */
    Map<String, Object> purgeAll(List<Long> ids, boolean resetSequence);
}