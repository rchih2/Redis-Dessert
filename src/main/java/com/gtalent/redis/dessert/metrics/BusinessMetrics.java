package com.gtalent.redis.dessert.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 業務指標（Business Metrics）統一入口。
 *
 * <p>跟 Actuator 自動蒐集的系統指標（API 延遲、5xx 錯誤率、JVM 記憶體等）不同，
 * 這裡的指標是「這次商業行為發生了幾次」，例如下單、取消訂單、AI 推薦、商品銷售量、目前庫存。
 * 這些數字 Micrometer 不會自動幫你算，需要在商業邏輯的關鍵節點手動呼叫這個元件累加，
 * 才會出現在 {@code /actuator/prometheus} 的輸出裡，進而被 Prometheus scrape 到。</p>
 *
 * <p>統一集中在這個元件、而不是讓各 Controller/Service 直接注入 {@link MeterRegistry}，
 * 是為了：
 * <ul>
 *   <li>Metric 名稱、tag 名稱只在一個地方定義，避免各處手打字串打錯字造成同名指標對不起來</li>
 *   <li>之後要調整指標定義（例如改 tag、加 Timer）只要改這一個檔案</li>
 * </ul>
 * </p>
 *
 * <p>對應的 Grafana「Dessert Business Dashboard」面板規劃：</p>
 * <pre>
 * 今日營業額     order_amount_total                  每日營收（Counter，累加訂單總金額）
 * 今日訂單數     order_created_total                  訂單量（Counter）
 * 熱門甜點 Top10 dessert_order_total{dessertId,dessertName}  熱門商品排行（Counter，依品項累加銷售數量）
 * 甜點庫存       dessert_inventory{dessertId,dessertName}    即時庫存（Gauge）
 * </pre>
 *
 * <p>已依需求移除的指標：{@code dessert_order_cancel_total}、
 * {@code order_average_amount}、{@code dessert_low_stock_total}、
 * {@code dessert_sales_amount}。</p>
 *
 * <p>命名調整說明：原本「訂單建立次數」是用 {@code dessert_order_total}（無 tag），
 * 現在依照新版 Grafana 面板規劃改名為 {@code order_created_total}，
 * 把 {@code dessert_order_total} 這個名稱讓給「依品項分組的熱門甜點排行」使用
 * （原本叫 {@code dessert_product_total}），避免同一個 metric 名稱被兩種不同語意
 * （一個無 tag 的總數、一個依 dessertId/dessertName 分組的計數）搶用，
 * 這在 Prometheus 是不允許的（同名 metric 的 type/語意必須一致）。</p>
 */
@Component
@RequiredArgsConstructor
public class BusinessMetrics {

    private final MeterRegistry meterRegistry;

    /**
     * 目前註冊過 Gauge 的甜點庫存數值，key 為 dessertId，用於之後更新同一個 Gauge 的數值。
     */
    private final Map<Long, AtomicInteger> stockGaugeValues = new ConcurrentHashMap<>();
    // 注意：不可命名為 "order_created_total"！
    // Prometheus/OpenMetrics 規範裡 "_created" 是 Counter 的保留後綴（代表該時間序列的建立時間戳），
    // Micrometer 底層的 Prometheus client 在正規化 Counter 名稱時，會先剝掉結尾的 "_total"，
    // 若剝完後仍以 "_created" 結尾會再剝一次，最後才補回 "_total"——
    // 也就是 "order_created_total" 實際註冊出來會變成 "order_total"，
    // 跟程式碼裡的常數字面值完全對不起來，非常容易誤判成「跑到舊版程式」。
    // ============================================================
// 甜點 / 訂單本業的業務指標，對應 Grafana「Dessert Business Dashboard」
// ============================================================
// 【今日訂單數】訂單建立成功時 +1（Counter，無 tag）
// 用途：統計「有多少筆訂單被成功建立」，是 Dashboard 上最基本的訂單量指標。
// 命名提醒：不可叫 "order_created_total"，會被 Prometheus 正規化成 order_total（見上方註解）。
    private static final String ORDER_CREATED_TOTAL = "order_new_total";

