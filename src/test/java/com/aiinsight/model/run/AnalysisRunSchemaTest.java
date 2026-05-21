package com.aiinsight.model.run;

import com.aiinsight.model.enums.ClaimType;
import com.aiinsight.model.schema.AnalysisClaim;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisRunSchemaTest {

    @Test
    void initializesStructuredSchemaState() {
        AnalysisRun run = new AnalysisRun();

        assertThat(run.getResearchPackage()).isNotNull();
        assertThat(run.getCompetitorProfiles()).isEmpty();
        assertThat(run.getClaims()).isEmpty();
        assertThat(run.getReviewDecision()).isNotNull();
        assertThat(run.getTraces()).isEmpty();
        assertThat(run.getWorkflowTransitions()).isEmpty();
    }

    @Test
    void claimCarriesEvidenceIdsForTraceability() {
        AnalysisClaim claim = new AnalysisClaim();
        claim.setType(ClaimType.OPPORTUNITY);
        claim.setContent("可溯源报告是差异化机会点");
        claim.getEvidenceIds().add("S1");

        assertThat(claim.getId()).startsWith("C-");
        assertThat(claim.getEvidenceIds()).containsExactly("S1");
    }
}
