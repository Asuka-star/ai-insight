package com.aiinsight.service;

import com.aiinsight.model.run.EvidenceChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceEmbeddingServiceTest {

    @Test
    void embedsChunksInBatchesAndStoresModelMetadata() {
        EmbeddingProperties properties = new EmbeddingProperties();
        properties.setMaxBatchSize(1);
        EvidenceEmbeddingService service = new EvidenceEmbeddingService(
                new FakeEmbeddingClient(true, "test-embedding-model"),
                properties
        );
        EvidenceChunk first = new EvidenceChunk("S1-C1", "S1", 1, "Pricing", "https://example.test/pricing", "Pricing details");
        EvidenceChunk second = new EvidenceChunk("S2-C1", "S2", 1, "Security", "https://example.test/security", "SAML and SCIM");

        service.embedChunks(List.of(first, second));

        assertThat(first.getEmbedding()).containsExactly(1.0, 0.0);
        assertThat(first.getEmbeddingModel()).isEqualTo("test-embedding-model");
        assertThat(first.getEmbeddedAt()).isNotNull();
        assertThat(second.getEmbedding()).containsExactly(1.0, 0.0);
        assertThat(second.getEmbeddedAt()).isNotNull();
    }

    @Test
    void leavesChunksUnchangedWhenClientUnavailable() {
        EvidenceChunk chunk = new EvidenceChunk("S1-C1", "S1", 1, "Pricing", "https://example.test/pricing", "Pricing details");

        EvidenceEmbeddingService.disabled().embedChunks(List.of(chunk));

        assertThat(chunk.getEmbedding()).isEmpty();
        assertThat(chunk.getEmbeddingModel()).isNull();
        assertThat(chunk.getEmbeddedAt()).isNull();
    }

    private record FakeEmbeddingClient(boolean available, String model) implements EmbeddingClient {

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public List<List<Double>> embed(List<String> inputs) {
            return inputs.stream()
                    .map(ignored -> List.of(1.0, 0.0))
                    .toList();
        }
    }
}
