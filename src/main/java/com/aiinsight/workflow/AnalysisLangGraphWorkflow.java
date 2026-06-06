package com.aiinsight.workflow;

import com.aiinsight.agent.AgentNode;
import com.aiinsight.exception.RunNotFoundException;
import com.aiinsight.model.enums.AgentName;
import com.aiinsight.model.enums.AnalysisStatus;
import com.aiinsight.model.enums.ReviewSeverity;
import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.model.enums.ReviewAction;
import com.aiinsight.model.review.ReviewDecision;
import com.aiinsight.model.review.ReviewFinding;
import com.aiinsight.model.review.ReviewRepairTask;
import com.aiinsight.model.run.WorkflowTransition;
import com.aiinsight.repository.AnalysisRunRepository;
import com.aiinsight.service.AnalysisEventBroker;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphDefinition;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncEdgeAction;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;

@Component
@Slf4j
public class AnalysisLangGraphWorkflow {

    static final String ROUTE_FINISH = "finish";
    private static final String ROUTE_RECOLLECT = "recollect";
    private static final String ROUTE_REEXTRACT = "reextract";
    private static final String ROUTE_REANALYZE = "reanalyze";
    private static final String ROUTE_REVISE = "revise";
    private static final String REVIEW_GATE = "REVIEW_GATE";
    private static final int MAX_MANUAL_RERUN_REPAIR_FINDINGS = 12;

    private final WorkflowNodeExecutor nodeExecutor;
    private final AnalysisRunRepository repository;
    private final AnalysisEventBroker eventBroker;
    private final AnalysisWorkflowProperties workflowProperties;
    private final Map<AgentName, AgentNode> nodesByName;
    private final CompiledGraph<AnalysisGraphState> graph;

    @Autowired
    public AnalysisLangGraphWorkflow(List<AgentNode> nodes,
                                     WorkflowNodeExecutor nodeExecutor,
                                     AnalysisRunRepository repository,
                                     AnalysisEventBroker eventBroker,
                                     AnalysisWorkflowProperties workflowProperties) {
        this.nodeExecutor = nodeExecutor;
        this.repository = repository;
        this.eventBroker = eventBroker;
        this.workflowProperties = workflowProperties == null ? new AnalysisWorkflowProperties() : workflowProperties;
        this.nodesByName = new EnumMap<>(AgentName.class);
        nodes.stream()
                .sorted(Comparator.comparingInt(node -> node.name().ordinal()))
                .forEach(node -> this.nodesByName.put(node.name(), node));
        this.graph = buildGraph();
    }

    public AnalysisLangGraphWorkflow(List<AgentNode> nodes,
                                     WorkflowNodeExecutor nodeExecutor,
                                     AnalysisRunRepository repository,
                                     AnalysisEventBroker eventBroker) {
        this(nodes, nodeExecutor, repository, eventBroker, new AnalysisWorkflowProperties());
    }

    public void execute(UUID runId) {
        graph.invoke(Map.of(
                AnalysisGraphState.RUN_ID, runId,
                AnalysisGraphState.REWORK_ATTEMPTS, 0,
                AnalysisGraphState.FEEDBACK_ROUTE, ROUTE_FINISH
        ));
    }

    public AnalysisRun rerunAgent(UUID runId, AgentName agentName) {
        AgentNode node = nodesByName.get(agentName);
        if (node == null) {
            throw new IllegalArgumentException("Unsupported agent: " + agentName);
        }
        prepareManualRerunReviewContext(runId, agentName);
        WorkflowNodeExecutor.RepairSnapshot cascadeRepairSnapshot = nodeExecutor.captureRepairSnapshot(runId, agentName);
        AnalysisRun run = null;
        try {
            // 手动重跑和质检打回都从目标 agent 开始顺序重放下游，避免只修上游却留下旧报告/旧复核结果。
            for (AgentNode cascadeNode : rerunCascade(agentName)) {
                run = nodeExecutor.executeNode(
                        runId,
                        cascadeNode,
                        "Manual cascade rerun requested from " + agentName
                );
                if (cascadeNode.name() == AgentName.REVIEWER) {
                    run = repository.findById(runId).orElseThrow(() -> new RunNotFoundException(runId));
                    recordTransition(run, ROUTE_FINISH, manualRerunAttempt(run), "manual-rerun-from-" + agentName);
                }
            }
            run = nodeExecutor.recordCascadeRepairDelta(runId, agentName, cascadeRepairSnapshot);
        } finally {
            clearManualRerunReviewContext(runId);
        }
        return repository.findById(runId).orElse(run);
    }

