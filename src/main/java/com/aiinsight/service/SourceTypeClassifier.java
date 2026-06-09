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

        if (isVideoHost(url)) {
            return "video";
        }
        if (isPublicReviewHost(url)
                || containsAny(combined, "reddit.", "forum", "community", "discuss", "review", "reviews", "g2.com", "capterra")) {
            return "public_review";
        }
        if (containsAny(combined, "/pricing", "/plans", " pricing", " plans")) {
            return isFirstPartyReferenceUrl(url, title) ? "pricing_page" : "third_party_pricing_reference";
        }
        if (containsAny(combined, "/security", "/trust", "/privacy", " security", " compliance", " permissions")) {
            return isFirstPartyReferenceUrl(url, title) ? "security_docs" : "third_party_article";
        }
        if (containsAny(combined, "/enterprise", " enterprise", "/product/", "/products/")) {
            return isFirstPartyReferenceUrl(url, title) || looksLikeOfficialHost(url, title) ? "official_site" : "third_party_article";
        }
        if (containsAny(combined, "/integrations", "/integration", " integrations", " api ")) {
            return isFirstPartyReferenceUrl(url, title) ? "integration_docs" : "third_party_article";
        }
        if (containsAny(combined, "/docs", "/doc", "/help", "/reference", "/guide", " documentation", " docs")) {
            return isFirstPartyReferenceUrl(url, title) ? "docs" : "third_party_docs";
        }
        if (containsAny(combined, "/release", "/changelog", "release notes", "changelog")) {
            return isFirstPartyReferenceUrl(url, title) ? "release_notes" : "article";
        }
        if (looksLikeOfficialHost(url, title)) {
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
        return switch (normalizedType) {
            case "official_site", "docs", "product_docs", "pricing_page", "release_notes", "security_docs", "integration_docs" -> "HIGH";
            case "public_review", "public_reviews", "forum", "search_result_snippet", "video" -> "LOW";
            default -> "MEDIUM";
        };
    }

    public String authorityFor(String url, String sourceType) {
        String normalizedType = normalize(sourceType);
        ParsedUrl parsed = parse(url);
        String host = parsed == null ? "" : parsed.host();
        if (normalizedType.startsWith("user_")) {
            return "USER_PROVIDED";
        }
        if ("search_result_snippet".equals(normalizedType)) {
            return "SEARCH_SNIPPET";
        }
        if (containsAny(normalizedType, "public_review", "community", "forum") || isPublicReviewHost(url)) {
            return "COMMUNITY";
        }
        if (!StringUtils.hasText(host)) {
            return "UNKNOWN";
        }
        if (isThirdPartyHost(host) || normalizedType.startsWith("third_party")) {
            return "THIRD_PARTY_GENERAL";
        }
        if (containsAny(host, "docs.", "doc.", "help.", "support.", "developer.", "developers.", "api.", "reference.")) {
            return "FIRST_PARTY_DOCS";
        }
        if ("release_notes".equals(normalizedType) || "technical_blog".equals(normalizedType)) {
            return "FIRST_PARTY_BLOG";
        }
        if (containsAny(normalizedType, "docs", "security", "integration")) {
            // SourceType suggests docs/security/integration content, but we must verify the
            // host actually belongs to the product's official domain. Without this check,
            // third-party guide sites (e.g., verdent.ai, learn-X.com) get misclassified as
            // FIRST_PARTY_DOCS, which inflates their authority and misleads the Writer and
            // Reviewer into treating their content as authoritative.
            if (hasFirstPartyDocsHost(host, url)) {
                return "FIRST_PARTY_DOCS";
            }
            return "THIRD_PARTY_GENERAL";
        }
        if (looksLikeOfficialHost(url, "") || "pricing_page".equals(normalizedType)) {
            return "FIRST_PARTY_OFFICIAL";
        }
        return "UNKNOWN";
    }

    public String canonicalHost(String url) {
        ParsedUrl parsed = parse(url);
        return parsed == null ? "" : parsed.host();
    }

    public String publisherName(String url) {
        String host = canonicalHost(url);
        if (!StringUtils.hasText(host)) {
            return "";
        }
        String root = rootDomain(host);
        return StringUtils.hasText(root) ? root : host;
    }

    private boolean isFirstPartyReferenceUrl(String url, String title) {
        ParsedUrl parsed = parse(url);
        if (parsed == null || !StringUtils.hasText(parsed.host())) {
            return false;
        }
        if (isLocalHost(parsed.host())) {
            return true;
        }
        if (parsed.host().endsWith(".test")
                || isThirdPartyHost(parsed.host())
                || titleSuggestsDifferentPublisher(parsed.host(), title)) {
            return false;
        }
        if (containsAny(parsed.host(), "docs.", "doc.", "help.", "support.", "developer.", "developers.", "api.", "reference.", "learn.")) {
            return true;
        }
        String root = rootDomain(parsed.host());
        if (containsAny(root, "-", "learn", "log", "tutorial", "guide", "unofficial", "awesome")) {
            return false;
        }
        return looksLikeOfficialHost(url, title) || looksLikeReferencePath(parsed.path());
    }

    private boolean looksLikeOfficialHost(String url, String title) {
        ParsedUrl parsed = parse(url);
        if (parsed == null) {
            return false;
        }
        return StringUtils.hasText(parsed.host())
                && !isLocalHost(parsed.host())
                && !parsed.host().endsWith(".test")
                && !isThirdPartyHost(parsed.host())
                && !titleSuggestsDifferentPublisher(parsed.host(), title)
                && looksLikeOfficialPath(parsed.path());
    }

    private boolean looksLikeOfficialPath(String path) {
        if (!StringUtils.hasText(path) || "/".equals(path)) {
            return true;
        }
        String normalizedPath = path.replaceFirst("/+$", "");
        if (!normalizedPath.startsWith("/")) {
            return false;
        }
        String[] segments = semanticSegments(normalizedPath);
        if (segments.length == 0) {
            return true;
        }
        if (segments.length > 2) {
            return false;
        }
        return containsAny(
                "/" + segments[0],
                "/product", "/products", "/features", "/platform", "/enterprise",
                "/solutions", "/security", "/integrations", "/customers", "/about", "/company"
        );
    }

    private boolean looksLikeReferencePath(String path) {
        if (!StringUtils.hasText(path)) {
            return false;
        }
        String normalizedPath = path.replaceFirst("/+$", "");
        if (!normalizedPath.startsWith("/")) {
            return false;
        }
        String[] segments = semanticSegments(normalizedPath);
        if (segments.length > 3) {
            return false;
        }
        for (String segment : segments) {
            if (containsAny(
                    "/" + segment,
                    "/docs", "/doc", "/help", "/support", "/reference", "/guide", "/guides",
                    "/pricing", "/plans", "/api", "/integrations", "/changelog", "/release", "/releases"
            )) {
                return true;
            }
        }
        return false;
    }

    private String[] semanticSegments(String normalizedPath) {
        String[] rawSegments = normalizedPath.substring(1).split("/");
        if (rawSegments.length <= 1 || rawSegments[0].length() != 2) {
            return rawSegments;
        }
        return java.util.Arrays.copyOfRange(rawSegments, 1, rawSegments.length);
    }

    private boolean isVideoHost(String url) {
        ParsedUrl parsed = parse(url);
        return parsed != null && containsAny(parsed.host(), "youtube.", "youtu.be", "vimeo.", "bilibili.", "tiktok.");
    }

    private boolean isPublicReviewHost(String url) {
        ParsedUrl parsed = parse(url);
        return parsed != null && containsAny(parsed.host(), "reddit.", "g2.", "capterra.", "trustpilot.");
    }

    /**
     * Verifies that a host genuinely belongs to a first-party documentation domain.
     * Requires at least one strong signal: docs subdomain (docs.cursor.com), official
     * docs path (/docs/...), or a clean official-looking host. This prevents third-party
     * guide/tutorial sites from being elevated to FIRST_PARTY_DOCS authority.
     */
    private boolean hasFirstPartyDocsHost(String host, String url) {
        if (!StringUtils.hasText(host)) {
            return false;
        }
        // Strong signal: docs-related subdomain
        if (containsAny(host, "docs.", "doc.", "help.", "support.", "developer.", "developers.", "api.", "reference.")) {
            return true;
        }
        // Strong signal: docs path in URL
        ParsedUrl parsed = parse(url);
        if (parsed != null && containsAny(parsed.path(), "/docs", "/doc", "/help", "/reference", "/api")) {
            return true;
        }
        // Fallback: host must look like an official product site (clean domain, official path)
        return looksLikeOfficialHost(url, "");
    }

    private boolean isThirdPartyHost(String host) {
        return containsAny(
                host,
                "medium.", "substack.", "reddit.", "github.", "github.io", "gitbook.", "readthedocs.",
                ".ac.cn",
                "forum", "community", "news.", "learn-", "claudelog.", "cursor101.", "aicursor.",
                "forbes.", "techcrunch.", "theverge.", "wired.", "bloomberg.", "reuters.",
                "businessinsider.", "zdnet.", "cnbc.", "crunchbase.", "wikipedia.",
                "youtube.", "youtu.be", "vimeo.", "bilibili.", "linkedin.", "twitter.", "x.com",
                "facebook.", "g2.", "capterra.", "trustpilot."
        );
    }

    private boolean titleSuggestsDifferentPublisher(String host, String title) {
        if (!StringUtils.hasText(host) || !StringUtils.hasText(title)) {
            return false;
        }
        String root = rootDomain(host);
        String normalizedTitle = normalize(title);
        if (!StringUtils.hasText(root)) {
            return false;
        }
        String leadingTitle = normalizedTitle.split("\\s[-|]\\s", 2)[0];
        if (leadingTitle.contains(root)) {
            return false;
        }
        for (String token : leadingTitle.split("[^a-z0-9]+")) {
            if (token.length() < 3 || GENERIC_TITLE_TOKENS.contains(token)) {
                continue;
            }
            return !root.contains(token) && !token.contains(root);
        }
        return false;
    }

    private boolean isLocalHost(String host) {
        return "localhost".equals(host) || host.matches("\\d+\\.\\d+\\.\\d+\\.\\d+");
    }

    private ParsedUrl parse(String url) {
        if (!StringUtils.hasText(url)) {
            return null;
        }
        try {
            URI uri = URI.create(url);
            return new ParsedUrl(normalize(uri.getHost()), normalize(uri.getPath()));
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private String rootDomain(String host) {
        if (!StringUtils.hasText(host)) {
            return "";
        }
        String[] parts = host.split("\\.");
        if (parts.length < 2) {
            return host;
        }
        return parts[parts.length - 2];
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

    private static final java.util.Set<String> GENERIC_TITLE_TOKENS = java.util.Set.of(
            "docs", "doc", "guide", "guides", "documentation", "pricing", "plans", "plan",
            "enterprise", "deployment", "overview", "security", "trust", "privacy", "product",
            "features", "feature", "integration", "integrations", "agent", "agents", "skills",
            "workflow", "workflows", "code", "coding", "developer", "developers"
    );

    private record ParsedUrl(String host, String path) {
    }
}
