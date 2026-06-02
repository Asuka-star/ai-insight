package com.aiinsight.service;

import com.aiinsight.model.run.EvidenceChunk;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
public class EvidenceEmbeddingService {

    private final EmbeddingClient embeddingClient;
    private final EmbeddingProperties properties;

    public EvidenceEmbeddingService(EmbeddingClient embeddingClient, EmbeddingProperties properties) {
        this.embeddingClient = embeddingClient == null ? new NoopEmbeddingClient() : embeddingClient;
        this.properties = properties == null ? new EmbeddingProperties() : properties;
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
        int batchSize = Math.max(1, Math.min(properties.getMaxBatchSize(), 128));
        for (int start = 0; start < candidates.size(); start += batchSize) {
            List<EvidenceChunk> batch = candidates.subList(start, Math.min(start + batchSize, candidates.size()));
            embedBatch(batch);
        }
        return chunks;
    }

    private void embedBatch(List<EvidenceChunk> batch) {
        try {
            List<String> inputs = batch.stream()
                    .map(this::embeddingInput)
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
                EvidenceChunk chunk = batch.get(i);
                chunk.setEmbedding(new ArrayList<>(vector));
                chunk.setEmbeddingModel(embeddingClient.model());
                chunk.setEmbeddedAt(embeddedAt);
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
        return chunk.getEmbedding() == null
                || chunk.getEmbedding().isEmpty()
                || !embeddingClient.model().equals(chunk.getEmbeddingModel());
    }

    private String embeddingInput(EvidenceChunk chunk) {
        return """
                Title: %s
                Source citation: %s
                Source type: %s
                Source authority: %s
                Source quality: %s
                Content kind: %s
                Heading path: %s
                Text:
                %s
                """.formatted(
                nullToEmpty(chunk.getTitle()),
                nullToEmpty(chunk.getSourceCitationKey()),
                nullToEmpty(chunk.getSourceType()),
                nullToEmpty(chunk.getSourceAuthority()),
                nullToEmpty(chunk.getSourceQuality()),
                nullToEmpty(chunk.getContentKind()),
                chunk.getHeadingPath() == null ? "" : String.join(" > ", chunk.getHeadingPath()),
                chunk.getText()
        );
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