    private CompiledGraph<AnalysisGraphState> buildGraph() {
        try {
            StateGraph<AnalysisGraphState> stateGraph = new StateGraph<>(AnalysisGraphState::new);
            for (AgentNode node : nodesByName.values()) {
                if (node.name() == AgentName.CLARIFIER) {
                    continue;
                }
                // 每个 Agent 节点只关心 AnalysisRun 的业务变更；执行生命周期、Trace、SSE 事件
                // 统一交给 WorkflowNodeExecutor，避免节点内混入流程控制细节。
                stateGraph.addNode(node.name().name(), AsyncNodeAction.node_async(state -> {
                    nodeExecutor.executeNode(state.runId(), node, inputSummary(state));
                    return Map.of();
                }));
            }
            stateGraph.addNode(REVIEW_GATE, AsyncNodeAction.node_async(state -> routeFromReview(state)));

            stateGraph.addEdge(GraphDefinition.START, AgentName.RESEARCHER.name());
            stateGraph.addEdge(AgentName.RESEARCHER.name(), AgentName.EXTRACTOR.name());
            stateGraph.addEdge(AgentName.EXTRACTOR.name(), AgentName.ANALYST.name());
            stateGraph.addEdge(AgentName.ANALYST.name(), AgentName.WRITER.name());
            stateGraph.addEdge(AgentName.WRITER.name(), AgentName.REVIEWER.name());
            stateGraph.addEdge(AgentName.REVIEWER.name(), REVIEW_GATE);
            // Reviewer 不直接结束流程，而是把结构化 ReviewDecision 映射成条件边，形成可回放的打回闭环。
            stateGraph.addConditionalEdges(
                    REVIEW_GATE,
                    AsyncEdgeAction.edge_async(AnalysisGraphState::feedbackRoute),
                    Map.of(
                            ROUTE_RECOLLECT, AgentName.RESEARCHER.name(),
                            ROUTE_REEXTRACT, AgentName.EXTRACTOR.name(),
                            ROUTE_REANALYZE, AgentName.ANALYST.name(),
                            ROUTE_REVISE, AgentName.WRITER.name(),
                            // The old final-copy step has been removed: Writer owns the report body, Reviewer owns quality state.
                            // A finished review now terminates the graph directly instead of producing a copied final artifact.
                            ROUTE_FINISH, GraphDefinition.END
                    )
            );
            return stateGraph.compile();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to build LangGraph4j analysis workflow", ex);
        }
    }

