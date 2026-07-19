package com.gtalent.redis.dessert.service;

import com.gtalent.redis.dessert.model.Dessert;

import java.util.List;

public interface DessertService {

    /**
     * 新增甜點
     */
    Dessert create(Dessert dessert);

    /**
     * 查詢所有甜點清單（直接查 MySQL，不經過 Redis 單筆快取）
     */
    List<Dessert> findAll();

    /**
     * 依 ID 查詢甜點（Cache Aside：先查 Redis，沒有再查 MySQL 並回寫快取）
     */
    Dessert getById(Long id);

    /**
     * 更新甜點資訊，並清除對應的 Redis 快取
     */
    Dessert update(Long id, Dessert dessert);

    /**
     * 刪除單一甜點，並清除對應的 Redis 快取
     */
    void delete(Long id);

    /**
     * 刪除全部甜點，並清除全部相關的 Redis 快取
     */
    void deleteAll();

    /**
     * 下單扣庫存：以資料庫層級的原子 UPDATE 執行「檢查庫存 + 扣減」，
     * 確保高併發下單時不會扣出負庫存，也不會讓賣完的品項繼續被下單。
     * 扣減成功後會一併清除該品項的 Redis 快取，避免快取殘留舊庫存數字。
     *
     * @throws InsufficientStockException 品項不存在或庫存不足時拋出
     */
    void deductStock(Long id, Integer quantity);

}