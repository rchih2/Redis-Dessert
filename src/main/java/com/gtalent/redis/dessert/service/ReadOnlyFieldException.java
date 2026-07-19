package com.gtalent.redis.dessert.service;

/**
 * 嘗試修改「唯讀欄位」時拋出的例外。
 *
 * 例如：Dessert.name 在建立(POST)後即視為唯讀，
 * PUT /api/desserts/{id} 若帶入與資料庫現有值不同的 name，
 * 就會拋出這個例外，避免品項名稱被隨意更改造成資料混亂
 *（例如歷史訂單的品項快照跟現在的甜點對不上、或誤觸發同名檢查等問題）。
 */
public class ReadOnlyFieldException extends RuntimeException {

    public ReadOnlyFieldException(String message) {
        super(message);
    }

}