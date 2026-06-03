package com.aiinsight.agent.research;

import com.aiinsight.service.SearchQueryPlanner;

import java.util.List;

public record ResearchAgentPlan(
        String objective,
        boolean recollectionMode,
        List<SearchQueryPlanner.SearchQueryBatch> searchQueryBatches,
        List<ResearchAction> actions
) {
}
