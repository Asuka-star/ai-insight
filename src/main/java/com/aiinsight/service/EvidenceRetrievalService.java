package com.aiinsight.service;

import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.model.run.EvidenceChunk;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class EvidenceRetrievalService {

    private static final int DEFAULT_TOP_K = 5;

    public List<EvidenceChunk> retrieve(AnalysisRun run, String query, Integer topK) {
        return retrieve(run, query, null, null, topK);
    }

    public List<EvidenceChunk> retrieve(AnalysisRun run, String query, String competitor, String dimension, Integer topK) {
        int limit = topK == null || topK <= 0 ? DEFAULT_TOP_K : topK;
        String effectiveQuery = String.join(" ",
                query == null ? "" : query,
                competitor == null ? "" : competitor,
                dimension == null ? "" : dimension
        );
        Set<String> queryTerms = expandedTerms(effectiveQuery, dimension);
        if (queryTerms.isEmpty()) {
            return run.getEvidenceChunks().stream()
                    .limit(limit)
                    .map(this::copy)
                    .toList();
        }
        return run.getEvidenceChunks().stream()
                .map(chunk -> scoredCopy(chunk, queryTerms, competitor, dimension))
                .filter(chunk -> chunk.getScore() > 0)
                .sorted(Comparator.comparingDouble(EvidenceChunk::getScore).reversed())
                .limit(limit)
                .toList();
    }

    private EvidenceChunk scoredCopy(EvidenceChunk chunk, Set<String> queryTerms, String competitor, String dimension) {
        EvidenceChunk copy = copy(chunk);
        String haystack = String.join(" ",
                nullToEmpty(chunk.getTitle()),
                nullToEmpty(chunk.getUrl()),
                String.join(" ", chunk.getHeadingPath() == null ? List.of() : chunk.getHeadingPath()),
                nullToEmpty(chunk.getContentKind()),
                nullToEmpty(chunk.getSourceType()),
                nullToEmpty(chunk.getSourceAuthority()),
                nullToEmpty(chunk.getText())
        ).toLowerCase(Locale.ROOT);
        double keywordScore = 0;
        for (String term : queryTerms) {
            if (haystack.contains(term)) {
                keywordScore += term.length() <= 2 ? 0.5 : 1.0;
            }
        }
        double contentBoost = contentKindBoost(chunk, dimension);
        double competitorBoost = StringUtils.hasText(competitor) && containsCompetitorToken(haystack, competitor) ? 1.5 : 0;
        double score = keywordScore > 0 || (contentBoost > 0 && competitorBoost > 0)
                ? keywordScore + contentBoost + competitorBoost + sourceAuthorityBoost(chunk) + sourceQualityBoost(chunk)
                : 0;
        copy.setScore(score);
        return copy;
    }

    private double contentKindBoost(EvidenceChunk chunk, String dimension) {
        String expectedKind = expectedContentKind(dimension);
        if (!StringUtils.hasText(expectedKind)) {
            return 0;
        }
        String actualKind = normalize(chunk.getContentKind());
        if (expectedKind.equals(actualKind)) {
            return 2.0;
        }
        if (("permission".equals(expectedKind) && "security".equals(actualKind))
                || ("security".equals(expectedKind) && "permission".equals(actualKind))) {
            return 1.2;
        }
        return 0;
    }

    private double sourceAuthorityBoost(EvidenceChunk chunk) {
        return switch (normalizeUpper(chunk.getSourceAuthority())) {
            case "FIRST_PARTY_OFFICIAL", "FIRST_PARTY_DOCS" -> 1.5;
            case "FIRST_PARTY_BLOG", "THIRD_PARTY_AUTHORITATIVE", "USER_PROVIDED", "INTERNAL_ONLY" -> 0.8;
            case "COMMUNITY", "SEARCH_SNIPPET" -> -0.3;
            default -> 0;
        };
    }

    private double sourceQualityBoost(EvidenceChunk chunk) {
        return switch (normalizeUpper(chunk.getSourceQuality())) {
            case "HIGH", "INTERNAL_ONLY" -> 0.8;
            case "LOW" -> -0.4;
            case "UNUSABLE" -> -2.0;
            default -> 0;
        };
    }

    private EvidenceChunk copy(EvidenceChunk chunk) {
        EvidenceChunk copy = new EvidenceChunk(
                chunk.getChunkKey(),
                chunk.getSourceCitationKey(),
                chunk.getChunkIndex(),
                chunk.getTitle(),
                chunk.getUrl(),
                chunk.getText()
        );
        copy.setId(chunk.getId());
        copy.setHeadingPath(chunk.getHeadingPath());
        copy.setContentKind(chunk.getContentKind());
        copy.setSourceType(chunk.getSourceType());
        copy.setSourceAuthority(chunk.getSourceAuthority());
        copy.setSourceQuality(chunk.getSourceQuality());
        copy.setTextHash(chunk.getTextHash());
        copy.setEmbeddingModel(chunk.getEmbeddingModel());
        copy.setEmbeddedAt(chunk.getEmbeddedAt());
        copy.setScore(chunk.getScore());
        copy.setCreatedAt(chunk.getCreatedAt());
        return copy;
    }

    private Set<String> expandedTerms(String query, String dimension) {
        Set<String> terms = terms(query);
        switch (expectedContentKind(dimension + " " + query)) {
            case "pricing" -> terms.addAll(List.of("pricing", "price", "plan", "billing", "free", "enterprise", "价格", "定价", "套餐", "付费"));
            case "permission" -> terms.addAll(List.of("permission", "permissions", "admin", "role", "rbac", "saml", "sso", "scim", "权限", "角色", "管理员"));
            case "security" -> terms.addAll(List.of("security", "compliance", "privacy", "saml", "sso", "scim", "安全", "合规", "隐私"));
            case "integration" -> terms.addAll(List.of("integration", "integrations", "api", "webhook", "集成", "接口"));
            case "ai_feature" -> terms.addAll(List.of("ai", "assistant", "copilot", "search", "智能", "生成式", "搜索"));
            default -> {
            }
        }
        return terms;
    }

    private String expectedContentKind(String dimension) {
        String normalized = normalize(dimension);
        if (containsAny(normalized, "pricing", "price", "billing", "plan", "价格", "定价", "套餐", "付费", "商业模式")) {
            return "pricing";
        }
        if (containsAny(normalized, "permission", "admin", "role", "rbac", "权限", "角色", "管理员")) {
            return "permission";
        }
        if (containsAny(normalized, "security", "compliance", "privacy", "安全", "合规", "隐私")) {
            return "security";
        }
        if (containsAny(normalized, "integration", "api", "webhook", "集成", "接口")) {
            return "integration";
        }
        if (containsAny(normalized, " ai ", "ai搜索", "assistant", "copilot", "智能", "生成式", "搜索")) {
            return "ai_feature";
        }
        return "";
    }

    private Set<String> terms(String query) {
        Set<String> terms = new LinkedHashSet<>();
        if (!StringUtils.hasText(query)) {
            return terms;
        }
        String normalized = query.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{IsHan}a-z0-9]+", " ")
                .trim();
        for (String part : normalized.split("\\s+")) {
            if (part.length() >= 2) {
                terms.add(part);
            }
        }
        terms.addAll(chineseBigrams(normalized));
        return terms;
    }

    private List<String> chineseBigrams(String text) {
        List<String> bigrams = new ArrayList<>();
        String chineseOnly = text.replaceAll("[^\\p{IsHan}]", "");
        for (int i = 0; i < chineseOnly.length() - 1; i++) {
            bigrams.add(chineseOnly.substring(i, i + 2));
        }
        return bigrams;
    }

    private boolean containsCompetitorToken(String searchable, String competitor) {
        for (String token : normalize(competitor).split("[^a-z0-9\\u4e00-\\u9fa5]+")) {
            if (token.length() >= 2 && searchable.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsAny(String text, String... patterns) {
        for (String pattern : patterns) {
            if (text.contains(pattern.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
    }

    private String normalizeUpper(String value) {
        return value == null ? "" : value.toUpperCase(Locale.ROOT).trim();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
