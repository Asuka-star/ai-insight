package com.aiinsight.service;

import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.model.run.EvidenceSource;
import com.aiinsight.model.run.UserProvidedEvidence;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
@Slf4j
public class SourceCollectionService {

    private static final int SNIPPET_LENGTH = 220;
    private static final int MIN_SEARCH_FETCH_TEXT_LENGTH = 180;
    private static final int MAX_RESULTS_PER_QUERY = 3;
    private static final int MIN_SEARCH_SOURCES = 12;
    private static final int SMALL_BATCH_SEARCH_SOURCES_PER_COMPETITOR = 3;
    private static final int LARGE_BATCH_SEARCH_SOURCES_PER_COMPETITOR = 2;
    private static final int LARGE_BATCH_COMPETITOR_THRESHOLD = 12;
    private static final int HARD_MAX_SEARCH_SOURCES = 60;

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
        return collect(run, recollecting, List.of());
    }

    public List<EvidenceSource> collect(AnalysisRun run,
                                        boolean recollecting,
                                        List<SearchQueryPlanner.SearchQueryBatch> plannedSearchBatches) {
        run.getResearchPackage().setActualSearchQueries(List.of());
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

        appendSearchEvidence(run, sources, index, recollecting, plannedSearchBatches);
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

    private void appendSearchEvidence(AnalysisRun run,
                                      List<EvidenceSource> sources,
                                      int index,
                                      boolean recollecting,
                                      List<SearchQueryPlanner.SearchQueryBatch> plannedSearchBatches) {
        List<SearchQueryPlanner.SearchQueryBatch> batches = searchBatches(run, recollecting, plannedSearchBatches);
        if (batches.isEmpty()) {
            return;
        }
        List<String> queries = batches.stream()
                .flatMap(batch -> batch.queries().stream())
                .toList();
        run.getResearchPackage().setActualSearchQueries(queries);
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

        int sourcesPerCompetitor = searchSourcesPerCompetitor(batches.size());
        int maxSearchSources = maxSearchSources(batches.size(), sourcesPerCompetitor);
        List<SearchBatchResult> batchResults = collectSearchBatches(run, batches, seenUrls, sourcesPerCompetitor, recollecting);
        batchResults.stream()
                .flatMap(result -> result.failures().stream())
                .forEach(run.getRecommendedActions()::add);

        int added = 0;
        int nextIndex = index;
        int maxCandidates = batchResults.stream()
                .mapToInt(result -> result.sources().size())
                .max()
                .orElse(0);
        for (int candidateIndex = 0; candidateIndex < maxCandidates && added < maxSearchSources; candidateIndex++) {
            for (SearchBatchResult batchResult : batchResults) {
                if (candidateIndex >= batchResult.sources().size()) {
                    continue;
                }
                EvidenceSource source = batchResult.sources().get(candidateIndex);
                if (!seenUrls.add(normalizeUrl(source.getUrl()))) {
                    continue;
                }
                String citationKey = "S" + nextIndex;
                source.setCitationKey(citationKey);
                sources.add(source);
                nextIndex++;
                added++;
                log.info("Search result promoted to fetched evidence: citationKey={}, url={}, competitor={}",
                        citationKey,
                        source.getUrl(),
                        batchResult.competitor());
                if (added >= maxSearchSources) {
                    break;
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

    private List<SearchQueryPlanner.SearchQueryBatch> searchBatches(AnalysisRun run,
                                                                    boolean recollecting,
                                                                    List<SearchQueryPlanner.SearchQueryBatch> plannedSearchBatches) {
        List<SearchQueryPlanner.SearchQueryBatch> batches;
        if (plannedSearchBatches == null || plannedSearchBatches.isEmpty()) {
            batches = searchQueryPlanner.planByCompetitor(run, recollecting);
        } else if (recollecting) {
            batches = plannedSearchBatches;
        } else {
            List<SearchQueryPlanner.SearchQueryBatch> ruleBatches = searchQueryPlanner.planByCompetitor(run, false);
            Set<String> plannedCompetitors = plannedSearchBatches.stream()
                    .map(SearchQueryPlanner.SearchQueryBatch::competitor)
                    .map(this::normalizeText)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            List<SearchQueryPlanner.SearchQueryBatch> merged = new ArrayList<>(plannedSearchBatches);
            ruleBatches.stream()
                    .filter(batch -> !plannedCompetitors.contains(normalizeText(batch.competitor())))
                    .forEach(merged::add);
            batches = merged;
        }
        return focusRecollectionBatches(run, recollecting, batches);
    }

    private List<SearchQueryPlanner.SearchQueryBatch> focusRecollectionBatches(AnalysisRun run,
                                                                               boolean recollecting,
                                                                               List<SearchQueryPlanner.SearchQueryBatch> batches) {
        if (!recollecting || batches.isEmpty()) {
            return batches;
        }
        Set<String> focusedCompetitors = focusedRepairCompetitors(run);
        if (focusedCompetitors.isEmpty()) {
            return batches;
        }
        List<SearchQueryPlanner.SearchQueryBatch> focusedBatches = batches.stream()
                .filter(batch -> focusedCompetitors.contains(normalizeText(batch.competitor())))
                .toList();
        if (focusedBatches.isEmpty()) {
            return batches;
        }
        log.info("Focused recollection search batches: runId={}, competitors={}, originalBatches={}, focusedBatches={}",
                run.getId(),
                focusedCompetitors,
                batches.size(),
                focusedBatches.size());
        return focusedBatches;
    }

    private int searchSourcesPerCompetitor(int competitorCount) {
        if (competitorCount > LARGE_BATCH_COMPETITOR_THRESHOLD) {
            return LARGE_BATCH_SEARCH_SOURCES_PER_COMPETITOR;
        }
        return SMALL_BATCH_SEARCH_SOURCES_PER_COMPETITOR;
    }

    private int maxSearchSources(int competitorCount, int sourcesPerCompetitor) {
        return Math.min(
                HARD_MAX_SEARCH_SOURCES,
                Math.max(MIN_SEARCH_SOURCES, competitorCount * sourcesPerCompetitor)
        );
    }

    private List<SearchBatchResult> collectSearchBatches(AnalysisRun run,
                                                         List<SearchQueryPlanner.SearchQueryBatch> batches,
                                                         Set<String> seenUrls,
                                                         int sourcesPerCompetitor,
                                                         boolean recollecting) {
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(batches.size(), 6));
        try {
            Set<String> existingSeenUrls = new LinkedHashSet<>(seenUrls);
            List<CompletableFuture<SearchBatchResult>> futures = batches.stream()
                    .map(batch -> CompletableFuture.supplyAsync(
                            () -> collectSearchBatch(
                                    run,
                                    batch,
                                    existingSeenUrls,
                                    searchSourcesForBatch(run, batch, sourcesPerCompetitor, recollecting)
                            ),
                            executor
                    ))
                    .toList();
            return futures.stream()
                    .map(this::joinSearchBatch)
                    .filter(result -> result != SearchBatchResult.EMPTY)
                    .toList();
        } finally {
            executor.shutdown();
        }
    }

    private int searchSourcesForBatch(AnalysisRun run,
                                      SearchQueryPlanner.SearchQueryBatch batch,
                                      int baseSourcesPerCompetitor,
                                      boolean recollecting) {
        if (!recollecting || run.getReviewDecision() == null || run.getReviewDecision().getRepairTasks().isEmpty()) {
            return baseSourcesPerCompetitor;
        }
        // Reviewer 打回后不平均加大所有竞品的搜索量，而是优先给 repairTasks 中被点名的竞品
        // 多留抓取名额；其他竞品保留少量兜底，避免完全错过新的公开资料。
        Set<String> focusedCompetitors = focusedRepairCompetitors(run);
        if (focusedCompetitors.isEmpty()) {
            return baseSourcesPerCompetitor;
        }
        if (focusedCompetitors.contains(normalizeText(batch.competitor()))) {
            return Math.min(baseSourcesPerCompetitor + 2, 5);
        }
        return Math.max(1, baseSourcesPerCompetitor - 1);
    }

    private Set<String> focusedRepairCompetitors(AnalysisRun run) {
        // repairTasks 里可能只记录 claim/category/recommendation，这里用文本回扫竞品名，
        // 把“哪几个竞品需要补证据”从 Reviewer 输出中提取出来。
        String repairText = run.getReviewDecision().getRepairTasks().stream()
                .map(task -> "%s %s %s %s".formatted(
                        task.getInstruction(),
                        task.getAcceptanceCriteria(),
                        task.getClaimId(),
                        task.getCategory()
                ))
                .collect(java.util.stream.Collectors.joining(" "));
        repairText = repairText + " " + String.join(" ", run.getReviewDecision().getRepairInstructions());
        Set<String> focused = new LinkedHashSet<>();
        for (String competitor : run.getRequirement().getCompetitors()) {
            if (containsIgnoreCase(repairText, competitor)) {
                focused.add(normalizeText(competitor));
            }
        }
        return focused;
    }

    private SearchBatchResult joinSearchBatch(CompletableFuture<SearchBatchResult> future) {
        try {
            return future.join();
        } catch (CompletionException ex) {
            log.warn("Source collection search batch failed unexpectedly: exceptionType={}, message={}",
                    ex.getClass().getName(),
                    ex.getMessage());
            return SearchBatchResult.EMPTY;
        }
    }

    private SearchBatchResult collectSearchBatch(AnalysisRun run,
                                                 SearchQueryPlanner.SearchQueryBatch batch,
                                                 Set<String> existingSeenUrls,
                                                 int sourcesPerCompetitor) {
        List<EvidenceSource> collected = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        Set<String> localSeenUrls = new LinkedHashSet<>(existingSeenUrls);
        for (String query : batch.queries()) {
            if (collected.size() >= sourcesPerCompetitor) {
                break;
            }
            List<SearchResult> results;
            try {
                results = searchProvider.search(query, MAX_RESULTS_PER_QUERY);
            } catch (RuntimeException ex) {
                log.warn("Source collection search query failed: runId={}, competitor={}, query={}, exceptionType={}, message={}",
                        run.getId(),
                        batch.competitor(),
                        query,
                        ex.getClass().getName(),
                        ex.getMessage());
                failures.add("Search query failed: " + query + ": " + ex.getMessage());
                continue;
            }
            for (SearchResult result : results) {
                if (!StringUtils.hasText(result.getUrl())) {
                    continue;
                }
                if (!localSeenUrls.add(normalizeUrl(result.getUrl()))) {
                    continue;
                }
                EvidenceSource source = fromSearchResult("", result);
                if (source != null) {
                    collected.add(source);
                }
                if (collected.size() >= sourcesPerCompetitor) {
                    break;
                }
            }
        }
        return new SearchBatchResult(batch.competitor(), collected, failures);
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
            if (StringUtils.hasText(citationKey)) {
                log.info("Search result promoted to fetched evidence: citationKey={}, url={}, query={}, rank={}",
                        citationKey,
                        result.getUrl(),
                        result.getQuery(),
                        result.getRank());
            }
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

    private String normalizeText(String text) {
        return text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
    }

    private String snippet(String text) {
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= SNIPPET_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, SNIPPET_LENGTH) + "...";
    }

    private record SearchBatchResult(String competitor, List<EvidenceSource> sources, List<String> failures) {
        private static final SearchBatchResult EMPTY = new SearchBatchResult("", Collections.emptyList(), Collections.emptyList());
    }
}
