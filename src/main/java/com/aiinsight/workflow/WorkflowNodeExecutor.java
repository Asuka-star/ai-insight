package com.aiinsight.workflow;

import com.aiinsight.agent.AgentNode;
import com.aiinsight.exception.RunNotFoundException;
import com.aiinsight.model.run.AgentStep;
import com.aiinsight.model.run.AgentTrace;
import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.repository.AnalysisRunRepository;
import com.aiinsight.service.AnalysisEventBroker;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class WorkflowNodeExecutor {

    private final AnalysisRunRepository repository;
    private final AnalysisEventBroker eventBroker;

    public AnalysisRun executeNode(UUID runId, AgentNode node, String inputSummary) {
        AnalysisRun run = repository.findById(runId).orElseThrow(() -> new RunNotFoundException(runId));
        long startedAt = System.currentTimeMillis();
        AgentStep step = new AgentStep(node.name(), node.title());
        step.start(inputSummary);
        run.getSteps().add(step);
        repository.save(run);
        eventBroker.publish(run, "agent_started", node.name() + " started");
        try {
            // Agent 直接修改 run 聚合，执行器统一负责生命周期、Trace 和事件。
            AnalysisRun updatedRun = node.execute(run);
            if (updatedRun != null) {
                run = updatedRun;
            }
            step.succeed(node.name() + " produced updated run state");
            run.getTraces().add(trace(node, inputSummary, step.getOutputSummary(), "SUCCEEDED", startedAt));
            repository.save(run);
            eventBroker.publish(run, "agent_succeeded", node.name() + " succeeded");
            pauseForReadableEvents();
            return run;
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
