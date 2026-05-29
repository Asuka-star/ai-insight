package com.aiinsight.workflow;

import com.aiinsight.agent.AgentNode;
import com.aiinsight.exception.RunNotFoundException;
import com.aiinsight.model.enums.AgentName;
import com.aiinsight.model.enums.AnalysisStatus;
import com.aiinsight.model.enums.StepStatus;
import com.aiinsight.model.review.ReviewDecision;
import com.aiinsight.model.run.AgentStep;
import com.aiinsight.model.run.AgentTrace;
import com.aiinsight.model.run.AnalysisRequirement;
import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.model.schema.ResearchPackage;
import com.aiinsight.model.schema.ResearchPlan;
import com.aiinsight.observability.AgentTraceContext;
import com.aiinsight.repository.AnalysisRunRepository;
import com.aiinsight.service.AnalysisEventBroker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
// 统一包住所有 Agent 的执行生命周期。AgentNode 只负责修改 AnalysisRun 业务状态；
// step、trace、SSE、日志、取消检查和异常落库都集中在这里，方便前端做一致的执行回放。
public class WorkflowNodeExecutor {

    private final AnalysisRunRepository repository;
    private final AnalysisEventBroker eventBroker;

    public AnalysisRun executeNode(UUID runId, AgentNode node, String inputSummary) {
        AnalysisRun run = repository.findById(runId).orElseThrow(() -> new RunNotFoundException(runId));
        ensureNotCancelled(run);
        long startedAt = System.currentTimeMillis();

        String effectiveInputSummary = buildInputSummary(run, node, inputSummary);
        AgentStep step = new AgentStep(node.name(), node.title());
        step.start(effectiveInputSummary);
        AgentTrace trace = traceStarted(node, step, effectiveInputSummary);
        // LLM 客户端和 fallback 工厂通过 ThreadLocal 写入 prompt、原始输出和 token 信息；
        // executeNode 结束时再把这些观测数据合并进当前 step 对应的 AgentTrace。
        AgentTraceContext.start(trace);
        run.getSteps().add(step);
        repository.save(run);
        eventBroker.publish(run, "agent_started", node.name() + " started");
        log.info("Agent node started: runId={}, agent={}, stepId={}, inputSummary={}",
                runId,
                node.name(),
                step.getId(),
                effectiveInputSummary);
        try {
            AnalysisRun updatedRun = node.execute(run);
            if (updatedRun != null) {
                run = updatedRun;
            }
            ensureNotCancelled(runId);
            step.succeed(buildOutputSummary(run, node));
            completeTrace(trace, step, run, "SUCCEEDED", startedAt);
            addTraceIfAbsent(run, trace);
            repository.save(run);
            eventBroker.publish(run, "agent_succeeded", node.name() + " succeeded");
            log.info("Agent node completed: runId={}, agent={}, stepId={}, status={}, fallbackUsed={}, modelName={}, latencyMs={}, evidenceSources={}, claims={}, artifacts={}, findings={}",
                    run.getId(),
                    node.name(),
                    step.getId(),
                    step.getStatus(),
                    trace.getFallbackUsed(),
                    trace.getModelName(),
                    trace.getLatencyMs(),
                    run.getEvidenceSources().size(),
                    run.getClaims().size(),
                    run.getArtifacts().size(),
                    run.getReviewFindings().size());
            pauseForReadableEvents();
            return run;
        } catch (CancellationException ex) {
            log.info("Agent node stopped because run was cancelled: runId={}, agent={}, stepId={}",
                    runId,
                    node.name(),
                    step.getId());
            throw ex;
        } catch (RuntimeException ex) {
            step.fail(ex.getMessage());
            AgentTraceContext.recordError(ex);
            completeTrace(trace, step, run, "FAILED", startedAt);
            trace.setErrorMessage(ex.getMessage());
            addTraceIfAbsent(run, trace);
            repository.save(run);
            eventBroker.publish(run, "agent_failed", node.name() + " failed: " + ex.getMessage());
            log.error("Agent node failed: runId={}, agent={}, stepId={}, fallbackUsed={}, modelName={}, latencyMs={}, exceptionType={}, message={}",
                    run.getId(),
                    node.name(),
                    step.getId(),
                    trace.getFallbackUsed(),
                    trace.getModelName(),
                    trace.getLatencyMs(),
                    ex.getClass().getName(),
                    ex.getMessage(),
                    ex);
            throw ex;
        } finally {
            AgentTraceContext.clear();
        }
    }

    private void ensureNotCancelled(UUID runId) {
        AnalysisRun latest = repository.findById(runId).orElseThrow(() -> new RunNotFoundException(runId));
        ensureNotCancelled(latest);
    }

