package com.aiinsight.agent.research;

import java.util.List;

public record ResearchAgentDecision(
        String action,
        String reason,
        List<String> unresolvedEvidenceTypes
) {
}
