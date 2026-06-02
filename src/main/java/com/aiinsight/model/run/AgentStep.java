package com.aiinsight.model.run;

import com.aiinsight.model.enums.AgentName;
import com.aiinsight.model.enums.StepStatus;
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
// AgentStep 记录一次 Agent 节点执行，而不是一个 Agent 的定义。
// 同一个 Agent 被打回重跑时会产生多条 step，从而保留完整审计轨迹。
public class AgentStep {

    private UUID id = UUID.randomUUID();
    private AgentName agentName;
    private String title;
    private StepStatus status = StepStatus.PENDING;
    private String inputSummary;
    private String outputSummary;
    private Instant startedAt;
    private Instant completedAt;
    private List<String> issues = new ArrayList<>();

    public AgentStep(AgentName agentName, String title) {
        this.agentName = agentName;
        this.title = title;
    }

    // start/succeed/fail 三个方法集中维护状态和时间，避免服务层散落细节。
    public void start(String inputSummary) {
        this.status = StepStatus.RUNNING;
        this.inputSummary = inputSummary;
        this.startedAt = Instant.now();
    }

    public void succeed(String outputSummary) {
        this.status = StepStatus.SUCCEEDED;
        this.outputSummary = outputSummary;
        this.completedAt = Instant.now();
    }

    public void fail(String issue) {
        this.status = StepStatus.FAILED;
        this.issues.add(issue);
        this.completedAt = Instant.now();
    }

    public void cancel(String issue) {
        this.status = StepStatus.CANCELLED;
        if (issue != null && !issue.isBlank()) {
            this.issues.add(issue);
        }
        this.completedAt = Instant.now();
    }
}
