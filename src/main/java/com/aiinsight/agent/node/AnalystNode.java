package com.aiinsight.agent.node;

import com.aiinsight.agent.AgentNode;
import com.aiinsight.llm.ChatMessage;
import com.aiinsight.llm.ChatOptions;
import com.aiinsight.llm.ChatRequest;
import com.aiinsight.llm.LlmClient;
import com.aiinsight.model.enums.AgentName;
import com.aiinsight.model.enums.ArtifactType;
import com.aiinsight.model.enums.ClaimType;
import com.aiinsight.model.enums.ConfidenceLevel;
import com.aiinsight.model.enums.ReviewAction;
import com.aiinsight.model.run.AnalysisArtifact;
import com.aiinsight.model.run.AnalysisRequirement;
import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.model.run.EvidenceSource;
import com.aiinsight.model.schema.AnalysisClaim;
import com.aiinsight.model.schema.CompetitorProfile;
import com.aiinsight.observability.AgentTraceContext;
import com.aiinsight.service.AnalysisDraft;
import com.aiinsight.service.fallback.FallbackAnalysisDraftFactory;
import com.aiinsight.util.JsonResponseExtractor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
// Analyst 是结构化分析层：把 Extractor 沉淀的竞品画像和证据索引转化为可复核 Claims，
// 再基于这些 Claims 生成矩阵和 SWOT，避免 Writer 在报告阶段重新承担分析判断。
public class AnalystNode implements AgentNode {

    private static final Pattern CITATION_PATTERN = Pattern.compile("\\[(S\\d+)]");

    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;
    private final FallbackAnalysisDraftFactory fallbackAnalysisDraftFactory;

    @Override
    public AgentName name() {
        return AgentName.ANALYST;
    }

    @Override
    public String title() {
        return "横向对比与机会点分析";
    }

    @Override
    public AnalysisRun execute(AnalysisRun run) {
        boolean llmAvailable = llmClient.isAvailable();
        AnalysisDraft draft = llmAvailable ? analysisDraftWithLlm(run) : null;
        if (draft == null || draft.claims().isEmpty()) {
            log.warn("Analyst fallback activated: runId={}, reason={}, competitors={}, evidenceSources={}, profiles={}, existingClaims={}",
                    run.getId(),
                    llmAvailable ? "llm_failed_or_unparseable" : "llm_unavailable",
                    run.getRequirement().getCompetitors(),
                    run.getEvidenceSources().size(),
                    run.getCompetitorProfiles().size(),
                    run.getClaims().size());
            draft = fallbackAnalysisDraftFactory.build(run);
            AgentTraceContext.recordFallback("deterministic-analyst-fallback", draft.traceOutput());
        }
        draft = sanitizeDraft(run, draft);
        List<String> citationKeys = artifactCitationKeys(run, draft);

        run.getClaims().clear();
        run.getClaims().addAll(draft.claims());
        run.addArtifact(new AnalysisArtifact(
                ArtifactType.COMPETITIVE_MATRIX,
                "竞品横向矩阵",
                draft.matrixMarkdown(),
                citationKeys
        ));
        run.addArtifact(new AnalysisArtifact(
                ArtifactType.SWOT_ANALYSIS,
                "SWOT 分析",
                draft.swotMarkdown(),
                citationKeys
        ));
        return run;
    }

    private AnalysisDraft analysisDraftWithLlm(AnalysisRun run) {
        AnalysisDraft fallback = fallbackAnalysisDraftFactory.build(run);
        AnalystContext context = analystContext(run);
        LlmSubtaskResult<List<AnalysisClaim>> claimsResult = runAnalystSubtask(
                run,
                "claims",
                () -> generateClaimsWithLlm(context)
        );
        List<AnalysisClaim> effectiveClaims = claimsResult.succeeded() && !claimsResult.value().isEmpty()
                ? claimsResult.value()
                : fallback.claims();
        List<LlmSubtaskResult<?>> results = List.of(claimsResult);
        recordAnalystTrace(results);
        results.stream()
                .filter(result -> !result.succeeded())
                .forEach(result -> run.getRecommendedActions().add(
                        "LLM 分析子任务失败，已对该字段使用规则兜底：" + result.name() + " - " + result.errorMessage()));
        if (results.stream().noneMatch(LlmSubtaskResult::succeeded)) {
            String reasons = results.stream()
                    .map(result -> result.name() + ": " + result.errorMessage())
                    .collect(Collectors.joining("；"));
            run.getRecommendedActions().add("LLM 分析生成失败，已使用规则分析兜底：" + reasons);
            return null;
        }
        return new AnalysisDraft(
                effectiveClaims,
                renderMatrixFromClaims(context, effectiveClaims),
                renderSwotFromClaims(effectiveClaims)
        );
    }

