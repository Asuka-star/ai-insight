package com.aiinsight.service.fallback;

import com.aiinsight.model.enums.ReviewSeverity;
import com.aiinsight.model.review.ReviewFinding;
import com.aiinsight.model.run.AnalysisRun;
import org.springframework.stereotype.Component;

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
                : String.join("、", run.getReviewDecision().getFindingCategories());
        String findingIds = run.getReviewDecision().getBlockingFindingIds().isEmpty()
                ? "无阻断 finding"
                : String.join("、", run.getReviewDecision().getBlockingFindingIds());
        String tasks = run.getReviewDecision().getRepairTasks().isEmpty()
                ? "暂无结构化修复任务。"
                : run.getReviewDecision().getRepairTasks().stream()
                .map(task -> "- action=%s target=%s finding=%s claim=%s citation=%s criteria=%s".formatted(
                        task.getAction(),
                        task.getTargetAgent(),
                        nullToEmpty(task.getFindingId()),
                        nullToEmpty(task.getClaimId()),
                        nullToEmpty(task.getCitationKey()),
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
                .filter(this::hasText)
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
                finding.getSeverity(),
                finding.getCategory(),
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
                .filter(this::hasText)
                .collect(Collectors.joining(", "));
        return hasText(location) ? "（" + location + "）" : "";
    }

    private long countBySeverity(List<ReviewFinding> findings, ReviewSeverity severity) {
        return findings.stream()
                .filter(finding -> finding.getSeverity() == severity)
                .count();
    }

    private boolean hasText(String text) {
        return text != null && !text.isBlank();
    }

    private String nullToEmpty(String text) {
        return text == null ? "" : text;
    }
}