    private Map<String, Object> routeFromReview(AnalysisGraphState state) {
        AnalysisRun run = repository.findById(state.runId()).orElseThrow(() -> new RunNotFoundException(state.runId()));
        if (run.getStatus() == AnalysisStatus.CANCELLED) {
            throw new CancellationException("Analysis workflow cancelled: " + run.getId());
        }
        int attempts = state.reworkAttempts();
        int maxAttempts = maxReviewReworkAttempts(run);
        boolean repeatedBlockers = hasRepeatedBlockingFindings(run);
        // REVIEW_GATE 是整个可信闭环的唯一分岔点：Reviewer 写入 ReviewDecision，
        // 这里把结构化 action 映射成 LangGraph 路由，并把选择持久化给前端回放。
        String route = nextRoute(run, attempts, repeatedBlockers);
        // 每一次条件边选择都落库，前端才能解释“Reviewer 为什么打回到某个 Agent”。
        WorkflowTransition transition = recordTransition(run, route, attempts, "auto-review-gate");
        ReviewDecision decision = run.getReviewDecision();
        if (ROUTE_FINISH.equals(route) && repeatedBlockers && decision != null && decision.getAction() != ReviewAction.PASS) {
            run.getRecommendedActions().add("Automatic review rework stopped because blocking findings remained unchanged after the previous repair attempt; please inspect unresolved findings manually.");
            repository.save(run);
        }
        log.info("Review gate decision: runId={}, route={}, target={}, action={}, attempt={}, maxAttempts={}, findings={}, blockingFindings={}, repeatedBlockers={}, resolutionStatus={}, finishReason={}",
                run.getId(),
                route,
                targetNodeFor(route),
                decision == null ? null : decision.getAction(),
                attempts,
                maxAttempts,
                run.getReviewFindings().size(),
                decision == null || decision.getBlockingFindingIds() == null ? 0 : decision.getBlockingFindingIds().size(),
                repeatedBlockers,
                transition.getResolutionStatus(),
                reviewFinishReason(run, attempts, maxAttempts, route, repeatedBlockers));
        if (!ROUTE_FINISH.equals(route)) {
            attempts++;
            eventBroker.publish(run, "review_rework_started", "复核 Agent 请求打回路径：" + route);
        } else if (attempts > 0) {
            eventBroker.publish(run, "review_rework_completed", "复核打回流程已完成");
        }
        return Map.of(
                AnalysisGraphState.REWORK_ATTEMPTS, attempts,
                AnalysisGraphState.FEEDBACK_ROUTE, route
        );
    }

    private WorkflowTransition recordTransition(AnalysisRun run, String route, int attempt, String trigger) {
        ReviewDecision decision = run.getReviewDecision();
        WorkflowTransition previous = latestTransition(run);
        WorkflowTransition transition = new WorkflowTransition(
                REVIEW_GATE,
                targetNodeFor(route),
                route,
                decision.getAction(),
                decision.getReason(),
                attempt
        );
        transition.setTrigger(trigger);
        transition.setBlockingFindingIds(new ArrayList<>(decision.getBlockingFindingIds() == null
                ? List.of()
                : decision.getBlockingFindingIds()));
        transition.setBlockingFindingSignatures(blockingFindingSignatures(run));
        transition.setResolvedFindingSignatures(resolvedFindingSignatures(previous, transition.getBlockingFindingSignatures()));
        transition.setUnresolvedFindingSignatures(unresolvedFindingSignatures(previous, transition.getBlockingFindingSignatures()));
        transition.setResolutionStatus(resolutionStatus(previous, transition));
        run.getWorkflowTransitions().add(transition);
        repository.save(run);
        return transition;
    }

    private String reviewFinishReason(AnalysisRun run, int attempts, int maxAttempts, String route, boolean repeatedBlockers) {
        if (!ROUTE_FINISH.equals(route)) {
            return "not_finished";
        }
        ReviewDecision decision = run.getReviewDecision();
        if (decision == null || decision.getAction() == ReviewAction.PASS) {
            return "review_passed";
        }
        if (repeatedBlockers) {
            return "unchanged_blockers_after_rework";
        }
        if (attempts >= maxAttempts) {
            return "max_rework_attempts_reached";
        }
        return "review_action_finished";
    }

    private String nextRoute(AnalysisRun run, int reworkAttempts, boolean repeatedBlockers) {
        // MVP 限制自动返工轮次，防止 Reviewer 和上游 Agent 在证据不足时无限循环。
        if (reworkAttempts >= maxReviewReworkAttempts(run)) {
            return ROUTE_FINISH;
        }
        if (reworkAttempts > 0 && repeatedBlockers) {
            return ROUTE_FINISH;
        }
        // ReviewAction 是后端和前端共同理解的返工协议：
        // 采集缺口回 Researcher，竞品分析问题回 Analyst，报告表达问题回 Writer。
        ReviewAction action = run.getReviewDecision().getAction();
        if (action == ReviewAction.RECOLLECT_EVIDENCE) {
            return ROUTE_RECOLLECT;
        }
        if (action == ReviewAction.REWORK_ANALYSIS) {
            if (run.getReviewDecision().getTargetAgent() == AgentName.EXTRACTOR) {
                return ROUTE_REEXTRACT;
            }
            return ROUTE_REANALYZE;
        }
        if (action == ReviewAction.REVISE_REPORT) {
            return ROUTE_REVISE;
        }
        return ROUTE_FINISH;
    }

