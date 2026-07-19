package com.gtalent.redis.dessert.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.util.List;

/**
 * 下單請求 DTO（第一層輸入驗證）
 *
 * 金額（小計、運費、總額）完全由後端依資料庫的實際單價自動計算，
 * 前端不需要、也不應該傳金額欄位，避免被竄改。
 */
@Data
public class OrderCreateDTO {

        /** 客戶姓名 */
        @NotBlank(message = "姓名不可為空白")
        private String customerName;

        /** 電話，需符合台灣手機格式 09xxxxxxxx */
        @NotBlank(message = "電話不可為空白")
        @Pattern(regexp = "^09\\d{8}$", message = "電話格式錯誤，須為 09 開頭共 10 碼數字")
        private String phone;

        /** LINE 帳號（非必填） */
        private String lineId;

        /** 選購的品項與數量清單 */
        @NotEmpty(message = "至少需選購一項甜點")
        @Valid
        private List<OrderItemCreateDTO> items;

}