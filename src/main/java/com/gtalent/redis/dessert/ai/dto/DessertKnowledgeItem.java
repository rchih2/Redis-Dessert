package com.gtalent.redis.dessert.ai.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 後台匯入 RAG 知識庫時，單一甜點品項的結構化知識內容。
 *
 * <p>對應情境：管理員在後台「甜點知識維護」頁面，針對每個品項填寫一段導覽文字
 * （例如原料、風味、適合場景），系統將其轉為向量並寫入知識庫，
 * 供 AI 助手日後檢索、推薦使用。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DessertKnowledgeItem {

    /** 對應 MySQL Dessert.id，方便之後從 RAG 命中結果反查商品詳細資料（價格、圖片、庫存）。 */
    private Long dessertId;

    /** 甜點名稱，例如「70% 苦甜巧克力布朗尼」。 */
    private String name;

    /** 分類，例如「巧克力系」「水果系」「輕食系」，可用於後續 metadata 篩選。 */
    private String category;

    /**
     * 知識內容本文，建議寫成自然語言導覽文字而非條列，
     * 因為 Embedding 模型對連貫語句的語意捕捉效果通常優於零散關鍵字。
     * 範例：「布朗尼是店內招牌，採用 70% 苦甜巧克力製成，口感濃郁扎實，
     * 適合心情低落、想要療癒系甜點的顧客。」
     */
    private String content;

    /** 額外標籤，例如 ["巧克力", "濃郁", "療癒"]，可加進 metadata 輔助篩選與除錯。 */
    private List<String> tags;
}