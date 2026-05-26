package com.aiinsight.service.fallback;

import com.aiinsight.model.enums.AgentName;
import com.aiinsight.model.enums.ClaimType;
import com.aiinsight.model.enums.ConfidenceLevel;
import com.aiinsight.model.run.AnalysisRequirement;
import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.model.run.EvidenceSource;
import com.aiinsight.model.schema.AnalysisClaim;
import com.aiinsight.model.schema.CompetitorProfile;
import com.aiinsight.service.AnalysisDraft;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class FallbackAnalysisDraftFactory {

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
                    "围绕%s，竞品画像显示权限治理、安全合规或团队管理能力是企业采用时的关键比较项。");
        }
        if (containsAny(normalized, "ai", "智能", "搜索", "生成", "总结")) {
            return profileBackedClaim(run, ClaimType.COMPARISON, dimension,
                    "围绕%s，竞品普遍在 AI 生成、搜索或知识处理能力上形成竞争焦点。");
        }
        if (containsAny(normalized, "功能", "协作", "文档", "知识", "流程", "集成")) {
            return profileBackedClaim(run, ClaimType.COMPARISON, dimension,
                    "围绕%s，当前差异主要体现在协作流程、知识沉淀和团队落地路径上。");
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
        return claim;
    }

    private AnalysisClaim pricingClaim(AnalysisRun run, String dimension) {
        List<String> evidenceIds = evidenceIdsForDimension(run, dimension);
        boolean pricingEvidencePresent = run.getCompetitorProfiles().stream()
                .anyMatch(profile -> profile.getPricingModel() != null
                        && !profile.getPricingModel().getEvidenceIds().isEmpty()
                        && hasText(profile.getPricingModel().getStrategySummary())
                        && !profile.getPricingModel().getStrategySummary().contains("待补充"));
        AnalysisClaim claim = baseClaim(run, evidenceIds);
        claim.setType(pricingEvidencePresent ? ClaimType.COMPARISON : ClaimType.RISK);
        claim.setContent(pricingEvidencePresent
                ? "围绕%s，已具备价格页或套餐证据，可初步比较定价策略；具体金额仍应回到原始页面核验。".formatted(dimension)
                : "围绕%s，当前价格页或套餐证据不足，定价和商业模式判断需要标注待验证。".formatted(dimension));
        claim.setConfidence(pricingEvidencePresent ? ConfidenceLevel.MEDIUM : ConfidenceLevel.LOW);
        return claim;
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
        return claim;
    }

    private AnalysisClaim opportunityClaim(AnalysisRun run) {
        String goal = hasText(run.getRequirement().getOutputGoal())
                ? run.getRequirement().getOutputGoal()
                : "实际业务决策";
        List<String> evidenceIds = evidenceIdsForDimension(run, "可溯源 复核 重跑 证据 用户需求 " + goal);
        AnalysisClaim claim = baseClaim(run, evidenceIds);
        claim.setType(ClaimType.OPPORTUNITY);
        claim.setContent("面向%s，机会点应从用户关注维度和证据缺口出发，把可溯源结论、Reviewer 复核和可重跑流程做成稳定能力，而不是只输出一次性报告。".formatted(goal));
        claim.setConfidence(evidenceIds.isEmpty() ? ConfidenceLevel.LOW : ConfidenceLevel.MEDIUM);
        return claim;
    }

    private AnalysisClaim evidenceGapRiskClaim(AnalysisRun run) {
        List<String> gaps = run.getResearchPackage().getMissingEvidenceTypes();
        AnalysisClaim claim = baseClaim(run, List.of());
        claim.setType(ClaimType.RISK);
        claim.setContent("当前仍存在证据缺口：%s；相关结论必须保持待验证，优先打回采集或补充用户资料。".formatted(String.join("、", gaps)));
        claim.setConfidence(ConfidenceLevel.LOW);
        return claim;
    }

    private AnalysisClaim baseClaim(AnalysisRun run, List<String> evidenceIds) {
        AnalysisClaim claim = new AnalysisClaim();
        claim.setGeneratedBy(AgentName.ANALYST.name());
        claim.setCompetitorNames(run.getRequirement().getCompetitors());
        claim.setEvidenceIds(evidenceIds == null ? List.of() : distinctKnownEvidenceIds(run, evidenceIds));
        return claim;
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
        String claimRows = claims.stream()
                .map(claim -> "| %s | %s | %s | %s |".formatted(
                        claim.getType(),
                        claim.getConfidence(),
                        claim.getContent(),
                        citationText(claim.getEvidenceIds())
                ))
                .collect(Collectors.joining("\n"));
        return """
                ## 竞品横向矩阵

                | 竞品 | 主要优势 | 潜在弱势 | 定价判断 | 证据 |
                | --- | --- | --- | --- | --- |
                %s

                ## 结构化结论

                | 类型 | 置信度 | 结论 | 证据 |
                | --- | --- | --- | --- |
                %s
                """.formatted(rows, claimRows);
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
            requirement.getDimensions().stream().filter(this::hasText).forEach(dimensions::add);
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

    private List<String> distinctKnownEvidenceIds(AnalysisRun run, List<String> evidenceIds) {
        Set<String> known = run.getEvidenceSources().stream()
                .map(EvidenceSource::getCitationKey)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return (evidenceIds == null ? List.<String>of() : evidenceIds).stream()
                .filter(this::hasText)
                .filter(known::contains)
                .distinct()
                .toList();
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

    private boolean containsIgnoreCase(String text, String pattern) {
        return text != null && pattern != null && text.toLowerCase(Locale.ROOT).contains(pattern.toLowerCase(Locale.ROOT));
    }

    private boolean containsAny(String text, String... patterns) {
        for (String pattern : patterns) {
            if (containsIgnoreCase(text, pattern)) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String text) {
        return nullToEmpty(text).toLowerCase(Locale.ROOT);
    }

    private boolean hasText(String text) {
        return text != null && !text.isBlank();
    }

    private String nullToEmpty(String text) {
        return text == null ? "" : text;
    }
}
