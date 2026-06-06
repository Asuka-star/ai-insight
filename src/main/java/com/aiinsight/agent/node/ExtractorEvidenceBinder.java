package com.aiinsight.agent.node;

import com.aiinsight.model.enums.FactType;
import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.model.run.EvidenceChunk;
import com.aiinsight.model.run.EvidenceSource;
import com.aiinsight.util.AgentUtils;
import com.aiinsight.util.TermExtractor;
import com.aiinsight.util.TermExtractor.TermOptions;
import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import static com.aiinsight.util.AgentUtils.abbreviate;
import static com.aiinsight.util.AgentUtils.containsAny;
import static com.aiinsight.util.AgentUtils.containsIgnoreCase;
import static com.aiinsight.util.AgentUtils.knownEvidenceIds;
import static com.aiinsight.util.AgentUtils.normalizeLower;
import static com.aiinsight.util.AgentUtils.nullToEmpty;
import static com.aiinsight.util.AgentUtils.textOrDash;

final class ExtractorEvidenceBinder {

    private static final int MAX_PRICING_EVIDENCE_IDS_PER_FACT = 4;
    private static final List<String> PRICING_EVIDENCE_MARKERS = List.of(
            "pricing", "price", "plan", "plans", "free", "paid", "subscription", "billing",
            "$", "usd", "/month", "month", "annual", "year", "enterprise",
            "价格", "定价", "套餐", "免费", "付费", "订阅", "月付", "年付", "企业版"
    );
    private static final Set<String> FACT_SUPPORT_STOP_WORDS = Set.of(
            "supports", "support", "provides", "provide", "offers", "offer", "includes", "include",
            "using", "used", "with", "without", "for", "from", "that", "this", "and", "the",
            "feature", "features", "capability", "capabilities", "product", "users", "user",
            "支持", "提供", "功能", "能力", "用户", "产品", "可以", "用于", "适用", "覆盖", "包括"
    );
    private static final TermOptions FACT_SUPPORT_TERM_OPTIONS = TermOptions.support(3, FACT_SUPPORT_STOP_WORDS);
    List<String> chunkKeysForEvidence(AnalysisRun run, List<String> evidenceIds, FactType factType, String value) {
        Set<String> accepted = new LinkedHashSet<>(evidenceIds);
        return run.getEvidenceChunks().stream()
                .filter(chunk -> accepted.contains(chunk.getSourceCitationKey()))
                .filter(chunk -> riskCompatibleChunk(run, chunk, factType, "", value))
                .filter(chunk -> evidenceTextSupports(value, chunkText(chunk), factType))
                .map(EvidenceChunk::getChunkKey)
                .filter(StringUtils::hasText)
                .distinct()
                .limit(6)
                .toList();
    }

    List<String> supportedEvidenceIdsForFact(AnalysisRun run,
                                             List<String> evidenceIds,
                                             String competitorName,
                                             FactType factType,
                                             String attribute,
                                             String value) {
        return evidenceIds.stream()
                .filter(id -> evidenceSupportsFact(run, id, competitorName, factType, attribute, value))
                .limit(6)
                .toList();
    }

    boolean highRiskFact(FactType factType, String attribute, String value) {
        return !"NORMAL".equals(riskLevelForFact(factType, attribute, value));
    }

    String riskLevelForFact(FactType factType, String attribute, String value) {
        String text = normalizeLower("%s %s %s".formatted(factType, nullToEmpty(attribute), nullToEmpty(value)));
        if (factType == FactType.PRICING || containsAny(text, "pricing", "price", "plan", "subscription", "$", "价格", "定价", "套餐", "订阅", "付费")) {
            return "PRICING";
        }
        if (factType == FactType.SECURITY || containsAny(text, "security", "compliance", "privacy", "安全", "合规", "隐私", "审计", "soc", "saml", "sso")) {
            return "SECURITY";
        }
        if (factType == FactType.PERMISSION || containsAny(text, "permission", "rbac", "scim", "权限", "角色", "治理")) {
            return "PERMISSION";
        }
        if (containsAny(text, "deployment", "deploy", "bedrock", "proxy", "vpc", "部署", "代理")) {
            return "DEPLOYMENT";
        }
        if (factType == FactType.CUSTOMER_SIGNAL) {
            return "CUSTOMER_SIGNAL";
        }
        return "NORMAL";
    }

    String supportStrengthForFact(FactType factType, List<String> chunkKeys) {
        if (chunkKeys == null || chunkKeys.isEmpty()) {
            return highRiskFact(factType, "", "") ? "UNSUPPORTED" : "WEAK";
        }
        return highRiskFact(factType, "", "") ? "DIRECT" : "PARTIAL";
    }

