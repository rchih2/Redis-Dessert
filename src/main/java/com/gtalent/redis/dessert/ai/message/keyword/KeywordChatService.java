package com.gtalent.redis.dessert.ai.message.keyword;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * 整合「keyword-rules 讀寫」「關鍵字比對」「向量搜尋回答」的單一服務。
 *
 * <p>持久化策略（MySQL 版本）：規則存在 {@code keyword_rule} / {@code keyword_rule_keyword}
 * 兩張表，透過 {@link KeywordRuleRepository} 讀寫。不再依賴外部檔案系統路徑、
 * 不再需要 volume 掛載，持久化交給 MySQL 本身處理。</p>
 *
 * <p><b>種子資料（Seed）：</b>啟動時如果資料庫是空的（例如第一次啟動、或
 * {@code docker compose down -v} 把 volume 清空重建），會自動從
 * {@code classpath:rag/keyword-rules.csv}（對應 {@code src/main/resources/rag/keyword-rules.csv}）
 * 載入一份預設規則寫進資料庫，避免每次重建環境都要手動呼叫上傳 API 才有基本規則可用。
 * 這個種子邏輯只在「資料庫目前完全沒有規則」時才會執行一次；只要資料庫裡已經有資料
 * （不論是種子寫入的，還是後續透過 API 上傳蓋掉的），之後開機都不會再被 classpath 檔案覆蓋，
 * 避免蓋掉使用者透過 {@link #reload(InputStream)} 上傳的最新版本。</p>
 *
 * <p>比對邏輯仍然維持「記憶體快取」，不是每次使用者傳訊息都查一次資料庫：
 * 開機時把整張表載入 {@link AtomicReference} 快取一次，之後只有在
 * {@link #reload(InputStream)} 上傳新規則時才重新整批寫入資料庫並刷新快取。</p>
 *
 * <p>對外只留一個入口 {@link #answer(String)}：
 * <pre>
 * 使用者訊息
 *   -> 關鍵字命中？ -- 是 --> 直接回傳固定答案（不呼叫 LLM，秒回、答案可控）
 *                    -- 否 --> 向量搜尋取回相關知識 -> 組 prompt -> 呼叫 LLM 生成回答
 * </pre></p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KeywordChatService {

    private final KeywordRuleRepository keywordRuleRepository;
    private final VectorStore vectorStore;
    private final ChatClient chatClient;

    private final AtomicReference<List<KeywordRule>> rulesRef = new AtomicReference<>(List.of());

    /** classpath 種子檔案路徑，對應 src/main/resources/rag/keyword-rules.csv */
    private static final String SEED_CLASSPATH_LOCATION = "rag/keyword-rules.csv";

    /**
     * 啟動流程：
     * 1. 若資料庫目前完全沒有規則，先從 classpath 種子檔案匯入一份預設規則
     *    （只在「資料庫為空」時做，避免蓋掉之後透過 API 上傳的最新版本）
     * 2. 不論有沒有執行過種子匯入，最後都把資料庫現有的規則整批載入記憶體快取
     */
    @PostConstruct
    public void init() {
        seedFromClasspathIfEmpty();
        refreshCacheFromDatabase();
    }

    /**
     * 資料庫目前沒有任何規則時，從 {@code classpath:rag/keyword-rules.csv} 載入一份種子資料寫入資料庫。
     *
     * <p>典型觸發情境：第一次啟動、或 {@code docker compose down -v} 把 MySQL volume
     * 清空重建之後——這種情況下資料庫是全新的，若不做這個補救，關鍵字比對會直接沒有任何規則可用，
     * 需要每次都手動呼叫 {@code POST /api/admin/rag/knowledge/csv/keyword-rules} 才能恢復。</p>
     *
     * <p>只有在 {@code count() == 0} 時才會執行，之後不論資料是種子寫入的還是使用者上傳蓋掉的，
     * 下次啟動都不會再被 classpath 檔案覆蓋。</p>
     */
    private void seedFromClasspathIfEmpty() {
        if (keywordRuleRepository.count() > 0) {
            log.info("[KeywordChatService] 資料庫已有關鍵字規則，略過 classpath 種子匯入");
            return;
        }

        Resource seedResource = new ClassPathResource(SEED_CLASSPATH_LOCATION);
        if (!seedResource.exists()) {
            log.warn("[KeywordChatService] 資料庫目前沒有任何關鍵字規則，且找不到種子檔案 classpath:{}，"
                            + "略過自動匯入，請透過 POST /api/admin/rag/knowledge/csv/keyword-rules 手動上傳",
                    SEED_CLASSPATH_LOCATION);
            return;
        }

        try (InputStream in = seedResource.getInputStream()) {
            List<KeywordRuleEntity> parsed = parseCsv(in);
            keywordRuleRepository.saveAll(parsed);
            log.info("[KeywordChatService] 資料庫目前沒有任何關鍵字規則，已從 classpath:{} 自動匯入 {} 筆種子規則",
                    SEED_CLASSPATH_LOCATION, parsed.size());
        } catch (IOException e) {
            // 種子匯入失敗不應該讓應用程式起不來，記錄錯誤即可，之後仍可透過 API 手動補上資料
            log.error("[KeywordChatService] 從 classpath:{} 自動匯入種子關鍵字規則失敗，"
                    + "請透過 POST /api/admin/rag/knowledge/csv/keyword-rules 手動上傳", SEED_CLASSPATH_LOCATION, e);
        }
    }

    /**
     * 上傳新的 keyword-rules.csv 後呼叫。解析 CSV，整批覆蓋資料庫裡的規則
     * （先刪除全部舊資料再整批新增，包在同一個 transaction 裡，避免中途失敗留下半套資料），
     * 完成後刷新記憶體快取，不需重啟應用程式即可生效。
     * 預期欄位（標頭）：keywords,answer,category（keywords 以 {@code |} 分隔多個同義詞）
     */
    @Transactional
    public void reload(InputStream csvInputStream) throws IOException {
        List<KeywordRuleEntity> parsed = parseCsv(csvInputStream);

        keywordRuleRepository.deleteAllInBatch();
        keywordRuleRepository.saveAll(parsed);
        log.info("[KeywordChatService] 已將 {} 筆規則寫入資料庫", parsed.size());

        refreshCacheFromDatabase();
    }

    /**
     * 共用的 CSV 解析邏輯，{@link #reload(InputStream)} 與
     * {@link #seedFromClasspathIfEmpty()} 都呼叫這裡，避免兩處邏輯各自維護一份、日後改格式漏改一邊。
     * 預期欄位（標頭）：keywords,answer,category（keywords 以 {@code |} 分隔多個同義詞）
     */
    private List<KeywordRuleEntity> parseCsv(InputStream csvInputStream) throws IOException {
        List<KeywordRuleEntity> parsed = new ArrayList<>();
        try (CSVParser parser = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setTrim(true)
                .setIgnoreSurroundingSpaces(true)
                .build()
                .parse(new InputStreamReader(csvInputStream, StandardCharsets.UTF_8))) {

            for (CSVRecord record : parser) {
                List<String> keywords = Arrays.stream(record.get("keywords").split("\\|"))
                        .map(String::trim)
                        .filter(s -> !s.isBlank())
                        .toList();
                String category = record.isMapped("category") ? record.get("category") : "";

                parsed.add(KeywordRuleEntity.builder()
                        .keywords(keywords)
                        .answer(record.get("answer"))
                        .category(category)
                        .build());
            }
        }
        return parsed;
    }

    /** 從資料庫整批讀出所有規則，覆蓋記憶體快取。 */
    private void refreshCacheFromDatabase() {
        List<KeywordRule> rules = keywordRuleRepository.findAll().stream()
                .map(entity -> KeywordRule.builder()
                        .keywords(entity.getKeywords())
                        .answer(entity.getAnswer())
                        .category(entity.getCategory())
                        .build())
                .toList();
        rulesRef.set(rules);
        log.info("[KeywordChatService] 已從資料庫載入 {} 筆規則到記憶體快取", rules.size());
    }

    /**
     * 對外唯一入口：關鍵字命中就直接回覆；沒命中才走向量搜尋 + LLM 生成。
     */
    public String answer(String userMessage) {
        Optional<KeywordRule> matched = match(userMessage);
        if (matched.isPresent()) {
            log.info("[KeywordChatService] 關鍵字命中，category={}", matched.get().getCategory());
            return matched.get().getAnswer();
        }
        return answerByVectorSearch(userMessage);
    }

    public Optional<KeywordRule> match(String message) {
        if (message == null || message.isBlank()) {
            return Optional.empty();
        }
        String normalized = message.trim();
        return rulesRef.get().stream()
                .filter(rule -> rule.getKeywords().stream().anyMatch(normalized::contains))
                .findFirst();
    }

    private static final double SIMILARITY_THRESHOLD = 0.5;

    private static final String FALLBACK_ANSWER =
            "這個問題目前知識庫裡沒有足夠相關的資料，建議聯繫真人客服協助確認，避免我提供不準確的答案。";

    private String answerByVectorSearch(String userMessage) {
        List<Document> docs = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(userMessage)
                        .topK(4)
                        .similarityThreshold(SIMILARITY_THRESHOLD)
                        .build()
        );

        if (docs.isEmpty()) {
            log.info("[KeywordChatService] 向量搜尋無相關文件（門檻 {}），回傳固定回退話術", SIMILARITY_THRESHOLD);
            return FALLBACK_ANSWER;
        }

        String context = docs.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n---\n"));

        return chatClient.prompt()
                .system("你是甜點店客服，只能根據以下知識回答，不清楚就誠實告知需要真人客服協助：\n" + context)
                .user(userMessage)
                .call()
                .content();
    }

    /** 目前生效中的關鍵字規則數量，方便健康檢查或除錯用的 API 回傳。 */
    public int ruleCount() {
        return rulesRef.get().size();
    }
}