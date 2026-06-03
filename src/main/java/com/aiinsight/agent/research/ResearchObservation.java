package com.aiinsight.agent.research;

import java.util.List;

public record ResearchObservation(
        String toolName,
        String summary,
        int producedCount,
        List<String> notes
) {
}
