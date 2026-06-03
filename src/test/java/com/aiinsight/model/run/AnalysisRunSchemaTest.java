package com.aiinsight.model.run;

import com.aiinsight.model.enums.ClaimType;
import com.aiinsight.model.schema.AnalysisClaim;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisRunSchemaTest {

    @Test
    void initializesStructuredSchemaState() {
        AnalysisRun run = new AnalysisRun();

        assertThat(run.getResearchPackage()).isNotNull();
        assertThat(run.getResearchPackage().getResearchPlan()).isNotNull();
        assertThat(run.getResearchPackage().getResearchPlan().getEvidenceGaps()).isEmpty();
        assertThat(run.getResearchPackage().getResearchCollectionPlan()).isNotNull();
        assertThat(run.getResearchPackage().getResearchCollectionPlan().getSubtasks()).isEmpty();
        assertThat(run.getResearchPackage().getResearchCollectionPlan().getCandidateUrls()).isEmpty();
        assertThat(run.getResearchPackage().getResearchCollectionPlan().getEvidenceBudgets()).isEmpty();
        assertThat(run.getResearchPackage().getResearchCollectionPlan().getCoverageGaps()).isEmpty();
        assertThat(run.getResearchPackage().getResearchCollectionPlan().getRepairTargets()).isEmpty();
        assertThat(run.getResearchPackage().getResearchCollectionPlan().getLeadResearchPlan()).isNotNull();
        assertThat(run.getResearchPackage().getInterviewInsights()).isEmpty();
        assertThat(run.getCompetitorProfiles()).isEmpty();
        assertThat(run.getClaims()).isEmpty();
        assertThat(run.getReviewDecision()).isNotNull();
        assertThat(run.getReviewDecision().getFindingCategories()).isEmpty();
        assertThat(run.getReviewDecision().getBlockingFindingIds()).isEmpty();
        assertThat(run.getReviewDecision().getRepairInstructions()).isEmpty();
        assertThat(run.getReviewDecision().getRepairTasks()).isEmpty();
        assertThat(run.getClarificationDraft()).isNotNull();
        assertThat(run.getContextMessages()).isEmpty();
        assertThat(run.getUserProvidedEvidence()).isEmpty();
        assertThat(run.getTraces()).isEmpty();
        assertThat(run.getWorkflowTransitions()).isEmpty();
    }

    @Test
    void claimCarriesEvidenceIdsForTraceability() {
        AnalysisClaim claim = new AnalysisClaim();
        claim.setType(ClaimType.OPPORTUNITY);
        claim.setContent("可溯源报告是差异化机会点");
        claim.getEvidenceIds().add("S1");

        assertThat(claim.getId()).startsWith("C-");
        assertThat(claim.getEvidenceIds()).containsExactly("S1");
    }

    @Test
    void evidenceChunkJsonKeepsEmbeddingMetadataWithoutLargeVector() throws Exception {
        EvidenceChunk chunk = new EvidenceChunk(
                "S1-C1",
                "S1",
                1,
                "Pricing",
                "https://example.test/pricing",
                "Pricing evidence"
        );
        chunk.setEmbedding(List.of(0.1, 0.2, 0.3));
        chunk.setEmbeddingModel("test-embedding-model");
        chunk.setEmbeddedAt(Instant.parse("2026-06-02T08:00:00Z"));

        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(chunk);

        assertThat(json).contains("\"embeddingModel\":\"test-embedding-model\"");
        assertThat(json).contains("\"embeddedAt\":");
        assertThat(json).doesNotContain("\"embedding\"");
        assertThat(json).doesNotContain("0.1");
    }
}
