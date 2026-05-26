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
                ? "- 当前暂无结构化结论，需等待 Analyst 产出。"
                : run.getClaims().stream()
                .map(this::claimLine)
                .collect(Collectors.joining("\n"));
        String evidenceGap = run.getResearchPackage().getMissingEvidenceTypes().isEmpty()
                ? "当前未记录关键证据缺口，仍建议人工复核引用覆盖。"
                : "仍需补充：" + String.join("、", run.getResearchPackage().getMissingEvidenceTypes()) + "。";
        return """
                # 竞品分析报告草稿

                ## 结论摘要

                本次分析围绕“%s”展开，覆盖竞品：%s。报告基于 Analyst 生成的结构化结论和当前证据链整理，所有确定性判断均应回到对应 citation 核验 [%s]。

                ## 建议结论

                - 优先处理高置信、有证据支撑的差异点，作为近期产品规划或竞品跟进输入。
                - 对低置信或无证据结论，仅作为待验证假设进入后续采集清单。

                ## 结构化结论

                %s

                ## 竞品矩阵摘要

                %s

                ## SWOT 摘要

                %s

                ## 风险

                %s
                """.formatted(
                run.getRequirement().getOriginalPrompt(),
                String.join("、", run.getRequirement().getCompetitors()),
                firstCitation,
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
