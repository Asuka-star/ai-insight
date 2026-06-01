package com.aiinsight.model.run;

import com.aiinsight.model.enums.ReviewAction;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
// WorkflowTransition 记录 LangGraph4j 条件边的选择结果，方便前端回放“为什么跳到这个节点”。
public class WorkflowTransition {

    private UUID id = UUID.randomUUID();
    private String sourceNode;
    private String targetNode;
    private String route;
    private ReviewAction reviewAction;
    private String reason;
    private int attempt;
    private String trigger;
    private String resolutionStatus;
    private List<String> blockingFindingIds = new ArrayList<>();
    private List<String> blockingFindingSignatures = new ArrayList<>();
    private List<String> resolvedFindingSignatures = new ArrayList<>();
    private List<String> unresolvedFindingSignatures = new ArrayList<>();
    private Instant createdAt = Instant.now();

    public WorkflowTransition(String sourceNode,
                              String targetNode,
                              String route,
                              ReviewAction reviewAction,
                              String reason,
                              int attempt) {
        this.sourceNode = sourceNode;
        this.targetNode = targetNode;
        this.route = route;
        this.reviewAction = reviewAction;
        this.reason = reason;
        this.attempt = attempt;
    }
}
