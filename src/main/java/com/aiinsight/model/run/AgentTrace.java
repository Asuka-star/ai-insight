package com.aiinsight.model.run;

import com.aiinsight.model.enums.AgentName;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
// AgentTrace 记录一次 Agent 执行的可观测信息，后续会继续补齐 Prompt、模型名和 token 消耗。
public class AgentTrace {

    private UUID id = UUID.randomUUID();
    private AgentName agentName;
    private String prompt;
    private String inputSnapshot;
    private String outputSnapshot;
    private String decisionSummary;
    private String modelName;
    private Integer promptTokens;
    private Integer completionTokens;
    private Long latencyMs;
    private Instant createdAt = Instant.now();
}
