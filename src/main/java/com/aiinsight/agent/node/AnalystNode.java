package com.aiinsight.agent.node;

import com.aiinsight.model.enums.AgentName;
import com.aiinsight.model.schema.AnalysisClaim;
import com.aiinsight.model.run.AnalysisArtifact;
import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.model.enums.ArtifactType;
import com.aiinsight.model.enums.ClaimType;
import com.aiinsight.model.enums.ConfidenceLevel;
import com.aiinsight.model.run.EvidenceSource;
import com.aiinsight.agent.AgentNode;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

@Component
// Analyst 消费结构化竞品信息，输出横向对比和机会点。
// 它不负责补采资料；发现证据不足应由 Reviewer 打回 Researcher。
public class AnalystNode implements AgentNode {

    @Override
    public AgentName name() {
        return AgentName.ANALYST;
    }

    @Override
    public String title() {
        return "横向对比与机会点分析";
    }

    @Override
    public AnalysisRun execute(AnalysisRun run) {
        run.getClaims().clear();
        run.getClaims().add(comparisonClaim(run));
        run.getClaims().add(opportunityClaim(run));
        run.getClaims().add(riskClaim(run));

        // MVP 先用矩阵表达对比结果，后续会拆成 SWOT、功能树差异和机会点 Claim。
        String rows = run.getCompetitorProfiles().stream()
                .map(profile -> "| %s | %s | %s | %s |".formatted(
                        profile.getProductName(),
                        String.join("、", profile.getStrengths()),
                        String.join("、", profile.getWeaknesses()),
                        String.join(", ", profile.getEvidenceIds())
                ))
                .collect(Collectors.joining("\n"));
        String content = """
                | 竞品 | 主要优势 | 潜在弱势 | 证据 |
                | --- | --- | --- | --- |
                %s

                机会点: 以“可溯源报告 + Reviewer 复核 + 单 Agent 重跑”切入，区别于只做内容生成的工具。
                """.formatted(rows);
        run.getArtifacts().add(new AnalysisArtifact(
                ArtifactType.COMPETITIVE_MATRIX,
                "竞品横向矩阵",
                content,
                run.getEvidenceSources().stream().map(EvidenceSource::getCitationKey).toList()
        ));
        return run;
    }

    private AnalysisClaim comparisonClaim(AnalysisRun run) {
        AnalysisClaim claim = baseClaim(run);
        claim.setType(ClaimType.COMPARISON);
        claim.setContent("主要竞品都在协作、权限和 AI 生成方向建设能力。");
        claim.setConfidence(ConfidenceLevel.HIGH);
        return claim;
    }

    private AnalysisClaim opportunityClaim(AnalysisRun run) {
        AnalysisClaim claim = baseClaim(run);
        claim.setType(ClaimType.OPPORTUNITY);
        claim.setContent("以可溯源报告、Reviewer 复核和单 Agent 重跑切入，可以区别于只做内容生成的工具。");
        claim.setConfidence(ConfidenceLevel.MEDIUM);
        return claim;
    }

    private AnalysisClaim riskClaim(AnalysisRun run) {
        AnalysisClaim claim = baseClaim(run);
        claim.setType(ClaimType.RISK);
        claim.setContent("当前价格策略和用户评价证据不足，商业模式结论需要标注待验证。");
        claim.setConfidence(ConfidenceLevel.LOW);
        return claim;
    }

    private AnalysisClaim baseClaim(AnalysisRun run) {
        AnalysisClaim claim = new AnalysisClaim();
        claim.setGeneratedBy(name().name());
        claim.setCompetitorNames(run.getRequirement().getCompetitors());
        claim.setEvidenceIds(run.getEvidenceSources().stream().map(EvidenceSource::getCitationKey).toList());
        return claim;
    }
}
