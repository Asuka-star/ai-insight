package com.aiinsight.agent.node;

import com.aiinsight.llm.ChatRequest;
import com.aiinsight.llm.LlmClient;
import com.aiinsight.model.enums.ArtifactType;
import com.aiinsight.model.enums.AgentName;
import com.aiinsight.model.enums.ClaimType;
import com.aiinsight.model.enums.ConfidenceLevel;
import com.aiinsight.model.enums.FactType;
import com.aiinsight.model.enums.ReviewAction;
import com.aiinsight.model.review.ReviewRepairTask;
import com.aiinsight.model.run.AnalysisRequirement;
import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.model.run.EvidenceChunk;
import com.aiinsight.model.run.EvidenceSource;
import com.aiinsight.model.schema.AnalysisClaim;
import com.aiinsight.model.schema.CompetitorFactSet;
import com.aiinsight.model.schema.ExtractedFact;
import com.aiinsight.service.AnalysisDraft;
import com.aiinsight.service.fallback.FallbackAnalysisDraftFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AnalystNodeTest {

    @Test
    void analystPromptIncludesFactsAndClaimsBindBackToFactLayer() {
        AtomicReference<String> promptCapture = new AtomicReference<>();
        LlmClient llmClient = new LlmClient() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String complete(ChatRequest request) {
                promptCapture.set(request.getMessages().get(1).getContent());
                return """
                        {
                          "claims": [
                            {
                              "type": "OPPORTUNITY",
                              "content": "Cursor Composer can be used as a benchmark for multi-file editing workflow.",
                              "confidence": "MEDIUM",
                              "competitorNames": ["Cursor"],
                              "evidenceIds": ["S1"]
                            }
                          ]
                        }
                        """;
            }
        };
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "Analyze Cursor",
                "AI coding tools",
                List.of("Cursor"),
                List.of("features"),
                List.of("official_site"),
                List.of()
        ));
        run.getEvidenceSources().add(new EvidenceSource(
                "S1",
                "Cursor product page",
                "https://example.test/cursor",
                "official_site",
                "FETCHED",
                "LIVE_FETCHED",
                "HIGH",
                "NONE",
                "Cursor Composer supports multi-file code edits.",
                "Cursor Composer supports multi-file code edits.",
                "test evidence"
        ));
        run.getEvidenceSources().get(0).setSourceAuthority("FIRST_PARTY_OFFICIAL");
        EvidenceChunk chunk = new EvidenceChunk(
                "S1-C1",
                "S1",
                1,
                "Cursor product page",
                "https://example.test/cursor",
                "Cursor Composer supports multi-file code edits."
        );
        run.getEvidenceChunks().add(chunk);
        run.getCompetitorFactSets().add(cursorFactSet());

        new AnalystNode(llmClient, new ObjectMapper(), new FallbackAnalysisDraftFactory()).execute(run);

        assertThat(promptCapture.get()).contains("Extractor facts", "id=F1", "S1-C1");
        assertThat(run.getClaims()).hasSize(1);
        assertThat(run.getClaims().get(0).getFactIds()).containsExactly("F1");
        assertThat(run.getClaims().get(0).getChunkKeys()).containsExactly("S1-C1");
    }

    @Test
    void matrixAndSwotIgnoreDraftTextAndRenderOnlyFromClaims() {
        LlmClient unavailableLlm = new LlmClient() {
            @Override
            public boolean isAvailable() {
                return false;
            }

            @Override
            public String complete(ChatRequest request) {
                throw new IllegalStateException("LLM is not configured");
            }
        };
        FallbackAnalysisDraftFactory noisyFallback = new FallbackAnalysisDraftFactory() {
            @Override
            public AnalysisDraft build(AnalysisRun run) {
                AnalysisClaim claim = new AnalysisClaim();
                claim.setType(ClaimType.OPPORTUNITY);
                claim.setContent("Cursor Composer is a useful benchmark for multi-file editing.");
                claim.setConfidence(ConfidenceLevel.MEDIUM);
                claim.setCompetitorNames(List.of("Cursor"));
                claim.setEvidenceIds(List.of("S1"));
                return new AnalysisDraft(
                        List.of(claim),
                        "UNSUPPORTED MATRIX TEXT [S1]",
                        "UNSUPPORTED SWOT TEXT [S1]"
                );
            }
        };
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "Analyze Cursor",
                "AI coding tools",
                List.of("Cursor"),
                List.of("features"),
                List.of("official_site"),
                List.of()
        ));
        run.getEvidenceSources().add(new EvidenceSource(
                "S1",
                "Cursor product page",
                "https://example.test/cursor",
                "official_site",
                "FETCHED",
                "LIVE_FETCHED",
                "HIGH",
                "NONE",
                "Cursor Composer supports multi-file code edits.",
                "Cursor Composer supports multi-file code edits.",
                "test evidence"
        ));

        new AnalystNode(unavailableLlm, new ObjectMapper(), noisyFallback).execute(run);

        String matrix = run.getArtifacts().stream()
                .filter(artifact -> artifact.getType() == ArtifactType.COMPETITIVE_MATRIX)
                .reduce((first, second) -> second)
                .orElseThrow()
                .getContent();
        String swot = run.getArtifacts().stream()
                .filter(artifact -> artifact.getType() == ArtifactType.SWOT_ANALYSIS)
                .reduce((first, second) -> second)
                .orElseThrow()
                .getContent();
        assertThat(matrix)
                .contains("Cursor Composer is a useful benchmark")
                .doesNotContain("UNSUPPORTED MATRIX TEXT");
        assertThat(swot)
                .contains("Cursor Composer is a useful benchmark")
                .doesNotContain("UNSUPPORTED SWOT TEXT");
    }

    @Test
    void analystRepairKeepsClaimIdAndDowngradesUnresolvedTaskClaim() {
        String claimText = "Cursor Composer is clearly the strongest workflow benchmark.";
        LlmClient llmClient = new LlmClient() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String complete(ChatRequest request) {
                return """
                        {
                          "claims": [
                            {
                              "type": "OPPORTUNITY",
                              "content": "%s",
                              "confidence": "HIGH",
                              "competitorNames": ["Cursor"],
                              "evidenceIds": ["S1"]
                            }
                          ]
                        }
                        """.formatted(claimText);
            }
        };
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "Analyze Cursor",
                "AI coding tools",
                List.of("Cursor"),
                List.of("features"),
                List.of("official_site"),
                List.of()
        ));
        run.getEvidenceSources().add(new EvidenceSource(
                "S1",
                "Cursor product page",
                "https://example.test/cursor",
                "official_site",
                "FETCHED",
                "LIVE_FETCHED",
                "HIGH",
                "NONE",
                "Cursor Composer supports multi-file code edits.",
                "Cursor Composer supports multi-file code edits.",
                "test evidence"
        ));
        AnalysisClaim previous = new AnalysisClaim();
        previous.setId("C-KEEP");
        previous.setType(ClaimType.OPPORTUNITY);
        previous.setContent(claimText);
        previous.setConfidence(ConfidenceLevel.HIGH);
        previous.setCompetitorNames(List.of("Cursor"));
        previous.setEvidenceIds(List.of("S1"));
        run.getClaims().add(previous);

        run.getReviewDecision().setAction(ReviewAction.REWORK_ANALYSIS);
        run.getReviewDecision().setTargetAgent(AgentName.ANALYST);
        ReviewRepairTask task = new ReviewRepairTask();
        task.setTargetAgent(AgentName.ANALYST);
        task.setClaimId("C-KEEP");
        task.setCitationKey("S1");
        task.setCategory("claim_fact_mismatch");
        task.setCurrentText(claimText);
        run.getReviewDecision().setRepairTasks(List.of(task));

        new AnalystNode(llmClient, new ObjectMapper(), new FallbackAnalysisDraftFactory()).execute(run);

        assertThat(run.getClaims()).hasSize(1);
        AnalysisClaim repaired = run.getClaims().get(0);
        assertThat(repaired.getId()).isEqualTo("C-KEEP");
        assertThat(repaired.getConfidence()).isEqualTo(ConfidenceLevel.LOW);
        assertThat(repaired.getEvidenceIds()).doesNotContain("S1");
        assertThat(repaired.getContent()).contains("证据不足", "待验证");
    }

    @Test
    void analystRepairDoesNotDowngradeClaimReboundToNewEvidence() {
        String claimText = "Cursor Composer is a strong workflow benchmark.";
        LlmClient llmClient = new LlmClient() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String complete(ChatRequest request) {
                return """
                        {
                          "claims": [
                            {
                              "type": "OPPORTUNITY",
                              "content": "%s",
                              "confidence": "HIGH",
                              "competitorNames": ["Cursor"],
                              "evidenceIds": ["S2"]
                            }
                          ]
                        }
                        """.formatted(claimText);
            }
        };
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "Analyze Cursor",
                "AI coding tools",
                List.of("Cursor"),
                List.of("features"),
                List.of("official_site"),
                List.of()
        ));
        run.getEvidenceSources().add(new EvidenceSource(
                "S1",
                "Old weak page",
                "https://example.test/old",
                "public_review",
                "FETCHED",
                "LIVE_FETCHED",
                "LOW",
                "NONE",
                "Old weak snippet.",
                "Old weak snippet.",
                "old evidence"
        ));
        run.getEvidenceSources().add(new EvidenceSource(
                "S2",
                "Cursor official docs",
                "https://example.test/cursor/docs",
                "official_site",
                "FETCHED",
                "LIVE_FETCHED",
                "HIGH",
                "NONE",
                "Cursor Composer supports multi-file workflow editing.",
                "Cursor Composer supports multi-file workflow editing.",
                "new evidence"
        ));
        run.getEvidenceSources().get(1).setSourceAuthority("FIRST_PARTY_OFFICIAL");
        AnalysisClaim previous = new AnalysisClaim();
        previous.setId("C-KEEP");
        previous.setType(ClaimType.OPPORTUNITY);
        previous.setContent(claimText);
        previous.setConfidence(ConfidenceLevel.HIGH);
        previous.setCompetitorNames(List.of("Cursor"));
        previous.setEvidenceIds(List.of("S1"));
        run.getClaims().add(previous);

        run.getReviewDecision().setAction(ReviewAction.REWORK_ANALYSIS);
        run.getReviewDecision().setTargetAgent(AgentName.ANALYST);
        ReviewRepairTask task = new ReviewRepairTask();
        task.setTargetAgent(AgentName.ANALYST);
        task.setClaimId("C-KEEP");
        task.setCitationKey("S1");
        task.setCategory("claim_fact_mismatch");
        task.setCurrentText(claimText);
        run.getReviewDecision().setRepairTasks(List.of(task));

        new AnalystNode(llmClient, new ObjectMapper(), new FallbackAnalysisDraftFactory()).execute(run);

        assertThat(run.getClaims()).hasSize(1);
        AnalysisClaim repaired = run.getClaims().get(0);
        assertThat(repaired.getId()).isEqualTo("C-KEEP");
        assertThat(repaired.getConfidence()).isEqualTo(ConfidenceLevel.HIGH);
        assertThat(repaired.getEvidenceIds()).containsExactly("S2");
        assertThat(repaired.getContent()).doesNotContain("证据不足", "待验证");
    }

    private CompetitorFactSet cursorFactSet() {
        ExtractedFact fact = new ExtractedFact();
        fact.setId("F1");
        fact.setCompetitorName("Cursor");
        fact.setFactType(FactType.FEATURE);
        fact.setAttribute("feature");
        fact.setValue("Composer supports multi-file code edits.");
        fact.setEvidenceIds(List.of("S1"));
        fact.setChunkKeys(List.of("S1-C1"));
        fact.setSourceAuthority("FIRST_PARTY_OFFICIAL");
        fact.setSourceQuality("HIGH");
        fact.setExtractionConfidence("HIGH");

        CompetitorFactSet factSet = new CompetitorFactSet();
        factSet.setCompetitorName("Cursor");
        factSet.setFacts(List.of(fact));
        return factSet;
    }
}