    private int maxReviewReworkAttempts(AnalysisRun run) {
        Integer runValue = run.getMaxReviewReworkAttempts();
        if (runValue == null) {
            return workflowProperties.maxReviewReworkAttempts();
        }
        // run 级配置来自前端本次选择，优先于全局默认；再次夹紧范围，避免历史数据或接口调用绕过限制。
        return Math.max(0, Math.min(runValue, 2));
    }

    private String targetNodeFor(String route) {
        if (ROUTE_RECOLLECT.equals(route)) {
            return AgentName.RESEARCHER.name();
        }
        if (ROUTE_REEXTRACT.equals(route)) {
            return AgentName.EXTRACTOR.name();
        }
        if (ROUTE_REANALYZE.equals(route)) {
            return AgentName.ANALYST.name();
        }
        if (ROUTE_REVISE.equals(route)) {
            return AgentName.WRITER.name();
        }
        // Persist the finish target as END so workflow history reflects that no downstream agent ran.
        return GraphDefinition.END;
    }

    private void prepareManualRerunReviewContext(UUID runId, AgentName agentName) {
        if (agentName == AgentName.CLARIFIER || agentName == AgentName.REVIEWER) {
            return;
        }
        AnalysisRun run = repository.findById(runId).orElseThrow(() -> new RunNotFoundException(runId));
        if (run.getReviewFindings().isEmpty()) {
            return;
        }

        List<AgentName> repairScopeAgents = manualRerunRepairScope(agentName);
        List<ReviewFinding> findings = manualRerunFindings(run, repairScopeAgents);
        List<ReviewRepairTask> existingTasks = existingManualRerunTasks(run, repairScopeAgents);
        if (findings.isEmpty() && existingTasks.isEmpty()) {
            return;
        }

        ReviewDecision previous = run.getReviewDecision() == null ? new ReviewDecision() : run.getReviewDecision();
        ReviewDecision decision = new ReviewDecision();
        decision.setAction(actionForManualRerun(agentName));
        decision.setTargetAgent(agentName);
        decision.setReason("Manual rerun of " + agentName + " is carrying previous Reviewer findings.");
        decision.setAffectedClaimIds(findings.stream()
                .map(ReviewFinding::getClaimId)
                .filter(this::hasText)
                .distinct()
                .toList());
        decision.setRequiredEvidenceTypes(previous.getRequiredEvidenceTypes() == null
                ? List.of()
                : new ArrayList<>(previous.getRequiredEvidenceTypes()));
        decision.setFindingCategories(findings.stream()
                .map(ReviewFinding::getCategory)
                .filter(this::hasText)
                .distinct()
                .toList());
        decision.setBlockingFindingIds(findings.stream()
                .map(finding -> finding.getId().toString())
                .toList());
        decision.setRepairInstructions(List.of(
                "手动重跑 " + agentName + " 时，请优先修复上一轮 Reviewer 指出的相关问题；不要原样保留被质检指出的问题文本。",
                "修复后下游 agent 会自动重跑，并由 Reviewer 重新验收。"
        ));
        List<ReviewRepairTask> tasks = new ArrayList<>(existingTasks);
        LinkedHashSet<String> existingFindingIds = tasks.stream()
                .map(ReviewRepairTask::getFindingId)
                .filter(this::hasText)
                .collect(LinkedHashSet::new, LinkedHashSet::add, LinkedHashSet::addAll);
        findings.stream()
                .filter(finding -> !existingFindingIds.contains(finding.getId().toString()))
                .map(finding -> repairTaskForManualRerun(repairTargetForManualRerun(finding, repairScopeAgents, agentName), finding))
                .forEach(tasks::add);
        decision.setRepairTasks(tasks.stream()
                .limit(MAX_MANUAL_RERUN_REPAIR_FINDINGS)
                .toList());
        decision.setRequiredEvidenceTypes(mergedRequiredEvidenceTypes(
                decision.getRequiredEvidenceTypes(),
                decision.getRepairTasks()
        ));
        decision.setRepairScopeSummary("手动重跑 " + agentName + " 自动携带上一轮 Reviewer 问题："
                + decision.getRepairTasks().size() + " 个修复任务；类别=" + decision.getFindingCategories());
        decision.setDecidedAt(Instant.now());
        run.setManualRerunDecision(decision);
        repository.save(run);
        log.info("Prepared manual rerun review context: runId={}, agent={}, findings={}, existingTasks={}, tasks={}",
                runId,
                agentName,
                findings.size(),
                existingTasks.size(),
                decision.getRepairTasks().size());
    }

