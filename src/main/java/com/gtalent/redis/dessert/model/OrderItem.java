package com.gtalent.redis.dessert.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 訂單明細（一筆訂單對應多筆品項）
 *
 * 這裡刻意把品項名稱、單價「快照」下來，而不是單純存 dessertId 事後再去查 Dessert 表，
 * 原因是甜點的價格、名稱未來可能會變動，訂單明細應該保留「下單當下」的資料，
 * 這樣之後查歷史訂單時，金額跟名稱才會跟客戶當初實際看到的一致。
 */
@Entity
@Table(name = "order_item")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所屬訂單（多對一） */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    @JsonIgnore // 避免序列化時 Order -> items -> order -> items ... 無限遞迴
    private Order order;

    /** 甜點品項 ID（僅作參照，非強制外鍵，方便日後追蹤是哪個品項） */
    @Column(nullable = false)
    private Long dessertId;

    /** 品項名稱快照（下單當下的名稱） */
    @Column(nullable = false, length = 100)
    private String dessertName;

    /** 單價快照（下單當下的單價） */
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal unitPrice;

    /** 購買數量 */
    @Column(nullable = false)
    private Integer quantity;

    /** 小計（單價 × 數量） */
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal lineTotal;

}