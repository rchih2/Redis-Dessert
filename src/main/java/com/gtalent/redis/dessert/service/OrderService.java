package com.gtalent.redis.dessert.service;

import com.gtalent.redis.dessert.dto.OrderCreateDTO;
import com.gtalent.redis.dessert.dto.OrderResponseDTO;

import java.util.List;

public interface OrderService {

    /**
     * 建立訂單：第一層(@Valid) + 第二層(金額覆核)驗證 + 第三層(扣庫存)。
     *
     * 整個流程包在同一個交易內：逐一依 {@code items} 撈出甜點單價、以資料庫原子 UPDATE
     * 扣庫存、重新計算金額明細（小計、運費、總額，一律由後端計算，不採信前端傳入的金額），
     * 最後建立 {@code Order}／{@code OrderItem} 並存檔。
     *
     * 交易確定 commit 後才會發布 Kafka 訂單事件、累加業務指標（訂單數、商品銷售數），
     * 避免交易回滾時發生「事件已發、指標已算，但訂單其實沒有真的成立」的幻影資料問題。
     *
     * @throws InsufficientStockException 品項庫存不足時拋出，整筆交易回滾
     */
    OrderResponseDTO createOrder(OrderCreateDTO dto);

    /**
     * 查詢全部訂單（含品項明細）。
     * 只回傳未被軟刪除（{@code deleted = false}）的訂單。
     */
    List<OrderResponseDTO> findAll();

    /**
     * 依 ID 查詢單一訂單（含品項明細）。
     *
     * @throws jakarta.persistence.EntityNotFoundException 找不到該筆訂單，
     *         或該筆訂單已被軟刪除時拋出
     */
    OrderResponseDTO getById(Long id);

    /**
     * 軟刪除單一訂單：只標記 {@code deleted = true}，訂單本身與明細仍留在資料庫，
     * 方便未來客戶對單、財務對帳或糾紛時可追溯歷史紀錄。
     * 刪除成功後會累加「取消訂單」業務指標。
     *
     * @throws jakarta.persistence.EntityNotFoundException 找不到該筆訂單，
     *         或該筆訂單已被軟刪除過時拋出
     */
    void softDelete(Long id);

    /**
     * 軟刪除全部尚未刪除的訂單，整批標記為 {@code deleted = true}。
     * 依實際被標記的筆數一次累加「取消訂單」業務指標。
     *
     * @return 這次動作實際被標記為刪除的訂單筆數
     */
    int softDeleteAll();
    /**
     * 依登入使用者查詢自己的訂單清單（未軟刪除），依下單時間倒序。
     * username 從 SecurityContext 取得，不信任前端傳入的值，避免查到別人的訂單。
     */
    List<OrderResponseDTO> findMyOrders(String username);

}