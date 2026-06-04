package com.aiinsight.service;

import com.aiinsight.model.enums.ClaimType;
import com.aiinsight.model.enums.FactType;
import com.aiinsight.model.run.AnalysisRequirement;
import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.model.run.EvidenceChunk;
import com.aiinsight.model.run.EvidenceSource;
import com.aiinsight.model.schema.AnalysisClaim;
import com.aiinsight.model.schema.CompetitorFactSet;
import com.aiinsight.model.schema.CompetitorProfile;
import com.aiinsight.model.schema.ExtractedFact;
import com.aiinsight.model.schema.FeatureNode;
import com.aiinsight.model.schema.PricingPlan;
import com.aiinsight.util.AgentUtils;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceSourceLifecycleServiceTest {

    private final EvidenceSourceLifecycleService service = new EvidenceSourceLifecycleService();

    @Test
    void replacesWeakEvidenceBindingsWithoutDisablingSource() {
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "Analyze Cursor",
                "AI coding tools",
                List.of("Cursor"),
                List.of("pricing"),
                List.of(),
                List.of()
        ));
        EvidenceSource weak = source(
                "S1",
                "Cursor pricing summary blog",
                "https://blog.example.test/cursor-pricing",
                "article",
                "LOW",
                "FETCHED",
                "Cursor pricing summary from a thin third-party blog.",
                "Cursor pricing summary"
        );
        EvidenceSource strong = source(
                "S2",
                "Cursor pricing docs",
                "https://cursor.com/pricing",
                "pricing_page",
                "HIGH",
                "FETCHED",
                "Cursor official pricing page with plans and billing.",
                "Cursor official pricing page"
        );
        run.getEvidenceSources().add(weak);
        AnalysisClaim claim = new AnalysisClaim();
        claim.setType(ClaimType.COMPARISON);
        claim.setContent("Cursor has transparent pricing.");
        claim.getEvidenceIds().add("S1");
        claim.getChunkKeys().add("S1-C1");
        run.getClaims().add(claim);
        ExtractedFact fact = new ExtractedFact();
        fact.setId("F1");
        fact.setFactType(FactType.PRICING);
        fact.setValue("Cursor pricing plan");
        fact.getEvidenceIds().add("S1");
        fact.getChunkKeys().add("S1-C1");
        CompetitorFactSet factSet = new CompetitorFactSet();
        factSet.setCompetitorName("Cursor");
        factSet.getFacts().add(fact);
        run.getCompetitorFactSets().add(factSet);
        CompetitorProfile profile = new CompetitorProfile();
        profile.setProductName("Cursor");
        profile.getEvidenceIds().add("S1");
        profile.getFeatureTree().getRoots().add(new FeatureNode(
                "AI coding",
                "Cursor AI coding feature",
                List.of("S1")
        ));
        profile.getPricingModel().getEvidenceIds().add("S1");
        profile.getPricingModel().getPlans().add(new PricingPlan(
                "Pro",
                "$20/month",
                "monthly",
                "developer",
                List.of("AI coding"),
                List.of("S1")
        ));
        run.getCompetitorProfiles().add(profile);

        List<EvidenceSource> collected = new java.util.ArrayList<>(List.of(strong));
        EvidenceSourceLifecycleService.EvidenceReplacementResult result = service.reconcileAfterCollection(
                run,
                List.of(weak),
                collected
        );
        run.getEvidenceSources().clear();
        run.getEvidenceSources().addAll(collected);

        assertThat(result.replacedBindings()).isGreaterThanOrEqualTo(6);
        assertThat(result.prunedBindings()).isGreaterThanOrEqualTo(6);
        assertThat(collected).extracting(EvidenceSource::getCitationKey).containsExactly("S2", "S1");
        assertThat(weak.getCollectionStatus()).isEqualTo("FETCHED");
        assertThat(weak.getComplianceNote()).contains("已从Claim");
        assertThat(weak.getComplianceNote()).contains("更高质量来源 S2");
        assertThat(claim.getEvidenceIds()).containsExactly("S2");
        assertThat(claim.getChunkKeys()).isEmpty();
        assertThat(fact.getEvidenceIds()).containsExactly("S2");
        assertThat(fact.getChunkKeys()).isEmpty();
        assertThat(profile.getEvidenceIds()).containsExactly("S2");
        assertThat(profile.getFeatureTree().getRoots().get(0).getEvidenceIds()).containsExactly("S2");
        assertThat(profile.getPricingModel().getEvidenceIds()).containsExactly("S2");
        assertThat(profile.getPricingModel().getPlans().get(0).getEvidenceIds()).containsExactly("S2");
        assertThat(AgentUtils.knownCitationKeys(run)).containsExactly("S2", "S1");

        List<EvidenceChunk> chunks = new EvidenceChunkService().chunk(run.getEvidenceSources());

        assertThat(chunks).extracting(EvidenceChunk::getSourceCitationKey).contains("S1", "S2");
    }

    private EvidenceSource source(String citationKey,
                                  String title,
                                  String url,
                                  String sourceType,
                                  String quality,
                                  String status,
                                  String rawText,
                                  String snippet) {
        return new EvidenceSource(
                citationKey,
                title,
                url,
                sourceType,
                status,
                "FRESH",
                quality,
                "NONE",
                snippet,
                rawText,
                ""
        );
    }
}
