package com.aiinsight.agent.node;

import com.aiinsight.model.AgentName;
import com.aiinsight.model.AnalysisArtifact;
import com.aiinsight.model.AnalysisRun;
import com.aiinsight.model.ArtifactType;
import com.aiinsight.model.EvidenceSource;
import com.aiinsight.agent.AgentNode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
// Researcher 只负责产出“可引用证据”，不直接写分析结论。
// 目前用模拟来源保证流程可跑，后续会替换为搜索、网页解析和问卷/访谈数据接入。
public class ResearcherNode implements AgentNode {

    @Override
    public AgentName name() {
        return AgentName.RESEARCHER;
    }

    @Override
    public String title() {
        return "采集资料与证据";
    }

    @Override
    public AnalysisRun execute(AnalysisRun run) {
        // 采集重跑时先清空旧证据，避免同一 citationKey 指向多个来源。
        run.getEvidenceSources().clear();
        int index = 1;
        for (String competitor : run.getRequirement().getCompetitors()) {
            run.getEvidenceSources().add(new EvidenceSource(
                    "S" + index,
                    competitor + " 官方产品资料",
                    "https://example.com/" + competitor.toLowerCase().replace(" ", "-"),
                    competitor + " 强调协作、知识沉淀、权限管理和 AI 辅助内容生成能力。"
            ));
            index++;
        }
        // 证据清单也保存为 artifact，便于工作台展示“采集 Agent 交付了什么”。
        String content = run.getEvidenceSources().stream()
                .map(source -> "- [%s] %s: %s".formatted(source.getCitationKey(), source.getTitle(), source.getSnippet()))
                .collect(Collectors.joining("\n"));
        run.getArtifacts().add(new AnalysisArtifact(
                ArtifactType.SOURCE_LIST,
                "资料采集清单",
                content,
                run.getEvidenceSources().stream().map(EvidenceSource::getCitationKey).toList()
        ));
        return run;
    }
}
