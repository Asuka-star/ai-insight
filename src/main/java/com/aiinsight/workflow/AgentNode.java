package com.aiinsight.workflow;

import com.aiinsight.domain.AgentName;
import com.aiinsight.domain.AnalysisRun;

// 所有 Agent 节点共享同一份 AnalysisRun 状态。
// 现在是线性编排，后续接 LangGraph4j 时每个实现可以直接变成图中的一个节点。
public interface AgentNode {

    AgentName name();

    String title();

    AnalysisRun execute(AnalysisRun run);
}
