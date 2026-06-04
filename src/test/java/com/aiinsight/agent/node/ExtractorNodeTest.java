package com.aiinsight.agent.node;

import com.aiinsight.llm.ChatRequest;
import com.aiinsight.llm.LlmClient;
import com.aiinsight.model.enums.AgentName;
import com.aiinsight.model.enums.ArtifactType;
import com.aiinsight.model.enums.FactType;
import com.aiinsight.model.enums.ReviewAction;
import com.aiinsight.model.review.ReviewRepairTask;
import com.aiinsight.model.run.AnalysisRequirement;
import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.model.run.AgentTrace;
import com.aiinsight.model.run.EvidenceChunk;
import com.aiinsight.model.run.EvidenceSource;
import com.aiinsight.model.schema.CompetitorProfile;
import com.aiinsight.observability.AgentTraceContext;
import com.aiinsight.service.fallback.FallbackExtractionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ExtractorNodeTest {

    @AfterEach
    void clearTrace() {
        AgentTraceContext.clear();
    }

    @Test
    void extractorPromptIncludesTargetedRepairTasksDuringReviewRework() {
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
                                "strategySummary": "待验证",
                                "hasFreePlan": false,
                                "plans": [],
                                "evidenceIds": []
                              },
                              "personas": [],
                              "strengths": ["Multi-file editing"],
                              "weaknesses": ["待验证"],
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
        run.getReviewDecision().setAction(ReviewAction.REWORK_ANALYSIS);
        run.getReviewDecision().setTargetAgent(AgentName.EXTRACTOR);
        run.getReviewDecision().setRepairScopeSummary("目标 Agent=EXTRACTOR；阻断问题=1。");
        run.getReviewDecision().setRepairInstructions(List.of("Extractor should repair only affected extracted facts."));
        ReviewRepairTask task = new ReviewRepairTask();
        task.setTargetAgent(AgentName.EXTRACTOR);
        task.setAction("REPAIR_FACT_EXTRACTION");
        task.setFactId("F7");
        task.setChunkKey("S1-C2");
        task.setCitationKey("S1");
        task.setCurrentText("Unsupported pricing fact");
        task.setInstruction("Fix fact=F7 evidence binding.");
        task.setExpectedFix("Move unsupported value to unknowns.");
        task.setAcceptanceCriteria("Fact must cite supporting evidence or be removed.");
        run.getReviewDecision().setRepairTasks(List.of(task));

        new ExtractorNode(llmClient, new FallbackExtractionFactory()).execute(run);

        assertThat(promptCapture.get())
                .contains("复核修复任务", "F7", "S1-C2", "Unsupported pricing fact", "Move unsupported value to unknowns");
    }

    @Test
    void extractsProfilesPerCompetitorToKeepJsonSmall() {
        List<String> subtasks = new ArrayList<>();
        LlmClient llmClient = new LlmClient() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String complete(ChatRequest request) {
                subtasks.add(request.getSubtaskName());
                String productName = request.getSubtaskName().contains("Claude Code") ? "Claude Code" : "Cursor";
                String evidenceId = "Claude Code".equals(productName) ? "S2" : "S1";
                return """
                        {
                          "profiles": [
                            {
                              "productName": "%s",
                              "companyName": "%s",
                              "positioning": "AI coding assistant",
                              "targetUsers": ["Developers"],
                              "features": [
                                {"name":"Coding workflow","description":"Evidence-backed coding workflow","evidenceIds":["%s"]}
                              ],
                              "pricing": {
                                "strategySummary": "待验证",
                                "hasFreePlan": false,
                                "plans": [],
                                "evidenceIds": []
                              },
                              "personas": [],
                              "strengths": ["Evidence-backed coding workflow"],
                              "weaknesses": ["待验证"],
                              "evidenceIds": ["%s"]
                            }
                          ]
                        }
                        """.formatted(productName, productName, evidenceId, evidenceId);
            }
        };
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "Analyze Cursor and Claude Code",
                "AI coding tools",
                List.of("Cursor", "Claude Code"),
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
                "Cursor supports coding workflow.",
                "Cursor supports coding workflow.",
                "test evidence"
        ));
        run.getEvidenceSources().add(new EvidenceSource(
                "S2",
                "Claude Code product page",
                "https://example.test/claude-code",
                "official_site",
                "FETCHED",
                "LIVE_FETCHED",
                "HIGH",
                "NONE",
                "Claude Code supports coding workflow.",
                "Claude Code supports coding workflow.",
                "test evidence"
        ));

        new ExtractorNode(llmClient, new FallbackExtractionFactory()).execute(run);

        assertThat(subtasks).containsExactly("profile-extraction:Cursor", "profile-extraction:Claude Code");
        assertThat(run.getCompetitorProfiles())
                .extracting(CompetitorProfile::getProductName)
                .containsExactly("Cursor", "Claude Code");
        assertThat(run.getRecommendedActions()).noneMatch(action -> action.contains("LLM Schema"));
    }

    @Test
    void toleratesTextualUnknownFreePlanFlagWithoutFallback() {
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
                              "companyName": "Anysphere",
                              "positioning": "AI code editor",
                              "targetUsers": ["Developers"],
                              "features": [
                                {"name":"Composer","description":"Multi-file code editing","evidenceIds":["S1"]}
                              ],
                              "pricing": {
                                "strategySummary": "Needs verification",
                                "hasFreePlan": "\\u5f85\\u9a8c\\u8bc1",
                                "plans": [],
                                "evidenceIds": ["S1"]
                              },
                              "personas": [],
                              "strengths": ["Multi-file editing"],
                              "weaknesses": ["Needs verification"],
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
                List.of("features", "pricing"),
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

        new ExtractorNode(llmClient, new FallbackExtractionFactory()).execute(run);

        assertThat(run.getRecommendedActions()).noneMatch(action -> action.contains("LLM Schema"));
        assertThat(run.getCompetitorProfiles()).hasSize(1);
        assertThat(run.getCompetitorProfiles().get(0).getProductName()).isEqualTo("Cursor");
    }

    @Test
    void toleratesSingleStringListFieldsFromLlmWithoutFallback() {
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
                              "targetUsers": "Developers",
                              "features": [
                                {"name":"Composer","description":"Multi-file code editing","evidenceIds":"S1"}
                              ],
                              "pricing": {
                                "strategySummary": "待验证",
                                "hasFreePlan": "待验证",
                                "plans": [],
                                "evidenceIds": []
                              },
                              "personas": [
                                {
                                  "name":"Developer",
                                  "segment":"Engineering",
                                  "companySize":"Any",
                                  "jobsToBeDone":"Edit code",
                                  "painPoints":"Context switching",
                                  "buyingConcerns":"Price",
                                  "evidenceIds":"S1"
                                }
                              ],
                              "strengths": "Multi-file editing",
                              "weaknesses": "待验证",
                              "evidenceIds": "S1"
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
        assertThat(run.getCompetitorProfiles().get(0).getProductName()).isEqualTo("Cursor");
        assertThat(run.getCompetitorProfiles().get(0).getFeatureTree().getRoots())
                .extracting(node -> node.getName())
                .containsExactly("Composer");
    }

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
    void acceptsTopLevelProfilesArray() {
        LlmClient llmClient = new LlmClient() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String complete(ChatRequest request) {
                return """
                        [
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
                            "strengths": ["Multi-file editing"],
                            "weaknesses": [],
                            "evidenceIds": ["S1"]
                          }
                        ]
                        """;
            }
        };
        AnalysisRun run = runWithCursorEvidence();

        new ExtractorNode(llmClient, new FallbackExtractionFactory()).execute(run);

        assertThat(run.getRecommendedActions()).noneMatch(action -> action.contains("LLM Schema"));
        assertThat(run.getCompetitorProfiles()).hasSize(1);
        assertThat(run.getCompetitorProfiles().get(0).getProductName()).isEqualTo("Cursor");
        assertThat(run.getCompetitorProfiles().get(0).getFeatureTree().getRoots())
                .extracting(node -> node.getName())
                .containsExactly("Composer");
    }

    @Test
    void acceptsCompetitorsWrapperWithMetadata() {
        LlmClient llmClient = new LlmClient() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String complete(ChatRequest request) {
                return """
                        {
                          "summary": "Structured profiles extracted from the evidence.",
                          "competitors": [
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
                              "strengths": ["Multi-file editing"],
                              "weaknesses": [],
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
    void acceptsProductMapWithMetadataFields() {
        LlmClient llmClient = new LlmClient() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String complete(ChatRequest request) {
                return """
                        {
                          "Cursor": {
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
                            "strengths": ["Multi-file editing"],
                            "weaknesses": [],
                            "evidenceIds": ["S1"]
                          },
                          "notes": "Only object-valued fields should be treated as profiles."
                        }
                        """;
            }
        };
        AnalysisRun run = runWithCursorEvidence();

        new ExtractorNode(llmClient, new FallbackExtractionFactory()).execute(run);

        assertThat(run.getRecommendedActions()).noneMatch(action -> action.contains("LLM Schema"));
        assertThat(run.getCompetitorProfiles()).hasSize(1);
        assertThat(run.getCompetitorProfiles().get(0).getProductName()).isEqualTo("Cursor");
        assertThat(run.getCompetitorProfiles().get(0).getFeatureTree().getRoots())
                .extracting(node -> node.getName())
                .containsExactly("Composer");
    }

    @Test
    void ignoresUnknownLlmFieldsInsideProfileDrafts() {
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
                              "website": "https://cursor.com",
                              "marketShare": "not in schema",
                              "positioning": "AI code editor",
                              "targetUsers": ["Developers"],
                              "features": [
                                {"name":"Composer","description":"Multi-file code editing","category":"editing","evidenceIds":["S1"]}
                              ],
                              "pricing": {
                                "strategySummary": "Pro plan is available",
                                "hasFreePlan": true,
                                "currency": "USD",
                                "plans": [
                                  {"name":"Pro","priceText":"$20/month","billingCycle":"monthly","targetSegment":"Developers","limits":"unknown","includedFeatures":["Composer"],"evidenceIds":["S1"]}
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
        AnalysisRun run = runWithCursorEvidence();

        new ExtractorNode(llmClient, new FallbackExtractionFactory()).execute(run);

        assertThat(run.getRecommendedActions()).noneMatch(action -> action.contains("LLM Schema"));
        assertThat(run.getCompetitorProfiles()).hasSize(1);
        assertThat(run.getCompetitorProfiles().get(0).getFeatureTree().getRoots())
                .extracting(node -> node.getName())
                .containsExactly("Composer");
    }

    @Test
    void recordsParseDiagnosticsWhenLlmJsonCannotBeConverted() {
        LlmClient llmClient = new LlmClient() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String complete(ChatRequest request) {
                return """
                        {
                          "profiles": "not a profiles array or object",
                          "notes": "This shape used to be hard to diagnose."
                        }
                        """;
            }
        };
        AnalysisRun run = runWithCursorEvidence();
        AgentTrace trace = new AgentTrace();
        AgentTraceContext.start(trace);

        new ExtractorNode(llmClient, new FallbackExtractionFactory()).execute(run);

        assertThat(run.getRecommendedActions()).anyMatch(action -> action.contains("LLM Schema"));
        assertThat(trace.getFallbackUsed()).isTrue();
        assertThat(trace.getProcessSnapshot())
                .contains(
                        "Extractor JSON parse failed",
                        "profilesShape=string",
                        "rawPreview=",
                        "not a profiles array or object"
                );
    }

    @Test
    void retriesMalformedProfileJsonBeforeFallback() {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> retrySubtask = new AtomicReference<>();
        LlmClient llmClient = new LlmClient() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String complete(ChatRequest request) {
                if (calls.incrementAndGet() == 1) {
                    return """
                            {"profiles":[{"productName":"Cursor","companyName":"Cursor","positioning":"AI code editor","targetUsers":["Developers"],"features":[{"name":"Composer","description":"Multi-file editing","evidenceIds":["S1"]}],"pricing":{"strategySummary":"待验证","hasFreePlan":false,"plans":[],"evidenceIds":[]},"personas":[],"strengths":["Multi-file editing"],"weaknesses":[],"evidenceIds":["S1"]}}
                            """;
                }
                retrySubtask.set(request.getSubtaskName());
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
                                "strategySummary": "待验证",
                                "hasFreePlan": false,
                                "plans": [],
                                "evidenceIds": []
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
        AnalysisRun run = runWithCursorEvidence();
        AgentTrace trace = new AgentTrace();
        AgentTraceContext.start(trace);

        new ExtractorNode(llmClient, new FallbackExtractionFactory()).execute(run);

        assertThat(calls.get()).isEqualTo(2);
        assertThat(retrySubtask.get()).isEqualTo("profile-json-repair");
        assertThat(trace.getFallbackUsed()).isFalse();
        assertThat(trace.getProcessSnapshot()).contains("Extractor JSON repair retry succeeded");
        assertThat(run.getRecommendedActions()).noneMatch(action -> action.contains("LLM Schema"));
        assertThat(run.getCompetitorProfiles()).hasSize(1);
        assertThat(run.getCompetitorProfiles().get(0).getFeatureTree().getRoots())
                .extracting(node -> node.getName())
                .containsExactly("Composer");
    }

    @Test
    void blankSingleCompetitorProfileFallsBackWithVisibleAction() {
        LlmClient llmClient = new LlmClient() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String complete(ChatRequest request) {
                return "";
            }
        };
        AnalysisRun run = runWithCursorEvidence();

        new ExtractorNode(llmClient, new FallbackExtractionFactory()).execute(run);

        assertThat(run.getRecommendedActions())
                .anyMatch(action -> action.contains("LLM Schema 抽取失败"));
        assertThat(run.getCompetitorProfiles())
                .extracting(CompetitorProfile::getProductName)
                .containsExactly("Cursor");
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
        run.getEvidenceSources().get(0).setSnippet("Cursor Composer supports multi-file code edits. Risk management dashboard is documented.");
        run.getEvidenceSources().get(0).setRawText("Cursor Composer supports multi-file code edits. Risk management dashboard is documented.");

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

    @Test
    void filtersTemplatePricingPlansBeforePublishingFacts() {
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
                                "strategySummary": "Pricing page is available",
                                "hasFreePlan": true,
                                "plans": [
                                  {
                                    "name":"\u516c\u5f00\u5957\u9910 / \u5b9a\u5236\u65b9\u6848",
                                    "priceText":"\u4ee5\u4ef7\u683c\u9875\u4e3a\u51c6",
                                    "billingCycle":"\u4ee5\u539f\u59cb\u9875\u9762\u4e3a\u51c6",
                                    "targetSegment":"\u76ee\u6807\u7528\u6237\u6216\u91c7\u8d2d\u4e3b\u4f53",
                                    "includedFeatures":["\u5df2\u62ab\u9732\u80fd\u529b","\u9002\u7528\u8303\u56f4","\u9650\u5236\u6761\u4ef6"],
                                    "evidenceIds":["S1"]
                                  }
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
        AnalysisRun run = runWithCursorEvidence();

        new ExtractorNode(llmClient, new FallbackExtractionFactory()).execute(run);

        assertThat(run.getCompetitorFactSets().get(0).getFacts())
                .filteredOn(fact -> "pricing_plan".equals(fact.getAttribute()))
                .isEmpty();
        assertThat(run.getCompetitorProfiles().get(0).getPricingModel().getPlans()).isEmpty();
        assertThat(run.getCompetitorFactSets().get(0).getFacts())
                .extracting(fact -> fact.getValue())
                .noneMatch(value -> value.contains("\u516c\u5f00\u5957\u9910") || value.contains("\u4ee5\u4ef7\u683c\u9875\u4e3a\u51c6"));
    }

    @Test
    void pricingFactsOnlyBindPricingEvidence() {
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
                                "strategySummary": "Cursor Pro costs $20/month.",
                                "hasFreePlan": false,
                                "plans": [
                                  {"name":"Pro","priceText":"$20/month","billingCycle":"monthly","targetSegment":"Developers","includedFeatures":["Composer"],"evidenceIds":["S1","S2"]}
                                ],
                                "evidenceIds": ["S1","S2"]
                              },
                              "personas": [],
                              "strengths": ["Multi-file editing"],
                              "weaknesses": [],
                              "evidenceIds": ["S1","S2"]
                            }
                          ]
                        }
                        """;
            }
        };
        AnalysisRun run = runWithMixedPricingEvidence();

        new ExtractorNode(llmClient, new FallbackExtractionFactory()).execute(run);

        assertThat(run.getCompetitorFactSets().get(0).getFacts())
                .filteredOn(fact -> "pricing_plan".equals(fact.getAttribute()))
                .singleElement()
                .satisfies(fact -> assertThat(fact.getEvidenceIds()).containsExactly("S2"));
    }

    @Test
    void dropsFactsWhenBoundEvidenceDoesNotSupportValue() {
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
                                {"name":"Enterprise security","description":"SSO and SCIM controls","evidenceIds":["S1"]}
                              ],
                              "pricing": {"strategySummary":"待验证","hasFreePlan":false,"plans":[],"evidenceIds":[]},
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
        AnalysisRun run = runWithCursorEvidence();

        new ExtractorNode(llmClient, new FallbackExtractionFactory()).execute(run);

        assertThat(run.getCompetitorFactSets().get(0).getFacts())
                .extracting(fact -> fact.getValue())
                .contains("Composer: Multi-file code editing")
                .noneMatch(value -> value.contains("SSO") || value.contains("SCIM"));
        assertThat(run.getCompetitorFactSets().get(0).getUnknowns())
                .anySatisfy(unknown -> assertThat(unknown.getField()).isEqualTo("feature"));
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

    private AnalysisRun runWithMixedPricingEvidence() {
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "Analyze Cursor",
                "AI coding tools",
                List.of("Cursor"),
                List.of("features", "pricing"),
                List.of("official_site", "pricing_page"),
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
        run.getEvidenceSources().add(new EvidenceSource(
                "S2",
                "Cursor Pricing",
                "https://example.test/cursor/pricing",
                "pricing_page",
                "FETCHED",
                "LIVE_FETCHED",
                "HIGH",
                "NONE",
                "Cursor Pro costs $20/month.",
                "Cursor Pro costs $20/month. Composer is included.",
                "test evidence"
        ));
        return run;
    }
}
