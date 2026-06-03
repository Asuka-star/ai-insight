package com.aiinsight.service;

import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.model.run.EvidenceSource;
import com.aiinsight.model.run.UserProvidedEvidence;
import com.aiinsight.model.enums.ResearchSubtaskPriority;
import com.aiinsight.model.enums.ResearchSubtaskStatus;
import com.aiinsight.model.schema.CandidateUrl;
import com.aiinsight.model.schema.EvidenceBudget;
import com.aiinsight.model.schema.ResearchCollectionPlan;
import com.aiinsight.model.schema.ResearchSubtask;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    private static final int MAX_OFFICIAL_REFERENCE_SOURCES_PER_URL = 4;

    private final WebPageFetchService webPageFetchService;
    private final SearchProvider searchProvider;
    private final SearchQueryPlanner searchQueryPlanner;
    private final SourceTypeClassifier sourceTypeClassifier;
    private final SourceCollectionProperties properties;
    private final LeadResearchPlanner leadResearchPlanner;

    public SourceCollectionService(WebPageFetchService webPageFetchService,
                                   SearchProvider searchProvider,
                                   SearchQueryPlanner searchQueryPlanner,
                                   SourceCollectionProperties properties) {
        this(webPageFetchService, searchProvider, searchQueryPlanner, properties, new LeadResearchPlanner());
    }

    @Autowired
    public SourceCollectionService(WebPageFetchService webPageFetchService,
                                   SearchProvider searchProvider,
                                   SearchQueryPlanner searchQueryPlanner,
                                   SourceCollectionProperties properties,
                                   LeadResearchPlanner leadResearchPlanner) {
        this(webPageFetchService, searchProvider, searchQueryPlanner, new SourceTypeClassifier(), properties, leadResearchPlanner);
    }

    SourceCollectionService(WebPageFetchService webPageFetchService,
                            SearchProvider searchProvider,
                            SearchQueryPlanner searchQueryPlanner,
                            SourceTypeClassifier sourceTypeClassifier,
                            SourceCollectionProperties properties) {
        this(webPageFetchService, searchProvider, searchQueryPlanner, sourceTypeClassifier, properties, new LeadResearchPlanner());
    }

    SourceCollectionService(WebPageFetchService webPageFetchService,
                            SearchProvider searchProvider,
                            SearchQueryPlanner searchQueryPlanner,
                            SourceTypeClassifier sourceTypeClassifier,
                            SourceCollectionProperties properties,
                            LeadResearchPlanner leadResearchPlanner) {
        this.webPageFetchService = webPageFetchService;
        this.searchProvider = searchProvider;
        this.searchQueryPlanner = searchQueryPlanner;
        this.sourceTypeClassifier = sourceTypeClassifier;
        this.properties = properties == null ? new SourceCollectionProperties() : properties;
        this.leadResearchPlanner = leadResearchPlanner == null ? new LeadResearchPlanner() : leadResearchPlanner;
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
        return collect(run, recollecting, plannedSearchBatches, true);
    }

    public List<EvidenceSource> collectUserDirectedSources(AnalysisRun run) {
        return collect(run, false, List.of(), false);
    }

    public SearchCandidateCollection searchCandidates(AnalysisRun run,
                                                       boolean recollecting,
                                                       List<SearchQueryPlanner.SearchQueryBatch> plannedSearchBatches) {
        List<SearchQueryPlanner.SearchQueryBatch> batches = searchBatches(run, recollecting, plannedSearchBatches);
        if (batches.isEmpty()) {
            initializeResearchCollectionPlan(run, batches, recollecting);
            return SearchCandidateCollection.empty();
        }
        ResearchCollectionPlan collectionPlan = initializeResearchCollectionPlan(run, batches, recollecting);
        List<String> queries = batches.stream()
                .flatMap(batch -> batch.queries().stream())
                .toList();
        run.getResearchPackage().setActualSearchQueries(queries);
        int sourcesPerCompetitor = searchSourcesPerCompetitor(batches.size());
        int maxSearchSources = maxSearchSources(batches.size(), sourcesPerCompetitor);
        if (!searchProvider.isAvailable()) {
            failSearchSubtasks(collectionPlan, "search_provider_unavailable", 0);
            return new SearchCandidateCollection(batches, List.of(), List.of(), false, maxSearchSources);
        }

        Set<String> seenUrls = new LinkedHashSet<>();
        run.getEvidenceSources().stream()
                .map(EvidenceSource::getUrl)
                .filter(StringUtils::hasText)
                .map(this::normalizeUrl)
                .forEach(seenUrls::add);
        long started = System.nanoTime();
        List<SearchCandidateBatchResult> batchResults = collectSearchCandidateBatches(
                run,
                batches,
                seenUrls,
                sourcesPerCompetitor,
                recollecting
        );
        long searchLatencyMs = elapsedMillis(started);
        List<String> failures = batchResults.stream()
                .flatMap(result -> result.failures().stream())
                .toList();
        CandidateDeduplication deduplication = deduplicateCandidates(
                run,
                candidateRoundRobin(batchResults, maxSearchSources * 3)
        );
        List<SearchCandidate> candidates = deduplication.candidates();
        updateSubtasksAfterCandidateSearch(collectionPlan, candidates, batchResults, searchLatencyMs);
        return new SearchCandidateCollection(batches, candidates, failures, true, maxSearchSources);
    }

    public List<EvidenceSource> collectSelectedSearchCandidates(AnalysisRun run,
                                                                boolean recollecting,
                                                                SearchCandidateCollection candidateCollection,
                                                                List<String> selectedCandidateIds) {
        if (candidateCollection == null || !candidateCollection.searchAvailable()) {
            return collect(run, recollecting, candidateCollection == null ? List.of() : candidateCollection.batches());
        }
        List<EvidenceSource> sources = collect(run, recollecting, candidateCollection.batches(), false);
        run.getResearchPackage().setActualSearchQueries(candidateCollection.queries());
        candidateCollection.failures().forEach(run.getRecommendedActions()::add);
        if (run.getResearchPackage().getResearchCollectionPlan().getCandidateUrls().isEmpty()) {
            deduplicateCandidates(run, candidateCollection.candidates());
        }
        int sourcesBeforeSearchCandidates = sources.size();
        int nextIndex = maxCitationNumber(sources) + 1;
        ResearchCollectionPlan collectionPlan = ensureResearchCollectionPlan(run, candidateCollection.batches(), recollecting);
        markFetchingSubtasks(collectionPlan, selectedCandidateIds);
        long fetchStarted = System.nanoTime();
        CandidateFetchOutcome outcome = appendCandidateSearchEvidence(
                run,
                sources,
                nextIndex,
                candidateCollection,
                selectedCandidateIds,
                "agent-selected"
        );
        if (outcome.needsCandidatePoolFill(candidateCollection)) {
            List<String> backupCandidateIds = outcome.backupCandidateIds(candidateCollection);
            markFetchingSubtasks(collectionPlan, backupCandidateIds);
            CandidateFetchOutcome fillOutcome = appendCandidateSearchEvidence(
                    run,
                    sources,
                    maxCitationNumber(sources) + 1,
                    candidateCollection,
                    backupCandidateIds,
                    "rule-backfill"
            );
            outcome = outcome.merge(fillOutcome);
        }
        updateSubtasksAfterCandidateFetch(collectionPlan, outcome, elapsedMillis(fetchStarted));
        int promotedFromCandidatePool = sources.size() - sourcesBeforeSearchCandidates;
        if (promotedFromCandidatePool < candidateCollection.maxSelectable()) {
            log.info("Search candidate pool underfilled evidence target without rerunning search: runId={}, promoted={}, target={}, candidates={}",
                    run.getId(),
                    promotedFromCandidatePool,
                    candidateCollection.maxSelectable(),
                    candidateCollection.candidates().size());
        }
        return sources;
    }

    private List<EvidenceSource> collect(AnalysisRun run,
                                         boolean recollecting,
                                         List<SearchQueryPlanner.SearchQueryBatch> plannedSearchBatches,
                                         boolean includeSearchEvidence) {
        run.getResearchPackage().setActualSearchQueries(List.of());
        List<EvidenceSource> sources = new ArrayList<>(run.getEvidenceSources());
        Set<String> seenUrls = new LinkedHashSet<>();
        List<OfficialSeed> officialSeeds = new ArrayList<>();
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
            OfficialSeed seed = fromUserUrlSeed("S" + index, url);
            EvidenceSource source = seed.source();
            sources.add(source);
            officialSeeds.add(seed);
            index++;
            if (userUrlNeedsAttention(source)) {
                run.getRecommendedActions().add(userUrlAction(url, source));
            }
        }

        index = appendOfficialReferenceCandidates(run, sources, seenUrls, officialSeeds, index);
        if (includeSearchEvidence) {
            appendSearchEvidence(run, sources, index, recollecting, plannedSearchBatches);
        }
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
                ? "用户提供敏感资料（internal-only），仅作为内部证据使用，避免对外传播。"
                : "用户提供资料，仅用于当前分析会话。";
        if (StringUtils.hasText(researchNote)) {
            complianceNote = complianceNote + " " + researchNote;
        }
        EvidenceSource source = new EvidenceSource(
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
        applySourceMetadata(source);
        source.setSourceAuthority(evidence.isSensitive() ? "INTERNAL_ONLY" : "USER_PROVIDED");
        return source;
    }

    private String firstPartyResearchNote(String sourceType) {
        if (containsIgnoreCase(sourceType, "interview")) {
            return "一手访谈证据；生成报告前应抽取角色、痛点、原话和决策信号。";
        }
        if (containsIgnoreCase(sourceType, "survey")) {
            return "一手问卷证据；总结时应保留样本量、问题措辞和回答分布。";
        }
        return "";
    }

    private int appendOfficialReferenceCandidates(AnalysisRun run,
                                                  List<EvidenceSource> sources,
                                                  Set<String> seenUrls,
                                                  List<OfficialSeed> officialSeeds,
                                                  int index) {
        List<OfficialCandidateGroup> candidateGroups = officialReferenceCandidateGroups(run);
        if (candidateGroups.isEmpty()) {
            return index;
        }
        for (OfficialSeed officialSeed : officialSeeds) {
            if (!isStrongUserProvidedOfficialSource(officialSeed.source())) {
                continue;
            }
            int promotedForUrl = 0;
            for (OfficialCandidateGroup candidateGroup : candidateGroups) {
                for (String candidateUrl : officialCandidateUrls(
                        officialSeed.source().getUrl(),
                        officialSeed.internalLinks(),
                        candidateGroup.paths()
                )) {
                    if (!seenUrls.add(normalizeUrl(candidateUrl))) {
                        continue;
                    }
                    EvidenceSource source = fromUrl(
                            "S" + index,
                            candidateUrl,
                            "official_" + candidateGroup.name() + "_candidate",
                            "Derived from user-provided official URL. requestedOfficialSection=" + candidateGroup.name() + ". ",
                            true
                    );
                    if (source == null) {
                        continue;
                    }
                    sources.add(source);
                    log.info("Official reference candidate promoted to evidence: citationKey={}, url={}, section={}, sourceType={}, sourceQuality={}",
                            source.getCitationKey(),
                            source.getUrl(),
                            candidateGroup.name(),
                            source.getSourceType(),
                            source.getSourceQuality());
                    index++;
                    promotedForUrl++;
                    break;
                }
                if (promotedForUrl >= MAX_OFFICIAL_REFERENCE_SOURCES_PER_URL) {
                    break;
                }
            }
        }
        return index;
    }

    private boolean isStrongUserProvidedOfficialSource(EvidenceSource source) {
        return source != null
                && "FETCHED".equals(source.getCollectionStatus())
                && !"METADATA_ONLY".equals(source.getFailureReason())
                && !"LOW".equals(source.getSourceQuality())
                && !"UNUSABLE".equals(source.getSourceQuality());
    }

    private List<OfficialCandidateGroup> officialReferenceCandidateGroups(AnalysisRun run) {
        if (run == null || run.getRequirement() == null || run.getRequirement().getSourceUrls().isEmpty()) {
            return List.of();
        }
        LinkedHashSet<OfficialCandidateGroup> groups = new LinkedHashSet<>();
        List<String> dimensions = run.getRequirement().getDimensions();
        List<String> sourcePreferences = run.getRequirement().getSourcePreferences();
        List<String> missingEvidenceTypes = run.getResearchPackage().getMissingEvidenceTypes();

        addGroup(groups, "product", List.of("/product", "/features", "/platform"),
                mentionsAny(sourcePreferences, "official", "官网", "product", "产品")
                        || mentionsAny(dimensions, "功能", "能力", "workflow", "工作流", "agent", "协作", "生成", "理解"));
        addGroup(groups, "docs", List.of("/docs", "/documentation", "/help", "/guide", "/guides"),
                mentionsAny(sourcePreferences, "doc", "docs", "文档", "help")
                        || mentionsAny(dimensions, "context", "上下文", "代码理解", "代码生成", "ide", "终端", "terminal", "api", "integration", "集成"));
        addGroup(groups, "security", List.of("/security", "/enterprise", "/trust", "/privacy"),
                mentionsAny(sourcePreferences, "security", "安全", "权限", "合规", "enterprise", "企业")
                        || mentionsAny(dimensions, "security", "安全", "权限", "合规", "enterprise", "企业", "privacy", "隐私"));
        addGroup(groups, "pricing", List.of("/pricing", "/plans"),
                mentionsAny(dimensions, "pricing", "price", "价格", "定价", "商业模式")
                        || mentionsAny(sourcePreferences, "pricing", "price", "价格", "定价")
                        || mentionsAny(missingEvidenceTypes, "pricing", "pricing_page"));
        addGroup(groups, "customers", List.of("/customers", "/case-studies", "/solutions"),
                mentionsAny(sourcePreferences, "case", "customer", "客户", "案例")
                        || mentionsAny(dimensions, "target", "用户", "团队", "协作", "客户", "案例", "persona"));
        addGroup(groups, "release", List.of("/changelog", "/release-notes", "/releases", "/updates", "/blog"),
                mentionsAny(sourcePreferences, "release", "changelog", "更新", "blog", "博客")
                        || mentionsAny(dimensions, "roadmap", "版本", "更新", "发布", "release", "changelog"));

        if (groups.isEmpty() && mentionsAny(sourcePreferences, "official", "官网", "official_site")) {
            groups.add(new OfficialCandidateGroup("product", List.of("/product", "/features", "/platform")));
            groups.add(new OfficialCandidateGroup("docs", List.of("/docs", "/documentation", "/help")));
        }
        return new ArrayList<>(groups);
    }

    private boolean mentionsAny(List<String> values, String... patterns) {
        if (values == null) {
            return false;
        }
        return values.stream().anyMatch(value -> containsAny(normalizeText(value), patterns));
    }

    private void addGroup(LinkedHashSet<OfficialCandidateGroup> groups,
                          String name,
                          List<String> paths,
                          boolean enabled) {
        if (enabled) {
            groups.add(new OfficialCandidateGroup(name, paths));
        }
    }

    private List<String> officialCandidateUrls(String sourceUrl, List<String> internalLinks, List<String> paths) {
        UrlParts parts = parseUrl(sourceUrl);
        if (parts == null) {
            return List.of();
        }
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        for (String internalLink : internalLinks == null ? List.<String>of() : internalLinks) {
            if (matchesOfficialGroupPath(internalLink, paths)) {
                candidates.add(internalLink);
            }
        }
        if (!candidates.isEmpty()) {
            return new ArrayList<>(candidates);
        }
        String origin = parts.scheme() + "://" + parts.host();
        String locale = firstLocaleSegment(parts.path());
        for (String path : paths) {
            String normalizedPath = path.startsWith("/") ? path : "/" + path;
            candidates.add(origin + normalizedPath);
            if (StringUtils.hasText(locale)) {
                candidates.add(origin + "/" + locale + normalizedPath);
            }
            candidates.add(origin + "/cn" + normalizedPath);
        }
        return new ArrayList<>(candidates);
    }

    private boolean matchesOfficialGroupPath(String url, List<String> paths) {
        UrlParts parts = parseUrl(url);
        if (parts == null || !StringUtils.hasText(parts.path())) {
            return false;
        }
        String path = normalizeText(parts.path());
        for (String groupPath : paths) {
            String normalizedPath = groupPath.startsWith("/") ? groupPath : "/" + groupPath;
            if (path.equals(normalizedPath)
                    || path.startsWith(normalizedPath + "/")
                    || path.matches("/[a-z]{2}" + java.util.regex.Pattern.quote(normalizedPath) + "(/.*)?")) {
                return true;
            }
        }
        return false;
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
        ResearchCollectionPlan collectionPlan = ensureResearchCollectionPlan(run, batches, recollecting);
        List<String> queries = batches.stream()
                .flatMap(batch -> batch.queries().stream())
                .toList();
        run.getResearchPackage().setActualSearchQueries(queries);
        if (!searchProvider.isAvailable()) {
            log.warn("Source collection search fallback required: runId={}, reason=search_provider_unavailable, queries={}",
                    run.getId(),
                    queries);
            run.getRecommendedActions().add("搜索服务未配置：请设置 TAVILY_API_KEY，或在公开来源 URL 中手动补充可抓取页面。");
            failSearchSubtasks(collectionPlan, "search_provider_unavailable", 0);
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
        long started = System.nanoTime();
        List<SearchCandidateBatchResult> batchResults = collectSearchCandidateBatches(
                run,
                batches,
                seenUrls,
                sourcesPerCompetitor,
                recollecting
        );
        long searchLatencyMs = elapsedMillis(started);
        batchResults.stream()
                .flatMap(result -> result.failures().stream())
                .forEach(run.getRecommendedActions()::add);
        CandidateDeduplication deduplication = deduplicateCandidates(
                run,
                candidateRoundRobin(batchResults, maxSearchSources * 3)
        );
        List<SearchCandidate> candidates = deduplication.candidates();
        updateSubtasksAfterCandidateSearch(collectionPlan, candidates, batchResults, searchLatencyMs);
        SearchCandidateCollection candidateCollection = new SearchCandidateCollection(
                batches,
                candidates,
                batchResults.stream().flatMap(result -> result.failures().stream()).toList(),
                true,
                maxSearchSources
        );
        long fetchStarted = System.nanoTime();
        CandidateFetchOutcome outcome = appendCandidateSearchEvidence(
                run,
                sources,
                index,
                candidateCollection,
                candidates.stream().map(SearchCandidate::id).toList(),
                "rule-direct"
        );
        updateSubtasksAfterCandidateFetch(collectionPlan, outcome, elapsedMillis(fetchStarted));

        if (outcome.added() == 0) {
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

    private ResearchCollectionPlan initializeResearchCollectionPlan(AnalysisRun run,
                                                                    List<SearchQueryPlanner.SearchQueryBatch> batches,
                                                                    boolean recollecting) {
        ResearchCollectionPlan plan = new ResearchCollectionPlan();
        plan.setRunId(run.getId());
        plan.setGoal(collectionGoal(run, recollecting));
        plan.setPlanSource(recollecting ? "REVIEW_REPAIR" : "RULE_BASED");
        plan.setLeadResearchPlan(leadResearchPlanner.plan(run, batches, recollecting));
        if (run.getRequirement() != null) {
            plan.setCompetitors(nullToEmpty(run.getRequirement().getCompetitors()).stream()
                    .filter(StringUtils::hasText)
                    .map(String::trim)
                    .toList());
            plan.setDimensions(nullToEmpty(run.getRequirement().getDimensions()).stream()
                    .filter(StringUtils::hasText)
                    .map(String::trim)
                    .toList());
        }
        int sourcesPerCompetitor = searchSourcesPerCompetitor(Math.max(1, batches == null ? 0 : batches.size()));
        for (SearchQueryPlanner.SearchQueryBatch batch : batches == null ? List.<SearchQueryPlanner.SearchQueryBatch>of() : batches) {
            ResearchSubtask subtask = new ResearchSubtask();
            subtask.setRunId(run.getId());
            subtask.setCompetitorName(batch.competitor());
            subtask.setDimension(inferSubtaskDimension(batch.queries()));
            subtask.setQueries(new ArrayList<>(batch.queries()));
            subtask.setSourcePreferences(run.getRequirement() == null
                    ? List.of()
                    : new ArrayList<>(nullToEmpty(run.getRequirement().getSourcePreferences())));
            subtask.setPriority(recollecting ? ResearchSubtaskPriority.REVIEW_REPAIR : ResearchSubtaskPriority.NORMAL_SEARCH);
            subtask.setStatus(ResearchSubtaskStatus.SEARCHING);
            subtask.setAttempt(1);
            subtask.setStartedAt(Instant.now());
            plan.getSubtasks().add(subtask);
            plan.getEvidenceBudgets().add(evidenceBudget(run, batch, subtask.getDimension(), sourcesPerCompetitor, recollecting));
        }
        run.getResearchPackage().setResearchCollectionPlan(plan);
        return plan;
    }

    private EvidenceBudget evidenceBudget(AnalysisRun run,
                                          SearchQueryPlanner.SearchQueryBatch batch,
                                          String dimension,
                                          int sourcesPerCompetitor,
                                          boolean recollecting) {
        EvidenceBudget budget = new EvidenceBudget();
        budget.setCompetitorName(batch.competitor());
        budget.setDimension(dimension);
        budget.setMinOfficialSources(mentionsAny(List.of(dimension), "pricing", "docs", "security", "release") ? 1 : 0);
        budget.setMinThirdPartySources(mentionsAny(List.of(dimension), "reviews", "customers", "public_search") ? 1 : 0);
        budget.setMinRagChunks(0);
        budget.setMaxAcceptedSources(searchSourcesForBatch(run, batch, sourcesPerCompetitor, recollecting));
        return budget;
    }

    private ResearchCollectionPlan ensureResearchCollectionPlan(AnalysisRun run,
                                                                List<SearchQueryPlanner.SearchQueryBatch> batches,
                                                                boolean recollecting) {
        ResearchCollectionPlan plan = run.getResearchPackage().getResearchCollectionPlan();
        if (plan == null || plan.getSubtasks() == null || plan.getSubtasks().isEmpty()) {
            return initializeResearchCollectionPlan(run, batches, recollecting);
        }
        return plan;
    }

    private void failSearchSubtasks(ResearchCollectionPlan plan, String failureReason, long searchLatencyMs) {
        if (plan == null || plan.getSubtasks() == null) {
            return;
        }
        for (ResearchSubtask subtask : plan.getSubtasks()) {
            subtask.setStatus(ResearchSubtaskStatus.FAILED);
            subtask.setFailureReason(failureReason);
            subtask.setSearchLatencyMs(searchLatencyMs);
            subtask.setFinishedAt(Instant.now());
        }
    }

    private CandidateDeduplication deduplicateCandidates(AnalysisRun run, List<SearchCandidate> candidates) {
        ResearchCollectionPlan plan = run.getResearchPackage().getResearchCollectionPlan();
        if (plan == null) {
            return new CandidateDeduplication(candidates == null ? List.of() : candidates);
        }
        Map<String, ResearchSubtask> subtaskByCompetitor = subtasksByCompetitor(plan);
        List<CandidateUrl> candidateUrls = new ArrayList<>();
        List<SearchCandidate> uniqueCandidates = new ArrayList<>();
        Map<String, CandidateUrl> firstByNormalizedUrl = new LinkedHashMap<>();
        for (SearchCandidate candidate : candidates == null ? List.<SearchCandidate>of() : candidates) {
            CandidateUrl candidateUrl = new CandidateUrl();
            candidateUrl.setRunId(run.getId());
            ResearchSubtask subtask = subtaskByCompetitor.get(normalizeText(candidate.competitor()));
            if (subtask != null) {
                candidateUrl.setSubtaskId(subtask.getId());
            }
            candidateUrl.setCandidateId(candidate.id());
            candidateUrl.setUrl(candidate.url());
            candidateUrl.setNormalizedUrl(normalizeUrl(candidate.url()));
            candidateUrl.setTitle(candidate.title());
            candidateUrl.setSnippet(candidate.snippet());
            candidateUrl.setSourceProvider("TAVILY");
            candidateUrl.setSourceTypeHint(candidate.sourceType());
            candidateUrl.setSearchScore(Math.max(0, 100 - candidate.rulePriority()));
            CandidateUrl first = firstByNormalizedUrl.get(candidateUrl.getNormalizedUrl());
            if (first == null) {
                firstByNormalizedUrl.put(candidateUrl.getNormalizedUrl(), candidateUrl);
                uniqueCandidates.add(candidate);
            } else {
                candidateUrl.setDuplicate(true);
                candidateUrl.setDuplicateOf(first.getId());
                candidateUrl.setRejectionReason("duplicate_url");
            }
            candidateUrls.add(candidateUrl);
        }
        plan.setCandidateUrls(candidateUrls);
        return new CandidateDeduplication(uniqueCandidates);
    }

    private void updateSubtasksAfterCandidateSearch(ResearchCollectionPlan plan,
                                                    List<SearchCandidate> candidates,
                                                    List<SearchCandidateBatchResult> batchResults,
                                                    long searchLatencyMs) {
        if (plan == null) {
            return;
        }
        Map<String, Long> candidateCountByCompetitor = (candidates == null ? List.<SearchCandidate>of() : candidates).stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        candidate -> normalizeText(candidate.competitor()),
                        LinkedHashMap::new,
                        java.util.stream.Collectors.counting()
                ));
        Map<String, String> failuresByCompetitor = new LinkedHashMap<>();
        for (SearchCandidateBatchResult result : batchResults == null ? List.<SearchCandidateBatchResult>of() : batchResults) {
            if (!result.failures().isEmpty()) {
                failuresByCompetitor.put(normalizeText(result.competitor()), String.join("; ", result.failures()));
            }
        }
        for (ResearchSubtask subtask : nullToEmptySubtasks(plan)) {
            String key = normalizeText(subtask.getCompetitorName());
            subtask.setCandidateUrlCount(candidateCountByCompetitor.getOrDefault(key, 0L).intValue());
            subtask.setSearchLatencyMs(searchLatencyMs);
            if (subtask.getCandidateUrlCount() > 0) {
                subtask.setStatus(ResearchSubtaskStatus.SEARCHED);
            } else {
                subtask.setStatus(ResearchSubtaskStatus.FAILED);
                subtask.setFailureReason(failuresByCompetitor.getOrDefault(key, "no_search_candidates"));
                subtask.setFinishedAt(Instant.now());
            }
        }
    }

    private void updateSubtasksAfterDirectSearch(ResearchCollectionPlan plan,
                                                 List<SearchBatchResult> batchResults,
                                                 Map<String, Integer> acceptedByCompetitor,
                                                 long searchLatencyMs) {
        Map<String, SearchBatchResult> resultByCompetitor = new LinkedHashMap<>();
        for (SearchBatchResult result : batchResults == null ? List.<SearchBatchResult>of() : batchResults) {
            resultByCompetitor.put(normalizeText(result.competitor()), result);
        }
        for (ResearchSubtask subtask : nullToEmptySubtasks(plan)) {
            String key = normalizeText(subtask.getCompetitorName());
            SearchBatchResult result = resultByCompetitor.get(key);
            int fetched = result == null ? 0 : result.sources().size();
            int accepted = acceptedByCompetitor == null ? 0 : acceptedByCompetitor.getOrDefault(key, 0);
            subtask.setSearchLatencyMs(searchLatencyMs);
            subtask.setFetchLatencyMs(searchLatencyMs);
            subtask.setCandidateUrlCount(fetched);
            subtask.setFetchedPageCount(fetched);
            subtask.setAcceptedEvidenceCount(accepted);
            subtask.setFinishedAt(Instant.now());
            if (accepted > 0) {
                subtask.setStatus(ResearchSubtaskStatus.SUCCEEDED);
            } else {
                subtask.setStatus(ResearchSubtaskStatus.FAILED);
                String failure = result == null || result.failures().isEmpty()
                        ? "no_accepted_search_evidence"
                        : String.join("; ", result.failures());
                subtask.setFailureReason(failure);
            }
        }
    }

    private void markFetchingSubtasks(ResearchCollectionPlan plan, List<String> candidateIds) {
        if (plan == null) {
            return;
        }
        Set<String> selected = candidateIds == null ? Set.of() : candidateIds.stream()
                .filter(StringUtils::hasText)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<java.util.UUID> subtaskIds = plan.getCandidateUrls().stream()
                .filter(candidate -> selected.isEmpty() || selected.contains(candidate.getCandidateId()))
                .map(CandidateUrl::getSubtaskId)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        for (ResearchSubtask subtask : nullToEmptySubtasks(plan)) {
            if (subtaskIds.isEmpty() || subtaskIds.contains(subtask.getId())) {
                subtask.setStatus(ResearchSubtaskStatus.FETCHING);
            }
        }
    }

    private void updateSubtasksAfterCandidateFetch(ResearchCollectionPlan plan,
                                                   CandidateFetchOutcome outcome,
                                                   long fetchLatencyMs) {
        if (plan == null || outcome == null) {
            return;
        }
        for (ResearchSubtask subtask : nullToEmptySubtasks(plan)) {
            String key = normalizeText(subtask.getCompetitorName());
            int fetched = outcome.fetchedByCompetitor().getOrDefault(key, 0);
            int accepted = outcome.acceptedByCompetitor().getOrDefault(key, 0);
            subtask.setFetchedPageCount(subtask.getFetchedPageCount() + fetched);
            subtask.setAcceptedEvidenceCount(subtask.getAcceptedEvidenceCount() + accepted);
            subtask.setFetchLatencyMs(fetchLatencyMs);
            if (accepted > 0) {
                subtask.setStatus(ResearchSubtaskStatus.SUCCEEDED);
                subtask.setFinishedAt(Instant.now());
                subtask.setFailureReason(null);
            } else if (subtask.getStatus() == ResearchSubtaskStatus.FETCHING) {
                subtask.setStatus(ResearchSubtaskStatus.FAILED);
                subtask.setFailureReason(fetched > 0 ? "no_usable_fetched_evidence" : "no_candidate_fetched");
                subtask.setFinishedAt(Instant.now());
            } else if (subtask.getStatus() == ResearchSubtaskStatus.SEARCHED) {
                subtask.setStatus(ResearchSubtaskStatus.SKIPPED);
                subtask.setFailureReason("not_selected_or_budget_exhausted");
                subtask.setFinishedAt(Instant.now());
            }
        }
    }

    private Map<String, ResearchSubtask> subtasksByCompetitor(ResearchCollectionPlan plan) {
        Map<String, ResearchSubtask> subtasks = new LinkedHashMap<>();
        for (ResearchSubtask subtask : nullToEmptySubtasks(plan)) {
            subtasks.putIfAbsent(normalizeText(subtask.getCompetitorName()), subtask);
        }
        return subtasks;
    }

    private List<ResearchSubtask> nullToEmptySubtasks(ResearchCollectionPlan plan) {
        if (plan == null || plan.getSubtasks() == null) {
            return List.of();
        }
        return plan.getSubtasks();
    }

    private String collectionGoal(AnalysisRun run, boolean recollecting) {
        if (run.getRequirement() == null) {
            return recollecting ? "Reviewer evidence recollection" : "Public evidence collection";
        }
        String competitors = String.join("、", nullToEmpty(run.getRequirement().getCompetitors()));
        String dimensions = String.join("、", nullToEmpty(run.getRequirement().getDimensions()));
        return "%s%s%s".formatted(
                recollecting ? "补采 " : "采集 ",
                StringUtils.hasText(competitors) ? competitors : "目标竞品",
                StringUtils.hasText(dimensions) ? " 的 " + dimensions + " 证据" : " 的公开证据"
        );
    }

    private String inferSubtaskDimension(List<String> queries) {
        String text = normalizeText(String.join(" ", queries == null ? List.of() : queries));
        if (containsAny(text, "pricing", "price", "plan", "定价", "价格")) {
            return "pricing";
        }
        if (containsAny(text, "review", "feedback", "评价", "评论", "反馈")) {
            return "reviews";
        }
        if (containsAny(text, "security", "compliance", "permission", "安全", "合规", "权限")) {
            return "security";
        }
        if (containsAny(text, "customer", "case", "客户", "案例")) {
            return "customers";
        }
        if (containsAny(text, "docs", "documentation", "文档")) {
            return "docs";
        }
        if (containsAny(text, "release", "changelog", "更新", "发布")) {
            return "release";
        }
        return "public_search";
    }

    private long elapsedMillis(long startedNanos) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    private List<SearchBatchResult> collectSearchBatches(AnalysisRun run,
                                                         List<SearchQueryPlanner.SearchQueryBatch> batches,
                                                         Set<String> seenUrls,
                                                         int sourcesPerCompetitor,
                                                         boolean recollecting) {
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(batches.size(), properties.maxParallelSearches()));
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

    private List<SearchCandidateBatchResult> collectSearchCandidateBatches(AnalysisRun run,
                                                                           List<SearchQueryPlanner.SearchQueryBatch> batches,
                                                                           Set<String> seenUrls,
                                                                           int sourcesPerCompetitor,
                                                                           boolean recollecting) {
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(batches.size(), properties.maxParallelSearches()));
        try {
            Set<String> existingSeenUrls = new LinkedHashSet<>(seenUrls);
            List<CompletableFuture<SearchCandidateBatchResult>> futures = batches.stream()
                    .map(batch -> CompletableFuture.supplyAsync(
                            () -> collectSearchCandidateBatch(
                                    run,
                                    batch,
                                    existingSeenUrls,
                                    searchSourcesForBatch(run, batch, sourcesPerCompetitor, recollecting)
                            ),
                            executor
                    ))
                    .toList();
            return futures.stream()
                    .map(this::joinSearchCandidateBatch)
                    .filter(result -> result != SearchCandidateBatchResult.EMPTY)
                    .toList();
        } finally {
            executor.shutdown();
        }
    }

    private SearchCandidateBatchResult joinSearchCandidateBatch(CompletableFuture<SearchCandidateBatchResult> future) {
        try {
            return future.join();
        } catch (CompletionException ex) {
            log.warn("Source collection search candidate batch failed unexpectedly: exceptionType={}, message={}",
                    ex.getClass().getName(),
                    ex.getMessage());
            return SearchCandidateBatchResult.EMPTY;
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
            boolean structuredTarget = run.getReviewDecision().getRepairTasks().stream()
                    .map(task -> task.getCompetitorName())
                    .filter(StringUtils::hasText)
                    .anyMatch(target -> normalizeText(target).equals(normalizeText(competitor)));
            if (structuredTarget || containsIgnoreCase(repairText, competitor)) {
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
            for (SearchResult result : prioritizedSearchResults(batch, results)) {
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

    private SearchCandidateBatchResult collectSearchCandidateBatch(AnalysisRun run,
                                                                   SearchQueryPlanner.SearchQueryBatch batch,
                                                                   Set<String> existingSeenUrls,
                                                                   int sourcesPerCompetitor) {
        List<SearchCandidateDraft> candidates = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        Set<String> localSeenUrls = new LinkedHashSet<>(existingSeenUrls);
        int maxCandidates = Math.max(sourcesPerCompetitor * 3, sourcesPerCompetitor);
        for (String query : batch.queries()) {
            if (candidates.size() >= maxCandidates) {
                break;
            }
            List<SearchResult> results;
            try {
                results = searchProvider.search(query, properties.maxResultsPerQuery());
            } catch (RuntimeException ex) {
                log.warn("Source collection search query failed before candidate selection: runId={}, competitor={}, query={}, exceptionType={}, message={}",
                        run.getId(),
                        batch.competitor(),
                        query,
                        ex.getClass().getName(),
                        ex.getMessage());
                failures.add("Search query failed: " + query + ": " + ex.getMessage());
                continue;
            }
            for (SearchResult result : prioritizedSearchResults(batch, results)) {
                if (!StringUtils.hasText(result.getUrl())) {
                    continue;
                }
                if (!localSeenUrls.add(normalizeUrl(result.getUrl()))) {
                    continue;
                }
                candidates.add(new SearchCandidateDraft(
                        batch.competitor(),
                        query,
                        result.getRank(),
                        result.getTitle(),
                        result.getUrl(),
                        result.getSnippet(),
                        sourceTypeClassifier.classify(result.getUrl(), result.getTitle()),
                        searchResultPriority(batch, result),
                        sourcesPerCompetitor
                ));
                if (candidates.size() >= maxCandidates) {
                    break;
                }
            }
        }
        return new SearchCandidateBatchResult(batch.competitor(), candidates, failures);
    }

    private List<SearchCandidate> candidateRoundRobin(List<SearchCandidateBatchResult> batchResults, int maxCandidates) {
        List<SearchCandidate> candidates = new ArrayList<>();
        int maxBatchCandidates = batchResults.stream()
                .mapToInt(result -> result.candidates().size())
                .max()
                .orElse(0);
        int nextId = 1;
        for (int candidateIndex = 0; candidateIndex < maxBatchCandidates && candidates.size() < maxCandidates; candidateIndex++) {
            for (SearchCandidateBatchResult batchResult : batchResults) {
                if (candidateIndex >= batchResult.candidates().size()) {
                    continue;
                }
                SearchCandidateDraft draft = batchResult.candidates().get(candidateIndex);
                candidates.add(new SearchCandidate(
                        "C" + nextId,
                        draft.competitor(),
                        draft.query(),
                        draft.rank(),
                        draft.title(),
                        draft.url(),
                        draft.snippet(),
                        draft.sourceType(),
                        draft.rulePriority(),
                        draft.sourceBudget()
                ));
                nextId++;
                if (candidates.size() >= maxCandidates) {
                    break;
                }
            }
        }
        return candidates;
    }

    private CandidateFetchOutcome appendCandidateSearchEvidence(AnalysisRun run,
                                                                List<EvidenceSource> sources,
                                                                int index,
                                                                SearchCandidateCollection candidateCollection,
                                                                List<String> selectedCandidateIds,
                                                                String selectionMode) {
        Set<String> seenUrls = new LinkedHashSet<>();
        sources.stream()
                .map(EvidenceSource::getUrl)
                .filter(StringUtils::hasText)
                .map(this::normalizeUrl)
                .forEach(seenUrls::add);
        Set<String> selectedIds = selectedCandidateIds == null
                ? Set.of()
                : selectedCandidateIds.stream()
                .filter(StringUtils::hasText)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<SearchCandidate> orderedCandidates = candidatesMatchingIds(candidateCollection.candidates(), selectedIds);
        int added = 0;
        int nextIndex = index;
        int maxSearchSources = Math.max(0, candidateCollection.maxSelectable());
        Map<String, Integer> addedByCompetitor = new LinkedHashMap<>();
        Map<String, Integer> fetchedByCompetitor = new LinkedHashMap<>();
        Map<String, Integer> acceptedByCompetitor = new LinkedHashMap<>();
        Set<String> attemptedCandidateIds = new LinkedHashSet<>();
        int cursor = 0;
        while (added < maxSearchSources && cursor < orderedCandidates.size()) {
            List<SearchCandidate> fetchBatch = new ArrayList<>();
            int remainingSlots = maxSearchSources - added;
            while (cursor < orderedCandidates.size()
                    && fetchBatch.size() < Math.min(properties.maxParallelFetches(), remainingSlots)) {
                SearchCandidate candidate = orderedCandidates.get(cursor);
                cursor++;
                String competitorKey = normalizeText(candidate.competitor());
                int competitorAdded = addedByCompetitor.getOrDefault(competitorKey, 0);
                if (competitorAdded >= Math.max(1, acceptedSourceBudget(run, candidate))) {
                    continue;
                }
                if (!seenUrls.add(normalizeUrl(candidate.url()))) {
                    continue;
                }
                fetchBatch.add(candidate);
                attemptedCandidateIds.add(candidate.id());
                fetchedByCompetitor.merge(competitorKey, 1, Integer::sum);
            }
            if (fetchBatch.isEmpty()) {
                break;
            }
            for (CandidateFetchResult result : fetchCandidateEvidence(fetchBatch)) {
                SearchCandidate candidate = result.candidate();
                EvidenceSource source = result.source();
                if (source == null || added >= maxSearchSources) {
                    continue;
                }
                String competitorKey = normalizeText(candidate.competitor());
                int competitorAdded = addedByCompetitor.getOrDefault(competitorKey, 0);
                if (competitorAdded >= Math.max(1, acceptedSourceBudget(run, candidate))) {
                    continue;
                }
                String citationKey = "S" + nextIndex;
                source.setCitationKey(citationKey);
                sources.add(source);
                nextIndex++;
                added++;
                addedByCompetitor.put(competitorKey, competitorAdded + 1);
                acceptedByCompetitor.merge(competitorKey, 1, Integer::sum);
                log.info("Search candidate promoted to fetched evidence: citationKey={}, candidateId={}, url={}, competitor={}, selectionMode={}",
                        citationKey,
                        candidate.id(),
                        source.getUrl(),
                        candidate.competitor(),
                        selectionMode);
            }
        }
        if (added == 0 && !orderedCandidates.isEmpty()) {
            log.warn("Search candidate collection produced no fetched evidence: runId={}, candidates={}, selectedIds={}, selectionMode={}",
                    run.getId(),
                    orderedCandidates.size(),
                    selectedCandidateIds,
                    selectionMode);
        }
        return new CandidateFetchOutcome(added, maxSearchSources, attemptedCandidateIds, fetchedByCompetitor, acceptedByCompetitor);
    }

    private List<SearchCandidate> candidatesMatchingIds(List<SearchCandidate> candidates, Set<String> selectedIds) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        if (selectedIds == null || selectedIds.isEmpty()) {
            return candidates;
        }
        return candidates.stream()
                .filter(candidate -> selectedIds.contains(candidate.id()))
                .toList();
    }

    private int acceptedSourceBudget(AnalysisRun run, SearchCandidate candidate) {
        ResearchCollectionPlan plan = run.getResearchPackage().getResearchCollectionPlan();
        if (plan == null || plan.getEvidenceBudgets() == null || candidate == null) {
            return candidate == null ? 1 : candidate.sourceBudget();
        }
        String competitor = normalizeText(candidate.competitor());
        String dimension = inferSubtaskDimension(List.of(candidate.query(), candidate.sourceType(), candidate.title(), candidate.url()));
        for (EvidenceBudget budget : plan.getEvidenceBudgets()) {
            if (normalizeText(budget.getCompetitorName()).equals(competitor)
                    && normalizeText(budget.getDimension()).equals(normalizeText(dimension))) {
                return budget.getMaxAcceptedSources();
            }
        }
        for (EvidenceBudget budget : plan.getEvidenceBudgets()) {
            if (normalizeText(budget.getCompetitorName()).equals(competitor)) {
                return budget.getMaxAcceptedSources();
            }
        }
        return candidate.sourceBudget();
    }

    private List<CandidateFetchResult> fetchCandidateEvidence(List<SearchCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(candidates.size(), properties.maxParallelFetches()));
        try {
            List<CompletableFuture<CandidateFetchResult>> futures = candidates.stream()
                    .map(candidate -> CompletableFuture.supplyAsync(() -> {
                        EvidenceSource source = fromSearchResult("", candidate.toSearchResult());
                        return new CandidateFetchResult(candidate, source);
                    }, executor))
                    .toList();
            return futures.stream()
                    .map(this::joinCandidateFetch)
                    .filter(java.util.Objects::nonNull)
                    .toList();
        } finally {
            executor.shutdown();
        }
    }

    private CandidateFetchResult joinCandidateFetch(CompletableFuture<CandidateFetchResult> future) {
        try {
            return future.join();
        } catch (CompletionException ex) {
            log.warn("Source collection candidate fetch failed unexpectedly: exceptionType={}, message={}",
                    ex.getClass().getName(),
                    ex.getMessage());
            return null;
        }
    }

    private List<SearchResult> prioritizedSearchResults(SearchQueryPlanner.SearchQueryBatch batch, List<SearchResult> results) {
        return results.stream()
                .sorted(Comparator
                        .comparingInt((SearchResult result) -> searchResultPriority(batch, result))
                        .thenComparingInt(SearchResult::getRank))
                .toList();
    }

    private int searchResultPriority(SearchQueryPlanner.SearchQueryBatch batch, SearchResult result) {
        String searchable = normalizeText(result.getTitle() + " " + result.getUrl() + " " + result.getSnippet());
        if (isUnavailableRegionText(searchable)) {
            return 1000;
        }
        String sourceType = sourceTypeClassifier.classify(result.getUrl(), result.getTitle());
        int score = switch (sourceType) {
            case "pricing_page" -> 0;
            case "docs", "product_docs" -> 10;
            case "release_notes" -> 20;
            case "official_site" -> 30;
            case "third_party_pricing_reference", "pricing_reference" -> 65;
            case "public_review", "public_reviews", "forum", "video" -> 80;
            default -> 70;
        };
        if (containsCompetitorToken(searchable, batch.competitor())) {
            score -= 5;
        }
        return score;
    }

    private boolean containsCompetitorToken(String searchable, String competitor) {
        if (!StringUtils.hasText(searchable) || !StringUtils.hasText(competitor)) {
            return false;
        }
        for (String token : normalizeText(competitor).split("[^a-z0-9\\u4e00-\\u9fa5]+")) {
            if (token.length() >= 3 && searchable.contains(token)) {
                return true;
            }
        }
        return false;
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
        return fromUserUrlSeed(citationKey, url).source();
    }

    private OfficialSeed fromUserUrlSeed(String citationKey, String url) {
        WebPageFetchService.FetchedPage page;
        try {
            page = webPageFetchService.fetch(url);
        } catch (RuntimeException ex) {
            return new OfficialSeed(failedUserUrl(citationKey, url, "FETCH_FAILED", "FETCH_FAILED", "Page fetch failed: " + ex.getMessage()), List.of());
        }
        if (!page.isUsable() || !StringUtils.hasText(page.getRawText())) {
            return new OfficialSeed(failedUserUrl(citationKey, url, page.getStatus(), page.getFailureReason(), page.getComplianceNote()), List.of());
        }
        String blockingIssue = blockingFetchedContentIssue(page);
        if (blockingIssue != null) {
            return new OfficialSeed(failedUserUrl(citationKey, url, "UNUSABLE_CONTENT", blockingIssue, page.getComplianceNote()), List.of());
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
        applySourceMetadata(source);
        return new OfficialSeed(source, page.getInternalLinks());
    }

    private EvidenceSource failedUserUrl(String citationKey,
                                         String url,
                                         String status,
                                         String failureReason,
                                         String complianceNote) {
        String normalizedStatus = StringUtils.hasText(status) ? status : "FETCH_FAILED";
        String normalizedReason = StringUtils.hasText(failureReason) ? failureReason : normalizedStatus;
        String message = userUrlFailureMessage(url, normalizedStatus, normalizedReason);
        EvidenceSource source = new EvidenceSource(
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
        applySourceMetadata(source);
        return source;
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
        if ("region_unavailable_page".equals(reason)) {
            return "page only reports regional unavailability and cannot support factual claims.";
        }
        if ("anti_bot_or_redirect_page".equals(reason)) {
            return "page appears to be an anti-bot, redirect, or placeholder page.";
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
        applySourceMetadata(source);
        return source;
    }

    private void applySourceMetadata(EvidenceSource source) {
        if (source == null) {
            return;
        }
        String sourceType = StringUtils.hasText(source.getSourceType()) ? source.getSourceType() : "unknown";
        source.setSourceAuthority(sourceTypeClassifier.authorityFor(source.getUrl(), sourceType));
        source.setCanonicalHost(sourceTypeClassifier.canonicalHost(source.getUrl()));
        source.setPublisherName(sourceTypeClassifier.publisherName(source.getUrl()));
        source.setContentLanguage(inferContentLanguage(source.getTitle() + " " + source.getSnippet() + " " + source.getRawText()));
    }

    private String inferContentLanguage(String text) {
        if (!StringUtils.hasText(text)) {
            return "unknown";
        }
        String normalized = text.trim();
        int hanCount = normalized.replaceAll("[^\\p{IsHan}]", "").length();
        int asciiLetterCount = normalized.replaceAll("[^A-Za-z]", "").length();
        if (hanCount >= 8 && hanCount >= asciiLetterCount / 3) {
            return "zh";
        }
        if (asciiLetterCount >= 20) {
            return "en";
        }
        return "unknown";
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
        if (isUnavailableRegionText(searchable)) {
            return "region_unavailable_page";
        }
        if (text.length() < MIN_SEARCH_FETCH_TEXT_LENGTH) {
            return "thin_page_text";
        }
        return null;
    }

    private String blockingFetchedContentIssue(WebPageFetchService.FetchedPage page) {
        String issue = searchFetchedContentIssue(page);
        if ("anti_bot_or_redirect_page".equals(issue) || "region_unavailable_page".equals(issue)) {
            return issue;
        }
        return null;
    }

    private boolean isUnavailableRegionText(String searchable) {
        return containsAny(searchable,
                "app unavailable in region",
                "unavailable in your region",
                "not available in your region",
                "not currently available in your region",
                "service is not available in your region");
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

    private List<String> nullToEmpty(List<String> values) {
        return values == null ? List.of() : values;
    }

    private UrlParts parseUrl(String url) {
        if (!StringUtils.hasText(url)) {
            return null;
        }
        try {
            java.net.URI uri = java.net.URI.create(url);
            String scheme = StringUtils.hasText(uri.getScheme()) ? uri.getScheme().toLowerCase(Locale.ROOT) : "https";
            String host = uri.getHost();
            if (!StringUtils.hasText(host)) {
                return null;
            }
            return new UrlParts(scheme, host.toLowerCase(Locale.ROOT), uri.getPath() == null ? "" : uri.getPath());
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private String firstLocaleSegment(String path) {
        if (!StringUtils.hasText(path) || !path.startsWith("/")) {
            return "";
        }
        String firstSegment = path.substring(1).split("/")[0].toLowerCase(Locale.ROOT);
        return firstSegment.matches("[a-z]{2}") ? firstSegment : "";
    }

    private String snippet(String text) {
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= SNIPPET_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, SNIPPET_LENGTH) + "...";
    }

    public record SearchCandidateCollection(List<SearchQueryPlanner.SearchQueryBatch> batches,
                                            List<SearchCandidate> candidates,
                                            List<String> failures,
                                            boolean searchAvailable,
                                            int maxSelectable) {

        public SearchCandidateCollection {
            batches = batches == null ? List.of() : List.copyOf(batches);
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
            failures = failures == null ? List.of() : List.copyOf(failures);
        }

        private static SearchCandidateCollection empty() {
            return new SearchCandidateCollection(List.of(), List.of(), List.of(), true, 0);
        }

        public List<String> queries() {
            return batches.stream()
                    .flatMap(batch -> batch.queries().stream())
                    .toList();
        }
    }

    public record SearchCandidate(String id,
                                  String competitor,
                                  String query,
                                  int rank,
                                  String title,
                                  String url,
                                  String snippet,
                                  String sourceType,
                                  int rulePriority,
                                  int sourceBudget) {

        private SearchResult toSearchResult() {
            return new SearchResult(title, url, snippet, query, rank);
        }
    }

    private record SearchCandidateDraft(String competitor,
                                        String query,
                                        int rank,
                                        String title,
                                        String url,
                                        String snippet,
                                        String sourceType,
                                        int rulePriority,
                                        int sourceBudget) {
    }

    private record CandidateFetchResult(SearchCandidate candidate, EvidenceSource source) {
    }

    private record CandidateDeduplication(List<SearchCandidate> candidates) {
        private CandidateDeduplication {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }
    }

    private record CandidateFetchOutcome(int added,
                                         int target,
                                         Set<String> attemptedCandidateIds,
                                         Map<String, Integer> fetchedByCompetitor,
                                         Map<String, Integer> acceptedByCompetitor) {

        private CandidateFetchOutcome {
            attemptedCandidateIds = attemptedCandidateIds == null ? Set.of() : Set.copyOf(attemptedCandidateIds);
            fetchedByCompetitor = fetchedByCompetitor == null ? Map.of() : Map.copyOf(fetchedByCompetitor);
            acceptedByCompetitor = acceptedByCompetitor == null ? Map.of() : Map.copyOf(acceptedByCompetitor);
        }

        private boolean needsCandidatePoolFill(SearchCandidateCollection candidateCollection) {
            if (candidateCollection == null || candidateCollection.candidates().isEmpty()) {
                return false;
            }
            return added < Math.max(0, target) && !backupCandidateIds(candidateCollection).isEmpty();
        }

        private List<String> backupCandidateIds(SearchCandidateCollection candidateCollection) {
            Set<String> attempted = attemptedCandidateIds == null ? Set.of() : attemptedCandidateIds;
            return candidateCollection.candidates().stream()
                    .map(SearchCandidate::id)
                    .filter(id -> !attempted.contains(id))
                    .toList();
        }

        private CandidateFetchOutcome merge(CandidateFetchOutcome other) {
            if (other == null) {
                return this;
            }
            Set<String> attempted = new LinkedHashSet<>(attemptedCandidateIds);
            attempted.addAll(other.attemptedCandidateIds);
            return new CandidateFetchOutcome(
                    added + other.added,
                    target,
                    attempted,
                    mergeCounts(fetchedByCompetitor, other.fetchedByCompetitor),
                    mergeCounts(acceptedByCompetitor, other.acceptedByCompetitor)
            );
        }

        private static Map<String, Integer> mergeCounts(Map<String, Integer> first, Map<String, Integer> second) {
            Map<String, Integer> merged = new LinkedHashMap<>();
            if (first != null) {
                first.forEach((key, value) -> merged.merge(key, value, Integer::sum));
            }
            if (second != null) {
                second.forEach((key, value) -> merged.merge(key, value, Integer::sum));
            }
            return merged;
        }
    }

    private record SearchCandidateBatchResult(String competitor, List<SearchCandidateDraft> candidates, List<String> failures) {
        private static final SearchCandidateBatchResult EMPTY = new SearchCandidateBatchResult("", Collections.emptyList(), Collections.emptyList());
    }

    private record SearchBatchResult(String competitor, List<EvidenceSource> sources, List<String> failures) {
        private static final SearchBatchResult EMPTY = new SearchBatchResult("", Collections.emptyList(), Collections.emptyList());
    }

    private record OfficialSeed(EvidenceSource source, List<String> internalLinks) {
    }

    private record OfficialCandidateGroup(String name, List<String> paths) {
    }

    private record UrlParts(String scheme, String host, String path) {
    }
}
