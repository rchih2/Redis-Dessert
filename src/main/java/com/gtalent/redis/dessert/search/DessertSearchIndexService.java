package com.gtalent.redis.dessert.search;

import com.gtalent.redis.dessert.model.Dessert;
import com.gtalent.redis.dessert.repository.DessertRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 負責把 MySQL {@code dessert} 表的異動同步寫進 Elasticsearch 索引。
 *
 * <p>同步策略（v20 起，兩層機制，取代原本手動的 {@code POST /api/admin/search/reindex}）：
 * <ul>
 *   <li><b>即時同步</b>：甜點 {@code create}/{@code update}/{@code delete}/{@code deleteAll}/{@code purge}
 *       發生當下就呼叫 {@link #index}/{@link #remove}/{@link #removeAll}，失敗時用
 *       {@code @Retryable} 自動重試最多 3 次（間隔 500ms → 1s → 2s 遞增），
 *       處理 Elasticsearch 短暫抖動、暫時逾時這類瞬時錯誤。</li>
 *   <li><b>背景兜底</b>：重試仍失敗、或當下應用程式沒在跑（例如部署空窗期漏掉的異動），
 *       由 {@link DessertSearchSyncScheduler} 定期呼叫 {@link #reindexAll()} 全量重建索引，
 *       確保最終一致。</li>
 * </ul>
 * 不論哪一層，MySQL 都是唯一真實來源（source of truth），Elasticsearch 只是可被重建的
 * 搜尋副本；這裡的重試/排程都不會讓甜點 CRUD 主流程失敗或回滾。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DessertSearchIndexService {

    private final DessertSearchRepository dessertSearchRepository;
    private final DessertRepository dessertRepository;

    /** 新增/更新甜點後呼叫，把最新內容寫回 Elasticsearch；失敗自動重試。 */
    @Retryable(
            retryFor = Exception.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 500, multiplier = 2)
    )
    public void index(Dessert dessert) {
        dessertSearchRepository.save(toDocument(dessert));
    }

    /** {@link #index} 重試 3 次仍失敗時的最終手段：記錄警告，交給排程兜底。 */
    @Recover
    public void recoverIndex(Exception e, Dessert dessert) {
        log.warn("寫入 Elasticsearch 索引重試 3 次仍失敗，dessertId={}，將由定期同步排程自動補齊",
                dessert.getId(), e);
    }

    /** 軟刪除/實體刪除甜點後呼叫，把它從搜尋索引移除；失敗自動重試。 */
    @Retryable(
            retryFor = Exception.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 500, multiplier = 2)
    )
    public void remove(Long id) {
        dessertSearchRepository.deleteById(id);
    }

    @Recover
    public void recoverRemove(Exception e, Long id) {
        log.warn("刪除 Elasticsearch 索引重試 3 次仍失敗，dessertId={}，將由定期同步排程自動補齊", id, e);
    }

    /** 批次刪除多筆甜點後呼叫（對應 {@code DELETE /api/desserts}）；失敗自動重試。 */
    @Retryable(
            retryFor = Exception.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 500, multiplier = 2)
    )
    public void removeAll(List<Long> ids) {
        dessertSearchRepository.deleteAllById(ids);
    }

    @Recover
    public void recoverRemoveAll(Exception e, List<Long> ids) {
        log.warn("批次刪除 Elasticsearch 索引重試 3 次仍失敗，ids={}，將由定期同步排程自動補齊", ids, e);
    }

    /**
     * 全量重建索引：清空目前的 dessert index，從 MySQL 目前「未刪除」的資料重新寫入。
     * v20 起改由 {@link DessertSearchSyncScheduler} 自動排程呼叫（啟動時 + 定期），
     * 不再對外開放手動觸發的 API。
     *
     * @return 實際寫入 Elasticsearch 的筆數
     */
    public int reindexAll() {
        dessertSearchRepository.deleteAll();
        List<Dessert> all = dessertRepository.findByDeletedFalse();
        List<DessertSearchDocument> docs = all.stream().map(this::toDocument).toList();
        dessertSearchRepository.saveAll(docs);
        log.info("Elasticsearch 全量重建索引完成，共 {} 筆", docs.size());
        return docs.size();
    }

    private DessertSearchDocument toDocument(Dessert dessert) {
        return DessertSearchDocument.builder()
                .id(dessert.getId())
                .name(dessert.getName())
                .price(dessert.getPrice())
                .stock(dessert.getStock())
                .enabled(dessert.getEnabled())
                .build();
    }
}