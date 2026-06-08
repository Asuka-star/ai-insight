package com.aiinsight.agent.node;

import com.aiinsight.model.enums.ClaimType;
import com.aiinsight.model.enums.ConfidenceLevel;
import com.aiinsight.model.run.AnalysisRequirement;
import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.model.run.EvidenceSource;
import com.aiinsight.model.schema.AnalysisClaim;
import com.aiinsight.util.AgentUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.aiinsight.agent.node.AnalysisClaimRules.PLACEMENT_NONE;
import static com.aiinsight.agent.node.AnalysisClaimRules.PLACEMENT_VALIDATION_BACKLOG;
import static com.aiinsight.agent.node.AnalysisClaimRules.SUPPORT_STATUS_UNVERIFIED;
import static com.aiinsight.agent.node.AnalysisClaimRules.displayableClaim;
import static com.aiinsight.agent.node.AnalysisClaimRules.matrixClaim;
import static com.aiinsight.agent.node.AnalysisClaimRules.normalizeRecommendedPlacement;
import static com.aiinsight.agent.node.AnalysisClaimRules.normalizeSupportStatus;
import static com.aiinsight.agent.node.AnalysisClaimRules.swotClaim;
import static com.aiinsight.util.AgentUtils.hasText;
import static com.aiinsight.util.AgentUtils.normalizeLower;
import static com.aiinsight.util.AgentUtils.safeList;
import static com.aiinsight.util.AgentUtils.textOrDash;

final class AnalysisProductRenderer {

    String renderMatrix(AnalysisRun run, List<AnalysisClaim> claims) {
        List<String> competitors = matrixCompetitors(run, claims);
        String rows = competitors.stream()
                .map(competitor -> matrixRowForCompetitor(run, competitor, claims))
                .collect(Collectors.joining("\n"));
        String dimensionRows = dimensionCoverageRows(run, claims);
        String backlogRows = validationBacklogRows(claims);
        return """
                ## 基于结构化结论的竞品矩阵

                | 竞品 | 基于结论的判断 | 置信度 | 证据 |
                | --- | --- | --- | --- |
                %s

                ## 用户指定维度覆盖

                | 维度 | 判断 | 置信度 | 证据 |
                | --- | --- | --- | --- |
                %s

                ## 待验证结论
                | 维度 | 结论 | 原因 |
                | --- | --- | --- |
                %s

                说明：主矩阵仅使用 MEDIUM/HIGH、已绑定证据且 recommendedPlacement=MATRIX 的结构化结论；LOW、无证据或 UNVERIFIED 结论只进入待验证区域。
                """.formatted(
                rows.isBlank() ? "| - | 暂无结构化结论。 | LOW | 证据不足 |" : rows,
                dimensionRows,
                backlogRows
        );
    }

    String renderSwot(List<AnalysisClaim> claims) {
        return """
                | 维度 | 基于结构化结论的判断 | 证据 |
                | --- | --- | --- |
                | 优势 | %s | %s |
                | 短板 | %s | %s |
                | 机会 | %s | %s |
                | 威胁 | %s | %s |

                说明：SWOT 仅由结构化结论渲染；证据不足的想法应留在证据缺口中，不作为新的 SWOT 结论。
                """.formatted(
                swotText(claims, ClaimType.STRENGTH, ClaimType.COMPARISON),
                citationText(evidenceIdsForClaimType(claims, ClaimType.STRENGTH, ClaimType.COMPARISON)),
                swotText(claims, ClaimType.WEAKNESS),
                citationText(evidenceIdsForClaimType(claims, ClaimType.WEAKNESS)),
                swotText(claims, ClaimType.OPPORTUNITY, ClaimType.RECOMMENDATION),
                citationText(evidenceIdsForClaimType(claims, ClaimType.OPPORTUNITY, ClaimType.RECOMMENDATION)),
                swotText(claims, ClaimType.RISK),
                citationText(evidenceIdsForClaimType(claims, ClaimType.RISK))
        );
    }

