package com.aiinsight.service;

public record SurveyResultBatch(
        String batchId,
        String title,
        int responseCount,
        String rawText,
        java.util.List<String> tableHeaders,
        java.util.List<java.util.List<String>> tableRows
) {
}
