package com.gtalent.redis.dessert.ai.controller;

import com.gtalent.redis.dessert.ai.dto.DessertKnowledgeItem;
import com.gtalent.redis.dessert.ai.message.keyword.KeywordChatService;
import com.gtalent.redis.dessert.ai.message.ingest.CsvKnowledgeParser;
import com.gtalent.redis.dessert.ai.service.RagKnowledgeIngestionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 直接上傳 CSV 檔案匯入知識庫，不需要任何外部轉換工具（不需要裝 Python / Node.js）。
 *
 * <p>使用方式（Postman 或 curl，multipart/form-data，欄位名固定為 {@code file}）：
 * <pre>
 * curl -X POST http://localhost:8080/api/admin/rag/knowledge/csv/desserts -F "file=@desserts.csv"
 *
 *
 * curl -X POST http://localhost:8080/api/admin/rag/knowledge/csv/faq  -F "file=@faq.csv"
 *
 *
 * curl -X POST http://localhost:8080/api/admin/rag/knowledge/csv/keyword-rules -F "file=@keyword-rules.csv"
 *
 * </pre>
 * 正式環境同樣要補上管理員權限檢查，這點跟既有的 {@link RagAdminController} 原則一致。</p>
 *
 * <p>異動說明：keyword-rules 這支原本依賴
 * {@code CsvKnowledgeParser#parseKeywordRules} + {@code KeywordMatchService}，
 * 現在改成直接把上傳的 InputStream 丟給 {@link KeywordChatService#reload(java.io.InputStream)}，
 * CSV 解析與規則儲存都封裝在該服務內部，這支 Controller 不再需要認識 KeywordRule 這個型別。</p>
 */
@RestController
@RequestMapping("/api/admin/rag/knowledge/csv")
@RequiredArgsConstructor
public class CsvKnowledgeUploadController {

    private final CsvKnowledgeParser csvKnowledgeParser;
    private final RagKnowledgeIngestionService ragKnowledgeIngestionService;
    private final KeywordChatService keywordChatService;

    /** 上傳結構化甜點知識 CSV，直接解析並走向量檢索匯入流程。 */
    @PostMapping("/desserts")
    public Map<String, Object> uploadDesserts(@RequestParam("file") MultipartFile file) throws Exception {
        List<DessertKnowledgeItem> items = csvKnowledgeParser.parseDesserts(file.getInputStream());
        int chunkCount = ragKnowledgeIngestionService.ingestDessertKnowledge(items);
        return Map.of("status", "success", "itemCount", items.size(), "chunkCount", chunkCount);
    }

    /** 上傳自由文字 FAQ CSV，逐筆轉成 content+metadata 並走向量檢索匯入流程。 */
    @PostMapping("/faq")
    public Map<String, Object> uploadFaq(@RequestParam("file") MultipartFile file) throws Exception {
        List<CsvKnowledgeParser.TextKnowledge> items = csvKnowledgeParser.parseFaq(file.getInputStream());
        int totalChunks = 0;
        for (CsvKnowledgeParser.TextKnowledge item : items) {
            totalChunks += ragKnowledgeIngestionService.ingestText(item.content(), item.metadata());
        }
        return Map.of("status", "success", "itemCount", items.size(), "chunkCount", totalChunks);
    }

    /**
     * 上傳關鍵字直接比對規則 CSV，直接更新 {@link KeywordChatService} 記憶體中的規則清單。
     * 這支不會寫進 VectorStore，也不需要重啟應用程式——上傳後立即生效。
     */
    @PostMapping("/keyword-rules")
    public Map<String, Object> uploadKeywordRules(@RequestParam("file") MultipartFile file) throws Exception {
        keywordChatService.reload(file.getInputStream());
        return Map.of("status", "success", "ruleCount", keywordChatService.ruleCount());
    }
}