    private String matrixRowForCompetitor(AnalysisRun run, String competitor, List<AnalysisClaim> claims) {
        Map<String, EvidenceSource> sourceByCitationKey = AgentEvidenceSupport.sourceByCitationKey(run);
        List<AnalysisClaim> relatedClaims = claims.stream()
                .filter(AnalysisClaimRules::matrixClaim)
                .filter(claim -> claimAppliesToCompetitor(claim, competitor))
                .sorted((left, right) -> Integer.compare(claimDisplayScore(right, sourceByCitationKey),
                        claimDisplayScore(left, sourceByCitationKey)))
                .limit(3)
                .toList();
        if (relatedClaims.isEmpty()) {
            return "| %s | 暂无可归属的结构化结论。 | LOW | 证据不足 |".formatted(escapeCell(competitor));
        }
        String summary = relatedClaims.stream()
                .map(claim -> "%s: %s".formatted(claim.getType(), claim.getContent()))
                .collect(Collectors.joining("<br>"));
        String confidence = relatedClaims.stream()
                .map(claim -> String.valueOf(claim.getConfidence()))
                .distinct()
                .collect(Collectors.joining("/"));
        List<String> evidenceIds = relatedClaims.stream()
                .flatMap(claim -> claim.getEvidenceIds().stream())
                .distinct()
                .toList();
        return "| %s | %s | %s | %s |".formatted(
                escapeCell(competitor),
                escapeCell(summary),
                confidence,
                citationText(evidenceIds)
        );
    }

    private String dimensionCoverageRows(AnalysisRun run, List<AnalysisClaim> claims) {
        List<String> dimensions = run.getRequirement() == null
                ? List.of()
                : safeList(run.getRequirement().getDimensions()).stream()
                .filter(AgentUtils::hasText)
                .toList();
        if (dimensions.isEmpty()) {
            dimensions = List.of("综合判断");
        }
        return dimensions.stream()
                .map(dimension -> dimensionCoverageRow(dimension, claims))
                .collect(Collectors.joining("\n"));
    }

    private String dimensionCoverageRow(String dimension, List<AnalysisClaim> claims) {
        List<AnalysisClaim> relatedClaims = claims.stream()
                .filter(AnalysisClaimRules::matrixClaim)
                .filter(claim -> claimMatchesDimension(claim, dimension))
                .limit(2)
                .toList();
        if (relatedClaims.isEmpty()) {
            return "| %s | 证据不足，待验证。 | LOW | 证据不足 |".formatted(escapeCell(dimension));
        }
        String summary = relatedClaims.stream()
                .map(AnalysisClaim::getContent)
                .collect(Collectors.joining("<br>"));
        String confidence = relatedClaims.stream()
                .map(claim -> String.valueOf(claim.getConfidence()))
                .distinct()
                .collect(Collectors.joining("/"));
        List<String> evidenceIds = relatedClaims.stream()
                .flatMap(claim -> claim.getEvidenceIds().stream())
                .distinct()
                .toList();
        return "| %s | %s | %s | %s |".formatted(
                escapeCell(dimension),
                escapeCell(summary),
                confidence,
                citationText(evidenceIds)
        );
    }

    private boolean claimMatchesDimension(AnalysisClaim claim, String dimension) {
        if ("综合判断".equals(dimension)) {
            return true;
        }
        String claimDimension = textOrDash(claim.getDimension());
        if (!hasText(claimDimension) || "-".equals(claimDimension)) {
            return false;
        }
        String normalizedClaimDimension = normalizeDimensionLabel(claimDimension);
        String normalizedRequestedDimension = normalizeDimensionLabel(dimension);
        if (normalizedClaimDimension.equals(normalizedRequestedDimension)) {
            return true;
        }
        int minLength = Math.min(normalizedClaimDimension.length(), normalizedRequestedDimension.length());
        return minLength >= 4
                && (normalizedClaimDimension.contains(normalizedRequestedDimension)
                || normalizedRequestedDimension.contains(normalizedClaimDimension));
    }

    private String normalizeDimensionLabel(String value) {
        return normalizeLower(value)
                .replaceAll("[^\\p{IsHan}a-z0-9]+", "")
                .trim();
    }

    private String validationBacklogRows(List<AnalysisClaim> claims) {
        String rows = claims.stream()
                .filter(claim -> !displayableClaim(claim)
                        || PLACEMENT_VALIDATION_BACKLOG.equals(normalizeRecommendedPlacement(
                        claim.getRecommendedPlacement(),
                        claim.getType()
                ))
                        || PLACEMENT_NONE.equals(normalizeRecommendedPlacement(
                        claim.getRecommendedPlacement(),
                        claim.getType()
                )))
                .limit(6)
                .map(claim -> "| %s | %s | %s |".formatted(
                        escapeCell(textOrDash(claim.getDimension())),
                        escapeCell(claim.getContent()),
                        validationReason(claim)
                ))
                .collect(Collectors.joining("\n"));
        return rows.isBlank() ? "| - | 暂无待验证结论。 | - |" : rows;
    }

