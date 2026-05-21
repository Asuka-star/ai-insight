package com.aiinsight.service;

import com.aiinsight.model.run.AnalysisRequirement;
import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.model.run.UserProvidedEvidence;
import org.junit.jupiter.api.Test;

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
        SourceCollectionService service = new SourceCollectionService(fetchService);
        AnalysisRequirement requirement = new AnalysisRequirement(
                "分析 Notion",
                "AI 协作文档",
                List.of("Notion", "飞书文档"),
                List.of("核心功能"),
                List.of("official_site"),
                List.of("https://www.notion.so/product")
        );
        AnalysisRun run = new AnalysisRun(requirement);

        var sources = service.collect(run, false);

        assertThat(sources).hasSize(1);
        assertThat(sources.get(0).getCitationKey()).isEqualTo("S1");
        assertThat(sources.get(0).getSourceType()).isEqualTo("public_web_page");
        assertThat(sources.get(0).getRawText()).contains("AI collaboration");
        assertThat(sources.get(0).getComplianceNote()).contains("robots.txt checked");
    }

    @Test
    void collectsUserProvidedEvidenceBeforeSeedEvidence() {
        SourceCollectionService service = new SourceCollectionService(new WebPageFetchService());
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

        assertThat(sources).hasSize(1);
        assertThat(sources.get(0).getCitationKey()).isEqualTo("S1");
        assertThat(sources.get(0).getSourceType()).isEqualTo("user_interview");
        assertThat(sources.get(0).getComplianceNote()).contains("internal-only");
    }
}
