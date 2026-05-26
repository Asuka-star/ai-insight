package com.aiinsight.agent.node;

import com.aiinsight.model.enums.AgentName;
import com.aiinsight.model.enums.ReviewSeverity;
import com.aiinsight.model.run.AnalysisArtifact;
import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.model.enums.ArtifactType;
import com.aiinsight.agent.AgentNode;
import com.aiinsight.model.review.ReviewFinding;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
// Revision 表示“根据质检结果修订”的动作。
// 之后接 DAG 时，它会成为 Reviewer 打回 Writer 后的一个条件节点。
public class RevisionNode implements AgentNode {

    @Override
    public AgentName name() {
        return AgentName.REVISION;
    }

    @Override
    public String title() {
        return "根据复核结果修订报告";
    }

    @Override
    public AnalysisRun execute(AnalysisRun run) {
        AnalysisArtifact draft = latestArtifact(run, ArtifactType.REPORT_DRAFT);
        if (draft == null) {
            return run;
        }
        // Revision 是自动流程的收口节点：它不重新采集事实，而是把 Reviewer 的结构化
        // 决策和 findings 写回最终报告，提醒人工确认哪些结论仍不能直接对外发布。
        AnalysisArtifact revised = new AnalysisArtifact(
                ArtifactType.FINAL_REPORT,
                "可溯源竞品分析报告",
                draft.getContent() + revisionNote(run),
                draft.getCitationKeys()
        );
        run.addArtifact(revised);
        run.getRecommendedActions().add(recommendedAction(run));
        return run;
    }

    private String revisionNote(AnalysisRun run) {
        return """

                ## 复核结论

                %s

                ## 质检问题摘要

                %s

                ## 人工复核建议

                %s
                """.formatted(
                decisionSummary(run),
                findingSummary(run),
                humanReviewGuidance(run)
        );
    }

    private String decisionSummary(AnalysisRun run) {
        if (run.getReviewFindings().isEmpty()) {
            return "Reviewer 未发现高风险引用缺失，报告可进入人工确认。";
        }
        long high = countBySeverity(run, ReviewSeverity.HIGH);
        long medium = countBySeverity(run, ReviewSeverity.MEDIUM);
        long low = countBySeverity(run, ReviewSeverity.LOW);
        return "Reviewer 当前决策为 `%s`。共保留 %d 个 HIGH、%d 个 MEDIUM、%d 个 LOW 质检项；目标处理 Agent：%s。".formatted(
                run.getReviewDecision().getAction(),
                high,
                medium,
                low,
                run.getReviewDecision().getTargetAgent() == null ? "无需自动打回" : run.getReviewDecision().getTargetAgent()
        );
    }

    private String findingSummary(AnalysisRun run) {
        if (run.getReviewFindings().isEmpty()) {
            return "- 暂无结构化质检问题。";
        }
        return run.getReviewFindings().stream()
                .sorted((left, right) -> Integer.compare(severityRank(right), severityRank(left)))
                .map(this::findingLine)
                .collect(Collectors.joining("\n"));
    }

    private String findingLine(ReviewFinding finding) {
        String location = List.of(
                        hasText(finding.getClaimId()) ? "claim=" + finding.getClaimId() : "",
                        hasText(finding.getCitationKey()) ? "citation=[" + finding.getCitationKey() + "]" : "",
                        finding.getParagraphIndex() == null ? "" : "paragraph=" + finding.getParagraphIndex()
                ).stream()
                .filter(this::hasText)
                .collect(Collectors.joining(", "));
        return "- [%s] %s：%s%s；建议：%s".formatted(
                finding.getSeverity(),
                finding.getCategory(),
                finding.getMessage(),
                hasText(location) ? "（" + location + "）" : "",
                finding.getRecommendation()
        );
    }

    private String humanReviewGuidance(AnalysisRun run) {
        // 最终报告保留 requiredEvidenceTypes 和 affectedClaimIds，
        // 这样答辩或人工复核时能直接说明“下一步该补什么、看哪条 claim”。
        List<String> requiredEvidenceTypes = run.getReviewDecision().getRequiredEvidenceTypes();
        List<String> affectedClaimIds = run.getReviewDecision().getAffectedClaimIds();
        String evidence = requiredEvidenceTypes == null || requiredEvidenceTypes.isEmpty()
                ? "暂无 Reviewer 指定的必补证据类型。"
                : "优先补充证据类型：" + String.join("、", requiredEvidenceTypes) + "。";
        String claims = affectedClaimIds == null || affectedClaimIds.isEmpty()
                ? "暂无指定 Claim 需要人工定位。"
                : "需重点复核 Claim：" + String.join("、", affectedClaimIds) + "。";
        return "- %s\n- %s\n- 对 MEDIUM/LOW 提醒可以先保留为人工复核项，不阻断演示；HIGH 问题不应作为已验证结论对外发布。"
                .formatted(evidence, claims);
    }

    private String recommendedAction(AnalysisRun run) {
        if (run.getReviewFindings().stream().anyMatch(finding -> finding.getSeverity() == ReviewSeverity.HIGH)) {
            return "最终报告仍包含 HIGH 质检项，请按 ReviewDecision 指向的 Agent 继续处理后再对外发布。";
        }
        if (!run.getReviewFindings().isEmpty()) {
            return "最终报告仅包含中低风险质检提醒，可进入人工确认并视情况补充证据。";
        }
        return "最终报告已通过 Reviewer 高风险检查，可进入人工确认。";
    }

    private long countBySeverity(AnalysisRun run, ReviewSeverity severity) {
        return run.getReviewFindings().stream()
                .filter(finding -> finding.getSeverity() == severity)
                .count();
    }

    private int severityRank(ReviewFinding finding) {
        if (finding.getSeverity() == ReviewSeverity.HIGH) {
            return 3;
        }
        if (finding.getSeverity() == ReviewSeverity.MEDIUM) {
            return 2;
        }
        return 1;
    }

    private boolean hasText(String text) {
        return text != null && !text.isBlank();
    }

    private AnalysisArtifact latestArtifact(AnalysisRun run, ArtifactType type) {
        List<AnalysisArtifact> artifacts = run.getArtifacts();
        for (int i = artifacts.size() - 1; i >= 0; i--) {
            if (artifacts.get(i).getType() == type) {
                return artifacts.get(i);
            }
        }
        return null;
    }
}
