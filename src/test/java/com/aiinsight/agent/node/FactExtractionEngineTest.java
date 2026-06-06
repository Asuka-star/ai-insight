package com.aiinsight.agent.node;

import com.aiinsight.model.enums.FactType;
import com.aiinsight.model.run.AnalysisRequirement;
import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.model.run.EvidenceSource;
import com.aiinsight.model.schema.CompetitorFactSet;
import com.aiinsight.model.schema.CompetitorProfile;
import com.aiinsight.model.schema.FeatureNode;
import com.aiinsight.model.schema.FeatureTree;
import com.aiinsight.model.schema.PricingModel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FactExtractionEngineTest {

    private final FactExtractionEngine engine = new FactExtractionEngine();

    @Test
    void buildsEvidenceBoundFeatureFactsFromProfile() {
        AnalysisRun run = runWithSource(
                "S1",
                "official_site",
                "Cursor Composer supports multi-file editing for developers."
        );
        CompetitorProfile profile = profile();
        FeatureTree tree = new FeatureTree();
        tree.setRoots(List.of(new FeatureNode("Composer", "multi-file editing", List.of("S1"))));
        profile.setFeatureTree(tree);

        List<CompetitorFactSet> factSets = engine.buildFactSets(List.of(profile), run);

        assertThat(factSets).singleElement().satisfies(factSet -> {
            assertThat(factSet.getFacts())
                    .anySatisfy(fact -> {
                        assertThat(fact.getFactType()).isEqualTo(FactType.FEATURE);
                        assertThat(fact.getAttribute()).isEqualTo("feature");
                        assertThat(fact.getEvidenceIds()).containsExactly("S1");
                    });
            assertThat(factSet.getSourceCoverageNotes()).first().asString().contains("facts extracted");
        });
    }

    @Test
    void movesTemplatePricingTextToUnknowns() {
        AnalysisRun run = runWithSource(
                "S1",
                "pricing_page",
                "Cursor pricing page lists product plans and enterprise billing."
        );
        CompetitorProfile profile = profile();
        PricingModel pricing = new PricingModel();
        pricing.setStrategySummary("公开套餐，以价格页为准，目标用户或采购主体待确认");
        pricing.setEvidenceIds(List.of("S1"));
        profile.setPricingModel(pricing);

        List<CompetitorFactSet> factSets = engine.buildFactSets(List.of(profile), run);

        assertThat(factSets).singleElement().satisfies(factSet -> {
            assertThat(factSet.getFacts())
                    .noneMatch(fact -> fact.getFactType() == FactType.PRICING);
            assertThat(factSet.getUnknowns())
                    .anySatisfy(unknown -> {
                        assertThat(unknown.getField()).isEqualTo("pricing_strategy");
                        assertThat(unknown.getReason()).contains("fallback/template pricing");
                    });
        });
    }

    private AnalysisRun runWithSource(String citationKey, String sourceType, String text) {
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "Analyze Cursor",
                "AI coding tools",
                List.of("Cursor"),
                List.of("features", "pricing"),
                List.of("official_site"),
                List.of()
        ));
        run.getEvidenceSources().add(new EvidenceSource(
                citationKey,
                "Cursor evidence",
                "https://example.test/" + citationKey.toLowerCase(),
                sourceType,
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

    private CompetitorProfile profile() {
        CompetitorProfile profile = new CompetitorProfile();
        profile.setProductName("Cursor");
        profile.setCompanyName("Cursor");
        profile.setPositioning("待验证");
        profile.setEvidenceIds(List.of("S1"));
        return profile;
    }
}
