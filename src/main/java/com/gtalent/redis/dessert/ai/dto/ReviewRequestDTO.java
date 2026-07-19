package com.gtalent.redis.dessert.ai.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * POST /api/desserts/{dessertId}/reviews 的請求主體。
 *
 * <p>dessertId 走路徑參數（PathVariable），不放在 body 裡，
 * 避免路徑與 body 兩邊的 dessertId 不一致造成混淆。</p>
 */
@Data
public class ReviewRequestDTO {

    @NotBlank(message = "userId 不可為空")
    private String userId;

    @NotNull(message = "rating 不可為空")
    @Min(value = 1, message = "評分必須介於 1 到 5 之間")
    @Max(value = 5, message = "評分必須介於 1 到 5 之間")
    private Integer rating;

    @Size(max = 500, message = "評論內容不可超過 500 字")
    private String comment;
}