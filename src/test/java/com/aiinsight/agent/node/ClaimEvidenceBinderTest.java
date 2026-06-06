package com.aiinsight.agent.node;

import com.aiinsight.model.enums.ClaimType;
import com.aiinsight.model.enums.ConfidenceLevel;
import com.aiinsight.model.run.AnalysisRequirement;
import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.model.run.EvidenceSource;
import com.aiinsight.model.schema.AnalysisClaim;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ClaimEvidenceBinderTest {

    private final ClaimEvidenceBinder binder = new ClaimEvidenceBinder();

    @Test
    void keepsEvidenceWhenSourceDirectlySupportsClaim() {
        AnalysisRun run = runWithSource(
                "Cursor enterprise security docs",
                "Cursor provides SSO and SCIM enterprise security controls for admins."
        );
        AnalysisClaim claim = claim("Cursor provides SSO and SCIM enterprise security controls.", ConfidenceLevel.HIGH);

        binder.pruneUnsupportedClaimEvidence(run, claim);

        assertThat(claim.getEvidenceIds()).containsExactly("S1");
        assertThat(claim.getConfidence()).isEqualTo(ConfidenceLevel.HIGH);
    }

    @Test
    void removesEvidenceAndDowngradesWhenSourceDoesNotSupportClaim() {
        AnalysisRun run = runWithSource(
                "Cursor product page",
                "Cursor Composer supports multi-file code edits."
        );
        AnalysisClaim claim = claim("Cursor provides SSO and SCIM enterprise security controls.", ConfidenceLevel.HIGH);

        binder.pruneUnsupportedClaimEvidence(run, claim);

        assertThat(claim.getEvidenceIds()).isEmpty();
        assertThat(claim.getConfidence()).isEqualTo(ConfidenceLevel.LOW);
    }

    private AnalysisRun runWithSource(String title, String text) {
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "Analyze Cursor",
                "AI coding tools",
                List.of("Cursor"),
                List.of("enterprise security"),
                List.of("official_site"),
                List.of()
        ));
        run.getEvidenceSources().add(new EvidenceSource(
                "S1",
                title,
                "https://example.test/cursor",
                "official_site",
                "FETCHED",
                "LIVE_FETCHED",
                "HIGH",
                "NONE",
                text,
                text,
                "test evidence"
        ));
        return run;
    }

    private AnalysisClaim claim(String content, ConfidenceLevel confidence) {
        AnalysisClaim claim = new AnalysisClaim();
        claim.setType(ClaimType.STRENGTH);
        claim.setContent(content);
        claim.setConfidence(confidence);
        claim.setCompetitorNames(List.of("Cursor"));
        claim.setEvidenceIds(List.of("S1"));
        return claim;
    }
}
