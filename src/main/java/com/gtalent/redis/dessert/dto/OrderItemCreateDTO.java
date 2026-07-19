package com.gtalent.redis.dessert.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class OrderItemCreateDTO {

        @NotNull(message = "甜點 ID 不可為空")
        private Long dessertId;

        @NotNull(message = "數量不可為空")
        @Min(value = 1, message = "數量至少為 1")
        private Integer quantity;
}