package com.aiinsight.agent.node;

import com.aiinsight.model.enums.ConfidenceLevel;
import com.aiinsight.model.enums.FactType;
import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.model.run.EvidenceChunk;
import com.aiinsight.model.run.EvidenceSource;
import com.aiinsight.model.schema.AnalysisClaim;
import com.aiinsight.model.schema.ExtractedFact;
import com.aiinsight.util.AgentUtils;
import com.aiinsight.util.TermExtractor;
import com.aiinsight.util.TermExtractor.TermOptions;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.aiinsight.util.AgentUtils.containsAny;
import static com.aiinsight.util.AgentUtils.containsIgnoreCase;
import static com.aiinsight.util.AgentUtils.normalizeLower;
import static com.aiinsight.util.AgentUtils.nullToEmpty;
import static com.aiinsight.util.AgentUtils.safeList;

final class ClaimEvidenceBinder {

    private static final Set<String> CLAIM_SUPPORT_STOP_WORDS = Set.of(
            "supports", "support", "provides", "provide", "offers", "offer", "includes", "include",
            "using", "used", "with", "without", "for", "from", "that", "this", "and", "the",
            "feature", "features", "capability", "capabilities", "product", "users", "user",
            "analysis", "competitive", "advantage", "risk", "opportunity", "recommendation",
            "支持", "提供", "功能", "能力", "用户", "产品", "可以", "用于", "适用", "覆盖", "包括",
            "风险", "机会", "建议", "分析", "竞品", "结论", "证据", "不足", "待验证"
    );
    private static final TermOptions CLAIM_SUPPORT_TERM_OPTIONS = TermOptions.supportWithAliases(3, CLAIM_SUPPORT_STOP_WORDS);
    private static final TermOptions BINDING_TERM_OPTIONS = TermOptions.basic(2);
    private static final int MIN_AUTO_BIND_SCORE = 3;

    void bindClaimFacts(AnalysisRun run, AnalysisClaim claim) {
        List<ExtractedFact> selectedFacts = selectedFactsForClaim(run, claim);
        if (claim.getFactIds().isEmpty()) {
            claim.setFactIds(selectedFacts.stream()
                    .map(ExtractedFact::getId)
                    .filter(AgentUtils::hasText)
                    .distinct()
                    .limit(6)
                    .toList());
        }
        if (claim.getEvidenceIds().isEmpty()) {
            claim.setEvidenceIds(selectedFacts.stream()
                    .flatMap(fact -> fact.getEvidenceIds().stream())
                    .filter(AgentUtils::hasText)
                    .distinct()
                    .limit(6)
                    .toList());
        }
        if (claim.getChunkKeys().isEmpty()) {
            claim.setChunkKeys(selectedFacts.stream()
                    .flatMap(fact -> fact.getChunkKeys().stream())
                    .filter(AgentUtils::hasText)
                    .distinct()
                    .limit(8)
                    .toList());
        }
    }

    void pruneUnsupportedClaimEvidence(AnalysisRun run, AnalysisClaim claim) {
        if (claim == null || claim.getEvidenceIds().isEmpty()) {
            return;
        }
        List<String> supportedEvidenceIds = claim.getEvidenceIds().stream()
                .filter(id -> evidenceSupportsClaim(run, id, claim))
                .distinct()
                .limit(6)
                .toList();
        if (supportedEvidenceIds.size() == claim.getEvidenceIds().size()) {
            return;
        }
        claim.setEvidenceIds(supportedEvidenceIds);
        Set<String> acceptedSources = new LinkedHashSet<>(supportedEvidenceIds);
        claim.setChunkKeys(claim.getChunkKeys().stream()
                .filter(chunkKey -> chunkSupportsClaim(run, chunkKey, acceptedSources, claim))
                .distinct()
                .limit(8)
                .toList());
        if (supportedEvidenceIds.isEmpty()) {
            claim.setConfidence(ConfidenceLevel.LOW);
        } else if (claim.getConfidence() == ConfidenceLevel.HIGH) {
            claim.setConfidence(ConfidenceLevel.MEDIUM);
        }
    }

