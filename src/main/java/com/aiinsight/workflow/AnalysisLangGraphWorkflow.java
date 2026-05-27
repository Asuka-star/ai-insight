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
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;

@Component
public class AnalysisLangGraphWorkflow {

    static final String ROUTE_FINISH = "finish";
    private static final String ROUTE_RECOLLECT = "recollect";
    private static final String ROUTE_REANALYZE = "reanalyze";
    private static final String ROUTE_REVISE = "revise";
    private static final String REVIEW_GATE = "REVIEW_GATE";
    private static final int MAX_REVIEW_REWORK_ATTEMPTS = 1;

    private final WorkflowNodeExecutor nodeExecutor;
    private final AnalysisRunRepository repository;
    private final AnalysisEventBroker eventBroker;
    private final Map<AgentName, AgentNode> nodesByName;
    private final CompiledGraph<AnalysisGraphState> graph;

    public AnalysisLangGraphWorkflow(List<AgentNode> nodes,
                                     WorkflowNodeExecutor nodeExecutor,
                                     AnalysisRunRepository repository,
                                     AnalysisEventBroker eventBroker) {
        this.nodeExecutor = nodeExecutor;
        this.repository = repository;
        this.eventBroker = eventBroker;
        this.nodesByName = new EnumMap<>(AgentName.class);
        nodes.stream()
                .sorted(Comparator.comparingInt(node -> node.name().ordinal()))
                .forEach(node -> this.nodesByName.put(node.name(), node));
        this.graph = buildGraph();
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
        return nodeExecutor.executeNode(runId, node, "Manual rerun requested for " + agentName);
    }

    private CompiledGraph<AnalysisGraphState> buildGraph() {
        try {
            StateGraph<AnalysisGraphState> stateGraph = new StateGraph<>(AnalysisGraphState::new);
            for (AgentNode node : nodesByName.values()) {
                // 每个 Agent 节点只关心 AnalysisRun 的业务变更；执行生命周期、Trace、SSE 事件
                // 统一交给 WorkflowNodeExecutor，避免节点内混入流程控制细节。
                stateGraph.addNode(node.name().name(), AsyncNodeAction.node_async(state -> {
                    nodeExecutor.executeNode(state.runId(), node, inputSummary(state));
                    return Map.of();
                }));
            }
            stateGraph.addNode(REVIEW_GATE, AsyncNodeAction.node_async(state -> routeFromReview(state)));

            stateGraph.addEdge(GraphDefinition.START, AgentName.CLARIFIER.name());
            stateGraph.addEdge(AgentName.CLARIFIER.name(), AgentName.RESEARCHER.name());
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
                            ROUTE_REANALYZE, AgentName.ANALYST.name(),
                            ROUTE_REVISE, AgentName.WRITER.name(),
                            ROUTE_FINISH, AgentName.REVISION.name()
                    )
            );
            stateGraph.addEdge(AgentName.REVISION.name(), GraphDefinition.END);
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
        recordTransition(run, route, attempts);
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

    private void recordTransition(AnalysisRun run, String route, int attempt) {
        ReviewDecision decision = run.getReviewDecision();
        run.getWorkflowTransitions().add(new WorkflowTransition(
                REVIEW_GATE,
                targetNodeFor(route),
                route,
                decision.getAction(),
                decision.getReason(),
                attempt
        ));
        repository.save(run);
    }

    private String nextRoute(AnalysisRun run, int reworkAttempts) {
        // MVP 限制自动返工轮次，防止 Reviewer 和上游 Agent 在证据不足时无限循环。
        if (reworkAttempts >= MAX_REVIEW_REWORK_ATTEMPTS) {
            return ROUTE_FINISH;
        }
        // ReviewAction 是后端和前端共同理解的返工协议：
        // 采集缺口回 Researcher，结构化分析问题回 Analyst，报告表达问题回 Writer。
        ReviewAction action = run.getReviewDecision().getAction();
        if (action == ReviewAction.RECOLLECT_EVIDENCE) {
            return ROUTE_RECOLLECT;
        }
        if (action == ReviewAction.REWORK_ANALYSIS) {
            return ROUTE_REANALYZE;
        }
        if (action == ReviewAction.REVISE_REPORT) {
            return ROUTE_REVISE;
        }
        return ROUTE_FINISH;
    }

    private String targetNodeFor(String route) {
        if (ROUTE_RECOLLECT.equals(route)) {
            return AgentName.RESEARCHER.name();
        }
        if (ROUTE_REANALYZE.equals(route)) {
            return AgentName.ANALYST.name();
        }
        if (ROUTE_REVISE.equals(route)) {
            return AgentName.WRITER.name();
        }
        return AgentName.REVISION.name();
    }

    private String inputSummary(AnalysisGraphState state) {
        if (state.reworkAttempts() > 0) {
            return "复核反馈重跑第 " + state.reworkAttempts() + " 轮";
        }
        return "来自上一 Agent 状态的输入";
    }
}
