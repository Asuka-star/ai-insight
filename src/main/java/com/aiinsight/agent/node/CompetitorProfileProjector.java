package com.aiinsight.agent.node;

import com.aiinsight.model.enums.FactType;
import com.aiinsight.model.schema.CompetitorFactSet;
import com.aiinsight.model.schema.CompetitorProfile;
import com.aiinsight.model.schema.ExtractedFact;
import com.aiinsight.model.schema.FeatureNode;
import com.aiinsight.model.schema.FeatureTree;
import com.aiinsight.model.schema.PricingModel;
import com.aiinsight.model.schema.PricingPlan;
import com.aiinsight.model.schema.UserPersona;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.aiinsight.util.AgentUtils.containsAny;
import static com.aiinsight.util.AgentUtils.normalizeLower;
import static com.aiinsight.util.AgentUtils.nullToEmpty;
import static com.aiinsight.util.AgentUtils.textOrDefault;

final class CompetitorProfileProjector {

    List<CompetitorProfile> projectProfilesFromFacts(List<CompetitorProfile> originalProfiles,
                                                     List<CompetitorFactSet> factSets) {
        Map<String, CompetitorProfile> originalsByName = (originalProfiles == null ? List.<CompetitorProfile>of() : originalProfiles).stream()
                .filter(profile -> profile != null && StringUtils.hasText(profile.getProductName()))
                .collect(Collectors.toMap(
                        profile -> normalizeLower(profile.getProductName()),
                        profile -> profile,
                        (first, ignored) -> first
                ));
        return (factSets == null ? List.<CompetitorFactSet>of() : factSets).stream()
                .filter(factSet -> factSet != null && StringUtils.hasText(factSet.getCompetitorName()))
                .map(factSet -> projectProfileFromFacts(factSet, originalsByName.get(normalizeLower(factSet.getCompetitorName()))))
                .toList();
    }

    private CompetitorProfile projectProfileFromFacts(CompetitorFactSet factSet, CompetitorProfile original) {
        CompetitorProfile profile = new CompetitorProfile();
        profile.setProductName(factSet.getCompetitorName());
        profile.setCompanyName(original == null ? factSet.getCompetitorName() : textOrDefault(original.getCompanyName(), factSet.getCompetitorName()));
        List<ExtractedFact> facts = factSet.getFacts() == null ? List.of() : factSet.getFacts();
        profile.setEvidenceIds(allFactEvidenceIds(facts));
        profile.setPositioning(firstFactValue(facts, "positioning", "待验证"));
        profile.setTargetUsers(valuesForAttribute(facts, "target_user"));
        profile.setFeatureTree(projectFeatureTree(facts));
        profile.setPricingModel(projectPricingModel(facts));
        profile.setPersonas(projectPersonas(facts, profile.getEvidenceIds()));
        profile.setStrengths(valuesForAttribute(facts, "observed_advantage"));
        profile.setWeaknesses(valuesForAttribute(facts, "observed_limitation"));
        return profile;
    }

    private FeatureTree projectFeatureTree(List<ExtractedFact> facts) {
        FeatureTree tree = new FeatureTree();
        List<FeatureNode> roots = facts.stream()
                .filter(fact -> fact != null && "feature".equals(fact.getAttribute()))
                .map(this::projectFeatureNode)
                .filter(node -> StringUtils.hasText(node.getName()))
                .toList();
        tree.setRoots(roots);
        return tree;
    }

    private FeatureNode projectFeatureNode(ExtractedFact fact) {
        String value = nullToEmpty(fact.getValue());
        String[] parts = value.split(":", 2);
        String name = parts.length > 0 ? textOrDefault(parts[0], "未命名功能") : "未命名功能";
        String description = parts.length > 1 ? textOrDefault(parts[1], value) : value;
        return new FeatureNode(name, description, fact.getEvidenceIds() == null ? List.of() : fact.getEvidenceIds());
    }

    private PricingModel projectPricingModel(List<ExtractedFact> facts) {
        PricingModel model = new PricingModel();
        List<ExtractedFact> pricingFacts = facts.stream()
                .filter(fact -> fact != null && fact.getFactType() == FactType.PRICING)
                .toList();
        model.setStrategySummary(firstFactValue(pricingFacts, "pricing_strategy", "待验证"));
        List<String> pricingEvidenceIds = allFactEvidenceIds(pricingFacts);
        model.setEvidenceIds(pricingEvidenceIds);
        List<PricingPlan> plans = pricingFacts.stream()
                .filter(fact -> "pricing_plan".equals(fact.getAttribute()))
                .map(this::projectPricingPlan)
                .toList();
        model.setPlans(plans);
        model.setHasFreePlan(plans.stream()
                .anyMatch(plan -> containsAny(normalizeLower(plan.getName() + " " + plan.getPriceText()), "free", "$0", "免费", "0元")));
        return model;
    }

