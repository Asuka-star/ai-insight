package com.aiinsight.service;

import com.aiinsight.model.run.EmbeddingCacheEntry;
import com.aiinsight.model.run.EvidenceChunk;
import com.aiinsight.observability.AgentTraceContext;
import com.aiinsight.repository.AnalysisRunRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
public class EvidenceEmbeddingService {

    private final EmbeddingClient embeddingClient;
    private final EmbeddingProperties properties;
    private final AnalysisRunRepository repository;

    @Autowired
    public EvidenceEmbeddingService(EmbeddingClient embeddingClient,
                                    EmbeddingProperties properties,
                                    AnalysisRunRepository repository) {
        this.embeddingClient = embeddingClient == null ? new NoopEmbeddingClient() : embeddingClient;
        this.properties = properties == null ? new EmbeddingProperties() : properties;
        this.repository = repository;
    }

    public EvidenceEmbeddingService(EmbeddingClient embeddingClient, EmbeddingProperties properties) {
        this(embeddingClient, properties, null);
    }

    public static EvidenceEmbeddingService disabled() {
        return new EvidenceEmbeddingService(new NoopEmbeddingClient(), new EmbeddingProperties());
    }

    public List<EvidenceChunk> embedChunks(List<EvidenceChunk> chunks) {
        if (chunks == null || chunks.isEmpty() || !embeddingClient.isAvailable()) {
            return chunks == null ? List.of() : chunks;
        }
        List<EvidenceChunk> candidates = chunks.stream()
                .filter(this::needsEmbedding)
                .toList();
        if (candidates.isEmpty()) {
            return chunks;
        }
        List<EmbeddingCandidate> missCandidates = applyCachedEmbeddings(candidates);
        int batchSize = Math.max(1, Math.min(properties.getMaxBatchSize(), 128));
        int batchCount = 0;
        for (int start = 0; start < missCandidates.size(); start += batchSize) {
            List<EmbeddingCandidate> batch = missCandidates.subList(start, Math.min(start + batchSize, missCandidates.size()));
            embedBatch(batch);
            batchCount++;
        }
        int cacheHits = candidates.size() - missCandidates.size();
        log.info("Evidence chunk embedding completed: model={}, totalChunks={}, candidates={}, cacheHits={}, cacheMisses={}, batches={}",
                embeddingClient.model(),
                chunks.size(),
                candidates.size(),
                cacheHits,
                missCandidates.size(),
                batchCount);
        AgentTraceContext.recordProcessSummary("""
                Evidence embedding:
                - model=%s
                - totalChunks=%d
                - candidates=%d
                - cacheHits=%d
                - cacheMisses=%d
                - batches=%d
                """.formatted(
                embeddingClient.model(),
                chunks.size(),
                candidates.size(),
                cacheHits,
                missCandidates.size(),
                batchCount
        ).trim());
        return chunks;
    }

    private List<EmbeddingCandidate> applyCachedEmbeddings(List<EvidenceChunk> candidates) {
        List<EmbeddingCandidate> embeddingCandidates = candidates.stream()
                .map(this::embeddingCandidate)
                .toList();
        if (!properties.isCacheEnabled() || repository == null || embeddingCandidates.isEmpty()) {
            return embeddingCandidates;
        }
        Map<String, EmbeddingCacheEntry> cached = repository.findCachedEmbeddings(
                embeddingCandidates.stream().map(EmbeddingCandidate::inputHash).collect(Collectors.toSet()),
                embeddingClient.model(),
                effectiveDimensions()
        );
        if (cached.isEmpty()) {
            return embeddingCandidates;
        }
        Instant now = Instant.now();
        List<EmbeddingCandidate> misses = new ArrayList<>();
        for (EmbeddingCandidate candidate : embeddingCandidates) {
            EmbeddingCacheEntry entry = cached.get(candidate.inputHash());
            if (entry == null || entry.embedding() == null || entry.embedding().isEmpty() || isCacheExpired(entry)) {
                misses.add(candidate);
                continue;
            }
            applyEmbedding(candidate.chunk(), entry.embedding(), now);
            saveCachedEmbedding(candidate, entry.embedding(), now);
        }
        return misses;
    }

