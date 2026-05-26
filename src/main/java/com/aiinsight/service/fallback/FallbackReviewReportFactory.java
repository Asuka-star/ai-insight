package com.aiinsight.service.fallback;

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

                ## 人工复核建议

                - 优先处理 HIGH 级问题，避免无引用结论或不存在的 citation 进入最终报告。
                - MEDIUM 级问题通常代表弱支撑、低质量来源或高置信结论证据不足，建议补采后再确认。
                - LOW 级问题可作为人工审阅提醒，不阻断当前流程。
                """.formatted(findingsBlock(run.getReviewFindings()));
    }

    private String findingsBlock(List<ReviewFinding> findings) {
        return findings.stream()
                .sorted(Comparator
                        .comparing(ReviewFinding::getSeverity, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(finding -> nullToEmpty(finding.getCategory())))
                .map(this::findingLine)
                .collect(Collectors.joining("\n"));
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

    private boolean hasText(String text) {
        return text != null && !text.isBlank();
    }

    private String nullToEmpty(String text) {
        return text == null ? "" : text;
    }
}
