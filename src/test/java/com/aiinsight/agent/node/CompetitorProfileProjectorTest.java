package com.aiinsight.agent.node;

import com.aiinsight.model.enums.FactType;
import com.aiinsight.model.schema.CompetitorFactSet;
import com.aiinsight.model.schema.CompetitorProfile;
import com.aiinsight.model.schema.ExtractedFact;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CompetitorProfileProjectorTest {

    private final CompetitorProfileProjector projector = new CompetitorProfileProjector();

    @Test
    void projectsFactsBackIntoProfileShape() {
        CompetitorProfile original = new CompetitorProfile();
        original.setProductName("Cursor");
        original.setCompanyName("Anysphere");
        CompetitorFactSet factSet = new CompetitorFactSet();
        factSet.setCompetitorName("Cursor");
        factSet.setFacts(List.of(
                fact(FactType.POSITIONING, "positioning", "AI code editor", "S1"),
                fact(FactType.TARGET_USER, "target_user", "Developers", "S1"),
                fact(FactType.FEATURE, "feature", "Composer: multi-file editing", "S2"),
                fact(FactType.PRICING, "pricing_strategy", "Pro plan available", "S3"),
                fact(FactType.PRICING, "pricing_plan", "Pro | $20/month | monthly | Developers | AI edits, Composer", "S3"),
                fact(FactType.CUSTOMER_SIGNAL, "persona", "Builder | Developer teams | SMB | jobs=build,debug | pains=context | concerns=cost", "S4"),
                fact(FactType.FEATURE, "observed_advantage", "Strong editor workflow", "S2")
        ));

        List<CompetitorProfile> profiles = projector.projectProfilesFromFacts(List.of(original), List.of(factSet));

        assertThat(profiles).singleElement().satisfies(profile -> {
            assertThat(profile.getCompanyName()).isEqualTo("Anysphere");
            assertThat(profile.getPositioning()).isEqualTo("AI code editor");
            assertThat(profile.getTargetUsers()).containsExactly("Developers");
            assertThat(profile.getFeatureTree().getRoots())
                    .singleElement()
                    .satisfies(node -> {
                        assertThat(node.getName()).isEqualTo("Composer");
                        assertThat(node.getDescription()).isEqualTo("multi-file editing");
                        assertThat(node.getEvidenceIds()).containsExactly("S2");
                    });
            assertThat(profile.getPricingModel().getStrategySummary()).isEqualTo("Pro plan available");
            assertThat(profile.getPricingModel().getPlans())
                    .singleElement()
                    .satisfies(plan -> {
                        assertThat(plan.getName()).isEqualTo("Pro");
                        assertThat(plan.getPriceText()).isEqualTo("$20/month");
                        assertThat(plan.getIncludedFeatures()).containsExactly("AI edits", "Composer");
                    });
            assertThat(profile.getPersonas())
                    .singleElement()
                    .satisfies(persona -> {
                        assertThat(persona.getName()).isEqualTo("Builder");
                        assertThat(persona.getJobsToBeDone()).containsExactly("build", "debug");
                        assertThat(persona.getPainPoints()).containsExactly("context");
                        assertThat(persona.getBuyingConcerns()).containsExactly("cost");
                    });
            assertThat(profile.getStrengths()).containsExactly("Strong editor workflow");
            assertThat(profile.getEvidenceIds()).containsExactly("S1", "S2", "S3", "S4");
        });
    }

    @Test
    void createsFallbackPersonaWhenFactsHaveEvidenceButNoPersona() {
        CompetitorFactSet factSet = new CompetitorFactSet();
        factSet.setCompetitorName("Cursor");
        factSet.setFacts(List.of(fact(FactType.FEATURE, "feature", "Composer: multi-file editing", "S2")));

        List<CompetitorProfile> profiles = projector.projectProfilesFromFacts(List.of(), List.of(factSet));

        assertThat(profiles).singleElement().satisfies(profile -> {
            assertThat(profile.getCompanyName()).isEqualTo("Cursor");
            assertThat(profile.getPersonas()).singleElement()
                    .satisfies(persona -> assertThat(persona.getEvidenceIds()).containsExactly("S2"));
        });
    }

    private ExtractedFact fact(FactType type, String attribute, String value, String evidenceId) {
        ExtractedFact fact = new ExtractedFact();
        fact.setFactType(type);
        fact.setAttribute(attribute);
        fact.setValue(value);
        fact.setEvidenceIds(List.of(evidenceId));
        return fact;
    }
}
