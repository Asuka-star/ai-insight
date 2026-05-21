package com.aiinsight.service;

import com.aiinsight.exception.RunNotFoundException;
import com.aiinsight.dto.CreateAnalysisRunRequest;
import com.aiinsight.model.enums.AgentName;
import com.aiinsight.model.run.EvidenceChunk;
import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.model.run.AgentTrace;
import com.aiinsight.model.enums.AnalysisStatus;
import com.aiinsight.repository.AnalysisRunRepository;
import com.aiinsight.workflow.AnalysisLangGraphWorkflow;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.UUID;

@Service
public class AnalysisWorkflowService {

    private final AnalysisRunRepository repository;
    private final AnalysisRequestNormalizer normalizer;
    private final AnalysisEventBroker eventBroker;
    private final AsyncTaskExecutor analysisTaskExecutor;
    private final AnalysisLangGraphWorkflow graphWorkflow;
    private final EvidenceRetrievalService evidenceRetrievalService;

    public AnalysisWorkflowService(AnalysisRunRepository repository,
                                   AnalysisRequestNormalizer normalizer,
                                   AnalysisEventBroker eventBroker,
                                   AsyncTaskExecutor analysisTaskExecutor,
                                   AnalysisLangGraphWorkflow graphWorkflow,
                                   EvidenceRetrievalService evidenceRetrievalService) {
        this.repository = repository;
        this.normalizer = normalizer;
        this.eventBroker = eventBroker;
        this.analysisTaskExecutor = analysisTaskExecutor;
        this.graphWorkflow = graphWorkflow;
        this.evidenceRetrievalService = evidenceRetrievalService;
    }

    public AnalysisRun start(CreateAnalysisRunRequest request) {
        AnalysisRun run = new AnalysisRun(normalizer.normalize(request));
        run.setStatus(AnalysisStatus.PENDING);
        repository.save(run);
        eventBroker.publish(run, "run_created", "Analysis run created");
        // HTTP 创建请求立即返回，真实执行放到后台线程，前端通过 SSE 观察进度。
        analysisTaskExecutor.execute(() -> executePipeline(run.getId()));
        return run;
    }

    public AnalysisRun get(UUID runId) {
        return repository.findById(runId).orElseThrow(() -> new RunNotFoundException(runId));
    }

    public Collection<AnalysisRun> list() {
        return repository.findAll();
    }

    public Collection<AgentTrace> traces(UUID runId) {
        return get(runId).getTraces();
    }

    public Collection<EvidenceChunk> retrieveEvidence(UUID runId, String query, Integer topK) {
        return evidenceRetrievalService.retrieve(get(runId), query, topK);
    }

    public String workflowMermaid() {
        return graphWorkflow.mermaid();
    }

    public AnalysisRun rerunAgent(UUID runId, AgentName agentName) {
        // 当前版本重跑单节点但不清理旧产物，方便对比 rerun 前后的输出。
        // 后续可按 artifact version 做更精细的替换策略。
        AnalysisRun run = graphWorkflow.rerunAgent(runId, agentName);
        eventBroker.publish(run, "agent_rerun_completed", agentName + " rerun completed");
        return run;
    }

    private void executePipeline(UUID runId) {
        AnalysisRun run = get(runId);
        run.setStatus(AnalysisStatus.RUNNING);
        repository.save(run);
        eventBroker.publish(run, "run_started", "Analysis workflow started");
        try {
            graphWorkflow.execute(runId);
            run = get(runId);
            run.setStatus(AnalysisStatus.SUCCEEDED);
            repository.save(run);
            eventBroker.publish(run, "run_succeeded", "Analysis workflow succeeded");
        } catch (RuntimeException ex) {
            run = repository.findById(runId).orElse(run);
            run.setStatus(AnalysisStatus.FAILED);
            run.setErrorMessage(ex.getMessage());
            repository.save(run);
            eventBroker.publish(run, "run_failed", ex.getMessage());
        }
    }
}
