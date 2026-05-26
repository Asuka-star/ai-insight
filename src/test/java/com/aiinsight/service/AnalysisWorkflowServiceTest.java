package com.aiinsight.service;

import com.aiinsight.agent.node.AnalystNode;
import com.aiinsight.agent.node.ClarifierNode;
import com.aiinsight.agent.node.ExtractorNode;
import com.aiinsight.agent.node.ResearcherNode;
import com.aiinsight.agent.node.ReviewerNode;
import com.aiinsight.agent.node.RevisionNode;
import com.aiinsight.agent.node.WriterNode;
import com.aiinsight.dto.AddAnalysisContextRequest;
import com.aiinsight.dto.AddUserEvidenceRequest;
import com.aiinsight.dto.CreateAnalysisRunRequest;
import com.aiinsight.dto.UpdateAnalysisRequirementRequest;
import com.aiinsight.exception.InvalidRunStateException;
import com.aiinsight.llm.LlmClient;
import com.aiinsight.model.enums.AgentName;
import com.aiinsight.model.enums.AnalysisStatus;
import com.aiinsight.model.enums.ArtifactType;
import com.aiinsight.model.enums.ClaimType;
import com.aiinsight.model.enums.ContextIntent;
import com.aiinsight.model.enums.ReviewAction;
import com.aiinsight.model.enums.ReviewSeverity;
import com.aiinsight.model.enums.StepStatus;
import com.aiinsight.model.run.AnalysisArtifact;
import com.aiinsight.model.run.EvidenceChunk;
import com.aiinsight.model.run.AnalysisRequirement;
import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.model.run.EvidenceSource;
import com.aiinsight.model.review.ReviewFinding;
import com.aiinsight.model.schema.AnalysisClaim;
import com.aiinsight.model.schema.CompetitorProfile;
import com.aiinsight.repository.AnalysisRunRepository;
import com.aiinsight.service.fallback.FallbackAnalysisDraftFactory;
import com.aiinsight.service.fallback.FallbackExtractionFactory;
import com.aiinsight.service.fallback.FallbackReportDraftFactory;
import com.aiinsight.service.fallback.FallbackReviewReportFactory;
import com.aiinsight.service.fallback.FallbackResearchPlanFactory;
import com.aiinsight.workflow.AnalysisLangGraphWorkflow;
import com.aiinsight.workflow.WorkflowNodeExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.support.TaskExecutorAdapter;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnalysisWorkflowServiceTest {

    @Test
    void createsDraftThenExecutesAfterRequirementConfirmation() {
        AnalysisWorkflowService service = newService();
        CreateAnalysisRunRequest request = new CreateAnalysisRunRequest();
        request.setPrompt("Analyze Notion and Confluence for AI document collaboration.");

        var draft = service.createDraft(request);

        assertThat(draft.getStatus()).isEqualTo(AnalysisStatus.AWAITING_CONFIRMATION);
        assertThat(draft.getSteps()).isEmpty();
        assertThat(draft.getClarificationDraft()).isNotNull();
        assertThat(draft.getClarificationDraft().isConfirmed()).isFalse();
        assertThat(draft.getClarificationDraft().getClarificationQuestions()).isNotEmpty();

        UpdateAnalysisRequirementRequest update = new UpdateAnalysisRequirementRequest();
        update.setCompetitors(List.of("Notion", "Confluence"));
        update.setOutputGoal("产品规划");
        var confirmed = service.updateRequirement(draft.getId(), update);

        assertThat(confirmed.getStatus()).isEqualTo(AnalysisStatus.PENDING);
        assertThat(confirmed.getRequirement().getOutputGoal()).isEqualTo("产品规划");
        assertThat(confirmed.getClarificationDraft().isConfirmed()).isTrue();

        AddAnalysisContextRequest context = new AddAnalysisContextRequest();
        context.setIntent(ContextIntent.ADJUST_SCOPE);
        context.setContent("再加入 Confluence，重点看企业权限和 AI 搜索能力，也补充价格页和公开评价。");
        var withContext = service.addContext(draft.getId(), context);

        assertThat(withContext.getStatus()).isEqualTo(AnalysisStatus.AWAITING_CONFIRMATION);
        assertThat(withContext.getContextMessages()).hasSize(1);
        assertThat(withContext.getRequirement().getCompetitors()).contains("Confluence");
        assertThat(withContext.getRequirement().getDimensions()).contains("权限协作", "AI 搜索", "价格策略", "用户评价");
        assertThat(withContext.getRequirement().getSourcePreferences()).contains("pricing_page", "public_reviews");

        var finished = service.startExecution(draft.getId());

        assertThat(finished.getStatus()).isEqualTo(AnalysisStatus.SUCCEEDED);
        assertThat(finished.getSteps()).isNotEmpty();
    }

    @Test
    void addsUserProvidedEvidenceAsCitableSourceAndChunk() {
        AnalysisWorkflowService service = newService();
        CreateAnalysisRunRequest request = new CreateAnalysisRunRequest();
        request.setPrompt("Analyze Notion and Confluence for AI document collaboration.");
        var run = service.createDraft(request);

        AddUserEvidenceRequest evidenceRequest = new AddUserEvidenceRequest();
        evidenceRequest.setTitle("Internal interview notes");
        evidenceRequest.setSourceType("interview");
        evidenceRequest.setContent("Users praised AI summaries, but asked for stronger enterprise permission governance.");
        evidenceRequest.setSensitive(true);

        var updated = service.addEvidence(run.getId(), evidenceRequest);

        assertThat(updated.getUserProvidedEvidence()).hasSize(1);
        assertThat(updated.getEvidenceSources()).hasSize(1);
        assertThat(updated.getEvidenceSources().get(0).getCitationKey()).isEqualTo("S1");
        assertThat(updated.getEvidenceSources().get(0).getSourceType()).isEqualTo("user_interview");
        assertThat(updated.getEvidenceSources().get(0).getComplianceNote()).contains("internal-only");
        assertThat(updated.getEvidenceChunks()).hasSize(1);
        assertThat(updated.getResearchPackage().getSources()).hasSize(1);
        assertThat(updated.getRecommendedActions()).anyMatch(action -> action.contains("用户证据 S1 已加入"));
    }

    @Test
    void addEvidenceContextCreatesCitableSource() {
        AnalysisWorkflowService service = newService();
        CreateAnalysisRunRequest request = new CreateAnalysisRunRequest();
        request.setPrompt("Analyze Notion and Confluence for AI document collaboration.");
        var run = service.createDraft(request);

        AddAnalysisContextRequest context = new AddAnalysisContextRequest();
        context.setIntent(ContextIntent.ADD_EVIDENCE);
        context.setContent("Interview notes: users want stronger audit trails for AI-generated document changes.");

        var updated = service.addContext(run.getId(), context);

        assertThat(updated.getContextMessages()).hasSize(1);
        assertThat(updated.getUserProvidedEvidence()).hasSize(1);
        assertThat(updated.getEvidenceSources()).hasSize(1);
        assertThat(updated.getEvidenceSources().get(0).getCitationKey()).isEqualTo("S1");
        assertThat(updated.getEvidenceChunks()).hasSize(1);
    }

    @Test
    void cancelsDraftAndBlocksRestart() {
        AnalysisWorkflowService service = newService();
        CreateAnalysisRunRequest request = new CreateAnalysisRunRequest();
        request.setPrompt("Analyze Notion and Confluence for AI document collaboration.");
        var run = service.createDraft(request);

        var cancelled = service.cancel(run.getId());

        assertThat(cancelled.getStatus()).isEqualTo(AnalysisStatus.CANCELLED);
        assertThat(cancelled.getRecommendedActions()).contains("任务已由用户取消。");
        assertThatThrownBy(() -> service.startExecution(run.getId()))
                .isInstanceOf(InvalidRunStateException.class)
                .hasMessageContaining("CANCELLED");
    }

    @Test
    void blocksRequirementChangesAfterRunSucceeded() {
        AnalysisWorkflowService service = newService();
        CreateAnalysisRunRequest request = new CreateAnalysisRunRequest();
        request.setPrompt("Analyze Notion and Confluence for AI document collaboration.");
        var finished = service.start(request);

        UpdateAnalysisRequirementRequest update = new UpdateAnalysisRequirementRequest();
        update.setOutputGoal("Change after completion");

        assertThat(finished.getStatus()).isEqualTo(AnalysisStatus.SUCCEEDED);
        assertThatThrownBy(() -> service.updateRequirement(finished.getId(), update))
                .isInstanceOf(InvalidRunStateException.class)
                .hasMessageContaining("SUCCEEDED");
    }

    @Test
    void executesFullWorkflowAndProducesFinalReport() {
        AnalysisWorkflowService service = newService();
        CreateAnalysisRunRequest request = new CreateAnalysisRunRequest();
        request.setPrompt("Analyze Notion and Confluence for AI document collaboration.");

        var run = service.start(request);
        var finished = service.get(run.getId());

        assertThat(finished.getStatus()).isEqualTo(AnalysisStatus.SUCCEEDED);
        assertThat(finished.getSteps()).hasSizeGreaterThanOrEqualTo(7);
        assertThat(finished.getTraces()).hasSize(finished.getSteps().size());
        assertThat(finished.getTraces()).allSatisfy(trace -> {
            assertThat(trace.getStepId()).isNotNull();
            assertThat(trace.getStatus()).isEqualTo(StepStatus.SUCCEEDED);
            assertThat(trace.getStartedAt()).isNotNull();
            assertThat(trace.getCompletedAt()).isNotNull();
            assertThat(trace.getLatencyMs()).isNotNull();
            assertThat(trace.getInputSnapshot()).isNotBlank();
            assertThat(trace.getOutputSnapshot()).isNotBlank();
        });
        assertThat(finished.getTraces())
                .filteredOn(trace -> trace.getAgentName() == AgentName.WRITER)
                .allSatisfy(trace -> {
                    assertThat(trace.getFallbackUsed()).isTrue();
                    assertThat(trace.getModelName()).isEqualTo("deterministic-writer-fallback");
                    assertThat(trace.getRawModelOutput()).isNotBlank();
                    assertThat(trace.getCompletionTokens()).isPositive();
                    assertThat(trace.getTotalTokens()).isEqualTo(trace.getCompletionTokens());
                });
        assertThat(finished.getTraces())
                .filteredOn(trace -> trace.getAgentName() == AgentName.REVIEWER)
                .allSatisfy(trace -> {
                    assertThat(trace.getFallbackUsed()).isTrue();
                    assertThat(trace.getModelName()).isEqualTo("deterministic-reviewer-fallback");
                    assertThat(trace.getRawModelOutput()).isNotBlank();
                    assertThat(trace.getCompletionTokens()).isNotNegative();
                    assertThat(trace.getTotalTokens()).isEqualTo(trace.getCompletionTokens());
                });
        assertThat(finished.getWorkflowTransitions()).isNotEmpty();
        assertThat(finished.getWorkflowTransitions().get(finished.getWorkflowTransitions().size() - 1).getRoute()).isEqualTo("finish");
        assertThat(finished.getWorkflowTransitions().get(finished.getWorkflowTransitions().size() - 1).getTargetNode()).isEqualTo(AgentName.REVISION.name());
        assertThat(finished.getSteps())
                .filteredOn(step -> step.getAgentName() == AgentName.RESEARCHER)
                .isNotEmpty();
        assertThat(finished.getSteps())
                .filteredOn(step -> step.getAgentName() == AgentName.REVIEWER)
                .isNotEmpty();
        assertThat(finished.getEvidenceSources()).hasSizeGreaterThanOrEqualTo(4);
        assertThat(finished.getEvidenceChunks()).hasSizeGreaterThanOrEqualTo(4);
        assertThat(service.retrieveEvidence(finished.getId(), "Notion", 3)).isNotEmpty();
        assertThat(finished.getResearchPackage().getSources()).hasSizeGreaterThanOrEqualTo(4);
        assertThat(finished.getResearchPackage().getMissingEvidenceTypes()).contains("survey_result", "interview_note");
        assertThat(finished.getResearchPackage().getResearchPlan().getQuestionnaire().getQuestions()).isNotEmpty();
        assertThat(finished.getResearchPackage().getResearchPlan().getInterviewGuide().getQuestions()).isNotEmpty();
        assertThat(finished.getResearchPackage().getResearchPlan().getPublicSourceTasks()).isNotEmpty();
        assertThat(finished.getResearchPackage().getResearchPlan().getSearchQueries()).isNotEmpty();
        assertThat(finished.getCompetitorProfiles()).hasSize(2);
        assertThat(finished.getCompetitorProfiles())
                .allSatisfy(profile -> {
                    assertThat(profile.getFeatureTree().getRoots()).isNotEmpty();
                    assertThat(profile.getPricingModel().getEvidenceIds()).isNotEmpty();
                    assertThat(profile.getPricingModel().getPlans()).isNotEmpty();
                    assertThat(profile.getPersonas()).isNotEmpty();
                    assertThat(profile.getEvidenceIds()).isNotEmpty();
                });
        assertThat(finished.getClaims()).hasSizeGreaterThanOrEqualTo(3);
        assertThat(finished.getClaims())
                .extracting(claim -> claim.getType())
                .contains(ClaimType.COMPARISON, ClaimType.OPPORTUNITY);
        assertThat(finished.getClaims()).allSatisfy(claim -> {
            if (claim.getEvidenceIds().isEmpty()) {
                assertThat(claim.getContent()).containsAnyOf("待验证", "证据不足");
            } else {
                assertThat(claim.getEvidenceIds()).isNotEmpty();
            }
        });
        assertThat(finished.getReviewDecision().getAction()).isEqualTo(ReviewAction.PASS);
        assertThat(finished.getArtifacts())
                .filteredOn(artifact -> artifact.getType() == ArtifactType.REVIEW_FINDINGS)
                .isNotEmpty();
        assertThat(finished.getArtifacts())
                .filteredOn(artifact -> artifact.getType() == ArtifactType.REPORT_DRAFT)
                .extracting(artifact -> artifact.getVersion())
                .contains(1);
        assertThat(finished.getArtifacts())
                .filteredOn(artifact -> artifact.getType() == ArtifactType.SOURCE_LIST)
                .extracting(artifact -> artifact.getVersion())
                .contains(1);
        assertThat(finished.getArtifacts()).anyMatch(artifact -> artifact.getType() == ArtifactType.SWOT_ANALYSIS);
        assertThat(finished.getArtifacts()).anyMatch(artifact -> artifact.getType() == ArtifactType.RESEARCH_PLAN);
        assertThat(finished.getArtifacts()).anyMatch(artifact -> artifact.getType() == ArtifactType.FINAL_REPORT);
        assertThat(finished.getReviewFindings())
                .noneMatch(finding -> finding.getSeverity() == com.aiinsight.model.enums.ReviewSeverity.HIGH);
    }

    @Test
    void writerFallbackUsesDynamicAnalystClaims() {
        AnalysisWorkflowService service = newService();
        CreateAnalysisRunRequest request = new CreateAnalysisRunRequest();
        request.setPrompt("分析 Notion 和 Confluence，重点看价格策略、权限治理、AI 搜索和用户评价，输出产品规划建议。");
        request.setCompetitors(List.of("Notion", "Confluence"));
        request.setDimensions(List.of("价格策略", "权限治理", "AI 搜索", "用户评价"));
        request.setOutputGoal("产品规划建议");

        var run = service.start(request);
        var finished = service.get(run.getId());

        assertThat(finished.getArtifacts())
                .filteredOn(artifact -> artifact.getType() == ArtifactType.REPORT_DRAFT)
                .last()
                .satisfies(artifact -> assertThat(artifact.getContent())
                        .contains("结构化结论", "竞品矩阵摘要", "SWOT 摘要", "价格策略", "权限治理", "AI 搜索", "用户评价", "产品规划建议")
                        .doesNotContain("当前竞品普遍围绕协作、知识沉淀、权限管理和 AI 内容生成建设能力"));
    }

    @Test
    void writerFallsBackWhenLlmFailsAndUsesActualCitations() {
        LlmClient failingWriterLlm = new LlmClient() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String complete(com.aiinsight.llm.ChatRequest request) {
                throw new IllegalStateException("simulated writer timeout");
            }
        };
        AnalysisRun run = writerReadyRun();

        new WriterNode(failingWriterLlm, new FallbackReportDraftFactory()).execute(run);

        assertThat(run.getRecommendedActions()).anyMatch(action -> action.contains("LLM 报告生成失败"));
        assertThat(run.getArtifacts())
                .filteredOn(artifact -> artifact.getType() == ArtifactType.REPORT_DRAFT)
                .last()
                .satisfies(artifact -> {
                    assertThat(artifact.getContent()).contains("结构化结论", "竞品矩阵摘要", "SWOT 摘要", "[S1]");
                    assertThat(artifact.getCitationKeys()).containsExactly("S1");
                });
    }

    @Test
    void writerSanitizesUnknownCitationsFromLlmOutput() {
        LlmClient writerLlm = new LlmClient() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String complete(com.aiinsight.llm.ChatRequest request) {
                return """
                        # 报告

                        Notion 的 AI 搜索线索可用 [S1]，但这个引用不存在 [S404]。
                        """;
            }
        };
        AnalysisRun run = writerReadyRun();

        new WriterNode(writerLlm, new FallbackReportDraftFactory()).execute(run);

        assertThat(run.getArtifacts())
                .filteredOn(artifact -> artifact.getType() == ArtifactType.REPORT_DRAFT)
                .last()
                .satisfies(artifact -> {
                    assertThat(artifact.getContent()).contains("[S1]", "证据不足");
                    assertThat(artifact.getContent()).doesNotContain("[S404]");
                    assertThat(artifact.getCitationKeys()).containsExactly("S1");
                });
    }

    @Test
    void writerLlmPromptIncludesStructuredInputs() {
        StringBuilder promptCapture = new StringBuilder();
        LlmClient writerLlm = new LlmClient() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String complete(com.aiinsight.llm.ChatRequest request) {
                promptCapture.append(request.getMessages().get(1).getContent());
                return "结构化结论已经用于报告生成 [S1]";
            }
        };
        AnalysisRun run = writerReadyRun();
        run.getEvidenceChunks().add(new EvidenceChunk(
                "S1-C1",
                "S1",
                1,
                "Notion AI search",
                "https://example.test/notion/ai",
                "Notion AI 搜索支持在团队知识库中查找答案，并保留来源线索。"
        ));
        CompetitorProfile profile = new CompetitorProfile();
        profile.setProductName("Notion");
        profile.setPositioning("AI 知识协作工具");
        profile.setStrengths(List.of("AI 搜索"));
        profile.setWeaknesses(List.of("权限审计待验证"));
        profile.setEvidenceIds(List.of("S1"));
        run.getCompetitorProfiles().add(profile);

        new WriterNode(writerLlm, new FallbackReportDraftFactory()).execute(run);

        assertThat(promptCapture.toString())
                .contains("结构化结论:", "竞品画像 Schema:", "采集包缺口与一手洞察:")
                .contains("Notion 的 AI 搜索能力可作为产品规划参考。", "AI 知识协作工具", "证据缺口", "相关证据切片", "S1-C1");
    }

    @Test
    void analystGeneratesClaimsFromRequirementDimensionsAndEvidence() {
        AnalysisWorkflowService service = newService();
        CreateAnalysisRunRequest request = new CreateAnalysisRunRequest();
        request.setPrompt("分析 Notion 和 Confluence，重点看价格策略、权限治理、AI 搜索和用户评价，输出产品规划建议。");
        request.setCompetitors(List.of("Notion", "Confluence"));
        request.setDimensions(List.of("价格策略", "权限治理", "AI 搜索", "用户评价"));
        request.setOutputGoal("产品规划建议");

        var run = service.start(request);
        var finished = service.get(run.getId());

        assertThat(finished.getClaims()).hasSizeGreaterThanOrEqualTo(5);
        assertThat(finished.getClaims())
                .extracting(AnalysisClaim::getContent)
                .anyMatch(content -> content.contains("价格策略"))
                .anyMatch(content -> content.contains("权限治理"))
                .anyMatch(content -> content.contains("AI 搜索"))
                .anyMatch(content -> content.contains("用户评价"))
                .anyMatch(content -> content.contains("产品规划建议"));
        assertThat(finished.getClaims())
                .extracting(AnalysisClaim::getType)
                .contains(ClaimType.COMPARISON, ClaimType.OPPORTUNITY, ClaimType.RISK);
        assertThat(finished.getArtifacts())
                .filteredOn(artifact -> artifact.getType() == ArtifactType.COMPETITIVE_MATRIX)
                .last()
                .satisfies(artifact -> assertThat(artifact.getContent()).contains("结构化结论", "价格策略", "权限治理", "AI 搜索", "用户评价"));
    }

    @Test
    void analystUsesStructuredLlmClaimsWhenAvailable() {
        StringBuilder promptCapture = new StringBuilder();
        LlmClient structuredLlm = new LlmClient() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String complete(com.aiinsight.llm.ChatRequest request) {
                promptCapture.append(request.getMessages().get(1).getContent());
                return """
                        {
                          "claims": [
                            {
                              "type": "RECOMMENDATION",
                              "content": "应优先围绕权限审计和 AI 搜索做差异化产品规划。",
                              "confidence": "HIGH",
                              "competitorNames": ["Notion", "Confluence"],
                              "evidenceIds": ["S1", "S404"]
                            },
                            {
                              "type": "RISK",
                              "content": "用户评价样本不足，满意度判断仍需待验证。",
                              "confidence": "MEDIUM",
                              "competitorNames": ["Notion", "Confluence"],
                              "evidenceIds": []
                            }
                          ],
                          "competitiveMatrixMarkdown": "| 维度 | 竞品 | 判断 | 证据 |\\n| --- | --- | --- | --- |\\n| 权限审计 | Notion/Confluence | 企业治理是差异化重点 | [S1] |",
                          "swotMarkdown": "| 维度 | 结论 | 证据 |\\n| --- | --- | --- |\\n| Opportunities 机会 | 权限审计和 AI 搜索可进入产品路线图 | [S1] |"
                        }
                        """;
            }
        };
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "分析 Notion 和 Confluence，重点看权限审计、AI 搜索和用户评价。",
                "协作文档",
                List.of("Notion", "Confluence"),
                List.of("权限审计", "AI 搜索", "用户评价"),
                List.of("official_site"),
                List.of()
        ));
        run.getEvidenceSources().add(new EvidenceSource(
                "S1",
                "Notion permission audit",
                "https://example.test/notion",
                "official_site",
                "FETCHED",
                "LIVE_FETCHED",
                "permission audit and AI search details",
                "permission audit and AI search details",
                "test evidence"
        ));
        run.getEvidenceChunks().add(new EvidenceChunk(
                "S1-C1",
                "S1",
                1,
                "Notion permission audit",
                "https://example.test/notion",
                "Notion permission audit and AI search details support enterprise governance planning."
        ));
        CompetitorProfile profile = new CompetitorProfile();
        profile.setProductName("Notion");
        profile.setEvidenceIds(List.of("S1"));
        run.getCompetitorProfiles().add(profile);

        new AnalystNode(structuredLlm, new ObjectMapper(), new FallbackAnalysisDraftFactory()).execute(run);

        assertThat(run.getClaims()).hasSize(2);
        assertThat(run.getClaims().get(0).getType()).isEqualTo(ClaimType.RECOMMENDATION);
        assertThat(run.getClaims().get(0).getEvidenceIds()).containsExactly("S1");
        assertThat(run.getClaims().get(1).getConfidence()).isEqualTo(com.aiinsight.model.enums.ConfidenceLevel.LOW);
        assertThat(run.getClaims().get(1).getContent()).contains("待验证");
        assertThat(run.getArtifacts())
                .filteredOn(artifact -> artifact.getType() == ArtifactType.COMPETITIVE_MATRIX)
                .last()
                .satisfies(artifact -> {
                    assertThat(artifact.getContent()).contains("权限审计");
                    assertThat(artifact.getCitationKeys()).containsExactly("S1");
                });
        assertThat(promptCapture.toString()).contains("相关证据切片", "S1-C1", "enterprise governance");
    }

    @Test
    void analystFallsBackWhenStructuredLlmFails() {
        LlmClient failingLlm = new LlmClient() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String complete(com.aiinsight.llm.ChatRequest request) {
                throw new IllegalStateException("simulated analyst timeout");
            }
        };
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "分析 Notion 和 Confluence 的价格策略和 AI 搜索。",
                "协作文档",
                List.of("Notion", "Confluence"),
                List.of("价格策略", "AI 搜索"),
                List.of("pricing_page"),
                List.of(),
                "产品规划"
        ));
        run.getEvidenceSources().add(new EvidenceSource(
                "S1",
                "Notion pricing",
                "https://example.test/notion/pricing",
                "pricing_page",
                "FETCHED",
                "LIVE_FETCHED",
                "pricing plan and AI search details",
                "pricing plan and AI search details",
                "test evidence"
        ));
        CompetitorProfile profile = new CompetitorProfile();
        profile.setProductName("Notion");
        profile.setEvidenceIds(List.of("S1"));
        run.getCompetitorProfiles().add(profile);

        new AnalystNode(failingLlm, new ObjectMapper(), new FallbackAnalysisDraftFactory()).execute(run);

        assertThat(run.getRecommendedActions()).anyMatch(action -> action.contains("LLM 分析生成失败"));
        assertThat(run.getClaims()).isNotEmpty();
        assertThat(run.getClaims()).extracting(AnalysisClaim::getContent)
                .anyMatch(content -> content.contains("价格策略"))
                .anyMatch(content -> content.contains("AI 搜索"));
    }

    @Test
    void analystSanitizesUnknownCitationsFromLlmArtifacts() {
        LlmClient structuredLlm = new LlmClient() {
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
                              "type": "COMPARISON",
                              "content": "Notion 在 AI 搜索方向有可验证线索。",
                              "confidence": "MEDIUM",
                              "competitorNames": ["Notion"],
                              "evidenceIds": ["S1"]
                            }
                          ],
                          "competitiveMatrixMarkdown": "| 维度 | 竞品 | 判断 | 证据 |\\n| --- | --- | --- | --- |\\n| AI 搜索 | Notion | 有可验证线索，也有未知引用 | [S1] [S404] |",
                          "swotMarkdown": "| 维度 | 结论 | 证据 |\\n| --- | --- | --- |\\n| Threats 威胁 | 错误引用应被清理 | [S404] |"
                        }
                        """;
            }
        };
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "分析 Notion 的 AI 搜索。",
                "协作文档",
                List.of("Notion"),
                List.of("AI 搜索"),
                List.of("official_site"),
                List.of()
        ));
        run.getEvidenceSources().add(new EvidenceSource(
                "S1",
                "Notion AI search",
                "https://example.test/notion/ai",
                "official_site",
                "FETCHED",
                "LIVE_FETCHED",
                "AI search details",
                "AI search details",
                "test evidence"
        ));

        new AnalystNode(structuredLlm, new ObjectMapper(), new FallbackAnalysisDraftFactory()).execute(run);

        assertThat(run.getArtifacts())
                .filteredOn(artifact -> artifact.getType() == ArtifactType.COMPETITIVE_MATRIX)
                .last()
                .satisfies(artifact -> {
                    assertThat(artifact.getContent()).contains("[S1]", "证据不足");
                    assertThat(artifact.getContent()).doesNotContain("[S404]");
                    assertThat(artifact.getCitationKeys()).containsExactly("S1");
                });
        assertThat(run.getArtifacts())
                .filteredOn(artifact -> artifact.getType() == ArtifactType.SWOT_ANALYSIS)
                .last()
                .satisfies(artifact -> {
                    assertThat(artifact.getContent()).contains("证据不足");
                    assertThat(artifact.getContent()).doesNotContain("[S404]");
                    assertThat(artifact.getCitationKeys()).containsExactly("S1");
                });
    }

    @Test
    void researcherBuildsSurveyFromRequestedDomainAndDimensions() {
        AnalysisWorkflowService service = newService();
        CreateAnalysisRunRequest request = new CreateAnalysisRunRequest();
        request.setPrompt("分析 Salesforce 和 HubSpot 在 CRM 销售自动化方向的竞品机会。");
        request.setIndustry("企业服务 CRM");
        request.setCompetitors(List.of("Salesforce", "HubSpot"));
        request.setDimensions(List.of("线索管理", "价格策略", "集成能力"));

        var run = service.start(request);
        var questionnaire = service.get(run.getId()).getResearchPackage().getResearchPlan().getQuestionnaire();
        var interviewGuide = service.get(run.getId()).getResearchPackage().getResearchPlan().getInterviewGuide();

        assertThat(questionnaire.getTitle()).contains("企业服务 CRM");
        assertThat(questionnaire.getTargetRespondents()).contains("Salesforce", "HubSpot");
        assertThat(questionnaire.getQuestions())
                .extracting(question -> question.getDimension())
                .contains("线索管理", "价格策略", "集成能力");
        assertThat(questionnaire.getQuestions())
                .flatExtracting(question -> question.getOptions())
                .contains("线索管理", "客户跟进", "销售预测");
        assertThat(interviewGuide.getQuestions()).anyMatch(question -> question.contains("Salesforce、HubSpot"));
    }

    @Test
    void researcherUsesLlmGeneratedResearchPlanWhenAvailable() {
        LlmClient researchPlanLlm = new LlmClient() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String complete(com.aiinsight.llm.ChatRequest request) {
                return """
                        {
                          "objective": "验证 CRM 竞品在销售流程中的真实使用差异",
                          "evidenceGaps": ["sales_ops_interview"],
                          "searchQueries": ["Salesforce HubSpot sales workflow comparison", "HubSpot CRM onboarding pain points"],
                          "publicSourceTasks": [
                            {"type": "public_review", "target": "Salesforce 与 HubSpot 销售运营评价", "rationale": "补充真实用户反馈", "status": "needs_collection"}
                          ],
                          "questionnaire": {
                            "title": "CRM 销售运营竞品决策问卷",
                            "targetRespondents": "评估过 Salesforce 或 HubSpot 的销售运营、销售主管和 CRM 管理员",
                            "recommendedSampleSize": "20-40 份，覆盖销售运营、销售主管和管理员",
                            "questions": [
                              {"dimension": "线索管理效率", "question": "在 Salesforce 或 HubSpot 中完成线索分配和跟进提醒的效率如何？", "options": ["明显提升", "基本满足", "流程偏重", "需要人工补充"]},
                              {"dimension": "销售预测可信度", "question": "你是否信任系统给出的销售预测或阶段判断？", "options": ["非常信任", "部分信任", "不信任", "未使用"]},
                              {"dimension": "采购顾虑", "question": "采购或续费 CRM 时最主要的阻力是什么？", "options": ["价格", "迁移成本", "集成成本", "团队学习成本"]}
                            ]
                          },
                          "interviewGuide": {
                            "title": "CRM 销售运营用户访谈提纲",
                            "targetRoles": ["销售运营", "销售主管", "CRM 管理员"],
                            "questions": ["最近一次使用 Salesforce 或 HubSpot 跟进线索的流程是什么？", "哪个环节最影响销售团队效率？", "如果切换竞品，最大的阻力是什么？"],
                            "probingQuestions": ["能否举一个具体商机或客户跟进案例？", "这个问题每周出现几次？", "你愿意为哪些能力付费？"]
                          }
                        }
                        """;
            }
        };
        SourceCollectionService sourceCollectionService = new SourceCollectionService(fetchAlwaysFails(), fakeSearchProvider());
        ResearcherNode researcherNode = researcherNode(sourceCollectionService, researchPlanLlm);
        var run = new com.aiinsight.model.run.AnalysisRun(new com.aiinsight.model.run.AnalysisRequirement(
                "分析 Salesforce 和 HubSpot 在 CRM 销售自动化方向的竞品机会。",
                "企业服务 CRM",
                List.of("Salesforce", "HubSpot"),
                List.of("线索管理效率", "销售预测可信度", "采购顾虑"),
                List.of("public_reviews", "interview"),
                List.of()
        ));

        researcherNode.execute(run);

        var questionnaire = run.getResearchPackage().getResearchPlan().getQuestionnaire();
        assertThat(questionnaire.getTitle()).isEqualTo("CRM 销售运营竞品决策问卷");
        assertThat(questionnaire.getQuestions())
                .extracting(question -> question.getDimension())
                .containsExactly("线索管理效率", "销售预测可信度", "采购顾虑");
        assertThat(run.getResearchPackage().getResearchPlan().getInterviewGuide().getTargetRoles())
                .contains("销售运营", "销售主管", "CRM 管理员");
        assertThat(run.getResearchPackage().getResearchPlan().getSearchQueries())
                .contains("Salesforce HubSpot sales workflow comparison");
    }

    @Test
    void researcherKeepsUnresolvedEvidenceGapsAfterRecollection() {
        SourceCollectionService sourceCollectionService = new SourceCollectionService(fetchAlwaysFails(), new NoopSearchProvider());
        ResearcherNode researcherNode = researcherNode(sourceCollectionService, noopLlmClient());
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "分析 Salesforce 和 HubSpot 的价格策略、用户评价和访谈反馈。",
                "企业服务 CRM",
                List.of("Salesforce", "HubSpot"),
                List.of("价格策略", "用户评价", "访谈反馈"),
                List.of("pricing_page", "public_reviews", "survey", "interview"),
                List.of()
        ));
        run.getReviewDecision().setAction(ReviewAction.RECOLLECT_EVIDENCE);
        run.getReviewDecision().setTargetAgent(AgentName.RESEARCHER);

        researcherNode.execute(run);

        assertThat(run.getResearchPackage().getMissingEvidenceTypes())
                .contains("pricing_page", "user_review", "survey_result", "interview_note");
    }

    @Test
    void extractorUsesCompetitorMatchedEvidenceAndRequestedDimensions() {
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "分析 Salesforce 和 HubSpot 在 CRM 销售自动化方向的机会。",
                "企业服务 CRM",
                List.of("Salesforce", "HubSpot"),
                List.of("线索管理", "销售预测", "客户支持"),
                List.of("official_site"),
                List.of()
        ));
        run.getEvidenceSources().add(new EvidenceSource(
                "S1",
                "Salesforce pricing and sales automation",
                "https://example.test/salesforce/pricing",
                "search_result_snippet",
                "FETCH_FAILED",
                "SEARCH_RESULT_SNIPPET",
                "Salesforce pricing plans and sales automation features.",
                "Salesforce pricing plans and sales automation features.",
                "test evidence"
        ));

        new ExtractorNode(noopLlmClient(), new FallbackExtractionFactory()).execute(run);

        var salesforce = run.getCompetitorProfiles().stream()
                .filter(profile -> profile.getProductName().equals("Salesforce"))
                .findFirst()
                .orElseThrow();
        var hubspot = run.getCompetitorProfiles().stream()
                .filter(profile -> profile.getProductName().equals("HubSpot"))
                .findFirst()
                .orElseThrow();
        assertThat(salesforce.getEvidenceIds()).containsExactly("S1");
        assertThat(hubspot.getEvidenceIds()).isEmpty();
        assertThat(salesforce.getPositioning()).contains("企业服务 CRM");
        assertThat(salesforce.getFeatureTree().getRoots())
                .extracting(node -> node.getName())
                .containsExactly("线索管理", "销售预测", "客户支持");
        assertThat(run.getArtifacts())
                .filteredOn(artifact -> artifact.getType() == ArtifactType.COMPETITOR_PROFILE)
                .last()
                .satisfies(artifact -> assertThat(artifact.getContent()).doesNotContain("AI 协作与知识沉淀工具"));
    }

    @Test
    void extractorFallsBackWhenLlmReturnsEmptyMessage() {
        LlmClient failingExtractorLlm = new LlmClient() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String complete(com.aiinsight.llm.ChatRequest request) {
                throw new IllegalStateException("Spring AI returned an empty chat message");
            }
        };
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "分析 Salesforce 在 CRM 销售自动化方向的机会。",
                "企业服务 CRM",
                List.of("Salesforce"),
                List.of("线索管理", "销售预测"),
                List.of("official_site"),
                List.of()
        ));
        run.getEvidenceSources().add(new EvidenceSource(
                "S1",
                "Salesforce sales automation",
                "https://example.test/salesforce/sales",
                "official_site",
                "FETCHED",
                "LIVE_FETCHED",
                "Salesforce sales automation includes lead management and forecasting.",
                "Salesforce sales automation includes lead management and forecasting.",
                "test evidence"
        ));

        new ExtractorNode(failingExtractorLlm, new FallbackExtractionFactory()).execute(run);

        assertThat(run.getRecommendedActions())
                .anyMatch(action -> action.contains("LLM Schema 抽取失败")
                        && action.contains("Spring AI returned an empty chat message"));
        assertThat(run.getCompetitorProfiles()).hasSize(1);
        assertThat(run.getCompetitorProfiles().get(0).getProductName()).isEqualTo("Salesforce");
        assertThat(run.getArtifacts())
                .filteredOn(artifact -> artifact.getType() == ArtifactType.COMPETITOR_PROFILE)
                .last()
                .satisfies(artifact -> {
                    assertThat(artifact.getContent()).contains("Salesforce sales automation", "需结合原始资料继续验证", "[S1]");
                    assertThat(artifact.getCitationKeys()).containsExactly("S1");
                });
    }

    @Test
    void researcherTurnsInterviewEvidenceIntoPersonaSignals() {
        AnalysisWorkflowService service = newService();
        CreateAnalysisRunRequest request = new CreateAnalysisRunRequest();
        request.setPrompt("分析 Salesforce 和 HubSpot 在 CRM 销售自动化方向的竞品机会。");
        request.setIndustry("企业服务 CRM");
        request.setCompetitors(List.of("Salesforce", "HubSpot"));
        request.setDimensions(List.of("权限治理", "价格策略", "用户体验"));
        var run = service.createDraft(request);

        AddUserEvidenceRequest evidenceRequest = new AddUserEvidenceRequest();
        evidenceRequest.setTitle("Salesforce 用户访谈");
        evidenceRequest.setSourceType("interview");
        evidenceRequest.setContent("受访者是销售运营管理员。最近在 Salesforce 做线索管理和客户跟进，觉得权限配置复杂，学习成本高，也担心价格和 HubSpot 集成成本。她认可销售预测提效，但不满意审批流程慢。");

        service.addEvidence(run.getId(), evidenceRequest);
        var finished = service.startExecution(run.getId());

        assertThat(finished.getResearchPackage().getInterviewInsights()).hasSize(1);
        var insight = finished.getResearchPackage().getInterviewInsights().get(0);
        assertThat(insight.getEvidenceId()).isEqualTo("S1");
        assertThat(insight.getIntervieweeRole()).contains("销售");
        assertThat(insight.getCompetitorMentions()).contains("Salesforce", "HubSpot");
        assertThat(insight.getPainPoints()).anyMatch(point -> point.contains("权限配置复杂"));
        assertThat(insight.getBuyingConcerns()).contains("价格/预算", "学习成本", "集成成本");
        assertThat(finished.getCompetitorProfiles())
                .flatExtracting(profile -> profile.getPersonas())
                .allSatisfy(persona -> {
                    assertThat(persona.getPainPoints()).anyMatch(point -> point.contains("权限配置复杂"));
                    assertThat(persona.getBuyingConcerns()).contains("价格/预算");
                    assertThat(persona.getEvidenceIds()).contains("S1");
                });
    }

    @Test
    void rerunAgentAppendsNextArtifactVersion() {
        AnalysisWorkflowService service = newService();
        CreateAnalysisRunRequest request = new CreateAnalysisRunRequest();
        request.setPrompt("Analyze Notion and Confluence for AI document collaboration.");

        var run = service.start(request);
        var rerun = service.rerunAgent(run.getId(), AgentName.WRITER);

        assertThat(rerun.getArtifacts())
                .filteredOn(artifact -> artifact.getType() == ArtifactType.REPORT_DRAFT)
                .extracting(artifact -> artifact.getVersion())
                .containsExactly(1, 2);
    }

    @Test
    void reviewerFindingsCarryArtifactAndClaimLocation() {
        LlmClient noopLlmClient = new LlmClient() {
            @Override
            public boolean isAvailable() {
                return false;
            }

            @Override
            public String complete(com.aiinsight.llm.ChatRequest request) {
                throw new IllegalStateException("LLM is not configured");
            }
        };
        AnalysisRun run = new AnalysisRun();
        AnalysisClaim claim = new AnalysisClaim();
        claim.setType(ClaimType.OPPORTUNITY);
        run.getClaims().add(claim);
        AnalysisArtifact draft = run.addArtifact(new AnalysisArtifact(
                ArtifactType.REPORT_DRAFT,
                "draft",
                "机会点是构建可复核的 Agent 工作流。",
                List.of()
        ));

        new ReviewerNode(new CitationCoverageEvaluator(), noopLlmClient, new FallbackReviewReportFactory()).execute(run);

        assertThat(run.getReviewFindings()).hasSize(1);
        assertThat(run.getReviewFindings().get(0).getArtifactId()).isEqualTo(draft.getId());
        assertThat(run.getReviewFindings().get(0).getClaimId()).isEqualTo(claim.getId());
        assertThat(run.getReviewFindings().get(0).getExcerpt()).contains("机会点");
    }

    @Test
    void reviewerRoutesEvidenceGapsBackToResearcher() {
        AnalysisRun run = new AnalysisRun();
        run.getResearchPackage().getMissingEvidenceTypes().add("pricing_page");
        run.addArtifact(new AnalysisArtifact(
                ArtifactType.REPORT_DRAFT,
                "draft",
                "机会点是优化企业版价格策略。",
                List.of()
        ));

        new ReviewerNode(new CitationCoverageEvaluator(), noopLlmClient(), new FallbackReviewReportFactory()).execute(run);

        assertThat(run.getReviewDecision().getAction()).isEqualTo(ReviewAction.RECOLLECT_EVIDENCE);
        assertThat(run.getReviewDecision().getTargetAgent()).isEqualTo(AgentName.RESEARCHER);
        assertThat(run.getReviewDecision().getRequiredEvidenceTypes()).containsExactly("pricing_page");
        assertThat(run.getReviewDecision().getReason()).contains("citation_missing", "pricing_page");
    }

    @Test
    void reviewerRoutesStructuredClaimProblemsBackToAnalyst() {
        AnalysisRun run = new AnalysisRun();
        run.getEvidenceSources().add(new EvidenceSource(
                "S1",
                "Pricing page",
                "https://example.test/pricing",
                "pricing_page",
                "FETCHED",
                "LIVE_FETCHED",
                "价格策略和套餐比较信息。",
                "价格策略和套餐比较信息。",
                "test evidence"
        ));
        run.getEvidenceChunks().add(new EvidenceChunk(
                "S1-C1",
                "S1",
                1,
                "Pricing page",
                "https://example.test/pricing",
                "价格策略 套餐 比较"
        ));
        AnalysisClaim claim = new AnalysisClaim();
        claim.setType(ClaimType.OPPORTUNITY);
        claim.setContent("Notion 在企业权限治理上形成明显优势。");
        run.getClaims().add(claim);
        run.addArtifact(new AnalysisArtifact(
                ArtifactType.REPORT_DRAFT,
                "draft",
                "机会点是优化价格策略和套餐比较 [S1]。",
                List.of("S1")
        ));

        new ReviewerNode(new CitationCoverageEvaluator(), noopLlmClient(), new FallbackReviewReportFactory()).execute(run);

        assertThat(run.getReviewDecision().getAction()).isEqualTo(ReviewAction.REWORK_ANALYSIS);
        assertThat(run.getReviewDecision().getTargetAgent()).isEqualTo(AgentName.ANALYST);
        assertThat(run.getReviewDecision().getAffectedClaimIds()).contains(claim.getId());
        assertThat(run.getReviewDecision().getReason()).contains("claim_missing_evidence", "Analyst");
    }

    @Test
    void reviewerMergesStructuredLlmFindingsIntoDecision() {
        StringBuilder promptCapture = new StringBuilder();
        LlmClient reviewerLlm = new LlmClient() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String complete(com.aiinsight.llm.ChatRequest request) {
                promptCapture.append(request.getMessages().get(1).getContent());
                return """
                        {
                          "summary": "发现一个语义层面的高风险过度推断。",
                          "findings": [
                            {
                              "severity": "HIGH",
                              "category": "llm_overclaim",
                              "message": "报告把价格页证据推断成明确增长结论，存在过度推断。",
                              "recommendation": "将增长判断降级为待验证假设，并补充客户案例或用户评价。",
                              "claimId": "C-LLM-1",
                              "citationKey": "S1",
                              "paragraphIndex": 1,
                              "excerpt": "价格策略会带来明显增长"
                            }
                          ]
                        }
                        """;
            }
        };
        AnalysisRun run = new AnalysisRun();
        AnalysisClaim claim = new AnalysisClaim();
        claim.setId("C-LLM-1");
        claim.setType(ClaimType.OPPORTUNITY);
        claim.setContent("价格策略和套餐比较是可验证机会。");
        claim.setEvidenceIds(List.of("S1"));
        run.getClaims().add(claim);
        run.getEvidenceSources().add(new EvidenceSource(
                "S1",
                "Pricing page",
                "https://example.test/pricing",
                "pricing_page",
                "FETCHED",
                "LIVE_FETCHED",
                "价格策略和套餐比较信息。",
                "价格策略和套餐比较信息。",
                "test evidence"
        ));
        run.getEvidenceChunks().add(new EvidenceChunk(
                "S1-C1",
                "S1",
                1,
                "Pricing page",
                "https://example.test/pricing",
                "价格策略 套餐 比较"
        ));
        run.addArtifact(new AnalysisArtifact(
                ArtifactType.REPORT_DRAFT,
                "draft",
                "机会点是优化价格策略和套餐比较 [S1]。",
                List.of("S1")
        ));

        new ReviewerNode(new CitationCoverageEvaluator(), reviewerLlm, new FallbackReviewReportFactory()).execute(run);

        assertThat(promptCapture.toString()).contains("只输出可解析 JSON", "结构化 Claims:", "C-LLM-1");
        assertThat(run.getReviewFindings())
                .anySatisfy(finding -> {
                    assertThat(finding.getCategory()).isEqualTo("llm_overclaim");
                    assertThat(finding.getClaimId()).isEqualTo("C-LLM-1");
                    assertThat(finding.getCitationKey()).isEqualTo("S1");
                });
        assertThat(run.getReviewDecision().getAction()).isEqualTo(ReviewAction.REVISE_REPORT);
        assertThat(run.getArtifacts())
                .filteredOn(artifact -> artifact.getType() == ArtifactType.REVIEW_FINDINGS)
                .last()
                .satisfies(artifact -> assertThat(artifact.getContent()).contains("结构化新增问题：1", "llm_overclaim"));
    }

    @Test
    void revisionSummarizesReviewerDecisionAndFindingsInFinalReport() {
        AnalysisRun run = new AnalysisRun();
        run.addArtifact(new AnalysisArtifact(
                ArtifactType.REPORT_DRAFT,
                "draft",
                "# 报告草稿\n\n机会点是优化价格策略 [S1]。",
                List.of("S1")
        ));
        ReviewFinding finding = new ReviewFinding(
                ReviewSeverity.HIGH,
                "claim_missing_evidence",
                "结构化结论未绑定证据。",
                "补充 evidenceIds 或降级为待验证。"
        );
        finding.setClaimId("C-1");
        finding.setCitationKey("S1");
        run.getReviewFindings().add(finding);
        run.getReviewDecision().setAction(ReviewAction.REWORK_ANALYSIS);
        run.getReviewDecision().setTargetAgent(AgentName.ANALYST);
        run.getReviewDecision().setAffectedClaimIds(List.of("C-1"));
        run.getReviewDecision().setRequiredEvidenceTypes(List.of("pricing_page"));

        new RevisionNode().execute(run);

        assertThat(run.getArtifacts())
                .filteredOn(artifact -> artifact.getType() == ArtifactType.FINAL_REPORT)
                .last()
                .satisfies(artifact -> assertThat(artifact.getContent())
                        .contains("Reviewer 当前决策为 `REWORK_ANALYSIS`")
                        .contains("claim_missing_evidence")
                        .contains("需重点复核 Claim：C-1")
                        .contains("优先补充证据类型：pricing_page"));
        assertThat(run.getRecommendedActions())
                .anyMatch(action -> action.contains("HIGH 质检项"));
    }

    private LlmClient noopLlmClient() {
        return new LlmClient() {
            @Override
            public boolean isAvailable() {
                return false;
            }

            @Override
            public String complete(com.aiinsight.llm.ChatRequest request) {
                throw new IllegalStateException("LLM is not configured");
            }
        };
    }

    private AnalysisWorkflowService newService() {
        LlmClient noopLlmClient = new LlmClient() {
            @Override
            public boolean isAvailable() {
                return false;
            }

            @Override
            public String complete(com.aiinsight.llm.ChatRequest request) {
                throw new IllegalStateException("LLM is not configured");
            }
        };
        AnalysisRunRepository repository = new TestAnalysisRunRepository();
        AnalysisEventBroker eventBroker = new AnalysisEventBroker();
        WorkflowNodeExecutor nodeExecutor = new WorkflowNodeExecutor(repository, eventBroker);
        SourceCollectionService sourceCollectionService = new SourceCollectionService(fetchAlwaysFails(), fakeSearchProvider());
        AnalysisLangGraphWorkflow graphWorkflow = new AnalysisLangGraphWorkflow(
                List.of(
                        new RevisionNode(),
                        new WriterNode(noopLlmClient, new FallbackReportDraftFactory()),
                        new ReviewerNode(new CitationCoverageEvaluator(), noopLlmClient, new FallbackReviewReportFactory()),
                        new AnalystNode(noopLlmClient, new ObjectMapper(), new FallbackAnalysisDraftFactory()),
                        new ExtractorNode(noopLlmClient, new FallbackExtractionFactory()),
                        researcherNode(sourceCollectionService, noopLlmClient),
                        new ClarifierNode(noopLlmClient)
                ),
                nodeExecutor,
                repository,
                eventBroker
        );
        assertThat(graphWorkflow.mermaid()).contains("REVIEW_GATE");
        return new AnalysisWorkflowService(
                repository,
                new AnalysisRequestNormalizer(),
                eventBroker,
                new TaskExecutorAdapter(Runnable::run),
                graphWorkflow,
                new EvidenceRetrievalService(),
                sourceCollectionService,
                new EvidenceChunkService()
        );
    }

    private WebPageFetchService fetchAlwaysFails() {
        return new WebPageFetchService() {
            @Override
            public FetchedPage fetch(String url) {
                return FetchedPage.failed(url, "simulated fetch failure");
            }
        };
    }

    private SearchProvider fakeSearchProvider() {
        return new SearchProvider() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public List<SearchResult> search(String query, int count) {
                return List.of(new SearchResult(
                        "Search result for " + query,
                        "https://search.example.test/" + query.toLowerCase().replaceAll("[^a-z0-9]+", "-"),
                        "Snippet for " + query + " with pricing, reviews, AI collaboration and permission details.",
                        query,
                        1
                ));
            }
        };
    }

    private AnalysisRun writerReadyRun() {
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "分析 Notion 的 AI 搜索机会。",
                "协作文档",
                List.of("Notion"),
                List.of("AI 搜索"),
                List.of("official_site"),
                List.of(),
                "产品规划"
        ));
        run.getEvidenceSources().add(new EvidenceSource(
                "S1",
                "Notion AI search",
                "https://example.test/notion/ai",
                "official_site",
                "FETCHED",
                "LIVE_FETCHED",
                "AI search details",
                "AI search details",
                "test evidence"
        ));
        AnalysisClaim claim = new AnalysisClaim();
        claim.setType(ClaimType.OPPORTUNITY);
        claim.setContent("Notion 的 AI 搜索能力可作为产品规划参考。");
        claim.setCompetitorNames(List.of("Notion"));
        claim.setGeneratedBy(AgentName.ANALYST.name());
        claim.setEvidenceIds(List.of("S1"));
        run.getClaims().add(claim);
        run.addArtifact(new AnalysisArtifact(
                ArtifactType.COMPETITIVE_MATRIX,
                "竞品横向矩阵",
                "| 维度 | 判断 | 证据 |\n| --- | --- | --- |\n| AI 搜索 | 有可验证线索 | [S1] |",
                List.of("S1")
        ));
        run.addArtifact(new AnalysisArtifact(
                ArtifactType.SWOT_ANALYSIS,
                "SWOT 分析",
                "| 维度 | 结论 | 证据 |\n| --- | --- | --- |\n| Opportunities 机会 | 可进入路线图 | [S1] |",
                List.of("S1")
        ));
        return run;
    }

    private ResearcherNode researcherNode(SourceCollectionService sourceCollectionService, LlmClient llmClient) {
        return new ResearcherNode(
                sourceCollectionService,
                new EvidenceChunkService(),
                llmClient,
                new ObjectMapper(),
                new FallbackResearchPlanFactory(),
                new InterviewInsightExtractor()
        );
    }

    private static class TestAnalysisRunRepository implements AnalysisRunRepository {

        private final ConcurrentMap<UUID, AnalysisRun> runs = new ConcurrentHashMap<>();

        @Override
        public AnalysisRun save(AnalysisRun run) {
            run.touch();
            runs.put(run.getId(), run);
            return run;
        }

        @Override
        public Optional<AnalysisRun> findById(UUID id) {
            return Optional.ofNullable(runs.get(id));
        }

        @Override
        public Collection<AnalysisRun> findAll() {
            return runs.values();
        }
    }
}
