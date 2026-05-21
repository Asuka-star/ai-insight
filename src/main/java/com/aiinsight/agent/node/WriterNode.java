package com.aiinsight.agent.node;

import com.aiinsight.model.enums.AgentName;
import com.aiinsight.model.run.AnalysisArtifact;
import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.model.enums.ArtifactType;
import com.aiinsight.model.run.EvidenceSource;
import com.aiinsight.llm.ChatMessage;
import com.aiinsight.llm.ChatOptions;
import com.aiinsight.llm.ChatRequest;
import com.aiinsight.llm.LlmClient;
import com.aiinsight.agent.AgentNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
// Writer 只负责把上游结构化产物组织成报告草稿，不直接采集新事实。
// 它必须把关键结论绑定 citationKey，否则 Reviewer 会判为高风险问题。
public class WriterNode implements AgentNode {

    private final LlmClient llmClient;

    @Override
    public AgentName name() {
        return AgentName.WRITER;
    }

    @Override
    public String title() {
        return "生成竞品分析报告草稿";
    }

    @Override
    public AnalysisRun execute(AnalysisRun run) {
        List<String> citations = run.getEvidenceSources().stream().map(EvidenceSource::getCitationKey).toList();
        // 未配置 LLM 时走 fallback，保证演示环境和单测不依赖外部模型。
        String content = llmClient.isAvailable() ? generateWithLlm(run) : fallbackReport(run, citations);
        AnalysisArtifact artifact = new AnalysisArtifact(ArtifactType.REPORT_DRAFT, "竞品分析报告草稿", content, citations);
        run.getArtifacts().add(artifact);
        return run;
    }

    private String generateWithLlm(AnalysisRun run) {
        // Prompt 中显式传入证据和中间产物，避免模型绕过已沉淀的 Schema 状态自由发挥。
        String prompt = """
                你是竞品分析小组中的 Writer Agent。请基于给定需求、结构化产物和证据，生成一版中文竞品分析报告草稿。

                约束:
                1. 输出 Markdown。
                2. 关键结论必须使用 [S1]、[S2] 这样的证据编号。
                3. 不确定的内容要标为“待验证”，不要编造价格、营收、客户案例。
                4. 保留一个“需补充证据”小节，列出证据覆盖不足的点。

                用户需求:
                %s

                竞品:
                %s

                已采集证据:
                %s

                中间产物:
                %s
                """.formatted(
                run.getRequirement().getOriginalPrompt(),
                String.join(", ", run.getRequirement().getCompetitors()),
                evidenceBlock(run),
                artifactBlock(run)
        );
        return llmClient.complete(new ChatRequest(
                List.of(
                        ChatMessage.system("你是严谨的竞品分析报告撰写 Agent，所有结论都要有证据意识。"),
                        ChatMessage.user(prompt)
                ),
                ChatOptions.deterministic()
        ));
    }

    private String fallbackReport(AnalysisRun run, List<String> citations) {
        String firstCitation = citations.isEmpty() ? "S1" : citations.get(0);
        String opportunityCitation = run.getResearchPackage().getMissingEvidenceTypes().isEmpty()
                ? " [" + firstCitation + "]"
                : "";
        // 首轮 fallback 故意保留一个无引用机会点；补采完成后再补引用，便于演示质检前后改善。
        return """
                # 竞品分析报告草稿

                ## 结论摘要

                当前竞品普遍围绕协作、知识沉淀、权限管理和 AI 内容生成建设能力，说明“AI 协作文档”竞争正在从单点生成转向团队工作流 [%s]。

                ## 机会点

                机会点是聚焦可溯源分析、Reviewer 复核和单 Agent 重跑，把报告生产过程变成可观察的协作流程。%s

                ## 风险

                若资料源不足，价格策略、商业模式和差异化结论容易出现过度推断，需要在最终报告中显式标注证据覆盖情况 [%s]。
                """.formatted(firstCitation, opportunityCitation, firstCitation);
    }

    private String evidenceBlock(AnalysisRun run) {
        return run.getEvidenceSources().stream()
                .map(source -> "[%s] %s\nURL: %s\n片段: %s".formatted(
                        source.getCitationKey(),
                        source.getTitle(),
                        source.getUrl(),
                        source.getSnippet()
                ))
                .collect(Collectors.joining("\n\n"));
    }

    private String artifactBlock(AnalysisRun run) {
        return run.getArtifacts().stream()
                .filter(artifact -> artifact.getType() != ArtifactType.REPORT_DRAFT)
                .map(artifact -> "## %s\n%s".formatted(artifact.getTitle(), artifact.getContent()))
                .collect(Collectors.joining("\n\n"));
    }
}
