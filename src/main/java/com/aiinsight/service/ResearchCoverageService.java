package com.aiinsight.service;

import com.aiinsight.model.enums.AgentName;
import com.aiinsight.model.review.ReviewDecision;
import com.aiinsight.model.review.ReviewRepairTask;
import com.aiinsight.model.run.AnalysisRequirement;
import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.model.run.EvidenceSource;
import com.aiinsight.model.schema.AnalysisClaim;
import com.aiinsight.model.schema.ResearchCollectionPlan;
import com.aiinsight.model.schema.ResearchCoverageGap;
import com.aiinsight.model.schema.ResearchRepairTarget;
import com.aiinsight.model.schema.ResearchSubtask;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ResearchCoverageService {

    public void refreshCoverage(AnalysisRun run) {
        if (run == null || run.getResearchPackage() == null) {
            return;
        }
        ResearchCollectionPlan plan = run.getResearchPackage().getResearchCollectionPlan();
        if (plan == null) {
            return;
        }
        List<ResearchCoverageGap> gaps = coverageGaps(run, plan);
        plan.setCoverageGaps(gaps);
        appendGapRepairTargets(run, plan, gaps);
    }

    public void enrichRepairTasks(AnalysisRun run) {
        ReviewDecision decision = run == null ? null : run.getRepairDecisionFor(AgentName.RESEARCHER);
        if (decision == null || decision.getRepairTasks() == null) {
            return;
        }
        for (ReviewRepairTask task : decision.getRepairTasks()) {
            enrichRepairTask(run, task);
        }
    }

    public void refreshRepairTargets(AnalysisRun run) {
        if (run == null || run.getResearchPackage() == null) {
            return;
        }
        ResearchCollectionPlan plan = run.getResearchPackage().getResearchCollectionPlan();
        if (plan == null) {
            return;
        }
        List<ResearchRepairTarget> targets = new ArrayList<>();
        if (plan.getRepairTargets() != null) {
            plan.getRepairTargets().stream()
                    .filter(target -> target.getRepairTaskId() == null)
                    .forEach(targets::add);
        }
        ReviewDecision decision = run.getRepairDecisionFor(AgentName.RESEARCHER);
        if (decision != null && decision.getRepairTasks() != null) {
            decision.getRepairTasks().stream()
                    .map(task -> repairTarget(run, task))
                    .forEach(targets::add);
        }
        plan.setRepairTargets(deduplicateTargets(targets));
    }

    public ResearchRepairTarget repairTarget(AnalysisRun run, ReviewRepairTask task) {
        ResearchRepairTarget target = new ResearchRepairTarget();
        target.setRunId(run.getId());
        target.setRepairTaskId(task.getId() == null ? null : task.getId().toString());
        target.setFindingId(task.getFindingId());
        target.setCompetitorName(task.getCompetitorName());
        target.setDimension(task.getDimension());
        target.setSourcePreferences(new ArrayList<>(nullToEmpty(task.getSourcePreferences())));
        target.setQueries(new ArrayList<>(nullToEmpty(task.getQueries())));
        target.setReason(firstText(task.getInstruction(), task.getExpectedFix(), task.getAcceptanceCriteria()));
        target.setPriority("REVIEW_REPAIR");
        return target;
    }

    private List<ResearchCoverageGap> coverageGaps(AnalysisRun run, ResearchCollectionPlan plan) {
        List<ResearchCoverageGap> gaps = new ArrayList<>();
        for (String competitor : competitors(run, plan)) {
            for (String dimension : dimensions(run, plan)) {
                int required = requiredEvidenceCount(run, plan, competitor, dimension);
                int existing = acceptedEvidenceCount(run, competitor, dimension);
                Set<String> missingSourceTypes = missingSourceTypes(run, competitor, dimension);
                ResearchSubtask subtask = matchingSubtask(plan, competitor, dimension);
                if (subtask != null && subtask.getAcceptedEvidenceCount() > 0) {
                    existing = Math.max(existing, subtask.getAcceptedEvidenceCount());
                }
                if (existing >= required && missingSourceTypes.isEmpty()) {
                    continue;
                }
                ResearchCoverageGap gap = new ResearchCoverageGap();
                gap.setRunId(run.getId());
                gap.setCompetitorName(competitor);
                gap.setDimension(dimension);
                gap.setExistingEvidenceCount(existing);
                gap.setRequiredEvidenceCount(required);
                gap.setMissingSourceTypes(new ArrayList<>(missingSourceTypes));
                gap.setRepairRecommended(existing < required || !missingSourceTypes.isEmpty());
                gap.setReason(gapReason(existing, required, missingSourceTypes));
                gaps.add(gap);
            }
        }
        return gaps;
    }

    private void appendGapRepairTargets(AnalysisRun run, ResearchCollectionPlan plan, List<ResearchCoverageGap> gaps) {
        List<ResearchRepairTarget> targets = plan.getRepairTargets() == null
                ? new ArrayList<>()
                : new ArrayList<>(plan.getRepairTargets());
        targets.removeIf(target -> target.getCoverageGapId() != null);
        for (ResearchCoverageGap gap : gaps) {
            if (!gap.isRepairRecommended()) {
                continue;
            }
            ResearchRepairTarget target = new ResearchRepairTarget();
            target.setRunId(run.getId());
            target.setCoverageGapId(gap.getId());
            target.setCompetitorName(gap.getCompetitorName());
            target.setDimension(gap.getDimension());
            target.setSourcePreferences(sourcePreferences(gap.getDimension(), gap.getMissingSourceTypes()));
            target.setQueries(queries(run, gap.getCompetitorName(), gap.getDimension(), target.getSourcePreferences()));
            target.setReason(gap.getReason());
            target.setPriority("BACKFILL");
            targets.add(target);
        }
        plan.setRepairTargets(deduplicateTargets(targets));
    }

    private void enrichRepairTask(AnalysisRun run, ReviewRepairTask task) {
        String context = repairTaskContext(run, task);
        if (!StringUtils.hasText(task.getCompetitorName())) {
            task.setCompetitorName(inferCompetitor(run, context));
        }
        if (!StringUtils.hasText(task.getDimension())) {
            task.setDimension(inferDimension(context));
        }
        if (task.getSourcePreferences() == null || task.getSourcePreferences().isEmpty()) {
            task.setSourcePreferences(sourcePreferences(task.getDimension(), task.getRequiredEvidenceTypes()));
        }
        if (task.getQueries() == null || task.getQueries().isEmpty()) {
            task.setQueries(queries(run, task.getCompetitorName(), task.getDimension(), task.getSourcePreferences()));
        }
    }

    private String repairTaskContext(AnalysisRun run, ReviewRepairTask task) {
        List<String> parts = new ArrayList<>();
        parts.add(task.getCategory());
        parts.add(task.getInstruction());
        parts.add(task.getExpectedFix());
        parts.add(task.getAcceptanceCriteria());
        parts.add(task.getExcerpt());
        parts.add(task.getCurrentText());
        if (StringUtils.hasText(task.getClaimId())) {
            run.getClaims().stream()
                    .filter(claim -> task.getClaimId().equals(claim.getId()))
                    .findFirst()
                    .ifPresent(claim -> {
                        parts.add(claim.getContent());
                        parts.addAll(claim.getCompetitorNames());
                    });
        }
        return parts.stream().filter(StringUtils::hasText).collect(Collectors.joining(" "));
    }

    private List<String> competitors(AnalysisRun run, ResearchCollectionPlan plan) {
        Set<String> values = new LinkedHashSet<>();
        AnalysisRequirement requirement = run.getRequirement();
        if (requirement != null) {
            nullToEmpty(requirement.getCompetitors()).stream()
                    .filter(StringUtils::hasText)
                    .map(String::trim)
                    .forEach(values::add);
        }
        nullToEmpty(plan.getCompetitors()).stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .forEach(values::add);
        nullToEmpty(plan.getSubtasks()).stream()
                .map(ResearchSubtask::getCompetitorName)
                .filter(StringUtils::hasText)
                .map(String::trim)
                .forEach(values::add);
        return new ArrayList<>(values);
    }

    private List<String> dimensions(AnalysisRun run, ResearchCollectionPlan plan) {
        Set<String> values = new LinkedHashSet<>();
        AnalysisRequirement requirement = run.getRequirement();
        if (requirement != null) {
            nullToEmpty(requirement.getDimensions()).stream()
                    .filter(StringUtils::hasText)
                    .map(this::inferDimension)
                    .forEach(values::add);
        }
        nullToEmpty(plan.getDimensions()).stream()
                .filter(StringUtils::hasText)
                .map(this::inferDimension)
                .forEach(values::add);
        nullToEmpty(plan.getSubtasks()).stream()
                .map(ResearchSubtask::getDimension)
                .filter(StringUtils::hasText)
                .map(this::inferDimension)
                .forEach(values::add);
        if (values.isEmpty()) {
            values.add("public_search");
        }
        return new ArrayList<>(values);
    }

    private int requiredEvidenceCount(AnalysisRun run, ResearchCollectionPlan plan, String competitor, String dimension) {
        return nullToEmpty(plan.getEvidenceBudgets()).stream()
                .filter(budget -> same(budget.getCompetitorName(), competitor) && same(budget.getDimension(), dimension))
                .mapToInt(budget -> Math.max(1, Math.min(2, budget.getMaxAcceptedSources())))
                .findFirst()
                .orElse(1);
    }

    private int acceptedEvidenceCount(AnalysisRun run, String competitor, String dimension) {
        return (int) run.getEvidenceSources().stream()
                .filter(source -> sourceMatchesCompetitor(source, competitor))
                .filter(source -> sourceMatchesDimension(source, dimension))
                .count();
    }

    private Set<String> missingSourceTypes(AnalysisRun run, String competitor, String dimension) {
        Set<String> expected = new LinkedHashSet<>();
        AnalysisRequirement requirement = run.getRequirement();
        if (requirement != null && !nullToEmpty(requirement.getSourcePreferences()).isEmpty()) {
            expected.addAll(requirement.getSourcePreferences());
        } else {
            expected.addAll(sourcePreferences(dimension, List.of()));
        }
        Set<String> existing = run.getEvidenceSources().stream()
                .filter(source -> sourceMatchesCompetitor(source, competitor))
                .map(EvidenceSource::getSourceType)
                .filter(StringUtils::hasText)
                .map(this::normalizeSourceType)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        expected.removeIf(sourceType -> existing.contains(normalizeSourceType(sourceType)));
        return expected;
    }

    private ResearchSubtask matchingSubtask(ResearchCollectionPlan plan, String competitor, String dimension) {
        return nullToEmpty(plan.getSubtasks()).stream()
                .filter(subtask -> same(subtask.getCompetitorName(), competitor))
                .filter(subtask -> same(subtask.getDimension(), dimension))
                .findFirst()
                .orElse(null);
    }

    private boolean sourceMatchesCompetitor(EvidenceSource source, String competitor) {
        if (!StringUtils.hasText(competitor)) {
            return true;
        }
        String text = "%s %s %s %s".formatted(source.getTitle(), source.getUrl(), source.getSnippet(), source.getRawText());
        return contains(text, competitor);
    }

    private boolean sourceMatchesDimension(EvidenceSource source, String dimension) {
        if (!StringUtils.hasText(dimension) || "public_search".equals(normalize(dimension))) {
            return true;
        }
        String normalized = normalize(dimension);
        String sourceType = normalizeSourceType(source.getSourceType());
        if (sourceType.contains(normalized) || normalized.contains(sourceType)) {
            return true;
        }
        String text = "%s %s %s %s".formatted(source.getTitle(), source.getSourceType(), source.getSnippet(), source.getRawText());
        return dimensionTerms(dimension).stream().anyMatch(term -> contains(text, term));
    }

    private String inferCompetitor(AnalysisRun run, String text) {
        AnalysisRequirement requirement = run.getRequirement();
        if (requirement != null) {
            for (String competitor : nullToEmpty(requirement.getCompetitors())) {
                if (contains(text, competitor)) {
                    return competitor;
                }
            }
        }
        return nullToEmpty(run.getClaims()).stream()
                .flatMap(claim -> nullToEmpty(claim.getCompetitorNames()).stream())
                .filter(competitor -> contains(text, competitor))
                .findFirst()
                .orElse(null);
    }

    private String inferDimension(String text) {
        String normalized = normalize(text);
        if (containsAny(normalized, "pricing", "price", "plans", "价格", "定价", "商业模式")) {
            return "pricing";
        }
        if (containsAny(normalized, "review", "feedback", "评价", "反馈", "用户")) {
            return "reviews";
        }
        if (containsAny(normalized, "security", "permission", "compliance", "安全", "权限", "合规")) {
            return "security";
        }
        if (containsAny(normalized, "customer", "case", "客户", "案例")) {
            return "customers";
        }
        if (containsAny(normalized, "docs", "documentation", "文档")) {
            return "docs";
        }
        if (containsAny(normalized, "release", "changelog", "更新", "发布")) {
            return "release";
        }
        return StringUtils.hasText(text) ? text.trim() : "public_search";
    }

    private List<String> sourcePreferences(String dimension, List<String> requestedTypes) {
        Set<String> preferences = new LinkedHashSet<>();
        nullToEmpty(requestedTypes).stream()
                .filter(StringUtils::hasText)
                .forEach(preferences::add);
        String normalized = normalize(dimension);
        if (containsAny(normalized, "pricing", "price", "定价", "价格")) {
            preferences.add("pricing_page");
            preferences.add("official_site");
        } else if (containsAny(normalized, "review", "feedback", "用户")) {
            preferences.add("user_review");
            preferences.add("third_party_report");
        } else if (containsAny(normalized, "security", "permission", "合规", "安全")) {
            preferences.add("security");
            preferences.add("official_site");
        } else if (containsAny(normalized, "customer", "case", "客户", "案例")) {
            preferences.add("case_study");
            preferences.add("official_site");
        } else if (containsAny(normalized, "release", "changelog", "发布", "更新")) {
            preferences.add("release_notes");
            preferences.add("official_site");
        } else {
            preferences.add("official_site");
            preferences.add("product_docs");
        }
        return new ArrayList<>(preferences);
    }

    private List<String> queries(AnalysisRun run, String competitor, String dimension, List<String> sourcePreferences) {
        if (!StringUtils.hasText(competitor)) {
            return List.of();
        }
        String domain = run.getRequirement() == null ? "" : firstText(run.getRequirement().getIndustry(), run.getRequirement().getOriginalPrompt());
        Set<String> queries = new LinkedHashSet<>();
        for (String sourcePreference : nullToEmpty(sourcePreferences)) {
            queries.add("%s %s %s %s".formatted(competitor, sourcePreference, dimension, domain).replaceAll("\\s+", " ").trim());
            if (queries.size() >= 4) {
                break;
            }
        }
        if (queries.isEmpty()) {
            queries.add("%s %s %s".formatted(competitor, dimension, domain).replaceAll("\\s+", " ").trim());
        }
        return new ArrayList<>(queries);
    }

    private List<ResearchRepairTarget> deduplicateTargets(List<ResearchRepairTarget> targets) {
        Map<String, ResearchRepairTarget> unique = new LinkedHashMap<>();
        for (ResearchRepairTarget target : nullToEmpty(targets)) {
            String key = "%s|%s|%s|%s".formatted(
                    firstText(
                            target.getRepairTaskId(),
                            target.getCoverageGapId() == null ? "" : target.getCoverageGapId().toString(),
                            target.getFindingId(),
                            target.getPriority()
                    ),
                    normalize(target.getCompetitorName()),
                    normalize(target.getDimension()),
                    String.join(",", nullToEmpty(target.getSourcePreferences())).toLowerCase(Locale.ROOT)
            );
            unique.putIfAbsent(key, target);
        }
        return new ArrayList<>(unique.values());
    }

    private String gapReason(int existing, int required, Set<String> missingSourceTypes) {
        if (existing < required && !missingSourceTypes.isEmpty()) {
            return "accepted evidence below budget and missing source types: " + String.join(", ", missingSourceTypes);
        }
        if (existing < required) {
            return "accepted evidence below budget";
        }
        return "missing source types: " + String.join(", ", missingSourceTypes);
    }

    private List<String> dimensionTerms(String dimension) {
        String normalized = normalize(dimension);
        if (containsAny(normalized, "pricing", "price")) {
            return List.of("pricing", "price", "plan", "定价", "价格");
        }
        if (containsAny(normalized, "review", "feedback")) {
            return List.of("review", "feedback", "customer", "用户", "评价");
        }
        if (containsAny(normalized, "security", "permission")) {
            return List.of("security", "permission", "compliance", "安全", "权限");
        }
        return List.of(dimension);
    }

    private String normalizeSourceType(String value) {
        String normalized = normalize(value);
        if (containsAny(normalized, "pricing", "price")) {
            return "pricing_page";
        }
        if (containsAny(normalized, "docs", "documentation", "official")) {
            return "official_site";
        }
        if (containsAny(normalized, "review", "feedback")) {
            return "user_review";
        }
        return normalized;
    }

    private boolean same(String left, String right) {
        return normalize(left).equals(normalize(right));
    }

    private boolean contains(String text, String pattern) {
        return StringUtils.hasText(text) && StringUtils.hasText(pattern)
                && normalize(text).contains(normalize(pattern));
    }

    private boolean containsAny(String text, String... patterns) {
        for (String pattern : patterns) {
            if (contains(text, pattern)) {
                return true;
            }
        }
        return false;
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return "";
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private <T> List<T> nullToEmpty(List<T> values) {
        return values == null ? List.of() : values;
    }
}
