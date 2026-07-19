package com.gtalent.redis.dessert.ai.controller;

import com.gtalent.redis.dessert.ai.dto.ChatRequestDTO;
import com.gtalent.redis.dessert.ai.dto.ChatResponseDTO;
import com.gtalent.redis.dessert.ai.exception.AiChatException;
import com.gtalent.redis.dessert.ai.model.ChatMessageHistory;
import com.gtalent.redis.dessert.ai.service.AiChatService;
import com.gtalent.redis.dessert.ai.service.MongoDataTrackingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 甜點 AI 顧問對話 API。
 *
 * 回應格式統一為 {success, data|message}，
 * 對齊文件第 8 節「建議後續工作」中提到希望統一的錯誤/成功格式，
 * 但目前專案裡 POST /api/orders 仍是舊格式，尚未全站統一 —— 這點請留意，
 * 若之後做全域 @ControllerAdvice，記得一併把這支 API 納入同一套轉換邏輯。
 */
@Slf4j
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class DessertAiChatController {

    private final AiChatService aiChatService;
    private final MongoDataTrackingService mongoDataTrackingService;

    @PostMapping("/chat")
    public ResponseEntity<Map<String, Object>> chat(@Valid @RequestBody ChatRequestDTO request) {
        try {
            ChatResponseDTO response = aiChatService.chat(request.getSessionId(), request.getMessage());
            return ResponseEntity.ok(successBody(response));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(errorBody(e.getMessage()));
        } catch (AiChatException e) {
            log.error("[DessertAiChatController] AI 對話流程失敗", e);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(errorBody(e.getMessage()));
        } catch (Exception e) {
            log.error("[DessertAiChatController] 未預期例外", e);
            return ResponseEntity.internalServerError().body(errorBody("系統發生未預期錯誤，請稍後再試"));
        }
    }

    private Map<String, Object> successBody(Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("data", data);
        return body;
    }

    /**
     * 查詢某場對話（依 sessionId）的完整歷史，依時間正序排列。
     *
     * <p>直接沿用既有的 {@link MongoDataTrackingService#getConversationHistory(String)}，
     * 不需要新增 Service 方法或 Repository 查詢。</p>
     */
    @GetMapping("/chat")
    public ResponseEntity<Map<String, Object>> getChatHistory(@RequestParam String sessionId) {
        try {
            List<ChatMessageHistory> history = mongoDataTrackingService.getConversationHistory(sessionId);
            return ResponseEntity.ok(successBody(history));
        } catch (Exception e) {
            log.error("[DessertAiChatController] 查詢對話歷史失敗, sessionId={}", sessionId, e);
            return ResponseEntity.internalServerError().body(errorBody("查詢對話歷史失敗，請稍後再試"));
        }
    }

    private Map<String, Object> errorBody(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("message", message);
        return body;
    }
}