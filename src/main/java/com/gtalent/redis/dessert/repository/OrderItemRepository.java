package com.gtalent.redis.dessert.repository;

import com.gtalent.redis.dessert.model.OrderItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    /** 重置自增主鍵計數器，讓下一筆新增從 1 開始。只應在「刪除全部資料」後呼叫。
     *  註：訂單改成軟刪除方案後，這個方法已不再被呼叫——
     *  訂單刪除只是把 Order.deleted 標記為 true，OrderItem 資料列完全不受影響、
     *  也不需要重置自增值。保留這個方法是為了未來如果有「真正物理清空」的管理功能時還能用到。 */
    @Modifying
    @Transactional
    @Query(value = "ALTER TABLE order_item AUTO_INCREMENT = 1", nativeQuery = true)
    void resetAutoIncrement();

}