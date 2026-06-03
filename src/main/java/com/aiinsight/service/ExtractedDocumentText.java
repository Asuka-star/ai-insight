package com.aiinsight.service;

import java.util.Map;

public record ExtractedDocumentText(
        String title,
        String mediaType,
        String originalFilename,
        String text,
        Map<String, String> metadata
) {
}
