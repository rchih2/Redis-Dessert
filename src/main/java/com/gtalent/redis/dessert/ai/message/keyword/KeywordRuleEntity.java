package com.gtalent.redis.dessert.ai.message.keyword;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 關鍵字規則的資料庫實體，對應 MySQL 的 keyword_rule + keyword_rule_keyword 兩張表。
 *
 * <p>取代原本存在外部檔案系統（volume 掛載的 CSV）的持久化方式——
 * 改用 MySQL 之後，不再需要 entrypoint 種子腳本、不用擔心 volume 掛錯位置，
 * {@code down}/{@code up}/{@code --build} 都不會影響資料，交給資料庫本身的持久化機制處理。</p>
 *
 * <p>{@code keywords} 用 {@code @ElementCollection} 對應到獨立的
 * {@code keyword_rule_keyword} 子表（一對多），而不是把多個關鍵字塞進單一字串欄位用
 * {@code |} 分隔——這樣才能在資料庫層面對單一關鍵字建索引、下條件查詢。</p>
 */
@Entity
@Table(name = "keyword_rule")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KeywordRuleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "keyword_rule_keyword", joinColumns = @JoinColumn(name = "rule_id"))
    @Column(name = "keyword", nullable = false, length = 100)
    @Builder.Default
    private List<String> keywords = new ArrayList<>();

    @Column(name = "answer", nullable = false, columnDefinition = "TEXT")
    private String answer;

    @Column(name = "category", length = 100)
    private String category;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}