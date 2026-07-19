package com.gtalent.redis.dessert.repository;

import com.gtalent.redis.dessert.model.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {

    /** 重置自增主鍵計數器，讓下一筆新增從 1 開始。只應在「刪除全部資料」後呼叫。
     *  註：改成軟刪除方案後，刪除訂單已不再呼叫這個方法，
     *  因為資料實際上還留在表裡，不需要（也不應該）重置自增值。
     *  保留這個方法是為了未來如果有「真正物理清空」的管理功能時還能用到。 */
    @Modifying
    @Transactional
    @Query(value = "ALTER TABLE orders AUTO_INCREMENT = 1", nativeQuery = true)
    void resetAutoIncrement();

    /**
     * 軟刪除新增方法：取代原本的 findAll()。
     * 只回傳 deleted = false 的訂單，讓「已刪除」的訂單不會出現在清單查詢裡。
     */
    List<Order> findByDeletedFalse();

    /**
     * 軟刪除新增方法：取代原本的 findById()。
     * 就算傳入的 id 對應到一筆已經被軟刪除的訂單，也會回傳空的 Optional，
     * 讓呼叫端統一走「找不到訂單」的例外處理邏輯，行為上跟資料真的被刪除一致。
     */
    Optional<Order> findByIdAndDeletedFalse(Long id);

    /**
     * 軟刪除單筆訂單：只把 deleted 設為 true，不做實體刪除，訂單明細（OrderItem）也不受影響。
     * WHERE 條件多加 deleted = false，避免對已經刪除過的訂單重複標記，
     * 回傳值是實際被更新的筆數：1 = 刪除成功；0 = 訂單不存在或已被刪除過。
     */
    @Modifying
    @Transactional
    @Query("UPDATE Order o SET o.deleted = true WHERE o.id = :id AND o.deleted = false")
    int softDeleteById(@Param("id") Long id);

    /**
     * 軟刪除全部訂單：整批把尚未刪除的訂單標記為 deleted = true。
     * 資料實際上仍留在資料庫，因此不需要（也不應該）搭配 resetAutoIncrement()。
     */
    @Modifying
    @Transactional
    @Query("UPDATE Order o SET o.deleted = true WHERE o.deleted = false")
    int softDeleteAll();

}