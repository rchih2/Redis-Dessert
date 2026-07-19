package com.gtalent.redis.dessert.ai.service;

import com.gtalent.redis.dessert.ai.dto.DessertKnowledgeItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 負責把「內部甜點知識」轉換成 Spring AI 的 {@link Document} 物件，
 * 並依 Token 數進行分塊（Chunking），為寫入向量資料庫做準備。
 *
 * <p>本服務只做「轉換與分塊」，不負責真正寫入向量庫——
 * 寫入交給 {@link RagKnowledgeIngestionService}，維持單一職責。</p>
 */
@Slf4j
@Service
public class RagDocumentService {

    @Value("${app.rag.chunk.chunk-size:500}")
    private int chunkSize;

    @Value("${app.rag.chunk.min-chunk-size-chars:200}")
    private int minChunkSizeChars;

    @Value("${app.rag.chunk.min-chunk-length-to-embed:10}")
    private int minChunkLengthToEmbed;

    @Value("${app.rag.chunk.max-num-chunks:10000}")
    private int maxNumChunks;

    /**
     * 將一段純文字（例如管理員在後台貼上的「甜點知識文字」）轉換為單一 {@link Document}。
     *
     * @param rawText  原始知識文字
     * @param metadata 額外 metadata（例如 source、category），可為 null
     */
    public Document toDocument(String rawText, Map<String, Object> metadata) {
        Map<String, Object> mergedMetadata = new HashMap<>();
        if (metadata != null) {
            mergedMetadata.putAll(metadata);
        }
        mergedMetadata.putIfAbsent("ingestedAt", LocalDateTime.now().toString());
        // 標記知識類型，讓後續查詢（GET /api/admin/rag/knowledge/faq）可以用
        // filterExpression 過濾出「自由文字知識」，跟結構化甜點知識區分開
        mergedMetadata.putIfAbsent("type", "text");

        return Document.builder()
                .text(rawText)
                .metadata(mergedMetadata)
                .build();
    }

    /**
     * 將結構化的甜點知識項目清單（例如後台表單一次匯入多筆）轉換為 {@link Document} 清單，
     * 每個品項各自成為一個 Document，並把 dessertId、name、category、tags 放進 metadata，
     * 方便日後用 {@code SearchRequest.filterExpression} 做條件篩選（例如只查「巧克力系」）。
     */
    public List<Document> toDocuments(List<DessertKnowledgeItem> items) {
        return items.stream()
                .map(item -> {
                    Map<String, Object> metadata = new HashMap<>();
                    metadata.put("dessertId", item.getDessertId());
                    metadata.put("name", item.getName());
                    metadata.put("category", item.getCategory());
                    metadata.put("tags", item.getTags());
                    metadata.put("ingestedAt", LocalDateTime.now().toString());
                    // 標記知識類型，讓後續查詢（GET /api/admin/rag/knowledge/desserts）可以用
                    // filterExpression 過濾出「結構化甜點知識」，跟自由文字知識區分開
                    metadata.put("type", "dessert");
                    return Document.builder()
                            .text(item.getContent())
                            .metadata(metadata)
                            .build();
                })
                .toList();
    }

    /**
     * 依 Token 數將 {@link Document} 清單切成適合 Embedding 的小塊。
     *
     * <p>分塊大小的取捨：chunk 太大，單一向量涵蓋的主題太廣，檢索精準度會下降；
     * chunk 太小，又會失去上下文（例如「布朗尼」跟「70% 苦甜巧克力」被切開）。
     * 甜點導覽文字通常不長，預設 500 token、最小 200 字元是偏保守的設定，
     * 讓大多數單一品項介紹可以整段落在同一個 chunk 裡。</p>
     */
    public List<Document> splitDocuments(List<Document> documents) {
        TokenTextSplitter splitter = TokenTextSplitter.builder()
                .withChunkSize(chunkSize)
                .withMinChunkSizeChars(minChunkSizeChars)
                .withMinChunkLengthToEmbed(minChunkLengthToEmbed)
                .withMaxNumChunks(maxNumChunks)
                .build();

        List<Document> chunked = splitter.apply(documents);
        log.info("文件分塊完成：原始 {} 筆 -> 分塊後 {} 筆", documents.size(), chunked.size());
        return chunked;
    }
}