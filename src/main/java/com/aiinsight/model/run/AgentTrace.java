package com.aiinsight.model.run;

import com.aiinsight.model.enums.AgentName;
import com.aiinsight.model.enums.StepStatus;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
// AgentTrace 记录一次 Agent 执行的可观测信息，供前端回放 Agent 决策过程。
public class AgentTrace {

    private UUID id = UUID.randomUUID();
    private UUID stepId;
    private AgentName agentName;
    private StepStatus status = StepStatus.PENDING;
    private String prompt;
    private String inputSnapshot;
    private String outputSnapshot;
    private String rawModelOutput;
    private String decisionSummary;
    private String modelName;
    private Boolean fallbackUsed = false;
    private String fallbackReason;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;
    private Long latencyMs;
    private String errorMessage;
    private Instant startedAt;
    private Instant completedAt;
    private Instant createdAt = Instant.now();
}
