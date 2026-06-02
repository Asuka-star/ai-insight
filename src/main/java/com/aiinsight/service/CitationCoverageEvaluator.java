package com.aiinsight.service;

import com.aiinsight.model.review.ReviewFinding;
import com.aiinsight.model.enums.ConfidenceLevel;
import com.aiinsight.model.enums.ReviewSeverity;
import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.model.run.EvidenceChunk;
import com.aiinsight.model.run.EvidenceSource;
import com.aiinsight.model.schema.AnalysisClaim;
import com.aiinsight.model.schema.ExtractedFact;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.stream.Collectors;

@Component
public class CitationCoverageEvaluator {

    // MVP 阶段强制使用 [S1]、[S2] 这种证据编号，后续可升级为 claimId -> evidenceId 映射。
    private static final Pattern CITATION_PATTERN = Pattern.compile("\\[S\\d+]");
    private static final Pattern CITATION_KEY_PATTERN = Pattern.compile("\\[(S\\d+)]");

    public List<ReviewFinding> evaluate(String reportContent) {
        return evaluate(reportContent, null);
    }

    public List<ReviewFinding> evaluate(String reportContent, AnalysisRun run) {
        List<ReviewFinding> findings = new ArrayList<>();
        int paragraphIndex = 0;
        String currentSection = "";
        for (String paragraph : reportContent.split("\\R\\R+")) {
            String trimmed = paragraph.trim();
            String section = sectionHeading(trimmed);
            if (StringUtils.hasText(section)) {
                currentSection = section;
            }
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("##") || StringUtils.hasText(section)) {
                paragraphIndex++;
                continue;
            }
            // 先用确定性规则兜底，保证即使 LLM 质检漏判也能抓住“无引用结论”。
            if (looksLikeClaim(trimmed) && !CITATION_PATTERN.matcher(trimmed).find()
                    && !allowsMissingCitation(trimmed, currentSection)) {
                ReviewFinding finding = new ReviewFinding(
                        ReviewSeverity.HIGH,
                        "citation_missing",
                        "发现未绑定引用的结论段落: " + abbreviate(trimmed),
                        "为该结论补充来源片段，或降级为待验证假设。"
                );
                finding.setParagraphIndex(paragraphIndex);
                finding.setExcerpt(trimmed);
                findings.add(finding);
            }
            if (looksLikeClaim(trimmed) && run != null && CITATION_PATTERN.matcher(trimmed).find()) {
                findings.addAll(validateCitationSupport(trimmed, paragraphIndex, run));
            }
            paragraphIndex++;
        }
        if (run != null) {
            findings.addAll(validateStructuredClaims(run));
        }
        return findings;
    }

    private String sectionHeading(String paragraph) {
        if (!StringUtils.hasText(paragraph)) {
            return "";
        }
        String normalized = paragraph.replaceAll("\\s+", "");
        if (normalized.matches("^#{1,6}.+")) {
            return normalized.replaceFirst("^#{1,6}", "");
        }
        if (normalized.matches("^[一二三四五六七八九十]+[、.．].{1,40}$")
                || normalized.matches("^\\d+\\.\\d+(?:\\.\\d+)*.{1,40}$")) {
            return normalized;
        }
        if (normalized.matches("^(复核结论|质检问题摘要|人工复核建议)$")) {
            return normalized;
        }
        return "";
    }

    private boolean allowsMissingCitation(String paragraph, String currentSection) {
        String section = currentSection == null ? "" : currentSection;
        if (containsAny(section, "需补充证据", "证据覆盖缺口", "结论与建议", "人工复核建议", "复核结论", "质检问题摘要")) {
            return true;
        }
        String normalized = paragraph.replaceAll("\\s+", "");
        return containsAny(normalized,
                "建议在后续调研",
                "建议补充",
                "需补充",
                "人工复核",
                "优先补充证据",
                "报告依据说明");
    }

    private List<ReviewFinding> validateCitationSupport(String paragraph, int paragraphIndex, AnalysisRun run) {
        List<ReviewFinding> findings = new ArrayList<>();
        // 表格通常一行承载多条竞品比较，关键词重合会被列名/分隔符干扰；
        // 当前先跳过弱支撑启发式，避免矩阵类 artifact 被大量误报。
        if (paragraph.contains("| ---") || paragraph.lines().filter(line -> line.trim().startsWith("|")).count() >= 2) {
            return findings;
        }
        Set<String> known = run.getEvidenceSources().stream()
                .map(EvidenceSource::getCitationKey)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> checkedCitationKeys = new LinkedHashSet<>();
        Matcher matcher = CITATION_KEY_PATTERN.matcher(paragraph);
        while (matcher.find()) {
            String citationKey = matcher.group(1);
            if (!checkedCitationKeys.add(citationKey)) {
                continue;
            }
            if (!known.contains(citationKey)) {
                ReviewFinding finding = new ReviewFinding(
                        ReviewSeverity.HIGH,
                        "citation_unknown",
                        "报告引用了不存在的证据编号: [" + citationKey + "]",
                        "删除该引用，或补充对应证据来源后再生成报告。"
                );
                finding.setCitationKey(citationKey);
                finding.setParagraphIndex(paragraphIndex);
                finding.setExcerpt(paragraph);
                findings.add(finding);
                continue;
            }
            if (!citationSupportsParagraph(paragraph, citationKey, run)) {
                ReviewFinding finding = new ReviewFinding(
                        ReviewSeverity.MEDIUM,
                        "citation_weak_support",
                        "引用 [" + citationKey + "] 与结论段落的关键词重合不足，可能存在弱支撑。",
                        "请改用更相关的证据片段，或将该结论降级为待验证假设。"
                );
                finding.setCitationKey(citationKey);
                finding.setParagraphIndex(paragraphIndex);
                finding.setExcerpt(paragraph);
                findings.add(finding);
            }
            SourceQualityRisk sourceRisk = sourceQualityRisk(sourceByCitationKey(run, citationKey));
            if (sourceRisk != null) {
                ReviewFinding finding = new ReviewFinding(
                        sourceRisk.severity(),
                        sourceRisk.category(),
                        "引用 [" + citationKey + "] 的来源质量偏弱: " + sourceRisk.reason(),
                        sourceRisk.recommendation()
                );
                finding.setCitationKey(citationKey);
                finding.setParagraphIndex(paragraphIndex);
                finding.setExcerpt(paragraph);
                findings.add(finding);
            }
        }
        return findings;
    }

    private List<ReviewFinding> validateStructuredClaims(AnalysisRun run) {
        List<ReviewFinding> findings = new ArrayList<>();
        // 报告文本可能被 Writer 改写，结构化 claim 才是 Analyst 的“结论原子”。
        // 这里直接检查 claim 的证据绑定和置信度，能发现报告层面不一定暴露的问题。
        Set<String> known = run.getEvidenceSources().stream()
                .map(EvidenceSource::getCitationKey)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        findings.addAll(validateExtractedFacts(run, known));
        for (AnalysisClaim claim : run.getClaims()) {
            if (claim == null || !StringUtils.hasText(claim.getContent())) {
                continue;
            }
            List<String> evidenceIds = claim.getEvidenceIds() == null ? List.of() : claim.getEvidenceIds();
            if (evidenceIds.isEmpty()) {
                ReviewFinding finding = new ReviewFinding(
                        containsUncertaintyMarker(claim.getContent()) ? ReviewSeverity.LOW : ReviewSeverity.HIGH,
                        "claim_missing_evidence",
                        "结构化结论未绑定证据: " + abbreviate(claim.getContent()),
                        "为该 claim 补充 evidenceIds，或在内容中明确标注“待验证/证据不足”。"
                );
                finding.setClaimId(claim.getId());
                finding.setExcerpt(claim.getContent());
                findings.add(finding);
                continue;
            }
            for (String evidenceId : evidenceIds) {
                if (!known.contains(evidenceId)) {
                    ReviewFinding finding = new ReviewFinding(
                            ReviewSeverity.HIGH,
                            "claim_unknown_evidence",
                            "结构化结论引用了不存在的证据编号: [" + evidenceId + "]",
                            "删除该 evidenceId，或补充对应证据来源后再运行 Analyst/Writer。"
                    );
                    finding.setClaimId(claim.getId());
                    finding.setCitationKey(evidenceId);
                    finding.setExcerpt(claim.getContent());
                    findings.add(finding);
                    continue;
                }
                if (!citationSupportsParagraph(claim.getContent(), evidenceId, run)) {
                    ReviewFinding finding = new ReviewFinding(
                            ReviewSeverity.MEDIUM,
                            "claim_weak_support",
                            "结构化结论与证据 [" + evidenceId + "] 的关键词重合不足，可能存在弱支撑。",
                            "请重新选择更贴近该 claim 的证据，或降低结论置信度。"
                    );
                    finding.setClaimId(claim.getId());
                    finding.setCitationKey(evidenceId);
                    finding.setExcerpt(claim.getContent());
                    findings.add(finding);
                }
                SourceQualityRisk sourceRisk = sourceQualityRisk(sourceByCitationKey(run, evidenceId));
                if (sourceRisk != null && claim.getConfidence() == ConfidenceLevel.HIGH) {
                    ReviewFinding finding = new ReviewFinding(
                            ReviewSeverity.MEDIUM,
                            "claim_high_confidence_low_quality_source",
                            "高置信结论依赖低质量来源 [" + evidenceId + "]: " + sourceRisk.reason(),
                            "高置信结论应优先绑定已抓取正文、官方页面或用户授权的一手资料。"
                    );
                    finding.setClaimId(claim.getId());
                    finding.setCitationKey(evidenceId);
                    finding.setExcerpt(claim.getContent());
                    findings.add(finding);
                }
                ClaimEvidencePolicyRisk policyRisk = claimEvidencePolicyRisk(
                        claim,
                        sourceByCitationKey(run, evidenceId),
                        evidenceId,
                        run
                );
                if (policyRisk != null) {
                    ReviewFinding finding = new ReviewFinding(
                            policyRisk.severity(),
                            policyRisk.category(),
                            policyRisk.reason(),
                            policyRisk.recommendation()
                    );
                    finding.setClaimId(claim.getId());
                    finding.setCitationKey(evidenceId);
                    finding.setExcerpt(claim.getContent());
                    findings.add(finding);
                }
            }
            findings.addAll(validateClaimFacts(run, claim, known));
            if (claim.getConfidence() == ConfidenceLevel.HIGH && containsUncertaintyMarker(claim.getContent())) {
                ReviewFinding finding = new ReviewFinding(
                        ReviewSeverity.LOW,
                        "claim_confidence_mismatch",
                        "结论内容标注为待验证，但置信度仍为 HIGH: " + abbreviate(claim.getContent()),
                        "将该 claim 的置信度降为 LOW/MEDIUM，或补足证据后移除待验证表述。"
                );
                finding.setClaimId(claim.getId());
                finding.setExcerpt(claim.getContent());
                findings.add(finding);
            }
        }
        return findings;
    }

    private List<ReviewFinding> validateExtractedFacts(AnalysisRun run, Set<String> knownEvidenceIds) {
        List<ReviewFinding> findings = new ArrayList<>();
        Map<String, String> claimIdByFactId = claimIdByFactId(run);
        Set<String> knownChunkKeys = run.getEvidenceChunks().stream()
                .map(EvidenceChunk::getChunkKey)
                .filter(StringUtils::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        int weakUnsupportedFactFindings = 0;
        for (ExtractedFact fact : factsById(run).values()) {
            List<String> factEvidenceIds = fact.getEvidenceIds() == null ? List.of() : fact.getEvidenceIds();
            String claimId = claimIdByFactId.get(fact.getId());
            if (factEvidenceIds.isEmpty()) {
                findings.add(factFinding(
                        ReviewSeverity.HIGH,
                        "fact_missing_evidence",
                        "Extracted fact has no evidenceIds: " + fact.getId(),
                        "Rerun Extractor and only emit facts that carry evidenceIds/chunkKeys, or move the item to unknowns.",
                        claimId,
                        fact,
                        null
                ));
                continue;
            }
            for (String chunkKey : fact.getChunkKeys() == null ? List.<String>of() : fact.getChunkKeys()) {
                if (StringUtils.hasText(chunkKey) && !knownChunkKeys.contains(chunkKey)) {
                    ReviewFinding finding = factFinding(
                            ReviewSeverity.MEDIUM,
                            "fact_unknown_chunk",
                            "Extracted fact references an unknown evidence chunk key: " + chunkKey,
                            "Rerun Extractor after chunking, or remove stale chunkKeys from the fact binding.",
                            claimId,
                            fact,
                            null
                    );
                    finding.setChunkKey(chunkKey);
                    findings.add(finding);
                }
            }
            for (String evidenceId : factEvidenceIds) {
                if (!knownEvidenceIds.contains(evidenceId)) {
                    findings.add(factFinding(
                            ReviewSeverity.HIGH,
                            "fact_unknown_evidence",
                            "Extracted fact references an unknown evidence id: [" + evidenceId + "]",
                            "Rerun Extractor and bind the fact to existing evidence, or drop the unsupported fact.",
                            claimId,
                            fact,
                            evidenceId
                    ));
                    continue;
                }
                if (StringUtils.hasText(fact.getValue()) && !citationSupportsParagraph(fact.getValue(), evidenceId, run)) {
                    ReviewSeverity severity = shouldBlockUnsupportedFact(fact)
                            ? ReviewSeverity.HIGH
                            : ReviewSeverity.MEDIUM;
                    if (severity == ReviewSeverity.HIGH || weakUnsupportedFactFindings < 6) {
                        findings.add(factFinding(
                                severity,
                                "fact_unsupported_by_evidence",
                                "Extracted fact value is not supported by its evidence [" + evidenceId + "]: " + abbreviate(fact.getValue()),
                                "Rerun Extractor to correct the fact value/evidence binding, or move the unsupported field to unknowns.",
                                claimId,
                                fact,
                                evidenceId
                        ));
                    }
                    if (severity != ReviewSeverity.HIGH) {
                        weakUnsupportedFactFindings++;
                    }
                }
            }
        }
        return findings;
    }

    private Map<String, String> claimIdByFactId(AnalysisRun run) {
        return run.getClaims().stream()
                .filter(claim -> claim != null && StringUtils.hasText(claim.getId()))
                .flatMap(claim -> (claim.getFactIds() == null ? List.<String>of() : claim.getFactIds()).stream()
                        .filter(StringUtils::hasText)
                        .map(factId -> Map.entry(factId, claim.getId())))
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (first, ignored) -> first
                ));
    }

    private List<ReviewFinding> validateClaimFacts(AnalysisRun run, AnalysisClaim claim, Set<String> knownEvidenceIds) {
        List<ReviewFinding> findings = new ArrayList<>();
        Map<String, ExtractedFact> factsById = factsById(run);
        List<String> factIds = claim.getFactIds() == null ? List.of() : claim.getFactIds();
        if (factIds.isEmpty()) {
            if (!factsById.isEmpty()
                    && claim.getConfidence() != ConfidenceLevel.LOW
                    && !containsUncertaintyMarker(claim.getContent())) {
                ReviewFinding finding = new ReviewFinding(
                        ReviewSeverity.MEDIUM,
                        "claim_missing_fact_binding",
                        "Structured claim is backed by evidenceIds but not by extracted factIds: " + abbreviate(claim.getContent()),
                        "Bind the claim to relevant ExtractedFact ids, or keep the claim tentative if no extracted fact supports it."
                );
                finding.setClaimId(claim.getId());
                finding.setExcerpt(claim.getContent());
                findings.add(finding);
            }
            return findings;
        }

        List<ExtractedFact> boundFacts = new ArrayList<>();
        for (String factId : factIds) {
            ExtractedFact fact = factsById.get(factId);
            if (fact == null) {
                ReviewFinding finding = new ReviewFinding(
                        ReviewSeverity.HIGH,
                        "claim_unknown_fact",
                        "Structured claim references an unknown extracted fact id: " + factId,
                        "Remove the stale factId or rerun Analyst after Extractor regenerates the fact layer."
                );
                finding.setClaimId(claim.getId());
                finding.setFactId(factId);
                finding.setExcerpt(claim.getContent());
                findings.add(finding);
                continue;
            }
            boundFacts.add(fact);
        }

        if (claim.getConfidence() == ConfidenceLevel.HIGH && !claimSupportedByFacts(claim, boundFacts)) {
            ReviewFinding finding = new ReviewFinding(
                    ReviewSeverity.HIGH,
                    "claim_fact_mismatch",
                    "High-confidence claim over-interprets or does not align with its bound extracted facts: " + abbreviate(claim.getContent()),
                    "Rerun Analyst to rewrite the claim from bound facts, bind more relevant facts, or downgrade confidence."
            );
            finding.setClaimId(claim.getId());
            finding.setExcerpt(claim.getContent());
            findings.add(finding);
        }
        return findings;
    }

    private Map<String, ExtractedFact> factsById(AnalysisRun run) {
        return run.getCompetitorFactSets().stream()
                .filter(factSet -> factSet != null && factSet.getFacts() != null)
                .flatMap(factSet -> factSet.getFacts().stream())
                .filter(fact -> fact != null)
                .filter(fact -> StringUtils.hasText(fact.getId()))
                .collect(Collectors.toMap(
                        ExtractedFact::getId,
                        fact -> fact,
                        (left, right) -> left
                ));
    }

    private ReviewFinding factFinding(ReviewSeverity severity,
                                      String category,
                                      String message,
                                      String recommendation,
                                      String claimId,
                                      ExtractedFact fact,
                                      String evidenceId) {
        ReviewFinding finding = new ReviewFinding(severity, category, message, recommendation);
        finding.setClaimId(claimId);
        finding.setFactId(fact.getId());
        finding.setCitationKey(evidenceId);
        finding.setExcerpt(fact.getValue());
        if (fact.getChunkKeys() != null && !fact.getChunkKeys().isEmpty()) {
            finding.setChunkKey(fact.getChunkKeys().get(0));
        }
        return finding;
    }

    private boolean claimSupportedByFacts(AnalysisClaim claim, List<ExtractedFact> facts) {
        if (facts.isEmpty()) {
            return true;
        }
        Set<String> claimTerms = terms(claim.getContent());
        if (claimTerms.isEmpty()) {
            return true;
        }
        String factText = facts.stream()
                .map(fact -> "%s %s %s %s".formatted(
                        fact.getCompetitorName(),
                        fact.getFactType(),
                        fact.getAttribute(),
                        fact.getValue()
                ))
                .collect(Collectors.joining(" "));
        Set<String> factTerms = terms(factText);
        long overlap = claimTerms.stream().filter(factTerms::contains).count();
        return overlap >= Math.min(2, claimTerms.size());
    }

    private boolean shouldBlockUnsupportedFact(ExtractedFact fact) {
        if (fact == null) {
            return false;
        }
        String confidence = normalizeUpper(fact.getExtractionConfidence());
        if (!"HIGH".equals(confidence)) {
            return false;
        }
        String attribute = normalizeLower(fact.getAttribute());
        if (Set.of(
                "positioning",
                "target_user",
                "observed_advantage",
                "observed_limitation",
                "persona",
                "feature",
                "pricing_strategy"
        ).contains(attribute)) {
            return false;
        }
        return fact.getValue() != null && fact.getValue().length() <= 220;
    }

    private boolean citationSupportsParagraph(String paragraph, String citationKey, AnalysisRun run) {
        Set<String> claimTerms = terms(paragraph.replaceAll("\\[S\\d+]", " "));
        if (claimTerms.isEmpty()) {
            return true;
        }
        String evidenceText = run.getEvidenceSources().stream()
                .filter(source -> citationKey.equals(source.getCitationKey()))
                .map(this::sourceText)
                .collect(Collectors.joining(" "));
        evidenceText += " " + run.getEvidenceChunks().stream()
                .filter(chunk -> citationKey.equals(chunk.getSourceCitationKey()))
                .map(EvidenceChunk::getText)
                .collect(Collectors.joining(" "));
        Set<String> evidenceTerms = terms(evidenceText);
        long overlap = claimTerms.stream().filter(evidenceTerms::contains).count();
        return overlap >= Math.min(2, claimTerms.size());
    }

    private Set<String> terms(String text) {
        Set<String> terms = new LinkedHashSet<>();
        if (!StringUtils.hasText(text)) {
            return terms;
        }
        String normalized = text.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{IsHan}a-z0-9]+", " ")
                .trim();
        for (String part : normalized.split("\\s+")) {
            if (part.length() >= 2 && !isStopWord(part)) {
                terms.add(part);
            }
        }
        String chineseOnly = normalized.replaceAll("[^\\p{IsHan}]", "");
        for (int i = 0; i < chineseOnly.length() - 1; i++) {
            String term = chineseOnly.substring(i, i + 2);
            if (!isStopWord(term)) {
                terms.add(term);
            }
        }
        return terms;
    }

    private boolean isStopWord(String term) {
        return Set.of("报告", "结论", "建议", "风险", "机会", "优势", "弱势", "可以", "需要", "进行", "当前", "the", "and", "for", "with")
                .contains(term);
    }

    private String sourceText(EvidenceSource source) {
        if (source == null) {
            return "";
        }
        return "%s %s %s %s".formatted(
                nullToEmpty(source.getTitle()),
                nullToEmpty(source.getSourceType()),
                nullToEmpty(source.getSnippet()),
                nullToEmpty(source.getRawText())
        );
    }

    private EvidenceSource sourceByCitationKey(AnalysisRun run, String citationKey) {
        return run.getEvidenceSources().stream()
                .filter(source -> citationKey.equals(source.getCitationKey()))
                .findFirst()
                .orElse(null);
    }

    private SourceQualityRisk sourceQualityRisk(EvidenceSource source) {
        if (source == null) {
            return null;
        }
        // 来源质量不直接阻断流程，但会影响高置信 claim 的可信度。
        // snippet-only / robots / fetch failed 都应被标成 Reviewer 提醒。
        String sourceType = normalize(source.getSourceType());
        String collectionStatus = normalize(source.getCollectionStatus());
        String freshness = normalize(source.getFreshness());
        String complianceNote = normalize(source.getComplianceNote());
        String searchable = String.join(" ",
                nullToEmpty(source.getTitle()),
                nullToEmpty(source.getUrl()),
                nullToEmpty(source.getSnippet()),
                nullToEmpty(source.getRawText()),
                nullToEmpty(source.getFailureReason()),
                nullToEmpty(source.getComplianceNote())
        ).toLowerCase(Locale.ROOT);
        if (containsAny(searchable,
                "region_unavailable_page",
                "app unavailable in region",
                "unavailable in your region",
                "not available in your region",
                "not currently available in your region",
                "service is not available in your region")) {
            return new SourceQualityRisk(
                    ReviewSeverity.HIGH,
                    "citation_region_unavailable_source",
                    "来源只是区域不可用或占位说明，不能作为产品能力、定价或部署结论的证据。",
                    "删除该引用，改用官方文档、官方价格页、可访问的产品页面或供应商确认材料。"
            );
        }
        if ("BLOCKED_BY_ROBOTS".equals(collectionStatus)) {
            return new SourceQualityRisk(
                    ReviewSeverity.MEDIUM,
                    "citation_blocked_source",
                    "网页受 robots 策略限制，未采集到正文。",
                    "请补充可访问的公开来源、官方文档或用户授权资料。"
            );
        }
        if ("FETCH_FAILED".equals(collectionStatus) || "SEARCH_RESULT_SNIPPET".equals(freshness)
                || "search_result_snippet".equals(sourceType) || complianceNote.contains("snippet only")) {
            return new SourceQualityRisk(
                    ReviewSeverity.MEDIUM,
                    "citation_snippet_only",
                    "当前只保留搜索摘要或抓取失败说明，不等于完整网页正文。",
                    "请补采原始页面正文，或在报告中标注该结论需要人工复核。"
            );
        }
        if (likelyMarketingOnlySource(source)) {
            return new SourceQualityRisk(
                    ReviewSeverity.LOW,
                    "citation_marketing_only_source",
                    "来源带有明显营销、推广或赞助内容特征，不能单独支撑关键结论。",
                    "请优先补充官网文档、定价页、更新日志、技术博客、权威媒体或行业报告作为支撑。"
            );
        }
        if (!StringUtils.hasText(source.getRawText()) && StringUtils.hasText(source.getSnippet())) {
            return new SourceQualityRisk(
                    ReviewSeverity.LOW,
                    "citation_thin_source",
                    "来源只有短摘要，没有可复核正文。",
                    "建议补充正文、截图摘要或用户授权材料来增强可复核性。"
            );
        }
        return null;
    }

    private ClaimEvidencePolicyRisk claimEvidencePolicyRisk(AnalysisClaim claim,
                                                            EvidenceSource source,
                                                            String evidenceId,
                                                            AnalysisRun run) {
        if (claim == null || source == null || !StringUtils.hasText(claim.getContent())) {
            return null;
        }
        String need = claimEvidenceNeed(claim);
        if ("pricing".equals(need)) {
            if (strongPricingEvidence(source, evidenceId, run)) {
                return null;
            }
            if (weakPricingEvidence(source, evidenceId, run) && firstPartyPricingEvidenceAvailable(run)) {
                return new ClaimEvidencePolicyRisk(
                        claim.getConfidence() == ConfidenceLevel.HIGH ? ReviewSeverity.HIGH : ReviewSeverity.MEDIUM,
                        "claim_weak_pricing_source",
                        "Pricing claim cites secondary pricing evidence [" + evidenceId + "] while first-party pricing evidence is available.",
                        "Use FIRST_PARTY_OFFICIAL pricing_page evidence or a first-party pricing chunk; otherwise downgrade the claim and mark the price as needing verification."
                );
            }
            if (!chunkHasKind(run, evidenceId, "pricing") && !sourceTypeIs(source, "pricing_page")) {
                return new ClaimEvidencePolicyRisk(
                        ReviewSeverity.MEDIUM,
                        "claim_missing_pricing_source",
                        "Pricing claim cites evidence [" + evidenceId + "] that is not marked as pricing content.",
                        "Bind this claim to pricing_page/pricing chunks, or rewrite it as an unverified pricing assumption."
                );
            }
            return null;
        }
        if ("security".equals(need)) {
            if (strongSecurityEvidence(source, evidenceId, run)) {
                return null;
            }
            return new ClaimEvidencePolicyRisk(
                    claim.getConfidence() == ConfidenceLevel.HIGH ? ReviewSeverity.HIGH : ReviewSeverity.MEDIUM,
                    "claim_weak_security_source",
                    "Security or permission claim cites evidence [" + evidenceId + "] without first-party docs, security docs, or security/permission chunks.",
                    "Use official security_docs/product_docs/official_site evidence or first-party security/permission chunks; otherwise reduce confidence."
            );
        }
        if ("sentiment".equals(need)) {
            if (sentimentEvidence(source, evidenceId, run)) {
                return null;
            }
            return new ClaimEvidencePolicyRisk(
                    ReviewSeverity.MEDIUM,
                    "claim_missing_sentiment_source",
                    "User sentiment claim cites evidence [" + evidenceId + "] that is not a review, community, interview, survey, or user-provided source.",
                    "Use public_review/community_discussion/user_interview/user_survey evidence, or rewrite the statement as vendor positioning instead of user sentiment."
            );
        }
        return null;
    }

    private String claimEvidenceNeed(AnalysisClaim claim) {
        String text = normalizeLower("%s %s".formatted(claim.getType(), claim.getContent()));
        if (containsAny(text,
                "pricing", "price", "plan", "billing", "cost", "$", "free plan", "enterprise plan",
                "\u4ef7\u683c", "\u5b9a\u4ef7", "\u5957\u9910", "\u4ed8\u8d39", "\u514d\u8d39\u7248", "\u5546\u4e1a\u6a21\u5f0f")) {
            return "pricing";
        }
        if (containsAny(text,
                "security", "permission", "permissions", "compliance", "privacy", "saml", "sso", "scim", "rbac", "admin", "audit log",
                "\u5b89\u5168", "\u6743\u9650", "\u5408\u89c4", "\u9690\u79c1", "\u7ba1\u7406\u5458", "\u89d2\u8272", "\u5ba1\u8ba1")) {
            return "security";
        }
        if (containsAny(text,
                "user review", "users report", "users complain", "customer feedback", "sentiment", "reviews", "complain", "complaint",
                "\u7528\u6237\u53cd\u9988", "\u7528\u6237\u8bc4\u4ef7", "\u53e3\u7891", "\u5410\u69fd", "\u62b1\u6028", "\u8bc4\u8bba")) {
            return "sentiment";
        }
        return "";
    }

    private boolean strongPricingEvidence(EvidenceSource source, String evidenceId, AnalysisRun run) {
        String authority = normalizeUpper(source.getSourceAuthority());
        if (sourceTypeIs(source, "pricing_page") && !weakAuthority(authority)) {
            return true;
        }
        return chunkHasKind(run, evidenceId, "pricing")
                && (firstPartyAuthority(authority) || !StringUtils.hasText(authority) || "UNKNOWN".equals(authority));
    }

    private boolean weakPricingEvidence(EvidenceSource source, String evidenceId, AnalysisRun run) {
        return sourceTypeIs(source, "third_party_pricing_reference")
                || sourceTypeIs(source, "pricing_reference")
                || weakAuthority(normalizeUpper(source.getSourceAuthority()))
                || (chunkHasKind(run, evidenceId, "pricing") && !strongPricingEvidence(source, evidenceId, run));
    }

    private boolean firstPartyPricingEvidenceAvailable(AnalysisRun run) {
        return run.getEvidenceSources().stream()
                .anyMatch(source -> strongPricingEvidence(source, source.getCitationKey(), run));
    }

    private boolean strongSecurityEvidence(EvidenceSource source, String evidenceId, AnalysisRun run) {
        String authority = normalizeUpper(source.getSourceAuthority());
        boolean securityKind = chunkHasKind(run, evidenceId, "security") || chunkHasKind(run, evidenceId, "permission");
        boolean officialSecurityType = sourceTypeIs(source, "security_docs")
                || sourceTypeIs(source, "docs")
                || sourceTypeIs(source, "product_docs")
                || sourceTypeIs(source, "official_site");
        if (securityKind && (firstPartyAuthority(authority) || !StringUtils.hasText(authority) || "UNKNOWN".equals(authority))) {
            return true;
        }
        return officialSecurityType && !weakAuthority(authority);
    }

    private boolean sentimentEvidence(EvidenceSource source, String evidenceId, AnalysisRun run) {
        String authority = normalizeUpper(source.getSourceAuthority());
        return sourceTypeIs(source, "public_review")
                || sourceTypeIs(source, "public_reviews")
                || sourceTypeIs(source, "community_discussion")
                || sourceTypeIs(source, "user_interview")
                || sourceTypeIs(source, "user_survey")
                || sourceTypeIs(source, "user_note")
                || "COMMUNITY".equals(authority)
                || "USER_PROVIDED".equals(authority)
                || "INTERNAL_ONLY".equals(authority)
                || chunkHasKind(run, evidenceId, "public_review");
    }

    private boolean chunkHasKind(AnalysisRun run, String evidenceId, String expectedKind) {
        return run.getEvidenceChunks().stream()
                .filter(chunk -> evidenceId.equals(chunk.getSourceCitationKey()))
                .map(EvidenceChunk::getContentKind)
                .filter(StringUtils::hasText)
                .map(this::normalizeLower)
                .anyMatch(kind -> expectedKind.equals(kind));
    }

    private boolean sourceTypeIs(EvidenceSource source, String expectedType) {
        return expectedType.equals(normalizeLower(source.getSourceType()));
    }

    private boolean firstPartyAuthority(String authority) {
        return "FIRST_PARTY_OFFICIAL".equals(authority)
                || "FIRST_PARTY_DOCS".equals(authority)
                || "FIRST_PARTY_BLOG".equals(authority)
                || "USER_PROVIDED".equals(authority)
                || "INTERNAL_ONLY".equals(authority);
    }

    private boolean weakAuthority(String authority) {
        return "THIRD_PARTY_GENERAL".equals(authority)
                || "COMMUNITY".equals(authority)
                || "SEARCH_SNIPPET".equals(authority);
    }

    private boolean likelyMarketingOnlySource(EvidenceSource source) {
        String searchable = String.join(" ",
                nullToEmpty(source.getTitle()),
                nullToEmpty(source.getUrl()),
                nullToEmpty(source.getSourceType()),
                nullToEmpty(source.getSnippet()),
                nullToEmpty(source.getComplianceNote())
        ).toLowerCase(Locale.ROOT);
        return containsAny(searchable,
                "sponsored",
                "advertorial",
                "paid post",
                "promoted content",
                "partner content",
                "软文",
                "推广",
                "赞助",
                "商业推广",
                "内容合作"
        );
    }

    private String normalize(String text) {
        return text == null ? "" : text.trim();
    }

    private String normalizeLower(String text) {
        return text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeUpper(String text) {
        return text == null ? "" : text.trim().toUpperCase(Locale.ROOT);
    }

    private boolean containsAny(String text, String... patterns) {
        for (String pattern : patterns) {
            if (text.contains(pattern.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String nullToEmpty(String text) {
        return text == null ? "" : text;
    }

    private boolean looksLikeClaim(String paragraph) {
        return paragraph.contains("机会")
                || paragraph.contains("风险")
                || paragraph.contains("优势")
                || paragraph.contains("弱势")
                || paragraph.contains("建议")
                || paragraph.contains("更适合");
    }

    private boolean containsUncertaintyMarker(String text) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        return text.contains("待验证")
                || text.contains("证据不足")
                || text.contains("需补充")
                || text.contains("需要补充")
                || text.contains("人工复核");
    }

    private String abbreviate(String text) {
        return text.length() <= 80 ? text : text.substring(0, 80) + "...";
    }

    private record SourceQualityRisk(ReviewSeverity severity,
                                     String category,
                                     String reason,
                                     String recommendation) {
    }

    private record ClaimEvidencePolicyRisk(ReviewSeverity severity,
                                           String category,
                                           String reason,
                                           String recommendation) {
    }
}
