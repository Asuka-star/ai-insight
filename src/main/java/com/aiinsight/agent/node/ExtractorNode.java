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
import com.aiinsight.model.run.EvidenceSource;
import com.aiinsight.observability.AgentTraceContext;
import com.aiinsight.service.fallback.FallbackExtractionFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class ExtractorNode implements AgentNode {

    private final LlmClient llmClient;
    private final FallbackExtractionFactory fallbackExtractionFactory;

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
        // Extractor 的结构化对象由 deterministic factory 先生成，保证无 LLM 时也有
        // CompetitorProfile / FeatureTree / PricingModel / Persona 可供下游消费。
        run.getCompetitorProfiles().clear();
        run.getCompetitorProfiles().addAll(fallbackExtractionFactory.buildProfiles(run));

        String fallbackContent = fallbackExtractionFactory.buildMarkdown(run);
        String content;
        if (llmClient.isAvailable()) {
            // LLM 只优化可读 Markdown Schema，不直接覆盖结构化对象；
            // 这样下游 Analyst 不依赖模型是否严格返回结构化 JSON。
            try {
                content = extractWithLlm(run, fallbackContent);
            } catch (RuntimeException ex) {
                log.warn("Extractor fallback activated: runId={}, reason=llm_exception, exceptionType={}, message={}, competitors={}, evidenceSources={}",
                        run.getId(),
                        ex.getClass().getName(),
                        ex.getMessage(),
                        run.getRequirement().getCompetitors(),
                        run.getEvidenceSources().size());
                run.getRecommendedActions().add("LLM Schema 抽取失败，已使用规则 Schema 兜底：" + ex.getMessage());
                content = fallbackContent;
                AgentTraceContext.recordFallback("deterministic-extractor-fallback", content);
            }
        } else {
            log.warn("Extractor fallback activated: runId={}, reason=llm_unavailable, competitors={}, evidenceSources={}",
                    run.getId(),
                    run.getRequirement().getCompetitors(),
                    run.getEvidenceSources().size());
            content = fallbackContent;
            AgentTraceContext.recordFallback("deterministic-extractor-fallback", content);
        }
        run.addArtifact(new AnalysisArtifact(
                ArtifactType.COMPETITOR_PROFILE,
                "竞品知识 Schema",
                content,
                run.getEvidenceSources().stream().map(EvidenceSource::getCitationKey).toList()
        ));
        return run;
    }

    private String extractWithLlm(AnalysisRun run, String deterministicSchema) {
        String prompt = """
                你是竞品分析工作流中的结构化抽取 Agent。请把证据片段整理成简洁的中文 Markdown Schema 视图。
                输出约束：
                1. 除产品名、专有名词、枚举值、URL 和 [S1] 这类引用编号外，全部使用中文。
                2. 必须保留原始产品名和证据编号。
                3. 不要编造价格、套餐、客户案例、营收或任何证据中没有的信息。
                4. 不确定字段请标注“待验证”。
                5. 输出应覆盖产品定位、功能树、定价模型、用户画像、优势、弱势和证据编号。

                竞品列表：
                %s

                证据片段：
                %s

                确定性 Schema 草稿：
                %s
                """.formatted(
                run.getRequirement().getCompetitors(),
                run.getEvidenceSources().stream()
                        .map(source -> "[%s] %s: %s".formatted(source.getCitationKey(), source.getTitle(), source.getSnippet()))
                        .collect(Collectors.joining("\n")),
                deterministicSchema
        );
        return llmClient.complete(new ChatRequest(
                List.of(
                        ChatMessage.system("你负责从证据中抽取竞品知识 Schema。请保留证据编号，并使用中文输出。"),
                        ChatMessage.user(prompt)
                ),
                ChatOptions.extractor()
        ));
    }
}
