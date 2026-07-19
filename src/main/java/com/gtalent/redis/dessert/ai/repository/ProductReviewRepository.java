package com.gtalent.redis.dessert.ai.repository;

import com.gtalent.redis.dessert.ai.model.ProductReview;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

/**
 * ProductReview 的 Repository。
 *
 * <p><b>與 MySQL 甜點業務的資料對齊方式：</b>
 * {@code dessertId} 即為 MySQL {@code Dessert} 主鍵（Long），MongoDB 這邊不做外鍵約束，
 * 寫入前應由 Service 層（或呼叫端）自行確認該 dessertId 在 MySQL 中確實存在。</p>
 *
 * <p><b>評分彙總查詢目前未對外開放：</b>
 * 原本用來計算平均星數的 Aggregation 查詢方法（{@code calculateRatingSummary} /
 * {@code calculateRatingSummaries}）與其回傳用的 {@code DessertRatingSummary} DTO
 * 已一併移除。未來若要重新開放「評分彙總」功能，可參考 MongoDB Aggregation Pipeline
 * 的寫法在此介面補回：以 {@code $match} 篩選 {@code dessertId} 且 {@code approved = true}
 * 的評論，再用 {@code $group} 依 dessertId 計算 rating 的平均值與筆數。</p>
 */
public interface ProductReviewRepository extends MongoRepository<ProductReview, String> {

    /**
     * 依 dessertId 取出所有評論（含未審核），依建立時間倒序。
     * 對應複合索引 dessert_time_idx。
     */
    List<ProductReview> findByDessertIdOrderByCreatedAtDesc(Long dessertId);

    /**
     * 依 dessertId 取出「已審核通過」的評論，前台商品頁應使用此方法，
     * 避免顯示尚未審核（可能含不當言論）的評論。
     */
    List<ProductReview> findByDessertIdAndApprovedTrueOrderByCreatedAtDesc(Long dessertId, Pageable pageable);

    /**
     * 依使用者查詢自己寫過的評論，用於「我的評論」頁面。
     */
    List<ProductReview> findByUserIdOrderByCreatedAtDesc(String userId);

    /**
     * 已審核通過的評論則數，前端顯示「共 N 則評論」用。
     */
    long countByDessertIdAndApprovedTrue(Long dessertId);
}