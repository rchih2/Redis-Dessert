package com.gtalent.redis.dessert.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 甜點品項
 */
@Entity
@Table(name = "dessert")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Dessert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "品項名稱不可為空白")
    @Column(nullable = false, length = 100)
    private String name;

    @NotNull(message = "單價不可為空")
    @DecimalMin(value = "0.0", inclusive = false, message = "單價必須大於 0")
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @NotNull(message = "庫存不可為空")
    @Min(value = 0, message = "庫存不可為負數")
    @Column(nullable = false)
    private Integer stock;

    /** 是否上架（true = 上架，可被查詢/下單；false = 缺貨自動下架） */
    @NotNull(message = "上架狀態不可為空")
    @Column(name = "enabled", nullable = false, columnDefinition = "tinyint(1) default 1")
    private Boolean enabled = true;

    /** 軟刪除標記（true = 已刪除，不應再出現在任何查詢結果中） */
    @Column(name = "deleted", nullable = false, columnDefinition = "tinyint(1) default 0")
    private Boolean deleted = false;

}