    private List<AnalysisClaim> generateClaimsWithLlm(AnalystContext context) {
        String prompt = """
                你是竞品分析工作流中的分析 Agent。请只生成结构化 claims，不要生成矩阵或 SWOT。
                你的职责是把 Extractor 生成的事实画像转化为可复核的分析断言。
                矩阵和 SWOT 会由系统基于你生成的 claims 统一渲染。

                输出约束：
                1. 只输出 JSON，不要 Markdown 代码块。
                2. claims 必须是数组，最多 8 条；每条 claim 包含 type、content、confidence、competitorNames、evidenceIds。
                3. type 只能取 FACT、COMPARISON、STRENGTH、WEAKNESS、OPPORTUNITY、RISK、RECOMMENDATION。
                4. confidence 只能取 LOW、MEDIUM、HIGH。
                5. content 不超过 120 字，必须围绕用户关注维度、业务目标或已采集证据生成。
                6. evidenceIds 只能使用已知证据编号；证据不足时可以为空，但 content 必须明确写“待验证”或“证据不足”。
                7. 不要输出矩阵、SWOT、报告正文或其他展示型字段。
                8. 不要编造价格、营收、客户案例、市场份额或证据中没有的信息。
                9. 不要把“证据不足”本身当成主要洞察；RISK 类型最多 1 条，其余优先产出有证据支撑的差异、取舍和建议。
                10. 对已有 strong/medium 证据覆盖的维度，不要写“待验证”；应给出保守但可行动的判断。

                JSON 结构：
                {
                  "claims": [
                    {
                      "type": "OPPORTUNITY",
                      "content": "结论正文",
                      "confidence": "MEDIUM",
                      "competitorNames": ["竞品名"],
                      "evidenceIds": ["S1"]
                    }
                  ]
                }

                分析需求：
                %s

                结构化竞品画像：
                %s

                证据索引：
                %s

                按维度整理的证据覆盖：
                %s

                证据缺口与一手洞察：
                %s

                Reviewer 修复计划：
                %s
                """.formatted(
                context.requirementSummary(),
                context.profileBlock(),
                context.evidenceIndex(),
                context.dimensionEvidence(),
                context.researchContext(),
                context.repairPlan()
        );
        String raw = llmClient.complete(new ChatRequest(
                List.of(
                        ChatMessage.system("你是严谨的竞品分析 Agent。你必须输出可解析 JSON，并让每条结论都能追溯到证据或明确标注待验证。"),
                        ChatMessage.user(prompt)
                ),
                ChatOptions.analyst()
        ));
        AnalysisDraft parsed = parseAnalysisDraft(raw, context.run());
        if (parsed == null || parsed.claims().isEmpty()) {
            throw new IllegalStateException("无法解析 claims JSON");
        }
        return parsed.claims();
    }

    private <T> LlmSubtaskResult<T> runAnalystSubtask(AnalysisRun run, String name, LlmSubtask<T> subtask) {
        try {
            return new LlmSubtaskResult<>(name, subtask.run(), null);
        } catch (Exception ex) {
            log.warn("Analyst LLM subtask failed: name={}, exceptionType={}, message={}, competitors={}, evidenceSources={}, profiles={}",
                    name,
                    ex.getClass().getName(),
                    ex.getMessage(),
                    run.getRequirement().getCompetitors(),
                    run.getEvidenceSources().size(),
                    run.getCompetitorProfiles().size());
            return new LlmSubtaskResult<>(name, null, ex.getMessage());
        }
    }

    private void recordAnalystTrace(List<LlmSubtaskResult<?>> results) {
        String summary = results.stream()
                .map(result -> "%s=%s%s".formatted(
                        result.name(),
                        result.succeeded() ? "succeeded" : "failed",
                        result.succeeded() ? "" : " (" + result.errorMessage() + ")"
                ))
                .collect(Collectors.joining("\n"));
        AgentTraceContext.recordProcessSummary("Analyst LLM subtasks:\n" + summary);
    }

