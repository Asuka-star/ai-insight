package com.aiinsight.service.fallback;

import com.aiinsight.model.enums.ConfidenceLevel;
import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.model.schema.AnalysisClaim;
import org.springframework.stereotype.Component;

import static com.aiinsight.util.AgentUtils.nullToEmpty;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class FallbackReportDraftFactory {

    public String build(AnalysisRun run) {
        String firstCitation = run.getEvidenceSources().isEmpty() ? "S1" : run.getEvidenceSources().get(0).getCitationKey();
        String claimSummary = run.getClaims().isEmpty()
                ? "- 当前暂无结构化结论，需先补齐可引用的分析判断。"
                : run.getClaims().stream()
                .map(this::claimLine)
                .collect(Collectors.joining("\n"));
        String evidenceGap = run.getResearchPackage().getMissingEvidenceTypes().isEmpty()
                ? "当前未记录关键证据缺口，仍建议人工复核引用覆盖。"
                : "仍需补充：" + String.join("、", run.getResearchPackage().getMissingEvidenceTypes()) + "。";
        String priorityTable = run.getClaims().isEmpty()
                ? "| 建议 | 理由 | 证据 | 置信度 | 下一步 |\n| --- | --- | --- | --- | --- |\n| 先补齐结构化结论 | 当前缺少可直接发布的分析判断 | [%s] | 低 | 补充可引用证据并重新生成分析 |".formatted(firstCitation)
                : run.getClaims().stream()
                .filter(this::publishableClaim)
                .limit(5)
                .map(this::priorityLine)
                .collect(Collectors.collectingAndThen(
                        Collectors.joining("\n"),
                        rows -> rows.isBlank()
                                ? "| 建议 | 理由 | 证据 | 置信度 | 下一步 |\n| --- | --- | --- | --- | --- |\n| 先补齐可发布证据 | 当前结构化结论多为待验证或缺证据，不适合直接形成行动建议 | 待补证 | 低 | 优先补采官方/高质量来源后重跑分析 |"
                                : "| 建议 | 理由 | 证据 | 置信度 | 下一步 |\n| --- | --- | --- | --- | --- |\n" + rows
                ));
        return """
                # 竞品分析报告

                ## 一句话结论

                本次分析围绕“%s”展开，覆盖竞品：%s。当前最可靠的输出不是完整选型结论，而是把已有证据支撑的差异点转成下一轮产品取舍与补证优先级 [%s]。

                ## 建议优先级

                %s

                ## 关键洞察（结构化结论）

                %s

                ## 竞品能力矩阵

                %s

                ## 机会与风险摘要（SWOT）

                %s

                ## 风险与证据缺口

                %s

                ## 下一步验证计划

                %s

                ## 结论与建议

                - 首选借鉴方向：优先采用已有证据支撑且能直接服务输出目标的能力，低置信度结论先进入验证计划。
                - 暂不建议投入：证据不足、待验证或缺少官方来源的判断不进入当前确定借鉴范围。
                - 最终决策口径：以“建议优先级”和“竞品能力矩阵”为主，风险和验证动作分别参考前文对应章节。
                """.formatted(
                run.getRequirement().getOriginalPrompt(),
                String.join("、", run.getRequirement().getCompetitors()),
                firstCitation,
                priorityTable,
                claimSummary,
                capabilityMatrixFallback(run),
                swotSummaryFallback(run),
                riskGapFallback(run),
                evidenceGap
        );
    }

    private String claimLine(AnalysisClaim claim) {
        String citations = claim.getEvidenceIds().isEmpty()
                ? "证据不足，待验证"
                : claim.getEvidenceIds().stream().map(id -> "[" + id + "]").collect(Collectors.joining(" "));
        String status = claim.getSupportStatus() == null || claim.getSupportStatus().isBlank()
                ? "未标注支撑状态"
                : claim.getSupportStatus();
        String support = claim.getSupportReason() == null || claim.getSupportReason().isBlank()
                ? status
                : status + "：" + claim.getSupportReason();
        return "- [%s/%s/%s] %s %s".formatted(
                claim.getType(),
                claim.getConfidence(),
                support,
                claim.getContent(),
                citations
        );
    }

    private String priorityLine(AnalysisClaim claim) {
        String citations = claim.getEvidenceIds().isEmpty()
                ? "待补证"
                : claim.getEvidenceIds().stream().map(id -> "[" + id + "]").collect(Collectors.joining(" "));
        String nextStep = claim.getEvidenceIds().isEmpty()
                ? "补采证据或降级为假设"
                : "进入人工复核并补强反例";
        return "| %s | %s | %s | %s | %s |".formatted(
                abbreviate(claim.getContent(), 36),
                claim.getType(),
                citations,
                claim.getConfidence(),
                nextStep
        );
    }

    private boolean publishableClaim(AnalysisClaim claim) {
        return claim.getConfidence() != ConfidenceLevel.LOW
                && claim.getEvidenceIds() != null
                && !claim.getEvidenceIds().isEmpty()
                && !"UNVERIFIED".equalsIgnoreCase(nullToEmpty(claim.getSupportStatus()))
                && !"VALIDATION_BACKLOG".equalsIgnoreCase(nullToEmpty(claim.getRecommendedPlacement()))
                && !"NONE".equalsIgnoreCase(nullToEmpty(claim.getRecommendedPlacement()));
    }

    private String abbreviate(String value, int maxChars) {
        if (value == null || value.isBlank() || value.length() <= maxChars) {
            return value == null || value.isBlank() ? "待明确建议" : value;
        }
        return value.substring(0, maxChars) + "...";
    }

    private String capabilityMatrixFallback(AnalysisRun run) {
        if (run.getClaims().isEmpty()) {
            return "暂无竞品矩阵数据，需 LLM 生成完整报告。";
        }
        return run.getClaims().stream()
                .filter(c -> c.getDimension() != null && !c.getDimension().isBlank())
                .limit(5)
                .map(c -> "- %s: %s [%s]".formatted(
                        c.getDimension(),
                        abbreviate(c.getContent(), 60),
                        c.getEvidenceIds().isEmpty() ? "待补证" : c.getEvidenceIds().get(0)))
                .collect(Collectors.joining("\n"));
    }

    private String swotSummaryFallback(AnalysisRun run) {
        if (run.getClaims().isEmpty()) {
            return "- 优势：暂无足够证据归纳。\n- 短板：暂无足够证据归纳。\n- 机会：围绕用户目标继续补证。\n- 威胁：证据不足时避免形成确定选型。";
        }
        return run.getClaims().stream()
                .filter(c -> c.getType() != null)
                .limit(4)
                .map(c -> "- [%s/%s] %s".formatted(c.getType(), c.getConfidence(), abbreviate(c.getContent(), 60)))
                .collect(Collectors.joining("\n"));
    }

    private String riskGapFallback(AnalysisRun run) {
        long weakClaims = run.getClaims().stream()
                .filter(claim -> claim.getEvidenceIds() == null || claim.getEvidenceIds().isEmpty()
                        || claim.getConfidence() == ConfidenceLevel.LOW)
                .count();
        if (weakClaims == 0) {
            return "当前未发现低置信度结论，但仍需人工复核引用覆盖和证据新鲜度。";
        }
        return "存在 %d 条低置信度或缺少证据的结论，建议先补齐官方资料、试用记录或一手访谈后再进入确定选型。".formatted(weakClaims);
    }
}
