package com.aiinsight.agent.node;

import com.aiinsight.model.enums.FactType;
import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.model.run.EvidenceSource;
import com.aiinsight.model.schema.CompetitorFactSet;
import com.aiinsight.model.schema.CompetitorProfile;
import com.aiinsight.model.schema.ExtractedFact;
import com.aiinsight.model.schema.FeatureNode;
import com.aiinsight.model.schema.PricingModel;
import com.aiinsight.model.schema.PricingPlan;
import com.aiinsight.model.schema.UnknownFact;
import com.aiinsight.model.schema.UserPersona;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static com.aiinsight.util.AgentUtils.containsAny;
import static com.aiinsight.util.AgentUtils.knownEvidenceIds;
import static com.aiinsight.util.AgentUtils.normalizeLower;
import static com.aiinsight.util.AgentUtils.nullToEmpty;
import static com.aiinsight.util.AgentUtils.textOrDash;

final class FactExtractionEngine {

    private static final List<String> STRONG_TEMPLATE_PRICING_MARKERS = List.of(
            "已补充价格页证据",
            "具体金额仍以原始页面为准",
            "当前采集资料不足",
            "定价模型待补充价格页证据"
    );
    private static final List<String> TEMPLATE_PRICING_MARKERS = List.of(
            "公开套餐",
            "定制方案",
            "以价格页为准",
            "以原始页面为准",
            "目标用户或采购主体",
            "已披露能力",
            "适用范围",
            "限制条件",
            "as listed on pricing page",
            "refer to original page",
            "target user or buyer"
    );

    private final ExtractorEvidenceBinder evidenceBinder = new ExtractorEvidenceBinder();

    List<CompetitorFactSet> buildFactSets(List<CompetitorProfile> profiles, AnalysisRun run) {
        AtomicInteger sequence = new AtomicInteger(1);
        return profiles.stream()
                .map(profile -> buildFactSet(profile, run, sequence))
                .toList();
    }

    boolean looksLikeTemplatePricingValue(String value) {
        String normalized = normalizeLower(value);
        if (!StringUtils.hasText(normalized)) {
            return false;
        }
        if (STRONG_TEMPLATE_PRICING_MARKERS.stream()
                .map(this::normalizeTemplateMarker)
                .anyMatch(normalized::contains)) {
            return true;
        }
        long markerHits = TEMPLATE_PRICING_MARKERS.stream()
                .map(this::normalizeTemplateMarker)
                .filter(normalized::contains)
                .count();
        return markerHits >= 2;
    }

    private CompetitorFactSet buildFactSet(CompetitorProfile profile, AnalysisRun run, AtomicInteger sequence) {
        CompetitorFactSet factSet = new CompetitorFactSet();
        factSet.setCompetitorName(profile.getProductName());
        List<ExtractedFact> facts = new ArrayList<>();
        List<UnknownFact> unknowns = new ArrayList<>();

        addFactIfKnown(facts, unknowns, run, sequence, profile.getProductName(), FactType.POSITIONING,
                "positioning", profile.getPositioning(), profile.getEvidenceIds(), List.of("official_site", "product_docs"));
        for (String targetUser : profile.getTargetUsers()) {
            addFactIfKnown(facts, unknowns, run, sequence, profile.getProductName(), FactType.TARGET_USER,
                    "target_user", targetUser, profile.getEvidenceIds(), List.of("public_review", "user_interview", "user_survey"));
        }
        addFeatureFacts(facts, unknowns, run, sequence, profile.getProductName(), profile.getFeatureTree().getRoots());
        addPricingFacts(facts, unknowns, run, sequence, profile.getProductName(), profile.getPricingModel());
        addPersonaFacts(facts, unknowns, run, sequence, profile.getProductName(), profile.getPersonas());
        for (String strength : profile.getStrengths()) {
            addFactIfKnown(facts, unknowns, run, sequence, profile.getProductName(), FactType.FEATURE,
                    "observed_advantage", strength, profile.getEvidenceIds(), List.of("official_site", "product_docs", "public_review"));
        }
        for (String weakness : profile.getWeaknesses()) {
            addFactIfKnown(facts, unknowns, run, sequence, profile.getProductName(), FactType.LIMITATION,
                    "observed_limitation", weakness, profile.getEvidenceIds(), List.of("public_review", "user_interview", "product_docs"));
        }
        factSet.setFacts(facts);
        factSet.setUnknowns(unknowns.stream()
                .filter(unknown -> StringUtils.hasText(unknown.getField()))
                .toList());
        factSet.setSourceCoverageNotes(coverageNotes(profile, facts, unknowns));
        return factSet;
    }

