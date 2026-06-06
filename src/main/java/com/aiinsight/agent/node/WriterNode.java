package com.aiinsight.agent.node;

import com.aiinsight.model.enums.AgentName;
import com.aiinsight.model.run.AnalysisArtifact;
import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.model.enums.ConfidenceLevel;
import com.aiinsight.model.enums.ArtifactType;
import com.aiinsight.model.enums.ReviewAction;
import com.aiinsight.model.review.ReviewDecision;
import com.aiinsight.model.run.EvidenceSource;
import com.aiinsight.model.schema.AnalysisClaim;
import com.aiinsight.model.schema.CompetitorProfile;
import com.aiinsight.llm.ChatMessage;
import com.aiinsight.llm.ChatOptions;
import com.aiinsight.llm.ChatRequest;
import com.aiinsight.llm.LlmClient;
import com.aiinsight.agent.AgentNode;
import com.aiinsight.observability.AgentTraceContext;
import com.aiinsight.util.AgentUtils;
import com.aiinsight.util.TermExtractor;
import com.aiinsight.util.TermExtractor.TermOptions;
import static com.aiinsight.util.AgentUtils.CITATION_PATTERN;
import static com.aiinsight.util.AgentUtils.abbreviate;
import static com.aiinsight.util.AgentUtils.containsAny;
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
import java.util.Locale;
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

    private static final Pattern CLAIM_REFERENCE_PATTERN = Pattern.compile("\\[C-[^\\]]+]");
    private static final int MAX_UPSTREAM_ARTIFACT_CHARS_FOR_PROMPT = 2_200;
    private static final TermOptions REPORT_LINE_TERM_OPTIONS = TermOptions.basic(3);
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
                9. 先根据“用户需求、分析维度、结构化结论、竞品画像”归纳本次最重要的 3-6 个对比维度，再决定报告小节；不要把示例结构当成固定模板。
                10. 建议结构可参考：一句话结论、建议优先级、关键洞察、竞品对比、风险与证据缺口、下一步补证清单；如果用户目标或证据形态不适合某个小节，可以合并、改名或省略。
                11. 报告主体只写“已验证/可初步判断”的内容；“待验证/证据不足”集中放到“风险与证据缺口”或“下一步补证清单”，不要铺满对比表。
                12. 如果某个维度只有公开说明而没有体验证据，请写成“公开资料显示...”而不是直接判定体验优劣。
                13. 不要把竞品固定归类为某条路线、某类用户或某种商业模式；只有结构化结论或证据明确支持时才可下这种判断。
                14. 不要输出“结构化结论”作为自然语言句子的尾巴或来源名；内部 Claim ID 应直接移除，不能替换成面向用户的词。
                15. 不要出现 Analyst、Reviewer、Researcher、Writer、打回采集、重跑 Agent 等内部流程措辞。
                16. 如果 Reviewer 修复计划包含结构化修复任务，优先只修订 task 定位的 paragraph/excerpt/currentText；不要为了一个 citation 问题重写整份报告。
                17. 每个 task 必须满足 expectedFix 和 criteria；无法满足时，把对应表述降级为“待验证/证据不足”，并放入风险与证据缺口。
                18. 如果证据 authority/quality 为 USER_PROVIDED 或 INTERNAL_ONLY，只能写成“用户提供资料/内部资料显示”；不要写成“公开资料显示”“市场证据显示”或“外部验证显示”，除非同一结论还绑定了公开/官方来源。
                19. 被 Reviewer 点名的 excerpt/currentText 不能原样保留；必须补 citation、降级措辞、删除无证据表述，或明确移动到风险与证据缺口。
                20. 返工输出必须相对上一版报告有可见变化；不要只调整标题、顺序或措辞而保留同一个阻塞问题。
                21. 结构化结论中的 supportStatus=UNVERIFIED、recommendedPlacement=VALIDATION_BACKLOG/NONE、confidence=LOW 或 evidence=[] 的内容，只能放入风险与证据缺口/下一步补证清单，不能写入主结论、建议优先级或 SWOT 正向判断。
                22. 矩阵和 SWOT 已由 Analyst 按证据边界过滤；报告主判断必须优先使用矩阵/SWOT 中的 MEDIUM/HIGH 结论，不能把“待验证结论”改写成确定判断。
                23. supportStatus=PARTIAL 或 confidence=MEDIUM 的结论只能写成“可参考、可进一步评估、公开资料显示”，不要写成“优势、表现突出、明确优先借鉴”。
                24. 只有 supportStatus=SUPPORTED、confidence=HIGH 且证据索引显示 authority=FIRST_PARTY_* 的结论，才可以进入“一句话结论”和“建议优先级”的强建议。
                25. 第三方、社区、镜像或 UNKNOWN authority 来源只能用于提出线索和补证方向，不能单独支撑竞品优势判断。

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
        ).tagged(name().name(), "report-draft"));
    }

    private String sanitizeReportText(AnalysisRun run, String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String cleaned = removeReportMetadata(text);
        cleaned = removeInternalClaimReferences(cleaned);
        cleaned = removeLeakedVerificationMarkers(cleaned);
        cleaned = sanitizeCitationText(run, cleaned);
        return enforceCitationDiscipline(run, cleaned);
    }

    private String removeLeakedVerificationMarkers(String text) {
        return text.lines()
                .map(this::normalizeLeakedVerificationMarker)
                .collect(Collectors.joining("\n"));
    }

    private String normalizeLeakedVerificationMarker(String line) {
        if (!hasText(line)) {
            return line;
        }
        String normalized = line.replaceFirst("^(\\s*)待验证：\\s*(\\d+[.、]\\s*)", "$1$2待验证：");
        normalized = normalized.replaceFirst("^(\\s*[-*]\\s*)待验证：\\s*(\\d+[.、]\\s*)", "$1$2待验证：");
        return normalized;
    }

    private String enforceCitationDiscipline(AnalysisRun run, String text) {
        return text.lines()
                .map(line -> enforceLineCitation(run, line))
                .collect(Collectors.joining("\n"))
                .trim();
    }

    private String enforceLineCitation(AnalysisRun run, String line) {
        String normalizedLine = normalizeCitationPlacement(line);
        if (CITATION_PATTERN.matcher(line).find()) {
            return needsOverclaimDowngrade(run, normalizedLine) ? prefixUnverified(normalizedLine) : normalizedLine;
        }
        if (!needsClaimCitation(run, normalizedLine)) {
            return normalizedLine;
        }
        List<String> citations = citationsForLine(run, normalizedLine);
        if (!citations.isEmpty()) {
            return appendCitations(normalizedLine, citations);
        }
        return prefixUnverified(normalizedLine);
    }

    private String appendCitations(String line, List<String> citations) {
        String citationText = citations.stream()
                .limit(3)
                .map("[%s]"::formatted)
                .collect(Collectors.joining(""));
        if (isMarkdownTableRow(line)) {
            return appendCitationsToTableRow(line, citationText);
        }
        return appendCitationsToText(line, citationText);
    }

    private String appendCitationsToText(String line, String citationText) {
        int trailingStart = line.length();
        while (trailingStart > 0 && Character.isWhitespace(line.charAt(trailingStart - 1))) {
            trailingStart--;
        }
        String body = line.substring(0, trailingStart);
        String trailing = line.substring(trailingStart);
        if (!body.isEmpty() && isSentenceEndingPunctuation(body.charAt(body.length() - 1))) {
            return body.substring(0, body.length() - 1) + citationText + body.charAt(body.length() - 1) + trailing;
        }
        return body + citationText + trailing;
    }

    private String appendCitationsToTableRow(String line, String citationText) {
        String[] cells = line.split("\\|", -1);
        for (int i = cells.length - 1; i >= 0; i--) {
            if (hasText(cells[i])) {
                cells[i] = appendCitationsToText(cells[i], citationText);
                break;
            }
        }
        return String.join("|", cells);
    }

    private String normalizeCitationPlacement(String line) {
        Matcher matcher = Pattern.compile("([。！？；.!?;])\\s*((?:\\[S\\d+]\\s*)+)(?=$|\\s|\\|)").matcher(line);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String citations = matcher.group(2).replaceAll("\\s+", "");
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(citations + matcher.group(1)));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private boolean isSentenceEndingPunctuation(char value) {
        return "。！？；.!?;".indexOf(value) >= 0;
    }

    private String prefixUnverified(String line) {
        String trimmed = line.trim();
        if (trimmed.startsWith("待验证") || trimmed.startsWith("- 待验证") || trimmed.startsWith("* 待验证")) {
            return line;
        }
        if (trimmed.matches("^\\d+[.、]\\s*待验证[:：].*")) {
            return line;
        }
        if (trimmed.matches("^\\d+[.、]\\s+.*")) {
            return line.replaceFirst("^(\\s*\\d+[.、]\\s*)", "$1待验证：");
        }
        if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
            return line.replaceFirst("^(\\s*[-*]\\s*)", "$1待验证：");
        }
        return line.replaceFirst("^(\\s*)", "$1待验证：");
    }

    private boolean needsOverclaimDowngrade(AnalysisRun run, String line) {
        if (!hasText(line)) {
            return false;
        }
        String normalized = normalizeText(line);
        boolean unsupportedImpactLanguage = containsAny(normalized,
                "直接提升", "提升效率", "效率提升", "工作流效率", "生产力", "roi", "降本", "提效",
                "适合借鉴", "满足平台", "适用于", "最佳选择", "优先采用");
        if (!unsupportedImpactLanguage) {
            return false;
        }
        Set<String> lineTerms = supportTerms(line);
        return run.getClaims().stream()
                .filter(this::mainReportClaim)
                .noneMatch(claim -> claimMatchesLine(claim, lineTerms)
                        && containsAny(normalizeText(claim.getContent()),
                        "直接提升", "提升效率", "效率提升", "工作流效率", "生产力", "roi", "降本", "提效",
                        "适合借鉴", "满足平台", "适用于", "最佳选择", "优先采用"));
    }

    private boolean needsClaimCitation(AnalysisRun run, String line) {
        if (!hasText(line)) {
            return false;
        }
        String trimmed = line.trim();
        if (trimmed.startsWith("#") || isMarkdownTableSeparator(trimmed)) {
            return false;
        }
        String normalized = normalizeText(tableText(trimmed));
        boolean mentionsCompetitor = run.getRequirement() != null && run.getRequirement().getCompetitors().stream()
                .filter(AgentUtils::hasText)
                .anyMatch(competitor -> normalized.contains(normalizeText(competitor)));
        boolean judgment = containsAny(normalized,
                "相比", "对比", "路径", "侧重", "优先", "建议", "风险", "机会", "更适合", "更强", "更弱", "领先", "不足",
                "显示", "表明", "判断", "推荐", "应该", "需要", "可以",
                "compare", "compared", "better", "stronger", "weaker", "recommend", "risk", "opportunity");
        return mentionsCompetitor && judgment;
    }

    private List<String> citationsForLine(AnalysisRun run, String line) {
        Set<String> lineTerms = supportTerms(tableText(line));
        return run.getClaims().stream()
                .filter(this::mainReportClaim)
                .filter(claim -> claimMatchesLine(claim, lineTerms))
                .flatMap(claim -> claim.getEvidenceIds().stream())
                .filter(knownCitationKeys(run)::contains)
                .distinct()
                .limit(3)
                .toList();
    }

    private boolean claimMatchesLine(AnalysisClaim claim, Set<String> lineTerms) {
        if (lineTerms.isEmpty()) {
            return false;
        }
        Set<String> claimTerms = supportTerms(claim.getContent());
        long overlap = lineTerms.stream().filter(claimTerms::contains).count();
        return overlap >= Math.min(2, Math.min(lineTerms.size(), claimTerms.size()));
    }

    private boolean isMarkdownTableRow(String line) {
        return line != null && line.trim().startsWith("|") && line.contains("|");
    }

    private boolean isMarkdownTableSeparator(String line) {
        return line != null && line.matches("^\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)+\\|?\\s*$");
    }

    private String tableText(String line) {
        if (!isMarkdownTableRow(line)) {
            return line;
        }
        return line.replace('|', ' ');
    }

    private Set<String> supportTerms(String text) {
        return TermExtractor.extract(text, REPORT_LINE_TERM_OPTIONS);
    }

    private String normalizeText(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private String removeInternalClaimReferences(String text) {
        return CLAIM_REFERENCE_PATTERN.matcher(text)
                .replaceAll("")
                .lines()
                .map(line -> line
                        .replaceAll("\\s+([，。！？；：,.!?;:])", "$1")
                        .replaceAll("[ \\t]{2,}", " ")
                        .trim())
                .collect(Collectors.joining("\n"))
                .trim();
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
        String mainClaims = run.getClaims().stream()
                .filter(this::mainReportClaim)
                .map(claim -> claimLine(run, claim))
                .collect(Collectors.joining("\n"));
        String backlogClaims = run.getClaims().stream()
                .filter(claim -> !mainReportClaim(claim))
                .map(claim -> claimLine(run, claim))
                .collect(Collectors.joining("\n"));
        return """
                主报告可用结论（只能从这里写主结论、建议优先级和正向判断）:
                %s

                待验证或弱支撑结论（只能写入风险与证据缺口/下一步补证，不得改写成确定判断）:
                %s
                """.formatted(
                mainClaims.isBlank() ? "- 暂无主报告可用结论。" : mainClaims,
                backlogClaims.isBlank() ? "- 暂无待验证结论。" : backlogClaims
        );
    }

    private boolean mainReportClaim(AnalysisClaim claim) {
        if (claim == null) {
            return false;
        }
        if (claim.getEligibleForMainReport() != null) {
            return Boolean.TRUE.equals(claim.getEligibleForMainReport());
        }
        return claim.getConfidence() != ConfidenceLevel.LOW
                && claim.getEvidenceIds() != null
                && !claim.getEvidenceIds().isEmpty()
                && !"UNVERIFIED".equalsIgnoreCase(textOrDefault(claim.getSupportStatus(), ""))
                && !"VALIDATION_BACKLOG".equalsIgnoreCase(textOrDefault(claim.getRecommendedPlacement(), ""))
                && !"NONE".equalsIgnoreCase(textOrDefault(claim.getRecommendedPlacement(), ""));
    }

    private String claimLine(AnalysisRun run, AnalysisClaim claim) {
        return "- id=%s type=%s confidence=%s status=%s placement=%s eligibleMain=%s dimension=%s competitors=%s evidence=%s evidenceAuthority=%s quotes=%s supportReason=%s missingEvidence=%s rewrite=%s reason=%s content=%s".formatted(
                claim.getId(),
                claim.getType(),
                claim.getConfidence(),
                textOrDefault(claim.getSupportStatus(), "-"),
                textOrDefault(claim.getRecommendedPlacement(), "-"),
                claim.getEligibleForMainReport(),
                textOrDefault(claim.getDimension(), "-"),
                claim.getCompetitorNames(),
                claim.getEvidenceIds(),
                evidenceAuthoritySummary(run, claim),
                claim.getEvidenceQuotes(),
                textOrDefault(claim.getSupportReason(), "-"),
                claim.getMissingEvidenceTypes(),
                textOrDefault(claim.getRewriteSuggestion(), "-"),
                textOrDefault(claim.getPlacementReason(), "-"),
                claim.getContent()
        );
    }

    private String evidenceAuthoritySummary(AnalysisRun run, AnalysisClaim claim) {
        return claim.getEvidenceIds().stream()
                .map(id -> run.getEvidenceSources().stream()
                        .filter(source -> id.equals(source.getCitationKey()))
                        .findFirst()
                        .map(source -> "%s:%s/%s/%s".formatted(
                                id,
                                effectiveSourceAuthority(source),
                                textOrDefault(source.getSourceQuality(), "UNKNOWN"),
                                textOrDefault(source.getSourceType(), "unknown")
                        ))
                        .orElse(id + ":UNKNOWN"))
                .collect(Collectors.joining(", "));
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
            indexedSources = run.getEvidenceSources().stream()
                    .limit(8)
                    .toList();
        }
        if (indexedSources.isEmpty()) {
            return "暂无可引用证据。";
        }
        return indexedSources.stream()
                .map(source -> "[%s] %s | type=%s | authority=%s | quality=%s | status=%s\nURL: %s\n摘要: %s".formatted(
                        source.getCitationKey(),
                        textOrDefault(source.getTitle(), "未命名来源"),
                        textOrDefault(source.getSourceType(), "unknown"),
                        effectiveSourceAuthority(source),
                        textOrDefault(source.getSourceQuality(), "UNKNOWN"),
                        textOrDefault(source.getCollectionStatus(), "UNKNOWN"),
                        source.getUrl(),
                        abbreviate(source.getSnippet(), 180)
                ))
                .collect(Collectors.joining("\n\n"));
    }

    private String effectiveSourceAuthority(EvidenceSource source) {
        if (source == null) {
            return "THIRD_PARTY_GENERAL";
        }
        String quality = textOrDefault(source.getSourceQuality(), "UNKNOWN").toUpperCase(Locale.ROOT);
        String status = textOrDefault(source.getCollectionStatus(), "UNKNOWN").toUpperCase(Locale.ROOT);
        String authority = textOrDefault(source.getSourceAuthority(), "UNKNOWN");
        if ("INTERNAL_ONLY".equals(quality) || "USER_PROVIDED".equals(status)) {
            return "UNKNOWN".equalsIgnoreCase(authority) || "USER_PROVIDED".equalsIgnoreCase(authority)
                    ? quality
                    : authority;
        }
        if (writerThirdPartyLikeSource(source)) {
            return "THIRD_PARTY_GENERAL";
        }
        return authority;
    }

    private boolean writerThirdPartyLikeSource(EvidenceSource source) {
        String authority = textOrDefault(source.getSourceAuthority(), "UNKNOWN").toUpperCase(Locale.ROOT);
        String sourceType = normalizeText(source.getSourceType());
        if (authority.startsWith("THIRD_PARTY")
                || "COMMUNITY".equals(authority)
                || "SEARCH_SNIPPET".equals(authority)
                || sourceType.startsWith("third_party")
                || sourceType.contains("public_review")) {
            return true;
        }
        String host = AgentEvidenceSupport.sourceHost(source.getUrl());
        return host.endsWith(".ac.cn") || AgentEvidenceSupport.titleSuggestsDifferentPublisher(host, source.getTitle());
    }

    private String repairPlanBlock(AnalysisRun run) {
        ReviewDecision decision = run.getRepairDecisionFor(AgentName.WRITER);
        if (decision == null || decision.getAction() == ReviewAction.PASS) {
            return "当前不是复核修复模式。";
        }
        String instructions = decision.getRepairInstructions().isEmpty()
                ? "暂无具体修复指令。"
                : decision.getRepairInstructions().stream()
                .map(instruction -> "- " + instruction)
                .collect(Collectors.joining("\n"));
        String tasks = decision.getRepairTasks().isEmpty()
                ? "暂无结构化修复任务。"
                : decision.getRepairTasks().stream()
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
                decision.getAction(),
                decision.getTargetAgent(),
                textOrDefault(decision.getRepairScopeSummary(), "未记录修复范围"),
                decision.getAffectedClaimIds(),
                decision.getRequiredEvidenceTypes(),
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
                        : abbreviate(artifact.getContent(), MAX_UPSTREAM_ARTIFACT_CHARS_FOR_PROMPT))
                .orElse("暂无 " + type + " 产物。");
    }

}