    private void clearManualRerunReviewContext(UUID runId) {
        repository.findById(runId).ifPresent(run -> {
            if (run.getManualRerunDecision() != null) {
                run.setManualRerunDecision(null);
                repository.save(run);
            }
        });
    }

    private List<ReviewFinding> manualRerunFindings(AnalysisRun run, List<AgentName> repairScopeAgents) {
        List<ReviewFinding> nonLowFindings = run.getReviewFindings().stream()
                .filter(finding -> finding.getSeverity() == null || !"LOW".equals(finding.getSeverity().name()))
                .toList();
        List<ReviewFinding> candidates = nonLowFindings.isEmpty() ? run.getReviewFindings() : nonLowFindings;
        List<ReviewFinding> scoped = candidates.stream()
                .filter(finding -> repairScopeAgents.contains(targetAgentForFinding(finding)))
                .sorted((left, right) -> {
                    int severityCompare = Integer.compare(severityRank(right), severityRank(left));
                    if (severityCompare != 0) {
                        return severityCompare;
                    }
                    return Integer.compare(
                            repairScopeAgents.indexOf(targetAgentForFinding(left)),
                            repairScopeAgents.indexOf(targetAgentForFinding(right))
                    );
                })
                .limit(MAX_MANUAL_RERUN_REPAIR_FINDINGS)
                .toList();
        if (!scoped.isEmpty()) {
            return scoped;
        }
        return List.of();
    }

    private int severityRank(ReviewFinding finding) {
        ReviewSeverity severity = finding == null ? null : finding.getSeverity();
        if (severity == ReviewSeverity.HIGH) {
            return 3;
        }
        if (severity == ReviewSeverity.MEDIUM) {
            return 2;
        }
        if (severity == ReviewSeverity.LOW) {
            return 1;
        }
        return 2;
    }

    private List<ReviewRepairTask> existingManualRerunTasks(AnalysisRun run, List<AgentName> repairScopeAgents) {
        ReviewDecision decision = run.getReviewDecision();
        if (decision == null || decision.getRepairTasks() == null || decision.getRepairTasks().isEmpty()) {
            return List.of();
        }
        return decision.getRepairTasks().stream()
                .filter(task -> repairScopeAgents.contains(task.getTargetAgent()))
                .limit(MAX_MANUAL_RERUN_REPAIR_FINDINGS)
                .toList();
    }

    private AgentName repairTargetForManualRerun(ReviewFinding finding, List<AgentName> repairScopeAgents, AgentName fallbackAgent) {
        AgentName targetAgent = finding.getTargetAgent() == null ? targetAgentForFinding(finding) : finding.getTargetAgent();
        return repairScopeAgents.contains(targetAgent) ? targetAgent : fallbackAgent;
    }

    private ReviewRepairTask repairTaskForManualRerun(AgentName agentName, ReviewFinding finding) {
        ReviewRepairTask task = new ReviewRepairTask();
        task.setTargetAgent(agentName);
        task.setFindingId(finding.getId().toString());
        task.setArtifactId(finding.getArtifactId());
        task.setClaimId(finding.getClaimId());
        task.setFactId(finding.getFactId());
        task.setChunkKey(finding.getChunkKey());
        task.setCitationKey(finding.getCitationKey());
        task.setParagraphIndex(finding.getParagraphIndex());
        task.setExcerpt(finding.getExcerpt());
        task.setCurrentText(firstText(finding.getExcerpt(), finding.getMessage()));
        task.setCategory(finding.getCategory());
        task.setAction(repairActionForManualRerun(agentName));
        task.setInstruction("修复上一轮 Reviewer 问题：" + shortText(finding.getMessage(), 220));
        task.setExpectedFix(expectedFixForManualRerun(agentName));
        task.setAcceptanceCriteria("下一轮 Reviewer 不应再出现同一 findingId/category/claim/citation 对应的问题。");
        if (agentName == AgentName.RESEARCHER) {
            List<String> evidenceTypes = inferredResearchEvidenceTypes(finding);
            task.setRequiredEvidenceTypes(evidenceTypes);
            task.setSourcePreferences(evidenceTypes);
        }
        return task;
    }

