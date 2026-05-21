package com.aiinsight.agent.node;

import com.aiinsight.model.AgentName;
import com.aiinsight.model.AnalysisArtifact;
import com.aiinsight.model.AnalysisRun;
import com.aiinsight.model.ArtifactType;
import com.aiinsight.model.EvidenceSource;
import com.aiinsight.model.ReviewAction;
import com.aiinsight.agent.AgentNode;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
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
        boolean recollecting = run.getReviewDecision().getAction() == ReviewAction.RECOLLECT_EVIDENCE
                && run.getReviewDecision().getTargetAgent() == name();
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
        if (recollecting) {
            index = appendSupplementalEvidence(run, index);
        }
        run.getResearchPackage().setSources(new ArrayList<>(run.getEvidenceSources()));
        run.getResearchPackage().setMissingEvidenceTypes(recollecting
                ? List.of()
                : List.of("pricing_page", "user_review"));
        run.getResearchPackage().setCollectedAt(Instant.now());
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

    private int appendSupplementalEvidence(AnalysisRun run, int index) {
        for (String competitor : run.getRequirement().getCompetitors()) {
            run.getEvidenceSources().add(new EvidenceSource(
                    "S" + index,
                    competitor + " 价格页资料",
                    "https://example.com/" + competitor.toLowerCase().replace(" ", "-") + "/pricing",
                    competitor + " 的价格页用于补充免费版、团队版、企业版等套餐信息，价格细节仍以页面原文为准。"
            ));
            index++;
            run.getEvidenceSources().add(new EvidenceSource(
                    "S" + index,
                    competitor + " 用户评价资料",
                    "https://example.com/reviews/" + competitor.toLowerCase().replace(" ", "-"),
                    competitor + " 的用户评价用于补充上手成本、协作体验和 AI 功能满意度等信息。"
            ));
            index++;
        }
        return index;
    }
}
