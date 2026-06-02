package com.aiinsight.service;

import com.aiinsight.agent.AgentNode;
import com.aiinsight.agent.node.AnalystNode;
import com.aiinsight.agent.node.ClarifierNode;
import com.aiinsight.agent.node.ExtractorNode;
import com.aiinsight.agent.node.ResearcherNode;
import com.aiinsight.agent.node.ReviewerNode;
import com.aiinsight.agent.node.FinalizerNode;
import com.aiinsight.agent.node.WriterNode;
import com.aiinsight.dto.AddAnalysisContextRequest;
import com.aiinsight.dto.AddUserEvidenceRequest;
import com.aiinsight.dto.AnalysisRunMetrics;
import com.aiinsight.dto.AnalysisRunSummary;
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
import com.aiinsight.model.run.WorkflowTransition;
import com.aiinsight.model.review.ReviewFinding;
import com.aiinsight.model.review.ReviewRepairTask;
import com.aiinsight.model.schema.AnalysisClaim;
import com.aiinsight.model.schema.CompetitorProfile;
import com.aiinsight.model.schema.InterviewGuide;
import com.aiinsight.model.schema.Questionnaire;
import com.aiinsight.model.schema.ResearchPlan;
import com.aiinsight.model.schema.ResearchTask;
import com.aiinsight.model.schema.SurveyQuestion;
import com.aiinsight.repository.AnalysisRunRepository;
import com.aiinsight.service.fallback.FallbackAnalysisDraftFactory;
import com.aiinsight.service.fallback.FallbackClarificationDraftFactory;
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
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class AnalysisWorkflowServiceTest {

    @Test
    void createsDraftThenExecutesAfterRequirementConfirmation() {
        AnalysisWorkflowService service = newService();
        CreateAnalysisRunRequest request = new CreateAnalysisRunRequest();
        request.setPrompt("Analyze Notion and Confluence for AI document collaboration.");

        var draft = service.createDraft(request);

        assertThat(draft.getStatus()).isEqualTo(AnalysisStatus.AWAITING_CONFIRMATION);
        assertThat(draft.getSteps())
                .hasSize(1)
                .first()
                .satisfies(step -> {
                    assertThat(step.getAgentName()).isEqualTo(AgentName.CLARIFIER);
                    assertThat(step.getStatus()).isEqualTo(StepStatus.SUCCEEDED);
                });
        assertThat(draft.getClarificationDraft()).isNotNull();
        assertThat(draft.getClarificationDraft().isConfirmed()).isFalse();
        assertThat(draft.getClarificationDraft().getClarificationQuestions()).isNotEmpty();
        assertThat(draft.getClarificationDraft().getClarificationItems()).isNotEmpty();

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

        UpdateAnalysisRequirementRequest reconfirm = new UpdateAnalysisRequirementRequest();
        reconfirm.setCompetitors(withContext.getRequirement().getCompetitors());
        reconfirm.setDimensions(withContext.getRequirement().getDimensions());
        reconfirm.setSourcePreferences(withContext.getRequirement().getSourcePreferences());
        reconfirm.setOutputGoal(withContext.getRequirement().getOutputGoal());
        service.updateRequirement(draft.getId(), reconfirm);
        var finished = service.startExecution(draft.getId());

        assertThat(finished.getStatus()).isEqualTo(AnalysisStatus.SUCCEEDED);
        assertThat(finished.getSteps()).isNotEmpty();
    }

    @Test
    void createDraftAsyncReturnsRunBeforeClarifierCompletes() {
        AnalysisWorkflowService service = newService(new TaskExecutorAdapter(command -> {
        }));
        CreateAnalysisRunRequest request = new CreateAnalysisRunRequest();
        request.setPrompt("Analyze Cursor and Claude Code.");

        AnalysisRun draft = service.createDraftAsync(request);

        assertThat(draft.getStatus()).isEqualTo(AnalysisStatus.AWAITING_CONFIRMATION);
        assertThat(draft.getId()).isNotNull();
        assertThat(draft.getSteps()).isEmpty();
        assertThat(draft.getTraces()).isEmpty();
    }

    @Test
    void clarifyRequirementRerunsPreflightClarifierWithoutConfirmingScope() {
        AnalysisWorkflowService service = newService();
        CreateAnalysisRunRequest request = new CreateAnalysisRunRequest();
        request.setPrompt("Analyze Notion.");
        var draft = service.createDraft(request);

        UpdateAnalysisRequirementRequest update = new UpdateAnalysisRequirementRequest();
        update.setCompetitors(List.of("Notion", "Confluence"));
        update.setDimensions(List.of("AI 搜索", "权限治理"));
        update.setOutputGoal("产品规划");
        var clarified = service.clarifyRequirement(draft.getId(), update);

        assertThat(clarified.getStatus()).isEqualTo(AnalysisStatus.AWAITING_CONFIRMATION);
        assertThat(clarified.getRequirement().getCompetitors()).containsExactly("Notion", "Confluence");
        assertThat(clarified.getClarificationDraft().isConfirmed()).isFalse();
        assertThat(clarified.getSteps())
                .extracting(step -> step.getAgentName())
                .containsExactly(AgentName.CLARIFIER, AgentName.CLARIFIER);
        assertThat(clarified.getArtifacts())
                .filteredOn(artifact -> artifact.getType() == ArtifactType.CLARIFICATION_BRIEF)
                .hasSize(2);
    }

    @Test
    void persistsFrontendControlledReviewReworkAttempts() {
        AnalysisWorkflowService service = newService();
        CreateAnalysisRunRequest request = new CreateAnalysisRunRequest();
        request.setPrompt("Analyze Notion.");
        request.setMaxReviewReworkAttempts(1);

        var draft = service.createDraft(request);

        assertThat(draft.getMaxReviewReworkAttempts()).isEqualTo(1);

        UpdateAnalysisRequirementRequest update = new UpdateAnalysisRequirementRequest();
        update.setCompetitors(List.of("Notion", "Confluence"));
        update.setMaxReviewReworkAttempts(2);

        var updated = service.updateRequirement(draft.getId(), update);

        assertThat(updated.getMaxReviewReworkAttempts()).isEqualTo(2);
    }

    @Test
    void updateRequirementClearsProvidedEmptyFieldsAndKeepsOmittedFields() {
        AnalysisWorkflowService service = newService();
        CreateAnalysisRunRequest request = new CreateAnalysisRunRequest();
        request.setPrompt("Analyze Notion and Confluence.");
        request.setIndustry("AI 文档协作");
        request.setCompetitors(List.of("Notion", "Confluence"));
        request.setDimensions(List.of("AI 搜索", "权限协作"));
        request.setSourcePreferences(List.of("official_site", "pricing_page"));
        request.setSourceUrls(List.of("https://example.test/notion", "https://example.test/confluence"));
        request.setOutputGoal("产品规划");
        var draft = service.createDraft(request);

        UpdateAnalysisRequirementRequest update = new UpdateAnalysisRequirementRequest();
        update.setDimensions(List.of());
        update.setSourcePreferences(List.of());
        update.setSourceUrls(List.of());
        update.setOutputGoal("");
        var updated = service.updateRequirement(draft.getId(), update);

        assertThat(updated.getRequirement().getIndustry()).isEqualTo("AI 文档协作");
        assertThat(updated.getRequirement().getCompetitors()).containsExactly("Notion", "Confluence");
        assertThat(updated.getRequirement().getDimensions()).isEmpty();
        assertThat(updated.getRequirement().getSourcePreferences()).isEmpty();
        assertThat(updated.getRequirement().getSourceUrls()).isEmpty();
        assertThat(updated.getRequirement().getOutputGoal()).isEmpty();
    }

    @Test
    void startAutoConfirmsScopeDraft() {
        AnalysisWorkflowService service = newService();
        CreateAnalysisRunRequest request = new CreateAnalysisRunRequest();
        request.setPrompt("Analyze Notion and Confluence for AI document collaboration.");
        var run = service.createDraft(request);

        assertThat(run.getStatus()).isEqualTo(AnalysisStatus.AWAITING_CONFIRMATION);
        assertThat(run.getClarificationDraft().isConfirmed()).isFalse();

        var finished = service.startExecution(run.getId());

        assertThat(finished.getStatus()).isEqualTo(AnalysisStatus.SUCCEEDED);
        assertThat(finished.getClarificationDraft().isConfirmed()).isTrue();
        assertThat(finished.getClarificationDraft().getConfirmedAt()).isNotNull();
        assertThat(finished.getSteps()).isNotEmpty();
    }

    @Test
    void exposesAuthoritativeRunMetrics() {
        AnalysisWorkflowService service = newService();
        CreateAnalysisRunRequest request = new CreateAnalysisRunRequest();
        request.setPrompt("Analyze Notion and Confluence for AI document collaboration.");

        var finished = service.start(request);
        AnalysisRunMetrics metrics = service.metrics(finished.getId());

        assertThat(metrics.getRunId()).isEqualTo(finished.getId());
        assertThat(metrics.getAgentStepCount()).isEqualTo(finished.getSteps().size());
        assertThat(metrics.getEvidenceCount()).isEqualTo(finished.getEvidenceSources().size());
        assertThat(metrics.getReviewFindingCount()).isEqualTo(finished.getReviewFindings().size());
        assertThat(metrics.getHighFindingCount() + metrics.getMediumFindingCount() + metrics.getLowFindingCount())
                .isEqualTo(finished.getReviewFindings().size());
        assertThat(metrics.getClaimCoverage()).isBetween(0, 100);
        assertThat(metrics.getSchemaCompleteness()).isBetween(0, 100);
        assertThat(metrics.getTotalLatencyMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void stepSummariesDescribeAgentWorkInsteadOfLifecyclePlaceholders() {
        AnalysisWorkflowService service = newService();
        CreateAnalysisRunRequest request = new CreateAnalysisRunRequest();
        request.setPrompt("Analyze Notion and Confluence for AI document collaboration.");

        var finished = service.start(request);

        assertThat(finished.getSteps())
                .allSatisfy(step -> {
                    assertThat(step.getInputSummary()).doesNotContain("来自上一 Agent 状态的输入");
                    assertThat(step.getOutputSummary()).doesNotContain("produced updated run state");
                });
        assertThat(finished.getSteps().get(0).getInputSummary()).contains("澄清原始需求");
        assertThat(finished.getSteps().get(0).getOutputSummary()).contains("范围已澄清");
        assertThat(finished.getSteps())
                .filteredOn(step -> step.getAgentName() == AgentName.RESEARCHER)
                .first()
                .satisfies(step -> {
                    assertThat(step.getInputSummary()).contains("采集公开资料");
                    assertThat(step.getOutputSummary()).contains("资料采集完成");
                });
    }

    @Test
    void blocksDuplicateStartWhileAsyncPipelineHasNotRunYet() {
        AnalysisWorkflowService service = newService(new TaskExecutorAdapter(command -> {
        }));
        CreateAnalysisRunRequest request = new CreateAnalysisRunRequest();
        request.setPrompt("Analyze Notion and Confluence for AI document collaboration.");
        var run = service.createDraft(request);

        UpdateAnalysisRequirementRequest update = new UpdateAnalysisRequirementRequest();
        update.setCompetitors(List.of("Notion", "Confluence"));
        update.setDimensions(List.of("AI 搜索", "权限协作"));
        service.updateRequirement(run.getId(), update);

        var started = service.startExecution(run.getId());

        assertThat(started.getStatus()).isEqualTo(AnalysisStatus.RUNNING);
        assertThatThrownBy(() -> service.startExecution(run.getId()))
                .isInstanceOf(InvalidRunStateException.class)
                .hasMessageContaining("workflow is already running");
        assertThat(service.get(run.getId()).getSteps())
                .extracting(step -> step.getAgentName())
                .containsExactly(AgentName.CLARIFIER);
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
    void nodeExecutorDoesNotOverwriteCancelledRunAfterNodeReturns() {
        CopyingTestAnalysisRunRepository repository = new CopyingTestAnalysisRunRepository();
        AnalysisEventBroker eventBroker = new AnalysisEventBroker();
        WorkflowNodeExecutor nodeExecutor = new WorkflowNodeExecutor(repository, eventBroker);
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "Analyze Notion",
                "AI documents",
                List.of("Notion"),
                List.of("pricing"),
                List.of("official_site"),
                List.of()
        ));
        run.setStatus(AnalysisStatus.RUNNING);
        repository.save(run);

        AgentNode cancellingNode = new AgentNode() {
            @Override
            public AgentName name() {
                return AgentName.CLARIFIER;
            }

            @Override
            public String title() {
                return "Cancelling node";
            }

            @Override
            public AnalysisRun execute(AnalysisRun staleRun) {
                AnalysisRun latest = repository.findById(staleRun.getId()).orElseThrow();
                latest.setStatus(AnalysisStatus.CANCELLED);
                repository.save(latest);
                staleRun.setStatus(AnalysisStatus.RUNNING);
                staleRun.getRecommendedActions().add("stale node result should not be saved");
                return staleRun;
            }
        };

        assertThatThrownBy(() -> nodeExecutor.executeNode(run.getId(), cancellingNode, "simulate external cancellation"))
                .isInstanceOf(CancellationException.class);

        AnalysisRun saved = repository.findById(run.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(AnalysisStatus.CANCELLED);
        assertThat(saved.getRecommendedActions()).doesNotContain("stale node result should not be saved");
        assertThat(saved.getSteps()).singleElement()
                .satisfies(step -> {
                    assertThat(step.getStatus()).isEqualTo(StepStatus.CANCELLED);
                    assertThat(step.getIssues()).anyMatch(issue -> issue.contains("cancelled"));
                    assertThat(step.getCompletedAt()).isNotNull();
                });
        assertThat(saved.getTraces()).singleElement()
                .satisfies(trace -> {
                    assertThat(trace.getStatus()).isEqualTo(StepStatus.CANCELLED);
                    assertThat(trace.getDecisionSummary()).isEqualTo("CANCELLED");
                    assertThat(trace.getCompletedAt()).isNotNull();
                });
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
        assertThat(finished.getSteps()).hasSizeGreaterThanOrEqualTo(6);
        assertThat(finished.getSteps().get(0).getAgentName()).isEqualTo(AgentName.CLARIFIER);
        assertThat(finished.getSteps().stream()
                .filter(step -> step.getAgentName() == AgentName.CLARIFIER)
                .count()).isEqualTo(1);
        assertThat(finished.getSteps().get(1).getAgentName()).isEqualTo(AgentName.RESEARCHER);
        assertThat(finished.getArtifacts())
                .filteredOn(artifact -> artifact.getType() == ArtifactType.CLARIFICATION_BRIEF)
                .isNotEmpty();
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
        assertThat(finished.getWorkflowTransitions().get(finished.getWorkflowTransitions().size() - 1).getTargetNode()).isEqualTo(AgentName.FINALIZER.name());
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
        assertThat(finished.getResearchPackage().getMissingEvidenceTypes())
                .doesNotContain("survey_result", "interview_note");
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
    void workflowNeedsUserInputWhenFinalReviewDecisionStillBlocksRelease() {
        AnalysisRunRepository repository = new TestAnalysisRunRepository();
        AnalysisEventBroker eventBroker = new AnalysisEventBroker();
        WorkflowNodeExecutor nodeExecutor = new WorkflowNodeExecutor(repository, eventBroker);
        SourceCollectionService sourceCollectionService = new SourceCollectionService(fetchUsefulPages(), fakeSearchProvider());
        ClarifierNode clarifierNode = new ClarifierNode(
                noopLlmClient(),
                new ObjectMapper(),
                new FallbackClarificationDraftFactory()
        );
        AnalysisLangGraphWorkflow graphWorkflow = mock(AnalysisLangGraphWorkflow.class);
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "Analyze Cursor and Claude Code.",
                "AI coding",
                List.of("Cursor", "Claude Code"),
                List.of("pricing"),
                List.of("official_site"),
                List.of()
        ));
        run.setStatus(AnalysisStatus.PENDING);
        repository.save(run);
        doAnswer(invocation -> {
            UUID runId = invocation.getArgument(0);
            AnalysisRun latest = repository.findById(runId).orElseThrow();
            ReviewFinding finding = new ReviewFinding(
                    ReviewSeverity.HIGH,
                    "citation_missing",
                    "最终复核仍缺少关键引用。",
                    "重跑 Writer 或人工补 citation。"
            );
            latest.getReviewFindings().add(finding);
            latest.getReviewDecision().setAction(ReviewAction.REVISE_REPORT);
            latest.getReviewDecision().setTargetAgent(AgentName.WRITER);
            repository.save(latest);
            return null;
        }).when(graphWorkflow).execute(run.getId());

        AnalysisWorkflowService service = new AnalysisWorkflowService(
                repository,
                new AnalysisRequestNormalizer(),
                eventBroker,
                new TaskExecutorAdapter(Runnable::run),
                graphWorkflow,
                nodeExecutor,
                clarifierNode,
                new FallbackClarificationDraftFactory(),
                new EvidenceRetrievalService(),
                sourceCollectionService,
                new EvidenceChunkService(),
                EvidenceEmbeddingService.disabled()
        );

        AnalysisRun finished = service.startExecution(run.getId());

        assertThat(finished.getStatus()).isEqualTo(AnalysisStatus.NEEDS_USER_INPUT);
        assertThat(finished.getReviewDecision().getAction()).isEqualTo(ReviewAction.REVISE_REPORT);
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
    void writerRemovesInternalClaimIdsAndReportMetadataFromLlmOutput() {
        LlmClient writerLlm = new LlmClient() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String complete(com.aiinsight.llm.ChatRequest request) {
                return """
                        报告编号： COMP-AI-2024-001
                        生成日期： 2024-07-30
                        # 报告草稿

                        核心路径差异可以作为规划输入 [C-77b41f42-9373-423c-84d3-c681d7d08f43] [S1]。
                        报告草稿结束
                        """;
            }
        };
        AnalysisRun run = writerReadyRun();

        new WriterNode(writerLlm, new FallbackReportDraftFactory()).execute(run);

        assertThat(run.getArtifacts())
                .filteredOn(artifact -> artifact.getType() == ArtifactType.REPORT_DRAFT)
                .last()
                .satisfies(artifact -> assertThat(artifact.getContent())
                        .contains("结构化结论", "[S1]")
                        .doesNotContain("报告编号", "生成日期", "报告草稿结束", "[C-77b41f42"));
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
                .contains("结构化结论:", "竞品画像摘要:", "竞品矩阵:", "SWOT 分析:", "采集包缺口与一手洞察:", "证据索引:")
                .contains("结论先行", "建议优先级", "不要输出报告编号", "不要在正文使用 [C-...] Claim ID")
                .contains("报告主体只写“已验证/可初步判断”的内容", "不要出现 Analyst、Reviewer、Researcher、Writer")
                .contains("Notion 的 AI 搜索能力可作为产品规划参考。", "AI 知识协作工具", "证据缺口", "Notion AI search")
                .doesNotContain("相关证据切片", "S1-C1");
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
        StringBuffer promptCapture = new StringBuffer();
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
        assertThat(promptCapture.toString())
                .contains("证据索引", "按维度整理的证据覆盖", "不要把“证据不足”本身当成主要洞察")
                .contains("[S1] Notion permission audit")
                .doesNotContain("competitiveMatrixMarkdown", "swotMarkdown", "S1-C1", "enterprise governance");
    }

    @Test
    void analystNormalizesCompetitorAliasesAndRanksMatrixClaims() {
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
                              "type": "FACT",
                              "content": "Unverified broad fact should not occupy the matrix summary.",
                              "confidence": "HIGH",
                              "competitorNames": ["Atlassian Confluence"],
                              "evidenceIds": []
                            },
                            {
                              "type": "RISK",
                              "content": "Weak review evidence mentions migration friction.",
                              "confidence": "LOW",
                              "competitorNames": ["Atlassian Confluence"],
                              "evidenceIds": ["S1"]
                            },
                            {
                              "type": "COMPARISON",
                              "content": "Official docs support enterprise admin controls.",
                              "confidence": "MEDIUM",
                              "competitorNames": ["Atlassian Confluence"],
                              "evidenceIds": ["S2"]
                            },
                            {
                              "type": "RECOMMENDATION",
                              "content": "Prioritize enterprise admin controls in the roadmap.",
                              "confidence": "HIGH",
                              "competitorNames": ["Atlassian Confluence"],
                              "evidenceIds": ["S2"]
                            }
                          ]
                        }
                        """;
            }
        };
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "Analyze Notion and Confluence enterprise readiness.",
                "Collaboration docs",
                List.of("Notion", "Confluence"),
                List.of("enterprise admin"),
                List.of("official_site", "public_review"),
                List.of()
        ));
        run.getEvidenceSources().add(new EvidenceSource(
                "S1",
                "Confluence review",
                "https://example.test/confluence/review",
                "public_review",
                "FETCHED",
                "LIVE_FETCHED",
                "LOW",
                "NONE",
                "User review mentions migration friction.",
                "",
                "test evidence"
        ));
        run.getEvidenceSources().add(new EvidenceSource(
                "S2",
                "Confluence admin docs",
                "https://example.test/confluence/admin",
                "docs",
                "FETCHED",
                "LIVE_FETCHED",
                "HIGH",
                "NONE",
                "Official docs describe enterprise admin controls.",
                "Official docs describe enterprise admin controls.",
                "test evidence"
        ));

        new AnalystNode(structuredLlm, new ObjectMapper(), new FallbackAnalysisDraftFactory()).execute(run);

        assertThat(run.getClaims())
                .anySatisfy(claim -> {
                    assertThat(claim.getContent()).contains("enterprise admin controls");
                    assertThat(claim.getCompetitorNames()).containsExactly("Confluence");
                });
        String matrixContent = run.getArtifacts().stream()
                .filter(artifact -> artifact.getType() == ArtifactType.COMPETITIVE_MATRIX)
                .reduce((first, second) -> second)
                .orElseThrow()
                .getContent();
        String matrixSummary = matrixContent.substring(0, matrixContent.indexOf("## 结构化结论明细"));
        assertThat(matrixSummary)
                .contains("Confluence", "Prioritize enterprise admin controls", "Official docs support enterprise admin controls")
                .doesNotContain("Unverified broad fact");
    }

    @Test
    void analystPrioritizesStrongEvidenceAndDowngradesWeakHighConfidenceClaims() {
        StringBuilder claimsPrompt = new StringBuilder();
        LlmClient structuredLlm = new LlmClient() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String complete(com.aiinsight.llm.ChatRequest request) {
                String prompt = request.getMessages().get(1).getContent();
                if (prompt.contains("只生成结构化 claims")) {
                    claimsPrompt.append(prompt);
                    return """
                            {
                              "claims": [
                                {
                                  "type": "RISK",
                                  "content": "Notion 的企业治理风险只来自低质量评论来源。",
                                  "confidence": "HIGH",
                                  "competitorNames": ["Notion"],
                                  "evidenceIds": ["S1"]
                                },
                                {
                                  "type": "STRENGTH",
                                  "content": "Notion 官方文档显示其 AI 搜索有可验证能力。",
                                  "confidence": "HIGH",
                                  "competitorNames": ["Notion"],
                                  "evidenceIds": ["S12"]
                                }
                              ]
                            }
                            """;
                }
                if (prompt.contains("matrixMarkdown")) {
                    return """
                            {"matrixMarkdown":"| 竞品 | 判断 | 证据 |\\n| --- | --- | --- |\\n| Notion | AI 搜索有官方证据 | [S12] |"}
                            """;
                }
                return """
                        {"swotMarkdown":"| 维度 | 结论 | 证据 |\\n| --- | --- | --- |\\n| Strengths | 官方 AI 搜索证据较强 | [S12] |"}
                        """;
            }
        };
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "分析 Notion 的企业治理和 AI 搜索。",
                "协作文档",
                List.of("Notion"),
                List.of("企业治理", "AI 搜索"),
                List.of("official_site", "docs"),
                List.of()
        ));
        for (int i = 1; i <= 11; i++) {
            run.getEvidenceSources().add(new EvidenceSource(
                    "S" + i,
                    "Low source " + i,
                    "https://example.test/low/" + i,
                    "public_review",
                    "FETCHED",
                    "LIVE_FETCHED",
                    "LOW",
                    "NONE",
                    "thin user comment about governance",
                    "",
                    "test evidence"
            ));
        }
        run.getEvidenceSources().add(new EvidenceSource(
                "S12",
                "Notion official AI search docs",
                "https://example.test/notion/docs/ai-search",
                "docs",
                "FETCHED",
                "LIVE_FETCHED",
                "HIGH",
                "NONE",
                "Notion official docs explain AI search capabilities",
                "Notion official docs explain AI search capabilities",
                "test evidence"
        ));

        new AnalystNode(structuredLlm, new ObjectMapper(), new FallbackAnalysisDraftFactory()).execute(run);

        assertThat(claimsPrompt.toString())
                .contains("[S12] Notion official AI search docs", "tier=strong")
                .contains("[S1] Low source 1", "tier=weak")
                .doesNotContain("[S8] Low source 8");
        assertThat(run.getClaims()).hasSize(2);
        assertThat(run.getClaims().get(0).getConfidence()).isEqualTo(com.aiinsight.model.enums.ConfidenceLevel.LOW);
        assertThat(run.getClaims().get(1).getConfidence()).isEqualTo(com.aiinsight.model.enums.ConfidenceLevel.HIGH);
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
        assertThat(run.getClaims()).extracting(AnalysisClaim::getContent)
                .allMatch(content -> !content.contains("Reviewer") && !content.contains("可重跑") && !content.contains("打回采集"));
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
                String prompt = request.getMessages().get(1).getContent();
                if (prompt.contains("matrixMarkdown")) {
                    return """
                            {"matrixMarkdown":"| 维度 | 竞品 | 判断 | 证据 |\\n| --- | --- | --- | --- |\\n| AI 搜索 | Notion | 有可验证线索，也有未知引用 | [S1] [S404] |"}
                            """;
                }
                if (prompt.contains("swotMarkdown")) {
                    return """
                            {"swotMarkdown":"| 维度 | 结论 | 证据 |\\n| --- | --- | --- |\\n| Threats 威胁 | 错误引用应被清理 | [S404] |"}
                            """;
                }
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
                              ]
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
                    assertThat(artifact.getContent()).contains("[S1]", "基于结构化结论的竞品矩阵");
                    assertThat(artifact.getContent()).doesNotContain("[S404]");
                    assertThat(artifact.getCitationKeys()).containsExactly("S1");
                });
        assertThat(run.getArtifacts())
                .filteredOn(artifact -> artifact.getType() == ArtifactType.SWOT_ANALYSIS)
                .last()
                .satisfies(artifact -> {
                    assertThat(artifact.getContent()).contains("[S1]", "SWOT 仅由结构化结论渲染");
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
        SourceCollectionService sourceCollectionService = new SourceCollectionService(fetchUsefulPages(), fakeSearchProvider());
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
    void researcherReusesExistingResearchPlanDuringRecollection() {
        AtomicInteger llmCalls = new AtomicInteger();
        LlmClient queryOnlyLlm = new LlmClient() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String complete(com.aiinsight.llm.ChatRequest request) {
                llmCalls.incrementAndGet();
                assertThat(request.getMessages().get(1).getContent()).contains("本轮真正用于搜索的 query batch");
                return """
                        {
                          "batches": [
                            {
                              "competitor": "Cursor",
                              "queries": [
                                {
                                  "query": "Cursor pricing official documentation",
                                  "evidenceType": "pricing_page",
                                  "purpose": "补充官方定价证据",
                                  "priority": "HIGH"
                                }
                              ]
                            }
                          ]
                        }
                        """;
            }
        };
        SourceCollectionService sourceCollectionService = new SourceCollectionService(fetchUsefulPages(), fakeSearchProvider());
        ResearcherNode researcherNode = researcherNode(sourceCollectionService, queryOnlyLlm);
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "分析 Cursor 的定价和团队能力。",
                "AI 编程助手",
                List.of("Cursor"),
                List.of("定价"),
                List.of("pricing_page"),
                List.of()
        ));
        ResearchPlan existingPlan = usableResearchPlan();
        run.getResearchPackage().setResearchPlan(existingPlan);
        run.getReviewDecision().setAction(ReviewAction.RECOLLECT_EVIDENCE);
        run.getReviewDecision().setTargetAgent(AgentName.RESEARCHER);
        run.getReviewDecision().setRequiredEvidenceTypes(List.of("pricing_page"));

        researcherNode.execute(run);

        assertThat(llmCalls).hasValue(1);
        assertThat(run.getResearchPackage().getResearchPlan()).isSameAs(existingPlan);
        assertThat(run.getResearchPackage().getResearchPlan().getQuestionnaire().getTitle())
                .isEqualTo("既有问卷");
        assertThat(run.getResearchPackage().getResearchPlan().getSearchQueries())
                .containsExactly("Cursor pricing official documentation");
        assertThat(run.getResearchPackage().getActualSearchQueries())
                .containsExactly("Cursor pricing official documentation");
    }

    @Test
    void researcherSkipsResearchPlanLlmDuringRecollectionEvenWhenExistingPlanIsIncomplete() {
        AtomicInteger llmCalls = new AtomicInteger();
        LlmClient queryPlannerLlm = new LlmClient() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String complete(com.aiinsight.llm.ChatRequest request) {
                llmCalls.incrementAndGet();
                assertThat(request.getMessages().get(1).getContent()).contains("本轮真正用于搜索的 query batch");
                return """
                        {
                          "batches": [
                            {
                              "competitor": "Cursor",
                              "queries": [
                                {
                                  "query": "Cursor pricing official documentation",
                                  "evidenceType": "pricing_page",
                                  "purpose": "补充官方定价证据",
                                  "priority": "HIGH"
                                }
                              ]
                            }
                          ]
                        }
                        """;
            }
        };
        SourceCollectionService sourceCollectionService = new SourceCollectionService(fetchUsefulPages(), fakeSearchProvider());
        ResearcherNode researcherNode = researcherNode(sourceCollectionService, queryPlannerLlm);
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "分析 Cursor 的定价。",
                "AI 编程助手",
                List.of("Cursor"),
                List.of("定价"),
                List.of("pricing_page"),
                List.of()
        ));
        ResearchPlan incompletePlan = new ResearchPlan();
        incompletePlan.setObjective("复用不完整调研计划");
        run.getResearchPackage().setResearchPlan(incompletePlan);
        run.getReviewDecision().setAction(ReviewAction.RECOLLECT_EVIDENCE);
        run.getReviewDecision().setTargetAgent(AgentName.RESEARCHER);
        run.getReviewDecision().setRequiredEvidenceTypes(List.of("pricing_page"));

        researcherNode.execute(run);

        assertThat(llmCalls).hasValue(1);
        assertThat(run.getResearchPackage().getResearchPlan()).isSameAs(incompletePlan);
        assertThat(run.getResearchPackage().getResearchPlan().getQuestionnaire().getQuestions()).isNotEmpty();
        assertThat(run.getResearchPackage().getResearchPlan().getInterviewGuide().getQuestions()).isNotEmpty();
        assertThat(run.getResearchPackage().getResearchPlan().getSearchQueries())
                .containsExactly("Cursor pricing official documentation");
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
    void extractorUsesLlmJsonToPopulateStructuredProfiles() {
        StringBuilder promptCapture = new StringBuilder();
        LlmClient extractorLlm = new LlmClient() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String complete(com.aiinsight.llm.ChatRequest request) {
                promptCapture.append(request.getMessages().get(1).getContent());
                return """
                        {
                          "profiles": [
                            {
                              "productName": "Cursor",
                              "companyName": "Cursor",
                              "positioning": "AI 优先的代码编辑器",
                              "targetUsers": ["软件开发者", "研发团队"],
                              "features": [
                                {
                                  "name": "Composer",
                                  "description": "支持跨文件生成和修改代码",
                                  "evidenceIds": ["S1"]
                                },
                                {
                                  "name": "编造能力",
                                  "description": "这个能力引用不存在证据",
                                  "evidenceIds": ["S404"]
                                }
                              ],
                              "pricing": {
                                "strategySummary": "提供 Pro 和团队订阅，具体价格以页面为准",
                                "hasFreePlan": true,
                                "plans": [
                                  {
                                    "name": "Pro",
                                    "priceText": "$20/月",
                                    "billingCycle": "monthly",
                                    "targetSegment": "个人开发者",
                                    "includedFeatures": ["Composer"],
                                    "evidenceIds": ["S1"]
                                  }
                                ],
                                "evidenceIds": ["S1"]
                              },
                              "personas": [
                                {
                                  "name": "研发团队用户",
                                  "segment": "软件研发",
                                  "companySize": "中小团队到企业团队",
                                  "jobsToBeDone": ["跨文件修改代码"],
                                  "painPoints": ["上下文切换成本高"],
                                  "buyingConcerns": ["价格方案"],
                                  "evidenceIds": ["S1"]
                                }
                              ],
                              "strengths": ["跨文件代码修改能力明确"],
                              "weaknesses": ["企业安全能力待验证"],
                              "evidenceIds": ["S1"]
                            }
                          ]
                        }
                        """;
            }
        };
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "分析 Cursor 的 AI 编程能力。",
                "AI 编程助手",
                List.of("Cursor"),
                List.of("Agent 工作流", "上下文管理"),
                List.of("official_site", "pricing_page"),
                List.of()
        ));
        run.getEvidenceSources().add(new EvidenceSource(
                "S1",
                "Cursor product and pricing",
                "https://example.test/cursor",
                "official_site",
                "FETCHED",
                "LIVE_FETCHED",
                "Cursor is an AI code editor with Composer. Pro plan is $20/month.",
                "Cursor Composer supports multi-file code edits. Cursor Pro costs $20/month.",
                "test evidence"
        ));

        new ExtractorNode(extractorLlm, new FallbackExtractionFactory()).execute(run);

        assertThat(promptCapture.toString()).contains("只输出可解析 JSON", "证据片段索引");
        assertThat(run.getCompetitorProfiles()).hasSize(1);
        var cursor = run.getCompetitorProfiles().get(0);
        assertThat(cursor.getPositioning()).isEqualTo("AI 优先的代码编辑器");
        assertThat(cursor.getStrengths()).contains("跨文件代码修改能力明确");
        assertThat(cursor.getFeatureTree().getRoots())
                .extracting(node -> node.getName())
                .containsExactly("Composer");
        assertThat(cursor.getPricingModel().getPlans())
                .singleElement()
                .satisfies(plan -> {
                    assertThat(plan.getName()).isEqualTo("Pro");
                    assertThat(plan.getPriceText()).isEqualTo("$20/月");
                    assertThat(plan.getEvidenceIds()).containsExactly("S1");
                });
        assertThat(run.getArtifacts())
                .filteredOn(artifact -> artifact.getType() == ArtifactType.COMPETITOR_PROFILE)
                .last()
                .satisfies(artifact -> assertThat(artifact.getContent())
                        .contains("Composer", "$20/月")
                        .doesNotContain("编造能力"));
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
        UpdateAnalysisRequirementRequest update = new UpdateAnalysisRequirementRequest();
        update.setCompetitors(request.getCompetitors());
        update.setDimensions(request.getDimensions());
        service.updateRequirement(run.getId(), update);
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

        assertThat(rerun.getSteps().subList(rerun.getSteps().size() - 3, rerun.getSteps().size()))
                .extracting(step -> step.getAgentName())
                .containsExactly(AgentName.WRITER, AgentName.REVIEWER, AgentName.FINALIZER);
        assertThat(rerun.getArtifacts())
                .filteredOn(artifact -> artifact.getType() == ArtifactType.REPORT_DRAFT)
                .extracting(artifact -> artifact.getVersion())
                .containsExactly(1, 2);
        assertThat(rerun.getArtifacts())
                .filteredOn(artifact -> artifact.getType() == ArtifactType.REVIEW_FINDINGS)
                .extracting(artifact -> artifact.getVersion())
                .containsExactly(1, 2);
        assertThat(rerun.getArtifacts())
                .filteredOn(artifact -> artifact.getType() == ArtifactType.FINAL_REPORT)
                .extracting(artifact -> artifact.getVersion())
                .containsExactly(1, 2);
        assertThat(rerun.getWorkflowTransitions())
                .last()
                .satisfies(transition -> {
                    assertThat(transition.getTrigger()).isEqualTo("manual-rerun-from-WRITER");
                    assertThat(transition.getResolutionStatus()).isNotBlank();
                });
    }

    @Test
    void rerunResearcherContinuesThroughEveryDownstreamAgent() {
        AnalysisWorkflowService service = newService();
        CreateAnalysisRunRequest request = new CreateAnalysisRunRequest();
        request.setPrompt("Analyze Cursor and Claude Code for developer workflow.");

        var run = service.start(request);
        var rerun = service.rerunAgent(run.getId(), AgentName.RESEARCHER);

        assertThat(rerun.getSteps().subList(rerun.getSteps().size() - 6, rerun.getSteps().size()))
                .extracting(step -> step.getAgentName())
                .containsExactly(
                        AgentName.RESEARCHER,
                        AgentName.EXTRACTOR,
                        AgentName.ANALYST,
                        AgentName.WRITER,
                        AgentName.REVIEWER,
                        AgentName.FINALIZER
                );
        assertThat(rerun.getArtifacts())
                .filteredOn(artifact -> artifact.getType() == ArtifactType.REPORT_DRAFT)
                .extracting(artifact -> artifact.getVersion())
                .containsExactly(1, 2);
        assertThat(rerun.getWorkflowTransitions())
                .last()
                .satisfies(transition -> assertThat(transition.getTrigger()).isEqualTo("manual-rerun-from-RESEARCHER"));
    }

    @Test
    void rerunAgentIsBlockedWhileWorkflowIsRunning() {
        AnalysisWorkflowService service = newService(new TaskExecutorAdapter(command -> {
        }));
        CreateAnalysisRunRequest request = new CreateAnalysisRunRequest();
        request.setPrompt("Analyze Notion and Confluence.");

        AnalysisRun run = service.start(request);

        assertThat(run.getStatus()).isEqualTo(AnalysisStatus.RUNNING);
        assertThatThrownBy(() -> service.rerunAgent(run.getId(), AgentName.WRITER))
                .isInstanceOf(InvalidRunStateException.class)
                .hasMessageContaining("agent cannot be rerun while workflow is RUNNING");
    }

    @Test
    void nodeExecutorPersistsRunningTraceBeforeAgentCompletes() {
        AnalysisRunRepository repository = new CopyingTestAnalysisRunRepository();
        WorkflowNodeExecutor executor = new WorkflowNodeExecutor(repository, new AnalysisEventBroker());
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement());
        repository.save(run);
        AnalysisRun[] snapshot = new AnalysisRun[1];
        AgentNode inspectingNode = new AgentNode() {
            @Override
            public AgentName name() {
                return AgentName.CLARIFIER;
            }

            @Override
            public String title() {
                return "Inspect trace persistence";
            }

            @Override
            public AnalysisRun execute(AnalysisRun currentRun) {
                snapshot[0] = repository.findById(currentRun.getId()).orElseThrow();
                return currentRun;
            }
        };

        AnalysisRun completed = executor.executeNode(run.getId(), inspectingNode, "trace should be visible while running");

        assertThat(snapshot[0].getTraces())
                .singleElement()
                .satisfies(trace -> {
                    assertThat(trace.getAgentName()).isEqualTo(AgentName.CLARIFIER);
                    assertThat(trace.getStatus()).isEqualTo(StepStatus.RUNNING);
                    assertThat(trace.getInputSnapshot()).contains("澄清原始需求");
                });
        assertThat(completed.getTraces())
                .singleElement()
                .satisfies(trace -> assertThat(trace.getStatus()).isEqualTo(StepStatus.SUCCEEDED));
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
        assertThat(run.getReviewDecision().getFindingCategories()).containsExactly("citation_missing");
        assertThat(run.getReviewDecision().getBlockingFindingIds()).hasSize(1);
        assertThat(run.getReviewDecision().getRepairScopeSummary()).contains("RESEARCHER", "pricing_page");
        assertThat(run.getReviewDecision().getRepairInstructions())
                .anyMatch(instruction -> instruction.contains("Researcher") && instruction.contains("pricing_page"));
        assertThat(run.getReviewDecision().getRepairTasks())
                .singleElement()
                .satisfies(task -> {
                    assertThat(task.getTargetAgent()).isEqualTo(AgentName.RESEARCHER);
                    assertThat(task.getAction()).isEqualTo("COLLECT_TARGETED_EVIDENCE");
                    assertThat(task.getRequiredEvidenceTypes()).containsExactly("pricing_page");
                    assertThat(task.getAcceptanceCriteria()).contains("pricing_page");
                });
        assertThat(run.getReviewDecision().getReason()).contains("citation_missing", "pricing_page");
        assertThat(run.getArtifacts())
                .filteredOn(artifact -> artifact.getType() == ArtifactType.REVIEW_FINDINGS)
                .last()
                .satisfies(artifact -> assertThat(artifact.getContent())
                        .contains("可信度状态", "定向修复计划", "结构化修复任务", "阻断问题", "citation_missing"));
    }

    @Test
    void reviewerDoesNotSendManualOnlyResearchGapsToPublicSearch() {
        AnalysisRun run = new AnalysisRun();
        run.getResearchPackage().getMissingEvidenceTypes().addAll(List.of("survey_result", "interview_note"));
        run.addArtifact(new AnalysisArtifact(
                ArtifactType.REPORT_DRAFT,
                "draft",
                "机会点是优化用户画像和采购访谈链路。",
                List.of()
        ));

        new ReviewerNode(new CitationCoverageEvaluator(), noopLlmClient(), new FallbackReviewReportFactory()).execute(run);

        assertThat(run.getReviewDecision().getAction()).isEqualTo(ReviewAction.REVISE_REPORT);
        assertThat(run.getReviewDecision().getTargetAgent()).isEqualTo(AgentName.WRITER);
        assertThat(run.getReviewDecision().getRequiredEvidenceTypes())
                .containsExactly("survey_result", "interview_note");
        assertThat(run.getReviewDecision().getReason())
                .contains("一手调研缺口", "不能由公开搜索自动补齐");
        assertThat(run.getRecommendedActions())
                .anyMatch(action -> action.contains("公开搜索不能自动生成真实问卷或访谈"));
    }

    @Test
    void reviewerOnlyRecollectsAutoCollectableEvidenceWhenManualGapsAreMixedIn() {
        AnalysisRun run = new AnalysisRun();
        run.getResearchPackage().getMissingEvidenceTypes().addAll(List.of("pricing_page", "survey_result"));
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
        assertThat(run.getReviewDecision().getReason())
                .contains("pricing_page")
                .contains("survey_result")
                .contains("人工补证");
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
        assertThat(run.getReviewDecision().getRepairInstructions())
                .anyMatch(instruction -> instruction.contains("Analyst") && instruction.contains("affectedClaimIds"));
        assertThat(run.getReviewDecision().getRepairTasks())
                .singleElement()
                .satisfies(task -> {
                    assertThat(task.getTargetAgent()).isEqualTo(AgentName.ANALYST);
                    assertThat(task.getAction()).isEqualTo("REPAIR_CLAIM_EVIDENCE");
                    assertThat(task.getClaimId()).isEqualTo(claim.getId());
                    assertThat(task.getAcceptanceCriteria()).contains("evidenceIds");
                });
        assertThat(run.getReviewDecision().getReason()).contains("claim_missing_evidence", "Analyst");
    }

    @Test
    void downstreamRepairPlansIncludeSpecificTaskInstruction() throws Exception {
        AnalysisRun run = new AnalysisRun();
        run.getReviewDecision().setAction(ReviewAction.REWORK_ANALYSIS);
        run.getReviewDecision().setTargetAgent(AgentName.ANALYST);
        run.getReviewDecision().setAffectedClaimIds(List.of("C-1"));
        run.getReviewDecision().setFindingCategories(List.of("claim_evidence_mismatch"));
        run.getReviewDecision().setRepairInstructions(List.of("repair affected claim only"));
        ReviewRepairTask analystTask = new ReviewRepairTask();
        analystTask.setTargetAgent(AgentName.ANALYST);
        analystTask.setAction("REPAIR_CLAIM_EVIDENCE");
        analystTask.setClaimId("C-1");
        analystTask.setInstruction("narrow this claim to pricing evidence");
        analystTask.setAcceptanceCriteria("claim confidence is downgraded or evidence is rebound");
        run.getReviewDecision().getRepairTasks().add(analystTask);

        String analystPlan = invokeRepairPlanBlock(
                new AnalystNode(noopLlmClient(), new ObjectMapper(), new FallbackAnalysisDraftFactory()),
                run
        );

        run.getReviewDecision().setAction(ReviewAction.REVISE_REPORT);
        run.getReviewDecision().setTargetAgent(AgentName.WRITER);
        ReviewRepairTask writerTask = new ReviewRepairTask();
        writerTask.setTargetAgent(AgentName.WRITER);
        writerTask.setAction("REVISE_REPORT_TEXT");
        writerTask.setCitationKey("S1");
        writerTask.setParagraphIndex(2);
        writerTask.setExcerpt("价格策略会带来明显增长");
        writerTask.setCurrentText("价格策略会带来明显增长 [S1]");
        writerTask.setInstruction("rewrite paragraph 2 as a tentative finding");
        writerTask.setExpectedFix("降级为待验证假设并保留 citation");
        writerTask.setAcceptanceCriteria("paragraph has source and uncertainty marker");
        run.getReviewDecision().getRepairTasks().clear();
        run.getReviewDecision().getRepairTasks().add(writerTask);

        String writerPlan = invokeRepairPlanBlock(new WriterNode(noopLlmClient(), new FallbackReportDraftFactory()), run);

        assertThat(analystPlan).contains("narrow this claim to pricing evidence");
        assertThat(writerPlan)
                .contains("rewrite paragraph 2 as a tentative finding")
                .contains("价格策略会带来明显增长")
                .contains("降级为待验证假设");
    }

    @Test
    void reviewerMergesStructuredLlmFindingsIntoDecision() {
        List<String> promptCapture = new java.util.concurrent.CopyOnWriteArrayList<>();
        LlmClient reviewerLlm = new LlmClient() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String complete(com.aiinsight.llm.ChatRequest request) {
                promptCapture.add(request.getMessages().get(1).getContent());
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

        assertThat(promptCapture).anySatisfy(prompt -> assertThat(prompt)
                .contains("只输出可解析 JSON", "Claim 与证据:", "C-LLM-1"));
        assertThat(run.getReviewFindings())
                .anySatisfy(finding -> {
                    assertThat(finding.getCategory()).isEqualTo("llm_overclaim");
                    assertThat(finding.getClaimId()).isEqualTo("C-LLM-1");
                    assertThat(finding.getCitationKey()).isEqualTo("S1");
                });
        assertThat(run.getReviewDecision().getAction()).isEqualTo(ReviewAction.REVISE_REPORT);
        assertThat(run.getReviewDecision().getRepairInstructions())
                .anyMatch(instruction -> instruction.contains("Writer") && instruction.contains("citation"));
        assertThat(run.getReviewDecision().getRepairTasks())
                .anySatisfy(task -> {
                    assertThat(task.getTargetAgent()).isEqualTo(AgentName.WRITER);
                    assertThat(task.getClaimId()).isEqualTo("C-LLM-1");
                    assertThat(task.getCitationKey()).isEqualTo("S1");
                    assertThat(task.getParagraphIndex()).isEqualTo(1);
                    assertThat(task.getExcerpt()).contains("明显增长");
                    assertThat(task.getCurrentText()).contains("明显增长");
                    assertThat(task.getExpectedFix()).contains("降级");
                });
        assertThat(run.getArtifacts())
                .filteredOn(artifact -> artifact.getType() == ArtifactType.REVIEW_FINDINGS)
                .last()
                .satisfies(artifact -> assertThat(artifact.getContent()).contains("结构化新增问题：1", "llm_overclaim"));
    }

    @Test
    void reviewerDowngradesNewHighFindingsDuringRepairVerification() {
        LlmClient reviewerLlm = new LlmClient() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String complete(com.aiinsight.llm.ChatRequest request) {
                return """
                        {
                          "summary": "发现一个非上一轮修复范围的新问题。",
                          "findings": [
                            {
                              "severity": "HIGH",
                              "category": "llm_overclaim",
                              "message": "报告新增了一个未验证的增长判断。",
                              "recommendation": "将增长判断降级为待验证。",
                              "claimId": "C-NEW-1",
                              "citationKey": "S1",
                              "paragraphIndex": 2,
                              "excerpt": "新增增长判断"
                            }
                          ]
                        }
                        """;
            }
        };
        AnalysisRun run = new AnalysisRun();
        AnalysisClaim claim = new AnalysisClaim();
        claim.setId("C-NEW-1");
        claim.setType(ClaimType.OPPORTUNITY);
        claim.setContent("已有证据支持基础判断。");
        claim.setEvidenceIds(List.of("S1"));
        run.getClaims().add(claim);
        run.getEvidenceSources().add(new EvidenceSource(
                "S1",
                "Product page",
                "https://example.test/product",
                "official_site",
                "FETCHED",
                "LIVE_FETCHED",
                "已有证据支持基础判断。",
                "已有证据支持基础判断。",
                "test evidence"
        ));
        run.addArtifact(new AnalysisArtifact(
                ArtifactType.REPORT_DRAFT,
                "draft",
                "已有证据支持基础判断 [S1]。",
                List.of("S1")
        ));
        run.getReviewDecision().setAction(ReviewAction.REVISE_REPORT);
        run.getReviewDecision().setTargetAgent(AgentName.WRITER);
        ReviewRepairTask previousTask = new ReviewRepairTask();
        previousTask.setTargetAgent(AgentName.WRITER);
        previousTask.setCategory("citation_missing");
        previousTask.setParagraphIndex(1);
        previousTask.setExcerpt("上一轮缺 citation 的旧段落");
        previousTask.setInstruction("修复上一轮旧段落 citation。");
        run.getReviewDecision().getRepairTasks().add(previousTask);

        new ReviewerNode(new CitationCoverageEvaluator(), reviewerLlm, new FallbackReviewReportFactory()).execute(run);

        assertThat(run.getReviewDecision().getAction()).isEqualTo(ReviewAction.PASS);
        assertThat(run.getReviewFindings())
                .anySatisfy(finding -> {
                    assertThat(finding.getCategory()).isEqualTo("llm_overclaim");
                    assertThat(finding.getSeverity()).isEqualTo(ReviewSeverity.MEDIUM);
                    assertThat(finding.getMessage()).contains("返工验证模式");
                });
        assertThat(run.getRecommendedActions())
                .anyMatch(action -> action.contains("返工验证模式") && action.contains("降为质量提醒"));
    }

    @Test
    void reviewerKeepsHighFindingWhenRepairTaskLocatorStillMatches() {
        LlmClient reviewerLlm = new LlmClient() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String complete(com.aiinsight.llm.ChatRequest request) {
                return """
                        {
                          "summary": "The previous paragraph still needs repair.",
                          "findings": [
                            {
                              "severity": "HIGH",
                              "category": "llm_overclaim",
                              "message": "The rewritten paragraph still overstates the evidence.",
                              "recommendation": "Downgrade the claim to a verified observation.",
                              "citationKey": "S1",
                              "paragraphIndex": 1,
                              "excerpt": "rewritten paragraph still overstates the evidence"
                            }
                          ]
                        }
                        """;
            }
        };
        AnalysisRun run = new AnalysisRun();
        run.getEvidenceSources().add(new EvidenceSource(
                "S1",
                "Product page",
                "https://example.test/product",
                "official_site",
                "FETCHED",
                "LIVE_FETCHED",
                "Evidence-backed baseline.",
                "Evidence-backed baseline.",
                "test evidence"
        ));
        run.addArtifact(new AnalysisArtifact(
                ArtifactType.REPORT_DRAFT,
                "draft",
                "Evidence-backed baseline [S1].",
                List.of("S1")
        ));
        run.getReviewDecision().setAction(ReviewAction.REVISE_REPORT);
        run.getReviewDecision().setTargetAgent(AgentName.WRITER);
        ReviewRepairTask previousTask = new ReviewRepairTask();
        previousTask.setTargetAgent(AgentName.WRITER);
        previousTask.setCategory("citation_missing");
        previousTask.setParagraphIndex(1);
        previousTask.setExcerpt("old paragraph text before writer rewrite");
        previousTask.setInstruction("Repair paragraph 1.");
        run.getReviewDecision().getRepairTasks().add(previousTask);

        new ReviewerNode(new CitationCoverageEvaluator(), reviewerLlm, new FallbackReviewReportFactory()).execute(run);

        assertThat(run.getReviewDecision().getAction()).isEqualTo(ReviewAction.REVISE_REPORT);
        assertThat(run.getReviewFindings())
                .anySatisfy(finding -> {
                    assertThat(finding.getCategory()).isEqualTo("llm_overclaim");
                    assertThat(finding.getSeverity()).isEqualTo(ReviewSeverity.HIGH);
                    assertThat(finding.getMessage()).doesNotContain("返工验证模式");
                });
        assertThat(run.getRecommendedActions())
                .noneMatch(action -> action.contains("降为质量提醒"));
    }

    @Test
    void reviewerSanitizesLlmFindingLocationFields() {
        String longCategory = "llm_semantic_review_" + "x".repeat(140);
        String longCitation = "The affected source is [S1], see https://example.test/very/long/location/value";
        LlmClient reviewerLlm = new LlmClient() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String complete(com.aiinsight.llm.ChatRequest request) {
                return """
                        {
                          "summary": "found one issue",
                          "findings": [
                            {
                              "severity": "MEDIUM",
                              "category": "%s",
                              "message": "claim needs a narrower wording",
                              "recommendation": "rewrite the claim as an assumption",
                              "claimId": "C-SAFE-1",
                              "citationKey": "%s",
                              "excerpt": "pricing conclusion"
                            }
                          ]
                        }
                        """.formatted(longCategory, longCitation);
            }
        };
        AnalysisRun run = new AnalysisRun();
        AnalysisClaim claim = new AnalysisClaim();
        claim.setId("C-SAFE-1");
        claim.setType(ClaimType.OPPORTUNITY);
        claim.setContent("pricing conclusion");
        claim.setEvidenceIds(List.of("S1"));
        run.getClaims().add(claim);
        run.getEvidenceSources().add(new EvidenceSource(
                "S1",
                "Pricing page",
                "https://example.test/pricing",
                "pricing_page",
                "FETCHED",
                "LIVE_FETCHED",
                "pricing conclusion",
                "pricing conclusion",
                "test evidence"
        ));
        run.addArtifact(new AnalysisArtifact(
                ArtifactType.REPORT_DRAFT,
                "draft",
                "pricing conclusion [S1]",
                List.of("S1")
        ));

        new ReviewerNode(new CitationCoverageEvaluator(), reviewerLlm, new FallbackReviewReportFactory()).execute(run);

        assertThat(run.getReviewFindings())
                .anySatisfy(finding -> {
                    assertThat(finding.getCategory()).startsWith("llm_semantic_review_");
                    assertThat(finding.getCategory()).hasSizeLessThanOrEqualTo(128);
                    assertThat(finding.getCitationKey()).isEqualTo("S1");
                });
        assertThat(run.getReviewDecision().getAction()).isEqualTo(ReviewAction.PASS);
        assertThat(run.getReviewDecision().getReason()).contains("质量提醒", "不阻断");
        assertThat(run.getReviewDecision().getRepairScopeSummary()).contains("无需自动修复");
    }

    @Test
    void reviewerDowngradesUnlocatedHighLlmFindingsToQualityReminder() {
        LlmClient reviewerLlm = new LlmClient() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String complete(com.aiinsight.llm.ChatRequest request) {
                return """
                        {
                          "summary": "发现一个没有定位的问题",
                          "findings": [
                            {
                              "severity": "HIGH",
                              "category": "unsupported_recommendation",
                              "message": "报告建议可能过强。",
                              "recommendation": "请人工复核。"
                            }
                          ]
                        }
                        """;
            }
        };
        AnalysisRun run = new AnalysisRun();
        run.getEvidenceSources().add(new EvidenceSource(
                "S1",
                "Official report",
                "https://example.test/report",
                "official_site",
                "FETCHED",
                "LIVE_FETCHED",
                "报告建议需要人工确认。",
                "报告建议需要人工确认。",
                "test evidence"
        ));
        run.addArtifact(new AnalysisArtifact(
                ArtifactType.REPORT_DRAFT,
                "draft",
                "报告建议需要人工确认 [S1]。",
                List.of("S1")
        ));

        new ReviewerNode(new CitationCoverageEvaluator(), reviewerLlm, new FallbackReviewReportFactory()).execute(run);

        assertThat(run.getReviewFindings())
                .anySatisfy(finding -> {
                    assertThat(finding.getCategory()).isEqualTo("unsupported_recommendation");
                    assertThat(finding.getSeverity()).isEqualTo(ReviewSeverity.MEDIUM);
                });
        assertThat(run.getReviewDecision().getAction()).isEqualTo(ReviewAction.PASS);
    }

    @Test
    void reviewerKeepsSourceQualityHighAsNonBlockingReminder() {
        LlmClient reviewerLlm = new LlmClient() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String complete(com.aiinsight.llm.ChatRequest request) {
                return """
                        {
                          "summary": "来源质量需要人工复核。",
                          "findings": [
                            {
                              "severity": "HIGH",
                              "category": "marketing_only_source",
                              "message": "S1 带有营销页特征，不适合单独支撑关键结论。",
                              "recommendation": "补充官方文档或一手访谈后再提升置信度。",
                              "citationKey": "S1"
                            }
                          ]
                        }
                        """;
            }
        };
        AnalysisRun run = new AnalysisRun();
        run.getEvidenceSources().add(new EvidenceSource(
                "S1",
                "Official product page",
                "https://example.test/product",
                "official_site",
                "FETCHED",
                "LIVE_FETCHED",
                "机会点是优化价格策略。",
                "机会点是优化价格策略。",
                "test evidence"
        ));
        run.addArtifact(new AnalysisArtifact(
                ArtifactType.REPORT_DRAFT,
                "draft",
                "机会点是优化价格策略 [S1]。",
                List.of("S1")
        ));

        new ReviewerNode(new CitationCoverageEvaluator(), reviewerLlm, new FallbackReviewReportFactory()).execute(run);

        assertThat(run.getReviewFindings())
                .anySatisfy(finding -> {
                    assertThat(finding.getCategory()).isEqualTo("marketing_only_source");
                    assertThat(finding.getSeverity()).isEqualTo(ReviewSeverity.HIGH);
                });
        assertThat(run.getReviewDecision().getAction()).isEqualTo(ReviewAction.PASS);
        assertThat(run.getReviewDecision().getBlockingFindingIds()).isEmpty();
        assertThat(run.getReviewDecision().getRepairTasks()).isEmpty();
        assertThat(run.getReviewDecision().getRepairScopeSummary()).contains("无需自动修复");
    }

    @Test
    void reviewerRunsParallelSemanticChecksAndRoutesHighRiskClaimIssues() {
        StringBuffer promptCapture = new StringBuffer();
        LlmClient reviewerLlm = new LlmClient() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String complete(com.aiinsight.llm.ChatRequest request) {
                String prompt = request.getMessages().get(1).getContent();
                promptCapture.append(prompt).append("\n---\n");
                if (prompt.contains("claim-evidence Reviewer")) {
                    return """
                            {
                              "summary": "发现 claim 与证据语义不一致。",
                              "findings": [
                                {
                                  "severity": "HIGH",
                                  "category": "claim_evidence_mismatch",
                                  "message": "claim 讨论权限优势，但证据只覆盖价格信息。",
                                  "recommendation": "重新绑定权限证据，或将该结论降级为待验证。",
                                  "claimId": "C-SEM-1",
                                  "citationKey": "S1",
                                  "excerpt": "Notion 在权限治理上形成明显优势"
                                }
                              ]
                            }
                            """;
                }
                if (prompt.contains("source-quality Reviewer")) {
                    return """
                            {
                              "summary": "发现一个抓取失败来源需要人工复核。",
                              "findings": [
                                {
                                  "severity": "MEDIUM",
                                  "category": "fetch_failed_source",
                                  "message": "S2 抓取失败，不能直接支撑最终结论。",
                                  "recommendation": "补充可访问原文或替代公开来源。",
                                  "citationKey": "S2"
                                }
                              ]
                            }
                            """;
                }
                return "{\"summary\":\"未发现额外问题\",\"findings\":[]}";
            }
        };
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
        run.getEvidenceSources().add(new EvidenceSource(
                "S2",
                "Security whitepaper",
                "https://example.test/security",
                "security",
                "FETCH_FAILED",
                "FETCH_FAILED",
                "无法抓取安全白皮书正文。",
                "",
                "fetch failed"
        ));
        AnalysisClaim claim = new AnalysisClaim();
        claim.setId("C-SEM-1");
        claim.setType(ClaimType.COMPARISON);
        claim.setContent("Notion 在权限治理上形成明显优势。");
        claim.setEvidenceIds(List.of("S1"));
        run.getClaims().add(claim);
        run.addArtifact(new AnalysisArtifact(
                ArtifactType.REPORT_DRAFT,
                "draft",
                "Notion 在权限治理上形成明显优势 [S1]。",
                List.of("S1")
        ));

        new ReviewerNode(new CitationCoverageEvaluator(), reviewerLlm, new FallbackReviewReportFactory()).execute(run);

        assertThat(promptCapture.toString())
                .contains("claim-evidence Reviewer", "report-overclaim Reviewer", "schema-consistency Reviewer",
                        "source-quality Reviewer", "report-actionability Reviewer");
        assertThat(run.getReviewFindings())
                .anySatisfy(finding -> {
                    assertThat(finding.getCategory()).isEqualTo("claim_evidence_mismatch");
                    assertThat(finding.getClaimId()).isEqualTo("C-SEM-1");
                    assertThat(finding.getCitationKey()).isEqualTo("S1");
                })
                .anySatisfy(finding -> assertThat(finding.getCategory()).isEqualTo("fetch_failed_source"));
        assertThat(run.getReviewDecision().getAction()).isEqualTo(ReviewAction.REWORK_ANALYSIS);
        assertThat(run.getReviewDecision().getTargetAgent()).isEqualTo(AgentName.ANALYST);
        assertThat(run.getReviewDecision().getAffectedClaimIds()).containsExactly("C-SEM-1");
        assertThat(run.getReviewDecision().getFindingCategories()).contains("claim_evidence_mismatch");
        assertThat(run.getReviewDecision().getRepairTasks())
                .anySatisfy(task -> {
                    assertThat(task.getTargetAgent()).isEqualTo(AgentName.ANALYST);
                    assertThat(task.getAction()).isEqualTo("REPAIR_CLAIM_EVIDENCE");
                    assertThat(task.getClaimId()).isEqualTo("C-SEM-1");
                    assertThat(task.getCitationKey()).isEqualTo("S1");
                });
        assertThat(run.getArtifacts())
                .filteredOn(artifact -> artifact.getType() == ArtifactType.REVIEW_FINDINGS)
                .last()
                .satisfies(artifact -> assertThat(artifact.getContent()).contains("LLM 并发语义质检", "结构化新增问题：2", "定向修复计划", "结构化修复任务"));
    }

    @Test
    void reviewerRoutesPoorActionabilityReportsBackToWriter() {
        LlmClient reviewerLlm = new LlmClient() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String complete(com.aiinsight.llm.ChatRequest request) {
                String prompt = request.getMessages().get(1).getContent();
                if (prompt.contains("report-actionability Reviewer")) {
                    return """
                            {
                              "summary": "report is too generic",
                              "findings": [
                                {
                                  "severity": "HIGH",
                                  "category": "report_quality_insufficient",
                                  "message": "report lacks a decision summary and prioritized recommendation",
                                  "recommendation": "rewrite the opening into a conclusion-first recommendation with tradeoffs",
                                  "paragraphIndex": 1,
                                  "excerpt": "Cursor and Claude Code both have AI capabilities"
                                }
                              ]
                            }
                            """;
                }
                return "{\"summary\":\"ok\",\"findings\":[]}";
            }
        };
        AnalysisRun run = new AnalysisRun();
        AnalysisClaim claim = new AnalysisClaim();
        claim.setId("C-ACT-1");
        claim.setType(ClaimType.RECOMMENDATION);
        claim.setContent("Cursor and Claude Code should be compared by workflow fit.");
        claim.setEvidenceIds(List.of("S1"));
        run.getClaims().add(claim);
        run.getEvidenceSources().add(new EvidenceSource(
                "S1",
                "Official comparison",
                "https://example.test/comparison",
                "official_site",
                "FETCHED",
                "LIVE_FETCHED",
                "Cursor and Claude Code both have AI capabilities.",
                "Cursor and Claude Code both have AI capabilities.",
                "test evidence"
        ));
        run.addArtifact(new AnalysisArtifact(
                ArtifactType.REPORT_DRAFT,
                "draft",
                "Cursor and Claude Code both have AI capabilities [S1].",
                List.of("S1")
        ));

        new ReviewerNode(new CitationCoverageEvaluator(), reviewerLlm, new FallbackReviewReportFactory()).execute(run);

        assertThat(run.getReviewDecision().getAction()).isEqualTo(ReviewAction.REVISE_REPORT);
        assertThat(run.getReviewDecision().getTargetAgent()).isEqualTo(AgentName.WRITER);
        assertThat(run.getReviewDecision().getFindingCategories()).contains("report_quality_insufficient");
        assertThat(run.getReviewDecision().getRepairTasks())
                .anySatisfy(task -> {
                    assertThat(task.getTargetAgent()).isEqualTo(AgentName.WRITER);
                    assertThat(task.getInstruction()).contains("paragraph=1");
                });
    }

    @Test
    void finalizerKeepsFinalReportCleanAndLeavesFindingsInReviewArtifact() {
        AnalysisRun run = new AnalysisRun();
        run.addArtifact(new AnalysisArtifact(
                ArtifactType.REPORT_DRAFT,
                "draft",
                "# 报告草稿\n\n机会点是优化价格策略 [S1]。",
                List.of("S1")
        ));
        run.addArtifact(new AnalysisArtifact(
                ArtifactType.REVIEW_FINDINGS,
                "review",
                "claim_missing_evidence：结构化结论未绑定证据。",
                List.of()
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
        run.getReviewDecision().setRepairScopeSummary("目标 Agent=ANALYST；阻断问题=1；Claim=C-1；证据类型=pricing_page。");
        run.getReviewDecision().setRepairInstructions(List.of(
                "Analyst 优先修复 affectedClaimIds 指向的结构化结论，避免重写无关 claims。"
        ));

        new FinalizerNode().execute(run);

        assertThat(run.getArtifacts())
                .filteredOn(artifact -> artifact.getType() == ArtifactType.FINAL_REPORT)
                .last()
                .satisfies(artifact -> assertThat(artifact.getContent())
                        .contains("内部复核项")
                        .doesNotContain("Reviewer 当前决策")
                        .doesNotContain("ReviewDecision")
                        .doesNotContain("定向修复计划")
                        .doesNotContain("Analyst 优先修复 affectedClaimIds")
                        .doesNotContain("claim_missing_evidence")
                        .doesNotContain("结构化结论未绑定证据。")
                );
        assertThat(run.getArtifacts())
                .filteredOn(artifact -> artifact.getType() == ArtifactType.FINALIZATION_NOTE)
                .last()
                .satisfies(artifact -> assertThat(artifact.getContent())
                        .contains("Reviewer 当前决策为 `REWORK_ANALYSIS`")
                        .contains("定向修复计划")
                        .contains("C-1")
                        .contains("pricing_page"));
        assertThat(run.getArtifacts())
                .filteredOn(artifact -> artifact.getType() == ArtifactType.REVIEW_FINDINGS)
                .last()
                .satisfies(artifact -> assertThat(artifact.getContent()).contains("claim_missing_evidence"));
        assertThat(run.getRecommendedActions())
                .anyMatch(action -> action.contains("HIGH"));
    }

    @Test
    void finalizerExplainsWhenReworkLimitStopsOpenReviewDecision() {
        AnalysisRun run = new AnalysisRun();
        run.addArtifact(new AnalysisArtifact(
                ArtifactType.REPORT_DRAFT,
                "draft",
                "# 报告草稿\n\n定价页证据仍不足 [S1]。",
                List.of("S1")
        ));
        ReviewFinding finding = new ReviewFinding(
                ReviewSeverity.HIGH,
                "citation_missing",
                "缺少官方定价页证据。",
                "补采 pricing_page。"
        );
        finding.setClaimId("C-1");
        run.getReviewFindings().add(finding);
        run.getReviewDecision().setAction(ReviewAction.RECOLLECT_EVIDENCE);
        run.getReviewDecision().setTargetAgent(AgentName.RESEARCHER);
        run.getReviewDecision().setRequiredEvidenceTypes(List.of("pricing_page"));
        run.getReviewDecision().setAffectedClaimIds(List.of("C-1"));
        run.getReviewDecision().setRepairScopeSummary("目标 Agent=RESEARCHER；证据类型=pricing_page。");
        WorkflowTransition transition = new WorkflowTransition(
                "REVIEW_GATE",
                AgentName.FINALIZER.name(),
                "finish",
                ReviewAction.RECOLLECT_EVIDENCE,
                "仍需补证，但已达到返工上限。",
                1
        );
        transition.setTrigger("auto-review-gate");
        run.getWorkflowTransitions().add(transition);

        new FinalizerNode().execute(run);

        assertThat(run.getArtifacts())
                .filteredOn(artifact -> artifact.getType() == ArtifactType.FINAL_REPORT)
                .last()
                .satisfies(artifact -> assertThat(artifact.getContent())
                        .contains("内部复核项")
                        .doesNotContain("自动返工上限说明")
                        .doesNotContain("ReviewDecision"));
        assertThat(run.getArtifacts())
                .filteredOn(artifact -> artifact.getType() == ArtifactType.FINALIZATION_NOTE)
                .last()
                .satisfies(artifact -> assertThat(artifact.getContent())
                        .contains("自动返工上限说明")
                        .contains("最后一次 ReviewDecision 仍为 `RECOLLECT_EVIDENCE`")
                        .contains("不得被理解为“质检已通过”")
                        .contains("已执行自动返工次数：0"));
        assertThat(run.getRecommendedActions())
                .anyMatch(action -> action.contains("自动返工")
                        && action.contains("ReviewDecision"));
    }

    private String invokeRepairPlanBlock(Object node, AnalysisRun run) throws Exception {
        java.lang.reflect.Method method = node.getClass().getDeclaredMethod("repairPlanBlock", AnalysisRun.class);
        method.setAccessible(true);
        return (String) method.invoke(node, run);
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
        return newService(new TaskExecutorAdapter(Runnable::run));
    }

    private AnalysisWorkflowService newService(TaskExecutorAdapter taskExecutor) {
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
        SourceCollectionService sourceCollectionService = new SourceCollectionService(fetchUsefulPages(), fakeSearchProvider());
        FallbackClarificationDraftFactory fallbackClarificationDraftFactory = new FallbackClarificationDraftFactory();
        ClarifierNode clarifierNode = new ClarifierNode(noopLlmClient, new ObjectMapper(), fallbackClarificationDraftFactory);
        AnalysisLangGraphWorkflow graphWorkflow = new AnalysisLangGraphWorkflow(
                List.of(
                        clarifierNode,
                        new FinalizerNode(),
                        new WriterNode(noopLlmClient, new FallbackReportDraftFactory()),
                        new ReviewerNode(new CitationCoverageEvaluator(), noopLlmClient, new FallbackReviewReportFactory()),
                        new AnalystNode(noopLlmClient, new ObjectMapper(), new FallbackAnalysisDraftFactory()),
                        new ExtractorNode(noopLlmClient, new FallbackExtractionFactory()),
                        researcherNode(sourceCollectionService, noopLlmClient)
                ),
                nodeExecutor,
                repository,
                eventBroker
        );
        return new AnalysisWorkflowService(
                repository,
                new AnalysisRequestNormalizer(),
                eventBroker,
                taskExecutor,
                graphWorkflow,
                nodeExecutor,
                clarifierNode,
                fallbackClarificationDraftFactory,
                new EvidenceRetrievalService(),
                sourceCollectionService,
                new EvidenceChunkService(),
                EvidenceEmbeddingService.disabled()
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

    private WebPageFetchService fetchUsefulPages() {
        return new WebPageFetchService() {
            @Override
            public FetchedPage fetch(String url) {
                return FetchedPage.success(
                        url,
                        "Useful page for " + url,
                        """
                                This official product documentation page describes pricing, reviews, enterprise controls,
                                collaboration workflows, permission governance, AI features, release notes, support options,
                                customer feedback, integration details, and product positioning for competitive analysis.
                                The content is intentionally long enough to be treated as a useful fetched search result.
                                """,
                        "robots.txt checked: allowed for public fetch."
                );
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
                EvidenceEmbeddingService.disabled(),
                llmClient,
                new LlmSearchQueryPlanner(llmClient, new ObjectMapper()),
                new ObjectMapper(),
                new FallbackResearchPlanFactory(),
                new InterviewInsightExtractor()
        );
    }

    private ResearchPlan usableResearchPlan() {
        ResearchPlan plan = new ResearchPlan();
        plan.setObjective("复用既有调研计划");
        plan.getEvidenceGaps().add("pricing_page");
        plan.getSearchQueries().add("旧 query");
        plan.getPublicSourceTasks().add(new ResearchTask(
                "pricing_page",
                "Cursor 定价页",
                "补充官方定价证据",
                "needs_collection"
        ));
        Questionnaire questionnaire = new Questionnaire();
        questionnaire.setTitle("既有问卷");
        questionnaire.setTargetRespondents("评估过 Cursor 的开发者");
        questionnaire.getQuestions().add(new SurveyQuestion("定价", "价格是否清晰？", List.of("清晰", "不清晰")));
        questionnaire.getQuestions().add(new SurveyQuestion("团队", "团队协作是否有帮助？", List.of("有", "没有")));
        questionnaire.getQuestions().add(new SurveyQuestion("采购", "采购顾虑是什么？", List.of("价格", "安全")));
        plan.setQuestionnaire(questionnaire);
        InterviewGuide guide = new InterviewGuide();
        guide.setTitle("既有访谈");
        guide.setTargetRoles(List.of("开发者"));
        guide.setQuestions(List.of("如何使用 Cursor？", "定价是否影响采购？", "团队协作如何？"));
        plan.setInterviewGuide(guide);
        return plan;
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
        public boolean existsById(UUID id) {
            return runs.containsKey(id);
        }

        @Override
        public Collection<AnalysisRun> findAll() {
            return runs.values();
        }

        @Override
        public Collection<AnalysisRunSummary> findSummaries() {
            return runs.values().stream().map(AnalysisWorkflowServiceTest::summaryOf).toList();
        }

        @Override
        public void deleteById(UUID id) {
            runs.remove(id);
        }
    }

    private static class CopyingTestAnalysisRunRepository implements AnalysisRunRepository {

        private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        private final ConcurrentMap<UUID, String> runs = new ConcurrentHashMap<>();

        @Override
        public AnalysisRun save(AnalysisRun run) {
            run.touch();
            runs.put(run.getId(), serialize(run));
            return copy(run);
        }

        @Override
        public Optional<AnalysisRun> findById(UUID id) {
            return Optional.ofNullable(runs.get(id)).map(this::deserialize);
        }

        @Override
        public boolean existsById(UUID id) {
            return runs.containsKey(id);
        }

        @Override
        public Collection<AnalysisRun> findAll() {
            return runs.values().stream().map(this::deserialize).toList();
        }

        @Override
        public Collection<AnalysisRunSummary> findSummaries() {
            return findAll().stream().map(AnalysisWorkflowServiceTest::summaryOf).toList();
        }

        @Override
        public void deleteById(UUID id) {
            runs.remove(id);
        }

        private AnalysisRun copy(AnalysisRun run) {
            return deserialize(serialize(run));
        }

        private String serialize(AnalysisRun run) {
            try {
                return objectMapper.writeValueAsString(run);
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        }

        private AnalysisRun deserialize(String payload) {
            try {
                return objectMapper.readValue(payload, AnalysisRun.class);
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        }
    }

    private static AnalysisRunSummary summaryOf(AnalysisRun run) {
        var scope = run.getClarificationDraft() != null ? run.getClarificationDraft() : null;
        var requirement = run.getRequirement();
        return new AnalysisRunSummary(
                run.getId(),
                run.getStatus(),
                scope != null && scope.getIndustry() != null ? scope.getIndustry() : requirement == null ? null : requirement.getIndustry(),
                scope != null && !scope.getCompetitors().isEmpty() ? scope.getCompetitors() : requirement == null ? List.of() : requirement.getCompetitors(),
                scope != null && scope.getOutputGoal() != null ? scope.getOutputGoal() : requirement == null ? null : requirement.getOutputGoal(),
                requirement == null ? null : requirement.getOriginalPrompt(),
                run.getEvidenceSources().size(),
                run.getArtifacts().size(),
                run.getReviewFindings().size(),
                run.getSteps().size(),
                run.getCreatedAt(),
                run.getUpdatedAt()
        );
    }
}
