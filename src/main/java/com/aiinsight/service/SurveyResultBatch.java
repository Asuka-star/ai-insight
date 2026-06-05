package com.aiinsight.service;

public record SurveyResultBatch(
        String batchId,
        String title,
        int responseCount,
        String rawText
) {
}
