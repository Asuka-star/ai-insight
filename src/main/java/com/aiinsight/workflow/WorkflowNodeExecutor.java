package com.aiinsight.workflow;

import com.aiinsight.agent.AgentNode;
import com.aiinsight.dto.ResearchCollectionEvent;
import com.aiinsight.exception.RunNotFoundException;
import com.aiinsight.model.enums.AgentName;
import com.aiinsight.model.enums.AnalysisStatus;
import com.aiinsight.model.enums.ArtifactType;
import com.aiinsight.model.enums.ReviewAction;
import com.aiinsight.model.enums.StepStatus;
import com.aiinsight.model.review.ReviewDecision;
import com.aiinsight.model.run.AgentStep;
import com.aiinsight.model.run.AgentTrace;
import com.aiinsight.model.run.AnalysisRequirement;
import com.aiinsight.model.run.AnalysisArtifact;
import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.model.schema.AnalysisClaim;
import com.aiinsight.model.schema.CompetitorFactSet;
import com.aiinsight.model.schema.CompetitorProfile;
import com.aiinsight.model.schema.ResearchPackage;
import com.aiinsight.model.schema.ResearchPlan;
import com.aiinsight.observability.AgentTraceContext;
import com.aiinsight.repository.AnalysisRunRepository;
import com.aiinsight.service.AnalysisEventBroker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.stream.Collectors;

