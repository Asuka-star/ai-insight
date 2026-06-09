package com.aiinsight.service;

import com.aiinsight.model.enums.ClaimType;
import com.aiinsight.model.enums.ConfidenceLevel;
import com.aiinsight.model.enums.FactType;
import com.aiinsight.model.enums.ReviewSeverity;
import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.model.run.EvidenceChunk;
import com.aiinsight.model.run.EvidenceSource;
import com.aiinsight.model.schema.AnalysisClaim;
import com.aiinsight.model.schema.CompetitorFactSet;
import com.aiinsight.model.schema.ExtractedFact;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CitationCoverageEvaluatorTest {

    private final CitationCoverageEvaluator evaluator = new CitationCoverageEvaluator();

    @Test
    void doesNotUseRulesToFlagClaimParagraphWithoutCitation() {
        String report = """
                # Report

                机会点是构建可复核的 Agent 工作流。

                风险在于资料源不足会导致推断过度 [S1]。
                """;

        var findings = evaluator.evaluate(report);

        assertThat(findings).isEmpty();
    }

    @Test
    void ignoresEvidenceGapAndRecommendationGuidanceWithoutCitation() {
        String report = """
                # Report

                机会点是构建可复核的 Agent 工作流。

                四、 需补充证据

                为确保选型分析的准确性和全面性，建议在后续调研中优先补充以下信息：

                五、 结论与建议

                明确自身定位：建议先判断平台聚合型还是生态闭环型工具更适合当前阶段。
                """;

        var findings = evaluator.evaluate(report);

        assertThat(findings).isEmpty();
    }

    @Test
    void ignoresActionRecommendationIntroWithoutCitation() {
        String report = """
                # Report

                基于对当前信息与风险的判断，以下是明确的行动建议。
                """;

        var findings = evaluator.evaluate(report);

        assertThat(findings).isEmpty();
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
    void leavesWeakCitationSupportToLlmReviewer() {
        AnalysisRun run = runWithEvidence();
        String report = "机会点是强化权限审计和企业安全治理 [S1]。";

        var findings = evaluator.evaluate(report, run);

        assertThat(findings).isEmpty();
    }

    @Test
    void flagsMalformedMarkdownTableRows() {
        AnalysisRun run = new AnalysisRun();
        String report = """
                | 建议 | 理由 | 证据 | 置信度 | 下一步 |
                | --- | --- | --- | --- | --- |
                待验证：| 考虑引入类似 Claude Code 的灵活定价 | 证据不足 | [S8] | HIGH | 复核 |
                """;

        var findings = evaluator.evaluate(report, run);

        assertThat(findings)
                .anySatisfy(finding -> {
                    assertThat(finding.getSeverity()).isEqualTo(ReviewSeverity.HIGH);
                    assertThat(finding.getCategory()).isEqualTo("report_table_malformed");
                    assertThat(finding.getExcerpt()).contains("待验证：|");
                });
    }

    @Test
    void flagsCitationsLeakedIntoMarkdownTableHeaders() {
        AnalysisRun run = new AnalysisRun();
        String report = """
                | 维度 | Cursor | Claude Code | 判断置信度[S5][S9] |
                | --- | --- | --- | --- |
                | 目标用户 | 企业工程团队 | 个人与团队 | HIGH |
                """;

        var findings = evaluator.evaluate(report, run);

        assertThat(findings)
                .anySatisfy(finding -> {
                    assertThat(finding.getSeverity()).isEqualTo(ReviewSeverity.MEDIUM);
                    assertThat(finding.getCategory()).isEqualTo("report_table_header_citation");
                });
    }

    @Test
    void flagsLeakedInternalVerificationStatusCounts() {
        AnalysisRun run = runWithEvidence();
        for (int i = 0; i < 4; i++) {
            AnalysisClaim claim = claim("Supported claim " + i, ConfidenceLevel.HIGH, List.of("S1"));
            claim.setSupportStatus("SUPPORTED");
            run.getClaims().add(claim);
        }
        AnalysisClaim partialClaim = claim("Partial claim", ConfidenceLevel.MEDIUM, List.of("S1"));
        partialClaim.setSupportStatus("PARTIAL");
        run.getClaims().add(partialClaim);
        for (int i = 0; i < 3; i++) {
            AnalysisClaim claim = claim("Unverified claim " + i, ConfidenceLevel.LOW, List.of());
            claim.setSupportStatus("UNVERIFIED");
            claim.setRecommendedPlacement("VALIDATION_BACKLOG");
            run.getClaims().add(claim);
        }
        String report = "基于当前有限的公开资料和文档证据（SUPPORTED=4, UNVERIFIED=3），建议继续补证。";

        var findings = evaluator.evaluate(report, run);

        assertThat(findings)
                .anySatisfy(finding -> {
                    assertThat(finding.getSeverity()).isEqualTo(ReviewSeverity.MEDIUM);
                    assertThat(finding.getCategory()).isEqualTo("report_verification_summary_mismatch");
                    assertThat(finding.getRecommendation()).contains("移除 SUPPORTED/PARTIAL/UNVERIFIED");
                    assertThat(finding.getExcerpt()).contains("SUPPORTED=4");
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
    void allowsEvidenceGapParagraphsWithoutCitation() {
        var findings = evaluator.evaluate("""
                # Report

                本次分析面临的最大风险是证据严重不足，所有结构化结论均应视为待验证假设。

                为弥补上述缺口，建议立即启动补证行动。
                """);

        assertThat(findings).isEmpty();
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
    void skipsStructuredClaimAlreadyIsolatedInValidationBacklog() {
        AnalysisRun run = runWithEvidence();
        AnalysisClaim claim = claim("Notion 在企业权限治理上可能有机会（证据不足，待验证）。", ConfidenceLevel.LOW, List.of());
        claim.setSupportStatus("UNVERIFIED");
        claim.setRecommendedPlacement("VALIDATION_BACKLOG");
        run.getClaims().add(claim);

        var findings = evaluator.evaluate("## Report\n\n结论段落 [S1]。", run);

        assertThat(findings)
                .noneMatch(finding -> "claim_missing_evidence".equals(finding.getCategory())
                        && claim.getId().equals(finding.getClaimId()));
    }

    @Test
    void leavesSnippetOnlyEvidenceQualityToLlmReviewer() {
        AnalysisRun run = runWithSnippetOnlyEvidence();
        AnalysisClaim claim = claim("机会点是优化价格策略和套餐比较。", ConfidenceLevel.HIGH, List.of("S2"));
        run.getClaims().add(claim);

        var findings = evaluator.evaluate("机会点是优化价格策略和套餐比较 [S2]。", run);

        assertThat(findings).isEmpty();
    }

    @Test
    void leavesMarketingOnlySourceQualityToLlmReviewer() {
        AnalysisRun run = new AnalysisRun();
        run.getEvidenceSources().add(new EvidenceSource(
                "S3",
                "Sponsored partner content",
                "https://example.test/sponsored/cursor",
                "public_web",
                "FETCHED",
                "LIVE_FETCHED",
                "价格策略和套餐比较信息。",
                "价格策略和套餐比较信息。",
                "promoted content"
        ));
        run.getEvidenceChunks().add(new EvidenceChunk(
                "S3-C1",
                "S3",
                1,
                "Sponsored partner content",
                "https://example.test/sponsored/cursor",
                "价格策略 套餐 比较"
        ));

        var findings = evaluator.evaluate("机会点是优化价格策略和套餐比较 [S3]。", run);

        assertThat(findings).isEmpty();
    }

    @Test
    void leavesRegionUnavailableSourceQualityToLlmReviewer() {
        AnalysisRun run = new AnalysisRun();
        run.getEvidenceSources().add(new EvidenceSource(
                "S4",
                "App unavailable in region | Claude",
                "https://claude.com/app-unavailable-in-region",
                "official_site",
                "FETCHED",
                "LIVE_FETCHED",
                "MEDIUM",
                "NONE",
                "App unavailable in region.",
                "Claude is not currently available in your region.",
                "robots.txt checked: allowed for public fetch."
        ));

        var findings = evaluator.evaluate("风险在于 Claude Code 存在区域可用性限制 [S4]。", run);

        assertThat(findings).isEmpty();
    }

    @Test
    void leavesPricingSourceStrengthToLlmReviewer() {
        AnalysisRun run = new AnalysisRun();
        EvidenceSource officialPricing = source(
                "S10",
                "Cursor Pricing",
                "https://www.cursor.com/pricing",
                "pricing_page",
                "FIRST_PARTY_OFFICIAL",
                "Cursor Pro pricing is listed on the official pricing page."
        );
        EvidenceSource thirdPartyPricing = source(
                "S11",
                "Cursor pricing comparison",
                "https://example-blog.com/cursor-pricing-comparison",
                "third_party_pricing_reference",
                "THIRD_PARTY_GENERAL",
                "Cursor Pro pricing is $20/month according to this comparison."
        );
        run.getEvidenceSources().addAll(List.of(officialPricing, thirdPartyPricing));
        run.getEvidenceChunks().add(chunk("S10-C1", "S10", "pricing", "FIRST_PARTY_OFFICIAL", "Cursor official pricing page lists Pro pricing."));
        run.getEvidenceChunks().add(chunk("S11-C1", "S11", "pricing", "THIRD_PARTY_GENERAL", "Cursor Pro pricing is $20/month according to this comparison."));
        AnalysisClaim claim = claim("Cursor Pro pricing is $20/month.", ConfidenceLevel.HIGH, List.of("S11"));
        run.getClaims().add(claim);

        var findings = evaluator.evaluate("## Report\n\nSummary only.", run);

        assertThat(findings).isEmpty();
    }

    @Test
    void acceptsOfficialPricingClaimWithPricingChunk() {
        AnalysisRun run = new AnalysisRun();
        EvidenceSource officialPricing = source(
                "S12",
                "Cursor Pricing",
                "https://www.cursor.com/pricing",
                "pricing_page",
                "FIRST_PARTY_OFFICIAL",
                "Cursor Pro pricing is $20/month on the official pricing page."
        );
        run.getEvidenceSources().add(officialPricing);
        run.getEvidenceChunks().add(chunk("S12-C1", "S12", "pricing", "FIRST_PARTY_OFFICIAL", "Cursor Pro pricing is $20/month on the official pricing page."));
        AnalysisClaim claim = claim("Cursor Pro pricing is $20/month.", ConfidenceLevel.HIGH, List.of("S12"));
        run.getClaims().add(claim);

        var findings = evaluator.evaluate("## Report\n\nSummary only.", run);

        assertThat(findings)
                .noneMatch(finding -> "claim_weak_pricing_source".equals(finding.getCategory())
                        || "claim_missing_pricing_source".equals(finding.getCategory()));
    }

    @Test
    void leavesSecuritySourceStrengthToLlmReviewer() {
        AnalysisRun run = new AnalysisRun();
        EvidenceSource community = source(
                "S13",
                "Community security discussion",
                "https://reddit.com/r/tool/comments/security",
                "public_review",
                "COMMUNITY",
                "Users mention SAML SSO and SCIM in an enterprise security discussion."
        );
        run.getEvidenceSources().add(community);
        run.getEvidenceChunks().add(chunk("S13-C1", "S13", "public_review", "COMMUNITY", "Users mention SAML SSO and SCIM in an enterprise security discussion."));
        AnalysisClaim claim = claim("Enterprise security supports SAML SSO and SCIM admin controls.", ConfidenceLevel.HIGH, List.of("S13"));
        run.getClaims().add(claim);

        var findings = evaluator.evaluate("## Report\n\nSummary only.", run);

        assertThat(findings).isEmpty();
    }

    @Test
    void allowsUserSentimentClaimBackedByPublicReview() {
        AnalysisRun run = new AnalysisRun();
        EvidenceSource review = source(
                "S14",
                "G2 reviews",
                "https://www.g2.com/products/example/reviews",
                "public_review",
                "COMMUNITY",
                "Users report onboarding friction and slow setup in reviews."
        );
        run.getEvidenceSources().add(review);
        run.getEvidenceChunks().add(chunk("S14-C1", "S14", "public_review", "COMMUNITY", "Users report onboarding friction and slow setup in reviews."));
        AnalysisClaim claim = claim("Users report onboarding friction in reviews.", ConfidenceLevel.MEDIUM, List.of("S14"));
        run.getClaims().add(claim);

        var findings = evaluator.evaluate("## Report\n\nSummary only.", run);

        assertThat(findings)
                .noneMatch(finding -> "claim_missing_sentiment_source".equals(finding.getCategory()));
    }

    @Test
    void leavesUserSentimentSourceTypeToLlmReviewer() {
        AnalysisRun run = new AnalysisRun();
        EvidenceSource official = source(
                "S15",
                "Vendor product page",
                "https://vendor.example.com/product",
                "official_site",
                "FIRST_PARTY_OFFICIAL",
                "The vendor says teams onboard quickly with guided setup."
        );
        run.getEvidenceSources().add(official);
        run.getEvidenceChunks().add(chunk("S15-C1", "S15", "general_product", "FIRST_PARTY_OFFICIAL", "The vendor says teams onboard quickly with guided setup."));
        AnalysisClaim claim = claim("Users report onboarding friction in reviews.", ConfidenceLevel.MEDIUM, List.of("S15"));
        run.getClaims().add(claim);

        var findings = evaluator.evaluate("## Report\n\nSummary only.", run);

        assertThat(findings).isEmpty();
    }

    @Test
    void leavesInternalEvidenceWordingToLlmReviewer() {
        AnalysisRun run = new AnalysisRun();
        EvidenceSource internal = source(
                "S16",
                "Uploaded interview notes",
                "user-document://s16",
                "user_document",
                "INTERNAL_ONLY",
                "Public market evidence shows Cursor has competitive advantage in enterprise permission governance."
        );
        internal.setSourceQuality("INTERNAL_ONLY");
        run.getEvidenceSources().add(internal);
        run.getEvidenceChunks().add(chunk("S16-C1", "S16", "permission", "INTERNAL_ONLY",
                "Public market evidence shows Cursor has competitive advantage in enterprise permission governance."));
        AnalysisClaim claim = claim(
                "Public market evidence shows Cursor has competitive advantage in enterprise permission governance.",
                ConfidenceLevel.HIGH,
                List.of("S16")
        );
        run.getClaims().add(claim);

        var findings = evaluator.evaluate("## Report\n\nSummary only.", run);

        assertThat(findings).isEmpty();
    }

    @Test
    void allowsUserProvidedClaimWhenWordingIsInternal() {
        AnalysisRun run = new AnalysisRun();
        EvidenceSource internal = source(
                "S17",
                "Uploaded interview notes",
                "user-document://s17",
                "user_document",
                "USER_PROVIDED",
                "Based on user-provided interview notes, enterprise buyers care about permission governance."
        );
        internal.setSourceQuality("INTERNAL_ONLY");
        run.getEvidenceSources().add(internal);
        run.getEvidenceChunks().add(chunk("S17-C1", "S17", "permission", "USER_PROVIDED",
                "Based on user-provided interview notes, enterprise buyers care about permission governance."));
        AnalysisClaim claim = claim(
                "Based on user-provided interview notes, enterprise buyers care about permission governance.",
                ConfidenceLevel.MEDIUM,
                List.of("S17")
        );
        run.getClaims().add(claim);

        var findings = evaluator.evaluate("## Report\n\nSummary only.", run);

        assertThat(findings)
                .noneMatch(finding -> "claim_internal_evidence_presented_as_public".equals(finding.getCategory()));
    }

    @Test
    void allowsPublicMarketClaimWithMixedInternalAndPublicEvidence() {
        AnalysisRun run = new AnalysisRun();
        EvidenceSource internal = source(
                "S18",
                "Uploaded interview notes",
                "user-document://s18",
                "user_document",
                "INTERNAL_ONLY",
                "Public market evidence shows Cursor has competitive advantage in enterprise permission governance."
        );
        internal.setSourceQuality("INTERNAL_ONLY");
        EvidenceSource official = source(
                "S19",
                "Cursor enterprise docs",
                "https://docs.cursor.com/enterprise",
                "product_docs",
                "FIRST_PARTY_OFFICIAL",
                "Public market evidence shows Cursor has competitive advantage in enterprise permission governance."
        );
        run.getEvidenceSources().addAll(List.of(internal, official));
        run.getEvidenceChunks().add(chunk("S18-C1", "S18", "permission", "INTERNAL_ONLY",
                "Public market evidence shows Cursor has competitive advantage in enterprise permission governance."));
        run.getEvidenceChunks().add(chunk("S19-C1", "S19", "permission", "FIRST_PARTY_OFFICIAL",
                "Public market evidence shows Cursor has competitive advantage in enterprise permission governance."));
        AnalysisClaim claim = claim(
                "Public market evidence shows Cursor has competitive advantage in enterprise permission governance.",
                ConfidenceLevel.HIGH,
                List.of("S18", "S19")
        );
        run.getClaims().add(claim);

        var findings = evaluator.evaluate("## Report\n\nSummary only.", run);

        assertThat(findings)
                .noneMatch(finding -> "claim_internal_evidence_presented_as_public".equals(finding.getCategory()));
    }

    @Test
    void leavesReportInternalEvidenceWordingToLlmReviewer() {
        AnalysisRun run = new AnalysisRun();
        EvidenceSource internal = source(
                "S23",
                "Uploaded interview notes",
                "user-document://s23",
                "user_document",
                "INTERNAL_ONLY",
                "Public market evidence shows enterprise buyers care about permission governance."
        );
        internal.setSourceQuality("INTERNAL_ONLY");
        run.getEvidenceSources().add(internal);
        run.getEvidenceChunks().add(chunk("S23-C1", "S23", "permission", "INTERNAL_ONLY",
                "Public market evidence shows enterprise buyers care about permission governance."));

        var findings = evaluator.evaluate(
                "Public market evidence shows enterprise buyers care about permission governance [S23].",
                run
        );

        assertThat(findings).isEmpty();
    }

    @Test
    void leavesExtractedFactSupportToLlmReviewer() {
        AnalysisRun run = new AnalysisRun();
        run.getEvidenceSources().add(source(
                "S20",
                "Cursor Composer docs",
                "https://docs.cursor.com/composer",
                "product_docs",
                "FIRST_PARTY_OFFICIAL",
                "Cursor Composer supports multi-file code editing."
        ));
        run.getEvidenceChunks().add(chunk("S20-C1", "S20", "feature", "FIRST_PARTY_OFFICIAL",
                "Cursor Composer supports multi-file code editing."));
        ExtractedFact fact = fact("F20", "Cursor", FactType.SECURITY,
                "compliance", "Cursor includes SOC 2 enterprise compliance controls.", List.of("S20"), List.of("S20-C1"));
        run.getCompetitorFactSets().add(factSet("Cursor", fact));
        AnalysisClaim claim = claim("Cursor includes SOC 2 enterprise compliance controls.", ConfidenceLevel.HIGH, List.of("S20"));
        claim.getFactIds().add("F20");
        run.getClaims().add(claim);

        var findings = evaluator.evaluate("## Report\n\nSummary only.", run);

        assertThat(findings).isEmpty();
    }

    @Test
    void leavesUnreferencedExtractedFactSupportToLlmReviewer() {
        AnalysisRun run = new AnalysisRun();
        run.getEvidenceSources().add(source(
                "S22",
                "Cursor Composer docs",
                "https://docs.cursor.com/composer",
                "product_docs",
                "FIRST_PARTY_OFFICIAL",
                "Cursor Composer supports multi-file code editing."
        ));
        run.getEvidenceChunks().add(chunk("S22-C1", "S22", "feature", "FIRST_PARTY_OFFICIAL",
                "Cursor Composer supports multi-file code editing."));
        ExtractedFact fact = fact("F22", "Cursor", FactType.SECURITY,
                "compliance", "Cursor includes SOC 2 enterprise compliance controls.", List.of("S22"), List.of("S22-C1"));
        run.getCompetitorFactSets().add(factSet("Cursor", fact));

        var findings = evaluator.evaluate("## Report\n\nSummary only.", run);

        assertThat(findings).isEmpty();
    }

    @Test
    void doesNotGroupSemanticFactSupportIssuesInRuleLayer() {
        AnalysisRun run = new AnalysisRun();
        run.getEvidenceSources().add(source(
                "S40",
                "Cursor Composer docs",
                "https://docs.cursor.com/composer",
                "product_docs",
                "FIRST_PARTY_OFFICIAL",
                "Cursor Composer supports multi-file code editing."
        ));
        run.getEvidenceSources().add(source(
                "S41",
                "Cursor pricing",
                "https://cursor.com/pricing",
                "pricing_page",
                "FIRST_PARTY_OFFICIAL",
                "Cursor offers Team and Business pricing tiers."
        ));
        run.getEvidenceChunks().add(chunk("S40-C1", "S40", "feature", "FIRST_PARTY_OFFICIAL",
                "Cursor Composer supports multi-file code editing."));
        run.getEvidenceChunks().add(chunk("S41-C1", "S41", "pricing", "FIRST_PARTY_OFFICIAL",
                "Cursor offers Team and Business pricing tiers."));
        ExtractedFact fact = fact("F40", "Cursor", FactType.SECURITY,
                "compliance", "Cursor includes SOC 2 enterprise compliance controls.", List.of("S40", "S41"), List.of("S40-C1", "S41-C1"));
        run.getCompetitorFactSets().add(factSet("Cursor", fact));

        var findings = evaluator.evaluate("## Report\n\nSummary only.", run);

        assertThat(findings).isEmpty();
    }

    @Test
    void leavesPartialWeakFactBindingToLlmReviewer() {
        AnalysisRun run = new AnalysisRun();
        run.getEvidenceSources().add(source(
                "S42",
                "Cursor security docs",
                "https://docs.cursor.com/security",
                "security_docs",
                "FIRST_PARTY_OFFICIAL",
                "Cursor includes SOC 2 enterprise compliance controls."
        ));
        run.getEvidenceSources().add(source(
                "S43",
                "Cursor Composer docs",
                "https://docs.cursor.com/composer",
                "product_docs",
                "FIRST_PARTY_OFFICIAL",
                "Cursor Composer supports multi-file code editing."
        ));
        run.getEvidenceChunks().add(chunk("S42-C1", "S42", "security", "FIRST_PARTY_OFFICIAL",
                "Cursor includes SOC 2 enterprise compliance controls."));
        run.getEvidenceChunks().add(chunk("S43-C1", "S43", "feature", "FIRST_PARTY_OFFICIAL",
                "Cursor Composer supports multi-file code editing."));
        ExtractedFact fact = fact("F42", "Cursor", FactType.SECURITY,
                "compliance", "Cursor includes SOC 2 enterprise compliance controls.", List.of("S42", "S43"), List.of("S42-C1", "S43-C1"));
        run.getCompetitorFactSets().add(factSet("Cursor", fact));

        var findings = evaluator.evaluate("## Report\n\nSummary only.", run);

        assertThat(findings).isEmpty();
    }

    @Test
    void acceptsCrossLingualOfficialEvidenceForChineseExtractedFact() {
        AnalysisRun run = new AnalysisRun();
        run.getEvidenceSources().add(source(
                "S7",
                "Claude Code product page",
                "https://claude.com/product/claude-code",
                "official_site",
                "FIRST_PARTY_OFFICIAL",
                "Build, debug, and ship from your terminal, IDE, Slack, or the web."
        ));
        run.getEvidenceChunks().add(chunk("S7-C1", "S7", "feature", "FIRST_PARTY_OFFICIAL",
                "Build, debug, and ship from your terminal, IDE, Slack, or the web."));
        ExtractedFact fact = fact("F7", "Claude Code", FactType.FEATURE,
                "integration", "终端和IDE集成: 支持从终端和IDE直接使用Claude Code构建、调试和部署。", List.of("S7"), List.of("S7-C1"));
        run.getCompetitorFactSets().add(factSet("Claude Code", fact));

        var findings = evaluator.evaluate("## Report\n\nSummary only.", run);

        assertThat(findings)
                .noneMatch(finding -> "fact_unsupported_by_evidence".equals(finding.getCategory())
                        && "F7".equals(finding.getFactId()));
    }

    @Test
    void leavesClaimFactOverInterpretationToLlmReviewer() {
        AnalysisRun run = new AnalysisRun();
        run.getEvidenceSources().add(source(
                "S21",
                "Cursor Composer docs",
                "https://docs.cursor.com/composer",
                "product_docs",
                "FIRST_PARTY_OFFICIAL",
                "Cursor Composer supports multi-file code editing."
        ));
        run.getEvidenceChunks().add(chunk("S21-C1", "S21", "feature", "FIRST_PARTY_OFFICIAL",
                "Cursor Composer supports multi-file code editing."));
        ExtractedFact fact = fact("F21", "Cursor", FactType.FEATURE,
                "composer", "Cursor Composer supports multi-file code editing.", List.of("S21"), List.of("S21-C1"));
        run.getCompetitorFactSets().add(factSet("Cursor", fact));
        AnalysisClaim claim = claim("Cursor is the best enterprise governance platform.", ConfidenceLevel.HIGH, List.of("S21"));
        claim.getFactIds().add("F21");
        run.getClaims().add(claim);

        var findings = evaluator.evaluate("## Report\n\nSummary only.", run);

        assertThat(findings).isEmpty();
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

    private CompetitorFactSet factSet(String competitorName, ExtractedFact fact) {
        CompetitorFactSet factSet = new CompetitorFactSet();
        factSet.setCompetitorName(competitorName);
        factSet.getFacts().add(fact);
        return factSet;
    }

    private ExtractedFact fact(String id,
                               String competitorName,
                               FactType type,
                               String attribute,
                               String value,
                               List<String> evidenceIds,
                               List<String> chunkKeys) {
        ExtractedFact fact = new ExtractedFact();
        fact.setId(id);
        fact.setCompetitorName(competitorName);
        fact.setFactType(type);
        fact.setAttribute(attribute);
        fact.setValue(value);
        fact.setEvidenceIds(evidenceIds);
        fact.setChunkKeys(chunkKeys);
        fact.setExtractionConfidence("HIGH");
        return fact;
    }

    private EvidenceSource source(String citationKey,
                                  String title,
                                  String url,
                                  String sourceType,
                                  String authority,
                                  String text) {
        EvidenceSource source = new EvidenceSource(
                citationKey,
                title,
                url,
                sourceType,
                "FETCHED",
                "LIVE_FETCHED",
                "HIGH",
                "NONE",
                text,
                text,
                "test evidence"
        );
        source.setSourceAuthority(authority);
        return source;
    }

    private EvidenceChunk chunk(String chunkKey, String sourceCitationKey, String contentKind, String authority, String text) {
        EvidenceChunk chunk = new EvidenceChunk(
                chunkKey,
                sourceCitationKey,
                1,
                "Evidence chunk",
                "https://example.test/evidence",
                text
        );
        chunk.setContentKind(contentKind);
        chunk.setSourceAuthority(authority);
        chunk.setSourceQuality("HIGH");
        return chunk;
    }
}
