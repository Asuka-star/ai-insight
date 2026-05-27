package com.aiinsight.service;

import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class PageQualityEvaluator {

    private static final int MIN_USEFUL_TEXT_LENGTH = 180;

    public PageQualityResult evaluate(String title, String rawText, int statusCode, String contentType) {
        if (statusCode >= 500) {
            return PageQualityResult.unusable("HTTP_5XX", "UNUSABLE");
        }
        if (statusCode >= 400) {
            return PageQualityResult.unusable("HTTP_4XX", "UNUSABLE");
        }
        String normalizedContentType = normalize(contentType);
        if (!normalizedContentType.isBlank()
                && !normalizedContentType.contains("text/html")
                && !normalizedContentType.contains("text/plain")
                && !normalizedContentType.contains("application/xhtml")) {
            return PageQualityResult.unusable("NON_HTML", "UNUSABLE");
        }

        String text = rawText == null ? "" : rawText.trim();
        if (text.isBlank()) {
            return PageQualityResult.unusable("EMPTY_TEXT", "UNUSABLE");
        }

        String searchable = normalize(title) + " " + normalize(text);
        if (containsAny(searchable,
                "just a moment",
                "enable javascript and cookies",
                "cloudflare ray id",
                "attention required",
                "sorry, you have been blocked",
                "challenge-platform",
                "checking your browser")) {
            return PageQualityResult.unusable("ANTI_BOT_PAGE", "UNUSABLE");
        }
        if (containsAny(searchable,
                "sign in to continue",
                "login required",
                "please log in",
                "access denied",
                "subscription required")) {
            return PageQualityResult.unusable("LOGIN_REQUIRED", "UNUSABLE");
        }
        if (text.length() < MIN_USEFUL_TEXT_LENGTH) {
            return PageQualityResult.unusable("THIN_TEXT", "UNUSABLE");
        }
        return PageQualityResult.usable("NONE");
    }

    private boolean containsAny(String text, String... patterns) {
        for (String pattern : patterns) {
            if (text.contains(pattern)) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
    }

    public record PageQualityResult(boolean usable, String failureReason, String sourceQuality) {

        static PageQualityResult usable(String failureReason) {
            return new PageQualityResult(true, failureReason, "MEDIUM");
        }

        static PageQualityResult unusable(String failureReason, String sourceQuality) {
            return new PageQualityResult(false, failureReason, sourceQuality);
        }
    }
}