    private void ensureNotCancelled(AnalysisRun run) {
        if (run.getStatus() == AnalysisStatus.CANCELLED) {
            throw new CancellationException("Analysis workflow cancelled: " + run.getId());
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

    private void addTraceIfAbsent(AnalysisRun run, AgentTrace trace) {
        boolean exists = run.getTraces().stream()
                .anyMatch(existing -> existing.getId().equals(trace.getId()));
        if (!exists) {
            run.getTraces().add(trace);
        }
    }

    private String buildInputSummary(AnalysisRun run, AgentNode node, String routeSummary) {
        String prefix = routePrefix(routeSummary);
        AnalysisRequirement requirement = run.getRequirement();
        AgentName agentName = node.name();
        return switch (agentName) {
            case CLARIFIER -> prefix + "澄清原始需求：%s".formatted(
                    truncate(textOrDefault(requirement == null ? null : requirement.getOriginalPrompt(), "未填写原始需求"), 90)
            );
            case RESEARCHER -> prefix + "采集公开资料：竞品=%s，指定URL=%d，已有来源=%d".formatted(
                    listPreview(requirement == null ? List.of() : requirement.getCompetitors(), 5),
                    size(requirement == null ? null : requirement.getSourceUrls()),
                    run.getEvidenceSources().size()
            );
            case EXTRACTOR -> prefix + "抽取结构化画像：待处理来源=%d，已有竞品画像=%d".formatted(
                    run.getEvidenceSources().size(),
                    run.getCompetitorProfiles().size()
            );
            case ANALYST -> prefix + "生成分析结论：竞品画像=%d，证据来源=%d，已有结论=%d".formatted(
                    run.getCompetitorProfiles().size(),
                    run.getEvidenceSources().size(),
                    run.getClaims().size()
            );
            case WRITER -> prefix + "撰写报告草稿：结论=%d，证据来源=%d，已有产物=%d".formatted(
                    run.getClaims().size(),
                    run.getEvidenceSources().size(),
                    run.getArtifacts().size()
            );
            case REVIEWER -> prefix + "复核报告质量：结论=%d，证据来源=%d，待检查产物=%d".formatted(
                    run.getClaims().size(),
                    run.getEvidenceSources().size(),
                    run.getArtifacts().size()
            );
            case FINALIZER -> prefix + "生成最终封版报告：复核问题=%d，复核动作=%s".formatted(
                    run.getReviewFindings().size(),
                    reviewAction(run)
            );
        };
    }

    private String buildOutputSummary(AnalysisRun run, AgentNode node) {
        ResearchPackage researchPackage = run.getResearchPackage();
        ResearchPlan researchPlan = researchPackage == null ? null : researchPackage.getResearchPlan();
        return switch (node.name()) {
            case CLARIFIER -> "范围已澄清：行业=%s，竞品=%s，分析维度=%s，指定URL=%d".formatted(
                    textOrDefault(run.getClarificationDraft().getIndustry(), "未指定"),
                    listPreview(run.getClarificationDraft().getCompetitors(), 5),
                    listPreview(run.getClarificationDraft().getDimensions(), 4),
                    size(run.getClarificationDraft().getSourceUrls())
            );
            case RESEARCHER -> "资料采集完成：有效来源=%d，检索任务=%d，证据缺口=%s".formatted(
                    run.getEvidenceSources().size(),
                    researchPlan == null ? 0 : researchPlan.getPublicSourceTasks().size(),
                    listPreview(researchPackage == null ? List.of() : researchPackage.getMissingEvidenceTypes(), 4)
            );
            case EXTRACTOR -> "结构化抽取完成：竞品画像=%d，访谈洞察=%d".formatted(
                    run.getCompetitorProfiles().size(),
                    researchPackage == null ? 0 : researchPackage.getInterviewInsights().size()
            );
            case ANALYST -> "分析完成：结论=%d，产物=%d，覆盖竞品=%s".formatted(
                    run.getClaims().size(),
                    run.getArtifacts().size(),
                    listPreview(run.getCompetitorProfiles().stream()
                            .map(profile -> textOrDefault(profile.getProductName(), profile.getCompanyName()))
                            .toList(), 5)
            );
            case WRITER -> "报告生成完成：产物=%d，引用结论=%d，证据来源=%d".formatted(
                    run.getArtifacts().size(),
                    run.getClaims().size(),
                    run.getEvidenceSources().size()
            );
            case REVIEWER -> "复核完成：问题=%d，处理动作=%s，原因=%s".formatted(
                    run.getReviewFindings().size(),
                    reviewAction(run),
                    truncate(textOrDefault(reviewDecision(run).getReason(), "未记录原因"), 60)
            );
            case FINALIZER -> "最终封版完成：产物=%d，遗留复核问题=%d，建议动作=%d".formatted(
                    run.getArtifacts().size(),
                    run.getReviewFindings().size(),
                    run.getRecommendedActions().size()
            );
        };
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
                        reviewAction(run)
                );
    }

    private String routePrefix(String routeSummary) {
        if (routeSummary == null || routeSummary.isBlank()) {
            return "";
        }
        if (routeSummary.contains("Manual rerun")) {
            return "手动重跑：";
        }
        if (routeSummary.contains("复核") || routeSummary.contains("重跑")) {
            return routeSummary + "：";
        }
        return "";
    }

    private ReviewDecision reviewDecision(AnalysisRun run) {
        return run.getReviewDecision() == null ? new ReviewDecision() : run.getReviewDecision();
    }

    private String reviewAction(AnalysisRun run) {
        return String.valueOf(reviewDecision(run).getAction());
    }

    private String listPreview(List<String> values, int max) {
        if (values == null || values.isEmpty()) {
            return "未指定";
        }
        List<String> cleaned = values.stream()
                .filter(value -> value != null && !value.isBlank())
                .toList();
        if (cleaned.isEmpty()) {
            return "未指定";
        }
        String preview = cleaned.stream()
                .limit(max)
                .collect(Collectors.joining("、"));
        int remaining = cleaned.size() - Math.min(cleaned.size(), max);
        if (remaining > 0) {
            return preview + " 等" + cleaned.size() + "项";
        }
        return preview;
    }

    private String textOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private int size(List<?> values) {
        return values == null ? 0 : values.size();
    }

    private String truncate(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, Math.max(0, maxChars - 1)) + "…";
    }

    private void pauseForReadableEvents() {
        try {
            Thread.sleep(120);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Analysis workflow interrupted", ex);
        }
    }
}
