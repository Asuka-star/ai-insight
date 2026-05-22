package com.aiinsight.service;

import com.aiinsight.model.review.ReviewFinding;
import com.aiinsight.model.enums.ReviewSeverity;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Component
public class CitationCoverageEvaluator {

    // MVP 阶段强制使用 [S1]、[S2] 这种证据编号，后续可升级为 claimId -> evidenceId 映射。
    private static final Pattern CITATION_PATTERN = Pattern.compile("\\[S\\d+]");

    public List<ReviewFinding> evaluate(String reportContent) {
        List<ReviewFinding> findings = new ArrayList<>();
        int paragraphIndex = 0;
        for (String paragraph : reportContent.split("\\R\\R+")) {
            String trimmed = paragraph.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("##")) {
                paragraphIndex++;
                continue;
            }
            // 先用确定性规则兜底，保证即使 LLM 质检漏判也能抓住“无引用结论”。
            if (looksLikeClaim(trimmed) && !CITATION_PATTERN.matcher(trimmed).find()) {
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
            paragraphIndex++;
        }
        return findings;
    }

    private boolean looksLikeClaim(String paragraph) {
        return paragraph.contains("机会")
                || paragraph.contains("风险")
                || paragraph.contains("优势")
                || paragraph.contains("弱势")
                || paragraph.contains("建议")
                || paragraph.contains("更适合");
    }

    private String abbreviate(String text) {
        return text.length() <= 80 ? text : text.substring(0, 80) + "...";
    }
}
