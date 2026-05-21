package com.aiinsight.workflow;

import com.aiinsight.agent.AgentNode;
import com.aiinsight.exception.RunNotFoundException;
import com.aiinsight.model.enums.StepStatus;
import com.aiinsight.model.run.AgentStep;
import com.aiinsight.model.run.AgentTrace;
import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.observability.AgentTraceContext;
import com.aiinsight.repository.AnalysisRunRepository;
import com.aiinsight.service.AnalysisEventBroker;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
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
        AgentTrace trace = traceStarted(node, step, inputSummary);
        AgentTraceContext.start(trace);
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
            completeTrace(trace, step, run, "SUCCEEDED", startedAt);
            run.getTraces().add(trace);
            repository.save(run);
            eventBroker.publish(run, "agent_succeeded", node.name() + " succeeded");
            pauseForReadableEvents();
            return run;
        } catch (RuntimeException ex) {
            step.fail(ex.getMessage());
            AgentTraceContext.recordError(ex);
            completeTrace(trace, step, run, "FAILED", startedAt);
            trace.setErrorMessage(ex.getMessage());
            run.getTraces().add(trace);
            repository.save(run);
            eventBroker.publish(run, "agent_failed", node.name() + " failed: " + ex.getMessage());
            throw ex;
        } finally {
            AgentTraceContext.clear();
        }
    }

    private AgentTrace traceStarted(AgentNode node, AgentStep step, String inputSummary) {
        AgentTrace trace = new AgentTrace();
        trace.setStepId(step.getId());
        trace.setAgentName(node.name());
        trace.setStatus(StepStatus.RUNNING);
        trace.setInputSnapshot(inputSummary);
        trace.setStartedAt(step.getStartedAt());
        return trace;
    }

    private void completeTrace(AgentTrace trace,
                               AgentStep step,
                               AnalysisRun run,
                               String decisionSummary,
                               long startedAt) {
        trace.setStatus(step.getStatus());
        trace.setDecisionSummary(decisionSummary);
        if (trace.getOutputSnapshot() == null || trace.getOutputSnapshot().isBlank()) {
            trace.setOutputSnapshot(stateSnapshot(run));
        }
        if (trace.getCompletedAt() == null) {
            trace.setCompletedAt(step.getCompletedAt() == null ? Instant.now() : step.getCompletedAt());
        }
        trace.setLatencyMs(System.currentTimeMillis() - startedAt);
    }

    private String stateSnapshot(AnalysisRun run) {
        return "status=%s, evidence=%d, competitors=%d, claims=%d, artifacts=%d, findings=%d, reviewAction=%s"
                .formatted(
                        run.getStatus(),
                        run.getEvidenceSources().size(),
                        run.getCompetitorProfiles().size(),
                        run.getClaims().size(),
                        run.getArtifacts().size(),
                        run.getReviewFindings().size(),
                        run.getReviewDecision().getAction()
                );
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
