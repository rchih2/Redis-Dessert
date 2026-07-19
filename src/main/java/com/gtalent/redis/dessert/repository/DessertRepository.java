package com.gtalent.redis.dessert.repository;

import com.gtalent.redis.dessert.model.Dessert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface DessertRepository extends JpaRepository<Dessert, Long> {

    /** 重置自增主鍵計數器，讓下一筆新增從 1 開始。只應在「刪除全部資料」後呼叫
     *  註：改成軟刪除方案後，deleteAll() 已不再呼叫這個方法，
     *  因為資料實際上還留在表裡，不需要（也不應該）重置自增值。
     *  保留這個方法是為了未來如果有「真正物理清空」的管理功能時還能用到。 */
    @Modifying
    @Transactional
    @Query(value = "ALTER TABLE dessert AUTO_INCREMENT = 1", nativeQuery = true)
    void resetAutoIncrement();

    /**
     * 原子性扣庫存：只有在 stock >= quantity 時才會真的扣減，
     * 並在同一條 UPDATE 語句裡完成「檢查 + 扣減 + 自動下架」，避免高併發下的 read-then-write 競爭問題。
     *
     * 新增邏輯：扣完之後如果庫存變成 0，順便把 enabled 設為 false（自動下架）。
     * 用 CASE WHEN 寫在同一條 UPDATE 裡，是為了避免「先扣庫存、再查一次、再另外下架」
     * 這種多步驟操作在併發情境下產生的時間差問題。
     *
     * 回傳值是實際被更新的資料筆數：1 = 扣減成功；0 = 庫存不足、品項不存在，或品項已被軟刪除。
     *
     * 軟刪除新增條件：WHERE 多加上 d.deleted = false，
     * 避免對「已經被軟刪除」的甜點誤扣庫存（例如舊訂單重送、或前端快取到舊的 id）。
     */
    @Modifying
    @Transactional
    @Query("UPDATE Dessert d SET d.stock = d.stock - :quantity, "
            + "d.enabled = CASE WHEN (d.stock - :quantity) <= 0 THEN false ELSE d.enabled END "
            + "WHERE d.id = :id AND d.stock >= :quantity AND d.deleted = false")
    int deductStock(@Param("id") Long id, @Param("quantity") Integer quantity);

    /**
     * 檢查名稱是否已存在，用來擋「新增重複名稱的甜點」。
     * Spring Data JPA 會依方法名稱自動產生對應的 SQL，不用自己寫 @Query。
     *
     * 軟刪除新增方法：existsByNameAndDeletedFalse
     * 原本的 existsByName() 已停用（不刪除是因為改名容易，但避免忘記換掉呼叫處造成誤判）。
     * 只檢查「還沒被刪除」的資料，避免「已刪除品項的名稱」把新品項的名稱擋住，
     * 導致使用者想新增一個跟舊資料同名的品項時，被誤判成重複而失敗。
     */
    boolean existsByName(String name);

    boolean existsByNameAndDeletedFalse(String name);

    /**
     * 軟刪除新增方法：取代原本的 findAll()。
     * 只回傳 deleted = false 的資料，讓「已刪除」的甜點不會出現在清單查詢裡。
     */
    List<Dessert> findByDeletedFalse();

    /**
     * 軟刪除新增方法：取代原本的 findById()。
     * 就算傳入的 id 對應到一筆已經被軟刪除的資料，也會回傳空的 Optional，
     * 讓呼叫端統一走「找不到品項」的例外處理邏輯，行為上跟資料真的被刪除一致。
     */
    Optional<Dessert> findByIdAndDeletedFalse(Long id);

}