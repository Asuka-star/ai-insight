package com.aiinsight.agent.node;

import com.aiinsight.model.enums.AgentName;
import com.aiinsight.model.run.AnalysisArtifact;
import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.model.enums.ArtifactType;
import com.aiinsight.model.run.EvidenceSource;
import com.aiinsight.model.enums.ReviewAction;
import com.aiinsight.agent.AgentNode;
import com.aiinsight.service.EvidenceChunkService;
import com.aiinsight.service.SourceCollectionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
// Researcher 只负责产出“可引用证据”，不直接写分析结论。
// 优先采集用户提供的公开 URL，没有 URL 时使用种子来源保证演示链路可跑。
public class ResearcherNode implements AgentNode {

    private final SourceCollectionService sourceCollectionService;
    private final EvidenceChunkService evidenceChunkService;

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
        run.getEvidenceChunks().clear();
        run.getEvidenceSources().addAll(sourceCollectionService.collect(run, recollecting));
        run.getEvidenceChunks().addAll(evidenceChunkService.chunk(run.getEvidenceSources()));
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
}
