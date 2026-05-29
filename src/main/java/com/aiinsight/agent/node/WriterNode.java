package com.aiinsight.agent.node;

import com.aiinsight.model.enums.AgentName;
import com.aiinsight.model.run.AnalysisArtifact;
import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.model.enums.ArtifactType;
import com.aiinsight.model.enums.ReviewAction;
import com.aiinsight.model.run.EvidenceSource;
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
        // Prompt 只提供报告所需上下文，避免 Writer 重新做 Researcher/Analyst 的工作。
        String prompt = """
                你是竞品分析小组中的 Writer Agent。请基于给定的报告上下文，生成一版中文竞品分析报告草稿。
                你的职责是报告编排和表达，不要重新采集事实，也不要推翻 Analyst 已生成的结构化结论。

                约束:
                1. 输出 Markdown。
                2. 关键结论必须使用 evidenceIds 中已有的 [S1]、[S2] 证据编号。
                3. 不确定的内容要标为“待验证”，不要编造价格、营收、客户案例。
                4. 保留一个“需补充证据”小节，列出证据覆盖不足的点。
                5. 必须优先使用“结构化结论”、竞品矩阵和 SWOT；证据索引只用于引用定位，不用于重新分析。
                6. 建议结构：执行摘要、分析范围与证据说明、竞品定位概览、核心发现、能力/策略对比、SWOT/机会风险、对 AI Insight 的借鉴建议、需补充证据。

                用户需求:
                %s

                输出目标:
                %s

                竞品:
                %s

                分析维度:
                %s

                结构化结论:
                %s

                竞品画像摘要:
                %s

                竞品矩阵:
                %s

                SWOT 分析:
                %s

                采集包缺口与一手洞察:
                %s

                证据索引:
                %s

                Reviewer 修复计划:
                %s
                """.formatted(
                run.getRequirement().getOriginalPrompt(),
                textOrDefault(run.getRequirement().getOutputGoal(), "竞品分析报告"),
                String.join(", ", run.getRequirement().getCompetitors()),
                String.join(", ", run.getRequirement().getDimensions()),
                claimsBlock(run),
                competitorProfileBlock(run),
                latestArtifactContent(run, ArtifactType.COMPETITIVE_MATRIX),
                latestArtifactContent(run, ArtifactType.SWOT_ANALYSIS),
                researchPackageBlock(run),
                evidenceIndexBlock(run),
                repairPlanBlock(run)
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

    private String evidenceIndexBlock(AnalysisRun run) {
        Set<String> neededCitationKeys = reportCitationKeys(run);
        List<EvidenceSource> indexedSources = run.getEvidenceSources().stream()
                .filter(source -> neededCitationKeys.isEmpty() || neededCitationKeys.contains(source.getCitationKey()))
                .limit(12)
                .toList();
        if (indexedSources.isEmpty()) {
            indexedSources = run.getEvidenceSources().stream().limit(8).toList();
        }
        if (indexedSources.isEmpty()) {
            return "暂无可引用证据。";
        }
        return indexedSources.stream()
                .map(source -> "[%s] %s | type=%s | quality=%s | status=%s\nURL: %s\n摘要: %s".formatted(
                        source.getCitationKey(),
                        textOrDefault(source.getTitle(), "未命名来源"),
                        textOrDefault(source.getSourceType(), "unknown"),
                        textOrDefault(source.getSourceQuality(), "UNKNOWN"),
                        textOrDefault(source.getCollectionStatus(), "UNKNOWN"),
                        source.getUrl(),
                        abbreviate(source.getSnippet(), 180)
                ))
                .collect(Collectors.joining("\n\n"));
    }

    private String repairPlanBlock(AnalysisRun run) {
        if (run.getReviewDecision() == null || run.getReviewDecision().getAction() == ReviewAction.PASS) {
            return "当前不是复核修复模式。";
        }
        String instructions = run.getReviewDecision().getRepairInstructions().isEmpty()
                ? "暂无具体修复指令。"
                : run.getReviewDecision().getRepairInstructions().stream()
                .map(instruction -> "- " + instruction)
                .collect(Collectors.joining("\n"));
        String tasks = run.getReviewDecision().getRepairTasks().isEmpty()
                ? "暂无结构化修复任务。"
                : run.getReviewDecision().getRepairTasks().stream()
                .filter(task -> task.getTargetAgent() == AgentName.WRITER)
                .map(task -> "- action=%s claim=%s citation=%s criteria=%s".formatted(
                        task.getAction(),
                        textOrDefault(task.getClaimId(), "-"),
                        textOrDefault(task.getCitationKey(), "-"),
                        textOrDefault(task.getAcceptanceCriteria(), "-")
                ))
                .collect(Collectors.joining("\n"));
        return """
                修复动作：%s
                目标 Agent：%s
                修复范围：%s
                受影响 Claim：%s
                必补证据类型：%s
                修复指令：
                %s
                结构化修复任务：
                %s
                """.formatted(
                run.getReviewDecision().getAction(),
                run.getReviewDecision().getTargetAgent(),
                textOrDefault(run.getReviewDecision().getRepairScopeSummary(), "未记录修复范围"),
                run.getReviewDecision().getAffectedClaimIds(),
                run.getReviewDecision().getRequiredEvidenceTypes(),
                instructions,
                tasks
        );
    }

    private Set<String> reportCitationKeys(AnalysisRun run) {
        Set<String> keys = new LinkedHashSet<>();
        run.getClaims().forEach(claim -> keys.addAll(claim.getEvidenceIds()));
        run.getCompetitorProfiles().forEach(profile -> {
            keys.addAll(profile.getEvidenceIds());
            if (profile.getPricingModel() != null) {
                keys.addAll(profile.getPricingModel().getEvidenceIds());
            }
        });
        latestArtifact(run, ArtifactType.COMPETITIVE_MATRIX).ifPresent(artifact -> keys.addAll(artifact.getCitationKeys()));
        latestArtifact(run, ArtifactType.SWOT_ANALYSIS).ifPresent(artifact -> keys.addAll(artifact.getCitationKeys()));
        keys.retainAll(knownCitationKeys(run));
        return keys;
    }

    private String latestArtifactContent(AnalysisRun run, ArtifactType type) {
        return latestArtifact(run, type)
                .map(artifact -> artifact.getContent() == null || artifact.getContent().isBlank()
                        ? "暂无 " + type + " 产物。"
                        : artifact.getContent())
                .orElse("暂无 " + type + " 产物。");
    }

    private java.util.Optional<AnalysisArtifact> latestArtifact(AnalysisRun run, ArtifactType type) {
        List<AnalysisArtifact> artifacts = run.getArtifacts();
        for (int i = artifacts.size() - 1; i >= 0; i--) {
            AnalysisArtifact artifact = artifacts.get(i);
            if (artifact.getType() == type) {
                return java.util.Optional.of(artifact);
            }
        }
        return java.util.Optional.empty();
    }

    private String textOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String abbreviate(String value, int maxChars) {
        if (value == null || value.isBlank() || value.length() <= maxChars) {
            return textOrDefault(value, "暂无摘要");
        }
        return value.substring(0, maxChars) + "...";
    }
}
