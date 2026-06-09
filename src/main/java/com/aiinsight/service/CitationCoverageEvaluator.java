package com.aiinsight.service;

import com.aiinsight.model.review.ReviewFinding;
import com.aiinsight.model.enums.ConfidenceLevel;
import com.aiinsight.model.enums.ReviewSeverity;
import com.aiinsight.model.enums.ReviewLocationType;
import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.model.run.EvidenceChunk;
import com.aiinsight.model.run.EvidenceSource;
import com.aiinsight.model.schema.AnalysisClaim;
import com.aiinsight.model.schema.ExtractedFact;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import static com.aiinsight.util.AgentUtils.CITATION_PATTERN;
import static com.aiinsight.util.AgentUtils.nullToEmpty;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class CitationCoverageEvaluator {

    private static final Pattern VERIFICATION_SUMMARY_PATTERN = Pattern.compile(
            "共\\s*(\\d+)\\s*条结论[^\\n]*?SUPPORTED\\s*=\\s*(\\d+)(?:[^\\n]*?PARTIAL\\s*=\\s*(\\d+))?[^\\n]*?UNVERIFIED\\s*=\\s*(\\d+)"
    );
    private static final Pattern COMPACT_VERIFICATION_SUMMARY_PATTERN = Pattern.compile(
            "SUPPORTED\\s*=\\s*(\\d+)(?:[^\\n)]*?PARTIAL\\s*=\\s*(\\d+))?[^\\n)]*?UNVERIFIED\\s*=\\s*(\\d+)"
    );

    // 维度名 → 同义词/关键词别名：用于确定性维度覆盖校验。
    // 每个条目包含英文规范名、中文别名和功能关键词，保证报告文本至少命中其中一个。
    private static final Map<String, List<String>> DIMENSION_ALIASES = new LinkedHashMap<>();
    static {
        DIMENSION_ALIASES.put("pricing", List.of("pricing", "price", "plans", "价格", "定价", "商业模式", "付费", "套餐"));
        DIMENSION_ALIASES.put("reviews", List.of("review", "feedback", "评价", "反馈", "用户口碑", "用户体验"));
        DIMENSION_ALIASES.put("security", List.of("security", "permission", "compliance", "安全", "权限", "合规"));
        DIMENSION_ALIASES.put("customers", List.of("customer", "case", "客户", "案例"));
        DIMENSION_ALIASES.put("code_generation", List.of("代码", "生成", "编程", "编码", "补全", "code generation", "coding", "completion"));
        DIMENSION_ALIASES.put("agent_workflow", List.of("工作流", "agent", "智能体", "workflow", "multi-agent"));
        DIMENSION_ALIASES.put("ide_integration", List.of("ide", "终端", "terminal", "编辑器", "editor", "集成开发"));
        DIMENSION_ALIASES.put("context_management", List.of("上下文", "context", "memory", "记忆", "窗口", "window", "上下文管理"));
        DIMENSION_ALIASES.put("team_collaboration", List.of("团队", "协作", "collaboration", "team", "协同", "多人"));
        DIMENSION_ALIASES.put("features", List.of("功能", "feature", "特性", "能力", "capability"));
        // 通用分析维度：产品定位、优劣势、机会点、风险提示等常见中文分析维度
        DIMENSION_ALIASES.put("positioning", List.of("定位", "positioning", "产品定位", "市场定位", "差异化"));
        DIMENSION_ALIASES.put("strengths_weaknesses", List.of("优势", "劣势", "强项", "弱项", "优劣势", "strength", "weakness", "pros", "cons"));
        DIMENSION_ALIASES.put("opportunities", List.of("机会", "机遇", "机会点", "opportunity", "增长点"));
        DIMENSION_ALIASES.put("risks", List.of("风险", "威胁", "风险提示", "risk", "threat", "挑战", "隐患"));
    }

    public List<ReviewFinding> evaluate(String reportContent) {
        return evaluate(reportContent, null);
    }

    public List<ReviewFinding> evaluate(String reportContent, AnalysisRun run) {
        List<ReviewFinding> findings = new ArrayList<>();
        int paragraphIndex = 0;
        for (String paragraph : reportContent.split("\\R\\R+")) {
            String trimmed = paragraph.trim();
            String section = sectionHeading(trimmed);
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("##") || StringUtils.hasText(section)) {
                paragraphIndex++;
                continue;
            }

            // 规则层只验证引用编号是否存在；“该不该引用、引用是否支撑”交给 Reviewer LLM 做语义判断。
            if (looksLikeClaim(trimmed) && run != null && CITATION_PATTERN.matcher(trimmed).find()) {
                findings.addAll(validateCitationSupport(trimmed, paragraphIndex, run));
            }
            paragraphIndex++;
        }
        if (run != null) {
            findings.addAll(validateReportStructure(reportContent, run));
            findings.addAll(validateStructuredClaims(run));
        }
        return findings;
    }

    private List<ReviewFinding> validateReportStructure(String reportContent, AnalysisRun run) {
        List<ReviewFinding> findings = new ArrayList<>();
        findings.addAll(validateMarkdownTables(reportContent));
        findings.addAll(validateVerificationSummary(reportContent, run));
        return findings;
    }

    private List<ReviewFinding> validateMarkdownTables(String reportContent) {
        List<ReviewFinding> findings = new ArrayList<>();
        if (!StringUtils.hasText(reportContent)) {
            return findings;
        }
        String[] lines = reportContent.split("\\R", -1);
        int expectedColumns = -1;
        String headerLine = "";
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            if (!isMarkdownTableRow(trimmed)) {
                expectedColumns = -1;
                headerLine = "";
                if (looksLikeBrokenTableContinuation(trimmed)) {
                    ReviewFinding finding = new ReviewFinding(
                            ReviewSeverity.HIGH,
                            "report_table_malformed",
                            "Markdown 表格行疑似被前缀或换行破坏，无法稳定渲染。",
                            "请保持整行以 | 开头并维持与表头相同的列数；待验证标记应写入单元格内部。"
                    );
                    finding.setParagraphIndex(i);
                    finding.setExcerpt(line);
                    finding.setLocationType(ReviewLocationType.REPORT_PARAGRAPH);
                    findings.add(finding);
                }
                continue;
            }
            if (i + 1 < lines.length && isMarkdownTableSeparator(lines[i + 1].trim())) {
                expectedColumns = tableColumnCount(trimmed);
                headerLine = trimmed;
                if (containsCitation(trimmed)) {
                    ReviewFinding finding = new ReviewFinding(
                            ReviewSeverity.MEDIUM,
                            "report_table_header_citation",
                            "Markdown 表头包含证据引用，容易形成类似“判断置信度[S5]”的异常列名。",
                            "删除表头中的 citation；引用应放在具体数据行的证据或判断单元格中。"
                    );
                    finding.setParagraphIndex(i);
                    finding.setExcerpt(line);
                    finding.setLocationType(ReviewLocationType.REPORT_PARAGRAPH);
                    findings.add(finding);
                }
                continue;
            }
            if (isMarkdownTableSeparator(trimmed)) {
                continue;
            }
            if (expectedColumns > 0 && tableColumnCount(trimmed) != expectedColumns) {
                ReviewFinding finding = new ReviewFinding(
                        ReviewSeverity.HIGH,
                        "report_table_column_mismatch",
                        "Markdown 表格数据行列数与表头不一致，可能导致前端表格渲染错位。",
                        "修复该行的 | 分隔符数量，确保与表头列数一致；不要把“待验证：”放到表格行外。"
                );
                finding.setParagraphIndex(i);
                finding.setExcerpt("%s\n%s".formatted(headerLine, line).trim());
                finding.setLocationType(ReviewLocationType.REPORT_PARAGRAPH);
                findings.add(finding);
            }
        }
        return findings;
    }

    private List<ReviewFinding> validateVerificationSummary(String reportContent, AnalysisRun run) {
        if (!StringUtils.hasText(reportContent) || run == null || run.getClaims().isEmpty()) {
            return List.of();
        }
        Matcher matcher = VERIFICATION_SUMMARY_PATTERN.matcher(reportContent);
        if (!matcher.find()) {
            return validateCompactVerificationSummary(reportContent, run);
        }
        ReviewFinding finding = new ReviewFinding(
                ReviewSeverity.MEDIUM,
                "report_verification_summary_mismatch",
                "报告正文展示了内部证据状态计数，容易让用户关注实现细节而不是结论。",
                "移除 SUPPORTED/PARTIAL/UNVERIFIED 等内部状态计数，只用自然语言说明证据置信度受限。"
        );
        finding.setExcerpt(matcher.group(0));
        finding.setLocationType(ReviewLocationType.REPORT_PARAGRAPH);
        return List.of(finding);
    }

    private List<ReviewFinding> validateCompactVerificationSummary(String reportContent, AnalysisRun run) {
        Matcher matcher = COMPACT_VERIFICATION_SUMMARY_PATTERN.matcher(reportContent);
        if (!matcher.find()) {
            return List.of();
        }
        ReviewFinding finding = new ReviewFinding(
                ReviewSeverity.MEDIUM,
                "report_verification_summary_mismatch",
                "报告正文展示了内部证据状态计数，容易让用户关注实现细节而不是结论。",
                "移除 SUPPORTED/PARTIAL/UNVERIFIED 等内部状态计数，只用自然语言说明证据置信度受限。"
        );
        finding.setExcerpt(matcher.group(0));
        finding.setLocationType(ReviewLocationType.REPORT_PARAGRAPH);
        return List.of(finding);
    }

    private int countClaimsWithStatus(AnalysisRun run, String status) {
        return (int) run.getClaims().stream()
                .filter(claim -> status.equalsIgnoreCase(nullToEmpty(claim.getSupportStatus())))
                .count();
    }

    private int parseCount(String value) {
        if (!StringUtils.hasText(value)) {
            return 0;
        }
        return Integer.parseInt(value.trim());
    }

    private boolean looksLikeBrokenTableContinuation(String trimmed) {
        return StringUtils.hasText(trimmed)
                && trimmed.contains("|")
                && (trimmed.startsWith("待验证：|") || trimmed.startsWith("待验证:|"));
    }

    private boolean containsCitation(String line) {
        return CITATION_PATTERN.matcher(line).find();
    }

    private boolean isMarkdownTableRow(String line) {
        return StringUtils.hasText(line) && line.startsWith("|") && line.contains("|");
    }

    private boolean isMarkdownTableSeparator(String line) {
        return StringUtils.hasText(line)
                && line.matches("^\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)+\\|?\\s*$");
    }

    private int tableColumnCount(String line) {
        if (!isMarkdownTableRow(line)) {
            return 0;
        }
        String normalized = line.trim();
        if (normalized.startsWith("|")) {
            normalized = normalized.substring(1);
        }
        if (normalized.endsWith("|")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.split("\\|", -1).length;
    }

    // 确定性维度覆盖校验：对用户指定的每个分析维度，检查报告文本是否包含该维度的关键词。
    // 替代纯 LLM 检测 report_dimension_coverage_gap 的非确定性方式。
    public List<ReviewFinding> evaluateDimensionCoverage(String reportContent, AnalysisRun run) {
        List<ReviewFinding> findings = new ArrayList<>();
        if (run == null || run.getRequirement() == null || !StringUtils.hasText(reportContent)) {
            return findings;
        }
        List<String> dimensions = run.getRequirement().getDimensions();
        if (dimensions == null || dimensions.isEmpty()) {
            return findings;
        }
        String reportLower = reportContent.toLowerCase(Locale.ROOT);
        for (String dimension : dimensions) {
            if (!StringUtils.hasText(dimension) || "public_search".equals(dimension.trim())) {
                continue;
            }
            Set<String> keywords = keywordsForDimension(dimension);
            boolean found = false;
            for (String keyword : keywords) {
                if (reportLower.contains(keyword.toLowerCase(Locale.ROOT))) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                findings.add(new ReviewFinding(
                        ReviewSeverity.HIGH,
                        "report_dimension_coverage_gap",
                        "用户指定的'%s'维度在报告中未被明确覆盖，关键洞察、建议优先级或风险清单中均未讨论此维度。".formatted(dimension),
                        "在报告中新增对'%s'维度的讨论，或在风险与证据缺口部分注明该维度证据不足。".formatted(dimension)
                ));
            }
        }
        return findings;
    }

    // 为给定维度收集所有匹配关键词：包括原始维度名和 DIMENSION_ALIASES 中的同义词组。
    private Set<String> keywordsForDimension(String dimension) {
        Set<String> keywords = new LinkedHashSet<>();
        // 原始维度名始终作为关键词（保证直接匹配中文维度名如"上下文管理"）
        keywords.add(dimension.trim());
        String dimNorm = normalizeLower(dimension);
        // 用规范名直接查找
        List<String> direct = DIMENSION_ALIASES.get(dimNorm);
        if (direct != null) {
            keywords.addAll(direct);
        }
        // 反向查找：如果维度文本包含某组的任一关键词，则纳入该组全部关键词
        for (Map.Entry<String, List<String>> entry : DIMENSION_ALIASES.entrySet()) {
            if (entry.getKey().equals(dimNorm)) {
                continue; // 已经直接添加过了
            }
            for (String alias : entry.getValue()) {
                if (dimNorm.contains(alias.toLowerCase(Locale.ROOT))
                        || alias.toLowerCase(Locale.ROOT).contains(dimNorm)) {
                    keywords.addAll(entry.getValue());
                    break;
                }
            }
        }
        return keywords;
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
        Set<String> paragraphCitationKeys = citationKeys(paragraph);
        boolean publicMarketClaim = publicMarketClaim(paragraph);
        boolean hasPublicEvidence = paragraphCitationKeys.stream()
                .map(citationKey -> sourceByCitationKey(run, citationKey))
                .anyMatch(this::publicEvidence);
        Matcher matcher = CITATION_PATTERN.matcher(paragraph);
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
                finding.setLocationType(ReviewLocationType.REPORT_PARAGRAPH);
                findings.add(finding);
                continue;
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
            if (isValidationBacklogClaim(claim)) {
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
                finding.setLocationType(ReviewLocationType.CLAIM);
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
                    finding.setLocationType(ReviewLocationType.CLAIM);
                    findings.add(finding);
                    continue;
                }
            }
            findings.addAll(validateClaimFacts(run, claim, known));
        }
        return findings;
    }

    private boolean isValidationBacklogClaim(AnalysisClaim claim) {
        return "VALIDATION_BACKLOG".equals(normalizeUpper(claim.getRecommendedPlacement()))
                || "NONE".equals(normalizeUpper(claim.getRecommendedPlacement()))
                || "UNVERIFIED".equals(normalizeUpper(claim.getSupportStatus()));
    }

    private List<ReviewFinding> validateExtractedFacts(AnalysisRun run, Set<String> knownEvidenceIds) {
        List<ReviewFinding> findings = new ArrayList<>();
        Map<String, String> claimIdByFactId = claimIdByFactId(run);
        Set<String> knownChunkKeys = run.getEvidenceChunks().stream()
                .map(EvidenceChunk::getChunkKey)
                .filter(StringUtils::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        for (ExtractedFact fact : factsById(run).values()) {
            List<String> factEvidenceIds = fact.getEvidenceIds() == null ? List.of() : fact.getEvidenceIds();
            String claimId = claimIdByFactId.get(fact.getId());
            if (factEvidenceIds.isEmpty()) {
                findings.add(factFinding(
                        ReviewSeverity.HIGH,
                        "fact_missing_evidence",
                        "抽取事实缺少 evidenceIds: " + fact.getId(),
                        "请重跑 Extractor；只保留带有 evidenceIds/chunkKeys 的事实，无法被证据支撑的字段应移动到未知事实列表。",
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
                            "抽取事实引用了不存在的证据切片: " + chunkKey,
                            "请在证据切片刷新后重跑 Extractor，或从事实绑定中移除失效的 chunkKeys。",
                            claimId,
                            fact,
                            null
                    );
                    finding.setChunkKey(chunkKey);
                    findings.add(finding);
                }
            }
            for (String evidenceId : factEvidenceIds.stream().filter(StringUtils::hasText).distinct().toList()) {
                if (!knownEvidenceIds.contains(evidenceId)) {
                    findings.add(factFinding(
                            ReviewSeverity.HIGH,
                            "fact_unknown_evidence",
                            "抽取事实引用了不存在的证据编号: [" + evidenceId + "]",
                            "请重跑 Extractor 并把事实绑定到现有证据；如果没有可支撑证据，请删除该事实。",
                            claimId,
                            fact,
                            evidenceId
                    ));
                }
            }
        }
        return findings;
    }

    private String limitedJoin(List<String> values, int limit) {
        List<String> visible = values.stream()
                .filter(StringUtils::hasText)
                .limit(limit)
                .toList();
        String joined = String.join(", ", visible);
        int hidden = values.size() - visible.size();
        return hidden > 0 ? joined + ", +" + hidden + " more" : joined;
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
            return findings;
        }

        List<ExtractedFact> boundFacts = new ArrayList<>();
        for (String factId : factIds) {
            ExtractedFact fact = factsById.get(factId);
            if (fact == null) {
                ReviewFinding finding = new ReviewFinding(
                        ReviewSeverity.HIGH,
                        "claim_unknown_fact",
                        "结构化结论引用了不存在的 extracted fact id: " + factId,
                        "请移除失效的 factId；或在 Extractor 重新生成事实层后重跑 Analyst。"
                );
                finding.setClaimId(claim.getId());
                finding.setFactId(factId);
                finding.setExcerpt(claim.getContent());
                finding.setLocationType(ReviewLocationType.CLAIM);
                findings.add(finding);
                continue;
            }
            boundFacts.add(fact);
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
        finding.setLocationType(ReviewLocationType.CLAIM);
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
        if (!riskTopicCompatible(paragraph, evidenceText)) {
            return false;
        }
        Set<String> evidenceTerms = terms(evidenceText);
        long overlap = claimTerms.stream().filter(evidenceTerms::contains).count();
        return overlap >= Math.min(2, claimTerms.size());
    }

    private boolean riskTopicCompatible(String claimText, String evidenceText) {
        String claim = normalizeLower(claimText);
        String evidence = normalizeLower(evidenceText);
        return topicCompatible(claim, evidence, List.of("安全", "权限", "审计", "治理", "security", "permission", "governance", "audit", "sso", "scim", "soc"))
                && topicCompatible(claim, evidence, List.of("定价", "价格", "套餐", "pricing", "price", "plan", "billing"))
                && topicCompatible(claim, evidence, List.of("部署", "deployment", "deploy", "bedrock", "代理", "proxy", "ship"));
    }

    private boolean topicCompatible(String claim, String evidence, List<String> topicTerms) {
        boolean claimMentionsTopic = topicTerms.stream().anyMatch(claim::contains);
        return !claimMentionsTopic || topicTerms.stream().anyMatch(evidence::contains);
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
        addCrossLingualSupportTerms(text, terms);
        return terms;
    }

    private void addCrossLingualSupportTerms(String text, Set<String> terms) {
        String normalized = normalizeLower(text);
        addAliasesIfContains(normalized, terms, List.of("终端", "terminal"), "terminal", "cli");
        addAliasesIfContains(normalized, terms, List.of("ide", "编辑器", "vscode", "jetbrains"), "ide", "editor");
        addAliasesIfContains(normalized, terms, List.of("slack"), "slack");
        addAliasesIfContains(normalized, terms, List.of("web", "网页", "界面"), "web");
        addAliasesIfContains(normalized, terms, List.of("代码库", "仓库", "codebase", "repository", "repo"), "codebase", "repository");
        addAliasesIfContains(normalized, terms, List.of("构建", "build", "ship"), "build", "ship");
        addAliasesIfContains(normalized, terms, List.of("调试", "debug", "debugging"), "debug", "debugging");
        addAliasesIfContains(normalized, terms, List.of("修复", "fix", "repair"), "fix", "repair");
        addAliasesIfContains(normalized, terms, List.of("部署", "deployment", "deploy"), "deploy", "deployment");
        addAliasesIfContains(normalized, terms, List.of("bedrock", "amazon"), "amazon", "bedrock");
        addAliasesIfContains(normalized, terms, List.of("代理", "proxy"), "proxy");
        addAliasesIfContains(normalized, terms, List.of("定价", "价格", "pricing", "price"), "pricing", "price");
        addAliasesIfContains(normalized, terms, List.of("团队计划", "团队", "team", "teams"), "team", "plan");
        addAliasesIfContains(normalized, terms, List.of("个人计划", "个人", "individual"), "individual", "plan");
        addAliasesIfContains(normalized, terms, List.of("企业", "enterprise"), "enterprise");
        addAliasesIfContains(normalized, terms, List.of("协作", "collaboration", "collaborate"), "collaboration", "collaborate");
        addAliasesIfContains(normalized, terms, List.of("工作流", "workflow"), "workflow");
        addAliasesIfContains(normalized, terms, List.of("技能", "skills"), "skills");
        addAliasesIfContains(normalized, terms, List.of("mcp"), "mcp");
    }

    private void addAliasesIfContains(String normalized, Set<String> terms, List<String> needles, String... aliases) {
        if (needles.stream().anyMatch(normalized::contains)) {
            terms.addAll(List.of(aliases));
        }
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
        if (publicMarketClaim(claim.getContent()) && internalOrUserEvidence(source) && !claimHasPublicEvidence(claim, run)) {
            return new ClaimEvidencePolicyRisk(
                    claim.getConfidence() == ConfidenceLevel.HIGH ? ReviewSeverity.HIGH : ReviewSeverity.MEDIUM,
                    "claim_internal_evidence_presented_as_public",
                    "面向公开或市场判断的 claim 仅引用了用户提供/内部证据 [" + evidenceId + "]。",
                    "请补充公开或官方证据；如果只能依赖用户提供/内部资料，请改写为基于内部资料的结论。"
            );
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
                        "定价 claim 在已有一手定价证据时，仍引用了二手定价来源 [" + evidenceId + "]。",
                        "请优先使用 FIRST_PARTY_OFFICIAL 的 pricing_page 或一手定价切片；否则应降低置信度，并标注价格仍需核验。"
                );
            }
            if (!chunkHasKind(run, evidenceId, "pricing") && !sourceTypeIs(source, "pricing_page")) {
                return new ClaimEvidencePolicyRisk(
                        ReviewSeverity.MEDIUM,
                        "claim_missing_pricing_source",
                        "定价 claim 引用了未标记为定价内容的证据 [" + evidenceId + "]。",
                        "请把该 claim 绑定到 pricing_page/pricing 切片；否则改写为待验证的定价假设。"
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
                    "安全或权限 claim 引用了缺少一手文档、安全文档或 security/permission 切片支撑的证据 [" + evidenceId + "]。",
                    "请使用官方 security_docs/product_docs/official_site 证据，或一手 security/permission 切片；否则应降低置信度。"
            );
        }
        if ("sentiment".equals(need)) {
            if (sentimentEvidence(source, evidenceId, run)) {
                return null;
            }
            return new ClaimEvidencePolicyRisk(
                    ReviewSeverity.MEDIUM,
                    "claim_missing_sentiment_source",
                    "用户口碑/情绪 claim 引用了非评论、社区、访谈、问卷或用户提供来源的证据 [" + evidenceId + "]。",
                    "请使用 public_review/community_discussion/user_interview/user_survey 证据；否则改写为厂商定位，而不是用户口碑结论。"
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

    private Set<String> citationKeys(String text) {
        Set<String> keys = new LinkedHashSet<>();
        if (!StringUtils.hasText(text)) {
            return keys;
        }
        Matcher matcher = CITATION_PATTERN.matcher(text);
        while (matcher.find()) {
            keys.add(matcher.group(1));
        }
        return keys;
    }

    private boolean publicMarketClaim(String text) {
        String normalized = normalizeLower(text);
        return containsAny(normalized,
                "public source",
                "public sources",
                "public data",
                "public evidence",
                "market",
                "competitor",
                "competitive",
                "industry",
                "external",
                "benchmark",
                "\u516c\u5f00\u8d44\u6599",
                "\u516c\u5f00\u4fe1\u606f",
                "\u516c\u5f00\u6570\u636e",
                "\u5e02\u573a",
                "\u7ade\u54c1",
                "\u884c\u4e1a",
                "\u5916\u90e8",
                "\u57fa\u51c6");
    }

    private boolean claimHasPublicEvidence(AnalysisClaim claim, AnalysisRun run) {
        if (claim.getEvidenceIds() == null || claim.getEvidenceIds().isEmpty()) {
            return false;
        }
        return claim.getEvidenceIds().stream()
                .map(evidenceId -> sourceByCitationKey(run, evidenceId))
                .anyMatch(this::publicEvidence);
    }

    private boolean publicEvidence(EvidenceSource source) {
        if (source == null || internalOrUserEvidence(source)) {
            return false;
        }
        String sourceType = normalizeLower(source.getSourceType());
        String authority = normalizeUpper(source.getSourceAuthority());
        String collectionStatus = normalizeUpper(source.getCollectionStatus());
        String freshness = normalizeUpper(source.getFreshness());
        String sourceQuality = normalizeUpper(source.getSourceQuality());
        return !"SEARCH_RESULT_SNIPPET".equals(freshness)
                && !"FETCH_FAILED".equals(collectionStatus)
                && !"BLOCKED_BY_ROBOTS".equals(collectionStatus)
                && !"search_result_snippet".equals(sourceType)
                && !"SEARCH_SNIPPET".equals(authority)
                && !"UNUSABLE".equals(sourceQuality);
    }

    private boolean internalOrUserEvidence(EvidenceSource source) {
        if (source == null) {
            return false;
        }
        String authority = normalizeUpper(source.getSourceAuthority());
        String quality = normalizeUpper(source.getSourceQuality());
        String sourceType = normalizeLower(source.getSourceType());
        String url = normalizeLower(source.getUrl());
        return "USER_PROVIDED".equals(authority)
                || "INTERNAL_ONLY".equals(authority)
                || "INTERNAL_ONLY".equals(quality)
                || sourceType.startsWith("user_")
                || url.startsWith("user-document://");
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

    private boolean looksLikeClaim(String paragraph) {
        String normalized = normalizeLower(paragraph);
        if (isReportScaffoldingParagraph(normalized)) {
            return false;
        }
        // 这里只用于触发引用支撑校验，不再用于生成“缺少引用” finding。
        return containsAny(normalized,
                "机会", "风险", "优势", "劣势", "弱势", "更适合", "领先", "落后", "强于", "弱于",
                "高于", "低于", "优于", "依赖", "缺乏", "不足", "增长", "下降", "占比",
                "定价", "价格", "套餐", "免费版", "企业版", "权限", "安全", "合规",
                "用户反馈", "用户评价", "口碑", "公开资料", "公开信息", "市场", "竞品", "行业",
                "数据显示", "证据显示", "资料显示", "调研显示", "访谈显示",
                "opportunity",
                "risk",
                "advantage",
                "weakness",
                "public evidence",
                "public market",
                "market evidence",
                "competitive",
                "competitor",
                "industry",
                "pricing",
                "security",
                "permission",
                "compliance",
                "market share",
                "user feedback",
                "customer feedback");
    }

    private boolean isReportScaffoldingParagraph(String normalized) {
        if (!StringUtils.hasText(normalized)) {
            return true;
        }
        String compact = normalized.replaceAll("\\s+", "");
        if (compact.length() <= 8) {
            return true;
        }
        // 章节导语、总结引子和行动建议引子通常不表达可核验事实，避免它们进入引用校验路径。
        if (containsAny(compact,
                "以下是", "如下", "本节", "本段", "本文", "本报告", "上文", "下文",
                "行动建议", "明确的行动建议", "建议如下", "下一步建议", "优先级建议",
                "基于当前信息", "基于对当前信息", "当前信息与风险", "综合判断",
                "总体来看", "整体来看", "综上", "小结", "总结如下", "分析如下",
                "需要注意的是", "为了便于", "为了确保", "为便于", "为确保",
                "the following", "below are", "in summary", "overall", "next steps",
                "action recommendations", "recommendations below")) {
            return true;
        }
        return compact.matches("^[一二三四五六七八九十0-9]+[、.．-].{1,42}$");
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
