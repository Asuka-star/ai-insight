package com.aiinsight.dto;

public record LlmStatusResponse(
        boolean available,
        boolean apiKeyConfigured,
        String provider,
        String model,
        String baseUrl,
        String completionsPath
) {
}