    private AnalysisDraft parseAnalysisDraft(String raw, AnalysisRun run) {
        if (!hasText(raw)) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(extractJson(raw));
            JsonNode claimsNode = root.has("claims") ? root.get("claims") : root;
            List<ClaimDraft> claimDrafts = objectMapper.convertValue(claimsNode, new TypeReference<>() {
            });
            List<AnalysisClaim> claims = claimDrafts.stream()
                    .map(draft -> toClaim(draft, run))
                    .filter(claim -> claim != null && hasText(claim.getContent()))
                    .toList();
            return new AnalysisDraft(claims, null, null);
        } catch (IllegalArgumentException | JsonProcessingException ex) {
            return null;
        }
    }

    private AnalysisClaim toClaim(ClaimDraft draft, AnalysisRun run) {
        if (draft == null || !hasText(draft.content)) {
            return null;
        }
        AnalysisClaim claim = new AnalysisClaim();
        claim.setGeneratedBy(name().name());
        claim.setType(parseClaimType(draft.type));
        claim.setContent(draft.content.trim());
        claim.setConfidence(parseConfidence(draft.confidence, draft.evidenceIds));
        claim.setCompetitorNames(normalizeCompetitorNames(run, draft.competitorNames));
        // evidenceIds 是 claim 进入 Writer/Reviewer 的硬约束，只允许已知 citation；
        // 模型编造的 [S404] 会被过滤，避免后续报告携带不可追溯引用。
        claim.setEvidenceIds(distinctKnownEvidenceIds(run, draft.evidenceIds));
        adjustClaimConfidence(run, claim);
        if (claim.getEvidenceIds().isEmpty() && !containsUncertaintyMarker(claim.getContent())) {
            claim.setContent(claim.getContent() + "（证据不足，待验证）");
        }
        return claim;
    }

    private AnalysisDraft sanitizeDraft(AnalysisRun run, AnalysisDraft draft) {
        List<AnalysisClaim> claims = draft.claims().stream()
                .map(claim -> sanitizeClaim(run, claim))
                .toList();
        return new AnalysisDraft(
                claims,
                sanitizeCitationText(run, draft.matrixMarkdown()),
                sanitizeCitationText(run, draft.swotMarkdown())
        );
    }

    private AnalysisClaim sanitizeClaim(AnalysisRun run, AnalysisClaim claim) {
        claim.setEvidenceIds(distinctKnownEvidenceIds(run, claim.getEvidenceIds()));
        if (claim.getEvidenceIds().isEmpty()) {
            claim.setConfidence(ConfidenceLevel.LOW);
            if (!containsUncertaintyMarker(claim.getContent())) {
                claim.setContent(claim.getContent() + "（证据不足，待验证）");
            }
            return claim;
        }
        adjustClaimConfidence(run, claim);
        return claim;
    }

    private void adjustClaimConfidence(AnalysisRun run, AnalysisClaim claim) {
        if (claim.getEvidenceIds().isEmpty()) {
            claim.setConfidence(ConfidenceLevel.LOW);
            return;
        }
        if (containsUncertaintyMarker(claim.getContent())) {
            claim.setConfidence(ConfidenceLevel.LOW);
            return;
        }
        Map<String, EvidenceSource> sources = sourceByCitationKey(run);
        int strongestEvidence = claim.getEvidenceIds().stream()
                .map(sources::get)
                .mapToInt(this::evidenceConfidenceScore)
                .max()
                .orElse(0);
        if (claim.getConfidence() == ConfidenceLevel.HIGH && strongestEvidence < 3) {
            claim.setConfidence(ConfidenceLevel.MEDIUM);
        }
        if (claim.getConfidence() == ConfidenceLevel.MEDIUM && strongestEvidence < 2) {
            claim.setConfidence(ConfidenceLevel.LOW);
        }
    }

    private String sanitizeCitationText(AnalysisRun run, String text) {
        if (!hasText(text)) {
            return "";
        }
        // 对矩阵/SWOT 这类 Markdown 文本也做 citation 白名单清洗；
        // 未知引用直接降级成“证据不足”，让 Reviewer 聚焦真实问题而不是模型幻觉编号。
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

    private List<String> artifactCitationKeys(AnalysisRun run, AnalysisDraft draft) {
        Set<String> keys = new LinkedHashSet<>();
        draft.claims().stream()
                .flatMap(claim -> claim.getEvidenceIds().stream())
                .forEach(keys::add);
        extractCitationKeys(draft.matrixMarkdown()).forEach(keys::add);
        extractCitationKeys(draft.swotMarkdown()).forEach(keys::add);
        Set<String> known = knownCitationKeys(run);
        return keys.stream().filter(known::contains).toList();
    }

    private List<String> extractCitationKeys(String text) {
        if (!hasText(text)) {
            return List.of();
        }
        List<String> keys = new ArrayList<>();
        Matcher matcher = CITATION_PATTERN.matcher(text);
        while (matcher.find()) {
            keys.add(matcher.group(1));
        }
        return keys;
    }

    private Set<String> knownCitationKeys(AnalysisRun run) {
        return run.getEvidenceSources().stream()
                .map(EvidenceSource::getCitationKey)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Map<String, EvidenceSource> sourceByCitationKey(AnalysisRun run) {
        return run.getEvidenceSources().stream()
                .filter(source -> hasText(source.getCitationKey()))
                .collect(Collectors.toMap(
                        EvidenceSource::getCitationKey,
                        source -> source,
                        (first, ignored) -> first
                ));
    }

    private List<String> distinctKnownEvidenceIds(AnalysisRun run, List<String> evidenceIds) {
        Set<String> known = knownCitationKeys(run);
        return (evidenceIds == null ? List.<String>of() : evidenceIds).stream()
                .filter(this::hasText)
                .filter(known::contains)
                .distinct()
                .toList();
    }

    private ClaimType parseClaimType(String value) {
        if (!hasText(value)) {
            return ClaimType.FACT;
        }
        try {
            return ClaimType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return ClaimType.FACT;
        }
    }

    private ConfidenceLevel parseConfidence(String value, List<String> evidenceIds) {
        if (!hasText(value)) {
            return evidenceIds == null || evidenceIds.isEmpty() ? ConfidenceLevel.LOW : ConfidenceLevel.MEDIUM;
        }
        try {
            return ConfidenceLevel.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return evidenceIds == null || evidenceIds.isEmpty() ? ConfidenceLevel.LOW : ConfidenceLevel.MEDIUM;
        }
    }

    private String extractJson(String raw) {
        return JsonResponseExtractor.extractJsonValue(raw);
    }

    private String requirementSummary(AnalysisRun run) {
        AnalysisRequirement requirement = run.getRequirement();
        if (requirement == null) {
            return "";
        }
        return """
                原始提示：%s
                行业：%s
                竞品：%s
                关注维度：%s
                输出目标：%s
                来源偏好：%s
                """.formatted(
                requirement.getOriginalPrompt(),
                requirement.getIndustry(),
                requirement.getCompetitors(),
                requirement.getDimensions(),
                requirement.getOutputGoal(),
                requirement.getSourcePreferences()
        );
    }

    private AnalystContext analystContext(AnalysisRun run) {
        return new AnalystContext(
                run,
                requirementSummary(run),
                compactProfileBlock(run),
                evidenceIndexBlock(run),
                dimensionEvidenceBlock(run),
                researchContextBlock(run),
                repairPlanBlock(run)
        );
    }

    private String claimsBlock(List<AnalysisClaim> claims) {
        if (claims == null || claims.isEmpty()) {
            return "暂无结构化 Claims。";
        }
        return claims.stream()
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

    private String renderMatrixFromClaims(AnalystContext context, List<AnalysisClaim> claims) {
        AnalysisRun run = context.run();
        List<String> competitors = matrixCompetitors(run, claims);
        String rows = competitors.stream()
                .map(competitor -> matrixRowForCompetitor(run, competitor, claims))
                .collect(Collectors.joining("\n"));
        String claimRows = claims.stream()
                .map(claim -> "| %s | %s | %s | %s | %s |".formatted(
                        claim.getType(),
                        claim.getConfidence(),
                        competitorText(claim.getCompetitorNames()),
                        escapeCell(claim.getContent()),
                        citationText(claim.getEvidenceIds())
                ))
                .collect(Collectors.joining("\n"));
        return """
                ## 基于结构化结论的竞品矩阵

                | 竞品 | 基于结论的判断 | 置信度 | 证据 |
                | --- | --- | --- | --- |
                %s

                ## 结构化结论明细

                | 类型 | 置信度 | 竞品 | 结论 | 证据 |
                | --- | --- | --- | --- | --- |
                %s

                说明：该矩阵仅由结构化结论渲染，不引入结论之外的新事实或新判断。
                """.formatted(
                rows.isBlank() ? "| - | 暂无结构化结论。 | LOW | 证据不足 |" : rows,
                claimRows.isBlank() ? "| - | LOW | - | 暂无结构化结论。 | 证据不足 |" : claimRows
        );
    }

    private String matrixRowForCompetitor(AnalysisRun run, String competitor, List<AnalysisClaim> claims) {
        Map<String, EvidenceSource> sourceByCitationKey = sourceByCitationKey(run);
        List<AnalysisClaim> relatedClaims = claims.stream()
                .filter(claim -> claimAppliesToCompetitor(claim, competitor))
                .sorted((left, right) -> Integer.compare(claimDisplayScore(right, sourceByCitationKey),
                        claimDisplayScore(left, sourceByCitationKey)))
                .limit(3)
                .toList();
        if (relatedClaims.isEmpty()) {
            return "| %s | 暂无可归属的结构化结论。 | LOW | 证据不足 |".formatted(escapeCell(competitor));
        }
        String summary = relatedClaims.stream()
                .map(claim -> "%s: %s".formatted(claim.getType(), claim.getContent()))
                .collect(Collectors.joining("<br>"));
        String confidence = relatedClaims.stream()
                .map(claim -> String.valueOf(claim.getConfidence()))
                .distinct()
                .collect(Collectors.joining("/"));
        List<String> evidenceIds = relatedClaims.stream()
                .flatMap(claim -> claim.getEvidenceIds().stream())
                .distinct()
                .toList();
        return "| %s | %s | %s | %s |".formatted(
                escapeCell(competitor),
                escapeCell(summary),
                confidence,
                citationText(evidenceIds)
        );
    }

    private String renderSwotFromClaims(List<AnalysisClaim> claims) {
        return """
                | 维度 | 基于结构化结论的判断 | 证据 |
                | --- | --- | --- |
                | 优势 | %s | %s |
                | 短板 | %s | %s |
                | 机会 | %s | %s |
                | 威胁 | %s | %s |

                说明：SWOT 仅由结构化结论渲染；证据不足的想法应留在证据缺口中，不作为新的 SWOT 结论。
                """.formatted(
                swotText(claims, ClaimType.STRENGTH, ClaimType.COMPARISON),
                citationText(evidenceIdsForClaimType(claims, ClaimType.STRENGTH, ClaimType.COMPARISON)),
                swotText(claims, ClaimType.WEAKNESS),
                citationText(evidenceIdsForClaimType(claims, ClaimType.WEAKNESS)),
                swotText(claims, ClaimType.OPPORTUNITY, ClaimType.RECOMMENDATION),
                citationText(evidenceIdsForClaimType(claims, ClaimType.OPPORTUNITY, ClaimType.RECOMMENDATION)),
                swotText(claims, ClaimType.RISK),
                citationText(evidenceIdsForClaimType(claims, ClaimType.RISK))
        );
    }

    private String swotText(List<AnalysisClaim> claims, ClaimType... types) {
        Set<ClaimType> accepted = Set.of(types);
        String text = claims.stream()
                .filter(claim -> accepted.contains(claim.getType()))
                .map(AnalysisClaim::getContent)
                .filter(this::hasText)
                .limit(2)
                .collect(Collectors.joining("<br>"));
        return text.isBlank() ? "暂无结构化结论。" : escapeCell(text);
    }

    private List<String> matrixCompetitors(AnalysisRun run, List<AnalysisClaim> claims) {
        LinkedHashSet<String> competitors = new LinkedHashSet<>();
        if (run.getRequirement() != null && run.getRequirement().getCompetitors() != null) {
            run.getRequirement().getCompetitors().stream().filter(this::hasText).forEach(competitors::add);
        }
        run.getCompetitorProfiles().stream()
                .map(profile -> textOrDash(profile.getProductName()))
                .filter(this::hasText)
                .forEach(competitors::add);
        claims.stream()
                .flatMap(claim -> safeList(claim.getCompetitorNames()).stream())
                .filter(this::hasText)
                .forEach(competitors::add);
        return competitors.isEmpty() ? List.of("-") : new ArrayList<>(competitors);
    }

    private boolean claimAppliesToCompetitor(AnalysisClaim claim, String competitor) {
        if (claim.getCompetitorNames() == null || claim.getCompetitorNames().isEmpty()) {
            return true;
        }
        return claim.getCompetitorNames().stream()
                .anyMatch(name -> name.equalsIgnoreCase(competitor));
    }

    private List<String> normalizeCompetitorNames(AnalysisRun run, List<String> candidateNames) {
        List<String> configuredCompetitors = run.getRequirement() == null
                ? List.of()
                : safeList(run.getRequirement().getCompetitors()).stream()
                .filter(this::hasText)
                .toList();
        List<String> candidates = safeList(candidateNames).stream()
                .filter(this::hasText)
                .map(String::trim)
                .toList();
        if (candidates.isEmpty()) {
            return configuredCompetitors;
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String candidate : candidates) {
            normalized.add(matchConfiguredCompetitor(candidate, configuredCompetitors));
        }
        return normalized.stream().filter(this::hasText).toList();
    }

    private String matchConfiguredCompetitor(String candidate, List<String> configuredCompetitors) {
        String candidateKey = competitorKey(candidate);
        for (String configured : configuredCompetitors) {
            String configuredKey = competitorKey(configured);
            if (candidateKey.equals(configuredKey)
                    || candidateKey.contains(configuredKey)
                    || configuredKey.contains(candidateKey)) {
                return configured;
            }
        }
        return candidate.trim();
    }

    private String competitorKey(String value) {
        return normalizeLower(value).replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}]+", "");
    }

    private int claimDisplayScore(AnalysisClaim claim, Map<String, EvidenceSource> sourceByCitationKey) {
        int bestEvidenceScore = safeList(claim.getEvidenceIds()).stream()
                .map(sourceByCitationKey::get)
                .mapToInt(this::evidenceConfidenceScore)
                .max()
                .orElse(0);
        return bestEvidenceScore * 100 + confidenceScore(claim.getConfidence()) * 10 + claimTypeDisplayScore(claim.getType());
    }

    private int confidenceScore(ConfidenceLevel confidence) {
        if (confidence == ConfidenceLevel.HIGH) {
            return 3;
        }
        if (confidence == ConfidenceLevel.MEDIUM) {
            return 2;
        }
        return 1;
    }

    private int claimTypeDisplayScore(ClaimType type) {
        if (type == null) {
            return 0;
        }
        return switch (type) {
            case RECOMMENDATION -> 7;
            case OPPORTUNITY -> 6;
            case COMPARISON -> 5;
            case STRENGTH -> 4;
            case WEAKNESS -> 3;
            case RISK -> 2;
            case FACT -> 1;
        };
    }

    private List<String> evidenceIdsForClaimType(List<AnalysisClaim> claims, ClaimType... types) {
        Set<ClaimType> accepted = Set.of(types);
        return claims.stream()
                .filter(claim -> accepted.contains(claim.getType()))
                .flatMap(claim -> claim.getEvidenceIds().stream())
                .distinct()
                .toList();
    }

    private String citationText(List<String> evidenceIds) {
        if (evidenceIds == null || evidenceIds.isEmpty()) {
            return "证据不足";
        }
        return evidenceIds.stream()
                .filter(this::hasText)
                .distinct()
                .map(id -> "[" + id + "]")
                .collect(Collectors.joining(" "));
    }

    private String competitorText(List<String> competitors) {
        if (competitors == null || competitors.isEmpty()) {
            return "-";
        }
        return competitors.stream().filter(this::hasText).collect(Collectors.joining(", "));
    }

    private String escapeCell(String value) {
        return textOrDash(value).replace("|", "\\|").replace("\n", "<br>");
    }

    private String textOrDash(String value) {
        return value == null || value.isBlank() ? "-" : value.trim();
    }

    private List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }

    private String evidenceIndexBlock(AnalysisRun run) {
        List<EvidenceSource> sources = selectedEvidenceSources(run);
        if (sources.isEmpty()) {
            return "暂无可引用证据。";
        }
        return sources.stream()
                .map(source -> "[%s] %s | type=%s | quality=%s | status=%s | tier=%s | %s".formatted(
                        source.getCitationKey(),
                        abbreviate(source.getTitle(), 80),
                        nullToEmpty(source.getSourceType()),
                        nullToEmpty(source.getSourceQuality()),
                        nullToEmpty(source.getCollectionStatus()),
                        evidenceTier(source),
                        abbreviate(source.getSnippet(), 180)
                ))
                .collect(Collectors.joining("\n"));
    }

    private String dimensionEvidenceBlock(AnalysisRun run) {
        if (run.getRequirement() == null || run.getRequirement().getDimensions().isEmpty()) {
            return "用户未指定分析维度。";
        }
        List<EvidenceSource> sources = selectedEvidenceSources(run);
        return run.getRequirement().getDimensions().stream()
                .filter(this::hasText)
                .map(dimension -> {
                    List<EvidenceSource> matched = sources.stream()
                            .filter(source -> dimensionMatchesSource(dimension, source))
                            .limit(4)
                            .toList();
                    if (matched.isEmpty()) {
                        return "- %s：暂无直接命中的公开证据，放入补证清单，不要作为主体结论。".formatted(dimension);
                    }
                    String evidence = matched.stream()
                            .map(source -> "[%s] %s (%s)".formatted(
                                    source.getCitationKey(),
                                    abbreviate(source.getTitle(), 60),
                                    evidenceTier(source)
                            ))
                            .collect(Collectors.joining("；"));
                    return "- %s：%s".formatted(dimension, evidence);
                })
                .collect(Collectors.joining("\n"));
    }

    private boolean dimensionMatchesSource(String dimension, EvidenceSource source) {
        List<String> keywords = keywordsForDimension(dimension);
        String text = "%s %s %s %s %s".formatted(
                nullToEmpty(source.getTitle()),
                nullToEmpty(source.getSourceType()),
                nullToEmpty(source.getUrl()),
                nullToEmpty(source.getSnippet()),
                nullToEmpty(source.getRawText())
        ).toLowerCase(Locale.ROOT);
        return keywords.stream().anyMatch(keyword -> text.contains(keyword.toLowerCase(Locale.ROOT)));
    }

    private List<String> keywordsForDimension(String dimension) {
        LinkedHashSet<String> keywords = new LinkedHashSet<>();
        keywords.add(dimension);
        String normalized = normalizeLower(dimension);
        if (containsAny(normalized, "价格", "定价", "pricing", "套餐", "商业模式")) {
            keywords.addAll(List.of("价格", "定价", "pricing", "plan", "套餐", "billing"));
        }
        if (containsAny(normalized, "用户", "评价", "口碑", "访谈", "痛点", "满意", "review")) {
            keywords.addAll(List.of("用户", "评价", "review", "feedback", "interview", "访谈", "pain", "满意"));
        }
        if (containsAny(normalized, "权限", "治理", "安全", "合规", "审计", "security")) {
            keywords.addAll(List.of("权限", "安全", "security", "permission", "governance", "合规", "审计", "sso", "scim", "rbac"));
        }
        if (containsAny(normalized, "ai", "智能", "搜索", "生成", "总结", "代码", "agent")) {
            keywords.addAll(List.of("ai", "智能", "搜索", "生成", "summary", "agent", "code", "代码"));
        }
        if (containsAny(normalized, "功能", "协作", "文档", "知识", "流程", "集成", "ide", "终端")) {
            keywords.addAll(List.of("功能", "协作", "文档", "知识", "workflow", "integration", "集成", "ide", "terminal", "cli"));
        }
        return new ArrayList<>(keywords);
    }

    private List<EvidenceSource> selectedEvidenceSources(AnalysisRun run) {
        List<EvidenceSource> ranked = run.getEvidenceSources().stream()
                .filter(source -> hasText(source.getCitationKey()))
                .sorted(Comparator
                        .comparingInt(this::evidencePromptScore)
                        .reversed()
                        .thenComparing(EvidenceSource::getCitationKey, Comparator.nullsLast(String::compareTo)))
                .toList();
        if (ranked.size() <= 10) {
            return ranked;
        }
        LinkedHashSet<EvidenceSource> selected = new LinkedHashSet<>();
        List<String> competitors = run.getRequirement() == null ? List.of() : run.getRequirement().getCompetitors();
        for (String competitor : competitors) {
            ranked.stream()
                    .filter(source -> mentionsCompetitor(source, competitor))
                    .findFirst()
                    .ifPresent(selected::add);
        }
        ranked.stream()
                .filter(source -> selected.size() < 10)
                .forEach(selected::add);
        return selected.stream().limit(10).toList();
    }

    private boolean mentionsCompetitor(EvidenceSource source, String competitor) {
        if (!hasText(competitor)) {
            return false;
        }
        String searchable = "%s %s %s".formatted(
                nullToEmpty(source.getTitle()),
                nullToEmpty(source.getUrl()),
                nullToEmpty(source.getSnippet())
        ).toLowerCase(Locale.ROOT);
        return searchable.contains(competitor.toLowerCase(Locale.ROOT));
    }

    private int evidencePromptScore(EvidenceSource source) {
        return evidenceConfidenceScore(source) * 100
                + sourceTypeScore(source) * 10
                + collectionStatusScore(source);
    }

    private int evidenceConfidenceScore(EvidenceSource source) {
        if (source == null) {
            return 0;
        }
        String quality = normalizeUpper(source.getSourceQuality());
        String status = normalizeUpper(source.getCollectionStatus());
        String freshness = normalizeUpper(source.getFreshness());
        if ("UNUSABLE".equals(quality) || "LOW".equals(quality)
                || "FETCH_FAILED".equals(status) || "BLOCKED_BY_ROBOTS".equals(status)
                || "SEARCH_RESULT_SNIPPET".equals(freshness)) {
            return 1;
        }
        if ("HIGH".equals(quality)) {
            return 3;
        }
        if (isAuthoritativeSourceType(source) && ("FETCHED".equals(status) || hasText(source.getRawText()))) {
            return 3;
        }
        if ("MEDIUM".equals(quality) || ("FETCHED".equals(status) && hasText(source.getSnippet()))) {
            return 2;
        }
        return hasText(source.getSnippet()) ? 2 : 1;
    }

    private String evidenceTier(EvidenceSource source) {
        int score = evidenceConfidenceScore(source);
        if (score >= 3) {
            return "strong";
        }
        if (score == 2) {
            return "medium";
        }
        return "weak";
    }

    private boolean isAuthoritativeSourceType(EvidenceSource source) {
        return Set.of("official_site", "docs", "product_docs", "pricing_page", "release_notes", "technical_blog", "authoritative_media")
                .contains(normalizeLower(source.getSourceType()));
    }

    private int sourceTypeScore(EvidenceSource source) {
        return switch (normalizeLower(source.getSourceType())) {
            case "docs", "product_docs" -> 6;
            case "pricing_page" -> 5;
            case "official_site" -> 4;
            case "release_notes", "technical_blog" -> 3;
            case "authoritative_media", "third_party_docs", "pricing_reference" -> 2;
            case "public_review", "public_reviews" -> 1;
            default -> 0;
        };
    }

    private int collectionStatusScore(EvidenceSource source) {
        return switch (normalizeUpper(source.getCollectionStatus())) {
            case "FETCHED" -> 3;
            case "UNKNOWN" -> 1;
            default -> 0;
        };
    }

    private String researchContextBlock(AnalysisRun run) {
        List<String> gaps = run.getResearchPackage().getMissingEvidenceTypes();
        String gapText = gaps == null || gaps.isEmpty()
                ? "暂无关键证据缺口"
                : String.join("、", gaps);
        String interviewText = run.getResearchPackage().getInterviewInsights().isEmpty()
                ? "暂无访谈或一手洞察"
                : run.getResearchPackage().getInterviewInsights().stream()
                .map(insight -> "- [%s] role=%s pain=%s concern=%s".formatted(
                        insight.getEvidenceId(),
                        insight.getIntervieweeRole(),
                        insight.getPainPoints(),
                        insight.getBuyingConcerns()
                ))
                .collect(Collectors.joining("\n"));
        return "证据缺口：" + gapText + "\n一手洞察：\n" + interviewText;
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
                .filter(task -> task.getTargetAgent() == AgentName.ANALYST)
                .map(task -> "- action=%s claim=%s citation=%s currentText=%s instruction=%s expectedFix=%s criteria=%s".formatted(
                        task.getAction(),
                        nullToEmpty(task.getClaimId()),
                        nullToEmpty(task.getCitationKey()),
                        nullToEmpty(task.getCurrentText()),
                        nullToEmpty(task.getInstruction()),
                        nullToEmpty(task.getExpectedFix()),
                        nullToEmpty(task.getAcceptanceCriteria())
                ))
                .collect(Collectors.joining("\n"));
        return """
                修复动作：%s
                修复范围：%s
                受影响 Claim：%s
                问题类别：%s
                修复指令：
                %s
                结构化修复任务：
                %s
                """.formatted(
                run.getReviewDecision().getAction(),
                nullToEmpty(run.getReviewDecision().getRepairScopeSummary()),
                run.getReviewDecision().getAffectedClaimIds(),
                run.getReviewDecision().getFindingCategories(),
                instructions,
                tasks
        );
    }

    private String compactProfileBlock(AnalysisRun run) {
        return run.getCompetitorProfiles().stream()
                .map(profile -> "- 产品=%s | 定位=%s | 优势=%s | 弱势=%s | 证据=%s".formatted(
                        profile.getProductName(),
                        abbreviate(profile.getPositioning(), 80),
                        abbreviate(String.join("、", profile.getStrengths()), 80),
                        abbreviate(String.join("、", profile.getWeaknesses()), 80),
                        profile.getEvidenceIds()
                ))
                .collect(Collectors.joining("\n"));
    }

    private boolean hasText(String text) {
        return text != null && !text.isBlank();
    }

    private boolean containsUncertaintyMarker(String text) {
        String normalized = nullToEmpty(text);
        return normalized.contains("待验证")
                || normalized.contains("证据不足")
                || normalized.toLowerCase(Locale.ROOT).contains("insufficient evidence");
    }

    private String nullToEmpty(String text) {
        return text == null ? "" : text;
    }

    private String normalizeUpper(String text) {
        return nullToEmpty(text).trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeLower(String text) {
        return nullToEmpty(text).trim().toLowerCase(Locale.ROOT);
    }

    private boolean containsAny(String text, String... patterns) {
        for (String pattern : patterns) {
            if (text != null && pattern != null && text.contains(pattern.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String abbreviate(String value, int maxLength) {
        String normalized = nullToEmpty(value).replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength) + "...";
    }

    private static class ClaimDraft {
        public String type;
        public String content;
        public String confidence;
        public List<String> competitorNames = List.of();
        public List<String> evidenceIds = List.of();
    }

    private record AnalystContext(
            AnalysisRun run,
            String requirementSummary,
            String profileBlock,
            String evidenceIndex,
            String dimensionEvidence,
            String researchContext,
            String repairPlan
    ) {
    }

    private interface LlmSubtask<T> {
        T run() throws Exception;
    }

    private record LlmSubtaskResult<T>(String name, T value, String errorMessage) {
        boolean succeeded() {
            if (value instanceof List<?> list) {
                return !list.isEmpty() && errorMessage == null;
            }
            if (value instanceof String text) {
                return hasStaticText(text) && errorMessage == null;
            }
            return value != null && errorMessage == null;
        }

        private static boolean hasStaticText(String text) {
            return text != null && !text.isBlank();
        }
    }
}