    String supportQuoteForChunks(AnalysisRun run, List<String> chunkKeys) {
        if (chunkKeys == null || chunkKeys.isEmpty()) {
            return "";
        }
        Set<String> accepted = new LinkedHashSet<>(chunkKeys);
        return run.getEvidenceChunks().stream()
                .filter(chunk -> accepted.contains(chunk.getChunkKey()))
                .map(EvidenceChunk::getText)
                .filter(StringUtils::hasText)
                .map(text -> abbreviate(text.replaceAll("\\s+", " ").trim(), 120))
                .findFirst()
                .orElse("");
    }

    boolean riskCompatibleEvidence(AnalysisRun run,
                                   String citationKey,
                                   FactType factType,
                                   String attribute,
                                   String value) {
        String risk = riskLevelForFact(factType, attribute, value);
        EvidenceSource source = AgentEvidenceSupport.sourceByCitationKey(run, citationKey);
        if (source == null) {
            return false;
        }
        if ("NORMAL".equals(risk)) {
            return true;
        }
        if ("PRICING".equals(risk)) {
            return isPricingEvidence(run, citationKey);
        }
        if ("CUSTOMER_SIGNAL".equals(risk)) {
            return customerSignalEvidence(source);
        }
        if (riskCompatibleSourceType(source, risk)
                && AgentEvidenceSupport.sourceTextHasRiskSignal(sourceText(source), risk)) {
            return true;
        }
        return run.getEvidenceChunks().stream()
                .filter(chunk -> citationKey.equals(chunk.getSourceCitationKey()))
                .anyMatch(chunk -> riskCompatibleChunk(run, chunk, factType, attribute, value));
    }

    List<String> pricingEvidenceIds(AnalysisRun run, List<String> evidenceIds) {
        return knownEvidenceIds(run, evidenceIds, List.of()).stream()
                .filter(id -> isPricingEvidence(run, id))
                .limit(MAX_PRICING_EVIDENCE_IDS_PER_FACT)
                .toList();
    }

    EvidenceSource primarySource(AnalysisRun run, List<String> evidenceIds) {
        Set<String> accepted = new LinkedHashSet<>(evidenceIds);
        return run.getEvidenceSources().stream()
                .filter(source -> accepted.contains(source.getCitationKey()))
                .findFirst()
                .orElse(null);
    }

    String extractionConfidence(EvidenceSource source, List<String> chunkKeys) {
        if (source == null) {
            return "LOW";
        }
        String quality = textOrDash(source.getSourceQuality()).toUpperCase(Locale.ROOT);
        String authority = textOrDash(source.getSourceAuthority()).toUpperCase(Locale.ROOT);
        if ("HIGH".equals(quality) && (authority.startsWith("FIRST_PARTY") || "USER_PROVIDED".equals(authority) || "INTERNAL_ONLY".equals(authority))) {
            return "HIGH";
        }
        if ("UNUSABLE".equals(quality) || chunkKeys == null || chunkKeys.isEmpty()) {
            return "LOW";
        }
        return "MEDIUM";
    }

    private boolean evidenceSupportsFact(AnalysisRun run,
                                         String citationKey,
                                         String competitorName,
                                         FactType factType,
                                         String attribute,
                                         String value) {
        EvidenceSource source = AgentEvidenceSupport.sourceByCitationKey(run, citationKey);
        if (source == null) {
            return false;
        }
        if (!riskCompatibleEvidence(run, citationKey, factType, attribute, value)) {
            return false;
        }
        String sourceText = sourceText(source);
        if (StringUtils.hasText(competitorName) && !containsIgnoreCase(sourceText, competitorName)) {
            boolean chunkMentionsCompetitor = run.getEvidenceChunks().stream()
                    .filter(chunk -> citationKey.equals(chunk.getSourceCitationKey()))
                    .anyMatch(chunk -> containsIgnoreCase(chunkText(chunk), competitorName));
            if (!chunkMentionsCompetitor) {
                return false;
            }
        }
        String expected = "%s %s".formatted(nullToEmpty(attribute), nullToEmpty(value));
        if (evidenceTextSupports(expected, sourceText, factType)) {
            return true;
        }
        return run.getEvidenceChunks().stream()
                .filter(chunk -> citationKey.equals(chunk.getSourceCitationKey()))
                .filter(chunk -> riskCompatibleChunk(run, chunk, factType, attribute, value))
                .anyMatch(chunk -> evidenceTextSupports(expected, chunkText(chunk), factType));
    }