    private void addFeatureFacts(List<ExtractedFact> facts,
                                 List<UnknownFact> unknowns,
                                 AnalysisRun run,
                                 AtomicInteger sequence,
                                 String competitorName,
                                 List<FeatureNode> nodes) {
        for (FeatureNode node : nodes == null ? List.<FeatureNode>of() : nodes) {
            String value = "%s: %s".formatted(nullToEmpty(node.getName()), nullToEmpty(node.getDescription()));
            addFactIfKnown(facts, unknowns, run, sequence, competitorName, factTypeForFeature(value),
                    "feature", value, node.getEvidenceIds(), List.of("official_site", "product_docs"));
            addFeatureFacts(facts, unknowns, run, sequence, competitorName, node.getChildren());
        }
    }

    private void addPricingFacts(List<ExtractedFact> facts,
                                 List<UnknownFact> unknowns,
                                 AnalysisRun run,
                                 AtomicInteger sequence,
                                 String competitorName,
                                 PricingModel pricingModel) {
        if (pricingModel == null) {
            addUnknown(unknowns, competitorName, "pricing", "No pricing model was extracted.", List.of("pricing_page"));
            return;
        }
        List<String> strategyEvidenceIds = evidenceBinder.pricingEvidenceIds(run, pricingModel.getEvidenceIds());
        addFactIfKnown(facts, unknowns, run, sequence, competitorName, FactType.PRICING,
                "pricing_strategy", pricingModel.getStrategySummary(), strategyEvidenceIds, List.of("pricing_page"));
        for (PricingPlan plan : pricingModel.getPlans() == null ? List.<PricingPlan>of() : pricingModel.getPlans()) {
            if (plan == null) {
                continue;
            }
            String value = "%s | %s | %s | %s | %s".formatted(
                    nullToEmpty(plan.getName()),
                    nullToEmpty(plan.getPriceText()),
                    nullToEmpty(plan.getBillingCycle()),
                    nullToEmpty(plan.getTargetSegment()),
                    plan.getIncludedFeatures() == null ? "" : String.join(", ", plan.getIncludedFeatures())
            );
            List<String> planEvidenceIds = evidenceBinder.pricingEvidenceIds(run, plan.getEvidenceIds());
            addFactIfKnown(facts, unknowns, run, sequence, competitorName, FactType.PRICING,
                    "pricing_plan", value, planEvidenceIds, List.of("pricing_page"));
        }
    }

    private void addPersonaFacts(List<ExtractedFact> facts,
                                 List<UnknownFact> unknowns,
                                 AnalysisRun run,
                                 AtomicInteger sequence,
                                 String competitorName,
                                 List<UserPersona> personas) {
        for (UserPersona persona : personas == null ? List.<UserPersona>of() : personas) {
            String value = "%s | %s | %s | jobs=%s | pains=%s | concerns=%s".formatted(
                    nullToEmpty(persona.getName()),
                    nullToEmpty(persona.getSegment()),
                    nullToEmpty(persona.getCompanySize()),
                    persona.getJobsToBeDone(),
                    persona.getPainPoints(),
                    persona.getBuyingConcerns()
            );
            addFactIfKnown(facts, unknowns, run, sequence, competitorName, FactType.CUSTOMER_SIGNAL,
                    "persona", value, persona.getEvidenceIds(), List.of("user_interview", "user_survey", "public_review"));
        }
    }

