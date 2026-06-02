package com.aiinsight.service;

import com.aiinsight.model.review.ReviewFinding;
import com.aiinsight.model.enums.ConfidenceLevel;
import com.aiinsight.model.enums.ReviewSeverity;
import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.model.run.EvidenceChunk;
import com.aiinsight.model.run.EvidenceSource;
import com.aiinsight.model.schema.AnalysisClaim;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
            }
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
}
