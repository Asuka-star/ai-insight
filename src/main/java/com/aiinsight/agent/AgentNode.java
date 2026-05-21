package com.aiinsight.agent;

import com.aiinsight.model.enums.AgentName;
import com.aiinsight.model.run.AnalysisRun;

// 所有 Agent 节点共享同一份 AnalysisRun 状态。
// LangGraph4j 会把每个实现注册成图节点，并通过 AnalysisRun 共享结构化状态。
public interface AgentNode {

    AgentName name();

    String title();

    AnalysisRun execute(AnalysisRun run);
}
