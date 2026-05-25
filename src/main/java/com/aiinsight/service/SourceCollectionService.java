package com.aiinsight.service;

import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.model.run.EvidenceSource;
import com.aiinsight.model.run.UserProvidedEvidence;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class SourceCollectionService {

    private static final int SNIPPET_LENGTH = 220;
    private static final int MAX_SEARCH_QUERIES = 8;
    private static final int MAX_RESULTS_PER_QUERY = 3;
    private static final int MAX_SEARCH_SOURCES = 8;

    private final WebPageFetchService webPageFetchService;
    private final SearchProvider searchProvider;

    public List<EvidenceSource> collect(AnalysisRun run, boolean recollecting) {
        List<EvidenceSource> sources = new ArrayList<>();
        int index = 1;
        // 用户明确提供的调研材料优先进入证据链，保留最低 citation 编号。
        for (UserProvidedEvidence evidence : run.getUserProvidedEvidence()) {
            sources.add(fromUserProvidedEvidence("S" + index, evidence));
            index++;
        }
        for (String url : run.getRequirement().getSourceUrls()) {
            EvidenceSource source = fromUrl("S" + index, url, "user_source_url", "");
            if (source != null) {
                sources.add(source);
                index++;
            } else {
                run.getRecommendedActions().add("用户提供的公开 URL 抓取失败，已跳过：" + url);
            }
        }
        appendSearchEvidence(run, sources, index, recollecting);
        return sources;
    }

    public EvidenceSource fromUserProvidedEvidence(String citationKey, UserProvidedEvidence evidence) {
        String sourceType = StringUtils.hasText(evidence.getSourceType()) ? evidence.getSourceType() : "note";
        String normalizedSourceType = sourceType.toLowerCase(Locale.ROOT);
        String url = StringUtils.hasText(evidence.getUrl())
                ? evidence.getUrl()
                : "user-evidence://" + evidence.getId();
        String researchNote = firstPartyResearchNote(normalizedSourceType);
        String complianceNote = evidence.isSensitive()
                ? "User-provided sensitive source. Treat as internal-only evidence and avoid public redistribution."
                : "User-provided source. Use only for this analysis run.";
        if (StringUtils.hasText(researchNote)) {
            complianceNote = complianceNote + " " + researchNote;
        }
        return new EvidenceSource(
                citationKey,
                evidence.getTitle(),
                url,
                "user_" + normalizedSourceType,
                "USER_PROVIDED",
                evidence.isSensitive() ? "INTERNAL_ONLY" : "USER_PROVIDED",
                snippet(evidence.getContent()),
                evidence.getContent(),
                complianceNote
        );
    }

    private String firstPartyResearchNote(String sourceType) {
        if (containsIgnoreCase(sourceType, "interview")) {
            return "First-party interview evidence; extract roles, pain points, quotes, and decision signals before final reporting.";
        }
        if (containsIgnoreCase(sourceType, "survey")) {
            return "First-party survey evidence; preserve sample size, question wording, and response distribution when summarizing.";
        }
        return "";
    }

    private void appendSearchEvidence(AnalysisRun run, List<EvidenceSource> sources, int index, boolean recollecting) {
        List<String> queries = buildSearchQueries(run, recollecting);
        if (queries.isEmpty()) {
            return;
        }
        if (!searchProvider.isAvailable()) {
            run.getRecommendedActions().add("搜索服务未配置：请设置 TAVILY_API_KEY，或在公开来源 URL 中手动补充可抓取页面。");
            return;
        }
        Set<String> seenUrls = new LinkedHashSet<>();
        sources.stream()
                .map(EvidenceSource::getUrl)
                .filter(StringUtils::hasText)
                .map(this::normalizeUrl)
                .forEach(seenUrls::add);
        int added = 0;
        int nextIndex = index;
        for (String query : queries) {
            List<SearchResult> results;
            try {
                results = searchProvider.search(query, MAX_RESULTS_PER_QUERY);
            } catch (RuntimeException ex) {
                run.getRecommendedActions().add("搜索查询失败：" + query + "；" + ex.getMessage());
                continue;
            }
            for (SearchResult result : results) {
                if (!StringUtils.hasText(result.getUrl())) {
                    continue;
                }
                String normalizedUrl = normalizeUrl(result.getUrl());
                if (!seenUrls.add(normalizedUrl)) {
                    continue;
                }
                EvidenceSource source = fromSearchResult("S" + nextIndex, result);
                if (source != null) {
                    sources.add(source);
                    nextIndex++;
                    added++;
                }
                if (added >= MAX_SEARCH_SOURCES) {
                    return;
                }
            }
        }
        if (added == 0) {
            run.getRecommendedActions().add("搜索服务已调用，但没有形成可用网页证据；请补充 URL、问卷结果或访谈记录。");
        }
    }

    private EvidenceSource fromSearchResult(String citationKey, SearchResult result) {
        EvidenceSource fetched = fromUrl(
                citationKey,
                result.getUrl(),
                "search_result_web_page",
                "Search query=\"" + result.getQuery() + "\", rank=" + result.getRank() + ". "
        );
        if (fetched != null) {
            return fetched;
        }
        if (!StringUtils.hasText(result.getSnippet())) {
            return null;
        }
        return new EvidenceSource(
                citationKey,
                result.getTitle(),
                result.getUrl(),
                "search_result_snippet",
                "FETCH_FAILED",
                "SEARCH_RESULT_SNIPPET",
                snippet(result.getSnippet()),
                result.getSnippet(),
                "Search result snippet only; page fetch failed or was not usable. "
                        + "Search query=\"" + result.getQuery() + "\", rank=" + result.getRank()
        );
    }

    private EvidenceSource fromUrl(String citationKey, String url, String sourceType, String compliancePrefix) {
        WebPageFetchService.FetchedPage page;
        try {
            page = webPageFetchService.fetch(url);
        } catch (RuntimeException ex) {
            return null;
        }
        if (!page.isUsable() || !StringUtils.hasText(page.getRawText())) {
            return null;
        }
        return new EvidenceSource(
                citationKey,
                page.getTitle(),
                page.getUrl(),
                sourceType,
                page.getStatus(),
                "LIVE_FETCHED",
                snippet(page.getRawText()),
                page.getRawText(),
                compliancePrefix + page.getComplianceNote()
        );
    }

    private List<String> buildSearchQueries(AnalysisRun run, boolean recollecting) {
        Set<String> queries = new LinkedHashSet<>();
        for (String competitor : run.getRequirement().getCompetitors()) {
            if (!StringUtils.hasText(competitor)) {
                continue;
            }
            queries.add(competitor + " official product documentation AI collaboration");
            if (shouldCollectPricing(run, recollecting)) {
                queries.add(competitor + " pricing plans enterprise");
            }
            if (shouldCollectFeedback(run, recollecting)) {
                queries.add(competitor + " user reviews AI collaboration");
            }
            run.getRequirement().getDimensions().stream()
                    .filter(StringUtils::hasText)
                    .limit(2)
                    .forEach(dimension -> queries.add(competitor + " " + dimension + " AI collaboration"));
            if (queries.size() >= MAX_SEARCH_QUERIES) {
                break;
            }
        }
        return queries.stream().limit(MAX_SEARCH_QUERIES).toList();
    }

    private boolean shouldCollectPricing(AnalysisRun run, boolean recollecting) {
        return recollecting
                || mentionsAny(run.getRequirement().getSourcePreferences(), "pricing", "价格", "定价")
                || mentionsAny(run.getRequirement().getDimensions(), "pricing", "价格", "定价", "商业模式");
    }

    private boolean shouldCollectFeedback(AnalysisRun run, boolean recollecting) {
        return recollecting
                || mentionsAny(run.getRequirement().getSourcePreferences(), "review", "评价", "反馈", "访谈", "问卷")
                || mentionsAny(run.getRequirement().getDimensions(), "review", "评价", "反馈", "用户");
    }

    private boolean mentionsAny(List<String> values, String... patterns) {
        return values.stream().anyMatch(value -> {
            for (String pattern : patterns) {
                if (containsIgnoreCase(value, pattern)) {
                    return true;
                }
            }
            return false;
        });
    }

    private boolean containsIgnoreCase(String text, String pattern) {
        return text != null && pattern != null && text.toLowerCase(Locale.ROOT).contains(pattern.toLowerCase(Locale.ROOT));
    }

    private String normalizeUrl(String url) {
        return url == null ? "" : url.trim().replaceFirst("/+$", "").toLowerCase(Locale.ROOT);
    }

    private String snippet(String text) {
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= SNIPPET_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, SNIPPET_LENGTH) + "...";
    }
}
