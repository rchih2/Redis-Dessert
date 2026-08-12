package com.gtalent.redis.dessert.security.model;

/**
 * 系統角色，三層式權限：
 * ADMIN  - 系統管理員，擁有全部權限（含實體刪除、真正刪除訂單）
 * STAFF  - 店員，可管理甜點（新增/修改/刪除、CSV 匯入），但不能刪除訂單
 * USER   - 一般顧客，只能瀏覽甜點、建立訂單
 */
public enum Role {
    ADMIN,
    STAFF,
    USER
}