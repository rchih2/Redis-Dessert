package com.gtalent.redis.dessert.search;

import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.query.Criteria;
import org.springframework.data.elasticsearch.core.query.CriteriaQuery;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * 甜點搜尋（Elasticsearch）查詢面，供 {@code GET /api/desserts/search} 使用。
 *
 * <p>用 {@code ElasticsearchOperations} + {@code CriteriaQuery} 組裝查詢，
 * 而不是直接用 Spring Data 方法名稱衍生查詢，是因為這裡需要動態組合
 * 「關鍵字模糊比對 + 價格區間 + 是否僅顯示上架商品」這幾個可有可無的條件，
 * {@code Criteria} API 比較適合這種條件是選擇性的組裝場景。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DessertSearchQueryService {

    private final ElasticsearchOperations elasticsearchOperations;
    private final SearchMetrics searchMetrics;

    /**
     * 依關鍵字（對 name 做模糊/全文比對）搜尋甜點，可選擇加上價格區間與是否只看上架商品。
     *
     * @param keyword     搜尋關鍵字，會對 name 欄位做 fuzzy 比對（容許少量錯字/變體）
     * @param minPrice    最低價格（含），為 null 代表不限制
     * @param maxPrice    最高價格（含），為 null 代表不限制
     * @param enabledOnly 是否只回傳目前上架（enabled = true）的商品
     */
    public List<DessertSearchDocument> search(String keyword, BigDecimal minPrice, BigDecimal maxPrice, boolean enabledOnly) {
        Timer.Sample sample = searchMetrics.startTimer();

        Criteria criteria = new Criteria("name").matches(keyword);

        if (minPrice != null) {
            criteria = criteria.and(new Criteria("price").greaterThanEqual(minPrice));
        }
        if (maxPrice != null) {
            criteria = criteria.and(new Criteria("price").lessThanEqual(maxPrice));
        }
        if (enabledOnly) {
            criteria = criteria.and(new Criteria("enabled").is(true));
        }

        CriteriaQuery query = new CriteriaQuery(criteria);

        try {
            SearchHits<DessertSearchDocument> hits =
                    elasticsearchOperations.search(query, DessertSearchDocument.class);
            List<DessertSearchDocument> results = hits.stream().map(SearchHit::getContent).toList();
            searchMetrics.recordSearch(sample, results.size());
            return results;
        } catch (RuntimeException e) {
            // 查詢失敗（例如 Elasticsearch 暫時無法連線）也記錄一次耗時與「零命中」，
            // 讓 Grafana 上的搜尋延遲/命中率曲線不會因為例外被靜默漏記，
            // 但例外本身仍往上拋，交由 Controller 層決定如何回應使用者
            searchMetrics.recordSearch(sample, 0);
            log.warn("Elasticsearch 搜尋失敗，keyword={}", keyword, e);
            throw e;
        }
    }
}