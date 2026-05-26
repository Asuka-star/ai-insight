package com.aiinsight.agent.node;

import com.aiinsight.model.enums.AgentName;
import com.aiinsight.model.run.AnalysisArtifact;
import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.model.enums.ArtifactType;
import com.aiinsight.model.run.EvidenceSource;
import com.aiinsight.model.schema.AnalysisClaim;
import com.aiinsight.model.schema.CompetitorProfile;
import com.aiinsight.llm.ChatMessage;
import com.aiinsight.llm.ChatOptions;
import com.aiinsight.llm.ChatRequest;
import com.aiinsight.llm.LlmClient;
import com.aiinsight.agent.AgentNode;
import com.aiinsight.observability.AgentTraceContext;
import com.aiinsight.service.fallback.FallbackReportDraftFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
// Writer 只负责把上游结构化产物组织成报告草稿，不直接采集新事实。
// 它必须把关键结论绑定 citationKey，否则 Reviewer 会判为高风险问题。
public class WriterNode implements AgentNode {

    private static final Pattern CITATION_PATTERN = Pattern.compile("\\[(S\\d+)]");

    private final LlmClient llmClient;
    private final FallbackReportDraftFactory fallbackReportDraftFactory;

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
        // 未配置 LLM 时走 fallback，保证演示环境和单测不依赖外部模型。
        String content;
        if (llmClient.isAvailable()) {
            try {
                content = generateWithLlm(run);
            } catch (RuntimeException ex) {
                log.warn("Writer fallback activated: runId={}, reason=llm_exception, exceptionType={}, message={}, competitors={}, evidenceSources={}, claims={}, artifacts={}",
                        run.getId(),
                        ex.getClass().getName(),
                        ex.getMessage(),
                        run.getRequirement().getCompetitors(),
                        run.getEvidenceSources().size(),
                        run.getClaims().size(),
                        run.getArtifacts().size());
                run.getRecommendedActions().add("LLM 报告生成失败，已使用规则报告兜底：" + ex.getMessage());
                content = fallbackReportDraftFactory.build(run);
                AgentTraceContext.recordFallback("deterministic-writer-fallback", content);
            }
        } else {
            log.warn("Writer fallback activated: runId={}, reason=llm_unavailable, competitors={}, evidenceSources={}, claims={}, artifacts={}",
                    run.getId(),
                    run.getRequirement().getCompetitors(),
                    run.getEvidenceSources().size(),
                    run.getClaims().size(),
                    run.getArtifacts().size());
            content = fallbackReportDraftFactory.build(run);
            AgentTraceContext.recordFallback("deterministic-writer-fallback", content);
        }
        // Writer 是最终 Markdown 的入口，必须在 artifact 落库前清理未知 citation；
        // 否则 Reviewer 会发现不存在的来源，且前端 citation 定位也会失效。
        content = sanitizeCitationText(run, content);
        List<String> citations = extractKnownCitationKeys(run, content);
        AnalysisArtifact artifact = new AnalysisArtifact(ArtifactType.REPORT_DRAFT, "竞品分析报告草稿", content, citations);
        run.addArtifact(artifact);
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
                5. 必须优先使用“结构化结论”和“竞品画像 Schema”，不要只改写 artifact 文本。

                用户需求:
                %s

                竞品:
                %s

                结构化结论:
                %s

                竞品画像 Schema:
                %s

                采集包缺口与一手洞察:
                %s

                已采集证据:
                %s

