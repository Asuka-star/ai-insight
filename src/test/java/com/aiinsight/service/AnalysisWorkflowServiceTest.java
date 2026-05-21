package com.aiinsight.service;

import com.aiinsight.dto.CreateAnalysisRunRequest;
import com.aiinsight.model.AgentName;
import com.aiinsight.model.AnalysisStatus;
import com.aiinsight.model.ArtifactType;
import com.aiinsight.model.ClaimType;
import com.aiinsight.model.ReviewAction;
import com.aiinsight.llm.LlmClient;
import com.aiinsight.repository.AnalysisRunRepository;
import com.aiinsight.agent.node.AnalystNode;
import com.aiinsight.agent.node.ClarifierNode;
import com.aiinsight.agent.node.ExtractorNode;
import com.aiinsight.agent.node.ResearcherNode;
import com.aiinsight.agent.node.ReviewerNode;
import com.aiinsight.agent.node.RevisionNode;
import com.aiinsight.agent.node.WriterNode;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.support.TaskExecutorAdapter;

import java.util.List;

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
        AnalysisRunRepository repository = new AnalysisRunRepository();
        AnalysisEventBroker eventBroker = new AnalysisEventBroker();
        AnalysisWorkflowService service = new AnalysisWorkflowService(
                repository,
                new AnalysisRequestNormalizer(),
                eventBroker,
                new TaskExecutorAdapter(Runnable::run),
                List.of(
                        new RevisionNode(),
                        new WriterNode(noopLlmClient),
                        new ReviewerNode(new CitationCoverageEvaluator(), noopLlmClient),
                        new AnalystNode(),
                        new ExtractorNode(),
                        new ResearcherNode(),
                        new ClarifierNode()
                )
        );
        CreateAnalysisRunRequest request = new CreateAnalysisRunRequest();
        request.setPrompt("分析 Notion 和飞书文档在 AI 协作文档方向的竞品机会");

        var run = service.start(request);
        var finished = service.get(run.getId());

        assertThat(finished.getStatus()).isEqualTo(AnalysisStatus.SUCCEEDED);
        assertThat(finished.getSteps()).hasSize(12);
        assertThat(finished.getTraces()).hasSize(12);
        assertThat(finished.getSteps())
                .filteredOn(step -> step.getAgentName() == AgentName.RESEARCHER)
                .hasSize(2);
        assertThat(finished.getSteps())
                .filteredOn(step -> step.getAgentName() == AgentName.REVIEWER)
                .hasSize(2);
        assertThat(finished.getEvidenceSources()).hasSize(6);
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
}
