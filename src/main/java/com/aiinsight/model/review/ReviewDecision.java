package com.aiinsight.model.review;

import com.aiinsight.model.enums.AgentName;
import com.aiinsight.model.enums.ReviewAction;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
// ReviewDecision 是 Reviewer Agent 的结构化决策，用于驱动 DAG 条件边和重跑策略。
public class ReviewDecision {

    private ReviewAction action = ReviewAction.PASS;
    private AgentName targetAgent;
    private String reason;
    private List<String> affectedClaimIds = new ArrayList<>();
    private List<String> requiredEvidenceTypes = new ArrayList<>();
    private Instant decidedAt = Instant.now();
}
