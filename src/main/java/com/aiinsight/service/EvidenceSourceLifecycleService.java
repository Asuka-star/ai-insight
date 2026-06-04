package com.aiinsight.service;

import com.aiinsight.model.enums.ClaimType;
import com.aiinsight.model.enums.FactType;
import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.model.run.EvidenceSource;
import com.aiinsight.model.schema.AnalysisClaim;
import com.aiinsight.model.schema.CompetitorFactSet;
import com.aiinsight.model.schema.CompetitorProfile;
import com.aiinsight.model.schema.ExtractedFact;
import com.aiinsight.model.schema.FeatureNode;
import com.aiinsight.model.schema.PricingModel;
import com.aiinsight.model.schema.PricingPlan;
import com.aiinsight.model.schema.UserPersona;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class EvidenceSourceLifecycleService {

    public EvidenceReplacementResult reconcileAfterCollection(AnalysisRun run,
                                                              List<EvidenceSource> beforeSources,
                                                              List<EvidenceSource> collectedSources) {
        if (run == null) {
            return new EvidenceReplacementResult(0, 0);
        }
        List<EvidenceSource> sources = collectedSources == null ? new ArrayList<>() : collectedSources;
        retainReferencedSourcesForAudit(run, beforeSources == null ? List.of() : beforeSources, sources);
        Map<String, EvidenceSource> sourcesByKey = sourcesByCitationKey(sources);
        BindingRepairStats stats = new BindingRepairStats();
        repairClaimBindings(run, sourcesByKey, stats);
        repairFactBindings(run, sourcesByKey, stats);
        repairProfileBindings(run, sourcesByKey, stats);
        return new EvidenceReplacementResult(stats.replacedBindings, stats.prunedBindings);
    }

    private void retainReferencedSourcesForAudit(AnalysisRun run, List<EvidenceSource> beforeSources, List<EvidenceSource> currentSources) {
        Set<String> referenced = referencedCitationKeys(run);
        Set<String> currentKeys = currentSources.stream()
                .filter(source -> source != null)
                .map(EvidenceSource::getCitationKey)
                .filter(StringUtils::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        beforeSources.stream()
                .filter(source -> source != null)
                .filter(source -> referenced.contains(source.getCitationKey()))
                .filter(source -> currentKeys.add(source.getCitationKey()))
                .forEach(currentSources::add);
    }

    private void repairClaimBindings(AnalysisRun run,
                                     Map<String, EvidenceSource> sourcesByKey,
                                     BindingRepairStats stats) {
        for (AnalysisClaim claim : safeList(run.getClaims())) {
            if (claim == null || claim.getEvidenceIds().isEmpty()) {
                continue;
            }
            BindingContext context = BindingContext.forClaim(claim);
            BindingRepair repair = repairEvidenceIds(run, claim.getEvidenceIds(), sourcesByKey, context);
            applyRepairToEvidenceIds(claim.getEvidenceIds(), repair, stats);
            pruneChunkKeys(claim.getChunkKeys(), repair.removedCitationKeys(), stats);
        }
    }

    private void repairFactBindings(AnalysisRun run,
                                    Map<String, EvidenceSource> sourcesByKey,
                                    BindingRepairStats stats) {
        for (CompetitorFactSet factSet : safeList(run.getCompetitorFactSets())) {
            if (factSet == null) {
                continue;
            }
            for (ExtractedFact fact : safeList(factSet.getFacts())) {
                if (fact == null || fact.getEvidenceIds().isEmpty()) {
                    continue;
                }
                BindingContext context = BindingContext.forFact(fact);
                BindingRepair repair = repairEvidenceIds(run, fact.getEvidenceIds(), sourcesByKey, context);
                applyRepairToEvidenceIds(fact.getEvidenceIds(), repair, stats);
                pruneChunkKeys(fact.getChunkKeys(), repair.removedCitationKeys(), stats);
            }
        }
    }

    private void repairProfileBindings(AnalysisRun run,
                                       Map<String, EvidenceSource> sourcesByKey,
                                       BindingRepairStats stats) {
        for (CompetitorProfile profile : safeList(run.getCompetitorProfiles())) {
            if (profile == null) {
                continue;
            }
            repairEvidenceList(run, profile.getEvidenceIds(), sourcesByKey, BindingContext.forProfile(profile), stats);
            if (profile.getFeatureTree() != null) {
                for (FeatureNode node : safeList(profile.getFeatureTree().getRoots())) {
                    repairFeatureNodeBindings(run, node, sourcesByKey, stats);
                }
            }
            PricingModel pricingModel = profile.getPricingModel();
            if (pricingModel != null) {
                repairEvidenceList(run, pricingModel.getEvidenceIds(), sourcesByKey, BindingContext.forPricing(profile.getProductName()), stats);
                for (PricingPlan plan : safeList(pricingModel.getPlans())) {
                    repairEvidenceList(run, plan.getEvidenceIds(), sourcesByKey, BindingContext.forPricing(profile.getProductName()), stats);
                }
            }
            for (UserPersona persona : safeList(profile.getPersonas())) {
                if (persona == null) {
                    continue;
                }
                repairEvidenceList(run, persona.getEvidenceIds(), sourcesByKey, BindingContext.forPersona(profile, persona), stats);
            }
        }
    }

    private void repairFeatureNodeBindings(AnalysisRun run,
                                           FeatureNode node,
                                           Map<String, EvidenceSource> sourcesByKey,
                                           BindingRepairStats stats) {
        if (node == null) {
            return;
        }
        repairEvidenceList(run, node.getEvidenceIds(), sourcesByKey, BindingContext.forFeatureNode(node), stats);
        for (FeatureNode child : safeList(node.getChildren())) {
            repairFeatureNodeBindings(run, child, sourcesByKey, stats);
        }
    }

    private void repairEvidenceList(AnalysisRun run,
                                    List<String> evidenceIds,
                                    Map<String, EvidenceSource> sourcesByKey,
                                    BindingContext context,
                                    BindingRepairStats stats) {
        if (evidenceIds == null || evidenceIds.isEmpty()) {
            return;
        }
        BindingRepair repair = repairEvidenceIds(run, evidenceIds, sourcesByKey, context);
        applyRepairToEvidenceIds(evidenceIds, repair, stats);
    }

    private BindingRepair repairEvidenceIds(AnalysisRun run,
                                            List<String> evidenceIds,
                                            Map<String, EvidenceSource> sourcesByKey,
                                            BindingContext context) {
        BindingRepair repair = new BindingRepair();
        for (String citationKey : new ArrayList<>(evidenceIds)) {
            EvidenceSource oldSource = sourcesByKey.get(citationKey);
            if (!isReplaceableWeakSource(oldSource)) {
                continue;
            }
            EvidenceSource replacement = replacementFor(run, oldSource, sourcesByKey.values().stream().toList(), context);
            repair.remove(citationKey);
            if (replacement != null) {
                repair.addReplacement(replacement.getCitationKey());
                appendBindingNote(oldSource, context, replacement);
            } else {
                appendBindingRemovedNote(oldSource, context);
            }
        }
        return repair;
    }

    private void applyRepairToEvidenceIds(List<String> evidenceIds, BindingRepair repair, BindingRepairStats stats) {
        if (repair.removedCitationKeys().isEmpty()) {
            return;
        }
        int before = evidenceIds.size();
        evidenceIds.removeIf(repair.removedCitationKeys()::contains);
        stats.prunedBindings += before - evidenceIds.size();
        for (String replacement : repair.replacementCitationKeys()) {
            if (!evidenceIds.contains(replacement)) {
                evidenceIds.add(replacement);
                stats.replacedBindings++;
            }
        }
    }

    private void pruneChunkKeys(List<String> chunkKeys, Set<String> removedCitationKeys, BindingRepairStats stats) {
        if (chunkKeys == null || chunkKeys.isEmpty() || removedCitationKeys.isEmpty()) {
            return;
        }
        int before = chunkKeys.size();
        chunkKeys.removeIf(chunkKey -> removedCitationKeys.stream().anyMatch(key -> chunkKey.startsWith(key + "-C")));
        stats.prunedBindings += before - chunkKeys.size();
    }

    private EvidenceSource replacementFor(AnalysisRun run,
                                          EvidenceSource oldSource,
                                          List<EvidenceSource> sources,
                                          BindingContext context) {
        return sources.stream()
                .filter(source -> !sameCitation(source, oldSource))
                .filter(source -> isStrongReplacementSource(source, context))
                .filter(source -> overlapsBindingScope(run, oldSource, source, context))
                .findFirst()
                .orElse(null);
    }

    private boolean isReplaceableWeakSource(EvidenceSource source) {
        if (source == null) {
            return false;
        }
        String quality = normalizeUpper(source.getSourceQuality());
        String status = normalizeUpper(source.getCollectionStatus());
        String freshness = normalizeUpper(source.getFreshness());
        String failureReason = normalizeUpper(source.getFailureReason());
        return "LOW".equals(quality)
                || "UNUSABLE".equals(quality)
                || "FETCH_FAILED".equals(status)
                || "BLOCKED_BY_ROBOTS".equals(status)
                || "SEARCH_RESULT_SNIPPET".equals(freshness)
                || "HTTP_4XX".equals(failureReason)
                || "ANTI_BOT_PAGE".equals(failureReason)
                || "THIN_TEXT".equals(failureReason)
                || normalizeLower(source.getComplianceNote()).contains("snippet only");
    }

    private boolean isStrongReplacementSource(EvidenceSource source, BindingContext context) {
        if (source == null) {
            return false;
        }
        String quality = normalizeUpper(source.getSourceQuality());
        String status = normalizeUpper(source.getCollectionStatus());
        return "HIGH".equals(quality)
                && !"FETCH_FAILED".equals(status)
                && !"BLOCKED_BY_ROBOTS".equals(status)
                && compatibleSourceType(source, context)
                && (StringUtils.hasText(source.getRawText()) || StringUtils.hasText(source.getSnippet()));
    }

    private boolean compatibleSourceType(EvidenceSource source, BindingContext context) {
        String sourceType = normalizeLower(source.getSourceType());
        if ("pricing".equals(context.need())) {
            return Set.of("pricing_page", "docs", "product_docs", "official_site").contains(sourceType);
        }
        if ("security".equals(context.need())) {
            return Set.of("security_docs", "docs", "product_docs", "official_site").contains(sourceType);
        }
        if ("user_signal".equals(context.need())) {
            return Set.of("public_review", "public_reviews", "user_survey", "user_interview",
                    "authoritative_media").contains(sourceType);
        }
        return authoritativeSourceType(source);
    }

    private boolean overlapsBindingScope(AnalysisRun run,
                                         EvidenceSource oldSource,
                                         EvidenceSource replacement,
                                         BindingContext context) {
        boolean sameCompetitor = context.competitors().isEmpty()
                ? runCompetitorOverlap(run, oldSource, replacement)
                : context.competitors().stream()
                .anyMatch(competitor -> mentions(replacement, competitor));
        if (!sameCompetitor) {
            return false;
        }
        if (bindingTerms(context).stream().anyMatch(term -> searchable(replacement).contains(term))) {
            return true;
        }
        boolean sameType = normalizeLower(oldSource.getSourceType()).equals(normalizeLower(replacement.getSourceType()));
        return sameType || sharedTerms(oldSource, replacement) >= 2;
    }

    private boolean runCompetitorOverlap(AnalysisRun run, EvidenceSource oldSource, EvidenceSource replacement) {
        List<String> competitors = run.getRequirement() == null ? List.of() : run.getRequirement().getCompetitors();
        return competitors.stream()
                .filter(StringUtils::hasText)
                .anyMatch(competitor -> mentions(oldSource, competitor) && mentions(replacement, competitor));
    }

    private Set<String> bindingTerms(BindingContext context) {
        Set<String> terms = terms(context.text());
        if ("pricing".equals(context.need())) {
            terms.addAll(List.of("pricing", "price", "plan", "billing", "定价", "价格", "套餐"));
        }
        if ("security".equals(context.need())) {
            terms.addAll(List.of("security", "trust", "sso", "scim", "soc", "安全", "权限", "合规"));
        }
        if ("user_signal".equals(context.need())) {
            terms.addAll(List.of("review", "feedback", "customer", "用户", "评价", "反馈", "口碑"));
        }
        return terms.stream()
                .filter(term -> term.length() >= 2)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private int sharedTerms(EvidenceSource first, EvidenceSource second) {
        Set<String> firstTerms = terms(searchable(first));
        Set<String> secondTerms = terms(searchable(second));
        firstTerms.retainAll(secondTerms);
        return firstTerms.size();
    }

    private Set<String> terms(String text) {
        String normalizedText = text(text).replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}\\u4e00-\\u9fa5]+", " ");
        Set<String> terms = new LinkedHashSet<>();
        for (String term : normalizedText.split("\\s+")) {
            String normalized = normalizeLower(term);
            if (normalized.length() >= 3) {
                terms.add(normalized);
            }
        }
        return terms;
    }

    private boolean mentions(EvidenceSource source, String value) {
        return searchable(source).contains(normalizeLower(value));
    }

    private String searchable(EvidenceSource source) {
        return "%s %s %s %s %s".formatted(
                text(source.getTitle()),
                text(source.getUrl()),
                text(source.getSourceType()),
                text(source.getSnippet()),
                text(source.getRawText())
        ).toLowerCase(Locale.ROOT);
    }

    private boolean authoritativeSourceType(EvidenceSource source) {
        return Set.of("official_site", "docs", "product_docs", "pricing_page", "release_notes",
                        "technical_blog", "security_docs", "authoritative_media")
                .contains(normalizeLower(source.getSourceType()));
    }

    private void appendBindingNote(EvidenceSource oldSource, BindingContext context, EvidenceSource replacement) {
        appendNote(oldSource, "已从%s的证据绑定中移除，因为更高质量来源 %s 可支撑该用途；该来源仍保留用于审计和背景参考。"
                .formatted(context.label(), replacement.getCitationKey()));
    }

    private void appendBindingRemovedNote(EvidenceSource oldSource, BindingContext context) {
        appendNote(oldSource, "已从%s的证据绑定中移除，因为该来源质量不足且本轮没有找到可直接替代的高质量来源；该来源仍保留用于审计和背景参考。"
                .formatted(context.label()));
    }

    private void appendNote(EvidenceSource source, String addition) {
        if (source == null || !StringUtils.hasText(addition)) {
            return;
        }
        String note = text(source.getComplianceNote());
        if (note.contains(addition)) {
            return;
        }
        source.setComplianceNote(note.isBlank() ? addition : note + " " + addition);
    }

    private Map<String, EvidenceSource> sourcesByCitationKey(List<EvidenceSource> sources) {
        return sources.stream()
                .filter(source -> source != null)
                .filter(source -> StringUtils.hasText(source.getCitationKey()))
                .collect(Collectors.toMap(
                        EvidenceSource::getCitationKey,
                        source -> source,
                        (first, ignored) -> first,
                        LinkedHashMap::new
                ));
    }

    private Set<String> referencedCitationKeys(AnalysisRun run) {
        Set<String> keys = new LinkedHashSet<>();
        safeList(run.getClaims()).stream()
                .filter(claim -> claim != null)
                .forEach(claim -> keys.addAll(safeList(claim.getEvidenceIds())));
        safeList(run.getCompetitorFactSets()).stream()
                .filter(factSet -> factSet != null)
                .forEach(factSet -> safeList(factSet.getFacts()).stream()
                        .filter(fact -> fact != null)
                        .forEach(fact -> keys.addAll(safeList(fact.getEvidenceIds()))));
        safeList(run.getCompetitorProfiles()).stream()
                .filter(profile -> profile != null)
                .forEach(profile -> {
            keys.addAll(safeList(profile.getEvidenceIds()));
            if (profile.getFeatureTree() != null) {
                safeList(profile.getFeatureTree().getRoots()).forEach(node -> collectFeatureNodeCitationKeys(node, keys));
            }
            PricingModel pricingModel = profile.getPricingModel();
            if (pricingModel != null) {
                keys.addAll(safeList(pricingModel.getEvidenceIds()));
                safeList(pricingModel.getPlans()).stream()
                        .filter(plan -> plan != null)
                        .forEach(plan -> keys.addAll(safeList(plan.getEvidenceIds())));
            }
            safeList(profile.getPersonas()).stream()
                    .filter(persona -> persona != null)
                    .forEach(persona -> keys.addAll(safeList(persona.getEvidenceIds())));
        });
        return keys;
    }

    private void collectFeatureNodeCitationKeys(FeatureNode node, Set<String> keys) {
        if (node == null) {
            return;
        }
        keys.addAll(safeList(node.getEvidenceIds()));
        safeList(node.getChildren()).forEach(child -> collectFeatureNodeCitationKeys(child, keys));
    }

    private boolean sameCitation(EvidenceSource left, EvidenceSource right) {
        return left != null && right != null
                && StringUtils.hasText(left.getCitationKey())
                && left.getCitationKey().equals(right.getCitationKey());
    }

    private static String normalizeLower(String value) {
        return text(value).trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeUpper(String value) {
        return text(value).trim().toUpperCase(Locale.ROOT);
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private static class BindingRepair {
        private final Set<String> removedCitationKeys = new LinkedHashSet<>();
        private final Set<String> replacementCitationKeys = new LinkedHashSet<>();

        void remove(String citationKey) {
            removedCitationKeys.add(citationKey);
        }

        void addReplacement(String citationKey) {
            replacementCitationKeys.add(citationKey);
        }

        Set<String> removedCitationKeys() {
            return removedCitationKeys;
        }

        Set<String> replacementCitationKeys() {
            return replacementCitationKeys;
        }
    }

    private static class BindingRepairStats {
        private int replacedBindings;
        private int prunedBindings;
    }

    private record BindingContext(String label, String text, List<String> competitors, String need) {

        static BindingContext forClaim(AnalysisClaim claim) {
            return new BindingContext(
                    "Claim " + claim.getId(),
                    claim.getContent(),
                    safeList(claim.getCompetitorNames()),
                    needFromClaim(claim)
            );
        }

        static BindingContext forFact(ExtractedFact fact) {
            return new BindingContext(
                    "Fact " + fact.getId(),
                    "%s %s %s".formatted(fact.getFactType(), fact.getAttribute(), fact.getValue()),
                    StringUtils.hasText(fact.getCompetitorName()) ? List.of(fact.getCompetitorName()) : List.of(),
                    needFromFact(fact)
            );
        }

        static BindingContext forProfile(CompetitorProfile profile) {
            return new BindingContext(
                    "竞品画像 " + profile.getProductName(),
                    "%s %s %s %s".formatted(
                            profile.getPositioning(),
                            profile.getTargetUsers(),
                            profile.getStrengths(),
                            profile.getWeaknesses()),
                    StringUtils.hasText(profile.getProductName()) ? List.of(profile.getProductName()) : List.of(),
                    "general"
            );
        }

        static BindingContext forFeatureNode(FeatureNode node) {
            return new BindingContext(
                    "功能节点 " + node.getName(),
                    "%s %s".formatted(node.getName(), node.getDescription()),
                    List.of(),
                    "general"
            );
        }

        static BindingContext forPricing(String productName) {
            return new BindingContext(
                    "定价信息 " + EvidenceSourceLifecycleService.text(productName),
                    "pricing price plan billing 定价 价格 套餐",
                    StringUtils.hasText(productName) ? List.of(productName) : List.of(),
                    "pricing"
            );
        }

        static BindingContext forPersona(CompetitorProfile profile, UserPersona persona) {
            return new BindingContext(
                    "用户画像 " + persona.getName(),
                    "%s %s %s %s %s".formatted(
                            profile.getProductName(),
                            persona.getSegment(),
                            persona.getJobsToBeDone(),
                            persona.getPainPoints(),
                            persona.getBuyingConcerns()),
                    StringUtils.hasText(profile.getProductName()) ? List.of(profile.getProductName()) : List.of(),
                    "user_signal"
            );
        }

        private static String needFromClaim(AnalysisClaim claim) {
            String text = normalizeLower(claim.getContent());
            ClaimType type = claim.getType();
            if (containsAny(text, "pricing", "price", "plan", "subscription", "billing", "定价", "价格", "套餐", "订阅")) {
                return "pricing";
            }
            if (containsAny(text, "security", "permission", "sso", "scim", "soc", "合规", "安全", "权限")) {
                return "security";
            }
            if (type == ClaimType.RISK || containsAny(text, "review", "feedback", "customer", "user", "用户", "评价", "反馈", "口碑")) {
                return "user_signal";
            }
            return "general";
        }

        private static String needFromFact(ExtractedFact fact) {
            if (fact.getFactType() == FactType.PRICING) {
                return "pricing";
            }
            if (fact.getFactType() == FactType.SECURITY) {
                return "security";
            }
            if (fact.getFactType() == FactType.CUSTOMER_SIGNAL) {
                return "user_signal";
            }
            return "general";
        }

        private static boolean containsAny(String text, String... patterns) {
            for (String pattern : patterns) {
                if (text.contains(pattern.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
            return false;
        }
    }

    public record EvidenceReplacementResult(int replacedBindings, int prunedBindings) {
    }
}