    private ReviewAction actionForManualRerun(AgentName agentName) {
        return switch (agentName) {
            case RESEARCHER -> ReviewAction.RECOLLECT_EVIDENCE;
            case WRITER -> ReviewAction.REVISE_REPORT;
            case EXTRACTOR, ANALYST -> ReviewAction.REWORK_ANALYSIS;
            case CLARIFIER, REVIEWER -> ReviewAction.PASS;
        };
    }

    private List<String> mergedRequiredEvidenceTypes(List<String> currentTypes, List<ReviewRepairTask> tasks) {
        LinkedHashSet<String> evidenceTypes = new LinkedHashSet<>();
        if (currentTypes != null) {
            currentTypes.stream()
                    .filter(this::hasText)
                    .forEach(evidenceTypes::add);
        }
        if (tasks != null) {
            tasks.stream()
                    .flatMap(task -> task.getRequiredEvidenceTypes() == null
                            ? List.<String>of().stream()
                            : task.getRequiredEvidenceTypes().stream())
                    .filter(this::hasText)
                    .forEach(evidenceTypes::add);
        }
        return new ArrayList<>(evidenceTypes);
    }

    private List<String> inferredResearchEvidenceTypes(ReviewFinding finding) {
        String text = normalizeFindingText(finding);
        LinkedHashSet<String> evidenceTypes = new LinkedHashSet<>();
        if (text.contains("claim_missing_sentiment_source")
                || text.contains("sentiment")
                || text.contains("user review")
                || text.contains("customer feedback")
                || text.contains("public_review")
                || text.contains("community")
                || text.contains("reviews")
                || text.contains("feedback source")) {
            evidenceTypes.add("public_review");
        }
        if (text.contains("pricing source")
                || text.contains("pricing_page")
                || text.contains("price source")
                || text.contains("pricing evidence")) {
            evidenceTypes.add("pricing_page");
        }
        if (text.contains("security source")
                || text.contains("security_docs")
                || text.contains("permission source")
                || text.contains("compliance source")) {
            evidenceTypes.add("security");
        }
        return new ArrayList<>(evidenceTypes);
    }

    private List<AgentName> manualRerunRepairScope(AgentName agentName) {
        return switch (agentName) {
            case RESEARCHER -> List.of(AgentName.RESEARCHER, AgentName.EXTRACTOR, AgentName.ANALYST, AgentName.WRITER);
            case EXTRACTOR -> List.of(AgentName.EXTRACTOR, AgentName.ANALYST, AgentName.WRITER);
            case ANALYST -> List.of(AgentName.ANALYST, AgentName.WRITER);
            case WRITER -> List.of(AgentName.WRITER);
            case CLARIFIER -> List.of(AgentName.CLARIFIER);
            case REVIEWER -> List.of(AgentName.REVIEWER);
        };
    }

    private String repairActionForManualRerun(AgentName agentName) {
        return switch (agentName) {
            case RESEARCHER -> "RECOLLECT_EVIDENCE";
            case EXTRACTOR -> "REPAIR_FACT_EXTRACTION";
            case ANALYST -> "REPAIR_CLAIM_EVIDENCE";
            case WRITER -> "REVISE_REPORT";
            case CLARIFIER, REVIEWER -> "REVIEW_ONLY";
        };
    }

