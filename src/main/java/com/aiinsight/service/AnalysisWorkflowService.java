package com.aiinsight.service;

import com.aiinsight.api.CreateAnalysisRunRequest;
import com.aiinsight.domain.AgentName;
import com.aiinsight.domain.AgentStep;
import com.aiinsight.domain.AnalysisRun;
import com.aiinsight.domain.AnalysisStatus;
import com.aiinsight.repository.AnalysisRunRepository;
import com.aiinsight.workflow.AgentNode;
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
            // 原型阶段先用确定顺序串起 Agent，保证运行态和产物链路可演示。
            // 真正的反馈闭环会在 Reviewer 之后加入条件边和回退节点。
            for (AgentNode node : pipeline) {
                executeNode(run, node, "Input from previous Agent state");
                repository.save(run);
                pauseForReadableEvents();
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

    private void executeNode(AnalysisRun run, AgentNode node, String inputSummary) {
        AgentStep step = new AgentStep(node.name(), node.title());
        step.start(inputSummary);
        run.getSteps().add(step);
        repository.save(run);
        eventBroker.publish(run, "agent_started", node.name() + " started");
        try {
            // Agent 直接修改 run 聚合，服务层只负责记录生命周期和事件。
            node.execute(run);
            step.succeed(node.name() + " produced updated run state");
            repository.save(run);
            eventBroker.publish(run, "agent_succeeded", node.name() + " succeeded");
        } catch (RuntimeException ex) {
            step.fail(ex.getMessage());
            repository.save(run);
            eventBroker.publish(run, "agent_failed", node.name() + " failed: " + ex.getMessage());
            throw ex;
        }
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
