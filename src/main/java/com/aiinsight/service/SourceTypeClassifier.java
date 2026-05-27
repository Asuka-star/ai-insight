package com.aiinsight.service;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.Locale;

@Component
public class SourceTypeClassifier {

    public String classify(String url, String title) {
        String normalizedUrl = normalize(url);
        String normalizedTitle = normalize(title);
        String combined = normalizedUrl + " " + normalizedTitle;

        if (containsAny(combined, "/pricing", "/plans", " pricing", " plans", "价格", "定价")) {
            return "pricing_page";
        }
        if (containsAny(combined, "/docs", "/doc", "/help", "/reference", "/guide", " documentation", " docs", "文档")) {
            return "docs";
        }
        if (containsAny(combined, "/release", "/changelog", "release notes", "changelog", "更新日志")) {
            return "release_notes";
        }
        if (containsAny(combined, "reddit.", "forum", "community", "discuss", "review", "reviews", "g2.com", "capterra")) {
            return "public_review";
        }
        if (looksLikeOfficialHost(url)) {
            return "official_site";
        }
        return "article";
    }

    public String qualityFor(String sourceType, String collectionStatus, String freshness) {
        String normalizedType = normalize(sourceType);
        String normalizedStatus = normalize(collectionStatus);
        String normalizedFreshness = normalize(freshness);
        if (normalizedStatus.contains("fetch_failed")
                || normalizedStatus.contains("blocked")
                || normalizedFreshness.contains("fetch_failed")) {
            return "UNUSABLE";
        }
        if (normalizedType.startsWith("user_")) {
            return normalizedType.contains("interview") || normalizedType.contains("survey")
                    ? "INTERNAL_ONLY"
                    : "MEDIUM";
        }
        if (containsAny(normalizedType, "official_site", "docs", "pricing_page", "release_notes")) {
            return "HIGH";
        }
        if (containsAny(normalizedType, "public_review", "forum", "search_result_snippet")) {
            return "LOW";
        }
        return "MEDIUM";
    }

    private boolean looksLikeOfficialHost(String url) {
        if (!StringUtils.hasText(url)) {
            return false;
        }
        try {
            URI uri = URI.create(url);
            String host = normalize(uri.getHost());
            String path = normalize(uri.getPath());
            return StringUtils.hasText(host)
                    && !host.equals("localhost")
                    && !host.endsWith(".test")
                    && !host.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")
                    && !containsAny(
                            host,
                            "medium.", "substack.", "reddit.", "github.", "forum", "community", "news.",
                            "forbes.", "techcrunch.", "theverge.", "wired.", "bloomberg.", "reuters.",
                            "businessinsider.", "zdnet.", "cnbc.", "crunchbase.", "wikipedia.",
                            "youtube.", "linkedin.", "twitter.", "x.com", "facebook.", "g2.", "capterra."
                    )
                    && looksLikeOfficialPath(path);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private boolean looksLikeOfficialPath(String path) {
        if (!StringUtils.hasText(path) || "/".equals(path)) {
            return true;
        }
        String normalizedPath = path.replaceFirst("/+$", "");
        if (!normalizedPath.startsWith("/")) {
            return false;
        }
        String[] segments = normalizedPath.substring(1).split("/");
        if (segments.length > 2) {
            return false;
        }
        return containsAny(
                "/" + segments[0],
                "/product", "/products", "/features", "/platform", "/enterprise",
                "/solutions", "/security", "/customers", "/about", "/company"
        );
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
}
