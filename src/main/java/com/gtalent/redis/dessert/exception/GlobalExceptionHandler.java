package com.gtalent.redis.dessert.exception;

import com.gtalent.redis.dessert.service.DuplicateNameException;
import com.gtalent.redis.dessert.service.InsufficientStockException;
import com.gtalent.redis.dessert.service.ReadOnlyFieldException;
import jakarta.persistence.EntityNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.validation.FieldError;
import org.springframework.security.access.AccessDeniedException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import com.gtalent.redis.dessert.security.exception.DuplicateUsernameException;
import org.springframework.security.authentication.BadCredentialsException;

/**
 * 全域例外處理器（技術文件第 9 節「建議後續工作」第 1 項）。
 *
 * <p>目的：把原本散落在 {@code DessertOrderController} 裡的
 * {@code @ExceptionHandler(InsufficientStockException.class)}／
 * {@code @ExceptionHandler(DuplicateNameException.class)}／
 * {@code @ExceptionHandler(EntityNotFoundException.class)} 三個方法，
 * 集中搬到這個 {@code @RestControllerAdvice}，讓所有 Controller（包含未來新增的）
 * 都能共用同一套例外 → HTTP 狀態碼 → {success:false, message} 回應格式的轉換邏輯，
 * 不需要每個 Controller 各自重複寫一次。</p>
 *
 * <p>順帶修正一個既有落差：{@code ReadOnlyFieldException}（甜點名稱唯讀檢查，
 * 見技術文件 3.1、4.5 節）原本被拋出後，{@code DessertOrderController} 並沒有
 * 對應的 {@code @ExceptionHandler}，實際會落到 Spring Boot 預設的錯誤處理，
 * 回傳 {@code 500 Internal Server Error}，跟文件記載的「應回傳 400 Bad Request」
 * 不一致。這裡補上對應的處理，讓實際行為符合文件描述。</p>
 *
 * <p>另外補上 {@code MethodArgumentNotValidException}（Bean Validation 失敗，
 * 例如 {@code @Valid} 擋下的 {@code phone} 格式錯誤）的統一格式化，
 * 對應技術文件第 8 節取捨第 2 項：原本這種驗證錯誤是 Spring Boot 預設格式
 * （{@code errors} 陣列 + 各種內部欄位），跟本專案其餘 API 慣用的
 * {@code {success, message}} 格式不一致。這裡改成同樣格式，並把每個欄位的
 * 錯誤訊息合併進 {@code message}。</p>
 *
 * <p>⚠️ 尚未涵蓋：{@code com.gtalent.redis.dessert.ai} 套件下的
 * {@code DessertAiChatController} 目前仍是自己 try/catch 組裝 {success/data}
 * 回應（見該類別頂端註解），沒有改用這裡的全域處理器——AI 對話流程需要依例外類型
 * 回傳不同的資料（例如 {@code ChatResponseDTO}），跟這裡單純的錯誤訊息回應不完全對等，
 * 之後若要合併，需要一併重新設計回應格式，這裡先只處理甜點／訂單模組。</p>
 *
 * <p>本次新增（RBAC）：{@code DuplicateUsernameException}（註冊帳號重複）與
 * {@code BadCredentialsException}（登入帳密錯誤，Spring Security 內建例外）的統一格式化，
 * 讓驗證相關的錯誤回應也遵循同一套 {success, message} 格式，不需要另外處理。</p>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 甜點名稱唯讀檢查失敗（PUT 時傳入與現有值不同的 name）→ 400 Bad Request */
    @ExceptionHandler(ReadOnlyFieldException.class)
    public ResponseEntity<Map<String, Object>> handleReadOnlyField(ReadOnlyFieldException ex) {
        return errorResponse(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    /** 新增甜點時名稱重複 → 409 Conflict */
    @ExceptionHandler(DuplicateNameException.class)
    public ResponseEntity<Map<String, Object>> handleDuplicateName(DuplicateNameException ex) {
        return errorResponse(HttpStatus.CONFLICT, ex.getMessage());
    }

    /** 下單時庫存不足（已售完）→ 409 Conflict */
    @ExceptionHandler(InsufficientStockException.class)
    public ResponseEntity<Map<String, Object>> handleInsufficientStock(InsufficientStockException ex) {
        return errorResponse(HttpStatus.CONFLICT, ex.getMessage());
    }

    /** 查詢/刪除的資料不存在（已軟刪除或 id 不存在）→ 404 Not Found */
    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleEntityNotFound(EntityNotFoundException ex) {
        return errorResponse(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    /**
     * Bean Validation（{@code @Valid}）失敗 → 400 Bad Request。
     * 把每個欄位的錯誤訊息（例如 "phone: 電話格式錯誤"）合併成一段 message，
     * 而不是沿用 Spring Boot 預設的巢狀 errors 陣列格式。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        if (message.isBlank()) {
            message = "請求參數驗證失敗";
        }
        return errorResponse(HttpStatus.BAD_REQUEST, message);
    }

    /** 註冊帳號已存在 → 409 Conflict */
    @ExceptionHandler(DuplicateUsernameException.class)
    public ResponseEntity<Map<String, Object>> handleDuplicateUsername(DuplicateUsernameException ex) {
        return errorResponse(HttpStatus.CONFLICT, ex.getMessage());
    }

    /** 登入帳號或密碼錯誤 → 401 Unauthorized（不要透露是帳號不存在還是密碼錯，避免帳號列舉攻擊） */
    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<Map<String, Object>> handleBadCredentials(BadCredentialsException ex) {
        return errorResponse(HttpStatus.UNAUTHORIZED, "帳號或密碼錯誤");
    }

    /**
     * 保底處理：任何沒被上面攔截到的未預期例外 → 500 Internal Server Error。
     * 避免把內部例外訊息（stack trace、SQL 錯誤細節等）直接回給前端，只記 log 供除錯，
     * 對外一律回覆固定訊息。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception ex) {
        log.error("[GlobalExceptionHandler] 未預期例外", ex);
        return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "系統發生未預期錯誤，請稍後再試");
    }

    private ResponseEntity<Map<String, Object>> errorResponse(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }
    /** 方法層級 @PreAuthorize 權限不足 → 403(URL 層級的權限不足已經由 RestAccessDeniedHandler 處理，
     這裡補的是方法層級的，因為 @PreAuthorize 丟出的例外比 GlobalExceptionHandler 更早被攔到） */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(AccessDeniedException ex) {
        return errorResponse(HttpStatus.FORBIDDEN, "權限不足，無法執行此操作");
    }

}