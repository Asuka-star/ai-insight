package com.aiinsight.service;

import com.aiinsight.model.run.AnalysisRequirement;
import com.aiinsight.model.run.AnalysisRun;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class SearchQueryPlanner {

    private static final int MAX_SEARCH_QUERIES = 8;

    public List<String> plan(AnalysisRun run, boolean recollecting) {
        AnalysisRequirement requirement = run.getRequirement();
        Set<String> queries = new LinkedHashSet<>();
        String domain = domainTerm(requirement);
        for (String competitor : requirement.getCompetitors()) {
            if (!StringUtils.hasText(competitor)) {
                continue;
            }
            addQuery(queries, competitor, "official product documentation", domain);
            if (shouldCollectPricing(requirement, recollecting)) {
                addQuery(queries, competitor, "pricing plans enterprise", domain);
            }
            if (shouldCollectFeedback(requirement, recollecting)) {
                addQuery(queries, competitor, "user reviews customer feedback", domain);
            }
            for (String sourcePreference : requirement.getSourcePreferences()) {
                addSourcePreferenceQuery(queries, competitor, sourcePreference, domain);
                if (queries.size() >= MAX_SEARCH_QUERIES) {
                    return queries.stream().limit(MAX_SEARCH_QUERIES).toList();
                }
            }
            for (String dimension : requirement.getDimensions()) {
                if (StringUtils.hasText(dimension)) {
                    addQuery(queries, competitor, dimension, domain);
                }
                if (queries.size() >= MAX_SEARCH_QUERIES) {
                    return queries.stream().limit(MAX_SEARCH_QUERIES).toList();
                }
            }
            if (queries.size() >= MAX_SEARCH_QUERIES) {
                break;
            }
        }
        return queries.stream().limit(MAX_SEARCH_QUERIES).toList();
    }

    private void addSourcePreferenceQuery(Set<String> queries, String competitor, String sourcePreference, String domain) {
        if (!StringUtils.hasText(sourcePreference)) {
            return;
        }
        String normalized = sourcePreference.toLowerCase(Locale.ROOT);
        if (containsAny(normalized, "pricing", "价格", "定价")) {
            addQuery(queries, competitor, "pricing", domain);
            return;
        }
        if (containsAny(normalized, "review", "评价", "反馈")) {
            addQuery(queries, competitor, "reviews", domain);
            return;
        }
        if (containsAny(normalized, "doc", "documentation", "文档", "official", "官网")) {
            addQuery(queries, competitor, "official documentation", domain);
            return;
        }
        if (containsAny(normalized, "release", "changelog", "更新")) {
            addQuery(queries, competitor, "release notes changelog", domain);
            return;
        }
        if (containsAny(normalized, "security", "安全", "权限", "合规")) {
            addQuery(queries, competitor, "security permissions compliance", domain);
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
