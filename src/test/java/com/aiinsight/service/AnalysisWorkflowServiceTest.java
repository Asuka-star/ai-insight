package com.aiinsight.service;

import com.aiinsight.dto.CreateAnalysisRunRequest;
import com.aiinsight.model.enums.AgentName;
import com.aiinsight.model.enums.AnalysisStatus;
import com.aiinsight.model.enums.ArtifactType;
import com.aiinsight.model.enums.ClaimType;
import com.aiinsight.model.enums.ReviewAction;
import com.aiinsight.model.enums.StepStatus;
import com.aiinsight.llm.LlmClient;
import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.repository.AnalysisRunRepository;
import com.aiinsight.agent.node.AnalystNode;
import com.aiinsight.agent.node.ClarifierNode;
import com.aiinsight.agent.node.ExtractorNode;
import com.aiinsight.agent.node.ResearcherNode;
import com.aiinsight.agent.node.ReviewerNode;
import com.aiinsight.agent.node.RevisionNode;
import com.aiinsight.agent.node.WriterNode;
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

class AnalysisWorkflowServiceTest {

    @Test
    void executesFullWorkflowAndProducesFinalReport() {
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
                        new AnalystNode(),
                        new ExtractorNode(),
                        new ResearcherNode(new SourceCollectionService(new WebPageFetchService()), new EvidenceChunkService()),
                        new ClarifierNode()
                ),
                nodeExecutor,
                repository,
                eventBroker
        );
        assertThat(graphWorkflow.mermaid()).contains("REVIEW_GATE");
        AnalysisWorkflowService service = new AnalysisWorkflowService(
                repository,
                new AnalysisRequestNormalizer(),
                eventBroker,
                new TaskExecutorAdapter(Runnable::run),
                graphWorkflow,
                new EvidenceRetrievalService()
        );
        CreateAnalysisRunRequest request = new CreateAnalysisRunRequest();
        request.setPrompt("分析 Notion 和飞书文档在 AI 协作文档方向的竞品机会");

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
        assertThat(service.retrieveEvidence(finished.getId(), "价格 套餐", 3)).isNotEmpty();
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
        assertThat(finished.getArtifacts()).anyMatch(artifact -> artifact.getTitle().equals("可溯源竞品分析报告"));
        assertThat(finished.getReviewFindings()).isEmpty();
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
