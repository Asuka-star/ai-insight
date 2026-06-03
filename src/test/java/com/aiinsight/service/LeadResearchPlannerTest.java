package com.aiinsight.service;

import com.aiinsight.model.enums.ReviewAction;
import com.aiinsight.model.review.ReviewRepairTask;
import com.aiinsight.model.run.AnalysisRequirement;
import com.aiinsight.model.run.AnalysisRun;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LeadResearchPlannerTest {

    private final LeadResearchPlanner planner = new LeadResearchPlanner();

    @Test
    void createsBalancedFirstRoundPlanWithSourceRationale() {
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "Analyze Cursor and Copilot pricing",
                "AI coding tools",
                List.of("Cursor", "GitHub Copilot"),
                List.of("pricing", "security"),
                List.of("pricing_page"),
                List.of()
        ));
        List<SearchQueryPlanner.SearchQueryBatch> batches = List.of(
                new SearchQueryPlanner.SearchQueryBatch("Cursor", List.of("Cursor pricing")),
                new SearchQueryPlanner.SearchQueryBatch("GitHub Copilot", List.of("GitHub Copilot pricing"))
        );

        var plan = planner.plan(run, batches, false);

        assertThat(plan.getObjective()).contains("Cursor", "GitHub Copilot");
        assertThat(plan.getFocusAreas()).contains("Cursor / pricing", "GitHub Copilot / pricing");
        assertThat(plan.getRecommendedSourceTypes()).contains("pricing_page", "security");
        assertThat(plan.getRationale()).anyMatch(item -> item.contains("per-competitor"));
    }

    @Test
    void createsReviewerRepairPlanningPriorities() {
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "Analyze Cursor pricing",
                "AI coding tools",
                List.of("Cursor", "GitHub Copilot"),
                List.of("pricing"),
                List.of("pricing_page"),
                List.of()
        ));
        run.getReviewDecision().setAction(ReviewAction.RECOLLECT_EVIDENCE);
        run.getReviewDecision().setRequiredEvidenceTypes(List.of("pricing_page"));
        ReviewRepairTask task = new ReviewRepairTask();
        task.setCompetitorName("Cursor");
        task.setDimension("pricing");
        task.setSourcePreferences(List.of("pricing_page", "official_site"));
        run.getReviewDecision().getRepairTasks().add(task);

        var plan = planner.plan(run, List.of(), true);

        assertThat(plan.getObjective()).contains("Target reviewer evidence gaps");
        assertThat(plan.getFocusAreas()).contains("Cursor / pricing");
        assertThat(plan.getRepairPriorities()).anyMatch(priority -> priority.contains("Cursor / pricing"));
        assertThat(plan.getRationale()).anyMatch(item -> item.contains("reviewer repair tasks"));
    }
}
