package com.aiinsight.agent.node;

import com.aiinsight.llm.ChatRequest;
import com.aiinsight.llm.LlmClient;
import com.aiinsight.model.enums.AgentName;
import com.aiinsight.model.enums.ArtifactType;
import com.aiinsight.model.enums.ClaimType;
import com.aiinsight.model.enums.ConfidenceLevel;
import com.aiinsight.model.enums.FactType;
import com.aiinsight.model.enums.ReviewAction;
import com.aiinsight.model.run.AnalysisArtifact;
import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.model.run.EvidenceChunk;
import com.aiinsight.model.run.EvidenceSource;
import com.aiinsight.model.schema.AnalysisClaim;
import com.aiinsight.model.schema.CompetitorFactSet;
import com.aiinsight.model.schema.ExtractedFact;
import com.aiinsight.service.CitationCoverageEvaluator;
import com.aiinsight.service.fallback.FallbackReviewReportFactory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewerNodeTest {

    @Test
    void routesUnsupportedExtractedFactBackToExtractor() {
        AnalysisRun run = new AnalysisRun();
        run.addArtifact(new AnalysisArtifact(ArtifactType.REPORT_DRAFT, "draft", "Summary only.", List.of()));
        run.getEvidenceSources().add(source("S30", "Cursor Composer supports multi-file code editing."));
        run.getEvidenceChunks().add(chunk("S30-C1", "S30", "feature", "Cursor Composer supports multi-file code editing."));
        ExtractedFact fact = fact("F30", FactType.SECURITY, "compliance",
                "Cursor includes SOC 2 enterprise compliance controls.", List.of("S30"), List.of("S30-C1"));
        run.getCompetitorFactSets().add(factSet(fact));
        AnalysisClaim claim = claim("Cursor includes SOC 2 enterprise compliance controls.", List.of("S30"), List.of("F30"));
        run.getClaims().add(claim);

        new ReviewerNode(new CitationCoverageEvaluator(), noopLlmClient(), new FallbackReviewReportFactory()).execute(run);

        assertThat(run.getReviewDecision().getAction()).isEqualTo(ReviewAction.REWORK_ANALYSIS);
        assertThat(run.getReviewDecision().getTargetAgent()).isEqualTo(AgentName.EXTRACTOR);
        assertThat(run.getReviewDecision().getFindingCategories()).contains("fact_unsupported_by_evidence");
        assertThat(run.getReviewDecision().getReason()).contains("extracted fact");
        assertThat(run.getReviewDecision().getRepairTasks())
                .anySatisfy(task -> {
                    assertThat(task.getTargetAgent()).isEqualTo(AgentName.EXTRACTOR);
                    assertThat(task.getAction()).isEqualTo("REPAIR_FACT_EXTRACTION");
                    assertThat(task.getClaimId()).isEqualTo(claim.getId());
                    assertThat(task.getFactId()).isEqualTo("F30");
                    assertThat(task.getChunkKey()).isEqualTo("S30-C1");
                    assertThat(task.getCitationKey()).isEqualTo("S30");
                    assertThat(task.getInstruction()).contains("fact=F30");
                });
    }

    @Test
    void routesClaimFactMismatchBackToAnalyst() {
        AnalysisRun run = new AnalysisRun();
        run.addArtifact(new AnalysisArtifact(ArtifactType.REPORT_DRAFT, "draft", "Summary only.", List.of()));
        run.getEvidenceSources().add(source("S31", "Cursor Composer supports multi-file code editing."));
        run.getEvidenceChunks().add(chunk("S31-C1", "S31", "feature", "Cursor Composer supports multi-file code editing."));
        ExtractedFact fact = fact("F31", FactType.FEATURE, "composer",
                "Cursor Composer supports multi-file code editing.", List.of("S31"), List.of("S31-C1"));
        run.getCompetitorFactSets().add(factSet(fact));
        AnalysisClaim claim = claim("Cursor is the best enterprise governance platform.", List.of("S31"), List.of("F31"));
        run.getClaims().add(claim);

        new ReviewerNode(new CitationCoverageEvaluator(), noopLlmClient(), new FallbackReviewReportFactory()).execute(run);

        assertThat(run.getReviewDecision().getAction()).isEqualTo(ReviewAction.REWORK_ANALYSIS);
        assertThat(run.getReviewDecision().getTargetAgent()).isEqualTo(AgentName.ANALYST);
        assertThat(run.getReviewDecision().getFindingCategories()).contains("claim_fact_mismatch");
        assertThat(run.getReviewDecision().getRepairTasks())
                .anySatisfy(task -> {
                    assertThat(task.getTargetAgent()).isEqualTo(AgentName.ANALYST);
                    assertThat(task.getAction()).isEqualTo("REPAIR_CLAIM_EVIDENCE");
                    assertThat(task.getClaimId()).isEqualTo(claim.getId());
                });
    }

    private LlmClient noopLlmClient() {
        return new LlmClient() {
            @Override
            public boolean isAvailable() {
                return false;
            }

            @Override
            public String complete(ChatRequest request) {
                throw new IllegalStateException("LLM is not configured");
            }
        };
    }

    private EvidenceSource source(String citationKey, String text) {
        EvidenceSource source = new EvidenceSource(
                citationKey,
                "Evidence",
                "https://example.test/evidence",
                "product_docs",
                "FETCHED",
                "LIVE_FETCHED",
                "HIGH",
                "NONE",
                text,
                text,
                "test evidence"
        );
        source.setSourceAuthority("FIRST_PARTY_OFFICIAL");
        return source;
    }

    private EvidenceChunk chunk(String chunkKey, String citationKey, String kind, String text) {
        EvidenceChunk chunk = new EvidenceChunk(
                chunkKey,
                citationKey,
                1,
                "Evidence chunk",
                "https://example.test/evidence",
                text
        );
        chunk.setContentKind(kind);
        chunk.setSourceAuthority("FIRST_PARTY_OFFICIAL");
        chunk.setSourceQuality("HIGH");
        return chunk;
    }

    private CompetitorFactSet factSet(ExtractedFact fact) {
        CompetitorFactSet factSet = new CompetitorFactSet();
        factSet.setCompetitorName("Cursor");
        factSet.getFacts().add(fact);
        return factSet;
    }

    private ExtractedFact fact(String id,
                               FactType factType,
                               String attribute,
                               String value,
                               List<String> evidenceIds,
                               List<String> chunkKeys) {
        ExtractedFact fact = new ExtractedFact();
        fact.setId(id);
        fact.setCompetitorName("Cursor");
        fact.setFactType(factType);
        fact.setAttribute(attribute);
        fact.setValue(value);
        fact.setEvidenceIds(evidenceIds);
        fact.setChunkKeys(chunkKeys);
        fact.setExtractionConfidence("HIGH");
        return fact;
    }

    private AnalysisClaim claim(String content, List<String> evidenceIds, List<String> factIds) {
        AnalysisClaim claim = new AnalysisClaim();
        claim.setType(ClaimType.OPPORTUNITY);
        claim.setContent(content);
        claim.setConfidence(ConfidenceLevel.HIGH);
        claim.setEvidenceIds(evidenceIds);
        claim.setFactIds(factIds);
        return claim;
    }
}
