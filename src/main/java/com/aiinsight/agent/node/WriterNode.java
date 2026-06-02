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
import com.aiinsight.util.AgentUtils;
import static com.aiinsight.util.AgentUtils.CITATION_PATTERN;
import static com.aiinsight.util.AgentUtils.abbreviate;
import static com.aiinsight.util.AgentUtils.hasText;
import static com.aiinsight.util.AgentUtils.knownCitationKeys;
import static com.aiinsight.util.AgentUtils.latestArtifact;
import static com.aiinsight.util.AgentUtils.sanitizeCitationText;
import static com.aiinsight.util.AgentUtils.textOrDefault;
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
    private static final Pattern CLAIM_REFERENCE_PATTERN = Pattern.compile("\\[C-[^\\]]+]");

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
        content = sanitizeReportText(run, content);
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
                4. 报告要“结论先行”：先给可行动判断、取舍和下一步建议，再解释证据限制；不要把证据不足写成主体。
                5. 不要输出报告编号、生成日期、撰写 Agent、免责声明、"报告草稿结束" 这类元信息。
                6. 不要在正文使用 [C-...] Claim ID；Claim ID 只供内部追踪，面向用户只展示自然语言结论和 [S] 证据编号。
                7. 总字数控制在 1200-1800 字。至少包含一个“建议优先级”表，列出：建议、理由、证据、置信度、下一步。
                8. 必须优先使用“结构化结论”、竞品矩阵和 SWOT；证据索引只用于引用定位，不用于重新分析。
                9. 建议结构：一句话结论、建议优先级、关键洞察、竞品对比、风险与证据缺口、下一步补证清单。
                10. 报告主体只写“已验证/可初步判断”的内容；“待验证/证据不足”集中放到“风险与证据缺口”或“下一步补证清单”，不要铺满对比表。
                11. 如果某个维度只有公开说明而没有体验证据，请写成“公开资料显示...”而不是直接判定体验优劣。
                12. 不要出现 Analyst、Reviewer、Researcher、Writer、打回采集、重跑 Agent 等内部流程措辞。
                13. 如果 Reviewer 修复计划包含结构化修复任务，优先只修订 task 定位的 paragraph/excerpt/currentText；不要为了一个 citation 问题重写整份报告。
                14. 每个 task 必须满足 expectedFix 和 criteria；无法满足时，把对应表述降级为“待验证/证据不足”，并放入风险与证据缺口。

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

    private String sanitizeReportText(AnalysisRun run, String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String cleaned = removeReportMetadata(text);
        cleaned = CLAIM_REFERENCE_PATTERN.matcher(cleaned).replaceAll("结构化结论");
        return sanitizeCitationText(run, cleaned);
    }

    private String removeReportMetadata(String text) {
        return text.lines()
                .filter(line -> !line.matches("^\\s*报告编号[:：].*"))
                .filter(line -> !line.matches("^\\s*撰写Agent[:：].*"))
                .filter(line -> !line.matches("^\\s*生成日期[:：].*"))
                .filter(line -> !line.matches("^\\s*报告草稿结束\\s*$"))
                .collect(Collectors.joining("\n"))
                .trim();
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
                .map(task -> "- action=%s claim=%s claimContent=%s citation=%s paragraph=%s excerpt=%s currentText=%s instruction=%s expectedFix=%s criteria=%s".formatted(
                        task.getAction(),
                        textOrDefault(task.getClaimId(), "-"),
                        claimContent(run, task.getClaimId()),
                        textOrDefault(task.getCitationKey(), "-"),
                        task.getParagraphIndex() == null ? "-" : task.getParagraphIndex(),
                        textOrDefault(task.getExcerpt(), "-"),
                        textOrDefault(task.getCurrentText(), "-"),
                        textOrDefault(task.getInstruction(), "-"),
                        textOrDefault(task.getExpectedFix(), "-"),
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

    private String claimContent(AnalysisRun run, String claimId) {
        if (claimId == null || claimId.isBlank()) {
            return "-";
        }
        return run.getClaims().stream()
                .filter(claim -> claimId.equals(claim.getId()))
                .map(claim -> abbreviate(claim.getContent(), 120))
                .findFirst()
                .orElse("-");
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
        latestArtifact(run.getArtifacts(), ArtifactType.COMPETITIVE_MATRIX).ifPresent(artifact -> keys.addAll(artifact.getCitationKeys()));
        latestArtifact(run.getArtifacts(), ArtifactType.SWOT_ANALYSIS).ifPresent(artifact -> keys.addAll(artifact.getCitationKeys()));
        keys.retainAll(knownCitationKeys(run));
        return keys;
    }

    private String latestArtifactContent(AnalysisRun run, ArtifactType type) {
        return latestArtifact(run.getArtifacts(), type)
                .map(artifact -> artifact.getContent() == null || artifact.getContent().isBlank()
                        ? "暂无 " + type + " 产物。"
                        : artifact.getContent())
                .orElse("暂无 " + type + " 产物。");
    }

}
