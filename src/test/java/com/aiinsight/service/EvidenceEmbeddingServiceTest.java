package com.aiinsight.service;

import com.aiinsight.model.run.EmbeddingCacheEntry;
import com.aiinsight.model.run.EvidenceChunk;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

    @Test
    void reusesCachedEmbeddingForSameCanonicalInputAcrossDifferentCitationKeys() {
        EmbeddingProperties properties = new EmbeddingProperties();
        CountingEmbeddingClient embeddingClient = new CountingEmbeddingClient("test-embedding-model");
        com.aiinsight.repository.AnalysisRunRepository repository = mock(com.aiinsight.repository.AnalysisRunRepository.class);
        Map<String, EmbeddingCacheEntry> store = new LinkedHashMap<>();
        when(repository.findCachedEmbeddings(any(Collection.class), eq("test-embedding-model"), eq(0)))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    Collection<String> inputHashes = invocation.getArgument(0);
                    Map<String, EmbeddingCacheEntry> hits = new LinkedHashMap<>();
                    for (String inputHash : inputHashes) {
                        if (store.containsKey(inputHash)) {
                            hits.put(inputHash, store.get(inputHash));
                        }
                    }
                    return hits;
                });
        doAnswer(invocation -> {
            EmbeddingCacheEntry entry = invocation.getArgument(0);
            store.put(entry.inputHash(), entry);
            return null;
        }).when(repository).saveCachedEmbedding(any(EmbeddingCacheEntry.class));
        EvidenceEmbeddingService service = new EvidenceEmbeddingService(embeddingClient, properties, repository);
        EvidenceChunk first = new EvidenceChunk("S1-C1", "S1", 1, "Pricing", "https://example.test/pricing", "Pricing details");
        EvidenceChunk second = new EvidenceChunk("S9-C1", "S9", 1, "Pricing", "https://example.test/pricing", "Pricing details");

        service.embedChunks(List.of(first));
        service.embedChunks(List.of(second));

        assertThat(embeddingClient.calls()).isEqualTo(1);
        assertThat(store).hasSize(1);
        assertThat(first.getEmbedding()).containsExactly(1.0, 0.0);
        assertThat(second.getEmbedding()).containsExactly(1.0, 0.0);
        assertThat(second.getEmbeddingModel()).isEqualTo("test-embedding-model");
        assertThat(second.getEmbeddedAt()).isNotNull();
    }

    @Test
    void treatsExpiredCachedEmbeddingAsMiss() {
        EmbeddingProperties properties = new EmbeddingProperties();
        properties.setCacheTtl(Duration.ofDays(1));
        CountingEmbeddingClient embeddingClient = new CountingEmbeddingClient("test-embedding-model");
        com.aiinsight.repository.AnalysisRunRepository repository = mock(com.aiinsight.repository.AnalysisRunRepository.class);
        Map<String, EmbeddingCacheEntry> store = new LinkedHashMap<>();
        when(repository.findCachedEmbeddings(any(Collection.class), eq("test-embedding-model"), eq(0)))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    Collection<String> inputHashes = invocation.getArgument(0);
                    Map<String, EmbeddingCacheEntry> hits = new LinkedHashMap<>();
                    for (String inputHash : inputHashes) {
                        hits.put(inputHash, new EmbeddingCacheEntry(
                                inputHash,
                                "text-hash-old",
                                "test-embedding-model",
                                0,
                                List.of(9.0, 9.0),
                                Instant.now().minus(Duration.ofDays(3)),
                                Instant.now().minus(Duration.ofDays(3)),
                                1
                        ));
                    }
                    return hits;
                });
        doAnswer(invocation -> {
            EmbeddingCacheEntry entry = invocation.getArgument(0);
            store.put(entry.inputHash(), entry);
            return null;
        }).when(repository).saveCachedEmbedding(any(EmbeddingCacheEntry.class));
        EvidenceEmbeddingService service = new EvidenceEmbeddingService(embeddingClient, properties, repository);
        EvidenceChunk chunk = new EvidenceChunk("S1-C1", "S1", 1, "Pricing", "https://example.test/pricing", "Pricing details");

        service.embedChunks(List.of(chunk));

        assertThat(embeddingClient.calls()).isEqualTo(1);
        assertThat(chunk.getEmbedding()).containsExactly(1.0, 0.0);
        assertThat(store).hasSize(1);
    }

    @Test
    void doesNotRefreshExpiredCacheEntryWhenReEmbeddingFails() {
        EmbeddingProperties properties = new EmbeddingProperties();
        properties.setCacheTtl(Duration.ofDays(1));
        FailingEmbeddingClient embeddingClient = new FailingEmbeddingClient("test-embedding-model");
        com.aiinsight.repository.AnalysisRunRepository repository = mock(com.aiinsight.repository.AnalysisRunRepository.class);
        when(repository.findCachedEmbeddings(any(Collection.class), eq("test-embedding-model"), eq(0)))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    Collection<String> inputHashes = invocation.getArgument(0);
                    Map<String, EmbeddingCacheEntry> hits = new LinkedHashMap<>();
                    for (String inputHash : inputHashes) {
                        hits.put(inputHash, new EmbeddingCacheEntry(
                                inputHash,
                                "text-hash-old",
                                "test-embedding-model",
                                0,
                                List.of(9.0, 9.0),
                                Instant.now().minus(Duration.ofDays(3)),
                                Instant.now().minus(Duration.ofDays(3)),
                                1
                        ));
                    }
                    return hits;
                });
        EvidenceEmbeddingService service = new EvidenceEmbeddingService(embeddingClient, properties, repository);
        EvidenceChunk chunk = new EvidenceChunk("S1-C1", "S1", 1, "Pricing", "https://example.test/pricing", "Pricing details");

        service.embedChunks(List.of(chunk));

        assertThat(embeddingClient.calls()).isEqualTo(1);
        assertThat(chunk.getEmbedding()).isEmpty();
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).saveCachedEmbedding(any(EmbeddingCacheEntry.class));
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

    private static class CountingEmbeddingClient implements EmbeddingClient {

        private final String model;
        private final AtomicInteger calls = new AtomicInteger();

        private CountingEmbeddingClient(String model) {
            this.model = model;
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public String model() {
            return model;
        }

        @Override
        public List<List<Double>> embed(List<String> inputs) {
            calls.incrementAndGet();
            return inputs.stream()
                    .map(ignored -> List.of(1.0, 0.0))
                    .toList();
        }

        private int calls() {
            return calls.get();
        }
    }

    private static class FailingEmbeddingClient implements EmbeddingClient {

        private final String model;
        private final AtomicInteger calls = new AtomicInteger();

        private FailingEmbeddingClient(String model) {
            this.model = model;
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public String model() {
            return model;
        }

        @Override
        public List<List<Double>> embed(List<String> inputs) {
            calls.incrementAndGet();
            throw new IllegalStateException("simulated embedding failure");
        }

        private int calls() {
            return calls.get();
        }
    }
}
