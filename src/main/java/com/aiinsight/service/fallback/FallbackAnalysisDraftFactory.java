package com.aiinsight.service.fallback;

import com.aiinsight.model.enums.AgentName;
import com.aiinsight.model.enums.ClaimType;
import com.aiinsight.model.enums.ConfidenceLevel;
import com.aiinsight.model.enums.FactType;
import com.aiinsight.model.run.AnalysisRequirement;
import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.model.run.EvidenceSource;
import com.aiinsight.model.schema.AnalysisClaim;
import com.aiinsight.model.schema.CompetitorProfile;
import com.aiinsight.model.schema.ExtractedFact;
import com.aiinsight.service.AnalysisDraft;
import org.springframework.stereotype.Component;

import static com.aiinsight.util.AgentUtils.containsAny;
import static com.aiinsight.util.AgentUtils.containsIgnoreCase;
import static com.aiinsight.util.AgentUtils.distinctKnownEvidenceIds;
import static com.aiinsight.util.AgentUtils.hasText;
import static com.aiinsight.util.AgentUtils.nullToEmpty;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class FallbackAnalysisDraftFactory {

    private static final String SUPPORT_STATUS_SUPPORTED = "SUPPORTED";
    private static final String SUPPORT_STATUS_PARTIAL = "PARTIAL";
    private static final String SUPPORT_STATUS_UNVERIFIED = "UNVERIFIED";
    private static final String PLACEMENT_MATRIX = "MATRIX";
    private static final String PLACEMENT_SWOT = "SWOT";
    private static final String PLACEMENT_VALIDATION_BACKLOG = "VALIDATION_BACKLOG";

    public AnalysisDraft build(AnalysisRun run) {
        List<AnalysisClaim> claims = new ArrayList<>();
        for (String dimension : requestedDimensions(run)) {
            AnalysisClaim claim = claimForDimension(run, dimension);
            if (claim != null) {
                claims.add(claim);
            }
        }
        claims.add(opportunityClaim(run));
        if (hasEvidenceGap(run)) {
            claims.add(evidenceGapRiskClaim(run));
        }
        claims = deduplicateClaims(claims);
        return new AnalysisDraft(claims, matrixContent(run, claims), swotContent(run, claims));
    }

    private AnalysisClaim claimForDimension(AnalysisRun run, String dimension) {
        String normalized = normalize(dimension);
        if (containsAny(normalized, "价格", "定价", "pricing", "套餐", "商业模式")) {
            return pricingClaim(run, dimension);
        }
        if (containsAny(normalized, "用户", "评价", "口碑", "访谈", "痛点", "满意")) {
            return userVoiceClaim(run, dimension);
        }
        if (containsAny(normalized, "权限", "治理", "安全", "合规", "审计")) {
            return profileBackedClaim(run, ClaimType.COMPARISON, dimension,
                    "围绕%s，竞品画像显示该维度会影响采用门槛、风险控制或后续运营方式，应按证据强弱分层比较。");
        }
        if (containsAny(normalized, "ai", "智能", "搜索", "生成", "总结")) {
            return profileBackedClaim(run, ClaimType.COMPARISON, dimension,
                    "围绕%s，已有资料可支持对智能能力、触发入口和实际使用场景做初步比较。");
        }
        if (containsAny(normalized, "功能", "协作", "文档", "知识", "流程", "集成")) {
            return profileBackedClaim(run, ClaimType.COMPARISON, dimension,
                    "围绕%s，当前差异应从能力覆盖、使用入口、工作流衔接和证据中的限制条件来判断。");
        }
        return profileBackedClaim(run, ClaimType.FACT, dimension,
                "围绕%s，已有资料可以支持初步比较，但仍需要结合更多业务场景继续验证。");
    }

    private AnalysisClaim profileBackedClaim(AnalysisRun run, ClaimType type, String dimension, String template) {
        List<String> evidenceIds = evidenceIdsForDimension(run, dimension);
        AnalysisClaim claim = baseClaim(run, evidenceIds);
        claim.setType(type);
        claim.setContent(template.formatted(dimension));
        claim.setConfidence(evidenceIds.isEmpty() ? ConfidenceLevel.LOW : ConfidenceLevel.MEDIUM);
        applyClaimMetadata(claim, dimension, PLACEMENT_MATRIX);
        return claim;
    }

    private AnalysisClaim pricingClaim(AnalysisRun run, String dimension) {
        List<String> evidenceIds = pricingFactEvidenceIds(run);
        boolean pricingEvidencePresent = !evidenceIds.isEmpty();
        AnalysisClaim claim = baseClaim(run, evidenceIds);
        claim.setType(pricingEvidencePresent ? ClaimType.COMPARISON : ClaimType.RISK);
        claim.setContent(pricingEvidencePresent
                ? "围绕%s，已抽取到可追溯的价格或套餐事实，可保守比较定价策略；未抽取到的具体金额不作结论。".formatted(dimension)
                : "围绕%s，当前结构化事实层没有可发布的价格或套餐事实，定价和商业模式判断需标注待验证。".formatted(dimension));
        claim.setConfidence(pricingEvidencePresent ? ConfidenceLevel.MEDIUM : ConfidenceLevel.LOW);
        applyClaimMetadata(claim, dimension, pricingEvidencePresent ? PLACEMENT_MATRIX : PLACEMENT_VALIDATION_BACKLOG);
        return claim;
    }

    private List<String> pricingFactEvidenceIds(AnalysisRun run) {
        return run.getCompetitorFactSets().stream()
                .flatMap(factSet -> factSet.getFacts().stream())
                .filter(fact -> fact.getFactType() == FactType.PRICING)
                .filter(fact -> hasText(fact.getValue()))
                .filter(fact -> !looksLikeUnverifiedOrTemplatePricingFact(fact))
                .flatMap(fact -> fact.getEvidenceIds().stream())
                .distinct()
                .limit(6)
                .toList();
    }

    private boolean looksLikeUnverifiedOrTemplatePricingFact(ExtractedFact fact) {
        String normalized = normalize(fact.getValue());
        return containsAny(normalized,
                "待验证",
                "证据不足",
                "unknown",
                "needs verification",
                "公开套餐",
                "定制方案",
                "以价格页为准",
                "以原始页面为准");
    }

    private AnalysisClaim userVoiceClaim(AnalysisRun run, String dimension) {
        List<String> evidenceIds = evidenceIdsForDimension(run, dimension);
        boolean hasInterview = run.getResearchPackage() != null
                && run.getResearchPackage().getInterviewInsights() != null
                && !run.getResearchPackage().getInterviewInsights().isEmpty();
        AnalysisClaim claim = baseClaim(run, evidenceIds);
        claim.setType(hasInterview ? ClaimType.WEAKNESS : ClaimType.RISK);
        claim.setContent(hasInterview
                ? "围绕%s，用户访谈资料已经暴露出痛点、采购顾虑或落地阻力，应作为机会判断的重要输入。".formatted(dimension)
                : "围绕%s，当前用户评价、访谈或问卷证据不足，不能直接推断真实满意度。".formatted(dimension));
        claim.setConfidence(hasInterview ? ConfidenceLevel.MEDIUM : ConfidenceLevel.LOW);
        applyClaimMetadata(claim, dimension, hasInterview ? PLACEMENT_SWOT : PLACEMENT_VALIDATION_BACKLOG);
        return claim;
    }

    private AnalysisClaim opportunityClaim(AnalysisRun run) {
        String goal = hasText(run.getRequirement().getOutputGoal())
                ? run.getRequirement().getOutputGoal()
                : "实际业务决策";
        String dimensionFocus = dimensionFocus(run);
        List<String> evidenceIds = evidenceIdsForDimension(run, dimensionFocus + " " + goal);
        AnalysisClaim claim = baseClaim(run, evidenceIds);
        claim.setType(ClaimType.OPPORTUNITY);
        claim.setContent("面向%s，建议把%s作为当前决策主线：已被证据支持的差异进入建议，证据薄弱的判断进入补证清单。".formatted(goal, dimensionFocus));
        claim.setConfidence(evidenceIds.isEmpty() ? ConfidenceLevel.LOW : ConfidenceLevel.MEDIUM);
        applyClaimMetadata(claim, dimensionFocus, evidenceIds.isEmpty() ? PLACEMENT_VALIDATION_BACKLOG : PLACEMENT_SWOT);
        return claim;
    }

    private AnalysisClaim evidenceGapRiskClaim(AnalysisRun run) {
        List<String> gaps = run.getResearchPackage().getMissingEvidenceTypes();
        AnalysisClaim claim = baseClaim(run, List.of());
        claim.setType(ClaimType.RISK);
        claim.setContent("当前仍存在证据缺口：%s；相关强结论应降级为假设，并在发布前补充公开来源或一手用户资料。".formatted(String.join("、", gaps)));
        claim.setConfidence(ConfidenceLevel.LOW);
        applyClaimMetadata(claim, "证据缺口", PLACEMENT_VALIDATION_BACKLOG);
        return claim;
    }

    private String dimensionFocus(AnalysisRun run) {
        List<String> dimensions = requestedDimensions(run);
        if (dimensions.isEmpty()) {
            return "关键选型维度";
        }
        return dimensions.stream().limit(3).collect(Collectors.joining("、"));
    }

    private AnalysisClaim baseClaim(AnalysisRun run, List<String> evidenceIds) {
        AnalysisClaim claim = new AnalysisClaim();
        claim.setGeneratedBy(AgentName.ANALYST.name());
        claim.setCompetitorNames(run.getRequirement().getCompetitors());
        claim.setEvidenceIds(evidenceIds == null ? List.of() : distinctKnownEvidenceIds(run, evidenceIds));
        return claim;
    }

    private void applyClaimMetadata(AnalysisClaim claim, String dimension, String preferredPlacement) {
        claim.setDimension(hasText(dimension) ? dimension : "综合判断");
        if (claim.getEvidenceIds().isEmpty() || claim.getConfidence() == ConfidenceLevel.LOW) {
            claim.setSupportStatus(SUPPORT_STATUS_UNVERIFIED);
            claim.setRecommendedPlacement(PLACEMENT_VALIDATION_BACKLOG);
            return;
        }
        claim.setSupportStatus(claim.getConfidence() == ConfidenceLevel.HIGH
                ? SUPPORT_STATUS_SUPPORTED
                : SUPPORT_STATUS_PARTIAL);
        claim.setRecommendedPlacement(preferredPlacement);
    }

    private List<AnalysisClaim> deduplicateClaims(List<AnalysisClaim> claims) {
        Set<String> seen = new LinkedHashSet<>();
        List<AnalysisClaim> result = new ArrayList<>();
        for (AnalysisClaim claim : claims) {
            String key = claim.getType() + "::" + claim.getContent();
            if (seen.add(key)) {
                result.add(claim);
            }
        }
        return result;
    }

    private String matrixContent(AnalysisRun run, List<AnalysisClaim> claims) {
        String rows = run.getCompetitorProfiles().stream()
                .map(profile -> "| %s | %s | %s | %s | %s |".formatted(
                        profile.getProductName(),
                        String.join("、", profile.getStrengths()),
                        String.join("、", profile.getWeaknesses()),
                        pricingSummary(profile),
                        citationText(profile.getEvidenceIds())
                ))
                .collect(Collectors.joining("\n"));
        return """
                ## 竞品横向矩阵

                | 竞品 | 主要优势 | 潜在弱势 | 定价判断 | 证据 |
                | --- | --- | --- | --- | --- |
                %s
                """.formatted(rows);
    }

    private String swotContent(AnalysisRun run, List<AnalysisClaim> claims) {
        String strengths = run.getCompetitorProfiles().stream()
                .flatMap(profile -> profile.getStrengths().stream())
                .distinct()
                .limit(3)
                .collect(Collectors.joining("、"));
        String weaknesses = run.getCompetitorProfiles().stream()
                .flatMap(profile -> profile.getWeaknesses().stream())
                .distinct()
                .limit(3)
                .collect(Collectors.joining("、"));
        String opportunities = claims.stream()
                .filter(claim -> claim.getType() == ClaimType.OPPORTUNITY || claim.getType() == ClaimType.RECOMMENDATION)
                .map(AnalysisClaim::getContent)
                .findFirst()
                .orElse("根据用户关注维度继续寻找可验证机会。");
        String risks = claims.stream()
                .filter(claim -> claim.getType() == ClaimType.RISK)
                .map(AnalysisClaim::getContent)
                .findFirst()
                .orElse("若后续证据覆盖不足，结论需要保持待验证。");
        return """
                | 维度 | 结论 | 证据 |
                | --- | --- | --- |
                | Strengths 优势 | %s | %s |
                | Weaknesses 劣势 | %s | %s |
                | Opportunities 机会 | %s | %s |
                | Threats 威胁 | %s | %s |

                注：SWOT 由结构化竞品画像、用户关注维度和证据缺口共同生成；证据不足的项必须在报告中保留“待验证”。
                """.formatted(
                hasText(strengths) ? strengths : "已有资料不足以确认稳定优势",
                citationText(evidenceIdsForClaimType(claims, ClaimType.STRENGTH, ClaimType.COMPARISON)),
                hasText(weaknesses) ? weaknesses : "弱势仍需更多用户评价或访谈资料验证",
                citationText(evidenceIdsForClaimType(claims, ClaimType.WEAKNESS, ClaimType.RISK)),
                opportunities,
                citationText(evidenceIdsForClaimType(claims, ClaimType.OPPORTUNITY, ClaimType.RECOMMENDATION)),
                risks,
                citationText(evidenceIdsForClaimType(claims, ClaimType.RISK))
        );
    }

    private List<String> requestedDimensions(AnalysisRun run) {
        LinkedHashSet<String> dimensions = new LinkedHashSet<>();
        AnalysisRequirement requirement = run.getRequirement();
        if (requirement != null && requirement.getDimensions() != null) {
            requirement.getDimensions().stream().filter(value -> hasText(value)).forEach(dimensions::add);
        }
        String prompt = requirement == null ? "" : nullToEmpty(requirement.getOriginalPrompt());
        if (containsAny(normalize(prompt), "价格", "定价", "套餐", "pricing")) {
            dimensions.add("价格策略");
        }
        if (containsAny(normalize(prompt), "权限", "安全", "合规", "审计")) {
            dimensions.add("权限治理");
        }
        if (containsAny(normalize(prompt), "ai", "智能", "搜索", "生成", "总结")) {
            dimensions.add("AI 能力");
        }
        if (containsAny(normalize(prompt), "用户", "评价", "口碑", "访谈", "问卷")) {
            dimensions.add("用户评价");
        }
        if (dimensions.isEmpty()) {
            dimensions.addAll(List.of("核心功能", "价格策略", "用户评价"));
        }
        return new ArrayList<>(dimensions).stream().limit(6).toList();
    }

    private List<String> evidenceIdsForDimension(AnalysisRun run, String dimension) {
        List<String> keywords = keywordsFor(dimension);
        List<String> matched = run.getEvidenceSources().stream()
                .filter(source -> keywords.stream().anyMatch(keyword -> containsIgnoreCase(sourceText(source), keyword)))
                .map(EvidenceSource::getCitationKey)
                .distinct()
                .toList();
        if (!matched.isEmpty()) {
            return matched;
        }
        return run.getCompetitorProfiles().stream()
                .flatMap(profile -> profile.getEvidenceIds().stream())
                .distinct()
                .limit(3)
                .toList();
    }

    private List<String> keywordsFor(String dimension) {
        LinkedHashSet<String> keywords = new LinkedHashSet<>();
        if (hasText(dimension)) {
            keywords.add(dimension);
        }
        String normalized = normalize(dimension);
        if (containsAny(normalized, "价格", "定价", "pricing", "套餐", "商业模式")) {
            keywords.addAll(List.of("价格", "定价", "pricing", "plan", "套餐", "billing"));
        }
        if (containsAny(normalized, "用户", "评价", "口碑", "访谈", "痛点", "满意")) {
            keywords.addAll(List.of("用户", "评价", "review", "interview", "访谈", "pain", "满意"));
        }
        if (containsAny(normalized, "权限", "治理", "安全", "合规", "审计")) {
            keywords.addAll(List.of("权限", "安全", "security", "permission", "governance", "合规", "审计"));
        }
        if (containsAny(normalized, "ai", "智能", "搜索", "生成", "总结")) {
            keywords.addAll(List.of("ai", "智能", "搜索", "生成", "summary", "summar"));
        }
        if (containsAny(normalized, "功能", "协作", "文档", "知识", "流程", "集成")) {
            keywords.addAll(List.of("功能", "协作", "文档", "知识", "workflow", "integration", "集成"));
        }
        return new ArrayList<>(keywords);
    }

    private List<String> evidenceIdsForClaimType(List<AnalysisClaim> claims, ClaimType... types) {
        Set<ClaimType> accepted = Set.of(types);
        return claims.stream()
                .filter(claim -> accepted.contains(claim.getType()))
                .flatMap(claim -> claim.getEvidenceIds().stream())
                .distinct()
                .toList();
    }

    private boolean hasEvidenceGap(AnalysisRun run) {
        return run.getResearchPackage() != null
                && run.getResearchPackage().getMissingEvidenceTypes() != null
                && !run.getResearchPackage().getMissingEvidenceTypes().isEmpty();
    }

    private String sourceText(EvidenceSource source) {
        return ("%s %s %s %s %s").formatted(
                nullToEmpty(source.getTitle()),
                nullToEmpty(source.getSourceType()),
                nullToEmpty(source.getUrl()),
                nullToEmpty(source.getSnippet()),
                nullToEmpty(source.getRawText())
        );
    }

    private String pricingSummary(CompetitorProfile profile) {
        if (profile.getPricingModel() == null || !hasText(profile.getPricingModel().getStrategySummary())) {
            return "定价证据不足";
        }
        return profile.getPricingModel().getStrategySummary();
    }

    private String citationText(List<String> evidenceIds) {
        if (evidenceIds == null || evidenceIds.isEmpty()) {
            return "证据不足";
        }
        return evidenceIds.stream().map(id -> "[" + id + "]").collect(Collectors.joining(" "));
    }

    private String normalize(String text) {
        return nullToEmpty(text).toLowerCase(Locale.ROOT);
    }
}
