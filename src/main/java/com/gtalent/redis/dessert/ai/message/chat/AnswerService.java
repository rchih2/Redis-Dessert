package com.gtalent.redis.dessert.ai.message.chat;

import com.gtalent.redis.dessert.ai.message.keyword.KeywordChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AnswerService {

    private final KeywordChatService keywordChatService;

    public String answer(String userMessage) {
        return keywordChatService.answer(userMessage);
    }
}
