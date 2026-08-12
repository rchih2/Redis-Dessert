package com.gtalent.redis.dessert.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 訂單
 */
@Entity
@Table(name = "orders")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 客戶姓名 */
    @Column(nullable = false, length = 50)
    private String customerName;

    /** 電話 */
    @Column(nullable = false, length = 20)
    private String phone;

    /** LINE 帳號 */
    @Column(length = 50)
    private String lineId;

    /** 總金額（已含運費） */
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal totalAmount;

    /** 下單時間 */
    @Column(nullable = false)
    private LocalDateTime orderTime;

    /** 軟刪除標記（true = 已刪除，不應再出現在任何查詢結果中）。
     *  比照 Dessert 的軟刪除策略，讓訂單被「刪除」後仍保留在 MySQL，
     *  方便未來客戶對單、財務對帳或糾紛時可追溯歷史紀錄。 */
    @Column(name = "deleted", nullable = false, columnDefinition = "tinyint(1) default 0")
    private Boolean deleted = false;

    /**
     * 訂單明細（一筆訂單對應多筆品項）
     * cascade = ALL：儲存/刪除訂單時，明細一併儲存/刪除
     * orphanRemoval = true：從 items 清單移除的明細，會一併從資料庫刪除
     */
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<OrderItem> items = new ArrayList<>();

    /** 新增一筆明細，並同步維護雙向關聯 */
    public void addItem(OrderItem item) {
        items.add(item);
        item.setOrder(this);
    }
    /**
     * 建立此訂單的登入使用者帳號（對應 users.username），與 customerName 分開存放：
     * customerName/phone 是「收件人資訊」（可能是代訂給別人），username 才是「誰下的單」，
     * 用於「查詢我自己的訂單」功能。可為 null——理論上目前 SecurityConfig 規則要求
     * POST /api/orders 必須已登入，所以正常流程一定會有值；保留 nullable 是為了
     * 相容測試環境或未來若開放訪客下單的彈性，不強制加 NOT NULL 約束。
     */
    @Column(name = "username", length = 50)
    private String username;
}