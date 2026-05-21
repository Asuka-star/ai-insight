package com.aiinsight.agent.node;

import com.aiinsight.model.AgentName;
import com.aiinsight.model.AnalysisArtifact;
import com.aiinsight.model.AnalysisRun;
import com.aiinsight.model.ArtifactType;
import com.aiinsight.model.EvidenceSource;
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
        // MVP 先用矩阵表达对比结果，后续会拆成 SWOT、功能树差异和机会点 Claim。
        String rows = run.getEvidenceSources().stream()
                .map(source -> "| %s | 协作、权限、AI 生成 | 团队导入成本 | %s |".formatted(
                        source.getTitle().replace(" 官方产品资料", ""),
                        source.getCitationKey()
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
}
