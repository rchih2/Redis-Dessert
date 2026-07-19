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

}