    private String expectedFixForManualRerun(AgentName agentName) {
        return switch (agentName) {
            case RESEARCHER -> "补齐 Reviewer 指出的证据缺口，并优先围绕 repairTasks 中的 claim/citation/chunk 补证。";
            case EXTRACTOR -> "删除或修正无法由证据支撑的结构化 fact，并修复 evidenceIds/chunkKey 绑定。";
            case ANALYST -> "重建受影响 claim，确保结论与 fact/evidence 一致，证据不足时降级为待验证。";
            case WRITER -> "修订受影响段落，移除过度表述、补齐引用或改写为证据可支撑的表达。";
            case CLARIFIER, REVIEWER -> "重新复核。";
        };
    }

    private AgentName targetAgentForFinding(ReviewFinding finding) {
        if (finding.getTargetAgent() != null) {
            return finding.getTargetAgent();
        }
        String text = normalizeFindingText(finding);
        if (isResearchEvidenceFinding(text)) {
            return AgentName.RESEARCHER;
        }
        if (hasText(finding.getFactId())
                || text.contains("fact_unsupported")
                || text.contains("extracted fact")
                || text.contains("extract")
                || text.contains("profile")
                || text.contains("pricing")) {
            return AgentName.EXTRACTOR;
        }
        if (text.contains("report")
                || text.contains("paragraph")
                || text.contains("overclaim")
                || text.contains("writer")
                || text.contains("actionability")
                || text.contains("citation_missing")
                || text.contains("引用缺失")) {
            return AgentName.WRITER;
        }
        if (hasText(finding.getClaimId())
                || text.contains("claim")
                || text.contains("matrix")
                || text.contains("swot")
                || text.contains("analysis")) {
            return AgentName.ANALYST;
        }
        if (finding.getParagraphIndex() != null) {
            return AgentName.WRITER;
        }
        return AgentName.ANALYST;
    }

    private boolean isResearchEvidenceFinding(String text) {
        return text.contains("missing evidence")
                || text.contains("missing_evidence")
                || text.contains("missing_source")
                || text.contains("claim_missing_sentiment_source")
                || text.contains("evidence gap")
                || text.contains("sentiment")
                || text.contains("user review")
                || text.contains("customer feedback")
                || text.contains("public_review")
                || text.contains("review source")
                || text.contains("feedback source")
                || text.contains("pricing source")
                || text.contains("pricing_page")
                || text.contains("price source")
                || text.contains("security source")
                || text.contains("security_docs")
                || text.contains("permission source")
                || text.contains("compliance source")
                || text.contains("source_quality")
                || text.contains("low_quality_source")
                || text.contains("marketing_only_source")
                || text.contains("snippet_only_source")
                || text.contains("blocked_source")
                || text.contains("fetch_failed_source")
                || text.contains("coverage")
                || text.contains("补证")
                || text.contains("证据缺口");
    }

    private String normalizeFindingText(ReviewFinding finding) {
        return ("%s %s %s".formatted(
                textOrDash(finding.getCategory()),
                textOrDash(finding.getMessage()),
                textOrDash(finding.getRecommendation())
        )).toLowerCase();
    }

    private String firstText(String first, String second) {
        return hasText(first) ? first.trim() : textOrDash(second);
    }

    private String shortText(String value, int maxLength) {
        String normalized = textOrDash(value).replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength) + "...";
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private List<AgentNode> rerunCascade(AgentName agentName) {
        // 这里定义 agent 依赖链：Reviewer 只需要选最上游责任点，下游会在同一轮级联中自动刷新。
        // Manual reruns replay the selected agent plus deterministic downstream dependencies only.
        // Reviewer is the terminal executable agent; finish no longer triggers a copied-report step.
        List<AgentName> order = switch (agentName) {
            case CLARIFIER -> List.of(AgentName.CLARIFIER);
            case RESEARCHER -> List.of(AgentName.RESEARCHER, AgentName.EXTRACTOR, AgentName.ANALYST,
                    AgentName.WRITER, AgentName.REVIEWER);
            case EXTRACTOR -> List.of(AgentName.EXTRACTOR, AgentName.ANALYST, AgentName.WRITER,
                    AgentName.REVIEWER);
            case ANALYST -> List.of(AgentName.ANALYST, AgentName.WRITER, AgentName.REVIEWER);
            case WRITER -> List.of(AgentName.WRITER, AgentName.REVIEWER);
            case REVIEWER -> List.of(AgentName.REVIEWER);
        };
        return order.stream()
                .map(nodesByName::get)
                .filter(node -> node != null)
                .toList();
    }

