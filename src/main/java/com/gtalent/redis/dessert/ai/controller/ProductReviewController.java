package com.gtalent.redis.dessert.ai.controller;

import com.gtalent.redis.dessert.ai.dto.ReviewRequestDTO;
import com.gtalent.redis.dessert.ai.model.ProductReview;
import com.gtalent.redis.dessert.ai.service.MongoDataTrackingService;
import com.gtalent.redis.dessert.model.Dessert;
import com.gtalent.redis.dessert.repository.DessertRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 甜點商品評論 API。
 *
 * <p>目前僅提供「提交評論」端點。評論本體（ProductReview）的寫入邏輯已經在
 * {@link MongoDataTrackingService} 裡實作完成，這支 Controller 只負責：
 * 1) 依 dessertId 向 MySQL 的 {@link DessertRepository} 確認甜點存在且已上架，
 *    確認通過後才呼叫 Mongo 那邊寫入評論（避免對已下架 / 不存在的商品留言）；
 * 2) 把 Service 方法包裝成統一的 {success, data|message} 回應格式，
 *    對齊 {@link DessertAiChatController} 的既有慣例。</p>
 *
 * <p>評分彙總查詢（{@code getRatingSummary} / {@code getRatingSummaries}）
 * 目前先不對外開放，{@link MongoDataTrackingService} 裡的方法仍保留，
 * 未來若要開放查詢，直接在這支 Controller 加回對應端點即可。</p>
 *
 * <p>正式環境上線前，POST /reviews 建議加上使用者驗證（目前 userId 由前端傳入，
 * 未做登入狀態核對），避免任意冒用他人 userId 留言。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/desserts")
@RequiredArgsConstructor
public class ProductReviewController {

    private final MongoDataTrackingService mongoDataTrackingService;
    private final DessertRepository dessertRepository;

    /**
     * 提交一筆商品評論。
     *
     * <p>流程：先查 MySQL 確認 dessertId 存在且未下架 → 通過才寫入 MongoDB。
     * 新評論預設未審核（approved = false），此處直接回傳「送出成功，審核中」的語意，
     * 實際文案由前端依 approved 欄位自行決定顯示方式。</p>
     */
    @PostMapping("/{dessertId}/reviews")
    public ResponseEntity<Map<String, Object>> submitReview(
            @PathVariable Long dessertId,
            @Valid @RequestBody ReviewRequestDTO request) {
        try {
            assertDessertAvailable(dessertId);
            ProductReview review = mongoDataTrackingService.submitReview(
                    dessertId, request.getUserId(), request.getRating(), request.getComment());
            return ResponseEntity.status(HttpStatus.CREATED).body(successBody(review));
        } catch (DessertNotAvailableException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorBody(e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(errorBody(e.getMessage()));
        } catch (Exception e) {
            log.error("[ProductReviewController] 提交評論失敗，dessertId={}", dessertId, e);
            return ResponseEntity.internalServerError().body(errorBody("系統發生未預期錯誤，請稍後再試"));
        }
    }

    /**
     * 確認甜點存在、未刪除、且已上架，否則丟出 {@link DessertNotAvailableException}。
     * 對齊 {@code MongoDataTrackingService#submitReview} javadoc 裡「呼叫端應先確認」的原則。
     */
    private void assertDessertAvailable(Long dessertId) {
        Optional<Dessert> dessert = dessertRepository.findByIdAndDeletedFalse(dessertId);
        if (dessert.isEmpty() || Boolean.FALSE.equals(dessert.get().getEnabled())) {
            throw new DessertNotAvailableException("找不到指定甜點，或該甜點已下架，無法留言");
        }
    }

    private Map<String, Object> successBody(Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("data", data);
        return body;
    }

    private Map<String, Object> errorBody(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("message", message);
        return body;
    }

    /** 甜點不存在或已下架時丟出，由 Controller 內部攔截轉成 404。 */
    private static class DessertNotAvailableException extends RuntimeException {
        DessertNotAvailableException(String message) {
            super(message);
        }
    }
}