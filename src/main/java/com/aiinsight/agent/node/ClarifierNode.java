package com.aiinsight.agent.node;

import com.aiinsight.agent.AgentNode;
import com.aiinsight.model.enums.AgentName;
import com.aiinsight.model.enums.ArtifactType;
import com.aiinsight.model.run.AnalysisArtifact;
import com.aiinsight.model.run.AnalysisRequirement;
import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.model.run.ClarificationDraft;
import com.aiinsight.observability.AgentTraceContext;
import com.aiinsight.service.ClarificationDraftService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class ClarifierNode implements AgentNode {

    private final ClarificationDraftService clarificationDraftService;

    @Override
    public AgentName name() {
        return AgentName.CLARIFIER;
    }

    @Override
    public String title() {
        return "澄清任务范围";
    }

    @Override
    public AnalysisRun execute(AnalysisRun run) {
        ClarificationDraft previous = run.getClarificationDraft();
        var result = clarificationDraftService.clarifyScope(run.getRequirement());
        ClarificationDraft draft = result.draft();
        preserveConfirmationState(draft, previous);
        run.setClarificationDraft(draft);
        applyDraftToRequirement(run.getRequirement(), draft);

        String brief = clarificationBriefMarkdown(run.getRequirement(), draft);
        if (result.fallbackUsed()) {
            AgentTraceContext.recordFallback("deterministic-clarifier-fallback", brief);
        }
        if (StringUtils.hasText(result.fallbackReason())) {
            run.getRecommendedActions().add(result.fallbackReason());
        }
        run.addArtifact(new AnalysisArtifact(
                ArtifactType.CLARIFICATION_BRIEF,
                "任务理解与范围摘要",
                brief,
                List.of()
        ));
        return run;
    }

    private void preserveConfirmationState(ClarificationDraft draft, ClarificationDraft previous) {
        if (previous == null) {
            return;
        }
        draft.setConfirmed(previous.isConfirmed());
        draft.setConfirmedAt(previous.getConfirmedAt());
        draft.setCreatedAt(previous.getCreatedAt() == null ? Instant.now() : previous.getCreatedAt());
    }

    private void applyDraftToRequirement(AnalysisRequirement requirement, ClarificationDraft draft) {
        if (requirement == null || draft == null) {
            return;
        }
        if (StringUtils.hasText(draft.getIndustry())) {
            requirement.setIndustry(draft.getIndustry());
        }
        if (!draft.getCompetitors().isEmpty()) {
            requirement.setCompetitors(new ArrayList<>(draft.getCompetitors()));
        }
        if (!draft.getDimensions().isEmpty()) {
            requirement.setDimensions(new ArrayList<>(draft.getDimensions()));
        }
        if (!draft.getSourcePreferences().isEmpty()) {
            requirement.setSourcePreferences(new ArrayList<>(draft.getSourcePreferences()));
        }
        if (!draft.getSourceUrls().isEmpty()) {
            requirement.setSourceUrls(new ArrayList<>(draft.getSourceUrls()));
        }
        if (StringUtils.hasText(draft.getOutputGoal())) {
            requirement.setOutputGoal(draft.getOutputGoal());
        }
    }

    private String clarificationBriefMarkdown(AnalysisRequirement requirement, ClarificationDraft draft) {
        return """
                ## 任务范围

                - 行业/场景：%s
                - 竞品：%s
                - 分析维度：%s
                - 资料偏好：%s
                - 报告用途：%s

                ## 待确认问题

                %s

                ## 执行说明

                Clarifier 已将确认后的范围同步为结构化任务输入；下游 Agent 只能围绕该范围采集证据、抽取 Schema、生成分析和报告。
                """.formatted(
                textOrFallback(draft.getIndustry(), requirement == null ? null : requirement.getIndustry(), "待澄清"),
                listText(draft.getCompetitors()),
                listText(draft.getDimensions()),
                listText(draft.getSourcePreferences()),
                textOrFallback(draft.getOutputGoal(), requirement == null ? null : requirement.getOutputGoal(), "待确认"),
                draft.getClarificationQuestions().isEmpty()
                        ? "- 暂无新增确认问题。"
                        : draft.getClarificationQuestions().stream()
                        .map(question -> "- " + question)
                        .collect(Collectors.joining("\n"))
        );
    }

    private String listText(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "待确认";
        }
        return String.join("、", values);
    }

    private String textOrFallback(String preferred, String fallback, String defaultValue) {
        if (StringUtils.hasText(preferred)) {
            return preferred;
        }
        if (StringUtils.hasText(fallback)) {
            return fallback;
        }
        return defaultValue;
    }
}
