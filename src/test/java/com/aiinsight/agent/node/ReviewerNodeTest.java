package com.aiinsight.agent.node;

import com.aiinsight.llm.ChatRequest;
import com.aiinsight.llm.LlmClient;
import com.aiinsight.model.enums.AgentName;
import com.aiinsight.model.enums.ArtifactType;
import com.aiinsight.model.enums.ClaimType;
import com.aiinsight.model.enums.ConfidenceLevel;
import com.aiinsight.model.enums.FactType;
import com.aiinsight.model.enums.ReviewAction;
import com.aiinsight.model.enums.ReviewLocationType;
import com.aiinsight.model.run.AnalysisArtifact;
import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.model.run.EvidenceChunk;
import com.aiinsight.model.run.EvidenceSource;
import com.aiinsight.model.run.ReviewRepairDelta;
import com.aiinsight.model.review.ReviewDecision;
import com.aiinsight.model.review.ReviewRepairTask;
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

        new ReviewerNode(new CitationCoverageEvaluator(), highFactFindingLlmClient(claim.getId(), "F30", "S30-C1", "S30"), new FallbackReviewReportFactory()).execute(run);

        assertThat(run.getReviewDecision().getAction()).isEqualTo(ReviewAction.REWORK_ANALYSIS);
        assertThat(run.getReviewDecision().getTargetAgent()).isEqualTo(AgentName.EXTRACTOR);
        assertThat(run.getReviewDecision().getFindingCategories()).contains("fact_unsupported_by_evidence");
        assertThat(run.getReviewDecision().getReason()).contains("事实未被证据支撑");
        assertThat(run.getReviewFindings())
                .allSatisfy(finding -> assertThat(finding.getTargetAgent()).isNotNull())
                .anySatisfy(finding -> {
                    assertThat(finding.getCategory()).isEqualTo("fact_unsupported_by_evidence");
                    assertThat(finding.getTargetAgent()).isEqualTo(AgentName.EXTRACTOR);
                });
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
    void sendsOnlyExtractorScopedRepairTasksWhenFactAndClaimBlockersCoexist() {
        AnalysisRun run = new AnalysisRun();
        run.addArtifact(new AnalysisArtifact(ArtifactType.REPORT_DRAFT, "draft", "Summary only.", List.of()));
        run.getEvidenceSources().add(source("S32", "Cursor Composer supports multi-file code editing."));
        run.getEvidenceChunks().add(chunk("S32-C1", "S32", "feature", "Cursor Composer supports multi-file code editing."));
        ExtractedFact fact = fact("F32", FactType.SECURITY, "compliance",
                "Cursor includes SOC 2 enterprise compliance controls.", List.of("S32"), List.of("S32-C1"));
        run.getCompetitorFactSets().add(factSet(fact));
        AnalysisClaim claim = claim("Cursor includes SOC 2 enterprise compliance controls.", List.of("S32"), List.of("F32"));
        run.getClaims().add(claim);

        new ReviewerNode(new CitationCoverageEvaluator(), highFactAndClaimFindingLlmClient(claim.getId(), "F32", "S32-C1", "S32"), new FallbackReviewReportFactory()).execute(run);

        assertThat(run.getReviewFindings())
                .anySatisfy(finding -> {
                    assertThat(finding.getSeverity()).isEqualTo(com.aiinsight.model.enums.ReviewSeverity.HIGH);
                    assertThat(finding.getCategory()).isEqualTo("claim_weak_support");
                    assertThat(finding.getClaimId()).isEqualTo(claim.getId());
                    assertThat(finding.getTargetAgent()).isEqualTo(AgentName.ANALYST);
                });
        assertThat(run.getReviewDecision().getTargetAgent()).isEqualTo(AgentName.EXTRACTOR);
        assertThat(run.getReviewDecision().getRepairTasks())
                .allSatisfy(task -> {
                    assertThat(task.getTargetAgent()).isEqualTo(AgentName.EXTRACTOR);
                    assertThat(task.getCategory()).startsWith("fact_");
                    assertThat(task.getFactId()).isEqualTo("F32");
                });
        assertThat(run.getReviewDecision().getRepairTasks())
                .noneMatch(task -> "claim_weak_support".equals(task.getCategory()));
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

        new ReviewerNode(new CitationCoverageEvaluator(), claimFactMismatchLlmClient(claim.getId(), "F31", "S31"), new FallbackReviewReportFactory()).execute(run);

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

    @Test
    void escalatesRepeatedAnalystRepairWithoutUpstreamChangeToExtractor() {
        AnalysisRun run = new AnalysisRun();
        run.addArtifact(new AnalysisArtifact(ArtifactType.REPORT_DRAFT, "draft", "Summary only.", List.of()));
        run.getEvidenceSources().add(source("S33", "Cursor Composer supports multi-file code editing."));
        run.getEvidenceChunks().add(chunk("S33-C1", "S33", "feature", "Cursor Composer supports multi-file code editing."));
        ExtractedFact fact = fact("F33", FactType.FEATURE, "composer",
                "Cursor Composer supports multi-file code editing.", List.of("S33"), List.of("S33-C1"));
        run.getCompetitorFactSets().add(factSet(fact));
        AnalysisClaim claim = claim("Cursor is the best enterprise governance platform.", List.of("S33"), List.of("F33"));
        run.getClaims().add(claim);
        run.setLastReviewRepairDelta(unchangedUpstreamDelta(AgentName.ANALYST, 1));

        new ReviewerNode(new CitationCoverageEvaluator(), claimFactMismatchLlmClient(claim.getId(), "F33", "S33"), new FallbackReviewReportFactory()).execute(run);

        assertThat(run.getReviewDecision().getAction()).isEqualTo(ReviewAction.REWORK_ANALYSIS);
        assertThat(run.getReviewDecision().getTargetAgent()).isEqualTo(AgentName.EXTRACTOR);
        assertThat(run.getReviewDecision().getReason()).contains("Analyst");
    }

    @Test
    void doesNotEscalateCascadedManualResearcherRerunFromAnalystDelta() {
        AnalysisRun run = new AnalysisRun();
        run.addArtifact(new AnalysisArtifact(ArtifactType.REPORT_DRAFT, "draft", "Summary only.", List.of()));
        run.getEvidenceSources().add(source("S35", "Cursor Composer supports multi-file code editing."));
        run.getEvidenceChunks().add(chunk("S35-C1", "S35", "feature", "Cursor Composer supports multi-file code editing."));
        ExtractedFact fact = fact("F35", FactType.FEATURE, "composer",
                "Cursor Composer supports multi-file code editing.", List.of("S35"), List.of("S35-C1"));
        run.getCompetitorFactSets().add(factSet(fact));
        AnalysisClaim claim = claim("Cursor is the best enterprise governance platform.", List.of("S35"), List.of("F35"));
        run.getClaims().add(claim);
        ReviewDecision manualDecision = new ReviewDecision();
        manualDecision.setAction(ReviewAction.RECOLLECT_EVIDENCE);
        manualDecision.setTargetAgent(AgentName.RESEARCHER);
        run.setManualRerunDecision(manualDecision);
        run.setLastReviewRepairDelta(unchangedUpstreamDelta(AgentName.ANALYST, 1));

        new ReviewerNode(new CitationCoverageEvaluator(), claimFactMismatchLlmClient(claim.getId(), "F35", "S35"), new FallbackReviewReportFactory()).execute(run);

        assertThat(run.getReviewDecision().getAction()).isEqualTo(ReviewAction.REWORK_ANALYSIS);
        assertThat(run.getReviewDecision().getTargetAgent()).isEqualTo(AgentName.ANALYST);
        assertThat(run.getReviewDecision().getReason()).contains("结构化分析层");
    }

    @Test
    void escalatesRepeatedExtractorRepairWithoutEvidenceChangeToResearcher() {
        AnalysisRun run = new AnalysisRun();
        run.addArtifact(new AnalysisArtifact(ArtifactType.REPORT_DRAFT, "draft", "Summary only.", List.of()));
        run.getEvidenceSources().add(source("S34", "Cursor Composer supports multi-file code editing."));
        run.getEvidenceChunks().add(chunk("S34-C1", "S34", "feature", "Cursor Composer supports multi-file code editing."));
        ExtractedFact fact = fact("F34", FactType.SECURITY, "compliance",
                "Cursor includes SOC 2 enterprise compliance controls.", List.of("S34"), List.of("S34-C1"));
        run.getCompetitorFactSets().add(factSet(fact));
        run.getClaims().add(claim("Cursor includes SOC 2 enterprise compliance controls.", List.of("S34"), List.of("F34")));
        run.setLastReviewRepairDelta(unchangedUpstreamDelta(AgentName.EXTRACTOR, 1));

        new ReviewerNode(new CitationCoverageEvaluator(), highFactFindingLlmClient(run.getClaims().get(0).getId(), "F34", "S34-C1", "S34"), new FallbackReviewReportFactory()).execute(run);

        assertThat(run.getReviewDecision().getAction()).isEqualTo(ReviewAction.RECOLLECT_EVIDENCE);
        assertThat(run.getReviewDecision().getTargetAgent()).isEqualTo(AgentName.RESEARCHER);
        assertThat(run.getReviewDecision().getRequiredEvidenceTypes()).contains("official_site", "pricing_page", "public_review");
        assertThat(run.getReviewDecision().getReason()).contains("Extractor");
    }

    @Test
    void capsLlmFindingsPerSubtask() {
        AnalysisRun run = new AnalysisRun();
        run.addArtifact(new AnalysisArtifact(
                ArtifactType.REPORT_DRAFT,
                "draft",
                "Cursor supports multi-file code editing [S30].",
                List.of("S30")
        ));
        run.getEvidenceSources().add(source("S30", "Cursor Composer supports multi-file code editing."));

        new ReviewerNode(new CitationCoverageEvaluator(), noisyLlmClient(), new FallbackReviewReportFactory()).execute(run);

        long llmFindingCount = run.getReviewFindings().stream()
                .filter(finding -> "llm_overclaim".equals(finding.getCategory()))
                .count();
        assertThat(llmFindingCount).isLessThanOrEqualTo(4);
        assertThat(run.getReviewFindings().stream()
                .filter(finding -> "llm_overclaim".equals(finding.getCategory()))
                .allMatch(finding -> finding.getMessage().length() <= 183))
                .isTrue();
    }

    @Test
    void dropsLlmCitationMissingFindingForReportScaffolding() {
        AnalysisRun run = new AnalysisRun();
        run.addArtifact(new AnalysisArtifact(
                ArtifactType.REPORT_DRAFT,
                "draft",
                "基于对当前信息与风险的判断，以下是明确的行动建议。",
                List.of()
        ));

        new ReviewerNode(new CitationCoverageEvaluator(), scaffoldingCitationMissingLlmClient(), new FallbackReviewReportFactory()).execute(run);

        assertThat(run.getReviewFindings()).isEmpty();
        assertThat(run.getReviewDecision().getAction()).isEqualTo(ReviewAction.PASS);
    }

    @Test
    void routesLlmMissingCitationToWriter() {
        AnalysisRun run = new AnalysisRun();
        run.addArtifact(new AnalysisArtifact(
                ArtifactType.REPORT_DRAFT,
                "draft",
                "Cursor has the strongest enterprise governance advantage.",
                List.of()
        ));

        new ReviewerNode(new CitationCoverageEvaluator(), missingCitationLlmClient(), new FallbackReviewReportFactory()).execute(run);

        assertThat(run.getReviewFindings())
                .anySatisfy(finding -> {
                    assertThat(finding.getSeverity()).isEqualTo(com.aiinsight.model.enums.ReviewSeverity.HIGH);
                    assertThat(finding.getCategory()).isEqualTo("missing_citation");
                    assertThat(finding.getLocationType()).isEqualTo(ReviewLocationType.REPORT_PARAGRAPH);
                });
        assertThat(run.getReviewDecision().getAction()).isEqualTo(ReviewAction.REVISE_REPORT);
        assertThat(run.getReviewDecision().getTargetAgent()).isEqualTo(AgentName.WRITER);
        assertThat(run.getReviewDecision().getRepairTasks())
                .allSatisfy(task -> assertThat(task.getTargetAgent()).isEqualTo(AgentName.WRITER));
    }

    @Test
    void normalizesSourceFindingToEvidenceLocationFromMessageCitation() {
        AnalysisRun run = new AnalysisRun();
        run.addArtifact(new AnalysisArtifact(
                ArtifactType.REPORT_DRAFT,
                "draft",
                "Cursor enterprise governance needs stronger evidence.",
                List.of()
        ));
        run.getEvidenceSources().add(source("S44", "Cursor pricing page."));

        new ReviewerNode(new CitationCoverageEvaluator(), lowQualitySourceLlmClient(), new FallbackReviewReportFactory()).execute(run);

        assertThat(run.getReviewFindings())
                .anySatisfy(finding -> {
                    assertThat(finding.getCategory()).isEqualTo("low_quality_source");
                    assertThat(finding.getCitationKey()).isEqualTo("S44");
                    assertThat(finding.getLocationType()).isEqualTo(ReviewLocationType.EVIDENCE_SOURCE);
                    assertThat(finding.getParagraphIndex()).isNull();
                });
    }

    @Test
    void marksUnmatchedReportFindingAsGlobalReportLocation() {
        AnalysisRun run = new AnalysisRun();
        run.addArtifact(new AnalysisArtifact(
                ArtifactType.REPORT_DRAFT,
                "draft",
                "## 关键洞察\n\nCursor has cited enterprise governance evidence [S1].",
                List.of("S1")
        ));
        run.getEvidenceSources().add(source("S1", "Cursor enterprise governance evidence."));

        new ReviewerNode(new CitationCoverageEvaluator(), unmatchedReportGapLlmClient(), new FallbackReviewReportFactory()).execute(run);

        assertThat(run.getReviewFindings())
                .anySatisfy(finding -> {
                    assertThat(finding.getCategory()).isEqualTo("report_dimension_coverage_gap");
                    assertThat(finding.getLocationType()).isEqualTo(ReviewLocationType.GLOBAL_REPORT);
                    assertThat(finding.getParagraphIndex()).isNull();
                    assertThat(finding.getExcerpt()).contains("目标用户维度");
                });
    }

    @Test
    void keepsUnlocatedHighLlmFindingBlocking() {
        AnalysisRun run = new AnalysisRun();
        run.addArtifact(new AnalysisArtifact(
                ArtifactType.REPORT_DRAFT,
                "draft",
                "Cursor has the strongest enterprise governance advantage.",
                List.of()
        ));

        new ReviewerNode(new CitationCoverageEvaluator(), unlocatedHighLlmClient(), new FallbackReviewReportFactory()).execute(run);

        assertThat(run.getReviewFindings())
                .anySatisfy(finding -> {
                    assertThat(finding.getSeverity()).isEqualTo(com.aiinsight.model.enums.ReviewSeverity.HIGH);
                    assertThat(finding.getCategory()).isEqualTo("unsupported_recommendation");
                    assertThat(finding.getRecommendation()).contains("缺少精确定位");
                });
        assertThat(run.getReviewDecision().getAction()).isEqualTo(ReviewAction.REVISE_REPORT);
        assertThat(run.getReviewDecision().getTargetAgent()).isEqualTo(AgentName.WRITER);
    }

    @Test
    void repairVerificationKeepsNewHighFindingsBlocking() {
        AnalysisRun run = new AnalysisRun();
        run.addArtifact(new AnalysisArtifact(
                ArtifactType.REPORT_DRAFT,
                "draft",
                "Cursor has the strongest enterprise governance advantage.",
                List.of()
        ));
        ReviewDecision previousDecision = new ReviewDecision();
        previousDecision.setAction(ReviewAction.REVISE_REPORT);
        previousDecision.setTargetAgent(AgentName.WRITER);
        ReviewRepairTask previousTask = new ReviewRepairTask();
        previousTask.setTargetAgent(AgentName.WRITER);
        previousTask.setCategory("missing_citation");
        previousTask.setParagraphIndex(9);
        previousDecision.setRepairTasks(List.of(previousTask));
        run.setReviewDecision(previousDecision);

        new ReviewerNode(new CitationCoverageEvaluator(), unlocatedHighLlmClient(), new FallbackReviewReportFactory()).execute(run);

        assertThat(run.getReviewFindings())
                .anySatisfy(finding -> {
                    assertThat(finding.getSeverity()).isEqualTo(com.aiinsight.model.enums.ReviewSeverity.HIGH);
                    assertThat(finding.getMessage()).contains("返工验证模式中发现的新 HIGH 问题");
                });
        assertThat(run.getReviewDecision().getAction()).isEqualTo(ReviewAction.REVISE_REPORT);
        assertThat(run.getRecommendedActions())
                .anySatisfy(action -> assertThat(action).contains("新 HIGH 问题"));
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

    private LlmClient missingCitationLlmClient() {
        return new LlmClient() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String complete(ChatRequest request) {
                return """
                        {
                          "summary": "reviewed",
                          "findings": [
                            {
                              "severity": "HIGH",
                              "category": "missing_citation",
                              "message": "Enterprise governance advantage is a competitive claim and needs citation.",
                              "recommendation": "Add a relevant citation or downgrade the claim.",
                              "paragraphIndex": 0,
                              "excerpt": "Cursor has the strongest enterprise governance advantage."
                            }
                          ]
                        }
                        """;
            }
        };
    }

    private LlmClient scaffoldingCitationMissingLlmClient() {
        return new LlmClient() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String complete(ChatRequest request) {
                return """
                        {
                          "summary": "reviewed",
                          "findings": [
                            {
                              "severity": "MEDIUM",
                              "category": "citation_missing",
                              "message": "Action-introduction sentence has no citation.",
                              "recommendation": "No change needed.",
                              "paragraphIndex": 1,
                              "excerpt": "基于对当前信息与风险的判断，以下是明确的行动建议。"
                            }
                          ]
                        }
                        """;
            }
        };
    }

    private LlmClient unlocatedHighLlmClient() {
        return fixedLlmFinding("""
                {
                  "severity": "HIGH",
                  "category": "unsupported_recommendation",
                  "message": "The report makes a strong enterprise recommendation without sufficient support.",
                  "recommendation": "Downgrade the recommendation or add direct evidence."
                }
                """);
    }

    private LlmClient lowQualitySourceLlmClient() {
        return fixedLlmFinding("""
                {
                  "severity": "MEDIUM",
                  "category": "low_quality_source",
                  "message": "Source [S44] is a weak third-party page for enterprise governance.",
                  "recommendation": "Use a stronger official source."
                }
                """);
    }

    private LlmClient unmatchedReportGapLlmClient() {
        return fixedLlmFinding("""
                {
                  "severity": "MEDIUM",
                  "category": "report_dimension_coverage_gap",
                  "message": "Report does not cover the target-user dimension.",
                  "recommendation": "Add a target-user comparison or keep it as a global gap.",
                  "excerpt": "关键洞察部分完全没有目标用户维度。"
                }
                """);
    }

    private LlmClient noisyLlmClient() {
        return new LlmClient() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String complete(ChatRequest request) {
                StringBuilder findings = new StringBuilder();
                for (int i = 0; i < 10; i++) {
                    if (i > 0) {
                        findings.append(',');
                    }
                    findings.append("""
                            {
                              "severity": "HIGH",
                              "category": "llm_overclaim",
                              "message": "Finding %d: this deliberately verbose semantic review finding should be bounded before it is merged into the run state so the frontend replay remains readable and stable.",
                              "recommendation": "Keep only the highest signal reviewer findings.",
                              "paragraphIndex": 1,
                              "excerpt": "Cursor supports multi-file code editing [S30]."
                            }
                            """.formatted(i));
                }
                return """
                        {
                          "summary": "reviewed",
                          "findings": [%s]
                        }
                        """.formatted(findings);
            }
        };
    }

    private LlmClient highClaimFindingLlmClient(String claimId) {
        return new LlmClient() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String complete(ChatRequest request) {
                return """
                        {
                          "summary": "reviewed",
                          "findings": [
                            {
                              "severity": "HIGH",
                              "category": "claim_weak_support",
                              "claimId": "%s",
                              "citationKey": "S32",
                              "message": "Claim cites weak evidence and should be repaired by Analyst.",
                              "recommendation": "Rebind the claim to stronger evidence or downgrade confidence."
                            }
                          ]
                        }
                        """.formatted(claimId);
            }
        };
    }

    private LlmClient highFactFindingLlmClient(String claimId, String factId, String chunkKey, String citationKey) {
        return fixedLlmFinding("""
                {
                  "severity": "HIGH",
                  "category": "fact_unsupported_by_evidence",
                  "claimId": "%s",
                  "factId": "%s",
                  "chunkKey": "%s",
                  "citationKey": "%s",
                  "message": "Extracted fact is not supported by its bound evidence.",
                  "recommendation": "Regenerate the extracted fact or move it to unknown facts."
                }
                """.formatted(claimId, factId, chunkKey, citationKey));
    }

    private LlmClient claimFactMismatchLlmClient(String claimId, String factId, String citationKey) {
        return fixedLlmFinding("""
                {
                  "severity": "HIGH",
                  "category": "claim_fact_mismatch",
                  "claimId": "%s",
                  "factId": "%s",
                  "citationKey": "%s",
                  "message": "Claim over-interprets the bound extracted fact.",
                  "recommendation": "Rewrite the claim from the bound fact or downgrade confidence."
                }
                """.formatted(claimId, factId, citationKey));
    }

    private LlmClient highFactAndClaimFindingLlmClient(String claimId, String factId, String chunkKey, String citationKey) {
        return fixedLlmFindings(
                """
                        {
                          "severity": "HIGH",
                          "category": "fact_unsupported_by_evidence",
                          "claimId": "%s",
                          "factId": "%s",
                          "chunkKey": "%s",
                          "citationKey": "%s",
                          "message": "Extracted fact is not supported by its bound evidence.",
                          "recommendation": "Regenerate the extracted fact or move it to unknown facts."
                        }
                        """.formatted(claimId, factId, chunkKey, citationKey),
                """
                        {
                          "severity": "HIGH",
                          "category": "claim_weak_support",
                          "claimId": "%s",
                          "citationKey": "%s",
                          "message": "Claim cites weak evidence and should be repaired by Analyst.",
                          "recommendation": "Rebind the claim to stronger evidence or downgrade confidence."
                        }
                        """.formatted(claimId, citationKey)
        );
    }

    private LlmClient fixedLlmFinding(String findingJson) {
        return fixedLlmFindings(findingJson);
    }

    private LlmClient fixedLlmFindings(String... findingsJson) {
        return new LlmClient() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String complete(ChatRequest request) {
                return """
                        {
                          "summary": "reviewed",
                          "findings": [%s]
                        }
                        """.formatted(String.join(",", findingsJson));
            }
        };
    }

    private ReviewRepairDelta unchangedUpstreamDelta(AgentName agentName, int findingsBefore) {
        ReviewRepairDelta delta = new ReviewRepairDelta();
        delta.setAgentName(agentName);
        delta.setChanged(true);
        delta.setFindingsBefore(findingsBefore);
        delta.setFindingsAfter(findingsBefore);
        delta.setEvidenceSourcesBefore(1);
        delta.setEvidenceSourcesAfter(1);
        delta.setClaimsBefore(1);
        delta.setClaimsAfter(2);
        delta.setArtifactsBefore(1);
        delta.setArtifactsAfter(2);
        delta.setEvidenceFingerprintBefore("evidence");
        delta.setEvidenceFingerprintAfter("evidence");
        delta.setProfileFingerprintBefore("profile");
        delta.setProfileFingerprintAfter("profile");
        delta.setFactFingerprintBefore("fact");
        delta.setFactFingerprintAfter("fact");
        delta.setClaimsFingerprintBefore("claims-before");
        delta.setClaimsFingerprintAfter("claims-after");
        delta.setReportFingerprintBefore("report");
        delta.setReportFingerprintAfter("report");
        return delta;
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
