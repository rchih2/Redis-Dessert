package com.gtalent.redis.dessert.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * POST /api/ai/chat 的請求主體
 */
@Data
public class ChatRequestDTO {

    @NotBlank(message = "sessionId 不可為空")
    private String sessionId;

    @NotBlank(message = "message 不可為空")
    @Size(max = 1000, message = "message 長度不可超過 1000 字")
    private String message;
}