    private String validationReason(AnalysisClaim claim) {
        if (claim.getEvidenceIds() == null || claim.getEvidenceIds().isEmpty()) {
            return "缺少可用证据绑定";
        }
        if (claim.getConfidence() == ConfidenceLevel.LOW) {
            return "LOW 置信度";
        }
        if (SUPPORT_STATUS_UNVERIFIED.equals(normalizeSupportStatus(claim.getSupportStatus()))) {
            return "支撑状态为 UNVERIFIED";
        }
        if (PLACEMENT_NONE.equals(normalizeRecommendedPlacement(claim.getRecommendedPlacement(), claim.getType()))) {
            return "放置建议为不展示";
        }
        return "放置建议为待验证";
    }

    private String swotText(List<AnalysisClaim> claims, ClaimType... types) {
        Set<ClaimType> accepted = Set.of(types);
        String text = claims.stream()
                .filter(AnalysisClaimRules::swotClaim)
                .filter(claim -> accepted.contains(claim.getType()))
                .map(AnalysisClaim::getContent)
                .filter(AgentUtils::hasText)
                .limit(2)
                .collect(Collectors.joining("<br>"));
        return text.isBlank() ? "暂无结构化结论。" : escapeCell(text);
    }

    private List<String> matrixCompetitors(AnalysisRun run, List<AnalysisClaim> claims) {
        LinkedHashSet<String> competitors = new LinkedHashSet<>();
        AnalysisRequirement requirement = run.getRequirement();
        if (requirement != null && requirement.getCompetitors() != null) {
            requirement.getCompetitors().stream().filter(AgentUtils::hasText).forEach(competitors::add);
        }
        run.getCompetitorProfiles().stream()
                .map(profile -> textOrDash(profile.getProductName()))
                .filter(AgentUtils::hasText)
                .forEach(competitors::add);
        claims.stream()
                .flatMap(claim -> safeList(claim.getCompetitorNames()).stream())
                .filter(AgentUtils::hasText)
                .forEach(competitors::add);
        return competitors.isEmpty() ? List.of("-") : new ArrayList<>(competitors);
    }

    private boolean claimAppliesToCompetitor(AnalysisClaim claim, String competitor) {
        if (claim.getCompetitorNames() == null || claim.getCompetitorNames().isEmpty()) {
            return true;
        }
        return claim.getCompetitorNames().stream()
                .anyMatch(name -> name.equalsIgnoreCase(competitor));
    }

    private int claimDisplayScore(AnalysisClaim claim, Map<String, EvidenceSource> sourceByCitationKey) {
        int bestEvidenceScore = safeList(claim.getEvidenceIds()).stream()
                .map(sourceByCitationKey::get)
                .mapToInt(AgentEvidenceSupport::evidenceConfidenceScore)
                .max()
                .orElse(0);
        return bestEvidenceScore * 100 + confidenceScore(claim.getConfidence()) * 10 + claimTypeDisplayScore(claim.getType());
    }

    private int confidenceScore(ConfidenceLevel confidence) {
        if (confidence == ConfidenceLevel.HIGH) {
            return 3;
        }
        if (confidence == ConfidenceLevel.MEDIUM) {
            return 2;
        }
        return 1;
    }

    private int claimTypeDisplayScore(ClaimType type) {
        if (type == null) {
            return 0;
        }
        return switch (type) {
            case RECOMMENDATION -> 7;
            case OPPORTUNITY -> 6;
            case COMPARISON -> 5;
            case STRENGTH -> 4;
            case WEAKNESS -> 3;
            case RISK -> 2;
            case FACT -> 1;
        };
    }

    private List<String> evidenceIdsForClaimType(List<AnalysisClaim> claims, ClaimType... types) {
        Set<ClaimType> accepted = Set.of(types);
        return claims.stream()
                .filter(AnalysisClaimRules::swotClaim)
                .filter(claim -> accepted.contains(claim.getType()))
                .flatMap(claim -> claim.getEvidenceIds().stream())
                .distinct()
                .toList();
    }

    private String citationText(List<String> evidenceIds) {
        if (evidenceIds == null || evidenceIds.isEmpty()) {
            return "证据不足";
        }
        return evidenceIds.stream()
                .filter(AgentUtils::hasText)
                .distinct()
                .map(id -> "[" + id + "]")
                .collect(Collectors.joining(" "));
    }

    private String competitorText(List<String> competitors) {
        if (competitors == null || competitors.isEmpty()) {
            return "-";
        }
        return competitors.stream().filter(AgentUtils::hasText).collect(Collectors.joining(", "));
    }

    private String escapeCell(String value) {
        return textOrDash(value).replace("|", "\\|").replace("\n", "<br>");
    }

}
