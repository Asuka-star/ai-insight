package com.aiinsight.agent.node;

import com.aiinsight.agent.AgentNode;
import com.aiinsight.llm.ChatMessage;
import com.aiinsight.llm.ChatOptions;
import com.aiinsight.llm.ChatRequest;
import com.aiinsight.llm.LlmClient;
import com.aiinsight.model.enums.AgentName;
import com.aiinsight.model.enums.ArtifactType;
import com.aiinsight.model.run.AnalysisArtifact;
import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.observability.AgentTraceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class ClarifierNode implements AgentNode {

    private final LlmClient llmClient;

    @Override
    public AgentName name() {
        return AgentName.CLARIFIER;
    }

    @Override
    public String title() {
        return "澄清分析范围";
    }

    @Override
    public AnalysisRun execute(AnalysisRun run) {
        String content;
        if (llmClient.isAvailable()) {
            content = clarifyWithLlm(run);
        } else {
            content = fallbackClarification(run);
            AgentTraceContext.recordFallback("deterministic-clarifier-fallback", content);
        }
        run.getArtifacts().add(new AnalysisArtifact(ArtifactType.CLARIFICATION_BRIEF, "分析范围确认", content, List.of()));
        run.getRecommendedActions().add("确认竞品、分析维度和信息源范围，必要时补充排除项。");
        return run;
    }

    private String clarifyWithLlm(AnalysisRun run) {
        String prompt = """
                你是竞品分析工作流中的澄清 Agent。请基于用户原始需求和系统归一化后的结构化需求，输出一份简洁的中文 Markdown 范围确认说明。
                输出约束：
                1. 除产品名、专有名词、枚举值、URL 和 [S1] 这类引用编号外，全部使用中文。
                2. 必须包含：已确认行业、竞品范围、分析维度、信息源偏好、仍需确认的问题。
                3. 不要扩写成报告正文，只做任务范围澄清。
                4. 如果字段为空或不确定，请写“待确认”。

                用户原始需求：
                %s

                结构化需求：
                行业=%s
                竞品=%s
                分析维度=%s
                信息源偏好=%s
                报告用途=%s
                """.formatted(
                run.getRequirement().getOriginalPrompt(),
                run.getRequirement().getIndustry(),
                run.getRequirement().getCompetitors(),
                run.getRequirement().getDimensions(),
                run.getRequirement().getSourcePreferences(),
                run.getRequirement().getOutputGoal()
        );
        return llmClient.complete(new ChatRequest(
                List.of(
                        ChatMessage.system("你负责澄清竞品分析任务范围，必须保留结构化范围约束，并使用中文输出。"),
                        ChatMessage.user(prompt)
                ),
                ChatOptions.deterministic()
        ));
    }

    private String fallbackClarification(AnalysisRun run) {
        var requirement = run.getRequirement();
        return """
                ## 分析范围

                行业: %s
                竞品: %s
                维度: %s
                信息源偏好: %s
                """.formatted(
                requirement.getIndustry(),
                String.join(", ", requirement.getCompetitors()),
                String.join(", ", requirement.getDimensions()),
                String.join(", ", requirement.getSourcePreferences())
        );
    }
}
