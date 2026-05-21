package com.aiinsight.service;

import com.aiinsight.api.CreateAnalysisRunRequest;
import com.aiinsight.domain.AnalysisStatus;
import com.aiinsight.llm.LlmClient;
import com.aiinsight.repository.AnalysisRunRepository;
import com.aiinsight.workflow.node.AnalystNode;
import com.aiinsight.workflow.node.ClarifierNode;
import com.aiinsight.workflow.node.ExtractorNode;
import com.aiinsight.workflow.node.ResearcherNode;
import com.aiinsight.workflow.node.ReviewerNode;
import com.aiinsight.workflow.node.RevisionNode;
import com.aiinsight.workflow.node.WriterNode;
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
        assertThat(finished.getSteps()).hasSize(7);
        assertThat(finished.getEvidenceSources()).hasSize(2);
        assertThat(finished.getArtifacts()).anyMatch(artifact -> artifact.getTitle().equals("可溯源竞品分析报告"));
        assertThat(finished.getReviewFindings()).isNotEmpty();
    }
}
