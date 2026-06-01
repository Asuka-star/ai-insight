package com.aiinsight.agent.node;

import com.aiinsight.agent.AgentNode;
import com.aiinsight.model.enums.AgentName;
import com.aiinsight.model.enums.ArtifactType;
import com.aiinsight.model.enums.ReviewAction;
import com.aiinsight.model.enums.ReviewSeverity;
import com.aiinsight.model.run.AnalysisArtifact;
import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.model.run.WorkflowTransition;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Component
public class FinalizerNode implements AgentNode {

    @Override
    public AgentName name() {
        return AgentName.FINALIZER;
    }

    @Override
    public String title() {
        return "生成最终封版报告";
    }

    @Override
    public AnalysisRun execute(AnalysisRun run) {
        AnalysisArtifact draft = latestArtifact(run, ArtifactType.REPORT_DRAFT);
        if (draft == null) {
            return run;
        }

        AnalysisArtifact finalReport = new AnalysisArtifact(
                ArtifactType.FINAL_REPORT,
                "可溯源竞品分析报告（最终封版）",
                draft.getContent() + finalReportReviewNote(run),
                draft.getCitationKeys()
        );
        run.addArtifact(finalReport);
        run.getRecommendedActions().add(recommendedAction(run));
        return run;
    }

    private String finalReportReviewNote(AnalysisRun run) {
        return """

                ## 复核状态
                %s

                %s

                ## 定向修复计划

                %s

                ## 证据限制说明

                %s
                """.formatted(
                decisionSummary(run),
                reworkLimitNote(run),
                repairPlanSummary(run),
                evidenceLimitations(run)
        );
    }

    private String decisionSummary(AnalysisRun run) {
        if (run.getReviewFindings().isEmpty()) {
            return "Reviewer 未发现高风险引用缺失，报告可进入人工确认。";
        }
        long high = countBySeverity(run, ReviewSeverity.HIGH);
        long medium = countBySeverity(run, ReviewSeverity.MEDIUM);
        long low = countBySeverity(run, ReviewSeverity.LOW);
        return "Reviewer 当前决策为 `%s`。当前保留 %d 个 HIGH、%d 个 MEDIUM、%d 个 LOW 质检项；详细清单请查看 Reviewer 复核结果产物。目标处理 Agent：%s。".formatted(
                run.getReviewDecision().getAction(),
                high,
                medium,
                low,
                run.getReviewDecision().getTargetAgent() == null ? "无需自动打回" : run.getReviewDecision().getTargetAgent()
        );
    }

    private String reworkLimitNote(AnalysisRun run) {
        if (!finalizedBecauseReworkLimitReached(run)) {
            return "";
        }
        long reworkCount = run.getWorkflowTransitions().stream()
                .filter(transition -> transition.getRoute() != null && !"finish".equals(transition.getRoute()))
                .count();
        return """
                ## 自动返工上限说明

                自动返工已达到本次运行上限，流程已封版进入最终报告；但最后一次 ReviewDecision 仍为 `%s`，说明仍有未完全解决的复核项。当前报告不得被理解为“质检已通过”，请人工复核下方补证/修复计划，或手动从目标 Agent 继续重跑下游链路后再对外发布。已执行自动返工次数：%d。
                """.formatted(run.getReviewDecision().getAction(), reworkCount);
    }

    private String repairPlanSummary(AnalysisRun run) {
        if (run.getReviewDecision().getRepairInstructions().isEmpty()
                && run.getReviewDecision().getRepairTasks().isEmpty()) {
            return "Reviewer 未生成自动修复计划。";
        }
        String scope = run.getReviewDecision().getRepairScopeSummary() == null
                ? "未记录修复范围。"
                : run.getReviewDecision().getRepairScopeSummary();
        String instructions = run.getReviewDecision().getRepairInstructions().stream()
                .map(instruction -> "- " + instruction)
                .collect(Collectors.joining("\n"));
        String tasks = run.getReviewDecision().getRepairTasks().isEmpty()
                ? "暂无结构化修复任务。"
                : run.getReviewDecision().getRepairTasks().stream()
                .map(task -> "- `%s` -> %s，Claim=%s，Citation=%s；指令：%s；验收：%s".formatted(
                        task.getAction(),
                        task.getTargetAgent(),
                        textOrDefault(task.getClaimId(), "-"),
                        textOrDefault(task.getCitationKey(), "-"),
                        textOrDefault(task.getInstruction(), "-"),
                        textOrDefault(task.getAcceptanceCriteria(), "-")
                ))
                .collect(Collectors.joining("\n"));
        return scope + "\n\n修复指令：\n" + instructions + "\n\n结构化修复任务：\n" + tasks;
    }

    private String evidenceLimitations(AnalysisRun run) {
        List<String> requiredEvidenceTypes = run.getReviewDecision().getRequiredEvidenceTypes();
        List<String> affectedClaimIds = run.getReviewDecision().getAffectedClaimIds();
        String evidence = requiredEvidenceTypes == null || requiredEvidenceTypes.isEmpty()
                ? "暂无 Reviewer 指定的必补证据类型。"
                : "优先补充证据类型：" + String.join("、", requiredEvidenceTypes) + "。";
        String claims = affectedClaimIds == null || affectedClaimIds.isEmpty()
                ? "暂无指定 Claim 需要人工定位。"
                : "需重点复核 Claim：" + String.join("、", affectedClaimIds) + "。";
        String highGuidance = run.getReviewFindings().stream().anyMatch(finding -> finding.getSeverity() == ReviewSeverity.HIGH)
                ? "本报告仍包含 HIGH 级复核项，相关结论不应作为已验证结论对外发布。"
                : "未发现 HIGH 级复核项，可进入人工确认。";
        return "- %s\n- %s\n- %s\n- MEDIUM/LOW 提醒保留在 Reviewer 复核结果产物中，作为人工审阅依据。"
                .formatted(evidence, claims, highGuidance);
    }

    private String recommendedAction(AnalysisRun run) {
        if (finalizedBecauseReworkLimitReached(run)) {
            return "自动返工已达到本次运行上限，但 ReviewDecision 仍要求继续处理；请人工复核未解决项，或手动从目标 Agent 继续重跑下游链路后再对外发布。";
        }
        if (run.getReviewFindings().stream().anyMatch(finding -> finding.getSeverity() == ReviewSeverity.HIGH)) {
            return "最终报告仍包含 HIGH 质检项，请按 ReviewDecision 指向的 Agent 继续处理后再对外发布。";
        }
        if (!run.getReviewFindings().isEmpty()) {
            return "最终报告仅包含中低风险质检提醒，可进入人工确认并视情况补充证据。";
        }
        return "最终报告已通过 Reviewer 高风险检查，可进入人工确认。";
    }

    private boolean finalizedBecauseReworkLimitReached(AnalysisRun run) {
        if (run.getReviewDecision() == null || run.getReviewDecision().getAction() == ReviewAction.PASS) {
            return false;
        }
        return latestTransition(run)
                .map(transition -> "finish".equals(transition.getRoute())
                        && AgentName.FINALIZER.name().equals(transition.getTargetNode())
                        && "auto-review-gate".equals(transition.getTrigger()))
                .orElse(false);
    }

    private Optional<WorkflowTransition> latestTransition(AnalysisRun run) {
        List<WorkflowTransition> transitions = run.getWorkflowTransitions();
        if (transitions.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(transitions.get(transitions.size() - 1));
    }

    private long countBySeverity(AnalysisRun run, ReviewSeverity severity) {
        return run.getReviewFindings().stream()
                .filter(finding -> finding.getSeverity() == severity)
                .count();
    }

    private String textOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
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
