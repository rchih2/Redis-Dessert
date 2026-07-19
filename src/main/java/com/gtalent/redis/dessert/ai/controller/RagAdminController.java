package com.gtalent.redis.dessert.ai.controller;

import com.gtalent.redis.dessert.ai.dto.DessertKnowledgeItem;
import com.gtalent.redis.dessert.ai.service.RagKnowledgeIngestionService;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 後台管理員維護 RAG 知識庫用的 API。
 *
 * <p>正式環境應在這裡加上管理員權限檢查（例如 {@code @PreAuthorize("hasRole('ADMIN')")}），
 * 目前先聚焦在知識寫入流程本身。</p>
 */
@RestController
@RequestMapping("/api/admin/rag/knowledge")
@RequiredArgsConstructor
@Validated
public class RagAdminController {

    private final RagKnowledgeIngestionService ragKnowledgeIngestionService;

    /**
     * 匯入一段自由格式的知識文字。
     * 範例 request body：
     * <pre>
     * {
     *   "content": "布朗尼是店內招牌，採用 70% 苦甜巧克力製成，口感濃郁扎實...",
     *   "metadata": { "category": "巧克力系", "source": "admin" }
     * }
     * </pre>
     */
    @PostMapping("/faq")
    public Map<String, Object> ingestText(@RequestBody TextKnowledgeRequest request) {
        int chunkCount = ragKnowledgeIngestionService.ingestText(request.content(), request.metadata());
        return Map.of("status", "success", "chunkCount", chunkCount);
    }

    /**
     * 批次匯入結構化甜點知識項目（JSON 陣列）。
     */
    @PostMapping("/desserts")
    public Map<String, Object> ingestDessertKnowledge(@RequestBody List<DessertKnowledgeItem> items) {
        int chunkCount = ragKnowledgeIngestionService.ingestDessertKnowledge(items);
        return Map.of("status", "success", "itemCount", items.size(), "chunkCount", chunkCount);
    }

    public record TextKnowledgeRequest(
            @NotBlank String content,
            Map<String, Object> metadata
    ) {}
}