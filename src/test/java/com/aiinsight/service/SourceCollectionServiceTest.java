package com.aiinsight.service;

import com.aiinsight.model.enums.AgentName;
import com.aiinsight.model.enums.ResearchSubtaskStatus;
import com.aiinsight.model.enums.ReviewAction;
import com.aiinsight.model.review.ReviewRepairTask;
import com.aiinsight.model.run.AnalysisRequirement;
import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.model.run.EvidenceSource;
import com.aiinsight.model.run.UserProvidedEvidence;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class SourceCollectionServiceTest {

    @Test
    void collectsUserProvidedPublicUrlsAsEvidence() {
        WebPageFetchService fetchService = new WebPageFetchService() {
            @Override
            public FetchedPage fetch(String url) {
                return FetchedPage.success(
                        url,
                        "Notion product page",
                        "Notion provides docs, wikis, project management and AI collaboration features for teams.",
                        "robots.txt checked: allowed for public fetch."
                );
            }
        };
        SourceCollectionService service = new SourceCollectionService(fetchService, new NoopSearchProvider());
        AnalysisRequirement requirement = new AnalysisRequirement(
                "分析 Notion",
                "AI 协作文档",
                List.of("Notion"),
                List.of(),
                List.of(),
                List.of("https://www.notion.so/product")
        );
        AnalysisRun run = new AnalysisRun(requirement);

        var sources = service.collect(run, false);

        assertThat(sources).hasSize(1);
        assertThat(sources.get(0).getCitationKey()).isEqualTo("S1");
        assertThat(sources.get(0).getSourceType()).isEqualTo("user_source_url");
        assertThat(sources.get(0).getCollectionStatus()).isEqualTo("FETCHED");
        assertThat(sources.get(0).getFreshness()).isEqualTo("LIVE_FETCHED");
        assertThat(sources.get(0).getRawText()).contains("AI collaboration");
        assertThat(sources.get(0).getComplianceNote()).contains("robots.txt checked");
    }

    @Test
    void fetchesUserProvidedPublicUrlsInParallel() {
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();
        SourceCollectionProperties properties = new SourceCollectionProperties();
        properties.setMaxParallelFetches(4);
        SourceCollectionService service = new SourceCollectionService(
                parallelRecordingFetchService(inFlight, maxInFlight),
                new NoopSearchProvider(),
                new SearchQueryPlanner(),
                properties
        );
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "Analyze user supplied URLs",
                "AI coding tools",
                List.of("Cursor"),
                List.of(),
                List.of(),
                List.of(
                        "https://user-source.example.test/page-1",
                        "https://user-source.example.test/page-2",
                        "https://user-source.example.test/page-3",
                        "https://user-source.example.test/page-4"
                )
        ));

        var sources = service.collect(run, false);

        assertThat(sources).hasSize(4);
        assertThat(maxInFlight.get()).isGreaterThan(1);
        assertThat(sources)
                .extracting(EvidenceSource::getCitationKey)
                .containsExactly("S1", "S2", "S3", "S4");
    }

    @Test
    void deduplicatesUserProvidedUrlsAfterRedirectFetch() {
        SourceCollectionService service = new SourceCollectionService(cursorDocsRedirectFetchService(), new NoopSearchProvider());
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "Analyze Cursor docs",
                "AI coding tools",
                List.of("Cursor"),
                List.of("market landscape"),
                List.of(),
                List.of(
                        "https://docs.cursor.com/agent",
                        "https://docs.cursor.com/account/agent-security"
                )
        ));

        var sources = service.collect(run, false);

        assertThat(sources).hasSize(1);
        assertThat(sources)
                .extracting(EvidenceSource::getUrl)
                .containsExactly("https://cursor.com/cn/docs");
        assertThat(sources)
                .extracting(EvidenceSource::getCitationKey)
                .containsExactly("S1");
    }

    @Test
    void fetchesOfficialReferenceCandidatesInParallel() {
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();
        SourceCollectionProperties properties = new SourceCollectionProperties();
        properties.setMaxParallelFetches(4);
        SourceCollectionService service = new SourceCollectionService(
                parallelRecordingFetchService(inFlight, maxInFlight),
                new NoopSearchProvider(),
                new SearchQueryPlanner(),
                properties
        );
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "Analyze Cursor",
                "AI coding tools",
                List.of("Cursor"),
                List.of("core features", "pricing", "security"),
                List.of("official_site", "pricing_page"),
                List.of("https://www.cursor.com")
        ));

        var sources = service.collect(run, false);

        assertThat(maxInFlight.get()).isGreaterThan(1);
        assertThat(sources).hasSizeGreaterThan(1);
        assertThat(sources)
                .extracting(EvidenceSource::getUrl)
                .contains("https://www.cursor.com/pricing")
                .anyMatch(url -> url.equals("https://www.cursor.com/security"));
    }

    @Test
    void asyncFetchWindowTimesOutOnceForTheWholeWindow() {
        SourceCollectionProperties properties = new SourceCollectionProperties();
        properties.setMaxParallelFetches(4);
        properties.setAsyncTaskTimeoutSeconds(3);
        Executor stalledExecutor = command -> {
        };
        SourceCollectionService service = new SourceCollectionService(
                fetchUsefulPages(),
                new NoopSearchProvider(),
                new SearchQueryPlanner(),
                new SourceTypeClassifier(),
                properties,
                new LeadResearchPlanner(),
                stalledExecutor
        );
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "Analyze stalled fetches",
                "AI coding tools",
                List.of("Cursor"),
                List.of(),
                List.of(),
                List.of(
                        "https://stalled.example.test/page-1",
                        "https://stalled.example.test/page-2",
                        "https://stalled.example.test/page-3",
                        "https://stalled.example.test/page-4"
                )
        ));

        long started = System.nanoTime();
        var sources = service.collect(run, false);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertThat(sources).isEmpty();
        assertThat(elapsedMillis).isLessThan(7_000);
    }

    @Test
    void collectsUserProvidedEvidenceBeforeSearchEvidence() {
        SourceCollectionService service = new SourceCollectionService(fetchUsefulPages(), fakeSearchProvider());
        AnalysisRequirement requirement = new AnalysisRequirement(
                "Analyze Notion",
                "AI documents",
                List.of("Notion"),
                List.of("pricing"),
                List.of("public_reviews"),
                List.of()
        );
        AnalysisRun run = new AnalysisRun(requirement);
        run.getUserProvidedEvidence().add(new UserProvidedEvidence(
                "Internal interview notes",
                "interview",
                "Users like Notion templates but worry about enterprise permission governance.",
                "",
                true
        ));

        var sources = service.collect(run, false);

        assertThat(sources).hasSizeGreaterThan(1);
        assertThat(sources.get(0).getCitationKey()).isEqualTo("S1");
        assertThat(sources.get(0).getSourceType()).isEqualTo("user_interview");
        assertThat(sources.get(0).getCollectionStatus()).isEqualTo("USER_PROVIDED");
        assertThat(sources.get(0).getComplianceNote()).contains("internal-only");
        assertThat(sources)
                .extracting(EvidenceSource::getSourceType)
                .contains("docs");
        assertThat(sources)
                .extracting(EvidenceSource::getSourceQuality)
                .contains("HIGH");
        assertThat(sources)
                .extracting(EvidenceSource::getUrl)
                .filteredOn(url -> url.startsWith("https://"))
                .allMatch(url -> url.contains("search.example.test"));
    }

    @Test
    void recordsResearchSubtasksForCandidateSearchAndFetch() {
        SourceCollectionService service = new SourceCollectionService(fetchUsefulPages(), fakeSearchProvider());
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "Analyze Notion pricing",
                "AI documents",
                List.of("Notion"),
                List.of("pricing"),
                List.of("pricing_page"),
                List.of()
        ));
        List<SearchQueryPlanner.SearchQueryBatch> batches = List.of(
                new SearchQueryPlanner.SearchQueryBatch("Notion", List.of("Notion official pricing plans AI documents"))
        );

        SourceCollectionService.SearchCandidateCollection candidates = service.searchCandidates(run, false, batches);

        var plan = run.getResearchPackage().getResearchCollectionPlan();
        assertThat(plan.getRunId()).isEqualTo(run.getId());
        assertThat(plan.getGoal()).contains("Notion");
        assertThat(plan.getSubtasks()).hasSize(1);
        assertThat(plan.getEvidenceBudgets()).hasSize(1);
        assertThat(plan.getEvidenceBudgets().get(0).getMaxAcceptedSources()).isGreaterThan(0);
        assertThat(plan.getCandidateUrls()).hasSameSizeAs(candidates.candidates());
        var subtask = plan.getSubtasks().get(0);
        assertThat(subtask.getStatus()).isEqualTo(ResearchSubtaskStatus.SEARCHED);
        assertThat(subtask.getCompetitorName()).isEqualTo("Notion");
        assertThat(subtask.getDimension()).isEqualTo("pricing");
        assertThat(subtask.getCandidateUrlCount()).isGreaterThan(0);
        assertThat(subtask.getStartedAt()).isNotNull();

        service.collectSelectedSearchCandidates(run, false, candidates, List.of(candidates.candidates().get(0).id()));

        assertThat(subtask.getStatus()).isEqualTo(ResearchSubtaskStatus.SUCCEEDED);
        assertThat(subtask.getFetchedPageCount()).isGreaterThan(0);
        assertThat(subtask.getAcceptedEvidenceCount()).isGreaterThan(0);
        assertThat(subtask.getFinishedAt()).isNotNull();
    }

    @Test
    void initializesCoverageBudgetsForAllRequestedDimensions() {
        SourceCollectionService service = new SourceCollectionService(fetchUsefulPages(), fakeSearchProvider());
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "Analyze AI coding tools",
                "AI coding tools",
                List.of("Cursor", "Claude Code"),
                List.of("代码理解与生成能力", "IDE/终端集成", "上下文管理", "团队协作", "企业安全与权限", "定价模式", "目标用户"),
                List.of("official_site", "pricing_page"),
                List.of()
        ));
        List<SearchQueryPlanner.SearchQueryBatch> batches = List.of(
                new SearchQueryPlanner.SearchQueryBatch("Cursor", List.of("Cursor official pricing plans AI coding tools")),
                new SearchQueryPlanner.SearchQueryBatch("Claude Code", List.of("Claude Code official pricing plans AI coding tools"))
        );

        service.searchCandidates(run, false, batches);

        var budgets = run.getResearchPackage().getResearchCollectionPlan().getEvidenceBudgets();
        assertThat(budgets)
                .extracting(budget -> budget.getCompetitorName() + "|" + budget.getDimension())
                .contains(
                        "Cursor|code_generation",
                        "Cursor|ide_integration",
                        "Cursor|context_management",
                        "Cursor|team_collaboration",
                        "Cursor|security",
                        "Cursor|pricing",
                        "Cursor|customers",
                        "Claude Code|code_generation",
                        "Claude Code|ide_integration",
                        "Claude Code|context_management",
                        "Claude Code|team_collaboration",
                        "Claude Code|security",
                        "Claude Code|pricing",
                        "Claude Code|customers"
                );
    }

    @Test
    void fetchesSelectedSearchCandidatesInParallel() {
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();
        SourceCollectionProperties properties = new SourceCollectionProperties();
        properties.setMaxParallelFetches(4);
        SourceCollectionService service = new SourceCollectionService(
                parallelRecordingFetchService(inFlight, maxInFlight),
                new NoopSearchProvider(),
                new SearchQueryPlanner(),
                properties
        );
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "Analyze Cursor",
                "AI coding tools",
                List.of("Cursor"),
                List.of("pricing"),
                List.of("official_site"),
                List.of()
        ));
        List<SourceCollectionService.SearchCandidate> candidateList = java.util.stream.IntStream.rangeClosed(1, 4)
                .mapToObj(index -> new SourceCollectionService.SearchCandidate(
                        "C" + index,
                        "Cursor",
                        "Cursor pricing",
                        index,
                        "Cursor page " + index,
                        "https://candidate.example.test/page-" + index,
                        "Cursor pricing and product evidence.",
                        "pricing_page",
                        index,
                        4
                ))
                .toList();
        SourceCollectionService.SearchCandidateCollection candidates = new SourceCollectionService.SearchCandidateCollection(
                List.of(new SearchQueryPlanner.SearchQueryBatch("Cursor", List.of("Cursor pricing"))),
                candidateList,
                List.of(),
                true,
                4
        );
        SourceCollectionService.SearchCandidateCollection initialized = service.searchCandidates(
                run,
                false,
                candidates.batches()
        );
        assertThat(initialized.candidates()).isEmpty();
        run.getResearchPackage().getResearchCollectionPlan().getEvidenceBudgets().forEach(budget ->
                budget.setMaxAcceptedSources(4));

        var sources = service.collectSelectedSearchCandidates(
                run,
                false,
                candidates,
                candidateList.stream().map(SourceCollectionService.SearchCandidate::id).toList()
        );

        assertThat(sources).hasSize(4);
        assertThat(maxInFlight.get()).isGreaterThan(1);
        assertThat(run.getResearchPackage().getResearchCollectionPlan().getSubtasks().get(0).getFetchedPageCount())
                .isEqualTo(4);
    }

    @Test
    void recordsDuplicateCandidateUrlsAcrossCompetitors() {
        SourceCollectionService service = new SourceCollectionService(fetchUsefulPages(), new SearchProvider() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public List<SearchResult> search(String query, int count) {
                return List.of(new SearchResult(
                        "Shared pricing page",
                        "https://shared.example.test/pricing",
                        "Shared pricing page reused by multiple competitors.",
                        query,
                        1
                ));
            }
        });
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "Analyze shared pricing",
                "AI tools",
                List.of("Alpha", "Beta"),
                List.of("pricing"),
                List.of("pricing_page"),
                List.of()
        ));
        List<SearchQueryPlanner.SearchQueryBatch> batches = List.of(
                new SearchQueryPlanner.SearchQueryBatch("Alpha", List.of("Alpha pricing")),
                new SearchQueryPlanner.SearchQueryBatch("Beta", List.of("Beta pricing"))
        );

        SourceCollectionService.SearchCandidateCollection candidates = service.searchCandidates(run, false, batches);

        var candidateUrls = run.getResearchPackage().getResearchCollectionPlan().getCandidateUrls();
        assertThat(candidates.candidates()).hasSize(1);
        assertThat(candidateUrls).hasSize(2);
        assertThat(candidateUrls).filteredOn(candidate -> candidate.isDuplicate()).hasSize(1)
                .allSatisfy(candidate -> {
                    assertThat(candidate.getDuplicateOf()).isNotNull();
                    assertThat(candidate.getRejectionReason()).isEqualTo("duplicate_url");
                });
    }

    @Test
    void appliesEvidenceBudgetWhenPromotingCandidateEvidence() {
        SourceCollectionService service = new SourceCollectionService(fetchUsefulPages(), new NoopSearchProvider());
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "Analyze Cursor",
                "AI coding tools",
                List.of("Cursor"),
                List.of("pricing"),
                List.of("official_site"),
                List.of()
        ));
        List<SourceCollectionService.SearchCandidate> candidateList = java.util.stream.IntStream.rangeClosed(1, 3)
                .mapToObj(index -> new SourceCollectionService.SearchCandidate(
                        "C" + index,
                        "Cursor",
                        "Cursor pricing",
                        index,
                        "Cursor page " + index,
                        "https://budget.example.test/page-" + index,
                        "Cursor pricing and product evidence.",
                        "pricing_page",
                        index,
                        3
                ))
                .toList();
        SourceCollectionService.SearchCandidateCollection candidates = new SourceCollectionService.SearchCandidateCollection(
                List.of(new SearchQueryPlanner.SearchQueryBatch("Cursor", List.of("Cursor pricing"))),
                candidateList,
                List.of(),
                true,
                3
        );

        SourceCollectionService.SearchCandidateCollection initialized = service.searchCandidates(
                run,
                false,
                candidates.batches()
        );
        assertThat(initialized.candidates()).isEmpty();
        run.getResearchPackage().getResearchCollectionPlan().getEvidenceBudgets().get(0).setMaxAcceptedSources(1);

        var sources = service.collectSelectedSearchCandidates(
                run,
                false,
                candidates,
                candidateList.stream().map(SourceCollectionService.SearchCandidate::id).toList()
        );

        assertThat(sources).hasSize(1);
        assertThat(run.getResearchPackage().getResearchCollectionPlan().getSubtasks().get(0).getAcceptedEvidenceCount())
                .isEqualTo(1);
    }

    @Test
    void recollectionDoesNotBackfillUnselectedCandidates() {
        SourceCollectionService service = new SourceCollectionService(fetchUsefulPages(), new NoopSearchProvider());
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "Analyze Cursor",
                "AI coding tools",
                List.of("Cursor"),
                List.of("user feedback"),
                List.of("public_reviews"),
                List.of()
        ));
        run.getReviewDecision().setAction(ReviewAction.RECOLLECT_EVIDENCE);
        run.getReviewDecision().setTargetAgent(AgentName.RESEARCHER);
        ReviewRepairTask task = new ReviewRepairTask();
        task.setTargetAgent(AgentName.RESEARCHER);
        task.setCompetitorName("Cursor");
        task.setRequiredEvidenceTypes(List.of("public_review"));
        run.getReviewDecision().getRepairTasks().add(task);
        List<SourceCollectionService.SearchCandidate> candidateList = java.util.stream.IntStream.rangeClosed(1, 4)
                .mapToObj(index -> new SourceCollectionService.SearchCandidate(
                        "C" + index,
                        "Cursor",
                        "Cursor user reviews",
                        index,
                        "Cursor review page " + index,
                        "https://repair.example.test/page-" + index,
                        "Cursor user review evidence.",
                        "public_review",
                        index,
                        4
                ))
                .toList();
        SourceCollectionService.SearchCandidateCollection candidates = new SourceCollectionService.SearchCandidateCollection(
                List.of(new SearchQueryPlanner.SearchQueryBatch("Cursor", List.of("Cursor user reviews"))),
                candidateList,
                List.of(),
                true,
                4
        );

        var sources = service.collectSelectedSearchCandidates(run, true, candidates, List.of("C1"));

        assertThat(sources)
                .extracting(EvidenceSource::getUrl)
                .containsExactly("https://repair.example.test/page-1");
    }

    @Test
    void recollectionLimitsSelectableSourcesToRepairTasks() {
        SourceCollectionService service = new SourceCollectionService(fetchUsefulPages(), new SearchProvider() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public List<SearchResult> search(String query, int count) {
                return java.util.stream.IntStream.rangeClosed(1, 5)
                        .mapToObj(index -> new SearchResult(
                                "Cursor review " + index,
                                "https://repair-budget.example.test/page-" + index,
                                "Cursor review evidence " + index,
                                query,
                                index
                        ))
                        .toList();
            }
        });
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "Analyze Cursor",
                "AI coding tools",
                List.of("Cursor"),
                List.of("user feedback"),
                List.of("public_reviews"),
                List.of()
        ));
        run.getReviewDecision().setAction(ReviewAction.RECOLLECT_EVIDENCE);
        run.getReviewDecision().setTargetAgent(AgentName.RESEARCHER);
        ReviewRepairTask task = new ReviewRepairTask();
        task.setTargetAgent(AgentName.RESEARCHER);
        task.setCompetitorName("Cursor");
        task.setRequiredEvidenceTypes(List.of("public_review"));
        run.getReviewDecision().getRepairTasks().add(task);

        var candidates = service.searchCandidates(
                run,
                true,
                List.of(new SearchQueryPlanner.SearchQueryBatch("Cursor", List.of("Cursor user reviews")))
        );

        assertThat(candidates.maxSelectable()).isEqualTo(2);
        assertThat(run.getResearchPackage().getResearchCollectionPlan().getEvidenceBudgets().get(0).getMaxAcceptedSources())
                .isEqualTo(2);
    }

    @Test
    void marksSurveyEvidenceAsFirstPartyResearch() {
        SourceCollectionService service = new SourceCollectionService(fetchAlwaysFails(), new NoopSearchProvider());
        var source = service.fromUserProvidedEvidence("S1", new UserProvidedEvidence(
                "User survey round 1",
                "survey",
                "n=18; users rated AI search and citation traceability as the most important buying factors.",
                "",
                false
        ));

        assertThat(source.getSourceType()).isEqualTo("user_survey");
        assertThat(source.getComplianceNote()).contains("First-party survey evidence");
        assertThat(source.getFreshness()).isEqualTo("USER_PROVIDED");
    }

    @Test
    void cleansInterviewInsightListMarkersFromUserProvidedNotes() {
        SourceCollectionService service = new SourceCollectionService(fetchAlwaysFails(), new NoopSearchProvider());
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "Analyze Cursor and Claude Code",
                "AI coding tools",
                List.of("Cursor", "Claude Code"),
                List.of("权限/安全/合规", "价格策略", "用户体验"),
                List.of(),
                List.of()
        ));
        run.getEvidenceSources().add(service.fromUserProvidedEvidence("S105", new UserProvidedEvidence(
                "访谈对象 A：前端负责人",
                "interview",
                """
                        访谈对象 A：前端负责人，团队 18 人 - 目前主要使用 Cursor。
                        痛点
                        - 高频场景是跨文件重构、组件迁移、补测试。、- 痛点是团队规则需要维护，否则不同成员提示词风格不一致。
                        原话：Cursor 更像在 IDE 里多了一个能动手的同事，但复杂任务还是要人工拆边界。
                        主要顾虑是权限审批、命令执行安全、团队审计。
                        """,
                "",
                false
        )));

        var insights = new InterviewInsightExtractor().extract(run);

        assertThat(insights).hasSize(1);
        assertThat(insights.get(0).getPainPoints())
                .isNotEmpty()
                .allSatisfy(point -> assertThat(point).doesNotStartWith("-"));
        assertThat(insights.get(0).getDirectQuotes())
                .allSatisfy(quote -> assertThat(quote).doesNotStartWith("-"));
    }

    @Test
    void usesSearchResultsInsteadOfBuiltInOrSeedEvidence() {
        SourceCollectionService service = new SourceCollectionService(fetchUsefulPages(), fakeSearchProvider());
        AnalysisRequirement requirement = new AnalysisRequirement(
                "Analyze Notion and Confluence",
                "AI documents",
                List.of("Notion", "Confluence"),
                List.of("core features"),
                List.of("official_site"),
                List.of()
        );
        AnalysisRun run = new AnalysisRun(requirement);

        var sources = service.collect(run, true);

        assertThat(sources).isNotEmpty();
        assertThat(sources).allSatisfy(source -> assertThat(source.getUrl()).doesNotContain("example.com"));
        assertThat(sources)
                .extracting(source -> source.getSourceType())
                .containsOnly("docs");
        assertThat(sources)
                .extracting(source -> source.getSourceQuality())
                .containsOnly("HIGH");
        assertThat(sources)
                .extracting(source -> source.getFailureReason())
                .containsOnly("NONE");
        assertThat(sources)
                .extracting(source -> source.getContentHash())
                .allMatch(hash -> hash != null && !hash.isBlank());
        assertThat(sources)
                .extracting(source -> source.isCacheHit())
                .containsOnly(false);
        assertThat(sources)
                .extracting(source -> source.getCollectionStatus())
                .allMatch(status -> status.equals("FETCHED"));
        assertThat(sources)
                .extracting(source -> source.getFreshness())
                .allMatch(freshness -> freshness.equals("LIVE_FETCHED"));
        assertThat(sources)
                .extracting(source -> source.getComplianceNote())
                .allMatch(note -> note.contains("Search query="));
        assertThat(sources)
                .extracting(source -> source.getUrl())
                .anyMatch(url -> url.contains("search.example.test"));
    }

    @Test
    void searchesCompetitorBatchesInParallelAndKeepsCompetitorCoverage() {
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();
        SourceCollectionService service = new SourceCollectionService(fetchUsefulPages(), parallelRecordingSearchProvider(inFlight, maxInFlight));
        AnalysisRequirement requirement = new AnalysisRequirement(
                "Analyze Alpha, Beta and Gamma",
                "AI documents",
                List.of("Alpha", "Beta", "Gamma"),
                List.of("core features"),
                List.of("official_site"),
                List.of()
        );
        AnalysisRun run = new AnalysisRun(requirement);

        var sources = service.collect(run, false);

        assertThat(maxInFlight.get()).isGreaterThan(1);
        assertThat(sources)
                .extracting(EvidenceSource::getUrl)
                .anyMatch(url -> url.contains("/alpha/"))
                .anyMatch(url -> url.contains("/beta/"))
                .anyMatch(url -> url.contains("/gamma/"));
    }

    @Test
    void expandsSearchSourceBudgetForLargeCompetitorLists() {
        List<String> competitors = java.util.stream.IntStream.rangeClosed(1, 13)
                .mapToObj(index -> "Tool" + index)
                .toList();
        SourceCollectionService service = new SourceCollectionService(fetchUsefulPages(), fakeSearchProvider());
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "Analyze many AI tools",
                "AI tools",
                competitors,
                List.of("pricing"),
                List.of("official_site"),
                List.of()
        ));

        var sources = service.collect(run, false);

        assertThat(sources).hasSizeGreaterThan(12);
        assertThat(sources)
                .extracting(EvidenceSource::getComplianceNote)
                .allMatch(note -> note.contains("Search query="));
        assertThat(competitors)
                .allSatisfy(competitor -> assertThat(sources)
                        .extracting(EvidenceSource::getComplianceNote)
                        .anyMatch(note -> note.contains(competitor + " ")));
    }

    @Test
    void dropsAntiBotSearchSnippetsWhenPageFetchFails() {
        SourceCollectionService service = new SourceCollectionService(fetchAlwaysFails(), searchProviderWithSnippet(
                "Blocked",
                "https://blocked.example.test/page",
                "Just a moment... Enable JavaScript and cookies to continue. Cloudflare Ray ID: abc"
        ));
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "Analyze Notion",
                "AI documents",
                List.of("Notion"),
                List.of("core features"),
                List.of("official_site"),
                List.of()
        ));

        var sources = service.collect(run, false);

        assertThat(sources).isEmpty();
        assertThat(run.getRecommendedActions()).anyMatch(action -> action.contains("没有形成可用网页证据"));
    }

    @Test
    void dropsUnusableFetchedSearchResultWithoutMarkingItAsFetchFailureEvidence() {
        WebPageFetchService fetchService = new WebPageFetchService() {
            @Override
            public FetchedPage fetch(String url) {
                return FetchedPage.success(
                        url,
                        "Just a moment...",
                        "Enable JavaScript and cookies to continue. Cloudflare Ray ID: abc.",
                        "robots.txt checked: allowed for public fetch."
                );
            }
        };
        SourceCollectionService service = new SourceCollectionService(fetchService, searchProviderWithSnippet(
                "Blocked",
                "https://blocked.example.test/page",
                "Just a moment... Enable JavaScript and cookies to continue. Cloudflare Ray ID: abc"
        ));
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "Analyze Notion",
                "AI documents",
                List.of("Notion"),
                List.of("core features"),
                List.of("official_site"),
                List.of()
        ));

        var sources = service.collect(run, false);

        assertThat(sources).isEmpty();
        assertThat(run.getRecommendedActions()).anyMatch(action -> action.contains("没有形成可用网页证据"));
    }

    @Test
    void marksUserProvidedUrlAsUnusableWhenFetchedContentLooksLikeChallengePage() {
        WebPageFetchService fetchService = new WebPageFetchService() {
            @Override
            public FetchedPage fetch(String url) {
                return FetchedPage.success(
                        url,
                        "Just a moment...",
                        "Enable JavaScript and cookies to continue. Cloudflare Ray ID: abc.",
                        "robots.txt checked: allowed for public fetch."
                );
            }
        };
        SourceCollectionService service = new SourceCollectionService(fetchService, new NoopSearchProvider());
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "Analyze user supplied URL",
                "AI documents",
                List.of("Notion"),
                List.of("core features"),
                List.of("official_site"),
                List.of("https://user.example.test/provided")
        ));

        var sources = service.collect(run, false);

        assertThat(sources).hasSize(1);
        assertThat(sources.get(0).getSourceType()).isEqualTo("user_source_url");
        assertThat(sources.get(0).getCollectionStatus()).isEqualTo("UNUSABLE_CONTENT");
        assertThat(sources.get(0).getFailureReason()).isEqualTo("anti_bot_or_redirect_page");
        assertThat(sources.get(0).getRawText()).isEmpty();
        assertThat(run.getRecommendedActions()).anyMatch(action -> action.contains("anti-bot")
                || action.contains("placeholder"));
    }

    @Test
    void keepsFailedUserProvidedUrlAsFetchFailedEvidence() {
        SourceCollectionService service = new SourceCollectionService(fetchAlwaysFails(), new NoopSearchProvider());
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "Analyze user supplied URL",
                "AI documents",
                List.of("Notion"),
                List.of("core features"),
                List.of("official_site"),
                List.of("https://user.example.test/unreachable")
        ));

        var sources = service.collect(run, false);

        assertThat(sources).hasSize(1);
        assertThat(sources.get(0).getSourceType()).isEqualTo("user_source_url");
        assertThat(sources.get(0).getCollectionStatus()).isEqualTo("FETCH_FAILED");
        assertThat(sources.get(0).getFreshness()).isEqualTo("FETCH_FAILED");
        assertThat(sources.get(0).getSnippet()).contains("User-provided URL issue", "network or TLS fetch failed");
        assertThat(run.getRecommendedActions()).anyMatch(action -> action.contains("User-provided URL needs attention")
                && action.contains("network or TLS fetch failed"));
    }

    @Test
    void explainsEmptyTextUserProvidedUrlAsJavascriptRenderingIssue() {
        WebPageFetchService fetchService = new WebPageFetchService() {
            @Override
            public FetchedPage fetch(String url) {
                return FetchedPage.unusable(
                        url,
                        "JetBrains AI",
                        "",
                        "robots.txt checked: allowed for public fetch. statusCode=200; failureReason=EMPTY_TEXT; extractionMode=empty_text.",
                        "article",
                        "UNUSABLE",
                        "EMPTY_TEXT",
                        200,
                        "text/html"
                );
            }
        };
        SourceCollectionService service = new SourceCollectionService(fetchService, new NoopSearchProvider());
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "Analyze user supplied URL",
                "AI coding tools",
                List.of("JetBrains AI"),
                List.of("core features"),
                List.of("official_site"),
                List.of("https://www.jetbrains.com/ai/")
        ));

        var sources = service.collect(run, false);

        assertThat(sources).hasSize(1);
        assertThat(sources.get(0).getCollectionStatus()).isEqualTo("UNUSABLE_CONTENT");
        assertThat(sources.get(0).getFailureReason()).isEqualTo("EMPTY_TEXT");
        assertThat(sources.get(0).getSnippet()).contains("no extractable text", "JavaScript rendering");
        assertThat(run.getRecommendedActions()).anyMatch(action -> action.contains("JavaScript rendering"));
    }

    @Test
    void flagsMetadataOnlyUserProvidedUrlAsWeakEvidence() {
        WebPageFetchService fetchService = new WebPageFetchService() {
            @Override
            public FetchedPage fetch(String url) {
                return FetchedPage.success(
                        url,
                        "JetBrains AI",
                        "JetBrains AI brings AI-powered coding assistance, agent workflows, developer productivity tools, enterprise controls, team collaboration support, and software development expertise into JetBrains IDEs.",
                        "robots.txt checked: allowed for public fetch. renderFallback=failed; failureReason=METADATA_ONLY.",
                        "article",
                        "LOW",
                        "METADATA_ONLY",
                        200,
                        "text/html"
                );
            }
        };
        SourceCollectionService service = new SourceCollectionService(fetchService, new NoopSearchProvider());
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "Analyze user supplied URL",
                "AI coding tools",
                List.of("JetBrains AI"),
                List.of("core features"),
                List.of("official_site"),
                List.of("https://www.jetbrains.com/ai/")
        ));

        var sources = service.collect(run, false);

        assertThat(sources).hasSize(1);
        assertThat(sources.get(0).getCollectionStatus()).isEqualTo("FETCHED");
        assertThat(sources.get(0).getFailureReason()).isEqualTo("METADATA_ONLY");
        assertThat(run.getRecommendedActions()).anyMatch(action -> action.contains("only page metadata was extracted"));
    }

    @Test
    void explainsTlsUserProvidedUrlFailure() {
        WebPageFetchService fetchService = new WebPageFetchService() {
            @Override
            public FetchedPage fetch(String url) {
                return FetchedPage.failed(url, "PKIX path validation failed", "TLS_FAILED");
            }
        };
        SourceCollectionService service = new SourceCollectionService(fetchService, new NoopSearchProvider());
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "Analyze user supplied URL",
                "AI tools",
                List.of("OpenAI"),
                List.of("core features"),
                List.of("official_site"),
                List.of("https://openai.com/")
        ));

        var sources = service.collect(run, false);

        assertThat(sources).hasSize(1);
        assertThat(sources.get(0).getFailureReason()).isEqualTo("TLS_FAILED");
        assertThat(sources.get(0).getSnippet()).contains("TLS certificate validation failed");
        assertThat(run.getRecommendedActions()).anyMatch(action -> action.contains("TLS certificate validation failed"));
    }

    @Test
    void dropsSearchResultWhenFetchFailsEvenWithUsefulSnippet() {
        String longSnippet = "Useful AI coding assistant comparison. " + "pricing and reviews ".repeat(200);
        SourceCollectionService service = new SourceCollectionService(fetchAlwaysFails(), searchProviderWithSnippet(
                "Useful comparison",
                "https://search.example.test/useful-comparison",
                longSnippet
        ));
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "Analyze Notion",
                "AI documents",
                List.of("Notion"),
                List.of("pricing"),
                List.of("public_reviews"),
                List.of()
        ));

        var sources = service.collect(run, false);

        assertThat(sources).isEmpty();
    }

    @Test
    void recollectionPreservesExistingCitationKeysAndAppendsNewSources() {
        SourceCollectionService service = new SourceCollectionService(fetchUsefulPages(), fakeSearchProvider());
        AnalysisRequirement requirement = new AnalysisRequirement(
                "Analyze Notion",
                "AI documents",
                List.of("Notion"),
                List.of("core features"),
                List.of("official_site"),
                List.of()
        );
        AnalysisRun run = new AnalysisRun(requirement);
        var firstPass = service.collect(run, false);
        run.getEvidenceSources().addAll(firstPass);

        var recollected = service.collect(run, true);

        assertThat(recollected).hasSizeGreaterThan(firstPass.size());
        assertThat(recollected.subList(0, firstPass.size()))
                .extracting(EvidenceSource::getCitationKey)
                .containsExactlyElementsOf(firstPass.stream().map(EvidenceSource::getCitationKey).toList());
        assertThat(recollected.subList(0, firstPass.size()))
                .extracting(EvidenceSource::getUrl)
                .containsExactlyElementsOf(firstPass.stream().map(EvidenceSource::getUrl).toList());
        assertThat(recollected.stream().map(EvidenceSource::getCitationKey).distinct().toList())
                .hasSize(recollected.size());
        assertThat(recollected)
                .extracting(EvidenceSource::getCitationKey)
                .contains("S1", "S2");
    }

    @Test
    void unavailableSearchProviderCreatesEvidenceGapActionWithoutFakeEvidence() {
        SourceCollectionService service = new SourceCollectionService(fetchAlwaysFails(), new NoopSearchProvider());
        AnalysisRequirement requirement = new AnalysisRequirement(
                "Analyze UnknownDoc",
                "AI documents",
                List.of("UnknownDoc"),
                List.of("core features"),
                List.of("official_site"),
                List.of()
        );
        AnalysisRun run = new AnalysisRun(requirement);

        var sources = service.collect(run, false);

        assertThat(sources).isEmpty();
        assertThat(run.getRecommendedActions()).anyMatch(action -> action.contains("搜索服务未配置"));
    }

    @Test
    void clearsActualSearchQueriesWhenNoSearchBatchIsPlanned() {
        SourceCollectionService service = new SourceCollectionService(fetchAlwaysFails(), new NoopSearchProvider());
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "Analyze empty competitor list",
                "AI tools",
                List.of(),
                List.of("pricing"),
                List.of("official_site"),
                List.of()
        ));
        run.getResearchPackage().setActualSearchQueries(List.of("stale query"));

        service.collect(run, false);

        assertThat(run.getResearchPackage().getActualSearchQueries()).isEmpty();
    }

    @Test
    void addsPreferredPricingAndFeedbackSearchSourcesWhenRequested() {
        SourceCollectionService service = new SourceCollectionService(fetchUsefulPages(), fakeSearchProvider());
        AnalysisRequirement requirement = new AnalysisRequirement(
                "Analyze Notion",
                "AI documents",
                List.of("Notion"),
                List.of("价格策略", "用户评价"),
                List.of("official_site", "pricing_page", "public_reviews"),
                List.of()
        );
        AnalysisRun run = new AnalysisRun(requirement);

        var sources = service.collect(run, false);

        assertThat(sources).isNotEmpty();
        assertThat(sources)
                .extracting(EvidenceSource::getComplianceNote)
                .anyMatch(note -> note.contains("pricing"))
                .anyMatch(note -> note.contains("reviews"));
    }

    @Test
    void usesProvidedSearchQueryBatchesBeforeRulePlanner() {
        List<String> queries = new ArrayList<>();
        SourceCollectionService service = new SourceCollectionService(fetchUsefulPages(), recordingSearchProvider(queries));
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "Analyze AI coding tools",
                "AI coding tools",
                List.of("Cursor"),
                List.of("pricing"),
                List.of("official_site"),
                List.of()
        ));
        List<SearchQueryPlanner.SearchQueryBatch> plannedBatches = List.of(
                new SearchQueryPlanner.SearchQueryBatch(
                        "Cursor",
                        List.of("Cursor model selection official documentation")
                )
        );

        var sources = service.collect(run, false, plannedBatches);

        assertThat(sources).isNotEmpty();
        assertThat(queries).containsExactly("Cursor model selection official documentation");
        assertThat(queries).noneMatch(query -> query.contains("official pricing plans"));
    }

    @Test
    void supplementsMissingCompetitorsWithRuleQueriesOnInitialCollection() {
        List<String> queries = new ArrayList<>();
        SourceCollectionService service = new SourceCollectionService(fetchUsefulPages(), recordingSearchProvider(queries));
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "Analyze AI coding tools",
                "AI coding tools",
                List.of("Cursor", "GitHub Copilot"),
                List.of("pricing"),
                List.of("official_site"),
                List.of()
        ));
        List<SearchQueryPlanner.SearchQueryBatch> plannedBatches = List.of(
                new SearchQueryPlanner.SearchQueryBatch(
                        "Cursor",
                        List.of("Cursor model selection official documentation")
                )
        );

        service.collect(run, false, plannedBatches);

        assertThat(queries).anyMatch(query -> query.equals("Cursor model selection official documentation"));
        assertThat(queries).anyMatch(query -> query.contains("GitHub Copilot"));
    }

    @Test
    void allocatesMoreRecollectionResultsToRepairFocusedCompetitor() {
        SourceCollectionService service = new SourceCollectionService(fetchUsefulPages(), multiResultSearchProvider());
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "Analyze Alpha and Beta",
                "AI tools",
                List.of("Alpha", "Beta"),
                List.of("pricing"),
                List.of("official_site"),
                List.of()
        ));
        run.getReviewDecision().setAction(ReviewAction.RECOLLECT_EVIDENCE);
        run.getReviewDecision().setTargetAgent(AgentName.RESEARCHER);
        ReviewRepairTask task = new ReviewRepairTask();
        task.setTargetAgent(AgentName.RESEARCHER);
        task.setInstruction("Recollect Alpha official pricing evidence.");
        run.getReviewDecision().getRepairTasks().add(task);
        List<SearchQueryPlanner.SearchQueryBatch> plannedBatches = List.of(
                new SearchQueryPlanner.SearchQueryBatch("Alpha", List.of("Alpha pricing official docs", "Alpha plans official docs")),
                new SearchQueryPlanner.SearchQueryBatch("Beta", List.of("Beta pricing official docs", "Beta plans official docs"))
        );

        var sources = service.collect(run, true, plannedBatches);

        long alphaSources = sources.stream().filter(source -> source.getUrl().contains("/alpha/")).count();
        long betaSources = sources.stream().filter(source -> source.getUrl().contains("/beta/")).count();
        assertThat(alphaSources).isGreaterThan(betaSources);
    }

    @Test
    void recollectionOnlySearchesRepairFocusedCompetitorForPlannedBatches() {
        List<String> queries = new ArrayList<>();
        SourceCollectionService service = new SourceCollectionService(fetchUsefulPages(), recordingSearchProvider(queries));
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "Analyze Alpha and Beta",
                "AI tools",
                List.of("Alpha", "Beta"),
                List.of("pricing"),
                List.of("official_site"),
                List.of()
        ));
        run.getReviewDecision().setAction(ReviewAction.RECOLLECT_EVIDENCE);
        run.getReviewDecision().setTargetAgent(AgentName.RESEARCHER);
        ReviewRepairTask task = new ReviewRepairTask();
        task.setTargetAgent(AgentName.RESEARCHER);
        task.setInstruction("Recollect Alpha official pricing evidence.");
        run.getReviewDecision().getRepairTasks().add(task);
        List<SearchQueryPlanner.SearchQueryBatch> plannedBatches = List.of(
                new SearchQueryPlanner.SearchQueryBatch("Alpha", List.of("Alpha pricing official docs")),
                new SearchQueryPlanner.SearchQueryBatch("Beta", List.of("Beta pricing official docs"))
        );

        service.collect(run, true, plannedBatches);

        assertThat(queries).containsExactly("Alpha pricing official docs");
    }

    @Test
    void recollectionFocusAlsoAppliesToRulePlannedBatches() {
        List<String> queries = new ArrayList<>();
        SourceCollectionService service = new SourceCollectionService(fetchUsefulPages(), recordingSearchProvider(queries));
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "Analyze Alpha and Beta",
                "AI tools",
                List.of("Alpha", "Beta"),
                List.of("pricing"),
                List.of("official_site"),
                List.of()
        ));
        run.getReviewDecision().setAction(ReviewAction.RECOLLECT_EVIDENCE);
        run.getReviewDecision().setTargetAgent(AgentName.RESEARCHER);
        run.getReviewDecision().setRequiredEvidenceTypes(List.of("pricing_page"));
        ReviewRepairTask task = new ReviewRepairTask();
        task.setTargetAgent(AgentName.RESEARCHER);
        task.setInstruction("Recollect Alpha official pricing evidence.");
        run.getReviewDecision().getRepairTasks().add(task);

        service.collect(run, true);

        assertThat(queries).isNotEmpty();
        assertThat(queries).allMatch(query -> query.contains("Alpha"));
        assertThat(queries).noneMatch(query -> query.contains("Beta"));
    }

    @Test
    void plansSearchQueriesFromDomainAndDimensionsWithoutFixedAiCollaborationForNonAiTopics() {
        List<String> queries = new ArrayList<>();
        SourceCollectionService service = new SourceCollectionService(fetchAlwaysFails(), recordingSearchProvider(queries));
        AnalysisRequirement requirement = new AnalysisRequirement(
                "Analyze Salesforce and HubSpot sales automation, pricing strategy, and customer support experience",
                "Enterprise service CRM",
                List.of("Salesforce", "HubSpot"),
                List.of("sales automation", "pricing strategy", "customer support experience"),
                List.of("official_site", "pricing_page", "public_reviews"),
                List.of()
        );
        AnalysisRun run = new AnalysisRun(requirement);

        service.collect(run, false);

        assertThat(queries).isNotEmpty();
        assertThat(queries).allMatch(query -> !query.contains("AI collaboration"));
        assertThat(queries).anyMatch(query -> query.contains("Enterprise service CRM"));
        assertThat(queries).anyMatch(query -> query.contains("sales automation"));
        assertThat(queries).anyMatch(query -> query.contains("pricing"));
        assertThat(queries).anyMatch(query -> query.contains("reviews"));
    }

    @Test
    void derivesOfficialPricingCandidateFromUserProvidedOfficialUrlBeforeSearch() {
        WebPageFetchService fetchService = new WebPageFetchService() {
            @Override
            public FetchedPage fetch(String url) {
                if (url.equals("https://cursor.com/pricing") || url.equals("https://cursor.com/plans")) {
                    return FetchedPage.failed(url, "simulated not found", "HTTP_4XX");
                }
                if (url.equals("https://cursor.com/cn/pricing")) {
                    return FetchedPage.success(
                            url,
                            "Cursor pricing",
                            """
                                    Cursor official pricing page with Pro, Business, and enterprise plan details for AI coding teams.
                                    The page explains monthly and annual billing, team administration, model access, usage limits,
                                    privacy controls, and enterprise procurement options for software engineering organizations.
                                    """,
                            "robots.txt checked: allowed for public fetch.",
                            "pricing_page",
                            "HIGH",
                            200,
                            "text/html"
                    );
                }
                return FetchedPage.success(
                        url,
                        "Cursor homepage",
                        "Cursor official homepage for AI coding agents and developer productivity.",
                        "robots.txt checked: allowed for public fetch.",
                        "official_site",
                        "HIGH",
                        200,
                        "text/html"
                );
            }
        };
        SourceCollectionService service = new SourceCollectionService(fetchService, new NoopSearchProvider());
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "Analyze Cursor pricing",
                "AI coding tools",
                List.of("Cursor"),
                List.of("定价模式"),
                List.of("official_site", "pricing_page"),
                List.of("https://cursor.com")
        ));

        var sources = service.collect(run, false);

        assertThat(sources)
                .extracting(EvidenceSource::getUrl)
                .contains("https://cursor.com", "https://cursor.com/cn/pricing");
        assertThat(sources)
                .filteredOn(source -> source.getUrl().equals("https://cursor.com/cn/pricing"))
                .singleElement()
                .satisfies(source -> {
                    assertThat(source.getCitationKey()).isEqualTo("S2");
                    assertThat(source.getSourceType()).isEqualTo("pricing_page");
                    assertThat(source.getSourceQuality()).isEqualTo("HIGH");
                });
    }

    @Test
    void derivesRelevantOfficialReferencePagesFromUserProvidedOfficialUrl() {
        WebPageFetchService fetchService = new WebPageFetchService() {
            @Override
            public FetchedPage fetch(String url) {
                if (url.equals("https://cursor.com/docs")) {
                    return FetchedPage.success(
                            url,
                            "Cursor docs",
                            """
                                    Cursor official documentation explains codebase indexing, context management, IDE integration,
                                    terminal workflows, agent tools, model controls, and team setup guidance for engineering teams.
                                    """,
                            "robots.txt checked: allowed for public fetch.",
                            "docs",
                            "HIGH",
                            200,
                            "text/html"
                    );
                }
                if (url.equals("https://cursor.com/security")) {
                    return FetchedPage.success(
                            url,
                            "Cursor security",
                            """
                                    Cursor official security page explains enterprise controls, privacy protections, permission models,
                                    compliance posture, data retention boundaries, access management, and team administration settings.
                                    """,
                            "robots.txt checked: allowed for public fetch.",
                            "official_site",
                            "HIGH",
                            200,
                            "text/html"
                    );
                }
                if (url.equals("https://cursor.com")) {
                    return FetchedPage.success(
                            url,
                            "Cursor homepage",
                            "Cursor official homepage for AI coding agents and developer productivity.",
                            "robots.txt checked: allowed for public fetch.",
                            "official_site",
                            "HIGH",
                            200,
                            "text/html"
                    );
                }
                return FetchedPage.failed(url, "simulated missing official section", "HTTP_4XX");
            }
        };
        SourceCollectionService service = new SourceCollectionService(fetchService, new NoopSearchProvider());
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "Analyze Cursor official capabilities",
                "AI coding tools",
                List.of("Cursor"),
                List.of("code understanding and generation", "IDE terminal integration", "enterprise security and permissions"),
                List.of("official_site", "product_docs", "security"),
                List.of("https://cursor.com")
        ));

        var sources = service.collect(run, false);

        assertThat(sources)
                .extracting(EvidenceSource::getUrl)
                .contains("https://cursor.com", "https://cursor.com/docs", "https://cursor.com/security");
        assertThat(sources)
                .extracting(EvidenceSource::getComplianceNote)
                .anyMatch(note -> note.contains("requestedOfficialSection=docs"))
                .anyMatch(note -> note.contains("requestedOfficialSection=security"));
    }

    @Test
    void prefersDiscoveredOfficialNavigationLinksBeforeFallbackPaths() {
        WebPageFetchService fetchService = new WebPageFetchService() {
            @Override
            public FetchedPage fetch(String url) {
                if (url.equals("https://cursor.com")) {
                    return FetchedPage.success(
                            url,
                            "Cursor homepage",
                            "Cursor homepage with AI coding features, agent workflows, IDE integration, and enterprise security.",
                            "robots.txt checked: allowed for public fetch.",
                            "official_site",
                            "HIGH",
                            "NONE",
                            200,
                            "text/html",
                            List.of("https://cursor.com/docs/context", "https://cursor.com/trust/security")
                    );
                }
                if (url.equals("https://cursor.com/docs/context")) {
                    return FetchedPage.success(
                            url,
                            "Cursor context docs",
                            """
                                    Cursor official context documentation explains codebase indexing, repository understanding,
                                    prompt context windows, IDE workflows, agent operations, model controls, and team usage patterns.
                                    """,
                            "robots.txt checked: allowed for public fetch.",
                            "docs",
                            "HIGH",
                            200,
                            "text/html"
                    );
                }
                if (url.equals("https://cursor.com/trust/security")) {
                    return FetchedPage.success(
                            url,
                            "Cursor trust security",
                            """
                                    Cursor official trust and security page explains privacy controls, enterprise permissions,
                                    access management, compliance posture, audit readiness, data retention, and procurement security.
                                    """,
                            "robots.txt checked: allowed for public fetch.",
                            "official_site",
                            "HIGH",
                            200,
                            "text/html"
                    );
                }
                return FetchedPage.failed(url, "fallback path should not be needed", "HTTP_4XX");
            }
        };
        SourceCollectionService service = new SourceCollectionService(fetchService, new NoopSearchProvider());
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "Analyze Cursor docs and security",
                "AI coding tools",
                List.of("Cursor"),
                List.of("context management", "enterprise security and permissions"),
                List.of("official_site", "product_docs", "security"),
                List.of("https://cursor.com")
        ));

        var sources = service.collect(run, false);

        assertThat(sources)
                .extracting(EvidenceSource::getUrl)
                .contains("https://cursor.com/docs/context", "https://cursor.com/trust/security");
        assertThat(sources)
                .extracting(EvidenceSource::getUrl)
                .doesNotContain("https://cursor.com/docs", "https://cursor.com/security");
    }

    @Test
    void deduplicatesOfficialReferenceCandidatesAfterRedirectFetch() {
        WebPageFetchService fetchService = new WebPageFetchService() {
            @Override
            public FetchedPage fetch(String url) {
                if (url.equals("https://cursor.com")) {
                    return FetchedPage.success(
                            url,
                            "Cursor homepage",
                            "Cursor homepage with agent workflows, context management, IDE integration, and enterprise security.",
                            "robots.txt checked: allowed for public fetch.",
                            "official_site",
                            "HIGH",
                            "NONE",
                            200,
                            "text/html",
                            List.of("https://docs.cursor.com/docs", "https://docs.cursor.com/security")
                    );
                }
                if (url.equals("https://docs.cursor.com/docs") || url.equals("https://docs.cursor.com/security")) {
                    return FetchedPage.success(
                            "https://cursor.com/cn/docs",
                            "Cursor docs",
                            """
                                    Cursor official documentation explains agent workflows, codebase context, repository indexing,
                                    IDE integration, terminal tooling, security controls, team rules, and MCP configuration details
                                    for software engineering teams evaluating AI coding assistants.
                                    """,
                            "robots.txt checked: allowed for public fetch. Redirect followed to https://cursor.com/cn/docs.",
                            "docs",
                            "HIGH",
                            200,
                            "text/html"
                    );
                }
                return FetchedPage.failed(url, "simulated missing official section", "HTTP_4XX");
            }
        };
        SourceCollectionService service = new SourceCollectionService(fetchService, new NoopSearchProvider());
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "Analyze Cursor docs and security",
                "AI coding tools",
                List.of("Cursor"),
                List.of("context management", "enterprise security and permissions"),
                List.of("official_site", "product_docs", "security"),
                List.of("https://cursor.com")
        ));

        var sources = service.collect(run, false);

        assertThat(sources)
                .extracting(EvidenceSource::getUrl)
                .containsExactly("https://cursor.com", "https://cursor.com/cn/docs");
        assertThat(sources)
                .extracting(EvidenceSource::getCitationKey)
                .containsExactly("S1", "S2");
    }

    @Test
    void deduplicatesSearchCandidatesAfterRedirectFetch() {
        SourceCollectionService service = new SourceCollectionService(cursorDocsRedirectFetchService(), new NoopSearchProvider());
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "Analyze Cursor docs",
                "AI coding tools",
                List.of("Cursor"),
                List.of("agent workflow"),
                List.of("product_docs"),
                List.of()
        ));
        List<SourceCollectionService.SearchCandidate> candidateList = List.of(
                new SourceCollectionService.SearchCandidate(
                        "C1",
                        "Cursor",
                        "Cursor agent workflow",
                        1,
                        "Cursor Agent docs",
                        "https://docs.cursor.com/agent",
                        "Cursor Agent docs",
                        "docs",
                        1,
                        4
                ),
                new SourceCollectionService.SearchCandidate(
                        "C2",
                        "Cursor",
                        "Cursor agent workflow",
                        2,
                        "Cursor Agent security docs",
                        "https://docs.cursor.com/account/agent-security",
                        "Cursor Agent security docs",
                        "docs",
                        2,
                        4
                )
        );
        SourceCollectionService.SearchCandidateCollection candidates = new SourceCollectionService.SearchCandidateCollection(
                List.of(new SearchQueryPlanner.SearchQueryBatch("Cursor", List.of("Cursor agent workflow"))),
                candidateList,
                List.of(),
                true,
                4
        );
        service.searchCandidates(run, false, candidates.batches());

        var sources = service.collectSelectedSearchCandidates(
                run,
                false,
                candidates,
                candidateList.stream().map(SourceCollectionService.SearchCandidate::id).toList()
        );

        assertThat(sources).hasSize(1);
        assertThat(sources)
                .extracting(EvidenceSource::getUrl)
                .containsExactly("https://cursor.com/cn/docs");
        assertThat(run.getResearchPackage().getResearchCollectionPlan().getSubtasks().get(0).getAcceptedEvidenceCount())
                .isEqualTo(1);
    }

    @Test
    void prioritizesOfficialSearchResultsOverThirdPartyPricingReferences() {
        SourceCollectionService service = new SourceCollectionService(fetchUsefulPages(), new SearchProvider() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public List<SearchResult> search(String query, int count) {
                return List.of(
                        new SearchResult("Cursor pricing explained", "https://example-blog.test/cursor-pricing", "Third-party pricing summary.", query, 1),
                        new SearchResult("Cursor pricing", "https://cursor.com/cn/pricing", "Official Cursor pricing page.", query, 2)
                );
            }
        });
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "Analyze Cursor pricing",
                "AI coding tools",
                List.of("Cursor"),
                List.of("定价模式"),
                List.of("pricing_page"),
                List.of()
        ));

        var sources = service.collect(run, false);

        assertThat(sources).isNotEmpty();
        assertThat(sources.get(0).getUrl()).isEqualTo("https://cursor.com/cn/pricing");
    }

    @Test
    void dropsRegionUnavailableSearchResultPages() {
        WebPageFetchService fetchService = new WebPageFetchService() {
            @Override
            public FetchedPage fetch(String url) {
                return FetchedPage.success(
                        url,
                        "App unavailable in region | Claude",
                        "App unavailable in region. Claude is not currently available in your region.",
                        "robots.txt checked: allowed for public fetch.",
                        "article",
                        "MEDIUM",
                        200,
                        "text/html"
                );
            }
        };
        SourceCollectionService service = new SourceCollectionService(fetchService, searchProviderWithSnippet(
                "App unavailable in region | Claude",
                "https://claude.com/app-unavailable-in-region",
                "App unavailable in region"
        ));
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "Analyze Claude Code",
                "AI coding tools",
                List.of("Claude Code"),
                List.of("Agent workflow"),
                List.of("official_site"),
                List.of()
        ));

        var sources = service.collect(run, false);

        assertThat(sources).isEmpty();
        assertThat(run.getRecommendedActions()).anyMatch(action -> action.contains("没有形成可用网页证据"));
    }

    @Test
    void dropsLowQualitySearchDerivedSources() {
        WebPageFetchService fetchService = new WebPageFetchService() {
            @Override
            public FetchedPage fetch(String url) {
                return FetchedPage.success(
                        url,
                        "Demo video page",
                        """
                                This page contains a long transcript-like promotional description with enough text to pass
                                basic extraction length checks, but it is still a low-quality video result and should not
                                become fetched evidence for downstream analysis.
                                """,
                        "robots.txt checked: allowed for public fetch.",
                        "video",
                        "LOW",
                        200,
                        "text/html"
                );
            }
        };
        SourceCollectionService service = new SourceCollectionService(fetchService, searchProviderWithSnippet(
                "Demo video",
                "https://www.youtube.com/watch?v=abc123",
                "Video walkthrough"
        ));
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "Analyze Claude Code",
                "AI coding tools",
                List.of("Claude Code"),
                List.of("agent workflow"),
                List.of("public_review"),
                List.of()
        ));

        var sources = service.collect(run, false);

        assertThat(sources).isEmpty();
    }

    @Test
    void dropsLoginRedirectSearchDerivedSources() {
        WebPageFetchService fetchService = new WebPageFetchService() {
            @Override
            public FetchedPage fetch(String url) {
                return FetchedPage.success(
                        "https://login.feishu.cn/accounts/trap?app_id=2&login_redirect_times=1",
                        "login.feishu.cn",
                        "Sign in required. Please login to continue.",
                        "robots.txt checked: allowed for public fetch.",
                        "article",
                        "MEDIUM",
                        302,
                        "text/html"
                );
            }
        };
        SourceCollectionService service = new SourceCollectionService(fetchService, searchProviderWithSnippet(
                "Cursor wiki",
                "https://docs.feishu.cn/v/wiki/example",
                "Cursor implementation notes"
        ));
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "Analyze Cursor",
                "AI coding tools",
                List.of("Cursor"),
                List.of("enterprise usage"),
                List.of("article"),
                List.of()
        ));

        var sources = service.collect(run, false);

        assertThat(sources).isEmpty();
    }

    @Test
    void dropsThinMediumSearchArticles() {
        WebPageFetchService fetchService = new WebPageFetchService() {
            @Override
            public FetchedPage fetch(String url) {
                return FetchedPage.success(
                        url,
                        "Cursor 2.0 guide",
                        "Cursor quick guide with a few extracted words only.",
                        "robots.txt checked: allowed for public fetch.",
                        "article",
                        "MEDIUM",
                        200,
                        "text/html"
                );
            }
        };
        SourceCollectionService service = new SourceCollectionService(fetchService, searchProviderWithSnippet(
                "Cursor 2.0 guide",
                "https://example.test/cursor-short-guide",
                "Cursor guide"
        ));
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "Analyze Cursor",
                "AI coding tools",
                List.of("Cursor"),
                List.of("agent workflow"),
                List.of("article"),
                List.of()
        ));

        var sources = service.collect(run, false);

        assertThat(sources).isEmpty();
    }

    @Test
    void doesNotDeriveClaudeChineseLocaleOfficialFallbacks() {
        List<String> fetchedUrls = new ArrayList<>();
        WebPageFetchService fetchService = new WebPageFetchService() {
            @Override
            public FetchedPage fetch(String url) {
                fetchedUrls.add(url);
                if (url.equals("https://claude.com/product/claude-code")) {
                    return FetchedPage.success(
                            url,
                            "Claude Code",
                            """
                                    Claude Code official product page describes agentic coding, terminal workflows,
                                    enterprise development practices, documentation, security, and release context.
                                    """,
                            "robots.txt checked: allowed for public fetch.",
                            "official_site",
                            "HIGH",
                            200,
                            "text/html"
                    );
                }
                return FetchedPage.failed(url, "simulated missing official section", "HTTP_4XX");
            }
        };
        SourceCollectionService service = new SourceCollectionService(fetchService, new NoopSearchProvider());
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "Analyze Claude Code",
                "AI coding tools",
                List.of("Claude Code"),
                List.of("docs", "security", "pricing"),
                List.of("official_site", "docs", "security", "pricing_page"),
                List.of("https://claude.com/product/claude-code")
        ));

        service.collect(run, false);

        assertThat(fetchedUrls).noneMatch(url -> url.contains("https://claude.com/cn/"));
    }

    @Test
    void doesNotFetchKnownClaudeAiChallengeFallbacks() {
        List<String> fetchedUrls = new ArrayList<>();
        WebPageFetchService fetchService = new WebPageFetchService() {
            @Override
            public FetchedPage fetch(String url) {
                fetchedUrls.add(url);
                if (url.equals("https://claude.com/product/claude-code")) {
                    return FetchedPage.success(
                            url,
                            "Claude Code",
                            """
                                    Claude Code official product page describes agentic coding, terminal workflows,
                                    enterprise development practices, documentation, security, pricing, and release context.
                                    """,
                            "robots.txt checked: allowed for public fetch.",
                            "official_site",
                            "HIGH",
                            200,
                            "text/html"
                    );
                }
                return FetchedPage.failed(url, "simulated missing official section", "HTTP_4XX");
            }
        };
        SourceCollectionService service = new SourceCollectionService(fetchService, new NoopSearchProvider());
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "Analyze Claude Code",
                "AI coding tools",
                List.of("Claude Code"),
                List.of("docs", "security", "pricing", "release notes"),
                List.of("official_site", "docs", "security", "pricing_page", "release_notes"),
                List.of("https://claude.com/product/claude-code")
        ));

        service.collect(run, false);

        assertThat(fetchedUrls).noneMatch(url -> url.startsWith("https://claude.ai/"));
    }

    private WebPageFetchService fetchAlwaysFails() {
        return new WebPageFetchService() {
            @Override
            public FetchedPage fetch(String url) {
                return FetchedPage.failed(url, "simulated fetch failure");
            }
        };
    }

    private WebPageFetchService fetchUsefulPages() {
        return new WebPageFetchService() {
            @Override
            public FetchedPage fetch(String url) {
                return FetchedPage.success(
                        url,
                        "Useful page for " + url,
                        """
                                This official product documentation page describes pricing, reviews, enterprise controls,
                                collaboration workflows, permission governance, AI features, release notes, support options,
                                customer feedback, integration details, and product positioning for competitive analysis.
                                The content is intentionally long enough to be treated as a useful fetched search result.
                                """,
                        "robots.txt checked: allowed for public fetch.",
                        "docs",
                        "HIGH",
                        200,
                        "text/html"
                );
            }
        };
    }

    private WebPageFetchService parallelRecordingFetchService(AtomicInteger inFlight, AtomicInteger maxInFlight) {
        return new WebPageFetchService() {
            @Override
            public FetchedPage fetch(String url) {
                int current = inFlight.incrementAndGet();
                maxInFlight.accumulateAndGet(current, Math::max);
                try {
                    Thread.sleep(80);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } finally {
                    inFlight.decrementAndGet();
                }
                return FetchedPage.success(
                        url,
                        "Useful page for " + url,
                        """
                                This official product documentation page describes pricing, reviews, enterprise controls,
                                collaboration workflows, permission governance, AI features, release notes, support options,
                                customer feedback, integration details, and product positioning for competitive analysis.
                                The content is intentionally long enough to be treated as a useful fetched search result.
                                """,
                        "robots.txt checked: allowed for public fetch.",
                        "docs",
                        "HIGH",
                        200,
                        "text/html"
                );
            }
        };
    }

    private WebPageFetchService cursorDocsRedirectFetchService() {
        return new WebPageFetchService() {
            @Override
            public FetchedPage fetch(String url) {
                if (url.equals("https://docs.cursor.com/agent") || url.equals("https://docs.cursor.com/account/agent-security")) {
                    return FetchedPage.success(
                            "https://cursor.com/cn/docs",
                            "Cursor docs",
                            """
                                    Cursor official documentation explains agent workflows, codebase context, repository indexing,
                                    IDE integration, terminal tooling, security controls, team rules, and MCP configuration details
                                    for software engineering teams evaluating AI coding assistants.
                                    """,
                            "robots.txt checked: allowed for public fetch. Redirect followed to https://cursor.com/cn/docs.",
                            "docs",
                            "HIGH",
                            200,
                            "text/html"
                    );
                }
                return FetchedPage.success(
                        url,
                        "Useful page for " + url,
                        """
                                This official product documentation page describes pricing, reviews, enterprise controls,
                                collaboration workflows, permission governance, AI features, release notes, support options,
                                customer feedback, integration details, and product positioning for competitive analysis.
                                The content is intentionally long enough to be treated as a useful fetched search result.
                                """,
                        "robots.txt checked: allowed for public fetch.",
                        "docs",
                        "HIGH",
                        200,
                        "text/html"
                );
            }
        };
    }

    private SearchProvider fakeSearchProvider() {
        return new SearchProvider() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public List<SearchResult> search(String query, int count) {
                return List.of(new SearchResult(
                        "Search result for " + query,
                        "https://search.example.test/" + query.toLowerCase().replaceAll("[^a-z0-9]+", "-"),
                        "Snippet for " + query + " with pricing, reviews, AI collaboration and permission details.",
                        query,
                        1
                ));
            }
        };
    }

    private SearchProvider searchProviderWithSnippet(String title, String url, String snippet) {
        return new SearchProvider() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public List<SearchResult> search(String query, int count) {
                return List.of(new SearchResult(title, url, snippet, query, 1));
            }
        };
    }

    private SearchProvider recordingSearchProvider(List<String> queries) {
        return new SearchProvider() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public List<SearchResult> search(String query, int count) {
                synchronized (queries) {
                    queries.add(query);
                }
                return List.of(new SearchResult(
                        "Search result for " + query,
                        "https://search.example.test/" + query.toLowerCase().replaceAll("[^a-z0-9]+", "-"),
                        "Snippet for " + query + " with CRM pricing and user feedback details.",
                        query,
                        1
                ));
            }
        };
    }

    private SearchProvider multiResultSearchProvider() {
        return new SearchProvider() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public List<SearchResult> search(String query, int count) {
                String competitor = query.split("\\s+")[0].toLowerCase();
                return java.util.stream.IntStream.rangeClosed(1, count)
                        .mapToObj(index -> new SearchResult(
                                "Search result " + index + " for " + query,
                                "https://search.example.test/" + competitor + "/" + Integer.toUnsignedString((query + index).hashCode()),
                                "Snippet for " + query + " with pricing, official documentation, user feedback and enterprise details.",
                                query,
                                index
                        ))
                        .toList();
            }
        };
    }

    private SearchProvider parallelRecordingSearchProvider(AtomicInteger inFlight, AtomicInteger maxInFlight) {
        return new SearchProvider() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public List<SearchResult> search(String query, int count) {
                int current = inFlight.incrementAndGet();
                maxInFlight.accumulateAndGet(current, Math::max);
                try {
                    Thread.sleep(80);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } finally {
                    inFlight.decrementAndGet();
                }
                String competitor = query.split("\\s+")[0].toLowerCase();
                return List.of(new SearchResult(
                        "Search result for " + query,
                        "https://search.example.test/" + competitor + "/" + Integer.toUnsignedString(query.hashCode()),
                        "Snippet for " + query + " with pricing, reviews, AI collaboration and permission details.",
                        query,
                        1
                ));
            }
        };
    }
}
