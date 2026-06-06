package com.aiinsight.agent.node;

import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.model.run.EvidenceChunk;
import com.aiinsight.model.run.EvidenceSource;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.aiinsight.util.AgentUtils.containsAny;
import static com.aiinsight.util.AgentUtils.hasText;
import static com.aiinsight.util.AgentUtils.normalizeLower;
import static com.aiinsight.util.AgentUtils.normalizeUpper;
import static com.aiinsight.util.AgentUtils.nullToEmpty;

final class AgentEvidenceSupport {

    static final Set<String> GENERIC_TITLE_TOKENS = Set.of(
            "docs", "doc", "guide", "guides", "documentation", "pricing", "plans", "plan",
            "enterprise", "deployment", "overview", "security", "trust", "privacy", "product",
            "features", "feature", "integration", "integrations", "agent", "agents", "skills",
            "workflow", "workflows", "code", "coding", "developer", "developers"
    );
    static final Set<String> HIGH_PRECISION_SUPPORT_TERMS = Set.of(
            "sso", "scim", "saml", "soc", "soc2", "soc 2", "rbac", "iso", "hipaa", "gdpr",
            "bedrock", "proxy", "agent", "skills", "mcp", "workflow", "slack", "terminal", "ide",
            "vscode", "github", "gitlab"
    );

    private AgentEvidenceSupport() {
    }

    static Map<String, EvidenceSource> sourceByCitationKey(AnalysisRun run) {
        return run.getEvidenceSources().stream()
                .filter(source -> hasText(source.getCitationKey()))
                .collect(Collectors.toMap(
                        EvidenceSource::getCitationKey,
                        source -> source,
                        (first, ignored) -> first
                ));
    }

    static EvidenceSource sourceByCitationKey(AnalysisRun run, String citationKey) {
        return sourceByCitationKey(run).get(citationKey);
    }

    static int evidenceConfidenceScore(EvidenceSource source) {
        if (source == null) {
            return 0;
        }
        String quality = normalizeUpper(source.getSourceQuality());
        String status = normalizeUpper(source.getCollectionStatus());
        String freshness = normalizeUpper(source.getFreshness());
        boolean thirdPartyLike = thirdPartyLikeSource(source);
        if ("UNUSABLE".equals(quality) || "LOW".equals(quality)
                || "FETCH_FAILED".equals(status) || "BLOCKED_BY_ROBOTS".equals(status)
                || "SEARCH_RESULT_SNIPPET".equals(freshness)) {
            return 1;
        }
        if ("HIGH".equals(quality)) {
            return thirdPartyLike ? 2 : 3;
        }
        if (isAuthoritativeSourceType(source) && ("FETCHED".equals(status) || hasText(source.getRawText()))) {
            return thirdPartyLike ? 2 : 3;
        }
        if ("MEDIUM".equals(quality) || ("FETCHED".equals(status) && hasText(source.getSnippet()))) {
            return 2;
        }
        return hasText(source.getSnippet()) ? 2 : 1;
    }

    static String evidenceTier(EvidenceSource source) {
        int score = evidenceConfidenceScore(source);
        if (score >= 3) {
            return "strong";
        }
        if (score == 2) {
            return "medium";
        }
        return "weak";
    }

    static boolean isAuthoritativeSourceType(EvidenceSource source) {
        return Set.of("official_site", "docs", "product_docs", "pricing_page", "release_notes", "technical_blog", "authoritative_media")
                .contains(normalizeLower(source.getSourceType()));
    }

    static boolean thirdPartyLikeSource(EvidenceSource source) {
        String host = sourceHost(source.getUrl());
        if (host.endsWith(".test") && isAuthoritativeSourceType(source)) {
            return false;
        }
        String authority = normalizeUpper(source.getSourceAuthority());
        String sourceType = normalizeLower(source.getSourceType());
        if (authority.startsWith("THIRD_PARTY")
                || "COMMUNITY".equals(authority)
                || "SEARCH_SNIPPET".equals(authority)
                || "UNKNOWN".equals(authority)
                || sourceType.startsWith("third_party")
                || sourceType.contains("public_review")) {
            return true;
        }
        return host.endsWith(".ac.cn") || titleSuggestsDifferentPublisher(host, source.getTitle());
    }

