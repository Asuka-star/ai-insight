package com.aiinsight.model.run;

import com.aiinsight.model.enums.AgentName;
import com.aiinsight.model.enums.ContextIntent;
import com.aiinsight.model.enums.ContextRole;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
public class AnalysisContextMessage {

    private UUID id = UUID.randomUUID();
    private ContextRole role = ContextRole.USER;
    private ContextIntent intent;
    private String content;
    private AgentName targetAgent;
    private Instant createdAt = Instant.now();

    public AnalysisContextMessage(ContextRole role, ContextIntent intent, String content, AgentName targetAgent) {
        this.role = role;
        this.intent = intent;
        this.content = content;
        this.targetAgent = targetAgent;
    }
}
