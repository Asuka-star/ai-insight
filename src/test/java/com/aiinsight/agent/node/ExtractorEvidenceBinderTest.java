package com.aiinsight.agent.node;

import com.aiinsight.model.enums.FactType;
import com.aiinsight.model.run.AnalysisRequirement;
import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.model.run.EvidenceChunk;
import com.aiinsight.model.run.EvidenceSource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExtractorEvidenceBinderTest {

    private final ExtractorEvidenceBinder binder = new ExtractorEvidenceBinder();

    @Test
    void pricingFactsOnlyBindPricingEvidence() {
        AnalysisRun run = run();
        run.getEvidenceSources().add(source(
                "S1",
                "Cursor pricing",
                "pricing_page",
                "Cursor Pro costs $20 per month with annual billing options."
        ));
        run.getEvidenceSources().add(source(
                "S2",
                "Cursor product docs",
                "official_site",
                "Cursor Composer supports multi-file code edits."
        ));

        List<String> evidenceIds = binder.supportedEvidenceIdsForFact(
                run,
                List.of("S1", "S2"),
                "Cursor",
                FactType.PRICING,
                "pricing_strategy",
                "Cursor Pro costs $20 per month"
        );

        assertThat(evidenceIds).containsExactly("S1");
        assertThat(binder.pricingEvidenceIds(run, List.of("S1", "S2"))).containsExactly("S1");
    }

    @Test
    void highRiskPermissionFactsRequireCompatibleSupportingChunk() {
        AnalysisRun run = run();
        run.getEvidenceSources().add(source(
                "S1",
                "Cursor security docs",
                "docs",
                "Cursor enterprise security includes SSO and SAML controls."
        ));
        EvidenceChunk chunk = new EvidenceChunk(
                "S1-C1",
                "S1",
                1,
                "Cursor security docs",
                "https://example.test/cursor/security",
                "Cursor enterprise security includes SSO and SAML controls for admins."
        );
        chunk.setContentKind("security");
        chunk.setSourceType("docs");
        run.getEvidenceChunks().add(chunk);

        List<String> chunkKeys = binder.chunkKeysForEvidence(
                run,
                List.of("S1"),
                FactType.PERMISSION,
                "SSO and SAML controls"
        );

        assertThat(binder.highRiskFact(FactType.PERMISSION, "permission", "SSO and SAML controls")).isTrue();
        assertThat(chunkKeys).containsExactly("S1-C1");
        assertThat(binder.supportStrengthForFact(FactType.PERMISSION, chunkKeys)).isEqualTo("DIRECT");
    }

    private AnalysisRun run() {
        return new AnalysisRun(new AnalysisRequirement(
                "Analyze Cursor",
                "AI coding tools",
                List.of("Cursor"),
                List.of("pricing", "security"),
                List.of("official_site"),
                List.of()
        ));
    }

    private EvidenceSource source(String citationKey, String title, String sourceType, String text) {
        return new EvidenceSource(
                citationKey,
                title,
                "https://example.test/" + citationKey.toLowerCase(),
                sourceType,
                "FETCHED",
                "LIVE_FETCHED",
                "HIGH",
                "NONE",
                text,
                text,
                "test evidence"
        );
    }
}
