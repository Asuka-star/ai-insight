package com.aiinsight.service;

import com.aiinsight.model.run.AnalysisRequirement;
import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.model.run.EvidenceSource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SurveyInsightExtractorTest {

    @Test
    void extractsStructuredSurveyInsightFromSurveyEvidence() {
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "Analyze Notion and Confluence.",
                "AI docs",
                List.of("Notion", "Confluence"),
                List.of("AI search", "Permission governance"),
                List.of("survey"),
                List.of(),
                "Product planning"
        ));
        run.getEvidenceSources().add(new EvidenceSource(
                "S1",
                "Survey results",
                "survey-import://survey-results.csv",
                "user_survey",
                "USER_PROVIDED",
                "USER_PROVIDED",
                "INTERNAL_ONLY",
                "NONE",
                "Sample size: 36",
                """
                        Survey title: AI docs survey
                        Sample size: 36
                        Respondent segments: product managers; IT administrators

                        Q: Which capability matters most for Notion and Confluence?
                        Dimension: AI search
                        Distribution: AI search=16; Permission governance=11; Pricing=9
                        Finding: 16 of 36 respondents selected "AI search", so this dimension is validated.
                        """,
                "User-provided survey evidence."
        ));

        var insights = new SurveyInsightExtractor().extract(run);

        assertThat(insights).hasSize(1);
        assertThat(insights.get(0).getEvidenceId()).isEqualTo("S1");
        assertThat(insights.get(0).getSampleSize()).isEqualTo("36 responses");
        assertThat(insights.get(0).getRespondentSegments()).contains("product managers", "IT administrators");
        assertThat(insights.get(0).getFindings()).hasSize(1);
        assertThat(insights.get(0).getFindings().get(0).getRelatedDimensions()).contains("AI search");
        assertThat(insights.get(0).getEvidenceIds()).containsExactly("S1");
    }

    @Test
    void extractsOnlyLatestSurveyEvidenceWhenMultipleSurveyResultsExist() {
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "Analyze Cursor.",
                "AI coding tools",
                List.of("Cursor"),
                List.of("Pricing"),
                List.of("survey"),
                List.of()
        ));
        run.getEvidenceSources().add(new EvidenceSource(
                "S1",
                "Old survey results",
                "survey-import://old.csv",
                "user_survey",
                "USER_PROVIDED",
                "USER_PROVIDED",
                "INTERNAL_ONLY",
                "NONE",
                "Sample size: 3",
                """
                        Survey title: Old survey
                        Sample size: 3
                        Respondent segments: product managers

                        Q: Old question?
                        Distribution: No=3
                        Finding: 3 of 3 respondents selected "No".
                        """,
                "Old survey evidence."
        ));
        run.getEvidenceSources().add(new EvidenceSource(
                "S2",
                "Latest survey results",
                "survey-import://latest.csv",
                "user_survey",
                "USER_PROVIDED",
                "USER_PROVIDED",
                "INTERNAL_ONLY",
                "NONE",
                "Sample size: 8",
                """
                        Survey title: Latest survey
                        Sample size: 8
                        Respondent segments: engineering managers

                        Q: Latest question?
                        Distribution: Yes=8
                        Finding: 8 of 8 respondents selected "Yes".
                        """,
                "Latest survey evidence."
        ));

        var insights = new SurveyInsightExtractor().extract(run);

        assertThat(insights).hasSize(1);
        assertThat(insights.get(0).getEvidenceId()).isEqualTo("S2");
        assertThat(insights.get(0).getSampleSize()).isEqualTo("8 responses");
        assertThat(insights.get(0).getFindings().get(0).getQuestion()).isEqualTo("Latest question?");
    }
}
