package com.aiinsight.workflow;

import org.bsc.langgraph4j.state.AgentState;

import java.util.Map;
import java.util.UUID;

public class AnalysisGraphState extends AgentState {

    public static final String RUN_ID = "runId";
    public static final String REWORK_ATTEMPTS = "reworkAttempts";
    public static final String FEEDBACK_ROUTE = "feedbackRoute";

    public AnalysisGraphState(Map<String, Object> initData) {
        super(initData);
    }

    public UUID runId() {
        return value(RUN_ID)
                .map(UUID.class::cast)
                .orElseThrow(() -> new IllegalStateException("LangGraph state missing runId"));
    }

    public int reworkAttempts() {
        return value(REWORK_ATTEMPTS)
                .map(Integer.class::cast)
                .orElse(0);
    }

    public String feedbackRoute() {
        return value(FEEDBACK_ROUTE)
                .map(String.class::cast)
                .orElse(AnalysisLangGraphWorkflow.ROUTE_FINISH);
    }
}