    boolean evidenceSupportsClaim(AnalysisRun run, String citationKey, AnalysisClaim claim) {
        if (!sourceMatchesSingleCompetitorClaim(run, citationKey, claim)) {
            return false;
        }
        if (boundFactSupportsEvidence(run, citationKey, claim)) {
            return true;
        }
        EvidenceSource source = AgentEvidenceSupport.sourceByCitationKey(run, citationKey);
        if (source == null) {
            return false;
        }
        String sourceText = evidenceSourceText(source);
        if (!claimMentionsAnyCompetitor(claim, sourceText)) {
            return false;
        }
        if (analystQuoteSupportsEvidence(run, citationKey, claim, sourceText)) {
            return true;
        }
        if (supportTextMatches(claim.getContent(), sourceText)) {
            return true;
        }
        return run.getEvidenceChunks().stream()
                .filter(chunk -> citationKey.equals(chunk.getSourceCitationKey()))
                .anyMatch(chunk -> supportTextMatches(claim.getContent(), evidenceChunkText(chunk)));
    }

    boolean supportTextMatches(String claimText, String evidenceText) {
        Set<String> claimTerms = supportTerms(claimText);
        Set<String> evidenceTerms = supportTerms(evidenceText);
        if (claimTerms.isEmpty() || evidenceTerms.isEmpty()) {
            return false;
        }
        Set<String> preciseTerms = claimTerms.stream()
                .filter(AgentEvidenceSupport.HIGH_PRECISION_SUPPORT_TERMS::contains)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!preciseTerms.isEmpty() && !evidenceTerms.containsAll(preciseTerms)) {
            return false;
        }
        long overlap = claimTerms.stream().filter(evidenceTerms::contains).count();
        int required = preciseTerms.isEmpty()
                ? (claimTerms.size() <= 3 ? 1 : 2)
                : Math.max(2, preciseTerms.size());
        return overlap >= Math.min(required, claimTerms.size());
    }

    String evidenceSourceText(EvidenceSource source) {
        return AgentEvidenceSupport.evidenceSourceText(source);
    }

    String evidenceChunkText(EvidenceChunk chunk) {
        return AgentEvidenceSupport.evidenceChunkText(chunk);
    }

    private boolean analystQuoteSupportsEvidence(AnalysisRun run,
                                                 String citationKey,
                                                 AnalysisClaim claim,
                                                 String sourceText) {
        List<String> quotes = safeList(claim.getEvidenceQuotes());
        if (quotes.isEmpty()) {
            return false;
        }
        if (quotes.stream().anyMatch(quote -> supportTextMatches(quote, sourceText))) {
            return true;
        }
        return run.getEvidenceChunks().stream()
                .filter(chunk -> citationKey.equals(chunk.getSourceCitationKey()))
                .map(this::evidenceChunkText)
                .anyMatch(chunkText -> quotes.stream().anyMatch(quote -> supportTextMatches(quote, chunkText)));
    }

    private boolean boundFactSupportsEvidence(AnalysisRun run, String citationKey, AnalysisClaim claim) {
        Set<String> boundFactIds = new LinkedHashSet<>(safeList(claim.getFactIds()));
        Set<String> claimCompetitors = claimCompetitorKeys(claim);
        return run.getCompetitorFactSets().stream()
                .flatMap(factSet -> factSet.getFacts().stream())
                .filter(fact -> fact.getEvidenceIds().contains(citationKey))
                .filter(fact -> boundFactIds.isEmpty() || boundFactIds.contains(fact.getId()))
                .filter(fact -> claimCompetitors.size() != 1 || claimCompetitors.contains(competitorKey(fact.getCompetitorName())))
                .anyMatch(fact -> factSupportsClaim(fact, claim));
    }

    private boolean factSupportsClaim(ExtractedFact fact, AnalysisClaim claim) {
        if (fact.getFactType() == FactType.PRICING && claimLooksLikePricing(claim)) {
            return true;
        }
        if (fact.getFactType() == FactType.CUSTOMER_SIGNAL && claimLooksLikeCustomerSignal(claim)) {
            return true;
        }
        return supportTextMatches(claim.getContent(), "%s %s %s".formatted(
                fact.getFactType(),
                nullToEmpty(fact.getAttribute()),
                nullToEmpty(fact.getValue())
        ));
    }

    private boolean claimLooksLikePricing(AnalysisClaim claim) {
        String text = normalizeLower(claim.getContent());
        return containsAny(text, "pricing", "price", "plan", "subscription", "billing", "$",
                "价格", "定价", "套餐", "订阅", "付费", "商业模式");
    }

    private boolean claimLooksLikeCustomerSignal(AnalysisClaim claim) {
        String text = normalizeLower(claim.getContent());
        return containsAny(text, "review", "feedback", "customer", "user", "pain", "interview", "survey",
                "评价", "反馈", "用户", "痛点", "访谈", "调研");
    }

    private boolean chunkSupportsClaim(AnalysisRun run,
                                       String chunkKey,
                                       Set<String> acceptedSources,
                                       AnalysisClaim claim) {
        return run.getEvidenceChunks().stream()
                .filter(chunk -> chunkKey.equals(chunk.getChunkKey()))
                .filter(chunk -> acceptedSources.contains(chunk.getSourceCitationKey()))
                .anyMatch(chunk -> supportTextMatches(claim.getContent(), evidenceChunkText(chunk)));
    }

    private boolean claimMentionsAnyCompetitor(AnalysisClaim claim, String evidenceText) {
        List<String> competitors = safeList(claim.getCompetitorNames()).stream()
                .filter(AgentUtils::hasText)
                .toList();
        if (competitors.isEmpty()) {
            return true;
        }
        return competitors.stream().anyMatch(competitor -> containsIgnoreCase(evidenceText, competitor));
    }

    private boolean sourceMatchesSingleCompetitorClaim(AnalysisRun run, String citationKey, AnalysisClaim claim) {
        Set<String> claimCompetitors = claimCompetitorKeys(claim);
        if (claimCompetitors.size() != 1) {
            return true;
        }
        EvidenceSource source = AgentEvidenceSupport.sourceByCitationKey(run, citationKey);
        if (source == null) {
            return false;
        }
        String expectedCompetitor = claimCompetitors.iterator().next();
        Set<String> coveredCompetitors = safeList(source.getCoveredCompetitors()).stream()
                .map(this::competitorKey)
                .filter(AgentUtils::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!coveredCompetitors.isEmpty()) {
            return coveredCompetitors.contains(expectedCompetitor);
        }
        return safeList(claim.getCompetitorNames()).stream()
                .filter(AgentUtils::hasText)
                .anyMatch(competitor -> containsIgnoreCase(evidenceSourceText(source), competitor));
    }

    private Set<String> claimCompetitorKeys(AnalysisClaim claim) {
        return safeList(claim.getCompetitorNames()).stream()
                .map(this::competitorKey)
                .filter(AgentUtils::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Set<String> supportTerms(String text) {
        return TermExtractor.extract(text, CLAIM_SUPPORT_TERM_OPTIONS);
    }

    private List<ExtractedFact> selectedFactsForClaim(AnalysisRun run, AnalysisClaim claim) {
        List<ExtractedFact> allFacts = run.getCompetitorFactSets().stream()
                .flatMap(factSet -> factSet.getFacts().stream())
                .toList();
        if (allFacts.isEmpty()) {
            return List.of();
        }
        Set<String> requestedFactIds = new LinkedHashSet<>(safeList(claim.getFactIds()));
        Set<String> evidenceIds = new LinkedHashSet<>(safeList(claim.getEvidenceIds()));
        Set<String> competitors = safeList(claim.getCompetitorNames()).stream()
                .map(this::competitorKey)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> claimTerms = termsForBinding(claim.getContent());
        return allFacts.stream()
                .map(fact -> new FactMatch(fact, factMatchScore(fact, requestedFactIds, evidenceIds, competitors, claimTerms)))
                .filter(match -> match.score() >= MIN_AUTO_BIND_SCORE)
                .sorted((left, right) -> Integer.compare(right.score(), left.score()))
                .map(FactMatch::fact)
                .limit(6)
                .toList();
    }

    private int factMatchScore(ExtractedFact fact,
                               Set<String> requestedFactIds,
                               Set<String> evidenceIds,
                               Set<String> competitors,
                               Set<String> claimTerms) {
        int score = 0;
        if (!requestedFactIds.isEmpty() && requestedFactIds.contains(fact.getId())) {
            score += 100;
        }
        if (!evidenceIds.isEmpty() && fact.getEvidenceIds().stream().anyMatch(evidenceIds::contains)) {
            score += 20;
        }
        if (!competitors.isEmpty() && competitors.contains(competitorKey(fact.getCompetitorName()))) {
            score += 10;
        }
        Set<String> factTerms = termsForBinding("%s %s %s".formatted(fact.getFactType(), fact.getAttribute(), fact.getValue()));
        long overlap = claimTerms.stream().filter(factTerms::contains).count();
        score += (int) Math.min(overlap, 8);
        return score;
    }

    private Set<String> termsForBinding(String text) {
        return TermExtractor.extract(text, BINDING_TERM_OPTIONS);
    }

    private String competitorKey(String value) {
        return normalizeLower(value).replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}]+", "");
    }

    private record FactMatch(ExtractedFact fact, int score) {
    }
}
