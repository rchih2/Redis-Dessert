package com.gtalent.redis.dessert.ai.message.keyword;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 關鍵字比對用的記憶體快取 DTO。
 *
 * <p>跟 {@link KeywordRuleEntity}（資料庫實體）分開的原因：
 * 比對邏輯（{@code KeywordChatService.match}）跟其他服務（例如
 * {@code AiChatService}）都直接依賴這個型別，讓它保持單純的 POJO，
 * 不要混入 JPA 的 {@code @Entity}、{@code @Id} 等持久化細節，
 * 兩者職責分開，之後要換掉持久化方式（例如換成 Redis 快取）也不會動到比對邏輯。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KeywordRule {

    /** 只要使用者訊息「包含」清單中任一關鍵字，即視為命中此規則。 */
    private List<String> keywords;

    /** 命中後直接採用的答案內容。 */
    private String answer;

    /** 分類，方便除錯與維護時分辨規則用途。 */
    private String category;
}