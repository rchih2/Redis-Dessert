package com.gtalent.redis.dessert.search;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 自動修復 MySQL 與 Elasticsearch 之間的資料落差，取代原本的手動
 * {@code POST /api/admin/search/reindex} 端點。
 *
 * <p>背景：{@link DessertSearchIndexService} 在甜點 CRUD 時是即時同步寫入 Elasticsearch，
 * 就算已經加上重試（見該類別），極端情況下（例如 Elasticsearch 整段時間都無法連線、
 * 或應用程式在同步完成前就被關閉）還是可能漏掉部分異動。</p>
 *
 * <p>這個排程把「補齊落差」變成自動化，不需要人工介入：
 * <ul>
 *   <li>應用程式啟動、所有 Bean 就緒後，先做一次全量同步（處理索引本來是空的、
 *       或上次關機期間 Elasticsearch 沒收到的異動）。</li>
 *   <li>之後每隔固定時間（預設 5 分鐘，可用 {@code app.search.sync.interval-ms} /
 *       環境變數 {@code APP_SEARCH_SYNC_INTERVAL_MS} 調整）自動重跑一次，
 *       持續把 MySQL 目前「未刪除」的資料覆蓋寫回 Elasticsearch，確保就算中途漏同步，
 *       最多在下一個週期內就會自動修正。</li>
 * </ul>
 * MySQL 永遠是唯一真實來源，這裡只是定期把 Elasticsearch 這份「可被重建的副本」
 * 拉回跟 MySQL 一致。可用 {@code app.search.sync.enabled=false} 整組關閉
 * （例如單元測試環境不需要背景排程）。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.search.sync", name = "enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class DessertSearchSyncScheduler {

    private final DessertSearchIndexService dessertSearchIndexService;

    /** 應用程式啟動完成後，立刻自動跑一次全量同步，不用等第一個排程週期。 */
    @EventListener(ApplicationReadyEvent.class)
    public void syncOnStartup() {
        runSync("啟動時自動同步");
    }

    /**
     * 週期性自動同步。用 fixedDelay（而非 fixedRate）：
     * 一定要等上一次同步真的跑完才會排下一次，避免 Elasticsearch 回應變慢時
     * 排程互相疊加、對 ES 造成額外壓力。
     */
    @Scheduled(
            initialDelayString = "${app.search.sync.interval-ms:300000}",
            fixedDelayString = "${app.search.sync.interval-ms:300000}"
    )
    public void syncPeriodically() {
        runSync("定期自動同步");
    }

    private void runSync(String trigger) {
        try {
            int count = dessertSearchIndexService.reindexAll();
            log.info("[{}] MySQL → Elasticsearch 索引同步完成，共 {} 筆", trigger, count);
        } catch (Exception e) {
            // 同步失敗（例如 Elasticsearch 暫時無法連線）只記錄錯誤，
            // 不讓排程執行緒掛掉，下一個週期會自動再試一次
            log.error("[{}] MySQL → Elasticsearch 索引同步失敗，將於下個週期自動重試", trigger, e);
        }
    }
}