    private void embedBatch(List<EmbeddingCandidate> batch) {
        try {
            List<String> inputs = batch.stream()
                    .map(EmbeddingCandidate::input)
                    .toList();
            List<List<Double>> vectors = embeddingClient.embed(inputs);
            if (vectors.size() != batch.size()) {
                throw new IllegalStateException("Embedding response size mismatch: expected "
                        + batch.size() + " but got " + vectors.size());
            }
            Instant embeddedAt = Instant.now();
            for (int i = 0; i < batch.size(); i++) {
                List<Double> vector = vectors.get(i);
                if (vector == null || vector.isEmpty()) {
                    continue;
                }
                EmbeddingCandidate candidate = batch.get(i);
                applyEmbedding(candidate.chunk(), vector, embeddedAt);
                saveCachedEmbedding(candidate, vector, embeddedAt);
            }
        } catch (RuntimeException ex) {
            log.warn("Evidence chunk embedding failed; keyword retrieval fallback remains available: model={}, batchSize={}, exceptionType={}, message={}",
                    embeddingClient.model(),
                    batch.size(),
                    ex.getClass().getName(),
                    ex.getMessage());
        }
    }

    private boolean needsEmbedding(EvidenceChunk chunk) {
        if (chunk == null || !StringUtils.hasText(chunk.getText())) {
            return false;
        }
        if (chunk.getEmbedding() == null || chunk.getEmbedding().isEmpty()) {
            return true;
        }
        if (!embeddingClient.model().equals(chunk.getEmbeddingModel())) {
            return true;
        }
        return properties.getDimensions() > 0 && chunk.getEmbedding().size() != properties.getDimensions();
    }

    private void applyEmbedding(EvidenceChunk chunk, List<Double> vector, Instant embeddedAt) {
        chunk.setEmbedding(new ArrayList<>(vector));
        chunk.setEmbeddingModel(embeddingClient.model());
        chunk.setEmbeddedAt(embeddedAt);
    }

    private void saveCachedEmbedding(EmbeddingCandidate candidate, List<Double> vector, Instant embeddedAt) {
        if (!properties.isCacheEnabled() || repository == null || vector == null || vector.isEmpty()) {
            return;
        }
        repository.saveCachedEmbedding(new EmbeddingCacheEntry(
                candidate.inputHash(),
                candidate.chunk().getTextHash(),
                embeddingClient.model(),
                candidate.dimensions(),
                new ArrayList<>(vector),
                embeddedAt,
                embeddedAt,
                1
        ));
    }

    private boolean isCacheExpired(EmbeddingCacheEntry entry) {
        if (properties.getCacheTtl() == null
                || properties.getCacheTtl().isZero()
                || properties.getCacheTtl().isNegative()) {
            return false;
        }
        Instant lastUsedAt = entry.lastUsedAt() == null ? entry.createdAt() : entry.lastUsedAt();
        return lastUsedAt != null && lastUsedAt.isBefore(Instant.now().minus(properties.getCacheTtl()));
    }

    private String embeddingInput(EvidenceChunk chunk) {
        return """
                Title: %s
                Source type: %s
                Source authority: %s
                Source quality: %s
                Content kind: %s
                Heading path: %s
                Text:
                %s
                """.formatted(
                nullToEmpty(chunk.getTitle()),
                nullToEmpty(chunk.getSourceType()),
                nullToEmpty(chunk.getSourceAuthority()),
                nullToEmpty(chunk.getSourceQuality()),
                nullToEmpty(chunk.getContentKind()),
                chunk.getHeadingPath() == null ? "" : String.join(" > ", chunk.getHeadingPath()),
                chunk.getText()
        );
    }

    private EmbeddingCandidate embeddingCandidate(EvidenceChunk chunk) {
        String input = embeddingInput(chunk);
        return new EmbeddingCandidate(chunk, input, sha256(input), effectiveDimensions());
    }

    private int effectiveDimensions() {
        return Math.max(0, properties.getDimensions());
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append("%02x".formatted(b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private record EmbeddingCandidate(EvidenceChunk chunk, String input, String inputHash, int dimensions) {
    }
}