    private boolean riskCompatibleChunk(AnalysisRun run,
                                        EvidenceChunk chunk,
                                        FactType factType,
                                        String attribute,
                                        String value) {
        String risk = riskLevelForFact(factType, attribute, value);
        if ("NORMAL".equals(risk)) {
            return true;
        }
        if ("PRICING".equals(risk)) {
            return isPricingChunk(run, chunk);
        }
        String text = chunkText(chunk);
        EvidenceSource source = AgentEvidenceSupport.sourceByCitationKey(run, chunk.getSourceCitationKey());
        boolean compatibleType = source == null || riskCompatibleSourceType(source, risk);
        return compatibleType && AgentEvidenceSupport.sourceTextHasRiskSignal(text, risk);
    }

    private boolean riskCompatibleSourceType(EvidenceSource source, String risk) {
        String sourceType = normalizeLower(source.getSourceType());
        return switch (risk) {
            case "SECURITY", "PERMISSION" ->
                    containsAny(sourceType, "security", "docs", "product", "official", "trust", "privacy");
            case "DEPLOYMENT" ->
                    containsAny(sourceType, "docs", "product", "official", "security", "integration");
            case "CUSTOMER_SIGNAL" -> customerSignalEvidence(source);
            default -> true;
        };
    }

    private boolean customerSignalEvidence(EvidenceSource source) {
        String type = normalizeLower(source.getSourceType());
        String text = normalizeLower(sourceText(source));
        return containsAny(type, "interview", "survey", "review", "customer", "user")
                || containsAny(text, "interview", "survey", "review", "feedback", "customer", "user", "pain", "concern",
                "访谈", "调研", "评价", "反馈", "用户", "痛点");
    }

    private boolean evidenceTextSupports(String expected, String evidenceText, FactType factType) {
        Set<String> expectedTerms = supportTerms(expected);
        Set<String> evidenceTerms = supportTerms(evidenceText);
        if (expectedTerms.isEmpty() || evidenceTerms.isEmpty()) {
            return false;
        }
        String normalizedExpected = normalizeLower(expected).replaceAll("\\s+", " ").trim();
        String normalizedEvidence = normalizeLower(evidenceText).replaceAll("\\s+", " ").trim();
        if (normalizedExpected.length() >= 8 && normalizedEvidence.contains(normalizedExpected)) {
            return true;
        }
        Set<String> preciseTerms = expectedTerms.stream()
                .filter(AgentEvidenceSupport.HIGH_PRECISION_SUPPORT_TERMS::contains)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (preciseTerms.isEmpty()) {
            return true;
        }
        if (!evidenceTerms.containsAll(preciseTerms)) {
            return false;
        }
        long overlap = expectedTerms.stream().filter(evidenceTerms::contains).count();
        int required = factType == FactType.TARGET_USER || expectedTerms.size() <= 2 ? 1 : 2;
        return overlap >= Math.min(required, expectedTerms.size());
    }

    private Set<String> supportTerms(String text) {
        return TermExtractor.extract(text, FACT_SUPPORT_TERM_OPTIONS);
    }

    private String chunkText(EvidenceChunk chunk) {
        return AgentEvidenceSupport.evidenceChunkText(chunk);
    }

    private boolean isPricingEvidence(AnalysisRun run, String citationKey) {
        EvidenceSource source = AgentEvidenceSupport.sourceByCitationKey(run, citationKey);
        if (source != null && (containsPricingSignal(source.getSourceType()) || containsPricingSignal(sourceText(source)))) {
            return true;
        }
        return run.getEvidenceChunks().stream()
                .filter(chunk -> citationKey.equals(chunk.getSourceCitationKey()))
                .anyMatch(chunk -> isPricingChunk(run, chunk));
    }

    private boolean isPricingChunk(AnalysisRun run, EvidenceChunk chunk) {
        String text = AgentEvidenceSupport.pricingChunkText(chunk);
        if (containsPricingSignal(text)) {
            return true;
        }
        EvidenceSource source = AgentEvidenceSupport.sourceByCitationKey(run, chunk.getSourceCitationKey());
        return source != null && containsPricingSignal(source.getSourceType());
    }

    private boolean containsPricingSignal(String value) {
        return AgentEvidenceSupport.containsPricingSignal(value, PRICING_EVIDENCE_MARKERS);
    }

    private String sourceText(EvidenceSource source) {
        return AgentEvidenceSupport.evidenceSourceText(source);
    }
}
