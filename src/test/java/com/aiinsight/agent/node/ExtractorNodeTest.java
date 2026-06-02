package com.aiinsight.agent.node;

import com.aiinsight.llm.ChatRequest;
import com.aiinsight.llm.LlmClient;
import com.aiinsight.model.enums.ArtifactType;
import com.aiinsight.model.enums.FactType;
import com.aiinsight.model.run.AnalysisRequirement;
import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.model.run.EvidenceChunk;
import com.aiinsight.model.run.EvidenceSource;
import com.aiinsight.service.fallback.FallbackExtractionFactory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ExtractorNodeTest {

    @Test
    void acceptsJsonResponseWithTrailingCommentary() {
        LlmClient llmClient = new LlmClient() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String complete(ChatRequest request) {
                return """
                        ```json
                        {
                          "profiles": [
                            {
                              "productName": "Cursor",
                              "companyName": "Cursor",
                              "positioning": "AI code editor",
                              "targetUsers": ["Developers"],
                              "features": [
                                {"name":"Composer","description":"Multi-file code editing","evidenceIds":["S1"]}
                              ],
                              "pricing": {
                                "strategySummary": "Pro plan is available",
                                "hasFreePlan": true,
                                "plans": [
                                  {"name":"Pro","priceText":"$20/month","billingCycle":"monthly","targetSegment":"Developers","includedFeatures":["Composer"],"evidenceIds":["S1"]}
                                ],
                                "evidenceIds": ["S1"]
                              },
                              "personas": [
                                {"name":"Developer","segment":"Software engineering","companySize":"Any","jobsToBeDone":["Edit code"],"painPoints":["Context switching"],"buyingConcerns":["Price"],"evidenceIds":["S1"]}
                              ],
                              "strengths": ["Multi-file editing"],
                              "weaknesses": ["Enterprise controls need verification"],
                              "evidenceIds": ["S1"]
                            }
                          ]
                        }
                        ```
                        以上 JSON 已按证据编号整理。
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
                "Cursor Composer supports multi-file code edits. Cursor Pro costs $20/month.",
                "Cursor Composer supports multi-file code edits. Cursor Pro costs $20/month.",
                "test evidence"
        ));

        new ExtractorNode(llmClient, new FallbackExtractionFactory()).execute(run);

        assertThat(run.getRecommendedActions()).noneMatch(action -> action.contains("LLM Schema"));
        assertThat(run.getCompetitorProfiles()).hasSize(1);
        assertThat(run.getCompetitorProfiles().get(0).getFeatureTree().getRoots())
                .extracting(node -> node.getName())
                .containsExactly("Composer");
        assertThat(run.getArtifacts())
                .filteredOn(artifact -> artifact.getType() == ArtifactType.COMPETITOR_PROFILE)
                .last()
                .satisfies(artifact -> assertThat(artifact.getContent()).contains("Composer", "$20/month"));
        assertThat(run.getCompetitorFactSets()).hasSize(1);
        assertThat(run.getCompetitorFactSets().get(0).getFacts())
                .anySatisfy(fact -> {
                    assertThat(fact.getFactType()).isEqualTo(FactType.FEATURE);
                    assertThat(fact.getValue()).contains("Composer");
                    assertThat(fact.getEvidenceIds()).containsExactly("S1");
                });
        assertThat(run.getArtifacts())
                .filteredOn(artifact -> artifact.getType() == ArtifactType.FACT_EXTRACTION)
                .last()
                .satisfies(artifact -> assertThat(artifact.getContent()).contains("F", "Composer"));
    }

    @Test
    void sendsDimensionGroupedRagEvidencePackToLlm() {
        AtomicReference<String> userPrompt = new AtomicReference<>();
        LlmClient llmClient = new LlmClient() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String complete(ChatRequest request) {
                userPrompt.set(request.getMessages().get(1).getContent());
                return """
                        {
                          "profiles": [
                            {
                              "productName": "Cursor",
                              "companyName": "Cursor",
                              "positioning": "AI code editor",
                              "targetUsers": ["Developers"],
                              "features": [
                                {"name":"Composer","description":"Multi-file editing","evidenceIds":["S1"]}
                              ],
                              "pricing": {
                                "strategySummary": "Pro plan is available",
                                "hasFreePlan": true,
                                "plans": [
                                  {"name":"Pro","priceText":"$20/month","billingCycle":"monthly","targetSegment":"Developers","includedFeatures":["Composer"],"evidenceIds":["S1"]}
                                ],
                                "evidenceIds": ["S1"]
                              },
                              "personas": [],
                              "strengths": ["Multi-file editing"],
                              "weaknesses": [],
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
                List.of("pricing"),
                List.of("official_site"),
                List.of()
        ));
        run.getEvidenceSources().add(new EvidenceSource(
                "S1",
                "Cursor Pricing",
                "https://www.cursor.com/pricing",
                "pricing_page",
                "FETCHED",
                "LIVE_FETCHED",
                "HIGH",
                "NONE",
                "Cursor Pro costs $20/month.",
                "Cursor Pro costs $20/month. Composer is included.",
                "test evidence"
        ));
        EvidenceChunk chunk = new EvidenceChunk(
                "S1-C1",
                "S1",
                1,
                "Cursor Pricing",
                "https://www.cursor.com/pricing",
                "Cursor Pro costs $20/month. Composer is included."
        );
        chunk.setHeadingPath(List.of("Cursor Pricing", "Pricing"));
        chunk.setContentKind("pricing");
        chunk.setSourceAuthority("FIRST_PARTY_OFFICIAL");
        chunk.setSourceQuality("HIGH");
        run.getEvidenceChunks().add(chunk);

        new ExtractorNode(llmClient, new FallbackExtractionFactory()).execute(run);

        assertThat(userPrompt.get()).contains(
                "Competitor: Cursor",
                "Dimension: pricing",
                "[S1-C1]",
                "source=[S1]",
                "kind=pricing",
                "authority=FIRST_PARTY_OFFICIAL"
        );
        assertThat(userPrompt.get()).doesNotContain("raw=");
    }

    @Test
    void skipsCitationMarkersBeforeJsonResponse() {
        LlmClient llmClient = new LlmClient() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String complete(ChatRequest request) {
                return """
                        Based on [S1], here is the structured output:
                        {
                          "profiles": [
                            {
                              "productName": "Cursor",
                              "companyName": "Cursor",
                              "positioning": "AI code editor",
                              "targetUsers": ["Developers"],
                              "features": [
                                {"name":"Composer","description":"Multi-file code editing","evidenceIds":["S1"]}
                              ],
                              "pricing": {
                                "strategySummary": "Pro plan is available",
                                "hasFreePlan": true,
                                "plans": [
                                  {"name":"Pro","priceText":"$20/month","billingCycle":"monthly","targetSegment":"Developers","includedFeatures":["Composer"],"evidenceIds":["S1"]}
                                ],
                                "evidenceIds": ["S1"]
                              },
                              "personas": [],
                              "strengths": ["Multi-file editing"],
                              "weaknesses": ["Enterprise controls need verification"],
                              "evidenceIds": ["S1"]
                            }
                          ]
                        }
                        """;
            }
        };
        AnalysisRun run = runWithCursorEvidence();

        new ExtractorNode(llmClient, new FallbackExtractionFactory()).execute(run);

        assertThat(run.getRecommendedActions()).noneMatch(action -> action.contains("LLM Schema"));
        assertThat(run.getCompetitorProfiles()).hasSize(1);
        assertThat(run.getCompetitorProfiles().get(0).getFeatureTree().getRoots())
                .extracting(node -> node.getName())
                .containsExactly("Composer");
    }

    @Test
    void acceptsProfilesObjectAndBracketedEvidenceIds() {
        LlmClient llmClient = new LlmClient() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String complete(ChatRequest request) {
                return """
                        {
                          "profiles": {
                            "Cursor": {
                              "companyName": "Cursor",
                              "positioning": "AI code editor",
                              "targetUsers": ["Developers"],
                              "features": [
                                {"name":"Composer","description":"Multi-file code editing","evidenceIds":["[S1]"]}
                              ],
                              "pricing": {
                                "strategySummary": "Pro plan is available",
                                "hasFreePlan": true,
                                "plans": [
                                  {"name":"Pro","priceText":"$20/month","billingCycle":"monthly","targetSegment":"Developers","includedFeatures":["Composer"],"evidenceIds":["[S1]"]}
                                ],
                                "evidenceIds": ["[S1]"]
                              },
                              "personas": [],
                              "strengths": ["Multi-file editing"],
                              "weaknesses": ["Enterprise controls need verification"],
                              "evidenceIds": ["[S1]"]
                            }
                          }
                        }
                        """;
            }
        };
        AnalysisRun run = runWithCursorEvidence();

        new ExtractorNode(llmClient, new FallbackExtractionFactory()).execute(run);

        assertThat(run.getRecommendedActions()).noneMatch(action -> action.contains("LLM Schema"));
        assertThat(run.getCompetitorProfiles()).hasSize(1);
        assertThat(run.getCompetitorProfiles().get(0).getProductName()).isEqualTo("Cursor");
        assertThat(run.getCompetitorProfiles().get(0).getEvidenceIds()).containsExactly("S1");
        assertThat(run.getCompetitorProfiles().get(0).getFeatureTree().getRoots().get(0).getEvidenceIds())
                .containsExactly("S1");
    }

    @Test
    void keepsRiskManagementFactsButFiltersAnalyticalJudgments() {
        LlmClient llmClient = new LlmClient() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String complete(ChatRequest request) {
                return """
                        {
                          "profiles": [
                            {
                              "productName": "Cursor",
                              "companyName": "Cursor",
                              "positioning": "AI code editor",
                              "targetUsers": ["Developers"],
                              "features": [
                                {"name":"Composer","description":"Multi-file code editing","evidenceIds":["S1"]}
                              ],
                              "pricing": {
                                "strategySummary": "Pro plan is available",
                                "hasFreePlan": true,
                                "plans": [],
                                "evidenceIds": ["S1"]
                              },
                              "personas": [],
                              "strengths": ["Risk management dashboard is documented", "should prioritize enterprise governance"],
                              "weaknesses": ["recommend deeper pricing validation"],
                              "evidenceIds": ["S1"]
                            }
                          ]
                        }
                        """;
            }
        };
        AnalysisRun run = runWithCursorEvidence();

        new ExtractorNode(llmClient, new FallbackExtractionFactory()).execute(run);

        assertThat(run.getCompetitorProfiles()).hasSize(1);
        assertThat(run.getCompetitorProfiles().get(0).getStrengths())
                .contains("Risk management dashboard is documented")
                .doesNotContain("should prioritize enterprise governance");
        assertThat(run.getRecommendedActions())
                .anyMatch(action -> action.contains("Extractor filtered non-factual weaknesses for Cursor"));
    }

    @Test
    void competitorProfileIsProjectedOnlyFromAcceptedFacts() {
        LlmClient llmClient = new LlmClient() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String complete(ChatRequest request) {
                return """
                        {
                          "profiles": [
                            {
                              "productName": "Cursor",
                              "companyName": "Cursor",
                              "positioning": "AI code editor",
                              "targetUsers": ["Developers"],
                              "features": [
                                {"name":"Composer","description":"Multi-file code editing","evidenceIds":["S1"]},
                                {"name":"Invented roadmap","description":"Unsupported future plan","evidenceIds":["S404"]}
                              ],
                              "pricing": {
                                "strategySummary": "待验证",
                                "hasFreePlan": false,
                                "plans": [],
                                "evidenceIds": []
                              },
                              "personas": [],
                              "strengths": ["Documented editing workflow"],
                              "weaknesses": ["Unsupported strategic risk"],
                              "evidenceIds": ["S1"]
                            }
                          ]
                        }
                        """;
            }
        };
        AnalysisRun run = runWithCursorEvidence();

        new ExtractorNode(llmClient, new FallbackExtractionFactory()).execute(run);

        assertThat(run.getCompetitorProfiles()).hasSize(1);
        assertThat(run.getCompetitorProfiles().get(0).getFeatureTree().getRoots())
                .extracting(node -> node.getName())
                .containsExactly("Composer");
        assertThat(run.getCompetitorProfiles().get(0).getPricingModel().getStrategySummary()).isEqualTo("待验证");
        assertThat(run.getCompetitorFactSets().get(0).getFacts())
                .extracting(fact -> fact.getValue())
                .noneMatch(value -> value.contains("Invented roadmap"));
    }

    private AnalysisRun runWithCursorEvidence() {
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
                "Cursor Composer supports multi-file code edits. Cursor Pro costs $20/month.",
                "Cursor Composer supports multi-file code edits. Cursor Pro costs $20/month.",
                "test evidence"
        ));
        return run;
    }
}
