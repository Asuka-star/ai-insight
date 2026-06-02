package com.aiinsight.service;

import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.model.run.EvidenceChunk;
import com.aiinsight.dto.AnalysisRunSummary;
import com.aiinsight.repository.AnalysisRunRepository;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceRetrievalServiceTest {

    @Test
    void retrievesTopEvidenceChunksByKeywordScore() {
        AnalysisRun run = new AnalysisRun();
        run.getEvidenceChunks().add(new EvidenceChunk("S1-C1", "S1", 1, "价格页", "https://example.com/pricing", "团队版和企业版套餐价格信息"));
        run.getEvidenceChunks().add(new EvidenceChunk("S2-C1", "S2", 1, "功能页", "https://example.com/features", "文档协同和权限管理能力"));

        var results = new EvidenceRetrievalService().retrieve(run, "价格 套餐", 1);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getChunkKey()).isEqualTo("S1-C1");
        assertThat(results.get(0).getScore()).isPositive();
    }

    @Test
    void hybridRetrievalBoostsOfficialPricingChunksForPricingDimensions() {
        AnalysisRun run = new AnalysisRun();
        EvidenceChunk officialPricing = new EvidenceChunk("S1-C1", "S1", 1, "Notion Pricing", "https://www.notion.com/pricing", "Business plan pricing and Enterprise contact sales details.");
        officialPricing.setContentKind("pricing");
        officialPricing.setSourceAuthority("FIRST_PARTY_OFFICIAL");
        officialPricing.setSourceQuality("HIGH");
        EvidenceChunk thirdPartyPricing = new EvidenceChunk("S2-C1", "S2", 1, "Notion pricing comparison", "https://example-blog.com/notion-pricing", "Notion pricing comparison and commentary.");
        thirdPartyPricing.setContentKind("pricing");
        thirdPartyPricing.setSourceAuthority("THIRD_PARTY_GENERAL");
        thirdPartyPricing.setSourceQuality("MEDIUM");
        run.getEvidenceChunks().add(thirdPartyPricing);
        run.getEvidenceChunks().add(officialPricing);

        var results = new EvidenceRetrievalService().retrieve(run, "Notion pricing", "Notion", "pricing and plans", 2);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).getChunkKey()).isEqualTo("S1-C1");
        assertThat(results.get(0).getScore()).isGreaterThan(results.get(1).getScore());
    }

    @Test
    void hybridRetrievalRecallsPermissionChunksWithSemanticKeywordExpansion() {
        AnalysisRun run = new AnalysisRun();
        EvidenceChunk permissionChunk = new EvidenceChunk("S3-C1", "S3", 1, "Confluence admin controls", "https://support.atlassian.com/confluence", "SAML SSO, SCIM, audit logs, role based admin controls.");
        permissionChunk.setContentKind("permission");
        permissionChunk.setSourceAuthority("FIRST_PARTY_DOCS");
        permissionChunk.setSourceQuality("HIGH");
        run.getEvidenceChunks().add(permissionChunk);

        var results = new EvidenceRetrievalService().retrieve(run, "企业权限", "Confluence", "权限协作", 1);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getChunkKey()).isEqualTo("S3-C1");
        assertThat(results.get(0).getScore()).isPositive();
    }

    @Test
    void semanticRetrievalRecallsChunksWhenKeywordsDoNotMatch() {
        AnalysisRun run = new AnalysisRun();
        EvidenceChunk semanticMatch = new EvidenceChunk("S1-C1", "S1", 1, "Admin docs", "https://example.test/admin", "A short unrelated sentence.");
        semanticMatch.setEmbedding(List.of(1.0, 0.0));
        semanticMatch.setEmbeddingModel("test-embedding-model");
        EvidenceChunk semanticMiss = new EvidenceChunk("S2-C1", "S2", 1, "Billing docs", "https://example.test/billing", "Another unrelated sentence.");
        semanticMiss.setEmbedding(List.of(0.0, 1.0));
        semanticMiss.setEmbeddingModel("test-embedding-model");
        run.getEvidenceChunks().add(semanticMiss);
        run.getEvidenceChunks().add(semanticMatch);

        var results = new EvidenceRetrievalService(new FakeEmbeddingClient()).retrieve(run, "enterprise access controls", 1);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getChunkKey()).isEqualTo("S1-C1");
        assertThat(results.get(0).getScore()).isPositive();
    }

    @Test
    void semanticRetrievalIgnoresEmbeddingsFromDifferentModels() {
        AnalysisRun run = new AnalysisRun();
        EvidenceChunk staleVector = new EvidenceChunk("S1-C1", "S1", 1, "Old admin docs", "https://example.test/old", "No matching keywords here.");
        staleVector.setEmbedding(List.of(1.0, 0.0));
        staleVector.setEmbeddingModel("old-embedding-model");
        EvidenceChunk currentVector = new EvidenceChunk("S2-C1", "S2", 1, "Current admin docs", "https://example.test/current", "No matching keywords here either.");
        currentVector.setEmbedding(List.of(0.8, 0.2));
        currentVector.setEmbeddingModel("test-embedding-model");
        run.getEvidenceChunks().add(staleVector);
        run.getEvidenceChunks().add(currentVector);

        var results = new EvidenceRetrievalService(new FakeEmbeddingClient()).retrieve(run, "enterprise access controls", 2);

        assertThat(results)
                .extracting(EvidenceChunk::getChunkKey)
                .containsExactly("S2-C1");
    }

    @Test
    void semanticRetrievalRejectsLowSimilarityCandidatesWithoutKeywordMatch() {
        AnalysisRun run = new AnalysisRun();
        EvidenceChunk weakMatch = new EvidenceChunk("S1-C1", "S1", 1, "Billing docs", "https://example.test/billing", "No matching keywords here.");
        weakMatch.setEmbedding(List.of(0.2, 0.98));
        weakMatch.setEmbeddingModel("test-embedding-model");
        run.getEvidenceChunks().add(weakMatch);

        var results = new EvidenceRetrievalService(new FakeEmbeddingClient()).retrieve(run, "enterprise access controls", 3);

        assertThat(results).isEmpty();
    }

    @Test
    void semanticRetrievalUsesRepositoryVectorCandidatesWhenRunPayloadHasNoEmbeddings() {
        AnalysisRun run = new AnalysisRun();
        EvidenceChunk payloadChunk = new EvidenceChunk("S1-C1", "S1", 1, "Admin docs", "https://example.test/admin", "A short unrelated sentence.");
        run.getEvidenceChunks().add(payloadChunk);
        EvidenceChunk vectorChunk = new EvidenceChunk("S1-C1", "S1", 1, "Admin docs", "https://example.test/admin", "A short unrelated sentence.");
        vectorChunk.setId(payloadChunk.getId());
        vectorChunk.setEmbedding(List.of(1.0, 0.0));
        vectorChunk.setEmbeddingModel("test-embedding-model");
        FakeVectorRepository repository = new FakeVectorRepository(List.of(vectorChunk));

        var results = new EvidenceRetrievalService(new FakeEmbeddingClient(), repository)
                .retrieve(run, "enterprise access controls", 1);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getChunkKey()).isEqualTo("S1-C1");
        assertThat(repository.requestedModel).isEqualTo("test-embedding-model");
        assertThat(repository.requestedTopK).isGreaterThanOrEqualTo(4);
    }

    private static class FakeEmbeddingClient implements EmbeddingClient {

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public String model() {
            return "test-embedding-model";
        }

        @Override
        public List<List<Double>> embed(List<String> inputs) {
            return List.of(List.of(1.0, 0.0));
        }
    }

    private static class FakeVectorRepository implements AnalysisRunRepository {

        private final List<EvidenceChunk> chunks;
        private String requestedModel;
        private int requestedTopK;

        private FakeVectorRepository(List<EvidenceChunk> chunks) {
            this.chunks = chunks;
        }

        @Override
        public AnalysisRun save(AnalysisRun run) {
            return run;
        }

        @Override
        public Optional<AnalysisRun> findById(UUID id) {
            return Optional.empty();
        }

        @Override
        public boolean existsById(UUID id) {
            return false;
        }

        @Override
        public Collection<AnalysisRun> findAll() {
            return List.of();
        }

        @Override
        public Collection<AnalysisRunSummary> findSummaries() {
            return List.of();
        }

        @Override
        public Optional<List<EvidenceChunk>> retrieveEvidenceByVector(UUID runId,
                                                                      List<Double> queryEmbedding,
                                                                      String embeddingModel,
                                                                      int topK) {
            requestedModel = embeddingModel;
            requestedTopK = topK;
            return Optional.of(chunks);
        }

        @Override
        public void deleteById(UUID id) {
        }
    }
}