    private void addFactIfKnown(List<ExtractedFact> facts,
                                List<UnknownFact> unknowns,
                                AnalysisRun run,
                                AtomicInteger sequence,
                                String competitorName,
                                FactType factType,
                                String attribute,
                                String value,
                                List<String> evidenceIds,
                                List<String> neededEvidenceTypes) {
        if (!StringUtils.hasText(value) || isUnknownValue(value)) {
            addUnknown(unknowns, competitorName, attribute, "Extractor did not find explicit evidence for this field.", neededEvidenceTypes);
            return;
        }
        if (factType == FactType.PRICING && looksLikeTemplatePricingValue(value)) {
            addUnknown(unknowns, competitorName, attribute, "Extractor produced fallback/template pricing text instead of an evidence-backed value.", neededEvidenceTypes);
            return;
        }
        List<String> knownIds = evidenceBinder.supportedEvidenceIdsForFact(
                run,
                knownEvidenceIds(run, evidenceIds, List.of()),
                competitorName,
                factType,
                attribute,
                value
        );
        if (knownIds.isEmpty() && conservativeFallbackPositioning(factType, attribute, value)) {
            knownIds = knownEvidenceIds(run, evidenceIds, List.of()).stream()
                    .filter(StringUtils::hasText)
                    .distinct()
                    .limit(3)
                    .toList();
        }
        if (knownIds.isEmpty()) {
            addUnknown(unknowns, competitorName, attribute, "Extracted value has no directly supporting evidence id.", neededEvidenceTypes);
            return;
        }
        List<String> chunkKeys = evidenceBinder.chunkKeysForEvidence(run, knownIds, factType == null ? FactType.UNKNOWN : factType, value);
        if (evidenceBinder.highRiskFact(factType, attribute, value) && !run.getEvidenceChunks().isEmpty() && chunkKeys.isEmpty()) {
            addUnknown(unknowns, competitorName, attribute, "High-risk extracted value needs a directly supporting evidence chunk.", neededEvidenceTypes);
            return;
        }
        if (evidenceBinder.highRiskFact(factType, attribute, value) && knownIds.stream()
                .noneMatch(id -> evidenceBinder.riskCompatibleEvidence(run, id, factType, attribute, value))) {
            addUnknown(unknowns, competitorName, attribute, "High-risk extracted value is bound to evidence with an incompatible source type.", neededEvidenceTypes);
            return;
        }
        ExtractedFact fact = new ExtractedFact();
        fact.setId("F" + sequence.getAndIncrement());
        fact.setCompetitorName(competitorName);
        fact.setFactType(factType == null ? FactType.UNKNOWN : factType);
        fact.setAttribute(attribute);
        fact.setValue(value.trim());
        fact.setEvidenceIds(knownIds);
        fact.setChunkKeys(chunkKeys);
        fact.setSupportQuote(evidenceBinder.supportQuoteForChunks(run, chunkKeys));
        fact.setSupportStrength(evidenceBinder.supportStrengthForFact(fact.getFactType(), fact.getChunkKeys()));
        fact.setRiskLevel(evidenceBinder.riskLevelForFact(fact.getFactType(), attribute, value));
        EvidenceSource primarySource = evidenceBinder.primarySource(run, knownIds);
        fact.setSourceAuthority(primarySource == null ? "UNKNOWN" : textOrDash(primarySource.getSourceAuthority()));
        fact.setSourceQuality(primarySource == null ? "UNKNOWN" : textOrDash(primarySource.getSourceQuality()));
        fact.setExtractionConfidence(evidenceBinder.extractionConfidence(primarySource, fact.getChunkKeys()));
        facts.add(fact);
    }

    private boolean conservativeFallbackPositioning(FactType factType, String attribute, String value) {
        return factType == FactType.POSITIONING
                && "positioning".equals(attribute)
                && containsAny(normalizeLower(value), "需结合证据继续验证", "继续验证", "待验证", "needs verification");
    }

    private String normalizeTemplateMarker(String marker) {
        return normalizeLower(marker);
    }

    private void addUnknown(List<UnknownFact> unknowns,
                            String competitorName,
                            String field,
                            String reason,
                            List<String> neededEvidenceTypes) {
        UnknownFact unknown = new UnknownFact();
        unknown.setCompetitorName(competitorName);
        unknown.setField(field);
        unknown.setReason(reason);
        unknown.setNeededEvidenceTypes(neededEvidenceTypes == null ? List.of() : neededEvidenceTypes);
        unknowns.add(unknown);
    }

    private FactType factTypeForFeature(String value) {
        String normalized = normalizeLower(value);
        if (containsAny(normalized, "ai", "assistant", "copilot", "search", "智能", "生成式", "搜索")) {
            return FactType.AI_CAPABILITY;
        }
        if (containsAny(normalized, "permission", "admin", "role", "rbac", "saml", "sso", "scim", "权限", "管理员", "角色")) {
            return FactType.PERMISSION;
        }
        if (containsAny(normalized, "security", "compliance", "privacy", "安全", "合规", "隐私")) {
            return FactType.SECURITY;
        }
        if (containsAny(normalized, "integration", "api", "webhook", "集成", "接口")) {
            return FactType.INTEGRATION;
        }
        return FactType.FEATURE;
    }

    private boolean isUnknownValue(String value) {
        String normalized = normalizeLower(value);
        return containsAny(normalized,
                "待验证", "证据不足", "unknown", "not verified", "needs verification",
                "unverified", "tbd", "n/a");
    }

    private List<String> coverageNotes(CompetitorProfile profile, List<ExtractedFact> facts, List<UnknownFact> unknowns) {
        List<String> notes = new ArrayList<>();
        notes.add("%s facts extracted from %s valid evidence-bound fields.".formatted(facts.size(), profile.getProductName()));
        if (!unknowns.isEmpty()) {
            notes.add("%s fields remain unknown and should drive targeted recollection.".formatted(unknowns.size()));
        }
        return notes;
    }
}