import static com.aiinsight.util.AgentUtils.hasText;
import static com.aiinsight.util.AgentUtils.textOrDefault;

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
        addTraceIfAbsent(run, trace);
        repository.save(run);
        RepairSnapshot repairSnapshot = RepairSnapshot.capture(run, node.name());
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
            recordRepairDelta(run, node, repairSnapshot);
            ensureNotCancelled(runId);
            step.succeed(buildOutputSummary(run, node));
            completeTrace(trace, step, run, "SUCCEEDED", startedAt);
            addTraceIfAbsent(run, trace);
            run = mergeClarifierResultIfUserMovedOn(runId, node, run, step, trace);
            repository.save(run);
            eventBroker.publish(run, "agent_succeeded", node.name() + " succeeded");
            publishResearchCollectionEvent(run, node);
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
            AnalysisRun latest = repository.findById(runId).orElse(run);
            markCancelled(latest, step, trace, ex, startedAt);
            repository.save(latest);
            eventBroker.publish(latest, "agent_cancelled", node.name() + " cancelled");
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

    private void markCancelled(AnalysisRun run,
                               AgentStep step,
                               AgentTrace trace,
                               CancellationException ex,
                               long startedAt) {
        String issue = ex.getMessage() == null || ex.getMessage().isBlank()
                ? "Analysis workflow cancelled"
                : ex.getMessage();
        step.cancel(issue);
        completeTrace(trace, step, run, "CANCELLED", startedAt);
        trace.setErrorMessage(issue);
        replaceStep(run, step);
        replaceTrace(run, trace);
    }

    private void replaceStep(AnalysisRun run, AgentStep step) {
        for (int i = 0; i < run.getSteps().size(); i++) {
            if (run.getSteps().get(i).getId().equals(step.getId())) {
                run.getSteps().set(i, step);
                return;
            }
        }
        run.getSteps().add(step);
    }

    private void replaceTrace(AnalysisRun run, AgentTrace trace) {
        for (int i = 0; i < run.getTraces().size(); i++) {
            if (run.getTraces().get(i).getId().equals(trace.getId())) {
                run.getTraces().set(i, trace);
                return;
            }
        }
        run.getTraces().add(trace);
    }

    private void recordRepairDelta(AnalysisRun run, AgentNode node, RepairSnapshot before) {
        if (before == null || !before.active()) {
            return;
        }
        RepairSnapshot after = RepairSnapshot.capture(run, node.name());
        String summary = repairDeltaSummary(node.name(), before, after);
        AgentTraceContext.recordProcessSummary(summary);
        log.info("Review repair delta: runId={}, agent={}, changed={}, before={}, after={}",
                run.getId(),
                node.name(),
                before.materiallyChanged(after),
                before.shortSummary(),
                after.shortSummary());
        if (!before.materiallyChanged(after)) {
            addRecommendedActionOnce(run, "Reviewer 打回后 " + node.name()
                    + " 返工没有产生实质变化；请检查 repairTasks 是否过宽、证据是否不足，或改为人工处理对应阻塞问题。");
        }
    }

    private String repairDeltaSummary(AgentName agentName, RepairSnapshot before, RepairSnapshot after) {
        boolean changed = before.materiallyChanged(after);
        return """
                Review repair delta:
                - agent=%s
                - changed=%s
                - before=%s
                - after=%s
                """.formatted(agentName, changed, before.shortSummary(), after.shortSummary());
    }

    private void addRecommendedActionOnce(AnalysisRun run, String action) {
        if (!run.getRecommendedActions().contains(action)) {
            run.getRecommendedActions().add(action);
        }
    }

    private AnalysisRun mergeClarifierResultIfUserMovedOn(UUID runId,
                                                          AgentNode node,
                                                          AnalysisRun completedRun,
                                                          AgentStep step,
                                                          AgentTrace trace) {
        if (node.name() != AgentName.CLARIFIER) {
            return completedRun;
        }
        AnalysisRun latest = repository.findById(runId).orElse(completedRun);
        if (!hasUserProgressAfterClarifierStarted(latest)) {
            return completedRun;
        }

        // Clarifier can run asynchronously after the draft is created. If the user has already confirmed
        // the scope, added evidence, or started the main workflow, do not save the older Clarifier snapshot.
        replaceStep(latest, step);
        replaceTrace(latest, trace);
        mergeMissingArtifacts(latest, completedRun);
        mergeMissingRecommendedActions(latest, completedRun);
        if (canStillApplyClarifierScope(latest)) {
            latest.setRequirement(completedRun.getRequirement());
            latest.setClarificationDraft(completedRun.getClarificationDraft());
        }
        log.info("Merged late Clarifier result without overwriting newer run state: runId={}, status={}, evidenceSources={}, steps={}",
                latest.getId(),
                latest.getStatus(),
                latest.getEvidenceSources().size(),
                latest.getSteps().size());
        return latest;
    }

    private boolean hasUserProgressAfterClarifierStarted(AnalysisRun latest) {
        return latest.getStatus() != AnalysisStatus.AWAITING_CONFIRMATION
                || latest.getSteps().stream().anyMatch(step -> step.getAgentName() != AgentName.CLARIFIER)
                || latest.getClarificationDraft() != null && latest.getClarificationDraft().isConfirmed()
                || !latest.getEvidenceSources().isEmpty()
                || !latest.getEvidenceChunks().isEmpty()
                || !latest.getUserProvidedEvidence().isEmpty()
                || !latest.getContextMessages().isEmpty();
    }

    private boolean canStillApplyClarifierScope(AnalysisRun latest) {
        return latest.getStatus() == AnalysisStatus.AWAITING_CONFIRMATION
                && latest.getSteps().stream().noneMatch(step -> step.getAgentName() != AgentName.CLARIFIER)
                && (latest.getClarificationDraft() == null || !latest.getClarificationDraft().isConfirmed());
    }

    private void mergeMissingArtifacts(AnalysisRun target, AnalysisRun source) {
        source.getArtifacts().stream()
                .filter(artifact -> target.getArtifacts().stream()
                        .noneMatch(existing -> existing.getId().equals(artifact.getId())))
                .forEach(target.getArtifacts()::add);
    }

    private void mergeMissingRecommendedActions(AnalysisRun target, AnalysisRun source) {
        source.getRecommendedActions().stream()
                .filter(action -> !target.getRecommendedActions().contains(action))
                .forEach(target.getRecommendedActions()::add);
    }

    private String buildInputSummary(AnalysisRun run, AgentNode node, String routeSummary) {
        String prefix = routePrefix(routeSummary);
        AnalysisRequirement requirement = run.getRequirement();
        AgentName agentName = node.name();
        return switch (agentName) {
            case CLARIFIER -> prefix + "澄清原始需求：%s".formatted(
                    textOrDefault(requirement == null ? null : requirement.getOriginalPrompt(), "未填写原始需求")
            );
            case RESEARCHER -> prefix + "采集公开资料：竞品=%s，指定URL=%s，已有来源=%d".formatted(
                    listAll(requirement == null ? List.of() : requirement.getCompetitors()),
                    listAll(requirement == null ? List.of() : requirement.getSourceUrls()),
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
        };
    }

    private String buildOutputSummary(AnalysisRun run, AgentNode node) {
        ResearchPackage researchPackage = run.getResearchPackage();
        ResearchPlan researchPlan = researchPackage == null ? null : researchPackage.getResearchPlan();
        return switch (node.name()) {
            case CLARIFIER -> "范围已澄清：行业=%s，竞品=%s，分析维度=%s，指定URL=%s".formatted(
                    textOrDefault(run.getClarificationDraft().getIndustry(), "未指定"),
                    listAll(run.getClarificationDraft().getCompetitors()),
                    listAll(run.getClarificationDraft().getDimensions()),
                    listAll(run.getClarificationDraft().getSourceUrls())
            );
            case RESEARCHER -> "资料采集完成：有效来源=%d，检索任务=%d，证据缺口=%s".formatted(
                    run.getEvidenceSources().size(),
                    researchPlan == null ? 0 : researchPlan.getPublicSourceTasks().size(),
                    listAll(researchPackage == null ? List.of() : researchPackage.getMissingEvidenceTypes())
            );
            case EXTRACTOR -> "结构化抽取完成：竞品画像=%d，访谈洞察=%d".formatted(
                    run.getCompetitorProfiles().size(),
                    researchPackage == null ? 0 : researchPackage.getInterviewInsights().size()
            );
            case ANALYST -> "分析完成：结论=%d，产物=%d，覆盖竞品=%s".formatted(
                    run.getClaims().size(),
                    run.getArtifacts().size(),
                    listAll(run.getCompetitorProfiles().stream()
                            .map(profile -> textOrDefault(profile.getProductName(), profile.getCompanyName()))
                            .toList())
            );
            case WRITER -> "报告生成完成：产物=%d，引用结论=%d，证据来源=%d".formatted(
                    run.getArtifacts().size(),
                    run.getClaims().size(),
                    run.getEvidenceSources().size()
            );
            case REVIEWER -> "复核完成：问题=%d，处理动作=%s，原因=%s".formatted(
                    run.getReviewFindings().size(),
                    reviewAction(run),
                    textOrDefault(reviewDecision(run).getReason(), "未记录原因")
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
        if (hasText(step.getOutputSummary())) {
            trace.setOutputSnapshot(step.getOutputSummary());
        } else if (!hasText(trace.getOutputSnapshot())) {
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
        if (routeSummary.contains("Manual rerun") || routeSummary.contains("Manual cascade rerun")) {
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

    private String listAll(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "未指定";
        }
        String joined = values.stream()
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.joining("、"));
        return joined.isBlank() ? "未指定" : joined;
    }

    private void pauseForReadableEvents() {
        try {
            Thread.sleep(120);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("Skipped readable event pause because the workflow thread was interrupted.");
        }
    }

    private void publishResearchCollectionEvent(AnalysisRun run, AgentNode node) {
        if (run.getResearchPackage() == null || run.getResearchPackage().getResearchCollectionPlan() == null) {
            return;
        }
        if (node.name() == AgentName.RESEARCHER) {
            eventBroker.publishPayload(
                    run,
                    "research.collection.plan.updated",
                    ResearchCollectionEvent.of(run.getId(), "research.collection.plan.updated", "Research collection plan updated", run.getResearchPackage().getResearchCollectionPlan())
            );
        } else if (node.name() == AgentName.REVIEWER) {
            eventBroker.publishPayload(
                    run,
                    "research.repair.targets.updated",
                    ResearchCollectionEvent.of(run.getId(), "research.repair.targets.updated", "Research repair targets updated", run.getResearchPackage().getResearchCollectionPlan())
            );
        }
    }

    private record RepairSnapshot(
            AgentName agentName,
            boolean active,
            int evidenceSources,
            int claims,
            int artifacts,
            String claimsFingerprint,
            String reportFingerprint,
            String evidenceFingerprint,
            String profileFingerprint,
            String factFingerprint
    ) {

        static RepairSnapshot capture(AnalysisRun run, AgentName agentName) {
            boolean active = isRepairTarget(run, agentName);
            return new RepairSnapshot(
                    agentName,
                    active,
                    run.getEvidenceSources().size(),
                    run.getClaims().size(),
                    run.getArtifacts().size(),
                    fingerprint(claimsText(run)),
                    fingerprint(latestArtifactContent(run, ArtifactType.REPORT_DRAFT)),
                    fingerprint(run.getEvidenceSources().stream()
                            .map(source -> "%s|%s|%s|%s".formatted(
                                    source.getCitationKey(),
                                    source.getUrl(),
                                    source.getSourceQuality(),
                                    source.getCollectionStatus()))
                            .sorted()
                            .collect(Collectors.joining("\n"))),
                    fingerprint(run.getCompetitorProfiles().stream()
                            .map(RepairSnapshot::profileText)
                            .sorted()
                            .collect(Collectors.joining("\n"))),
                    fingerprint(run.getCompetitorFactSets().stream()
                            .map(RepairSnapshot::factSetText)
                            .sorted()
                            .collect(Collectors.joining("\n")))
            );
        }

        boolean materiallyChanged(RepairSnapshot after) {
            if (after == null) {
                return false;
            }
            return switch (agentName) {
                case RESEARCHER -> evidenceSources != after.evidenceSources
                        || !evidenceFingerprint.equals(after.evidenceFingerprint);
                case EXTRACTOR -> !profileFingerprint.equals(after.profileFingerprint)
                        || !factFingerprint.equals(after.factFingerprint);
                case ANALYST -> claims != after.claims
                        || !claimsFingerprint.equals(after.claimsFingerprint);
                case WRITER -> !reportFingerprint.equals(after.reportFingerprint);
                default -> true;
            };
        }

        String shortSummary() {
            return "evidence=%d, claims=%d, artifacts=%d, claimsFp=%s, reportFp=%s, evidenceFp=%s, profileFp=%s, factFp=%s"
                    .formatted(evidenceSources, claims, artifacts, claimsFingerprint, reportFingerprint,
                            evidenceFingerprint, profileFingerprint, factFingerprint);
        }

        private static boolean isRepairTarget(AnalysisRun run, AgentName agentName) {
            ReviewDecision decision = run.getReviewDecision();
            return decision != null
                    && decision.getAction() != ReviewAction.PASS
                    && decision.getTargetAgent() == agentName;
        }

        private static String claimsText(AnalysisRun run) {
            return run.getClaims().stream()
                    .map(RepairSnapshot::claimText)
                    .sorted()
                    .collect(Collectors.joining("\n"));
        }

        private static String claimText(AnalysisClaim claim) {
            return "%s|%s|%s|%s|%s".formatted(
                    claim.getType(),
                    claim.getConfidence(),
                    sortedText(claim.getCompetitorNames()),
                    sortedText(claim.getEvidenceIds()),
                    normalize(claim.getContent())
            );
        }

        private static String profileText(CompetitorProfile profile) {
            return "%s|%s|%s|%s|%s".formatted(
                    normalize(profile.getProductName()),
                    normalize(profile.getPositioning()),
                    sortedText(profile.getTargetUsers()),
                    sortedText(profile.getStrengths()),
                    sortedText(profile.getWeaknesses())
            );
        }

        private static String factSetText(CompetitorFactSet factSet) {
            return "%s|%s|%s".formatted(
                    normalize(factSet.getCompetitorName()),
                    factSet.getFacts().stream()
                            .map(fact -> "%s|%s|%s|%s".formatted(
                                    fact.getId(),
                                    fact.getFactType(),
                                    normalize(fact.getValue()),
                                    sortedText(fact.getEvidenceIds())))
                            .sorted()
                            .collect(Collectors.joining(";")),
                    factSet.getUnknowns().stream()
                            .map(unknown -> "%s|%s|%s".formatted(
                                    normalize(unknown.getField()),
                                    normalize(unknown.getReason()),
                                    sortedText(unknown.getNeededEvidenceTypes())))
                            .sorted()
                            .collect(Collectors.joining(";"))
            );
        }

        private static String latestArtifactContent(AnalysisRun run, ArtifactType type) {
            return run.getArtifacts().stream()
                    .filter(artifact -> artifact.getType() == type)
                    .max(Comparator.comparingInt(AnalysisArtifact::getVersion))
                    .map(AnalysisArtifact::getContent)
                    .orElse("");
        }

        private static String sortedText(List<String> values) {
            if (values == null || values.isEmpty()) {
                return "";
            }
            return values.stream()
                    .map(RepairSnapshot::normalize)
                    .sorted()
                    .collect(Collectors.joining(","));
        }

        private static String fingerprint(String value) {
            return Integer.toHexString(normalize(value).hashCode());
        }

        private static String normalize(String value) {
            return value == null ? "" : value.toLowerCase()
                    .replaceAll("\\s+", " ")
                    .trim();
        }
    }
}
