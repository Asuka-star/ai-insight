package com.aiinsight.agent.node;

import com.aiinsight.agent.AgentNode;
import com.aiinsight.llm.ChatMessage;
import com.aiinsight.llm.ChatOptions;
import com.aiinsight.llm.ChatRequest;
import com.aiinsight.llm.LlmClient;
import com.aiinsight.model.enums.AgentName;
import com.aiinsight.model.enums.ArtifactType;
import com.aiinsight.model.enums.ClaimType;
import com.aiinsight.model.enums.ConfidenceLevel;
import com.aiinsight.model.run.AnalysisArtifact;
import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.model.run.EvidenceSource;
import com.aiinsight.model.schema.AnalysisClaim;
import com.aiinsight.observability.AgentTraceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class AnalystNode implements AgentNode {

    private final LlmClient llmClient;

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

        String rows = run.getCompetitorProfiles().stream()
                .map(profile -> "| %s | %s | %s | %s |".formatted(
                        profile.getProductName(),
                        String.join("、", profile.getStrengths()),
                        String.join("、", profile.getWeaknesses()),
                        String.join(", ", profile.getEvidenceIds())
                ))
                .collect(Collectors.joining("\n"));
        String fallbackContent = """
                | 竞品 | 主要优势 | 潜在弱势 | 证据 |
                | --- | --- | --- | --- |
                %s

                机会点: 以“可溯源报告 + Reviewer 复核 + 单 Agent 重跑”切入，区别于只做内容生成的工具。
                """.formatted(rows);
        String content;
        if (llmClient.isAvailable()) {
            content = analyzeWithLlm(run, fallbackContent);
        } else {
            content = fallbackContent;
            AgentTraceContext.recordFallback("deterministic-analyst-fallback", content);
        }
        run.getArtifacts().add(new AnalysisArtifact(
                ArtifactType.COMPETITIVE_MATRIX,
                "竞品横向矩阵",
                content,
                run.getEvidenceSources().stream().map(EvidenceSource::getCitationKey).toList()
        ));
        return run;
    }

    private String analyzeWithLlm(AnalysisRun run, String deterministicMatrix) {
        String prompt = """
                你是竞品分析工作流中的分析 Agent。请只基于下方结构化竞品画像、分析结论和证据编号，输出中文 Markdown 竞品矩阵、机会点和风险分析。
                输出约束：
                1. 除产品名、专有名词、枚举值、URL 和 [S1] 这类引用编号外，全部使用中文。
                2. 每条关键结论必须引用证据编号，例如 [S1]。
                3. 不要编造价格、营收、客户案例、市场份额或证据中没有的信息。
                4. 证据不足时必须写“待验证”或“证据不足”，不要强行下结论。

                分析需求：
                %s

                确定性矩阵草稿：
                %s

                结构化分析结论：
                %s
                """.formatted(
                run.getRequirement().getOriginalPrompt(),
                deterministicMatrix,
                run.getClaims().stream()
                        .map(claim -> "- %s: %s evidence=%s".formatted(claim.getType(), claim.getContent(), claim.getEvidenceIds()))
                        .collect(Collectors.joining("\n"))
        );
        return llmClient.complete(new ChatRequest(
                List.of(
                        ChatMessage.system("你是严谨的竞品分析 Agent。必须引用证据，并清楚标注不确定性；输出语言使用中文。"),
                        ChatMessage.user(prompt)
                ),
                ChatOptions.deterministic()
        ));
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
