package com.aiinsight.service;

import com.aiinsight.model.enums.ReviewAction;
import com.aiinsight.model.review.ReviewRepairTask;
import com.aiinsight.model.run.AnalysisRequirement;
import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.model.schema.LeadResearchPlan;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class LeadResearchPlanner {

    public LeadResearchPlan plan(AnalysisRun run,
                                 List<SearchQueryPlanner.SearchQueryBatch> batches,
                                 boolean recollecting) {
        LeadResearchPlan plan = new LeadResearchPlan();
        plan.setRunId(run.getId());
        plan.setObjective(objective(run, recollecting));
        plan.setFocusAreas(focusAreas(run, batches, recollecting));
        plan.setRationale(rationale(run, batches, recollecting));
        plan.setRecommendedSourceTypes(recommendedSourceTypes(run, recollecting));
        plan.setRepairPriorities(repairPriorities(run, recollecting));
        return plan;
    }

    private String objective(AnalysisRun run, boolean recollecting) {
        AnalysisRequirement requirement = run.getRequirement();
        String competitors = join(requirement == null ? List.of() : requirement.getCompetitors());
        String dimensions = join(requirement == null ? List.of() : requirement.getDimensions());
        if (recollecting) {
            return "Target reviewer evidence gaps for %s across %s without rerunning unrelated competitors."
                    .formatted(blankToDefault(competitors, "selected competitors"), blankToDefault(dimensions, "requested dimensions"));
        }
        return "Collect balanced public evidence for %s across %s."
                .formatted(blankToDefault(competitors, "selected competitors"), blankToDefault(dimensions, "requested dimensions"));
    }

    private List<String> focusAreas(AnalysisRun run,
                                    List<SearchQueryPlanner.SearchQueryBatch> batches,
                                    boolean recollecting) {
        Set<String> focus = new LinkedHashSet<>();
        if (recollecting && run.getReviewDecision() != null) {
            run.getReviewDecision().getRepairTasks().stream()
                    .map(this::repairFocus)
                    .filter(StringUtils::hasText)
                    .forEach(focus::add);
        }
        if (focus.isEmpty()) {
            for (SearchQueryPlanner.SearchQueryBatch batch : nullToEmpty(batches)) {
                String dimension = inferDimension(batch.queries());
                focus.add("%s / %s".formatted(batch.competitor(), dimension));
            }
        }
        if (focus.isEmpty() && run.getRequirement() != null) {
            for (String competitor : nullToEmpty(run.getRequirement().getCompetitors())) {
                for (String dimension : nullToEmpty(run.getRequirement().getDimensions())) {
                    focus.add("%s / %s".formatted(competitor, dimension));
                }
            }
        }
        return new ArrayList<>(focus).stream().limit(24).toList();
    }

    private List<String> rationale(AnalysisRun run,
                                   List<SearchQueryPlanner.SearchQueryBatch> batches,
                                   boolean recollecting) {
        List<String> rationale = new ArrayList<>();
        int competitors = run.getRequirement() == null ? 0 : nullToEmpty(run.getRequirement().getCompetitors()).size();
        int batchCount = nullToEmpty(batches).size();
        if (competitors > 1) {
            rationale.add("Use per-competitor subtasks so evidence coverage can be compared fairly.");
        }
        if (batchCount > 0) {
            rationale.add("Create %d search subtasks and keep search/fetch execution in shared bounded pools.".formatted(batchCount));
        }
        if (!nullToEmpty(run.getUserProvidedEvidence()).isEmpty()) {
            rationale.add("Blend user-provided material with public sources before judging coverage gaps.");
        }
        if (recollecting) {
            rationale.add("Prioritize reviewer repair tasks and keep unrelated competitors out of the recollection search scope.");
        }
        if (rationale.isEmpty()) {
            rationale.add("Use rule-based fallback planning because the requirement is still minimal.");
        }
        return rationale;
    }

    private List<String> recommendedSourceTypes(AnalysisRun run, boolean recollecting) {
        Set<String> sourceTypes = new LinkedHashSet<>();
        AnalysisRequirement requirement = run.getRequirement();
        if (requirement != null) {
            nullToEmpty(requirement.getSourcePreferences()).stream()
                    .filter(StringUtils::hasText)
                    .forEach(sourceTypes::add);
            for (String dimension : nullToEmpty(requirement.getDimensions())) {
                sourceTypes.addAll(sourceTypesForDimension(dimension));
            }
        }
        if (recollecting && run.getReviewDecision() != null) {
            nullToEmpty(run.getReviewDecision().getRequiredEvidenceTypes()).forEach(sourceTypes::add);
            run.getReviewDecision().getRepairTasks().stream()
                    .flatMap(task -> nullToEmpty(task.getSourcePreferences()).stream())
                    .forEach(sourceTypes::add);
        }
        if (sourceTypes.isEmpty()) {
            sourceTypes.addAll(List.of("official_site", "product_docs", "pricing_page"));
        }
        return new ArrayList<>(sourceTypes).stream().limit(12).toList();
    }

    private List<String> repairPriorities(AnalysisRun run, boolean recollecting) {
        if (!recollecting || run.getReviewDecision() == null
                || run.getReviewDecision().getAction() != ReviewAction.RECOLLECT_EVIDENCE) {
            return List.of();
        }
        return run.getReviewDecision().getRepairTasks().stream()
                .map(this::repairPriority)
                .filter(StringUtils::hasText)
                .distinct()
                .limit(12)
                .toList();
    }

    private String repairFocus(ReviewRepairTask task) {
        String competitor = blankToDefault(task.getCompetitorName(), "unscoped competitor");
        List<String> repairSignals = new ArrayList<>();
        if (StringUtils.hasText(task.getInstruction())) {
            repairSignals.add(task.getInstruction());
        }
        if (StringUtils.hasText(task.getExpectedFix())) {
            repairSignals.add(task.getExpectedFix());
        }
        String dimension = blankToDefault(task.getDimension(), inferDimension(repairSignals));
        return "%s / %s".formatted(competitor, dimension);
    }

    private String repairPriority(ReviewRepairTask task) {
        String target = repairFocus(task);
        List<String> sourcePreferences = nullToEmpty(task.getSourcePreferences());
        String sourceTypes = join(sourcePreferences.isEmpty() ? task.getRequiredEvidenceTypes() : sourcePreferences);
        return "%s -> %s".formatted(target, blankToDefault(sourceTypes, "reviewer-required evidence"));
    }

    private List<String> sourceTypesForDimension(String dimension) {
        String normalized = normalize(dimension);
        if (containsAny(normalized, "pricing", "price", "定价", "价格")) {
            return List.of("pricing_page", "official_site");
        }
        if (containsAny(normalized, "review", "feedback", "用户", "评价")) {
            return List.of("public_reviews", "third_party_report");
        }
        if (containsAny(normalized, "security", "permission", "安全", "权限", "合规")) {
            return List.of("security", "official_site");
        }
        if (containsAny(normalized, "customer", "case", "客户", "案例")) {
            return List.of("case_study", "official_site");
        }
        if (containsAny(normalized, "release", "changelog", "发布", "更新")) {
            return List.of("release_notes", "technical_blog");
        }
        return List.of("official_site", "product_docs");
    }

    private String inferDimension(List<String> texts) {
        String joined = nullToEmpty(texts).stream().filter(StringUtils::hasText).collect(Collectors.joining(" "));
        String normalized = normalize(joined);
        if (containsAny(normalized, "pricing", "price", "定价", "价格")) {
            return "pricing";
        }
        if (containsAny(normalized, "review", "feedback", "用户", "评价")) {
            return "reviews";
        }
        if (containsAny(normalized, "security", "permission", "安全", "权限", "合规")) {
            return "security";
        }
        if (containsAny(normalized, "release", "changelog", "发布", "更新")) {
            return "release";
        }
        return "public_search";
    }

    private boolean containsAny(String text, String... values) {
        for (String value : values) {
            if (text.contains(normalize(value))) {
                return true;
            }
        }
        return false;
    }

    private String join(List<String> values) {
        return nullToEmpty(values).stream()
                .filter(StringUtils::hasText)
                .collect(Collectors.joining(", "));
    }

    private String blankToDefault(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private <T> List<T> nullToEmpty(List<T> values) {
        return values == null ? List.of() : values;
    }
}
