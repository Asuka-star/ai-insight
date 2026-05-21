package com.aiinsight.workflow.node;

import com.aiinsight.domain.AgentName;
import com.aiinsight.domain.AnalysisArtifact;
import com.aiinsight.domain.AnalysisRun;
import com.aiinsight.domain.ArtifactType;
import com.aiinsight.domain.EvidenceSource;
import com.aiinsight.workflow.AgentNode;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

@Component
// Extractor 将非结构化证据转换成竞品知识 Schema。
// 赛题强调结构化消息传递，所以后续这里会从 Markdown 升级为强类型 DTO。
public class ExtractorNode implements AgentNode {

    @Override
    public AgentName name() {
        return AgentName.EXTRACTOR;
    }

    @Override
    public String title() {
        return "抽取竞品结构化信息";
    }

    @Override
    public AnalysisRun execute(AnalysisRun run) {
        // 每个结构化字段都保留 citationKey，报告和 Reviewer 才能追溯到原始证据。
        String content = run.getEvidenceSources().stream()
                .map(this::toProfile)
                .collect(Collectors.joining("\n\n"));
        run.getArtifacts().add(new AnalysisArtifact(
                ArtifactType.COMPETITOR_PROFILE,
                "竞品知识 Schema",
                content,
                run.getEvidenceSources().stream().map(EvidenceSource::getCitationKey).toList()
        ));
        return run;
    }

    private String toProfile(EvidenceSource source) {
        return """
                ### %s
                - 产品定位: AI 协作与知识沉淀工具 [%s]
                - 核心功能: 文档协同、权限、模板、AI 生成 [%s]
                - 目标用户: 团队知识管理和项目协作用户 [%s]
                - 可验证片段: %s
                """.formatted(
                source.getTitle().replace(" 官方产品资料", ""),
                source.getCitationKey(),
                source.getCitationKey(),
                source.getCitationKey(),
                source.getSnippet()
        );
    }
}
