package com.aiinsight.service;

import com.aiinsight.model.enums.ReviewAction;
import com.aiinsight.model.review.ReviewRepairTask;
import com.aiinsight.model.run.AnalysisRequirement;
import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.model.run.EvidenceSource;
import com.aiinsight.model.schema.AnalysisClaim;
import com.aiinsight.model.schema.EvidenceBudget;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ResearchCoverageServiceTest {

    private final ResearchCoverageService service = new ResearchCoverageService();

    @Test
    void createsCoverageGapAndBackfillTargetForMissingCompetitorDimension() {
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "Analyze Cursor and Copilot pricing",
                "AI coding tools",
                List.of("Cursor", "GitHub Copilot"),
                List.of("pricing"),
                List.of("pricing_page"),
                List.of()
        ));
        EvidenceBudget cursorBudget = budget("Cursor", "pricing");
        EvidenceBudget copilotBudget = budget("GitHub Copilot", "pricing");
        run.getResearchPackage().getResearchCollectionPlan().getEvidenceBudgets().addAll(List.of(cursorBudget, copilotBudget));
        run.getEvidenceSources().add(new EvidenceSource(
                "S1",
                "Cursor pricing",
                "https://cursor.com/pricing",
                "pricing_page",
                "FETCHED",
                "LIVE_FETCHED",
                "HIGH",
                "NONE",
                "Cursor pricing page.",
                "Cursor pricing plans and enterprise tiers.",
                "test"
        ));

        service.refreshCoverage(run);

        var plan = run.getResearchPackage().getResearchCollectionPlan();
        assertThat(plan.getCoverageGaps()).hasSize(1);
        assertThat(plan.getCoverageGaps().get(0).getCompetitorName()).isEqualTo("GitHub Copilot");
        assertThat(plan.getCoverageGaps().get(0).getDimension()).isEqualTo("pricing");
        assertThat(plan.getRepairTargets()).hasSize(1);
        assertThat(plan.getRepairTargets().get(0).getQueries())
                .anyMatch(query -> query.contains("GitHub Copilot") && query.contains("pricing"));
    }

    @Test
    void enrichesReviewerRepairTaskWithPreciseCollectionTarget() {
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "Analyze Cursor pricing",
                "AI coding tools",
                List.of("Cursor"),
                List.of("pricing"),
                List.of("pricing_page"),
                List.of()
        ));
        AnalysisClaim claim = new AnalysisClaim();
        claim.setContent("Cursor pricing evidence is missing.");
        claim.getCompetitorNames().add("Cursor");
        run.getClaims().add(claim);
        run.getReviewDecision().setAction(ReviewAction.RECOLLECT_EVIDENCE);
        run.getReviewDecision().setRequiredEvidenceTypes(List.of("pricing_page"));
        ReviewRepairTask task = new ReviewRepairTask();
        task.setClaimId(claim.getId());
        task.setInstruction("Collect missing pricing evidence for the claim.");
        task.setRequiredEvidenceTypes(List.of("pricing_page"));
        run.getReviewDecision().getRepairTasks().add(task);

        service.enrichRepairTasks(run);
        service.refreshRepairTargets(run);

        assertThat(task.getCompetitorName()).isEqualTo("Cursor");
        assertThat(task.getDimension()).isEqualTo("pricing");
        assertThat(task.getSourcePreferences()).contains("pricing_page");
        assertThat(task.getQueries()).anyMatch(query -> query.contains("Cursor") && query.contains("pricing"));
        assertThat(run.getResearchPackage().getResearchCollectionPlan().getRepairTargets())
                .anySatisfy(target -> {
                    assertThat(target.getCompetitorName()).isEqualTo("Cursor");
                    assertThat(target.getPriority()).isEqualTo("REVIEW_REPAIR");
                });
    }

    @Test
    void queryPlannerUsesStructuredRepairTaskTargetsDuringRecollection() {
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "Analyze Cursor and Copilot",
                "AI coding tools",
                List.of("Cursor", "GitHub Copilot"),
                List.of("pricing"),
                List.of("pricing_page"),
                List.of()
        ));
        run.getReviewDecision().setAction(ReviewAction.RECOLLECT_EVIDENCE);
        ReviewRepairTask task = new ReviewRepairTask();
        task.setCompetitorName("Cursor");
        task.setDimension("pricing");
        task.setSourcePreferences(List.of("pricing_page"));
        task.setQueries(List.of("Cursor pricing official plans"));
        run.getReviewDecision().getRepairTasks().add(task);

        List<SearchQueryPlanner.SearchQueryBatch> batches = new SearchQueryPlanner().planByCompetitor(run, true);

        assertThat(batches).hasSize(1);
        assertThat(batches.get(0).competitor()).isEqualTo("Cursor");
        assertThat(batches.get(0).queries()).contains("Cursor pricing official plans");
    }

    @Test
    void keepsBackfillAndReviewerRepairTargetsForSameCompetitorDimension() {
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "Analyze Cursor pricing",
                "AI coding tools",
                List.of("Cursor"),
                List.of("pricing"),
                List.of("pricing_page"),
                List.of()
        ));
        run.getResearchPackage().getResearchCollectionPlan().getEvidenceBudgets().add(budget("Cursor", "pricing"));

        service.refreshCoverage(run);

        assertThat(run.getResearchPackage().getResearchCollectionPlan().getRepairTargets())
                .hasSize(1)
                .first()
                .extracting(target -> target.getPriority())
                .isEqualTo("BACKFILL");

        run.getReviewDecision().setAction(ReviewAction.RECOLLECT_EVIDENCE);
        ReviewRepairTask task = new ReviewRepairTask();
        task.setCompetitorName("Cursor");
        task.setDimension("pricing");
        task.setSourcePreferences(List.of("pricing_page"));
        task.setInstruction("Reviewer requires a fresher pricing source.");
        run.getReviewDecision().getRepairTasks().add(task);

        service.refreshRepairTargets(run);

        assertThat(run.getResearchPackage().getResearchCollectionPlan().getRepairTargets())
                .extracting(target -> target.getPriority())
                .containsExactlyInAnyOrder("BACKFILL", "REVIEW_REPAIR");

        service.refreshCoverage(run);

        assertThat(run.getResearchPackage().getResearchCollectionPlan().getRepairTargets())
                .extracting(target -> target.getPriority())
                .containsExactlyInAnyOrder("REVIEW_REPAIR", "BACKFILL");
    }

    @Test
    void reviewsCoverageRequiresUsableReviewLikeEvidence() {
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "Analyze Cursor user reviews",
                "AI coding tools",
                List.of("Cursor"),
                List.of("reviews"),
                List.of("public_reviews"),
                List.of()
        ));
        run.getResearchPackage().getResearchCollectionPlan().getEvidenceBudgets().add(budget("Cursor", "reviews"));
        run.getEvidenceSources().add(new EvidenceSource(
                "S1",
                "Cursor docs mention reviews",
                "https://cursor.com/docs",
                "official_site",
                "FETCHED",
                "LIVE_FETCHED",
                "HIGH",
                "NONE",
                "Cursor product docs mention code review workflows.",
                "Cursor product docs mention code review workflows.",
                "test"
        ));
        run.getEvidenceSources().add(new EvidenceSource(
                "S2",
                "Cursor reviews metadata",
                "https://example.com/cursor-reviews",
                "public_review",
                "FETCHED",
                "LIVE_FETCHED",
                "LOW",
                "METADATA_ONLY",
                "Cursor reviews",
                "",
                "metadata only"
        ));

        service.refreshCoverage(run);

        assertThat(run.getResearchPackage().getResearchCollectionPlan().getCoverageGaps())
                .hasSize(1)
                .first()
                .satisfies(gap -> {
                    assertThat(gap.getCompetitorName()).isEqualTo("Cursor");
                    assertThat(gap.getDimension()).isEqualTo("reviews");
                });

        run.getEvidenceSources().add(new EvidenceSource(
                "S3",
                "Cursor user reviews",
                "https://example.com/cursor-user-reviews",
                "public_review",
                "FETCHED",
                "LIVE_FETCHED",
                "LOW",
                "NONE",
                "Users report onboarding friction and strong code completion in reviews.",
                "Users report onboarding friction and strong code completion in reviews.",
                "test"
        ));

        service.refreshCoverage(run);

        assertThat(run.getResearchPackage().getResearchCollectionPlan().getCoverageGaps()).isEmpty();
    }

    private EvidenceBudget budget(String competitor, String dimension) {
        EvidenceBudget budget = new EvidenceBudget();
        budget.setCompetitorName(competitor);
        budget.setDimension(dimension);
        budget.setMaxAcceptedSources(1);
        return budget;
    }
}
