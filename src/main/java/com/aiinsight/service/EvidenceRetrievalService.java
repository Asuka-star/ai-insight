package com.aiinsight.service;

import com.aiinsight.repository.AnalysisRunRepository;
import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.model.run.EvidenceChunk;
import com.aiinsight.model.run.EvidenceSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
@Slf4j
public class EvidenceRetrievalService {

    private static final int DEFAULT_TOP_K = 5;
    private static final int MAX_GLOBAL_KEYWORD_CANDIDATES = 1_000;
    private static final double SEMANTIC_MATCH_THRESHOLD = 0.35;
    private static final double SEMANTIC_SCORE_WEIGHT = 4.0;

    private final EmbeddingClient embeddingClient;
    private final AnalysisRunRepository repository;

    public EvidenceRetrievalService() {
        this(new NoopEmbeddingClient());
    }

    public EvidenceRetrievalService(EmbeddingClient embeddingClient) {
        this(embeddingClient, null);
    }

    @Autowired
    public EvidenceRetrievalService(EmbeddingClient embeddingClient, AnalysisRunRepository repository) {
        this.embeddingClient = embeddingClient == null ? new NoopEmbeddingClient() : embeddingClient;
        this.repository = repository;
    }

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
        List<Double> queryEmbedding = queryEmbedding(effectiveQuery);
        if (queryTerms.isEmpty() && queryEmbedding.isEmpty()) {
            return run.getEvidenceChunks().stream()
                    .limit(limit)
                    .map(this::copy)
                    .toList();
        }
        List<EvidenceChunk> results = retrievalCandidates(run, queryEmbedding, limit).stream()
                .map(chunk -> scoredCopy(chunk, queryTerms, competitor, dimension, queryEmbedding))
                .filter(chunk -> chunk.getScore() > 0)
                .sorted(Comparator.comparingDouble(EvidenceChunk::getScore).reversed())
                .limit(limit)
                .toList();
        return localizeGlobalChunks(run, results);
    }

    private EvidenceChunk scoredCopy(EvidenceChunk chunk,
                                     Set<String> queryTerms,
                                     String competitor,
                                     String dimension,
                                     List<Double> queryEmbedding) {
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
        double semanticScore = semanticScore(queryEmbedding, chunk);
        double score = keywordScore > 0
                || semanticScore >= SEMANTIC_MATCH_THRESHOLD
                || (contentBoost > 0 && competitorBoost > 0)
                ? keywordScore
                + (semanticScore * SEMANTIC_SCORE_WEIGHT)
                + contentBoost
                + competitorBoost
                + sourceAuthorityBoost(chunk)
                + sourceQualityBoost(chunk)
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
        copy.setEmbedding(chunk.getEmbedding() == null ? List.of() : new ArrayList<>(chunk.getEmbedding()));
        copy.setScore(chunk.getScore());
        copy.setCreatedAt(chunk.getCreatedAt());
        return copy;
    }

    private List<EvidenceChunk> retrievalCandidates(AnalysisRun run, List<Double> queryEmbedding, int limit) {
        Map<String, EvidenceChunk> candidates = new LinkedHashMap<>();
        for (EvidenceChunk chunk : run.getEvidenceChunks()) {
            candidates.put(chunkIdentity(chunk), chunk);
        }
        // 检索候选分三层：当前 run 的 chunk、当前 run 的向量投影、全局 RAG。
        // 全局 RAG 即使没有 pgvector，也会通过 findGlobalEvidenceChunks 进入关键词召回。
        if (repository != null && queryEmbedding != null && !queryEmbedding.isEmpty()) {
            repository.retrieveEvidenceByVector(
                            run.getId(),
                            queryEmbedding,
                            embeddingClient.model(),
                            Math.max(limit * 4, DEFAULT_TOP_K)
                    )
                    .ifPresent(vectorChunks -> vectorChunks.forEach(chunk ->
                            candidates.put(chunkIdentity(chunk), chunk)));
            repository.retrieveGlobalEvidenceByVector(
                            queryEmbedding,
                            embeddingClient.model(),
                            Math.max(limit * 4, DEFAULT_TOP_K)
                    )
                    .ifPresent(vectorChunks -> vectorChunks.forEach(chunk ->
                            addGlobalCandidate(candidates, run, chunk)));
        }
        if (repository != null) {
            repository.findGlobalEvidenceChunks(MAX_GLOBAL_KEYWORD_CANDIDATES)
                    .forEach(chunk -> addGlobalCandidate(candidates, run, chunk));
        }
        return new ArrayList<>(candidates.values());
    }

    private void addGlobalCandidate(Map<String, EvidenceChunk> candidates, AnalysisRun run, EvidenceChunk chunk) {
        if (!isGlobalChunk(chunk) || duplicatesRunChunk(run, chunk)) {
            return;
        }
        // 加 global: 前缀，避免全局 chunk 的 id/chunkKey 与当前 run 内 chunk 冲突。
        candidates.put("global:" + chunkIdentity(chunk), chunk);
    }

    private boolean duplicatesRunChunk(AnalysisRun run, EvidenceChunk chunk) {
        if (!StringUtils.hasText(chunk.getTextHash())) {
            return false;
        }
        return run.getEvidenceChunks().stream()
                .anyMatch(existing -> chunk.getTextHash().equals(existing.getTextHash()));
    }

    private List<EvidenceChunk> localizeGlobalChunks(AnalysisRun run, List<EvidenceChunk> chunks) {
        List<EvidenceChunk> localized = new ArrayList<>();
        for (EvidenceChunk chunk : chunks) {
            localized.add(isGlobalChunk(chunk) ? localizeGlobalChunk(run, chunk) : chunk);
        }
        if (!localized.isEmpty()) {
            run.getResearchPackage().setSources(new ArrayList<>(run.getEvidenceSources()));
        }
        return localized;
    }

    private EvidenceChunk localizeGlobalChunk(AnalysisRun run, EvidenceChunk chunk) {
        // 检索结果不能直接暴露全局库里的 S 编号；挂载到当前 run 后重写为本 run 的 citation。
        EvidenceSource source = globalSourceFor(run, chunk);
        EvidenceChunk copy = copy(chunk);
        copy.setSourceCitationKey(source.getCitationKey());
        copy.setChunkKey(source.getCitationKey() + "-C" + Math.max(1, chunk.getChunkIndex()));
        copy.setTitle(source.getTitle());
        copy.setUrl(source.getUrl());
        return copy;
    }

    private EvidenceSource globalSourceFor(AnalysisRun run, EvidenceChunk chunk) {
        String url = chunk.getUrl();
        return run.getEvidenceSources().stream()
                .filter(source -> url.equals(source.getUrl()))
                .findFirst()
                .orElseGet(() -> attachGlobalSource(run, chunk));
    }

    private EvidenceSource attachGlobalSource(AnalysisRun run, EvidenceChunk chunk) {
        EvidenceSource source = new EvidenceSource(
                nextCitationKey(run),
                StringUtils.hasText(chunk.getTitle()) ? chunk.getTitle() : "全局用户资源",
                chunk.getUrl(),
                StringUtils.hasText(chunk.getSourceType()) ? chunk.getSourceType() : "global_user_document",
                "READY",
                "GLOBAL_RAG",
                StringUtils.hasText(chunk.getSourceQuality()) ? chunk.getSourceQuality() : "USER_PROVIDED",
                "NONE",
                abbreviate(chunk.getText(), 220),
                chunk.getText(),
                "来自全局 RAG 资源库，已挂载到当前分析任务用于证据引用。"
        );
        source.setSourceAuthority(StringUtils.hasText(chunk.getSourceAuthority()) ? chunk.getSourceAuthority() : "USER_PROVIDED");
        source.setCanonicalHost("global-document");
        source.setPublisherName("全局用户资源");
        source.setGlobalResource(true);
        run.getEvidenceSources().add(source);
        return source;
    }

    private String nextCitationKey(AnalysisRun run) {
        int next = run.getEvidenceSources().stream()
                .map(EvidenceSource::getCitationKey)
                .filter(StringUtils::hasText)
                .mapToInt(this::citationNumber)
                .max()
                .orElse(0) + 1;
        return "S" + next;
    }

    private int citationNumber(String citationKey) {
        if (citationKey == null || !citationKey.startsWith("S")) {
            return 0;
        }
        try {
            return Integer.parseInt(citationKey.substring(1));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private boolean isGlobalChunk(EvidenceChunk chunk) {
        return chunk != null && chunk.getUrl() != null && chunk.getUrl().startsWith("global-document://");
    }

    private String abbreviate(String value, int maxLength) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxLength - 3)).trim() + "...";
    }

    private String chunkIdentity(EvidenceChunk chunk) {
        if (chunk.getId() != null) {
            return chunk.getId().toString();
        }
        if (StringUtils.hasText(chunk.getChunkKey())) {
            return chunk.getChunkKey();
        }
        return "%s-%d".formatted(chunk.getSourceCitationKey(), chunk.getChunkIndex());
    }

    private List<Double> queryEmbedding(String query) {
        if (!StringUtils.hasText(query) || !embeddingClient.isAvailable()) {
            return List.of();
        }
        try {
            List<List<Double>> embeddings = embeddingClient.embed(List.of(query));
            if (embeddings.isEmpty() || embeddings.get(0) == null) {
                return List.of();
            }
            return embeddings.get(0);
        } catch (RuntimeException ex) {
            log.warn("Evidence semantic query embedding failed; keyword retrieval fallback remains available: model={}, exceptionType={}, message={}",
                    embeddingClient.model(),
                    ex.getClass().getName(),
                    ex.getMessage());
            return List.of();
        }
    }

    private boolean hasCompatibleEmbedding(EvidenceChunk chunk) {
        return chunk != null
                && chunk.getEmbedding() != null
                && !chunk.getEmbedding().isEmpty()
                && embeddingClient.model().equals(chunk.getEmbeddingModel());
    }

    private double semanticScore(List<Double> queryEmbedding, EvidenceChunk chunk) {
        if (!hasCompatibleEmbedding(chunk)) {
            return 0;
        }
        List<Double> chunkEmbedding = chunk.getEmbedding();
        if (queryEmbedding == null || queryEmbedding.isEmpty() || chunkEmbedding == null || chunkEmbedding.isEmpty()) {
            return 0;
        }
        int dimensions = Math.min(queryEmbedding.size(), chunkEmbedding.size());
        if (dimensions == 0) {
            return 0;
        }
        double dot = 0;
        double queryNorm = 0;
        double chunkNorm = 0;
        for (int i = 0; i < dimensions; i++) {
            double queryValue = queryEmbedding.get(i) == null ? 0 : queryEmbedding.get(i);
            double chunkValue = chunkEmbedding.get(i) == null ? 0 : chunkEmbedding.get(i);
            dot += queryValue * chunkValue;
            queryNorm += queryValue * queryValue;
            chunkNorm += chunkValue * chunkValue;
        }
        if (queryNorm == 0 || chunkNorm == 0) {
            return 0;
        }
        return Math.max(0, dot / (Math.sqrt(queryNorm) * Math.sqrt(chunkNorm)));
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
