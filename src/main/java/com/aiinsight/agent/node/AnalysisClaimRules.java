package com.aiinsight.agent.node;

import com.aiinsight.model.enums.ClaimType;
import com.aiinsight.model.enums.ConfidenceLevel;
import com.aiinsight.model.schema.AnalysisClaim;

import java.util.Locale;

import static com.aiinsight.util.AgentUtils.normalizeUpper;
import static com.aiinsight.util.AgentUtils.nullToEmpty;

final class AnalysisClaimRules {

    static final String SUPPORT_STATUS_SUPPORTED = "SUPPORTED";
    static final String SUPPORT_STATUS_PARTIAL = "PARTIAL";
    static final String SUPPORT_STATUS_UNVERIFIED = "UNVERIFIED";
    static final String PLACEMENT_MATRIX = "MATRIX";
    static final String PLACEMENT_SWOT = "SWOT";
    static final String PLACEMENT_VALIDATION_BACKLOG = "VALIDATION_BACKLOG";
    static final String PLACEMENT_NONE = "NONE";

    private AnalysisClaimRules() {
    }

    static String normalizeSupportStatus(String value) {
        String normalized = normalizeUpper(value);
        if (SUPPORT_STATUS_SUPPORTED.equals(normalized)
                || SUPPORT_STATUS_PARTIAL.equals(normalized)
                || SUPPORT_STATUS_UNVERIFIED.equals(normalized)) {
            return normalized;
        }
        return SUPPORT_STATUS_PARTIAL;
    }

    static String normalizeRecommendedPlacement(String value, ClaimType type) {
        String normalized = normalizeUpper(value);
        if (PLACEMENT_MATRIX.equals(normalized)
                || PLACEMENT_SWOT.equals(normalized)
                || PLACEMENT_VALIDATION_BACKLOG.equals(normalized)
                || PLACEMENT_NONE.equals(normalized)) {
            return normalized;
        }
        return defaultPlacementFor(type);
    }

    static String defaultPlacementFor(ClaimType type) {
        if (type == ClaimType.STRENGTH
                || type == ClaimType.WEAKNESS
                || type == ClaimType.OPPORTUNITY
                || type == ClaimType.RISK) {
            return PLACEMENT_SWOT;
        }
        return PLACEMENT_MATRIX;
    }

    static boolean containsUncertaintyMarker(String text) {
        String normalized = nullToEmpty(text);
        return normalized.contains("待验证")
                || normalized.contains("证据不足")
                || normalized.toLowerCase(Locale.ROOT).contains("insufficient evidence");
    }

    static boolean displayableClaim(AnalysisClaim claim) {
        return claim != null
                && claim.getConfidence() != ConfidenceLevel.LOW
                && SUPPORT_STATUS_SUPPORTED.equals(normalizeSupportStatus(claim.getSupportStatus()))
                && !SUPPORT_STATUS_UNVERIFIED.equals(normalizeSupportStatus(claim.getSupportStatus()))
                && claim.getEvidenceIds() != null
                && !claim.getEvidenceIds().isEmpty()
                && !containsUncertaintyMarker(claim.getContent());
    }

    static boolean matrixClaim(AnalysisClaim claim) {
        if (claim != null && claim.getEligibleForMatrix() != null) {
            return Boolean.TRUE.equals(claim.getEligibleForMatrix());
        }
        return displayableClaim(claim) && PLACEMENT_MATRIX.equals(normalizeRecommendedPlacement(
                claim.getRecommendedPlacement(),
                claim.getType()
        ));
    }

    static boolean swotClaim(AnalysisClaim claim) {
        if (claim != null && claim.getEligibleForSwot() != null) {
            return Boolean.TRUE.equals(claim.getEligibleForSwot());
        }
        return displayableClaim(claim) && PLACEMENT_SWOT.equals(normalizeRecommendedPlacement(
                claim.getRecommendedPlacement(),
                claim.getType()
        ));
    }

    // 宽松的 SWOT 过滤：只要 claim 基本可展示（非 LOW、非 UNVERIFIED、有证据），
    // 不管 recommendedPlacement 是 MATRIX 还是 SWOT，都可以作为 SWOT 的降级内容。
    // 这解决了 Analyst 把 STRENGTH/WEAKNESS 类 claim 标记为 VALIDATION_BACKLOG
    // 导致 SWOT 全部为空的问题。
    static boolean displayableSwotClaim(AnalysisClaim claim) {
        return claim != null
                && claim.getConfidence() != ConfidenceLevel.LOW
                && !SUPPORT_STATUS_UNVERIFIED.equals(normalizeSupportStatus(claim.getSupportStatus()))
                && claim.getEvidenceIds() != null
                && !claim.getEvidenceIds().isEmpty()
                && !containsUncertaintyMarker(claim.getContent());
    }
}
