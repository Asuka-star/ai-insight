package com.aiinsight.service;

import com.aiinsight.model.run.AnalysisRequirement;
import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.model.run.EvidenceSource;
import com.aiinsight.model.run.UserProvidedEvidence;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

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
                List.of("核心功能"),
                List.of("official_site"),
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
    void keepsUserProvidedUrlEvenWhenFetchedContentLooksLikeChallengePage() {
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
        assertThat(sources.get(0).getCollectionStatus()).isEqualTo("FETCHED");
        assertThat(sources.get(0).getRawText()).contains("Cloudflare Ray ID");
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
        assertThat(sources.get(0).getSnippet()).contains("User-provided URL could not be fetched");
        assertThat(run.getRecommendedActions()).anyMatch(action -> action.contains("User-provided URL fetch failed"));
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
    void plansSearchQueriesFromDomainAndDimensionsWithoutFixedAiCollaborationForNonAiTopics() {
        List<String> queries = new ArrayList<>();
        SourceCollectionService service = new SourceCollectionService(fetchAlwaysFails(), recordingSearchProvider(queries));
        AnalysisRequirement requirement = new AnalysisRequirement(
                "分析 Salesforce 和 HubSpot 的销售自动化、价格策略和客户支持体验。",
                "企业服务 CRM",
                List.of("Salesforce", "HubSpot"),
                List.of("销售自动化", "价格策略", "客户支持体验"),
                List.of("official_site", "pricing_page", "public_reviews"),
                List.of()
        );
        AnalysisRun run = new AnalysisRun(requirement);

        service.collect(run, false);

        assertThat(queries).isNotEmpty();
        assertThat(queries).allMatch(query -> !query.contains("AI collaboration"));
        assertThat(queries).anyMatch(query -> query.contains("企业服务 CRM"));
        assertThat(queries).anyMatch(query -> query.contains("销售自动化"));
        assertThat(queries).anyMatch(query -> query.contains("pricing"));
        assertThat(queries).anyMatch(query -> query.contains("reviews"));
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
                queries.add(query);
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
}