    static String sourceHost(String url) {
        if (!hasText(url)) {
            return "";
        }
        try {
            String host = URI.create(url).getHost();
            return host == null ? "" : host.toLowerCase(Locale.ROOT);
        } catch (RuntimeException ex) {
            return "";
        }
    }

    static boolean titleSuggestsDifferentPublisher(String host, String title) {
        if (!hasText(host) || !hasText(title)) {
            return false;
        }
        String root = rootDomain(host);
        String leadingTitle = normalizeLower(title).split("\\s[-|]\\s", 2)[0];
        if (!hasText(root) || leadingTitle.contains(root)) {
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

    static String rootDomain(String host) {
        if (!hasText(host)) {
            return "";
        }
        String[] parts = host.toLowerCase(Locale.ROOT).split("\\.");
        if (parts.length < 2) {
            return host;
        }
        if (parts.length >= 3 && Set.of("com", "net", "org", "ac", "edu", "gov").contains(parts[parts.length - 2])) {
            return parts[parts.length - 3];
        }
        return parts[parts.length - 2];
    }

    static boolean sourceTextHasRiskSignal(String text, String risk) {
        String normalized = normalizeLower(text);
        return switch (risk) {
            case "SECURITY" -> containsAny(normalized,
                    "security", "permission", "compliance", "privacy", "trust", "sso", "scim", "saml", "soc",
                    "安全", "权限", "合规", "隐私", "审计", "单点登录");
            case "PERMISSION" -> containsAny(normalized,
                    "permission", "rbac", "role", "admin", "sso", "scim", "saml",
                    "权限", "角色", "管理员", "单点登录");
            case "DEPLOYMENT" -> containsAny(normalized,
                    "deployment", "deploy", "bedrock", "proxy", "vpc", "cloud",
                    "部署", "代理", "云");
            case "CUSTOMER_SIGNAL" -> containsAny(normalized,
                    "review", "feedback", "customer", "user", "survey", "interview",
                    "评价", "反馈", "用户", "调研", "访谈");
            case "pricing", "PRICING" -> containsAny(normalized,
                    "pricing", "price", "plan", "subscription", "billing", "$",
                    "价格", "定价", "套餐", "订阅", "付费", "企业版");
            case "security" -> sourceTextHasRiskSignal(text, "SECURITY");
            case "deployment" -> sourceTextHasRiskSignal(text, "DEPLOYMENT");
            default -> true;
        };
    }

    static String evidenceSourceText(EvidenceSource source) {
        return "%s %s %s %s %s %s".formatted(
                nullToEmpty(source.getTitle()),
                nullToEmpty(source.getUrl()),
                nullToEmpty(source.getSourceType()),
                nullToEmpty(source.getSnippet()),
                nullToEmpty(source.getRawText()),
                nullToEmpty(source.getComplianceNote())
        );
    }

    static String evidenceChunkText(EvidenceChunk chunk) {
        return "%s %s %s %s %s".formatted(
                nullToEmpty(chunk.getTitle()),
                nullToEmpty(chunk.getUrl()),
                chunk.getHeadingPath() == null ? "" : String.join(" ", chunk.getHeadingPath()),
                nullToEmpty(chunk.getContentKind()),
                nullToEmpty(chunk.getText())
        );
    }

    static String pricingChunkText(EvidenceChunk chunk) {
        return "%s %s %s %s %s".formatted(
                nullToEmpty(chunk.getContentKind()),
                nullToEmpty(chunk.getSourceType()),
                nullToEmpty(chunk.getTitle()),
                chunk.getHeadingPath() == null ? "" : String.join(" ", chunk.getHeadingPath()),
                nullToEmpty(chunk.getText())
        );
    }

    static boolean containsPricingSignal(String value, List<String> pricingMarkers) {
        return containsAny(normalizeLower(value), pricingMarkers.toArray(String[]::new));
    }
}
