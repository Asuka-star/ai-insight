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
import com.aiinsight.model.schema.CompetitorProfile;
import com.aiinsight.model.schema.ExtractedFact;
import com.aiinsight.model.schema.PricingModel;
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
                              "dimension": "features",
                              "supportStatus": "SUPPORTED",
                              "recommendedPlacement": "MATRIX",
                              "supportReason": "Evidence states Composer supports the workflow directly.",
                              "evidenceQuotes": ["Cursor Composer supports multi-file editing workflow"],
                              "missingEvidenceTypes": [],
                              "rewriteSuggestion": "",
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
                "Cursor Composer supports multi-file editing workflow and can be used as a benchmark.",
                "Cursor Composer supports multi-file editing workflow and can be used as a benchmark.",
                "test evidence"
        ));
        run.getEvidenceSources().get(0).setSourceAuthority("FIRST_PARTY_OFFICIAL");
        EvidenceChunk chunk = new EvidenceChunk(
                "S1-C1",
                "S1",
                1,
                "Cursor product page",
                "https://example.test/cursor",
                "Cursor Composer supports multi-file editing workflow and can be used as a benchmark."
        );
        run.getEvidenceChunks().add(chunk);
        run.getCompetitorFactSets().add(cursorFactSet());

        new AnalystNode(llmClient, new ObjectMapper(), new FallbackAnalysisDraftFactory()).execute(run);

        assertThat(promptCapture.get())
                .contains("Extractor facts", "id=F1", "S1-C1")
                .contains("supportReason", "evidenceQuotes", "missingEvidenceTypes", "rewriteSuggestion");
        assertThat(run.getClaims()).hasSize(1);
        assertThat(run.getClaims().get(0).getFactIds()).containsExactly("F1");
        assertThat(run.getClaims().get(0).getChunkKeys()).containsExactly("S1-C1");
        assertThat(run.getClaims().get(0).getSupportReason()).contains("Composer supports");
        assertThat(run.getClaims().get(0).getEvidenceQuotes())
                .containsExactly("Cursor Composer supports multi-file editing workflow");
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
    void analystRendersArtifactsFromGuardedClaimsAfterRepairDowngrade() {
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
                              "type": "FACT",
                              "content": "%s",
                              "confidence": "HIGH",
                              "dimension": "features",
                              "supportStatus": "SUPPORTED",
                              "recommendedPlacement": "MATRIX",
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
        previous.setId("C-GUARDED");
        previous.setType(ClaimType.FACT);
        previous.setContent(claimText);
        previous.setConfidence(ConfidenceLevel.HIGH);
        previous.setCompetitorNames(List.of("Cursor"));
        previous.setEvidenceIds(List.of("S1"));
        run.getClaims().add(previous);

        run.getReviewDecision().setAction(ReviewAction.REWORK_ANALYSIS);
        run.getReviewDecision().setTargetAgent(AgentName.ANALYST);
        ReviewRepairTask task = new ReviewRepairTask();
        task.setTargetAgent(AgentName.ANALYST);
        task.setClaimId("C-GUARDED");
        task.setCitationKey("S1");
        task.setCategory("claim_evidence_mismatch");
        task.setCurrentText(claimText);
        run.getReviewDecision().setRepairTasks(List.of(task));

        new AnalystNode(llmClient, new ObjectMapper(), new FallbackAnalysisDraftFactory()).execute(run);

        AnalysisClaim repaired = run.getClaims().get(0);
        assertThat(repaired.getConfidence()).isEqualTo(ConfidenceLevel.LOW);
        assertThat(repaired.getSupportStatus()).isEqualTo("UNVERIFIED");
        assertThat(repaired.getRecommendedPlacement()).isEqualTo("VALIDATION_BACKLOG");
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

    @Test
    void fallbackAnalystDoesNotPromotePricingProfileEvidenceWithoutPricingFact() {
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "Analyze Cursor pricing",
                "AI coding tools",
                List.of("Cursor"),
                List.of("pricing"),
                List.of("pricing_page"),
                List.of()
        ));
        run.getEvidenceSources().add(new EvidenceSource(
                "S1",
                "Cursor pricing",
                "https://example.test/cursor/pricing",
                "pricing_page",
                "FETCHED",
                "LIVE_FETCHED",
                "HIGH",
                "NONE",
                "Cursor pricing page is available.",
                "Cursor pricing page is available.",
                "test evidence"
        ));
        PricingModel pricingModel = new PricingModel();
        pricingModel.setStrategySummary("已补充价格页证据，可初步描述套餐策略，具体金额仍以原始页面为准。");
        pricingModel.setEvidenceIds(List.of("S1"));
        CompetitorProfile profile = new CompetitorProfile();
        profile.setProductName("Cursor");
        profile.setPricingModel(pricingModel);
        profile.setEvidenceIds(List.of("S1"));
        run.getCompetitorProfiles().add(profile);

        new AnalystNode(unavailableLlm(), new ObjectMapper(), new FallbackAnalysisDraftFactory()).execute(run);

        assertThat(run.getClaims())
                .filteredOn(claim -> claim.getContent().contains("定价") || claim.getContent().contains("价格"))
                .anySatisfy(claim -> {
                    assertThat(claim.getType()).isEqualTo(ClaimType.RISK);
                    assertThat(claim.getConfidence()).isEqualTo(ConfidenceLevel.LOW);
                    assertThat(claim.getEvidenceIds()).isEmpty();
                    assertThat(claim.getContent()).contains("事实层", "待验证");
                    assertThat(claim.getContent()).doesNotContain("可初步比较定价策略");
                });
    }

    @Test
    void fallbackAnalystUsesPublishedPricingFactsForPricingComparison() {
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "Analyze Cursor pricing",
                "AI coding tools",
                List.of("Cursor"),
                List.of("pricing"),
                List.of("pricing_page"),
                List.of()
        ));
        run.getEvidenceSources().add(new EvidenceSource(
                "S1",
                "Cursor pricing",
                "https://example.test/cursor/pricing",
                "pricing_page",
                "FETCHED",
                "LIVE_FETCHED",
                "HIGH",
                "NONE",
                "Cursor Pro costs $20/month.",
                "Cursor Pro costs $20/month.",
                "test evidence"
        ));
        ExtractedFact pricingFact = new ExtractedFact();
        pricingFact.setId("F-PRICE");
        pricingFact.setCompetitorName("Cursor");
        pricingFact.setFactType(FactType.PRICING);
        pricingFact.setAttribute("pricing_plan");
        pricingFact.setValue("Pro | $20/month | monthly | Developers | Composer");
        pricingFact.setEvidenceIds(List.of("S1"));
        CompetitorFactSet factSet = new CompetitorFactSet();
        factSet.setCompetitorName("Cursor");
        factSet.setFacts(List.of(pricingFact));
        run.getCompetitorFactSets().add(factSet);

        new AnalystNode(unavailableLlm(), new ObjectMapper(), new FallbackAnalysisDraftFactory()).execute(run);

        assertThat(run.getClaims())
                .filteredOn(claim -> claim.getContent().contains("定价") || claim.getContent().contains("价格"))
                .anySatisfy(claim -> {
                    assertThat(claim.getType()).isEqualTo(ClaimType.COMPARISON);
                    assertThat(claim.getConfidence()).isEqualTo(ConfidenceLevel.MEDIUM);
                    assertThat(claim.getEvidenceIds()).containsExactly("S1");
                    assertThat(claim.getContent()).contains("可追溯");
                });
    }

    @Test
    void downgradesClaimWhenBoundEvidenceDoesNotSupportContent() {
        LlmClient llmClient = new LlmClient() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String complete(com.aiinsight.llm.ChatRequest request) {
                return """
                        {
                          "claims": [
                            {
                              "type": "STRENGTH",
                              "content": "Cursor provides SSO and SCIM enterprise security controls.",
                              "confidence": "HIGH",
                              "competitorNames": ["Cursor"],
                              "factIds": [],
                              "evidenceIds": ["S1"],
                              "chunkKeys": []
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
                List.of("enterprise security"),
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

        new AnalystNode(llmClient, new ObjectMapper(), new FallbackAnalysisDraftFactory()).execute(run);

        assertThat(run.getClaims())
                .singleElement()
                .satisfies(claim -> {
                    assertThat(claim.getConfidence()).isEqualTo(ConfidenceLevel.LOW);
                    assertThat(claim.getEvidenceIds()).isEmpty();
                    assertThat(claim.getContent()).contains("SSO", "SCIM");
                });
    }

    @Test
    void analystSelfVerifiedClaimIsNotDowngradedByBroadRiskKeywords() {
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
                              "type": "FACT",
                              "content": "Cursor provides SAML security controls for enterprise governance.",
                              "confidence": "HIGH",
                              "dimension": "企业治理",
                              "supportStatus": "SUPPORTED",
                              "recommendedPlacement": "MATRIX",
                              "supportReason": "The cited product documentation directly mentions SAML security controls.",
                              "evidenceQuotes": ["Cursor provides SAML security controls"],
                              "missingEvidenceTypes": [],
                              "rewriteSuggestion": "",
                              "competitorNames": ["Cursor"],
                              "evidenceIds": ["S1"],
                              "chunkKeys": ["S1-C1"]
                            }
                          ]
                        }
                        """;
            }
        };
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "Analyze Cursor security governance",
                "AI coding tools",
                List.of("Cursor"),
                List.of("企业治理"),
                List.of("official_site"),
                List.of()
        ));
        run.getEvidenceSources().add(new EvidenceSource(
                "S1",
                "Cursor enterprise security docs",
                "https://example.test/cursor/security",
                "product_docs",
                "FETCHED",
                "LIVE_FETCHED",
                "HIGH",
                "NONE",
                "Cursor provides SAML security controls for enterprise governance.",
                "Cursor provides SAML security controls for enterprise governance.",
                "test evidence"
        ));
        run.getEvidenceSources().get(0).setSourceAuthority("FIRST_PARTY_DOCS");
        run.getEvidenceChunks().add(new EvidenceChunk(
                "S1-C1",
                "S1",
                1,
                "Cursor enterprise security docs",
                "https://example.test/cursor/security",
                "Cursor provides SAML security controls for enterprise governance."
        ));

        new AnalystNode(llmClient, new ObjectMapper(), new FallbackAnalysisDraftFactory()).execute(run);

        assertThat(run.getClaims())
                .singleElement()
                .satisfies(claim -> {
                    assertThat(claim.getSupportStatus()).isEqualTo("SUPPORTED");
                    assertThat(claim.getConfidence()).isEqualTo(ConfidenceLevel.HIGH);
                    assertThat(claim.getRecommendedPlacement()).isEqualTo("MATRIX");
                    assertThat(claim.getEligibleForMainReport()).isTrue();
                    assertThat(claim.getEvidenceQuotes()).containsExactly("Cursor provides SAML security controls");
                    assertThat(claim.getPlacementReason()).contains("Analyst 已给出证据摘录");
                });
    }

    @Test
    void thirdPartySelfVerifiedClaimCannotStayHighSupported() {
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
                              "content": "Claude Code 的 Agent Skills 在结构化企业级自动化工作流方面具有明确优势。",
                              "confidence": "HIGH",
                              "dimension": "Agent 工作流",
                              "supportStatus": "SUPPORTED",
                              "recommendedPlacement": "MATRIX",
                              "supportReason": "第三方指南描述了 Agent Skills 的工作流结构。",
                              "evidenceQuotes": ["Agent Skills define autonomous workflows"],
                              "missingEvidenceTypes": [],
                              "rewriteSuggestion": "",
                              "competitorNames": ["Claude Code"],
                              "evidenceIds": ["S1"]
                            }
                          ]
                        }
                        """;
            }
        };
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "Analyze Claude Code",
                "AI coding tools",
                List.of("Claude Code"),
                List.of("Agent 工作流"),
                List.of("official_site"),
                List.of()
        ));
        run.getEvidenceSources().add(new EvidenceSource(
                "S1",
                "Claude Code Agent Skills - Verdent Guides",
                "https://www.verdent.ai/guides/claude-code-agent-skills",
                "third_party_docs",
                "FETCHED",
                "LIVE_FETCHED",
                "MEDIUM",
                "NONE",
                "Agent Skills define autonomous workflows within Claude Code.",
                "Agent Skills define autonomous workflows within Claude Code.",
                "test evidence"
        ));
        run.getEvidenceSources().get(0).setSourceAuthority("THIRD_PARTY_GENERAL");

        new AnalystNode(llmClient, new ObjectMapper(), new FallbackAnalysisDraftFactory()).execute(run);

        assertThat(run.getClaims())
                .singleElement()
                .satisfies(claim -> {
                    assertThat(claim.getConfidence()).isEqualTo(ConfidenceLevel.MEDIUM);
                    assertThat(claim.getSupportStatus()).isEqualTo("PARTIAL");
                    assertThat(claim.getEligibleForMainReport()).isTrue();
                });
    }

    @Test
    void downgradesSecurityClaimBoundOnlyToPricingEvidence() {
        LlmClient llmClient = new LlmClient() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String complete(com.aiinsight.llm.ChatRequest request) {
                return """
                        {
                          "claims": [
                            {
                              "type": "RISK",
                              "content": "Cursor provides SAML security controls for enterprise governance.",
                              "confidence": "HIGH",
                              "supportStatus": "SUPPORTED",
                              "recommendedPlacement": "SWOT",
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
                List.of("security"),
                List.of("official_site"),
                List.of()
        ));
        run.getEvidenceSources().add(new EvidenceSource(
                "S1",
                "Cursor pricing",
                "https://cursor.com/pricing",
                "pricing_page",
                "FETCHED",
                "LIVE_FETCHED",
                "HIGH",
                "NONE",
                "Cursor pricing page lists enterprise plans and billing options.",
                "Cursor pricing page lists enterprise plans and billing options.",
                "test evidence"
        ));

        new AnalystNode(llmClient, new ObjectMapper(), new FallbackAnalysisDraftFactory()).execute(run);

        assertThat(run.getClaims())
                .singleElement()
                .satisfies(claim -> {
                    assertThat(claim.getSupportStatus()).isEqualTo("UNVERIFIED");
                    assertThat(claim.getConfidence()).isEqualTo(ConfidenceLevel.LOW);
                    assertThat(claim.getEvidenceIds()).isEmpty();
                    assertThat(claim.getRecommendedPlacement()).isEqualTo("VALIDATION_BACKLOG");
                    assertThat(claim.getEligibleForMainReport()).isFalse();
                    assertThat(claim.getPlacementReason()).contains("证据不足");
                });
    }

    @Test
    void incrementalRepairDoesNotReuseOneRevisedClaimForMultipleTargetedClaims() {
        String workflowClaim = "Cursor Composer supports multi-file workflow editing.";
        String securityClaim = "Cursor provides SAML security controls.";
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
                              "type": "FACT",
                              "content": "%s",
                              "confidence": "HIGH",
                              "supportStatus": "SUPPORTED",
                              "recommendedPlacement": "MATRIX",
                              "competitorNames": ["Cursor"],
                              "evidenceIds": ["S1"]
                            },
                            {
                              "type": "FACT",
                              "content": "%s",
                              "confidence": "HIGH",
                              "supportStatus": "SUPPORTED",
                              "recommendedPlacement": "MATRIX",
                              "competitorNames": ["Cursor"],
                              "evidenceIds": ["S2"]
                            }
                          ]
                        }
                        """.formatted(workflowClaim, securityClaim);
            }
        };
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "Analyze Cursor",
                "AI coding tools",
                List.of("Cursor"),
                List.of("features", "security"),
                List.of("official_site"),
                List.of()
        ));
        run.getEvidenceSources().add(firstPartyEvidence(
                "S1",
                "Cursor product page",
                "https://example.test/cursor",
                "Cursor Composer supports multi-file workflow editing."
        ));
        run.getEvidenceSources().add(firstPartyEvidence(
                "S2",
                "Cursor security docs",
                "https://example.test/cursor/security",
                "Cursor provides SAML security controls."
        ));
        run.getClaims().add(previousClaim("C-WORKFLOW", workflowClaim, "S1"));
        run.getClaims().add(previousClaim("C-SECURITY", securityClaim, "S2"));

        run.getReviewDecision().setAction(ReviewAction.REWORK_ANALYSIS);
        run.getReviewDecision().setTargetAgent(AgentName.ANALYST);
        run.getReviewDecision().setRepairTasks(List.of(
                repairTaskFor("C-WORKFLOW", workflowClaim),
                repairTaskFor("C-SECURITY", securityClaim)
        ));

        new AnalystNode(llmClient, new ObjectMapper(), new FallbackAnalysisDraftFactory()).execute(run);

        assertThat(run.getClaims()).extracting(AnalysisClaim::getId)
                .containsExactly("C-WORKFLOW", "C-SECURITY");
        assertThat(run.getClaims()).extracting(AnalysisClaim::getContent)
                .containsExactly(workflowClaim, securityClaim);
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

    private AnalysisClaim previousClaim(String id, String content, String evidenceId) {
        AnalysisClaim claim = new AnalysisClaim();
        claim.setId(id);
        claim.setType(ClaimType.FACT);
        claim.setContent(content);
        claim.setConfidence(ConfidenceLevel.HIGH);
        claim.setSupportStatus("SUPPORTED");
        claim.setRecommendedPlacement("MATRIX");
        claim.setCompetitorNames(List.of("Cursor"));
        claim.setEvidenceIds(List.of(evidenceId));
        return claim;
    }

    private ReviewRepairTask repairTaskFor(String claimId, String currentText) {
        ReviewRepairTask task = new ReviewRepairTask();
        task.setTargetAgent(AgentName.ANALYST);
        task.setClaimId(claimId);
        task.setCategory("claim_style_revision");
        task.setCurrentText(currentText);
        return task;
    }

    private EvidenceSource firstPartyEvidence(String citationKey, String title, String url, String text) {
        EvidenceSource source = new EvidenceSource(
                citationKey,
                title,
                url,
                "official_site",
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

    private LlmClient unavailableLlm() {
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
}