                中间产物:
                %s
                """.formatted(
                run.getRequirement().getOriginalPrompt(),
                String.join(", ", run.getRequirement().getCompetitors()),
                claimsBlock(run),
                competitorProfileBlock(run),
                researchPackageBlock(run),
                evidenceBlock(run),
                artifactBlock(run)
        );
        return llmClient.complete(new ChatRequest(
                List.of(
                        ChatMessage.system("你是严谨的竞品分析报告撰写 Agent，所有结论都要有证据意识。"),
                        ChatMessage.user(prompt)
                ),
                ChatOptions.writer()
        ));
    }

    private String sanitizeCitationText(AnalysisRun run, String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        Set<String> known = knownCitationKeys(run);
        Matcher matcher = CITATION_PATTERN.matcher(text);
        StringBuffer sanitized = new StringBuffer();
        while (matcher.find()) {
            String key = matcher.group(1);
            if (known.contains(key)) {
                matcher.appendReplacement(sanitized, Matcher.quoteReplacement(matcher.group(0)));
            } else {
                matcher.appendReplacement(sanitized, "证据不足");
            }
        }
        matcher.appendTail(sanitized);
        return sanitized.toString();
    }

    private List<String> extractKnownCitationKeys(AnalysisRun run, String text) {
        Set<String> known = knownCitationKeys(run);
        Set<String> citations = new LinkedHashSet<>();
        Matcher matcher = CITATION_PATTERN.matcher(text == null ? "" : text);
        while (matcher.find()) {
            String key = matcher.group(1);
            if (known.contains(key)) {
                citations.add(key);
            }
        }
        return citations.stream().toList();
    }

    private Set<String> knownCitationKeys(AnalysisRun run) {
        return run.getEvidenceSources().stream()
                .map(EvidenceSource::getCitationKey)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private String claimsBlock(AnalysisRun run) {
        if (run.getClaims().isEmpty()) {
            return "暂无结构化结论。";
        }
        return run.getClaims().stream()
                .map(claim -> "- id=%s type=%s confidence=%s competitors=%s evidence=%s content=%s".formatted(
                        claim.getId(),
                        claim.getType(),
                        claim.getConfidence(),
                        claim.getCompetitorNames(),
                        claim.getEvidenceIds(),
                        claim.getContent()
                ))
                .collect(Collectors.joining("\n"));
    }

    private String competitorProfileBlock(AnalysisRun run) {
        if (run.getCompetitorProfiles().isEmpty()) {
            return "暂无竞品画像。";
        }
        return run.getCompetitorProfiles().stream()
                .map(this::profileLine)
                .collect(Collectors.joining("\n"));
    }

    private String profileLine(CompetitorProfile profile) {
        return "- product=%s positioning=%s targetUsers=%s strengths=%s weaknesses=%s pricing=%s evidence=%s".formatted(
                profile.getProductName(),
                profile.getPositioning(),
                profile.getTargetUsers(),
                profile.getStrengths(),
                profile.getWeaknesses(),
                profile.getPricingModel() == null ? "待验证" : profile.getPricingModel().getStrategySummary(),
                profile.getEvidenceIds()
        );
    }

    private String researchPackageBlock(AnalysisRun run) {
        String gaps = run.getResearchPackage().getMissingEvidenceTypes().isEmpty()
                ? "暂无关键缺口"
                : String.join("、", run.getResearchPackage().getMissingEvidenceTypes());
        String insights = run.getResearchPackage().getInterviewInsights().isEmpty()
                ? "暂无访谈洞察"
                : run.getResearchPackage().getInterviewInsights().stream()
                .map(insight -> "- [%s] role=%s pain=%s concern=%s".formatted(
                        insight.getEvidenceId(),
                        insight.getIntervieweeRole(),
                        insight.getPainPoints(),
                        insight.getBuyingConcerns()
                ))
                .collect(Collectors.joining("\n"));
        return "证据缺口：" + gaps + "\n访谈洞察：\n" + insights;
    }

    private String evidenceBlock(AnalysisRun run) {
        // Prompt 同时给完整来源和相关 chunk：完整来源用于溯源说明，
        // chunk 用于把长网页压缩成 Writer 更容易消费的证据片段。
        String sources = run.getEvidenceSources().stream()
                .map(source -> "[%s] %s\nURL: %s\n片段: %s".formatted(
                        source.getCitationKey(),
                        source.getTitle(),
                        source.getUrl(),
                        source.getSnippet()
                ))
                .collect(Collectors.joining("\n\n"));
        String chunks = relevantChunks(run, writerQuery(run), 8).stream()
                .map(chunk -> "- [%s/%s] %s".formatted(chunk.getSourceCitationKey(), chunk.getChunkKey(), chunk.getText()))
                .collect(Collectors.joining("\n"));
        if (chunks.isBlank()) {
            return sources;
        }
        return sources + "\n\n相关证据切片:\n" + chunks;
    }

    private String artifactBlock(AnalysisRun run) {
        return run.getArtifacts().stream()
                .filter(artifact -> artifact.getType() != ArtifactType.REPORT_DRAFT)
                .map(artifact -> "## %s\n%s".formatted(artifact.getTitle(), artifact.getContent()))
                .collect(Collectors.joining("\n\n"));
    }

    private String writerQuery(AnalysisRun run) {
        String claims = run.getClaims().stream()
                .map(AnalysisClaim::getContent)
                .collect(Collectors.joining(" "));
        return run.getRequirement().getOriginalPrompt()
                + " " + String.join(" ", run.getRequirement().getDimensions())
                + " " + claims;
    }

    private List<com.aiinsight.model.run.EvidenceChunk> relevantChunks(AnalysisRun run, String query, int limit) {
        Set<String> queryTerms = terms(query);
        if (queryTerms.isEmpty()) {
            return run.getEvidenceChunks().stream().limit(limit).toList();
        }
        return run.getEvidenceChunks().stream()
                .map(chunk -> new ChunkScore(chunk, score(chunk.getTitle() + " " + chunk.getText(), queryTerms)))
                .filter(scored -> scored.score() > 0)
                .sorted((left, right) -> Double.compare(right.score(), left.score()))
                .limit(limit)
                .map(ChunkScore::chunk)
                .toList();
    }

    private double score(String text, Set<String> queryTerms) {
        String normalized = (text == null ? "" : text).toLowerCase();
        double score = 0;
        for (String term : queryTerms) {
            if (normalized.contains(term)) {
                score += term.length() <= 2 ? 0.5 : 1.0;
            }
        }
        return score;
    }

    private Set<String> terms(String text) {
        Set<String> terms = new LinkedHashSet<>();
        String normalized = (text == null ? "" : text).toLowerCase()
                .replaceAll("[^\\p{IsHan}a-z0-9]+", " ")
                .trim();
        for (String part : normalized.split("\\s+")) {
            if (part.length() >= 2) {
                terms.add(part);
            }
        }
        String chineseOnly = normalized.replaceAll("[^\\p{IsHan}]", "");
        for (int i = 0; i < chineseOnly.length() - 1; i++) {
            terms.add(chineseOnly.substring(i, i + 2));
        }
        return terms;
    }

    private record ChunkScore(com.aiinsight.model.run.EvidenceChunk chunk, double score) {
    }
}