    // 【今日營業額】訂單建立成功時，累加該筆訂單的含運費總金額（Counter）
// 用途：Grafana Stat 面板顯示「每日營收」，用 rate() 或 increase() 算區間營收。
    private static final String ORDER_AMOUNT_TOTAL = "order_amount_total";

    // 【AI 推薦次數】AI 對話成功產生回覆時累加（不分關鍵字命中/RAG命中/fallback）
// 用途：業務面統計「AI 顧問成功服務了幾次」，跟 AiMetrics 的 ai_chat_total 是不同層次
// （這裡算「業務事件」，AiMetrics 算「AI 子系統本身的使用量/延遲」）。
    private static final String AI_RECOMMEND_TOTAL = "dessert_ai_recommend_total";

    // 【熱門甜點 Top 10（依銷售數量）】依 dessertId/dessertName 分組，累加購買數量
// 用途：Bar Chart 呈現「哪個品項賣最多份」，找出熱門商品。
    private static final String PRODUCT_TOTAL = "dessert_order_total";

    // 【即時庫存】Gauge，依 dessertId/dessertName 分組，反映甜點目前庫存數量
// 用途：Table / Bar Gauge 呈現目前庫存水位，庫存變動（新增/修改/扣庫存）時即時更新數值。
    private static final String DESSERT_INVENTORY = "dessert_inventory";

    // 【Redis 快取命中率】新增，對應技術文件第 9 節「建議後續工作」第 8 項：
    // 原本 DessertServiceImpl.getById() 只有 log.debug("Cache hit/miss")，
    // 沒有接上任何 Micrometer 指標，Prometheus/Grafana 上看不到命中率曲線。
    // 用「同一個 Counter + result tag（hit/miss）」而不是兩個獨立 Counter 名稱，
    // 是為了在 Grafana 上可以直接用一條 PromQL 算出命中率：
    //   sum(rate(dessert_cache_access_total{result="hit"}[5m]))
    //     / sum(rate(dessert_cache_access_total[5m]))
    private static final String DESSERT_CACHE_ACCESS_TOTAL = "dessert_cache_access_total";


    /**
     * 訂單建立成功時呼叫一次。
     *
     * @param totalAmount 這筆訂單的含運費總金額；用來累加「今日營業額」，
     *                    為 {@code null} 時只計次數、不計金額（理論上不會發生，多做一層防呆）。
     */
    public void recordOrderCreated(BigDecimal totalAmount) {
        meterRegistry.counter(ORDER_CREATED_TOTAL).increment();

        if (totalAmount != null) {
            meterRegistry.counter(ORDER_AMOUNT_TOTAL).increment(totalAmount.doubleValue());
        }
    }

    /**
     * AI 對話服務成功產生一次回覆（不論是關鍵字命中、RAG 命中或 fallback）時呼叫。
     *
     * <p>目前定義為「只要 AI 顧問成功回應一次，就算一次 AI 推薦」，這是較寬鬆的定義；
     * 如果之後想收斂成「只有真的檢索到知識庫內容才算推薦」，可以在呼叫端改成只在
     * {@code ragHit == true} 時才呼叫這個方法即可，這個方法本身不需要改。</p>
     *
     * <p>跟新增的 {@code com.gtalent.redis.dessert.ai.metrics.AiMetrics}（{@code ai_chat_total} 等）
     * 是不同層次的指標：這裡算的是「業務上算不算一次推薦」，AiMetrics 算的是「AI 助手子系統
     * 的使用量／成功率／延遲」，兩者並存、互不取代。</p>
     */
    public void recordAiRecommend() {
        meterRegistry.counter(AI_RECOMMEND_TOTAL).increment();
    }

