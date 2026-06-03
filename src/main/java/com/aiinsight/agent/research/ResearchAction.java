package com.aiinsight.agent.research;

import java.util.List;

public record ResearchAction(
        String toolName,
        String intent,
        String target,
        List<String> inputs
) {
}
