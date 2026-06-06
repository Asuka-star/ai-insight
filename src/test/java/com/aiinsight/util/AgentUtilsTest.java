package com.aiinsight.util;

import com.aiinsight.model.run.AnalysisRequirement;
import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.model.run.EvidenceSource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentUtilsTest {

    @Test
    void knownEvidenceIdsNormalizesBracketedIdsAndDeduplicates() {
        AnalysisRun run = runWithSources("S1", "S2");

        List<String> ids = AgentUtils.knownEvidenceIds(
                run,
                List.of("[S1]", "S1", "S3", "S2"),
                List.of("S2")
        );

        assertThat(ids).containsExactly("S1", "S2");
    }

    @Test
    void knownEvidenceIdsFallsBackWhenCandidatesAreUnknown() {
        AnalysisRun run = runWithSources("S1", "S2");

        List<String> ids = AgentUtils.knownEvidenceIds(
                run,
                List.of("S9"),
                List.of("[S2]", "S1")
        );

        assertThat(ids).containsExactly("S2", "S1");
    }

    @Test
    void firstCitationKeyExtractsBareOrBracketedCitationKey() {
        assertThat(AgentUtils.firstCitationKey("", "evidence [S12]", "S1")).isEqualTo("S12");
        assertThat(AgentUtils.firstCitationKey("citationKey=S7")).isEqualTo("S7");
        assertThat(AgentUtils.firstCitationKey("no citation")).isNull();
    }

    @Test
    void containsAnyIsCaseInsensitive() {
        assertThat(AgentUtils.containsAny("Enterprise Governance", "governance")).isTrue();
        assertThat(AgentUtils.containsAny("Enterprise Governance", "pricing")).isFalse();
    }

    private AnalysisRun runWithSources(String... citationKeys) {
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "Analyze product",
                "SaaS",
                List.of("Product"),
                List.of("pricing"),
                List.of(),
                List.of()
        ));
        for (String citationKey : citationKeys) {
            run.getEvidenceSources().add(new EvidenceSource(
                    citationKey,
                    citationKey + " title",
                    "https://example.test/" + citationKey,
                    "official",
                    "FETCHED",
                    "FRESH",
                    "HIGH",
                    "NONE",
                    "snippet",
                    "raw text",
                    ""
            ));
        }
        return run;
    }
}
