package com.aiinsight.service;

import com.aiinsight.model.run.AnalysisRequirement;
import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.model.enums.AgentName;
import com.aiinsight.model.enums.ReviewAction;
import com.aiinsight.model.review.ReviewDecision;
import com.aiinsight.model.review.ReviewRepairTask;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import static com.aiinsight.util.AgentUtils.containsIgnoreCase;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class SearchQueryPlanner {

    private static final List<String> DEFAULT_AUTHORITY_TOPICS = List.of(
            "official site product documentation",
            "official pricing plans",
            "official release notes changelog",
            "official technical blog"
    );

    private final SearchQueryPlannerProperties properties;

    public SearchQueryPlanner() {
        this(new SearchQueryPlannerProperties());
    }

    @Autowired
    public SearchQueryPlanner(SearchQueryPlannerProperties properties) {
        this.properties = properties == null ? new SearchQueryPlannerProperties() : properties;
    }

    public record SearchQueryBatch(String competitor, List<String> queries) {
    }

    public List<String> plan(AnalysisRun run, boolean recollecting) {
        return planByCompetitor(run, recollecting).stream()
                .flatMap(batch -> batch.queries().stream())
                .distinct()
                .limit(properties.maxSearchQueries())
                .toList();
    }

    public List<SearchQueryBatch> planByCompetitor(AnalysisRun run, boolean recollecting) {
        AnalysisRequirement requirement = run.getRequirement();
        if (requirement == null || requirement.getCompetitors() == null || requirement.getCompetitors().isEmpty()) {
            return List.of();
        }
        String domain = domainTerm(requirement);
        List<SearchQueryBatch> repairBatches = targetedRepairBatches(run, recollecting, domain);
        if (!repairBatches.isEmpty()) {
            return repairBatches;
        }
        List<String> targetedEvidenceTypes = targetedEvidenceTypes(run, recollecting);
        List<SearchQueryBatch> batches = new ArrayList<>();
        for (String competitor : requirement.getCompetitors()) {
            if (!StringUtils.hasText(competitor)) {
                continue;
            }
            Set<String> queries = new LinkedHashSet<>();
            if (!targetedEvidenceTypes.isEmpty()) {
                for (String evidenceType : targetedEvidenceTypes) {
                    addSourcePreferenceQuery(queries, competitor, evidenceType, domain);
                    if (queries.size() >= properties.maxSearchQueriesPerCompetitor()) {
                        break;
                    }
                }
                if (queries.isEmpty()) {
                    addDefaultAuthorityQueries(queries, competitor, domain);
                }
                batches.add(new SearchQueryBatch(
                        competitor.trim(),
                        queries.stream().limit(properties.maxSearchQueriesPerCompetitor()).toList()
                ));
                continue;
            }
            addQuery(queries, competitor, "official site product documentation", domain);
            if (shouldCollectPricing(requirement, recollecting)) {
                addQuery(queries, competitor, "official pricing plans enterprise", domain);
            }
            if (shouldCollectFeedback(requirement, recollecting)) {
                addQuery(queries, competitor, "independent user reviews customer feedback", domain);
            }
            for (String sourcePreference : nullToEmpty(requirement.getSourcePreferences())) {
                addSourcePreferenceQuery(queries, competitor, sourcePreference, domain);
                if (queries.size() >= properties.maxSearchQueriesPerCompetitor()) {
                    break;
                }
            }
            // 官方和权威主题先占住基础名额，防止用户维度过多时把官网、价格页、发布记录挤掉。
            addDefaultAuthorityQueries(queries, competitor, domain);
            for (String dimension : nullToEmpty(requirement.getDimensions())) {
                if (StringUtils.hasText(dimension)) {
                    addQuery(queries, competitor, dimension, domain);
                }
                if (queries.size() >= properties.maxSearchQueriesPerCompetitor()) {
                    break;
                }
            }
            if (!queries.isEmpty()) {
                batches.add(new SearchQueryBatch(
                        competitor.trim(),
                        queries.stream().limit(properties.maxSearchQueriesPerCompetitor()).toList()
                ));
            }
        }
        return batches;
    }

    private List<SearchQueryBatch> targetedRepairBatches(AnalysisRun run, boolean recollecting, String domain) {
        ReviewDecision decision = run.getRepairDecisionFor(AgentName.RESEARCHER);
        if (!recollecting || decision == null
                || decision.getAction() != ReviewAction.RECOLLECT_EVIDENCE
                || decision.getRepairTasks() == null
                || decision.getRepairTasks().isEmpty()) {
            return List.of();
        }
        LinkedHashMap<String, Set<String>> queriesByCompetitor = new LinkedHashMap<>();
        for (ReviewRepairTask task : decision.getRepairTasks()) {
            String competitor = StringUtils.hasText(task.getCompetitorName())
                    ? task.getCompetitorName().trim()
                    : firstMentionedCompetitor(run.getRequirement(), repairTaskText(task));
            if (!StringUtils.hasText(competitor)) {
                continue;
            }
            Set<String> queries = queriesByCompetitor.computeIfAbsent(competitor, ignored -> new LinkedHashSet<>());
            if (task.getQueries() != null && !task.getQueries().isEmpty()) {
                task.getQueries().stream()
                        .filter(StringUtils::hasText)
                        .map(String::trim)
                        .forEach(queries::add);
            }
            List<String> sourcePreferences = task.getSourcePreferences() == null || task.getSourcePreferences().isEmpty()
                    ? task.getRequiredEvidenceTypes()
                    : task.getSourcePreferences();
            if (sourcePreferences == null || sourcePreferences.isEmpty()) {
                sourcePreferences = inferredSourcePreferencesForRepairTask(task);
            }
            for (String sourcePreference : nullToEmpty(sourcePreferences)) {
                addSourcePreferenceQuery(queries, competitor, sourcePreference, domain);
                if (queries.size() >= properties.maxSearchQueriesPerCompetitor()) {
                    break;
                }
            }
            if (StringUtils.hasText(task.getDimension()) && queries.size() < properties.maxSearchQueriesPerCompetitor()) {
                addQuery(queries, competitor, task.getDimension(), domain);
            }
        }
        return queriesByCompetitor.entrySet().stream()
                .filter(entry -> !entry.getValue().isEmpty())
                .map(entry -> new SearchQueryBatch(
                        entry.getKey(),
                        entry.getValue().stream().limit(properties.maxSearchQueriesPerCompetitor()).toList()
                ))
                .toList();
    }

    private List<String> inferredSourcePreferencesForRepairTask(ReviewRepairTask task) {
        String text = "%s %s %s %s %s".formatted(
                task.getCategory(),
                task.getDimension(),
                task.getInstruction(),
                task.getExpectedFix(),
                task.getCurrentText()
        ).toLowerCase(Locale.ROOT);
        if (containsAny(text, "pricing", "price", "plan", "subscription", "billing", "定价", "价格", "套餐", "订阅", "付费")) {
            return List.of("pricing_page", "official_site");
        }
        if (containsAny(text, "security", "permission", "compliance", "privacy", "sso", "scim", "saml", "安全", "权限", "合规", "隐私", "审计")) {
            return List.of("security_docs", "product_docs", "official_site");
        }
        if (containsAny(text, "deployment", "enterprise", "bedrock", "部署", "企业")) {
            return List.of("product_docs", "security_docs", "official_site");
        }
        if (containsAny(text, "agent", "workflow", "mcp", "skills", "工作流", "技能")) {
            return List.of("product_docs", "release_notes", "technical_blog", "official_site");
        }
        return List.of("official_site", "product_docs");
    }

    private String firstMentionedCompetitor(AnalysisRequirement requirement, String text) {
        if (requirement == null || requirement.getCompetitors() == null) {
            return "";
        }
        return requirement.getCompetitors().stream()
                .filter(StringUtils::hasText)
                .filter(competitor -> containsIgnoreCase(text, competitor))
                .findFirst()
                .orElse("");
    }

    private String repairTaskText(ReviewRepairTask task) {
        return "%s %s %s %s %s".formatted(
                task.getInstruction(),
                task.getExpectedFix(),
                task.getAcceptanceCriteria(),
                task.getExcerpt(),
                task.getCurrentText()
        );
    }

    private List<String> targetedEvidenceTypes(AnalysisRun run, boolean recollecting) {
        ReviewDecision decision = run.getRepairDecisionFor(AgentName.RESEARCHER);
        if (!recollecting || decision == null
                || decision.getRequiredEvidenceTypes() == null
                || decision.getRequiredEvidenceTypes().isEmpty()) {
            return List.of();
        }
        return decision.getRequiredEvidenceTypes().stream()
                .filter(StringUtils::hasText)
                .distinct()
                .limit(properties.maxSearchQueriesPerCompetitor())
                .toList();
    }

    private void addSourcePreferenceQuery(Set<String> queries, String competitor, String sourcePreference, String domain) {
        if (!StringUtils.hasText(sourcePreference)) {
            return;
        }
        String normalized = sourcePreference.toLowerCase(Locale.ROOT);
        if (containsAny(normalized, "pricing", "价格", "定价")) {
            addQuery(queries, competitor, "official pricing plans", domain);
            return;
        }
        if (containsAny(normalized, "review", "评价", "反馈")) {
            addQuery(queries, competitor, "independent user reviews customer feedback", domain);
            return;
        }
        if (containsAny(normalized, "survey", "问卷", "调研")) {
            addQuery(queries, competitor, "developer survey user research feedback", domain);
            return;
        }
        if (containsAny(normalized, "interview", "访谈")) {
            addQuery(queries, competitor, "customer interview case study user feedback", domain);
            return;
        }
        if (containsAny(normalized, "security", "permission", "compliance", "privacy", "安全", "权限", "合规")) {
            addQuery(queries, competitor, "official security permissions compliance docs", domain);
            return;
        }
        if (containsAny(normalized, "doc", "documentation", "文档", "official", "官网")) {
            addQuery(queries, competitor, "official site product documentation", domain);
            return;
        }
        if (containsAny(normalized, "release", "changelog", "更新")) {
            addQuery(queries, competitor, "official release notes changelog", domain);
            return;
        }
        if (containsAny(normalized, "blog", "technical", "技术博客", "工程博客")) {
            addQuery(queries, competitor, "official technical blog", domain);
            return;
        }
        if (containsAny(normalized, "media", "report", "authority", "权威", "报道", "行业报告")) {
            addQuery(queries, competitor, "authoritative media industry report", domain);
            return;
        }
        if (containsAny(normalized, "security", "安全", "权限", "合规")) {
            addQuery(queries, competitor, "security permissions compliance", domain);
        }
    }

    private void addDefaultAuthorityQueries(Set<String> queries, String competitor, String domain) {
        for (String topic : DEFAULT_AUTHORITY_TOPICS) {
            addQuery(queries, competitor, topic, domain);
            if (queries.size() >= properties.maxSearchQueriesPerCompetitor()) {
                return;
            }
        }
    }

    private void addQuery(Set<String> queries, String competitor, String topic, String domain) {
        String query = "%s %s %s".formatted(competitor, topic, domain).replaceAll("\\s+", " ").trim();
        if (StringUtils.hasText(query)) {
            queries.add(query);
        }
    }

    private String domainTerm(AnalysisRequirement requirement) {
        if (StringUtils.hasText(requirement.getIndustry()) && !"待澄清行业".equals(requirement.getIndustry())) {
            return requirement.getIndustry();
        }
        if (StringUtils.hasText(requirement.getOriginalPrompt())) {
            return requirement.getOriginalPrompt();
        }
        return "";
    }

    private boolean shouldCollectPricing(AnalysisRequirement requirement, boolean recollecting) {
        return recollecting
                || mentionsAny(requirement.getSourcePreferences(), "pricing", "价格", "定价")
                || mentionsAny(requirement.getDimensions(), "pricing", "价格", "定价", "商业模式");
    }

    private boolean shouldCollectFeedback(AnalysisRequirement requirement, boolean recollecting) {
        return recollecting
                || mentionsAny(requirement.getSourcePreferences(), "review", "评价", "反馈", "访谈", "问卷")
                || mentionsAny(requirement.getDimensions(), "review", "评价", "反馈", "用户");
    }

    private boolean mentionsAny(List<String> values, String... patterns) {
        return nullToEmpty(values).stream().anyMatch(value -> containsAny(value, patterns));
    }

    private boolean containsAny(String text, String... patterns) {
        for (String pattern : patterns) {
            if (containsIgnoreCase(text, pattern)) {
                return true;
            }
        }
        return false;
    }

    private List<String> nullToEmpty(List<String> values) {
        return values == null ? List.of() : values;
    }
}
