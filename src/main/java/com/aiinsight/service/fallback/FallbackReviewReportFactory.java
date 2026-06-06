package com.aiinsight.service.fallback;

import com.aiinsight.model.enums.ReviewSeverity;
import com.aiinsight.model.review.ReviewFinding;
import com.aiinsight.model.run.AnalysisRun;
import org.springframework.stereotype.Component;

import static com.aiinsight.util.AgentUtils.hasText;
import static com.aiinsight.util.AgentUtils.nullToEmpty;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class FallbackReviewReportFactory {

    public String build(AnalysisRun run) {
        if (run.getReviewFindings().isEmpty()) {
            return """
                    ## Reviewer 复核结果

                    Reviewer 未发现高风险问题。

                    ## 人工复核建议

                    - 抽查最终报告中的关键 citation 是否能回到原始来源。
                    - 对 snippet-only、抓取失败或 robots 阻断的来源保持人工确认。
                    """;
        }
        return """
                ## Reviewer 复核结果

                %s

                %s

                %s

                ## 人工复核建议

                - 阻断问题会影响 ReviewDecision，并可能触发补证、重做分析或修订报告。
                - 质量提醒不阻断当前流程，但建议在正式发布前补强证据或降低措辞强度。
                - 人工复核项用于提醒需要访谈、实测、定价时效或企业判断的内容。
                """.formatted(
                qualitySummary(run),
                repairPlanBlock(run),
                findingsBlock(run.getReviewFindings())
        );
    }

    private String repairPlanBlock(AnalysisRun run) {
        if (run.getReviewDecision() == null || run.getReviewDecision().getRepairInstructions().isEmpty()) {
            return "## 定向修复计划\n\nReviewer 未生成自动修复计划。";
        }
        String instructions = run.getReviewDecision().getRepairInstructions().stream()
                .map(instruction -> "- " + instruction)
                .collect(Collectors.joining("\n"));
        String categories = run.getReviewDecision().getFindingCategories().isEmpty()
                ? "未指定"
                : run.getReviewDecision().getFindingCategories().stream()
                .map(this::categoryLabel)
                .collect(Collectors.joining("、"));
        String findingIds = run.getReviewDecision().getBlockingFindingIds().isEmpty()
                ? "无阻断 finding"
                : String.join("、", run.getReviewDecision().getBlockingFindingIds());
        String tasks = run.getReviewDecision().getRepairTasks().isEmpty()
                ? "暂无结构化修复任务。"
                : run.getReviewDecision().getRepairTasks().stream()
                .map(task -> "- action=%s target=%s finding=%s claim=%s citation=%s paragraph=%s excerpt=%s instruction=%s expectedFix=%s criteria=%s".formatted(
                        task.getAction(),
                        task.getTargetAgent(),
                        nullToEmpty(task.getFindingId()),
                        nullToEmpty(task.getClaimId()),
                        nullToEmpty(task.getCitationKey()),
                        task.getParagraphIndex() == null ? "-" : task.getParagraphIndex(),
                        nullToEmpty(task.getExcerpt()),
                        nullToEmpty(task.getInstruction()),
                        nullToEmpty(task.getExpectedFix()),
                        nullToEmpty(task.getAcceptanceCriteria())
                ))
                .collect(Collectors.joining("\n"));
        return """
                ## 定向修复计划

                修复范围：%s

                问题类别：%s

                阻断 Finding：%s

                修复指令：
                %s

                结构化修复任务：
                %s
                """.formatted(
                nullToEmpty(run.getReviewDecision().getRepairScopeSummary()),
                categories,
                findingIds,
                instructions,
                tasks
        );
    }

    private String qualitySummary(AnalysisRun run) {
        List<ReviewFinding> findings = run.getReviewFindings();
        long high = countBySeverity(findings, ReviewSeverity.HIGH);
        long medium = countBySeverity(findings, ReviewSeverity.MEDIUM);
        long low = countBySeverity(findings, ReviewSeverity.LOW);
        int blockers = run.getReviewDecision() == null ? 0 : run.getReviewDecision().getBlockingFindingIds().size();
        String status = blockers > 0
                ? "不建议对外发布，需先处理阻断问题。"
                : high + medium + low > 0
                        ? "可演示，但建议进入人工确认。"
                        : "已通过高风险检查。";
        return "可信度状态：%s 当前包含 %d 个阻断问题、%d 个 HIGH 提醒、%d 个质量提醒、%d 个人工复核项。".formatted(
                status,
                blockers,
                high,
                medium,
                low
        );
    }

    private String findingsBlock(List<ReviewFinding> findings) {
        return List.of(
                        findingsGroup(findings, ReviewSeverity.HIGH, "HIGH 复核项"),
                        findingsGroup(findings, ReviewSeverity.MEDIUM, "质量提醒"),
                        findingsGroup(findings, ReviewSeverity.LOW, "人工复核项")
                ).stream()
                .filter(value -> hasText(value))
                .collect(Collectors.joining("\n\n"));
    }

    private String findingsGroup(List<ReviewFinding> findings, ReviewSeverity severity, String title) {
        List<ReviewFinding> group = findings.stream()
                .filter(finding -> finding.getSeverity() == severity)
                .sorted(Comparator.comparing(finding -> nullToEmpty(finding.getCategory())))
                .toList();
        if (group.isEmpty()) {
            return "";
        }
        String lines = group.stream()
                .map(this::findingLine)
                .collect(Collectors.joining("\n"));
        return "### " + title + "\n\n" + lines;
    }

    private String findingLine(ReviewFinding finding) {
        String location = locationText(finding);
        return "- [%s] %s: %s%s\n  建议：%s".formatted(
                severityLabel(finding.getSeverity()),
                categoryLabel(finding.getCategory()),
                finding.getMessage(),
                location,
                finding.getRecommendation()
        );
    }

    private String locationText(ReviewFinding finding) {
        String claim = hasText(finding.getClaimId()) ? "claim=" + finding.getClaimId() : "";
        String citation = hasText(finding.getCitationKey()) ? "citation=[" + finding.getCitationKey() + "]" : "";
        String paragraph = finding.getParagraphIndex() == null ? "" : "paragraph=" + finding.getParagraphIndex();
        String location = List.of(claim, citation, paragraph).stream()
                .filter(value -> hasText(value))
                .collect(Collectors.joining(", "));
        return hasText(location) ? "（" + location + "）" : "";
    }

    private long countBySeverity(List<ReviewFinding> findings, ReviewSeverity severity) {
        return findings.stream()
                .filter(finding -> finding.getSeverity() == severity)
                .count();
    }

    private String severityLabel(ReviewSeverity severity) {
        if (severity == ReviewSeverity.HIGH) return "阻断问题";
        if (severity == ReviewSeverity.MEDIUM) return "质量提醒";
        return "人工复核";
    }

    private String categoryLabel(String category) {
        String normalized = category == null ? "" : category.trim().toLowerCase().replace('-', '_');
        return switch (normalized) {
            case "citation_missing", "missing_citation" -> "缺少引用";
            case "citation_unknown" -> "未知引用";
            case "citation_weak_support" -> "引用支撑不足";
            case "citation_support_mismatch" -> "引用支撑不一致";
            case "citation_snippet_only", "snippet_only_source" -> "搜索摘要来源";
            case "citation_blocked_source", "blocked_source" -> "来源受限";
            case "fetch_failed_source" -> "来源抓取失败";
            case "citation_thin_source", "thin_source" -> "来源内容过薄";
            case "citation_internal_evidence_presented_as_public",
                 "claim_internal_evidence_presented_as_public" -> "内部资料被表述为公开证据";
            case "citation_region_unavailable_source", "region_unavailable_source" -> "来源区域不可用";
            case "citation_marketing_only_source", "marketing_only_source" -> "营销型来源";
            case "low_quality_source" -> "低质量来源";
            case "claim_missing_evidence" -> "结论缺少证据";
            case "claim_unknown_evidence" -> "结论引用未知证据";
            case "claim_evidence_mismatch" -> "结论与证据不一致";
            case "claim_weak_support" -> "结论支撑不足";
            case "claim_high_confidence_low_quality_source" -> "高置信结论依赖低质量来源";
            case "claim_confidence_mismatch" -> "置信度不一致";
            case "claim_missing_fact_binding" -> "结论缺少事实绑定";
            case "claim_unknown_fact" -> "结论引用未知事实";
            case "claim_fact_mismatch" -> "结论与事实不一致";
            case "claim_weak_pricing_source" -> "定价来源偏弱";
            case "claim_missing_pricing_source" -> "缺少定价来源";
            case "claim_weak_security_source" -> "安全/权限来源偏弱";
            case "claim_missing_sentiment_source" -> "缺少用户口碑来源";
            case "fact_missing_evidence" -> "事实缺少证据";
            case "fact_unknown_chunk" -> "事实引用未知切片";
            case "fact_unknown_evidence" -> "事实引用未知证据";
            case "fact_partial_evidence_binding_weak" -> "事实部分证据偏弱";
            case "fact_unsupported_by_evidence" -> "事实未被证据支撑";
            case "fact_extraction_mismatch", "extracted_fact_mismatch" -> "事实抽取不一致";
            case "report_overclaim", "llm_overclaim" -> "报告过度推断";
            case "report_quality_insufficient" -> "报告质量不足";
            case "report_missing_decision_summary" -> "报告缺少决策摘要";
            case "report_dimension_coverage_gap" -> "报告维度覆盖不足";
            case "report_actionability_gap", "report_actionability_insufficient" -> "报告行动建议不足";
            case "unsupported_recommendation" -> "建议缺少支撑";
            case "schema_consistency" -> "结构化信息不一致";
            case "matrix_claim_conflict" -> "矩阵与结论冲突";
            case "swot_claim_conflict" -> "SWOT 与结论冲突";
            case "llm_semantic_review" -> "语义质检";
            default -> fallbackCategoryLabel(normalized);
        };
    }

    private String fallbackCategoryLabel(String category) {
        if (!hasText(category)) return "未分类质检项";
        if (category.contains("citation") || category.contains("reference")) return "引用问题";
        if (category.contains("overclaim")) return "过度推断";
        if (category.contains("source")) return "来源质量问题";
        if (category.contains("fact")) return "事实抽取问题";
        if (category.contains("claim")) return "结论支撑问题";
        if (category.contains("schema") || category.contains("matrix") || category.contains("swot")) return "结构化一致性问题";
        if (category.contains("report")) return "报告质量问题";
        return "质检问题";
    }
}
