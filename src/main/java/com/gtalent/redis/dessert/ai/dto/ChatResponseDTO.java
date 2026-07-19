package com.gtalent.redis.dessert.ai.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * POST /api/ai/chat 的回應內容
 */
@Data
@Builder
public class ChatResponseDTO {

    private String sessionId;

    private String reply;

    /** 這次回覆是否有命中 RAG 知識庫 */
    private boolean ragHit;

    /** 命中的 RAG 文件數量，方便前端/除錯判斷回覆可信度 */
    private int contextDocCount;

    private LocalDateTime timestamp;
}