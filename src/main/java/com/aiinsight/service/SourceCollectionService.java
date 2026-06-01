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

    private final WebPageFetchService webPageFetchService;
    private final SearchProvider searchProvider;
    private final SearchQueryPlanner searchQueryPlanner;
    private final SourceTypeClassifier sourceTypeClassifier;
    private final SourceCollectionProperties properties;

    @Autowired
    public SourceCollectionService(WebPageFetchService webPageFetchService,
                                   SearchProvider searchProvider,
                                   SearchQueryPlanner searchQueryPlanner,
                                   SourceCollectionProperties properties) {
        this(webPageFetchService, searchProvider, searchQueryPlanner, new SourceTypeClassifier(), properties);
    }

    SourceCollectionService(WebPageFetchService webPageFetchService,
                            SearchProvider searchProvider,
                            SearchQueryPlanner searchQueryPlanner,
                            SourceTypeClassifier sourceTypeClassifier,
                            SourceCollectionProperties properties) {
        this.webPageFetchService = webPageFetchService;
        this.searchProvider = searchProvider;
        this.searchQueryPlanner = searchQueryPlanner;
        this.sourceTypeClassifier = sourceTypeClassifier;
        this.properties = properties == null ? new SourceCollectionProperties() : properties;
    }

    public SourceCollectionService(WebPageFetchService webPageFetchService, SearchProvider searchProvider) {
        this(webPageFetchService, searchProvider, new SearchQueryPlanner(), new SourceCollectionProperties());
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
            if (userUrlNeedsAttention(source)) {
                run.getRecommendedActions().add(userUrlAction(url, source));
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
        // 按候选位轮询各竞品，避免第一个竞品独占全部证据名额，导致后续画像缺少基础来源。
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
            // LLM 规划可以更贴合任务，但不能漏掉竞品覆盖；规则规划只补齐缺失竞品。
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
        if (competitorCount > properties.largeBatchCompetitorThreshold()) {
            return properties.largeBatchSearchSourcesPerCompetitor();
        }
        return properties.smallBatchSearchSourcesPerCompetitor();
    }

    private int maxSearchSources(int competitorCount, int sourcesPerCompetitor) {
        return Math.min(
                properties.hardMaxSearchSources(),
                Math.max(properties.minSearchSources(), competitorCount * sourcesPerCompetitor)
        );
    }

    private List<SearchBatchResult> collectSearchBatches(AnalysisRun run,
                                                         List<SearchQueryPlanner.SearchQueryBatch> batches,
                                                         Set<String> seenUrls,
                                                         int sourcesPerCompetitor,
                                                         boolean recollecting) {
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(batches.size(), properties.maxParallelBatches()));
        try {
            // 并发抓取共享“已有来源”快照，批内先去重；最终提升证据时再用全局 seenUrls 做一次严格去重。
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
                results = searchProvider.search(query, properties.maxResultsPerQuery());
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
            return failedUserUrl(citationKey, url, "FETCH_FAILED", "FETCH_FAILED", "Page fetch failed: " + ex.getMessage());
        }
        if (!page.isUsable() || !StringUtils.hasText(page.getRawText())) {
            return failedUserUrl(citationKey, url, page.getStatus(), page.getFailureReason(), page.getComplianceNote());
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

    private EvidenceSource failedUserUrl(String citationKey,
                                         String url,
                                         String status,
                                         String failureReason,
                                         String complianceNote) {
        String normalizedStatus = StringUtils.hasText(status) ? status : "FETCH_FAILED";
        String normalizedReason = StringUtils.hasText(failureReason) ? failureReason : normalizedStatus;
        String message = userUrlFailureMessage(url, normalizedStatus, normalizedReason);
        return new EvidenceSource(
                citationKey,
                url,
                url,
                "user_source_url",
                normalizedStatus,
                "FETCH_FAILED",
                "UNUSABLE",
                normalizedReason,
                message,
                "",
                complianceNote
        );
    }

    private boolean userUrlNeedsAttention(EvidenceSource source) {
        String status = source.getCollectionStatus();
        return "FETCH_FAILED".equals(status)
                || "BLOCKED_BY_ROBOTS".equals(status)
                || "UNUSABLE_CONTENT".equals(status)
                || "METADATA_ONLY".equals(source.getFailureReason());
    }

    private String userUrlAction(String url, EvidenceSource source) {
        return "User-provided URL needs attention: " + url + " - " + explainFailure(source.getCollectionStatus(), source.getFailureReason());
    }

    private String userUrlFailureMessage(String url, String status, String failureReason) {
        return "User-provided URL issue: " + explainFailure(status, failureReason) + " URL: " + url;
    }

    private String explainFailure(String status, String failureReason) {
        String reason = failureReason == null ? "" : failureReason;
        if ("EMPTY_TEXT".equals(reason)) {
            return "page returned HTTP success but no extractable text was found; it may require JavaScript rendering.";
        }
        if ("THIN_TEXT".equals(reason)) {
            return "page returned HTTP success but only very thin text was extracted; it may be a shell or JavaScript-rendered page.";
        }
        if ("HTTP_4XX".equals(reason)) {
            return "page returned a 4xx response or anti-bot checkpoint.";
        }
        if ("HTTP_5XX".equals(reason)) {
            return "page returned a server error.";
        }
        if ("ANTI_BOT_PAGE".equals(reason)) {
            return "page appears to be an anti-bot challenge.";
        }
        if ("LOGIN_REQUIRED".equals(reason)) {
            return "page requires login or restricted access.";
        }
        if ("ROBOTS_BLOCKED".equals(reason) || "BLOCKED_BY_ROBOTS".equals(status)) {
            return "robots.txt disallows public fetching.";
        }
        if ("TIMEOUT".equals(reason)) {
            return "page fetch timed out.";
        }
        if ("TLS_FAILED".equals(reason)) {
            return "TLS certificate validation failed; on Windows the fetcher now tries the system root store, but the proxy or JDK may still need its certificate installed.";
        }
        if ("DNS_FAILED".equals(reason)) {
            return "domain name resolution failed.";
        }
        if ("CONNECT_FAILED".equals(reason)) {
            return "network connection to the page failed.";
        }
        if ("METADATA_ONLY".equals(reason)) {
            return "only page metadata was extracted, so the evidence is usable but weak; JavaScript rendering may not have produced full body text.";
        }
        if ("FETCH_FAILED".equals(reason) || "FETCH_FAILED".equals(status)) {
            return "network or TLS fetch failed.";
        }
        if ("UNUSABLE_CONTENT".equals(status)) {
            return "page was fetched but did not provide usable evidence text.";
        }
        return StringUtils.hasText(reason) ? reason : "unknown fetch issue.";
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
