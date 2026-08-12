package com.gtalent.redis.dessert.controller;

import com.gtalent.redis.dessert.search.DessertSearchDocument;
import com.gtalent.redis.dessert.search.DessertSearchQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 甜點全文/模糊搜尋 API（Elasticsearch），與 {@link DessertOrderController} 的甜點 CRUD
 * 是不同的 Controller：CRUD 走 MySQL + Redis 單筆快取，這裡走 Elasticsearch 索引，
 * 兩者職責分開，互不影響。
 *
 * <p>v20 異動：移除 {@code POST /api/admin/search/reindex} 手動端點。
 * MySQL → Elasticsearch 的同步現在由兩層機制自動處理，不再需要人工觸發：
 * <ul>
 *   <li>即時同步：{@code DessertSearchIndexService} 在甜點 CRUD 當下同步寫入，
 *       失敗自動重試（見該類別的 {@code @Retryable}）。</li>
 *   <li>背景兜底：{@code DessertSearchSyncScheduler} 啟動時與定期（預設 5 分鐘）
 *       自動全量重建索引，確保就算即時同步重試後仍失敗，也會在下個週期自動修正。</li>
 * </ul>
 * </p>
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class DessertSearchController {

    private final DessertSearchQueryService dessertSearchQueryService;

    /**
     * 依關鍵字搜尋甜點，可選擇加上價格區間、是否僅顯示上架商品。
     * 範例：{@code GET /api/desserts/search?keyword=布朗尼&minPrice=50&maxPrice=200}
     */
    @GetMapping("/desserts/search")
    public ResponseEntity<Map<String, Object>> search(
            @RequestParam String keyword,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice,
            @RequestParam(defaultValue = "true") boolean enabledOnly) {

        List<DessertSearchDocument> results =
                dessertSearchQueryService.search(keyword, minPrice, maxPrice, enabledOnly);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("count", results.size());
        body.put("data", results);
        return ResponseEntity.ok(body);
    }
}