    private int manualRerunAttempt(AnalysisRun run) {
        return run.getWorkflowTransitions().stream()
                .mapToInt(WorkflowTransition::getAttempt)
                .max()
                .orElse(-1) + 1;
    }

    private WorkflowTransition latestTransition(AnalysisRun run) {
        List<WorkflowTransition> transitions = run.getWorkflowTransitions();
        if (transitions == null || transitions.isEmpty()) {
            return null;
        }
        return transitions.get(transitions.size() - 1);
    }

    private boolean hasRepeatedBlockingFindings(AnalysisRun run) {
        WorkflowTransition previous = latestTransition(run);
        if (previous == null || previousSignatures(previous).isEmpty()) {
            return false;
        }
        List<String> currentSignatures = blockingFindingSignatures(run);
        if (currentSignatures.isEmpty()) {
            return false;
        }
        return currentSignatures.stream().anyMatch(previousSignatures(previous)::contains);
    }

    private List<String> blockingFindingSignatures(AnalysisRun run) {
        LinkedHashSet<String> decisionIds = new LinkedHashSet<>(run.getReviewDecision().getBlockingFindingIds() == null
                ? List.of()
                : run.getReviewDecision().getBlockingFindingIds());
        return run.getReviewFindings().stream()
                .filter(finding -> decisionIds.contains(finding.getId().toString()))
                .map(finding -> "%s|claim=%s|fact=%s|chunk=%s|citation=%s|paragraph=%s|excerpt=%s".formatted(
                        textOrDash(finding.getCategory()),
                        textOrDash(finding.getClaimId()),
                        textOrDash(finding.getFactId()),
                        textOrDash(finding.getChunkKey()),
                        textOrDash(finding.getCitationKey()),
                        finding.getParagraphIndex() == null ? "-" : finding.getParagraphIndex(),
                        shortTextHash(finding.getExcerpt())
                ))
                .distinct()
                .toList();
    }

    private List<String> resolvedFindingSignatures(WorkflowTransition previous, List<String> currentSignatures) {
        if (previous == null || previousSignatures(previous).isEmpty()) {
            return List.of();
        }
        return previousSignatures(previous).stream()
                .filter(signature -> !currentSignatures.contains(signature))
                .toList();
    }

    private List<String> unresolvedFindingSignatures(WorkflowTransition previous, List<String> currentSignatures) {
        if (previous == null || previousSignatures(previous).isEmpty()) {
            return List.of();
        }
        return previousSignatures(previous).stream()
                .filter(currentSignatures::contains)
                .toList();
    }

    private String resolutionStatus(WorkflowTransition previous, WorkflowTransition current) {
        if (previous == null || previousSignatures(previous).isEmpty()) {
            return current.getBlockingFindingSignatures().isEmpty() ? "NO_BLOCKERS" : "NEW_BLOCKERS";
        }
        if (current.getBlockingFindingSignatures().isEmpty()) {
            return "RESOLVED";
        }
        if (!current.getUnresolvedFindingSignatures().isEmpty()) {
            return "PARTIALLY_UNRESOLVED";
        }
        return "REPLACED_BY_NEW_BLOCKERS";
    }

    private List<String> previousSignatures(WorkflowTransition previous) {
        return previous.getBlockingFindingSignatures() == null ? List.of() : previous.getBlockingFindingSignatures();
    }

    private String textOrDash(String value) {
        return value == null || value.isBlank() ? "-" : value.trim();
    }

    private String shortTextHash(String value) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        String normalized = value.toLowerCase()
                .replaceAll("\\s+", " ")
                .trim();
        return Integer.toHexString(normalized.hashCode());
    }

    private String inputSummary(AnalysisGraphState state) {
        if (state.reworkAttempts() > 0) {
            return "复核反馈重跑第 " + state.reworkAttempts() + " 轮";
        }
        return "来自上一 Agent 状态的输入";
    }
}
