package com.gtalent.redis.dessert.ai.service;

import com.gtalent.redis.dessert.ai.dto.DessertKnowledgeItem;
import com.gtalent.redis.dessert.repository.DessertRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * RAG 知識庫寫入的統一入口，供後台管理 API 或初始化流程呼叫。
 *
 * <p>流程固定為：轉換成 Document → 分塊 → 呼叫 {@link VectorStore#add} 寫入
 * （{@code add} 內部會自動呼叫 EmbeddingModel 產生向量，並寫入底層資料庫，
 * 呼叫端不需要自己處理 Embedding API）。</p>
 *
 * <p>異動說明：{@link #ingestDessertKnowledge(List)} 新增匯入前檢查，
 * 逐筆驗證 {@code dessertId} 必須對應到 MySQL {@code desserts} 表中
 * 存在且未刪除的商品，否則整批拒絕寫入（硬擋），避免向量庫出現
 * 指向不存在商品的髒資料，導致 AI 推薦「查無此商品」的品項。
 * 驗證邏輯與 {@code ProductReviewController} 裡「先查 MySQL 確認商品存在」的原則相同，
 * 但這裡不檢查 enabled 狀態（下架商品仍可保留知識，只是不開放留言/下單）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagKnowledgeIngestionService {

    private final RagDocumentService ragDocumentService;
    private final VectorStore vectorStore;
    private final DessertRepository dessertRepository;

    /**
     * 匯入一段純文字知識（例如管理員在後台文字框貼上的一大段甜點介紹）。
     *
     * @param rawText  原始知識文字
     * @param metadata 額外 metadata，例如 {"source": "admin-manual-input", "category": "巧克力系"}
     * @return 實際寫入向量庫的分塊筆數
     */
    public int ingestText(String rawText, Map<String, Object> metadata) {
        if (rawText == null || rawText.isBlank()) {
            throw new IllegalArgumentException("知識文字內容不可為空");
        }

        Document document = ragDocumentService.toDocument(rawText, metadata);
        List<Document> chunked = ragDocumentService.splitDocuments(List.of(document));

        vectorStore.add(chunked);
        log.info("已寫入 {} 個文字知識分塊至向量資料庫", chunked.size());
        return chunked.size();
    }

    /**
     * 批次匯入結構化甜點知識項目（例如後台「甜點知識維護」頁面一次送出多筆品項介紹）。
     *
     * <p>寫入前會先驗證每一筆的 {@code dessertId} 是否存在於 MySQL {@code desserts} 表
     * 且未被刪除；只要有任何一筆對不上，就整批拒絕、不寫入向量庫。</p>
     *
     * @throws IllegalArgumentException 清單為空，或存在對不上 MySQL 商品的 dessertId
     * @return 實際寫入向量庫的分塊筆數
     */
    public int ingestDessertKnowledge(List<DessertKnowledgeItem> items) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("甜點知識項目清單不可為空");
        }

        validateDessertIdsExist(items);

        List<Document> documents = ragDocumentService.toDocuments(items);
        List<Document> chunked = ragDocumentService.splitDocuments(documents);

        vectorStore.add(chunked);
        log.info("已寫入 {} 筆甜點（分塊後 {} 筆）至向量資料庫",
                items.size(), chunked.size());
        return chunked.size();
    }

    /**
     * 逐筆確認 dessertId 存在於 MySQL 且未刪除，否則整批拒絕匯入（硬擋）。
     * dessertId 為 null 的項目視為合法（允許無對應商品的純知識文字），不列入檢查。
     */
    private void validateDessertIdsExist(List<DessertKnowledgeItem> items) {
        List<Long> invalidIds = items.stream()
                .map(DessertKnowledgeItem::getDessertId)
                .filter(id -> id != null)
                .distinct()
                .filter(id -> dessertRepository.findByIdAndDeletedFalse(id).isEmpty())
                .toList();

        if (!invalidIds.isEmpty()) {
            log.warn("[RagKnowledgeIngestionService] 匯入拒絕：以下 dessertId 在 MySQL 找不到對應商品或已刪除: {}",
                    invalidIds);
            throw new IllegalArgumentException(
                    "以下 dessertId 在商品資料表中不存在或已刪除，無法匯入知識庫: " + invalidIds);
        }
    }

}