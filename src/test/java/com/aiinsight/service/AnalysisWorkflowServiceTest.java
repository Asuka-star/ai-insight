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
import com.aiinsight.model.enums.StepStatus;
import com.aiinsight.model.run.AnalysisArtifact;
import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.model.schema.AnalysisClaim;
import com.aiinsight.repository.AnalysisRunRepository;
import com.aiinsight.workflow.AnalysisLangGraphWorkflow;
import com.aiinsight.workflow.WorkflowNodeExecutor;
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
        assertThat(finished.getSteps()).hasSize(12);
        assertThat(finished.getTraces()).hasSize(12);
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
        assertThat(finished.getWorkflowTransitions()).hasSize(2);
        assertThat(finished.getWorkflowTransitions().get(0).getRoute()).isEqualTo("recollect");
        assertThat(finished.getWorkflowTransitions().get(0).getTargetNode()).isEqualTo(AgentName.RESEARCHER.name());
        assertThat(finished.getWorkflowTransitions().get(1).getRoute()).isEqualTo("finish");
        assertThat(finished.getWorkflowTransitions().get(1).getTargetNode()).isEqualTo(AgentName.REVISION.name());
        assertThat(finished.getSteps())
                .filteredOn(step -> step.getAgentName() == AgentName.RESEARCHER)
                .hasSize(2);
        assertThat(finished.getSteps())
                .filteredOn(step -> step.getAgentName() == AgentName.REVIEWER)
                .hasSize(2);
        assertThat(finished.getEvidenceSources()).hasSize(6);
        assertThat(finished.getEvidenceChunks()).hasSize(6);
        assertThat(service.retrieveEvidence(finished.getId(), "Notion", 3)).isNotEmpty();
        assertThat(finished.getResearchPackage().getSources()).hasSize(6);
        assertThat(finished.getResearchPackage().getMissingEvidenceTypes()).isEmpty();
        assertThat(finished.getCompetitorProfiles()).hasSize(2);
        assertThat(finished.getCompetitorProfiles())
                .allSatisfy(profile -> {
                    assertThat(profile.getFeatureTree().getRoots()).isNotEmpty();
                    assertThat(profile.getPricingModel().getEvidenceIds()).isNotEmpty();
                    assertThat(profile.getPricingModel().getPlans()).isNotEmpty();
                    assertThat(profile.getPersonas()).isNotEmpty();
                    assertThat(profile.getEvidenceIds()).isNotEmpty();
                });
        assertThat(finished.getClaims()).hasSize(3);
        assertThat(finished.getClaims())
                .extracting(claim -> claim.getType())
                .containsExactlyInAnyOrder(ClaimType.COMPARISON, ClaimType.OPPORTUNITY, ClaimType.RISK);
        assertThat(finished.getClaims()).allSatisfy(claim -> assertThat(claim.getEvidenceIds()).isNotEmpty());
        assertThat(finished.getReviewDecision().getAction()).isEqualTo(ReviewAction.PASS);
        assertThat(finished.getArtifacts())
                .filteredOn(artifact -> artifact.getType() == ArtifactType.REVIEW_FINDINGS)
                .hasSize(2);
        assertThat(finished.getArtifacts())
                .filteredOn(artifact -> artifact.getType() == ArtifactType.REPORT_DRAFT)
                .extracting(artifact -> artifact.getVersion())
                .containsExactly(1, 2);
        assertThat(finished.getArtifacts())
                .filteredOn(artifact -> artifact.getType() == ArtifactType.SOURCE_LIST)
                .extracting(artifact -> artifact.getVersion())
                .containsExactly(1, 2);
        assertThat(finished.getArtifacts()).anyMatch(artifact -> artifact.getType() == ArtifactType.SWOT_ANALYSIS);
        assertThat(finished.getArtifacts()).anyMatch(artifact -> artifact.getType() == ArtifactType.FINAL_REPORT);
        assertThat(finished.getReviewFindings()).isEmpty();
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
                .containsExactly(1, 2, 3);
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

        new ReviewerNode(new CitationCoverageEvaluator(), noopLlmClient).execute(run);

        assertThat(run.getReviewFindings()).hasSize(1);
        assertThat(run.getReviewFindings().get(0).getArtifactId()).isEqualTo(draft.getId());
        assertThat(run.getReviewFindings().get(0).getClaimId()).isEqualTo(claim.getId());
        assertThat(run.getReviewFindings().get(0).getExcerpt()).contains("机会点");
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
        AnalysisLangGraphWorkflow graphWorkflow = new AnalysisLangGraphWorkflow(
                List.of(
                        new RevisionNode(),
                        new WriterNode(noopLlmClient),
                        new ReviewerNode(new CitationCoverageEvaluator(), noopLlmClient),
                        new AnalystNode(noopLlmClient),
                        new ExtractorNode(noopLlmClient),
                        new ResearcherNode(new SourceCollectionService(new WebPageFetchService()), new EvidenceChunkService()),
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
                new SourceCollectionService(new WebPageFetchService()),
                new EvidenceChunkService()
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