    /**
     * 訂單內每一筆品項成立時呼叫，依商品 id / 名稱分別累加銷售數量，用於「熱門甜點 Top 10」排行。
     *
     * <p>用 {@code dessertId} 與 {@code dessertName} 兩個 tag 一起標記，是因為 4.5 節提到
     * 甜點目前允許改價、名稱建立後唯讀但仍可能有極少數舊資料或個案需要對照，
     * 用 id 當唯一鍵、name 純粹方便在 Grafana 上直接看到品名，不用另外查表。</p>
     *
     * <p>⚠️ 基數（cardinality）提醒：Prometheus 的 tag 組合數不能無限增長。
     * 甜點品項數量有限（屬於「有界」的維度）沒有問題；但絕對不要把這個方法拿去
     * 用使用者 id、訂單 id 這種無界（unbounded）的值當 tag，否則會讓 Prometheus
     * 的時序資料量爆炸。</p>
     *
     * <p>（技術文件第 9 節「建議後續工作」第 15 項：原本這裡還有第 4 個參數
     * {@code amount}，是 v14 為了 {@code dessert_sales_amount}（依金額分組的
     * 高營收甜點排行）新增的；v16 移除該指標後，這個參數已不再被讀取，
     * 屬於死碼，這次一併把參數從方法簽章、呼叫端（{@code OrderServiceImpl}）
     * 與既有單元測試中拿掉，徹底清除。若之後真的需要依金額分組的排行，
     * 屆時再重新設計、重新加回相對應的參數即可。）</p>
     *
     * @param dessertId   甜點 id
     * @param dessertName 甜點名稱（下單當下的快照即可，不需要額外查詢最新名稱）
     * @param quantity    這筆品項的購買數量
     */
    public void recordProductSold(Long dessertId, String dessertName, int quantity) {
        if (quantity <= 0) {
            return;
        }
        String safeName = dessertName == null ? "unknown" : dessertName;

        meterRegistry.counter(PRODUCT_TOTAL,
                        "dessertId", String.valueOf(dessertId),
                        "dessertName", safeName)
                .increment(quantity);
    }

    /**
     * 甜點庫存異動時呼叫（新增、修改、扣庫存成功後），更新對應的 {@code dessert_inventory} Gauge。
     *
     * <p>實作方式：每個 dessertId 只註冊一次 Gauge（用 {@link AtomicInteger} 當底層數值來源），
     * 之後只更新這個 AtomicInteger 的值，Micrometer 會在 Prometheus 每次 scrape 時即時讀取，
     * 不需要每次庫存變動都重新註冊 Gauge。</p>
     *
     * <p>⚠️ 已知限制：甜點被軟刪除（{@code delete()} / {@code deleteAll()}）時，
     * 目前不會移除對應的 Gauge，該品項的庫存數字會維持刪除前的最後一次數值，
     * 不會消失也不會自動歸零。如果之後想讓已刪除品項的庫存立刻從 Grafana 面板上消失，
     * 可以在刪除流程呼叫 {@link MeterRegistry#remove(io.micrometer.core.instrument.Meter)}
     * 移除對應 Gauge，目前為求簡單先不處理。</p>
     *
     * @param dessertId   甜點 id，為 {@code null} 時直接略過
     * @param dessertName 甜點名稱
     * @param stock       目前庫存數量
     */
    public void updateInventory(Long dessertId, String dessertName, Integer stock) {
        if (dessertId == null) {
            return;
        }
        int stockValue = stock == null ? 0 : stock;
        String safeName = dessertName == null ? "unknown" : dessertName;

        AtomicInteger gaugeValue = stockGaugeValues.computeIfAbsent(dessertId, id -> {
            AtomicInteger initial = new AtomicInteger(stockValue);
            Gauge.builder(DESSERT_INVENTORY, initial, AtomicInteger::get)
                    .tag("dessertId", String.valueOf(id))
                    .tag("dessertName", safeName)
                    .register(meterRegistry);
            return initial;
        });
        gaugeValue.set(stockValue);
    }

    /**
     * {@code DessertServiceImpl.getById()} 命中 Redis 快取（不需查 MySQL）時呼叫一次。
     * 對應技術文件第 9 節「建議後續工作」第 8 項：把原本只有文字 log 的快取命中/未命中
     * 行為，接上 Micrometer Counter，讓命中率可以進 Grafana。
     */
    public void recordCacheHit() {
        meterRegistry.counter(DESSERT_CACHE_ACCESS_TOTAL, "result", "hit").increment();
    }

    /**
     * {@code DessertServiceImpl.getById()} 未命中 Redis 快取（改查 MySQL）時呼叫一次。
     * 與 {@link #recordCacheHit()} 搭配使用，見上方常數欄位的 PromQL 範例。
     */
    public void recordCacheMiss() {
        meterRegistry.counter(DESSERT_CACHE_ACCESS_TOTAL, "result", "miss").increment();
    }
}