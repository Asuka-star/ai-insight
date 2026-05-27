package com.aiinsight.service;

import com.aiinsight.model.run.AnalysisRequirement;
import com.aiinsight.model.run.AnalysisRun;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class SearchQueryPlanner {

    private static final int MAX_SEARCH_QUERIES = 8;
    private static final int MAX_SEARCH_QUERIES_PER_COMPETITOR = 8;
    private static final List<String> DEFAULT_AUTHORITY_TOPICS = List.of(
            "official site product documentation",
            "official pricing plans",
            "official release notes changelog",
            "official technical blog"
    );

    public record SearchQueryBatch(String competitor, List<String> queries) {
    }

    public List<String> plan(AnalysisRun run, boolean recollecting) {
        return planByCompetitor(run, recollecting).stream()
                .flatMap(batch -> batch.queries().stream())
                .distinct()
                .limit(MAX_SEARCH_QUERIES)
                .toList();
    }

    public List<SearchQueryBatch> planByCompetitor(AnalysisRun run, boolean recollecting) {
        AnalysisRequirement requirement = run.getRequirement();
        String domain = domainTerm(requirement);
        List<SearchQueryBatch> batches = new ArrayList<>();
        for (String competitor : requirement.getCompetitors()) {
            if (!StringUtils.hasText(competitor)) {
                continue;
            }
            Set<String> queries = new LinkedHashSet<>();
            addQuery(queries, competitor, "official site product documentation", domain);
            if (shouldCollectPricing(requirement, recollecting)) {
                addQuery(queries, competitor, "official pricing plans enterprise", domain);
            }
            if (shouldCollectFeedback(requirement, recollecting)) {
                addQuery(queries, competitor, "independent user reviews customer feedback", domain);
            }
            for (String sourcePreference : requirement.getSourcePreferences()) {
                addSourcePreferenceQuery(queries, competitor, sourcePreference, domain);
                if (queries.size() >= MAX_SEARCH_QUERIES_PER_COMPETITOR) {
                    break;
                }
            }
            for (String dimension : requirement.getDimensions()) {
                if (StringUtils.hasText(dimension)) {
                    addQuery(queries, competitor, dimension, domain);
                }
                if (queries.size() >= MAX_SEARCH_QUERIES_PER_COMPETITOR) {
                    break;
                }
            }
            addDefaultAuthorityQueries(queries, competitor, domain);
            if (!queries.isEmpty()) {
                batches.add(new SearchQueryBatch(
                        competitor.trim(),
                        queries.stream().limit(MAX_SEARCH_QUERIES_PER_COMPETITOR).toList()
                ));
            }
        }
        return batches;
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
            if (queries.size() >= MAX_SEARCH_QUERIES_PER_COMPETITOR) {
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
        return values.stream().anyMatch(value -> containsAny(value, patterns));
    }

    private boolean containsAny(String text, String... patterns) {
        for (String pattern : patterns) {
            if (containsIgnoreCase(text, pattern)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsIgnoreCase(String text, String pattern) {
        return text != null && pattern != null && text.toLowerCase(Locale.ROOT).contains(pattern.toLowerCase(Locale.ROOT));
    }
}