    private PricingPlan projectPricingPlan(ExtractedFact fact) {
        String[] parts = nullToEmpty(fact.getValue()).split("\\|", -1);
        return new PricingPlan(
                partOrDefault(parts, 0, "未命名套餐"),
                partOrDefault(parts, 1, "待验证"),
                partOrDefault(parts, 2, "unknown"),
                partOrDefault(parts, 3, "待验证"),
                splitCsv(partOrDefault(parts, 4, "")),
                fact.getEvidenceIds() == null ? List.of() : fact.getEvidenceIds()
        );
    }

    private List<UserPersona> projectPersonas(List<ExtractedFact> facts, List<String> fallbackEvidenceIds) {
        List<UserPersona> personas = facts.stream()
                .filter(fact -> fact != null && "persona".equals(fact.getAttribute()))
                .map(this::projectPersona)
                .toList();
        if (!personas.isEmpty() || fallbackEvidenceIds == null || fallbackEvidenceIds.isEmpty()) {
            return personas;
        }
        UserPersona unknownPersona = new UserPersona();
        unknownPersona.setName("典型使用或评估者待验证");
        unknownPersona.setSegment("待验证");
        unknownPersona.setCompanySize("需按目标场景继续确认");
        unknownPersona.setJobsToBeDone(List.of("按用户关注维度继续验证"));
        unknownPersona.setPainPoints(List.of("实际使用阻力待验证"));
        unknownPersona.setBuyingConcerns(List.of("采用成本、学习成本或商业条款待验证"));
        unknownPersona.setEvidenceIds(fallbackEvidenceIds);
        return List.of(unknownPersona);
    }

    private UserPersona projectPersona(ExtractedFact fact) {
        String[] parts = nullToEmpty(fact.getValue()).split("\\|", -1);
        UserPersona persona = new UserPersona();
        persona.setName(partOrDefault(parts, 0, "典型用户"));
        persona.setSegment(partOrDefault(parts, 1, "待验证"));
        persona.setCompanySize(partOrDefault(parts, 2, "待验证"));
        persona.setJobsToBeDone(extractTaggedList(parts, "jobs="));
        persona.setPainPoints(extractTaggedList(parts, "pains="));
        persona.setBuyingConcerns(extractTaggedList(parts, "concerns="));
        persona.setEvidenceIds(fact.getEvidenceIds() == null ? List.of() : fact.getEvidenceIds());
        return persona;
    }

    private List<String> allFactEvidenceIds(List<ExtractedFact> facts) {
        return facts.stream()
                .filter(fact -> fact != null && fact.getEvidenceIds() != null)
                .flatMap(fact -> fact.getEvidenceIds().stream())
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
    }

    private List<String> valuesForAttribute(List<ExtractedFact> facts, String attribute) {
        return facts.stream()
                .filter(fact -> fact != null && attribute.equals(fact.getAttribute()))
                .map(ExtractedFact::getValue)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
    }

    private String firstFactValue(List<ExtractedFact> facts, String attribute, String fallback) {
        return facts.stream()
                .filter(fact -> fact != null && attribute.equals(fact.getAttribute()))
                .map(ExtractedFact::getValue)
                .filter(StringUtils::hasText)
                .findFirst()
                .orElse(fallback);
    }

    private String partOrDefault(String[] parts, int index, String fallback) {
        if (parts == null || index >= parts.length) {
            return fallback;
        }
        return textOrDefault(parts[index], fallback);
    }

    private List<String> splitCsv(String value) {
        if (!StringUtils.hasText(value)) {
            return List.of();
        }
        return List.of(value.split(",")).stream()
                .map(String::trim)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
    }

    private List<String> extractTaggedList(String[] parts, String tag) {
        for (String part : parts == null ? new String[0] : parts) {
            String trimmed = part.trim();
            if (trimmed.startsWith(tag)) {
                return splitCsv(trimmed.substring(tag.length())
                        .replace("[", "")
                        .replace("]", ""));
            }
        }
        return List.of("待验证");
    }
}
