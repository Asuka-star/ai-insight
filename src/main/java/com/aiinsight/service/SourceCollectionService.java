package com.aiinsight.service;

import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.model.run.EvidenceSource;
import com.aiinsight.model.run.UserProvidedEvidence;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
@Slf4j
public class SourceCollectionService {

    private static final int SNIPPET_LENGTH = 220;
    private static final int MAX_RESULTS_PER_QUERY = 3;
    private static final int MAX_SEARCH_SOURCES = 8;

    private final WebPageFetchService webPageFetchService;
    private final SearchProvider searchProvider;
    private final SearchQueryPlanner searchQueryPlanner;

    @Autowired
    public SourceCollectionService(WebPageFetchService webPageFetchService,
                                   SearchProvider searchProvider,
                                   SearchQueryPlanner searchQueryPlanner) {
        this.webPageFetchService = webPageFetchService;
        this.searchProvider = searchProvider;
        this.searchQueryPlanner = searchQueryPlanner;
    }

    public SourceCollectionService(WebPageFetchService webPageFetchService, SearchProvider searchProvider) {
        this(webPageFetchService, searchProvider, new SearchQueryPlanner());
    }

    public List<EvidenceSource> collect(AnalysisRun run, boolean recollecting) {
        // 采集是 append-only 语义：重跑 Researcher 时保留旧 EvidenceSource，
        // 新来源从当前最大 S 编号继续追加，避免历史 artifact 中的 [S1] 指向变化。
        List<EvidenceSource> sources = new ArrayList<>(run.getEvidenceSources());
        Set<String> seenUrls = new LinkedHashSet<>();
        sources.stream()
                .map(EvidenceSource::getUrl)
                .filter(StringUtils::hasText)
                .map(this::normalizeUrl)
                .forEach(seenUrls::add);
        int index = maxCitationNumber(sources) + 1;
        // 用户明确提供的调研材料优先进入证据链；重跑时保留旧 citation，只追加新增资料。
        for (UserProvidedEvidence evidence : run.getUserProvidedEvidence()) {
            EvidenceSource source = fromUserProvidedEvidence("S" + index, evidence);
            // 用户资料会生成 user-evidence://{id}，因此同一份资料多次重跑不会重复入链。
            if (seenUrls.add(normalizeUrl(source.getUrl()))) {
                sources.add(source);
                index++;
            }
        }
        for (String url : run.getRequirement().getSourceUrls()) {
            if (!seenUrls.add(normalizeUrl(url))) {
                continue;
            }
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

    private int maxCitationNumber(List<EvidenceSource> sources) {
        return sources.stream()
                .map(EvidenceSource::getCitationKey)
                .filter(StringUtils::hasText)
                .mapToInt(this::citationNumber)
                .max()
                .orElse(0);
    }

    private int citationNumber(String citationKey) {
        if (!citationKey.startsWith("S")) {
            return 0;
        }
        try {
            return Integer.parseInt(citationKey.substring(1));
        } catch (NumberFormatException ex) {
            return 0;
        }
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
        // 搜索 query 由任务范围、竞品、维度和返工状态共同决定；
        // 默认优先官方/权威来源，来源偏好只决定重点覆盖类型，不降低来源质量要求。
        // recollecting=true 时会更主动补价格页和用户评价，响应 Reviewer 的证据缺口。
        List<String> queries = searchQueryPlanner.plan(run, recollecting);
        if (queries.isEmpty()) {
            return;
        }
        if (!searchProvider.isAvailable()) {
            log.warn("Source collection search fallback required: runId={}, reason=search_provider_unavailable, queries={}",
                    run.getId(),
                    queries);
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
                log.warn("Source collection search query failed: runId={}, query={}, exceptionType={}, message={}",
                        run.getId(),
                        query,
                        ex.getClass().getName(),
                        ex.getMessage());
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
            log.warn("Source collection produced no search evidence: runId={}, queries={}, existingSources={}",
                    run.getId(),
                    queries,
                    sources.size());
            run.getRecommendedActions().add("搜索服务已调用，但没有形成可用网页证据；请补充 URL、问卷结果或访谈记录。");
        }
    }

    private EvidenceSource fromSearchResult(String citationKey, SearchResult result) {
        // 搜索命中的 URL 仍优先尝试真实抓取；只有抓取失败且搜索摘要可用时，
        // 才降级为 SEARCH_RESULT_SNIPPET，并在 complianceNote 中明确标注。
        EvidenceSource fetched = fromUrl(
                citationKey,
                result.getUrl(),
                "search_result_web_page",
                "Search query=\"" + result.getQuery() + "\", rank=" + result.getRank() + ". "
        );
        if (fetched != null) {
            log.info("Search result promoted to fetched evidence: citationKey={}, url={}, query={}, rank={}",
                    citationKey,
                    result.getUrl(),
                    result.getQuery(),
                    result.getRank());
            return fetched;
        }
        if (!StringUtils.hasText(result.getSnippet())) {
            log.warn("Search result dropped: citationKey={}, url={}, reason=no_snippet_after_fetch_failure, query={}, rank={}",
                    citationKey,
                    result.getUrl(),
                    result.getQuery(),
                    result.getRank());
            return null;
        }
        log.warn("Search result snippet fallback activated: citationKey={}, url={}, reason=page_fetch_failed, query={}, rank={}, snippetChars={}",
                citationKey,
                result.getUrl(),
                result.getQuery(),
                result.getRank(),
                result.getSnippet().length());
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
