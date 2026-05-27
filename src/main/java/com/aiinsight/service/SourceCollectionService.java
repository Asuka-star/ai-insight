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
    private static final int MIN_SEARCH_FETCH_TEXT_LENGTH = 180;
    private static final int MAX_RESULTS_PER_QUERY = 3;
    private static final int MAX_SEARCH_SOURCES = 8;

    private final WebPageFetchService webPageFetchService;
    private final SearchProvider searchProvider;
    private final SearchQueryPlanner searchQueryPlanner;
    private final SourceTypeClassifier sourceTypeClassifier;

    @Autowired
    public SourceCollectionService(WebPageFetchService webPageFetchService,
                                   SearchProvider searchProvider,
                                   SearchQueryPlanner searchQueryPlanner) {
        this(webPageFetchService, searchProvider, searchQueryPlanner, new SourceTypeClassifier());
    }

    SourceCollectionService(WebPageFetchService webPageFetchService,
                            SearchProvider searchProvider,
                            SearchQueryPlanner searchQueryPlanner,
                            SourceTypeClassifier sourceTypeClassifier) {
        this.webPageFetchService = webPageFetchService;
        this.searchProvider = searchProvider;
        this.searchQueryPlanner = searchQueryPlanner;
        this.sourceTypeClassifier = sourceTypeClassifier;
    }

    public SourceCollectionService(WebPageFetchService webPageFetchService, SearchProvider searchProvider) {
        this(webPageFetchService, searchProvider, new SearchQueryPlanner());
    }

    public List<EvidenceSource> collect(AnalysisRun run, boolean recollecting) {
        List<EvidenceSource> sources = new ArrayList<>(run.getEvidenceSources());
        Set<String> seenUrls = new LinkedHashSet<>();
        sources.stream()
                .map(EvidenceSource::getUrl)
                .filter(StringUtils::hasText)
                .map(this::normalizeUrl)
                .forEach(seenUrls::add);

        int index = maxCitationNumber(sources) + 1;
        for (UserProvidedEvidence evidence : run.getUserProvidedEvidence()) {
            EvidenceSource source = fromUserProvidedEvidence("S" + index, evidence);
            if (seenUrls.add(normalizeUrl(source.getUrl()))) {
                sources.add(source);
                index++;
            }
        }

        for (String url : run.getRequirement().getSourceUrls()) {
            if (!seenUrls.add(normalizeUrl(url))) {
                continue;
            }
            EvidenceSource source = fromUserUrl("S" + index, url);
            sources.add(source);
            index++;
            if ("FETCH_FAILED".equals(source.getCollectionStatus()) || "BLOCKED_BY_ROBOTS".equals(source.getCollectionStatus())) {
                run.getRecommendedActions().add("User-provided URL fetch failed: " + url);
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
                sourceTypeClassifier.qualityFor("user_" + normalizedSourceType, "USER_PROVIDED", evidence.isSensitive() ? "INTERNAL_ONLY" : "USER_PROVIDED"),
                "NONE",
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
        EvidenceSource fetched = fromUrl(
                citationKey,
                result.getUrl(),
                "search_result_web_page",
                "Search query=\"" + result.getQuery() + "\", rank=" + result.getRank() + ". ",
                true
        );
        if (fetched != null) {
            log.info("Search result promoted to fetched evidence: citationKey={}, url={}, query={}, rank={}",
                    citationKey,
                    result.getUrl(),
                    result.getQuery(),
                    result.getRank());
            return fetched;
        }
        log.debug("Search result dropped: citationKey={}, url={}, reason=fetch_failed_or_unusable_content, query={}, rank={}",
                citationKey,
                result.getUrl(),
                result.getQuery(),
                result.getRank());
        return null;
    }

    private EvidenceSource fromUserUrl(String citationKey, String url) {
        WebPageFetchService.FetchedPage page;
        try {
            page = webPageFetchService.fetch(url);
        } catch (RuntimeException ex) {
            return failedUserUrl(citationKey, url, "FETCH_FAILED", "Page fetch failed: " + ex.getMessage());
        }
        if (!page.isUsable() || !StringUtils.hasText(page.getRawText())) {
            return failedUserUrl(citationKey, url, page.getStatus(), page.getComplianceNote());
        }
        EvidenceSource source = new EvidenceSource(
                citationKey,
                page.getTitle(),
                page.getUrl(),
                "user_source_url",
                page.getStatus(),
                "LIVE_FETCHED",
                page.getSourceQuality(),
                page.getFailureReason(),
                snippet(page.getRawText()),
                page.getRawText(),
                page.getComplianceNote()
        );
        source.setContentHash(page.getContentHash());
        source.setCacheHit(page.isCacheHit());
        return source;
    }

    private EvidenceSource failedUserUrl(String citationKey, String url, String status, String complianceNote) {
        String normalizedStatus = StringUtils.hasText(status) ? status : "FETCH_FAILED";
        String message = "User-provided URL could not be fetched: " + url;
        return new EvidenceSource(
                citationKey,
                url,
                url,
                "user_source_url",
                normalizedStatus,
                "FETCH_FAILED",
                "UNUSABLE",
                normalizedStatus,
                message,
                "",
                complianceNote
        );
    }

    private EvidenceSource fromUrl(String citationKey,
                                   String url,
                                   String sourceType,
                                   String compliancePrefix,
                                   boolean requireUsefulFetchedContent) {
        WebPageFetchService.FetchedPage page;
        try {
            page = webPageFetchService.fetch(url);
        } catch (RuntimeException ex) {
            return null;
        }
        if (!page.isUsable() || !StringUtils.hasText(page.getRawText())) {
            return null;
        }
        String unusableReason = searchFetchedContentIssue(page);
        if (requireUsefulFetchedContent && unusableReason != null) {
            log.debug("Fetched search result dropped: citationKey={}, url={}, reason={}, title={}, rawTextChars={}",
                    citationKey,
                    url,
                    unusableReason,
                    page.getTitle(),
                    page.getRawText().length());
            return null;
        }
        EvidenceSource source = new EvidenceSource(
                citationKey,
                page.getTitle(),
                page.getUrl(),
                page.getSourceType(),
                page.getStatus(),
                "LIVE_FETCHED",
                page.getSourceQuality(),
                page.getFailureReason(),
                snippet(page.getRawText()),
                page.getRawText(),
                compliancePrefix + page.getComplianceNote() + " requestedSourceType=" + sourceType + "."
        );
        source.setContentHash(page.getContentHash());
        source.setCacheHit(page.isCacheHit());
        return source;
    }

    private String searchFetchedContentIssue(WebPageFetchService.FetchedPage page) {
        String title = page.getTitle() == null ? "" : page.getTitle().toLowerCase(Locale.ROOT);
        String text = page.getRawText() == null ? "" : page.getRawText().toLowerCase(Locale.ROOT);
        String searchable = title + " " + text;
        if (containsAny(searchable,
                "301 moved permanently",
                "302 found",
                "403 forbidden",
                "just a moment",
                "attention required",
                "enable javascript and cookies",
                "sorry, you have been blocked",
                "cloudflare ray id",
                "challenge-platform")) {
            return "anti_bot_or_redirect_page";
        }
        if (text.length() < MIN_SEARCH_FETCH_TEXT_LENGTH) {
            return "thin_page_text";
        }
        return null;
    }

    private boolean containsIgnoreCase(String text, String pattern) {
        return text != null && pattern != null && text.toLowerCase(Locale.ROOT).contains(pattern.toLowerCase(Locale.ROOT));
    }

    private boolean containsAny(String text, String... patterns) {
        for (String pattern : patterns) {
            if (text.contains(pattern)) {
                return true;
            }
        }
        return false;
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
