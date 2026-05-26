package com.aiinsight.service;

import com.aiinsight.model.enums.ClaimType;
import com.aiinsight.model.enums.ConfidenceLevel;
import com.aiinsight.model.enums.ReviewSeverity;
import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.model.run.EvidenceChunk;
import com.aiinsight.model.run.EvidenceSource;
import com.aiinsight.model.schema.AnalysisClaim;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CitationCoverageEvaluatorTest {

    private final CitationCoverageEvaluator evaluator = new CitationCoverageEvaluator();

    @Test
    void flagsClaimParagraphWithoutCitation() {
        String report = """
                # Report

                机会点是构建可复核的 Agent 工作流。

                风险在于资料源不足会导致推断过度 [S1]。
                """;

        var findings = evaluator.evaluate(report);

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).getCategory()).isEqualTo("citation_missing");
        assertThat(findings.get(0).getParagraphIndex()).isEqualTo(1);
        assertThat(findings.get(0).getExcerpt()).contains("机会点");
    }

    @Test
    void flagsUnknownCitationKey() {
        AnalysisRun run = runWithEvidence();
        String report = "风险在于价格策略证据不足 [S404]。";

        var findings = evaluator.evaluate(report, run);

        assertThat(findings)
                .anySatisfy(finding -> {
                    assertThat(finding.getCategory()).isEqualTo("citation_unknown");
                    assertThat(finding.getCitationKey()).isEqualTo("S404");
                });
    }

    @Test
    void flagsWeakCitationSupportWhenEvidenceDoesNotMatchClaim() {
        AnalysisRun run = runWithEvidence();
        String report = "机会点是强化权限审计和企业安全治理 [S1]。";

        var findings = evaluator.evaluate(report, run);

        assertThat(findings)
                .anySatisfy(finding -> {
                    assertThat(finding.getCategory()).isEqualTo("citation_weak_support");
                    assertThat(finding.getCitationKey()).isEqualTo("S1");
                });
    }

    @Test
    void acceptsCitationWhenEvidenceChunkSupportsClaim() {
        AnalysisRun run = runWithEvidence();
        String report = "机会点是优化价格策略和套餐比较 [S1]。";

        var findings = evaluator.evaluate(report, run);

        assertThat(findings).isEmpty();
    }

    @Test
    void flagsStructuredClaimWithoutEvidenceWhenNotMarkedTentative() {
        AnalysisRun run = runWithEvidence();
        AnalysisClaim claim = claim("Notion 在企业权限治理上形成明显优势。", ConfidenceLevel.MEDIUM, List.of());
        run.getClaims().add(claim);

        var findings = evaluator.evaluate("## Report\n\n结论段落 [S1]。", run);

        assertThat(findings)
                .anySatisfy(finding -> {
                    assertThat(finding.getSeverity()).isEqualTo(ReviewSeverity.HIGH);
                    assertThat(finding.getCategory()).isEqualTo("claim_missing_evidence");
                    assertThat(finding.getClaimId()).isEqualTo(claim.getId());
                });
    }

    @Test
    void keepsTentativeStructuredClaimAsLowRiskReminder() {
        AnalysisRun run = runWithEvidence();
        AnalysisClaim claim = claim("Notion 在企业权限治理上可能有机会（证据不足，待验证）。", ConfidenceLevel.LOW, List.of());
        run.getClaims().add(claim);

        var findings = evaluator.evaluate("## Report\n\n结论段落 [S1]。", run);

        assertThat(findings)
                .anySatisfy(finding -> {
                    assertThat(finding.getSeverity()).isEqualTo(ReviewSeverity.LOW);
                    assertThat(finding.getCategory()).isEqualTo("claim_missing_evidence");
                    assertThat(finding.getClaimId()).isEqualTo(claim.getId());
                });
    }

    @Test
    void flagsHighConfidenceClaimUsingSnippetOnlyEvidence() {
        AnalysisRun run = runWithSnippetOnlyEvidence();
        AnalysisClaim claim = claim("机会点是优化价格策略和套餐比较。", ConfidenceLevel.HIGH, List.of("S2"));
        run.getClaims().add(claim);

        var findings = evaluator.evaluate("机会点是优化价格策略和套餐比较 [S2]。", run);

        assertThat(findings)
                .anySatisfy(finding -> {
                    assertThat(finding.getSeverity()).isEqualTo(ReviewSeverity.MEDIUM);
                    assertThat(finding.getCategory()).isEqualTo("claim_high_confidence_low_quality_source");
                    assertThat(finding.getClaimId()).isEqualTo(claim.getId());
                    assertThat(finding.getCitationKey()).isEqualTo("S2");
                });
        assertThat(findings)
                .anySatisfy(finding -> {
                    assertThat(finding.getCategory()).isEqualTo("citation_snippet_only");
                    assertThat(finding.getCitationKey()).isEqualTo("S2");
                });
    }

    private AnalysisRun runWithEvidence() {
        AnalysisRun run = new AnalysisRun();
        run.getEvidenceSources().add(new EvidenceSource(
                "S1",
                "Pricing page",
                "https://example.test/pricing",
                "pricing_page",
                "FETCHED",
                "LIVE_FETCHED",
                "价格策略和套餐比较信息。",
                "价格策略和套餐比较信息。",
                "test evidence"
        ));
        run.getEvidenceChunks().add(new EvidenceChunk(
                "S1-C1",
                "S1",
                1,
                "Pricing page",
                "https://example.test/pricing",
                "价格策略 套餐 比较 企业版"
        ));
        return run;
    }

    private AnalysisRun runWithSnippetOnlyEvidence() {
        AnalysisRun run = new AnalysisRun();
        run.getEvidenceSources().add(new EvidenceSource(
                "S2",
                "Search result",
                "https://example.test/search-result",
                "search_result_snippet",
                "FETCH_FAILED",
                "SEARCH_RESULT_SNIPPET",
                "价格策略和套餐比较信息。",
                "",
                "Search result snippet only; page fetch failed."
        ));
        run.getEvidenceChunks().add(new EvidenceChunk(
                "S2-C1",
                "S2",
                1,
                "Search result",
                "https://example.test/search-result",
                "价格策略 套餐 比较"
        ));
        return run;
    }

    private AnalysisClaim claim(String content, ConfidenceLevel confidence, List<String> evidenceIds) {
        AnalysisClaim claim = new AnalysisClaim();
        claim.setType(ClaimType.OPPORTUNITY);
        claim.setContent(content);
        claim.setConfidence(confidence);
        claim.setEvidenceIds(evidenceIds);
        return claim;
    }
}
