package com.aiinsight.llm;

import java.util.List;

public record ChatRequest(
        List<ChatMessage> messages,
        ChatOptions options
) {
}
