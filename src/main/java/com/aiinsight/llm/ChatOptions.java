package com.aiinsight.llm;

public record ChatOptions(
        double temperature,
        int maxTokens
) {
    public static ChatOptions deterministic() {
        return new ChatOptions(0.2, 1800);
    }
}
