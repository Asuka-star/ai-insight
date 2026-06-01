package com.aiinsight.service.fallback;

import com.aiinsight.model.enums.ArtifactType;
import com.aiinsight.model.run.AnalysisArtifact;
import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.model.schema.AnalysisClaim;
import org.springframework.stereotype.Component;

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
                .limit(5)
                .map(this::priorityLine)
                .collect(Collectors.joining("\n", "| 建议 | 理由 | 证据 | 置信度 | 下一步 |\n| --- | --- | --- | --- | --- |\n", ""));
        return """
                # 竞品分析报告

                ## 一句话结论

                本次分析围绕“%s”展开，覆盖竞品：%s。当前最可靠的输出不是完整选型结论，而是把已有证据支撑的差异点转成下一轮产品取舍与补证优先级 [%s]。

                ## 建议优先级

                %s

                ## 关键洞察（结构化结论）

                %s

                ## 竞品对比（竞品矩阵摘要）

                %s

                ## 机会与风险（SWOT 摘要）

                %s

                ## 下一步补证清单

                %s
                """.formatted(
                run.getRequirement().getOriginalPrompt(),
                String.join("、", run.getRequirement().getCompetitors()),
                firstCitation,
                priorityTable,
                claimSummary,
                latestArtifact(run, ArtifactType.COMPETITIVE_MATRIX),
                latestArtifact(run, ArtifactType.SWOT_ANALYSIS),
                evidenceGap
        );
    }

    private String claimLine(AnalysisClaim claim) {
        String citations = claim.getEvidenceIds().isEmpty()
                ? "证据不足，待验证"
                : claim.getEvidenceIds().stream().map(id -> "[" + id + "]").collect(Collectors.joining(" "));
        return "- [%s/%s] %s %s".formatted(
                claim.getType(),
                claim.getConfidence(),
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

    private String abbreviate(String value, int maxChars) {
        if (value == null || value.isBlank() || value.length() <= maxChars) {
            return value == null || value.isBlank() ? "待明确建议" : value;
        }
        return value.substring(0, maxChars) + "...";
    }

    private String latestArtifact(AnalysisRun run, ArtifactType type) {
        List<AnalysisArtifact> artifacts = run.getArtifacts();
        for (int i = artifacts.size() - 1; i >= 0; i--) {
            AnalysisArtifact artifact = artifacts.get(i);
            if (artifact.getType() == type) {
                return artifact.getContent();
            }
        }
        return "暂无 " + type + " 产物。";
    }
}
