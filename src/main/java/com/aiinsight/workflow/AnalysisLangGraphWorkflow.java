package com.aiinsight.workflow;

import com.aiinsight.agent.AgentNode;
import com.aiinsight.exception.RunNotFoundException;
import com.aiinsight.model.enums.AgentName;
import com.aiinsight.model.enums.AnalysisStatus;
import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.model.enums.ReviewAction;
import com.aiinsight.model.review.ReviewDecision;
import com.aiinsight.model.run.WorkflowTransition;
import com.aiinsight.repository.AnalysisRunRepository;
import com.aiinsight.service.AnalysisEventBroker;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphDefinition;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncEdgeAction;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;

@Component
public class AnalysisLangGraphWorkflow {

    static final String ROUTE_FINISH = "finish";
    private static final String ROUTE_RECOLLECT = "recollect";
    private static final String ROUTE_REEXTRACT = "reextract";
    private static final String ROUTE_REANALYZE = "reanalyze";
    private static final String ROUTE_REVISE = "revise";
    private static final String REVIEW_GATE = "REVIEW_GATE";

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
        AnalysisRun run = null;
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
        return run == null ? repository.findById(runId).orElseThrow(() -> new RunNotFoundException(runId)) : run;
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
                            ROUTE_FINISH, AgentName.FINALIZER.name()
                    )
            );
            stateGraph.addEdge(AgentName.FINALIZER.name(), GraphDefinition.END);
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
        // REVIEW_GATE 是整个可信闭环的唯一分岔点：Reviewer 写入 ReviewDecision，
        // 这里把结构化 action 映射成 LangGraph 路由，并把选择持久化给前端回放。
        String route = nextRoute(run, attempts);
        // 每一次条件边选择都落库，前端才能解释“Reviewer 为什么打回到某个 Agent”。
        recordTransition(run, route, attempts, "auto-review-gate");
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

    private void recordTransition(AnalysisRun run, String route, int attempt, String trigger) {
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
    }

    private String nextRoute(AnalysisRun run, int reworkAttempts) {
        // MVP 限制自动返工轮次，防止 Reviewer 和上游 Agent 在证据不足时无限循环。
        if (reworkAttempts >= maxReviewReworkAttempts(run)) {
            return ROUTE_FINISH;
        }
        // ReviewAction 是后端和前端共同理解的返工协议：
        // 采集缺口回 Researcher，结构化分析问题回 Analyst，报告表达问题回 Writer。
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
        return AgentName.FINALIZER.name();
    }

    private List<AgentNode> rerunCascade(AgentName agentName) {
        List<AgentName> order = switch (agentName) {
            case CLARIFIER -> List.of(AgentName.CLARIFIER);
            case RESEARCHER -> List.of(AgentName.RESEARCHER, AgentName.EXTRACTOR, AgentName.ANALYST,
                    AgentName.WRITER, AgentName.REVIEWER, AgentName.FINALIZER);
            case EXTRACTOR -> List.of(AgentName.EXTRACTOR, AgentName.ANALYST, AgentName.WRITER,
                    AgentName.REVIEWER, AgentName.FINALIZER);
            case ANALYST -> List.of(AgentName.ANALYST, AgentName.WRITER, AgentName.REVIEWER, AgentName.FINALIZER);
            case WRITER -> List.of(AgentName.WRITER, AgentName.REVIEWER, AgentName.FINALIZER);
            case REVIEWER -> List.of(AgentName.REVIEWER, AgentName.FINALIZER);
            case FINALIZER -> List.of(AgentName.FINALIZER);
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

    private List<String> blockingFindingSignatures(AnalysisRun run) {
        LinkedHashSet<String> decisionIds = new LinkedHashSet<>(run.getReviewDecision().getBlockingFindingIds() == null
                ? List.of()
                : run.getReviewDecision().getBlockingFindingIds());
        return run.getReviewFindings().stream()
                .filter(finding -> decisionIds.contains(finding.getId().toString()))
                .map(finding -> "%s|%s|%s".formatted(
                        textOrDash(finding.getCategory()),
                        textOrDash(finding.getClaimId()),
                        textOrDash(finding.getCitationKey())
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

    private String inputSummary(AnalysisGraphState state) {
        if (state.reworkAttempts() > 0) {
            return "复核反馈重跑第 " + state.reworkAttempts() + " 轮";
        }
        return "来自上一 Agent 状态的输入";
    }
}
