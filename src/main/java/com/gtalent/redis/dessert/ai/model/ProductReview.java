package com.gtalent.redis.dessert.ai.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.LocalDateTime;

/**
 * 商品（甜點）評論。
 *
 * <p>{@code dessertId} 對應 MySQL 端 {@code Dessert} 的主鍵（Long），
 * 這裡刻意存成 String 是為了讓 MongoDB 文件與關聯式資料庫解耦，
 * 避免未來甜點 ID 型別調整時牽動這個 collection。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "product_reviews")
@CompoundIndexes({
        @CompoundIndex(name = "dessert_time_idx", def = "{'dessertId': 1, 'createdAt': -1}")
})
public class ProductReview {

    @Id
    private String id;

    /** 對應的甜點品項 ID（關聯到 MySQL Dessert.id）。 */
    @Indexed
    @Field("dessert_id")
    private Long dessertId;

    /** 撰寫評論的使用者 ID。 */
    @Indexed
    @Field("user_id")
    private String userId;

    /** 評分，建議範圍 1~5 分，實際驗證邏輯放在 Service 層（Bean Validation 亦可加在 DTO）。 */
    @Field("rating")
    private Integer rating;

    /** 評論文字內容。 */
    @Field("comment")
    private String comment;

    /** 評論建立時間。 */
    @Indexed
    @CreatedDate
    @Field("created_at")
    private LocalDateTime createdAt;

    /** 是否已通過內容審核（例如過濾不當言論），預設 false 待審。 */
    @Builder.Default
    @Field("approved")
    private boolean approved = false;
}