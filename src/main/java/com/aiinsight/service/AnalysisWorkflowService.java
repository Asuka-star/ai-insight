package com.aiinsight.service;

import com.aiinsight.exception.RunNotFoundException;
import com.aiinsight.dto.CreateAnalysisRunRequest;
import com.aiinsight.model.AgentName;
import com.aiinsight.model.AgentStep;
import com.aiinsight.model.AgentTrace;
import com.aiinsight.model.AnalysisRun;
import com.aiinsight.model.AnalysisStatus;
import com.aiinsight.model.ReviewAction;
import com.aiinsight.repository.AnalysisRunRepository;
import com.aiinsight.agent.AgentNode;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AnalysisWorkflowService {

    private static final int MAX_REVIEW_REWORK_ATTEMPTS = 1;

    private final AnalysisRunRepository repository;
    private final AnalysisRequestNormalizer normalizer;
    private final AnalysisEventBroker eventBroker;
    private final AsyncTaskExecutor analysisTaskExecutor;
    private final List<AgentNode> pipeline;
    private final Map<AgentName, AgentNode> nodesByName;

    public AnalysisWorkflowService(AnalysisRunRepository repository,
                                   AnalysisRequestNormalizer normalizer,
                                   AnalysisEventBroker eventBroker,
                                   AsyncTaskExecutor analysisTaskExecutor,
                                   List<AgentNode> nodes) {
        this.repository = repository;
        this.normalizer = normalizer;
        this.eventBroker = eventBroker;
        this.analysisTaskExecutor = analysisTaskExecutor;
        // 用 enum ordinal 固定原型阶段的执行顺序；升级 DAG 后这里会替换为 LangGraph4j 图定义。
        this.pipeline = nodes.stream()
                .sorted(Comparator.comparingInt(node -> node.name().ordinal()))
                .toList();
        // 单 Agent 重跑需要按角色名快速定位节点实现。
        this.nodesByName = new EnumMap<>(AgentName.class);
        this.pipeline.forEach(node -> this.nodesByName.put(node.name(), node));
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

    public AnalysisRun rerunAgent(UUID runId, AgentName agentName) {
        AnalysisRun run = get(runId);
        AgentNode node = nodesByName.get(agentName);
        if (node == null) {
            throw new IllegalArgumentException("Unsupported agent: " + agentName);
        }
        // 当前版本重跑单节点但不清理旧产物，方便对比 rerun 前后的输出。
        // 后续可按 artifact version 做更精细的替换策略。
        executeNode(run, node, "Manual rerun requested for " + agentName);
        repository.save(run);
        eventBroker.publish(run, "agent_rerun_completed", agentName + " rerun completed");
        return run;
    }

    private void executePipeline(UUID runId) {
        AnalysisRun run = get(runId);
        run.setStatus(AnalysisStatus.RUNNING);
        repository.save(run);
        eventBroker.publish(run, "run_started", "Analysis workflow started");
        try {
            int reworkAttempts = 0;
            // 原型阶段先用确定顺序串起 Agent；Reviewer 后根据结构化决策触发一次反馈重跑。
            for (AgentNode node : pipeline) {
                executeNode(run, node, "Input from previous Agent state");
                repository.save(run);
                pauseForReadableEvents();
                if (node.name() == AgentName.REVIEWER && shouldReworkFromReview(run, reworkAttempts)) {
                    reworkAttempts++;
                    executeReviewRework(run, reworkAttempts);
                }
            }
            run.setStatus(AnalysisStatus.SUCCEEDED);
            repository.save(run);
            eventBroker.publish(run, "run_succeeded", "Analysis workflow succeeded");
        } catch (RuntimeException ex) {
            run.setStatus(AnalysisStatus.FAILED);
            run.setErrorMessage(ex.getMessage());
            repository.save(run);
            eventBroker.publish(run, "run_failed", ex.getMessage());
        }
    }

    private boolean shouldReworkFromReview(AnalysisRun run, int reworkAttempts) {
        return reworkAttempts < MAX_REVIEW_REWORK_ATTEMPTS
                && run.getReviewDecision().getAction() == ReviewAction.RECOLLECT_EVIDENCE
                && run.getReviewDecision().getTargetAgent() != null;
    }

    private void executeReviewRework(AnalysisRun run, int attempt) {
        AgentName targetAgent = run.getReviewDecision().getTargetAgent();
        eventBroker.publish(run, "review_rework_started", "Reviewer requested rework from " + targetAgent);
        for (AgentNode node : pipeline) {
            if (node.name().ordinal() < targetAgent.ordinal() || node.name().ordinal() > AgentName.REVIEWER.ordinal()) {
                continue;
            }
            executeNode(run, node, "Review feedback rework attempt " + attempt);
            repository.save(run);
            pauseForReadableEvents();
        }
        eventBroker.publish(run, "review_rework_completed", "Review rework attempt " + attempt + " completed");
    }

    private void executeNode(AnalysisRun run, AgentNode node, String inputSummary) {
        long startedAt = System.currentTimeMillis();
        AgentStep step = new AgentStep(node.name(), node.title());
        step.start(inputSummary);
        run.getSteps().add(step);
        repository.save(run);
        eventBroker.publish(run, "agent_started", node.name() + " started");
        try {
            // Agent 直接修改 run 聚合，服务层只负责记录生命周期和事件。
            node.execute(run);
            step.succeed(node.name() + " produced updated run state");
            run.getTraces().add(trace(node, inputSummary, step.getOutputSummary(), "SUCCEEDED", startedAt));
            repository.save(run);
            eventBroker.publish(run, "agent_succeeded", node.name() + " succeeded");
        } catch (RuntimeException ex) {
            step.fail(ex.getMessage());
            run.getTraces().add(trace(node, inputSummary, ex.getMessage(), "FAILED", startedAt));
            repository.save(run);
            eventBroker.publish(run, "agent_failed", node.name() + " failed: " + ex.getMessage());
            throw ex;
        }
    }

    private AgentTrace trace(AgentNode node, String inputSummary, String outputSummary, String decisionSummary, long startedAt) {
        AgentTrace trace = new AgentTrace();
        trace.setAgentName(node.name());
        trace.setInputSnapshot(inputSummary);
        trace.setOutputSnapshot(outputSummary);
        trace.setDecisionSummary(decisionSummary);
        trace.setLatencyMs(System.currentTimeMillis() - startedAt);
        return trace;
    }

    private void pauseForReadableEvents() {
        try {
            // 给本地演示留出可感知的 SSE 节奏，避免所有节点瞬间刷完。
            Thread.sleep(120);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Analysis workflow interrupted", ex);
        }
    }
}
