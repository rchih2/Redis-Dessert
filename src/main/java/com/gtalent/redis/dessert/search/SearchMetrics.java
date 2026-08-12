package com.gtalent.redis.dessert.search;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 甜點搜尋（Elasticsearch）相關的業務指標，獨立於 {@code BusinessMetrics} 之外，
 * 因為這組指標只關注「搜尋這個子系統」的品質與效能，跟訂單/AI 業務指標關注點不同。
 *
 * <p>對應的 Grafana 面板規劃：</p>
 * <pre>
 * 搜尋總次數       dessert_search_total                每分鐘/每日搜尋量
 * 零命中搜尋次數   dessert_search_no_result_total       評估關鍵字覆蓋率、索引資料是否足夠
 * 搜尋耗時         dessert_search_duration_seconds      p95/p99 延遲，觀察 Elasticsearch 查詢效能
 * </pre>
 *
 * <p>這組屬於「AI 業務指標」相同層次的自訂業務指標（見技術文件「系統監控」小節），
 * 透過 Micrometer 累加後一樣會出現在 {@code /actuator/prometheus}，由 Prometheus scrape，
 * 跟 elasticsearch-exporter 提供的「Elasticsearch 叢集本身健不健康」指標是兩個不同層次，
 * 一個看「搜尋功能好不好用」，一個看「Elasticsearch 服務本身健不健康」。</p>
 */
@Component
@RequiredArgsConstructor
public class SearchMetrics {

    private final MeterRegistry meterRegistry;

    private static final String SEARCH_TOTAL = "dessert_search_total";
    private static final String SEARCH_NO_RESULT_TOTAL = "dessert_search_no_result_total";
    private static final String SEARCH_DURATION = "dessert_search_duration_seconds";

    /** 呼叫 Elasticsearch 前先啟動計時器 */
    public Timer.Sample startTimer() {
        return Timer.start(meterRegistry);
    }

    /**
     * 搜尋完成後呼叫一次：停止計時、累加搜尋總次數，若零命中則額外累加零命中計數。
     *
     * @param sample      {@link #startTimer()} 回傳的計時器
     * @param resultCount 這次搜尋回傳的筆數
     */
    public void recordSearch(Timer.Sample sample, int resultCount) {
        sample.stop(meterRegistry.timer(SEARCH_DURATION));
        meterRegistry.counter(SEARCH_TOTAL).increment();
        if (resultCount == 0) {
            meterRegistry.counter(SEARCH_NO_RESULT_TOTAL).increment();
        }
    }
}