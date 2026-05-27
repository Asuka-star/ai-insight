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
import com.aiinsight.model.run.AnalysisArtifact;
import com.aiinsight.model.run.AnalysisRequirement;
import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.model.run.EvidenceSource;
import com.aiinsight.model.schema.AnalysisClaim;
import com.aiinsight.model.schema.CompetitorProfile;
import com.aiinsight.observability.AgentTraceContext;
import com.aiinsight.service.AnalysisDraft;
import com.aiinsight.service.fallback.FallbackAnalysisDraftFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
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
        CompletableFuture<LlmSubtaskResult<List<AnalysisClaim>>> claimsTask = CompletableFuture.supplyAsync(
                AgentTraceContext.wrap(() -> runAnalystSubtask(run, "claims", () -> generateClaimsWithLlm(run)))
        );
        CompletableFuture<LlmSubtaskResult<String>> matrixTask = CompletableFuture.supplyAsync(
                AgentTraceContext.wrap(() -> runAnalystSubtask(run, "competitive-matrix", () -> generateMatrixWithLlm(run)))
        );
        CompletableFuture<LlmSubtaskResult<String>> swotTask = CompletableFuture.supplyAsync(
                AgentTraceContext.wrap(() -> runAnalystSubtask(run, "swot", () -> generateSwotWithLlm(run)))
        );
        CompletableFuture.allOf(claimsTask, matrixTask, swotTask).join();

        LlmSubtaskResult<List<AnalysisClaim>> claimsResult = claimsTask.join();
        LlmSubtaskResult<String> matrixResult = matrixTask.join();
        LlmSubtaskResult<String> swotResult = swotTask.join();
        List<LlmSubtaskResult<?>> results = List.of(claimsResult, matrixResult, swotResult);
        recordParallelAnalystTrace(results);
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
                claimsResult.succeeded() && !claimsResult.value().isEmpty() ? claimsResult.value() : fallback.claims(),
                matrixResult.succeeded() && hasText(matrixResult.value()) ? matrixResult.value() : fallback.matrixMarkdown(),
                swotResult.succeeded() && hasText(swotResult.value()) ? swotResult.value() : fallback.swotMarkdown()
        );
    }

    private List<AnalysisClaim> generateClaimsWithLlm(AnalysisRun run) {
        String prompt = """
                你是竞品分析工作流中的分析 Agent。请只生成结构化 claims，不要生成矩阵或 SWOT。
                矩阵和 SWOT 会由另外两个并行 LLM 子任务生成；你只负责提炼可追溯结论。

                输出约束：
                1. 只输出 JSON，不要 Markdown 代码块。
                2. claims 必须是数组，最多 6 条；每条 claim 包含 type、content、confidence、competitorNames、evidenceIds。
                3. type 只能取 FACT、COMPARISON、STRENGTH、WEAKNESS、OPPORTUNITY、RISK、RECOMMENDATION。
                4. confidence 只能取 LOW、MEDIUM、HIGH。
                5. content 不超过 120 字，必须围绕用户关注维度、业务目标或已采集证据生成。
                6. evidenceIds 只能使用已知证据编号；证据不足时可以为空，但 content 必须明确写“待验证”或“证据不足”。
                7. 不要输出 competitiveMatrixMarkdown 或 swotMarkdown。
                8. 不要编造价格、营收、客户案例、市场份额或证据中没有的信息。

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

                证据片段：
                %s
                """.formatted(
                requirementSummary(run),
                compactProfileBlock(run),
                compactEvidenceBlock(run)
        );
        String raw = llmClient.complete(new ChatRequest(
                List.of(
                        ChatMessage.system("你是严谨的竞品分析 Agent。你必须输出可解析 JSON，并让每条结论都能追溯到证据或明确标注待验证。"),
                        ChatMessage.user(prompt)
                ),
                ChatOptions.analyst()
        ));
        AnalysisDraft parsed = parseAnalysisDraft(raw, run);
        if (parsed == null || parsed.claims().isEmpty()) {
            throw new IllegalStateException("无法解析 claims JSON");
        }
        return parsed.claims();
    }

    private String generateMatrixWithLlm(AnalysisRun run) {
        String prompt = """
                你是竞品分析工作流中的矩阵分析 Agent。请单独生成竞品横向矩阵 Markdown。
                输出必须是 JSON 对象，不要 Markdown 代码块，不要解释。

                JSON 结构：
                {"matrixMarkdown":"## 竞品横向矩阵\\n\\n| 竞品 | ... | 证据 |\\n| --- | --- | --- |\\n..."}

                约束：
                1. 必须是 Markdown 表格，覆盖所有竞品。
                2. 列必须贴合用户关注维度，至少包含竞品、核心定位、关键能力、短板/风险、证据。
                3. 证据列只能使用已知 citation，如 [S1]；证据不足写“证据不足，待验证”。
                4. 不要编造价格、客户、市场份额或证据外事实。

                分析需求：
                %s

                结构化竞品画像：
                %s

                证据片段：
                %s
                """.formatted(
                requirementSummary(run),
                compactProfileBlock(run),
                compactEvidenceBlock(run)
        );
        String raw = llmClient.complete(new ChatRequest(
                List.of(
                        ChatMessage.system("你是严谨的竞品矩阵分析 Agent。只输出可解析 JSON。"),
                        ChatMessage.user(prompt)
                ),
                ChatOptions.analyst()
        ));
        return parseMarkdownField(raw, "matrixMarkdown");
    }

    private String generateSwotWithLlm(AnalysisRun run) {
        String prompt = """
                你是竞品分析工作流中的 SWOT 分析 Agent。请单独生成 SWOT Markdown。
                输出必须是 JSON 对象，不要 Markdown 代码块，不要解释。

                JSON 结构：
                {"swotMarkdown":"| 维度 | 结论 | 证据 |\\n| --- | --- | --- |\\n..."}

                约束：
                1. 必须包含 Strengths、Weaknesses、Opportunities、Threats 四行。
                2. 每行结论不超过 120 字，并绑定证据 citation；证据不足写“证据不足，待验证”。
                3. 机会和威胁必须面向用户输出目标，不要泛泛而谈。
                4. 不要编造价格、客户、市场份额或证据外事实。

                分析需求：
                %s

                结构化竞品画像：
                %s

                证据片段：
                %s
                """.formatted(
                requirementSummary(run),
                compactProfileBlock(run),
                compactEvidenceBlock(run)
        );
        String raw = llmClient.complete(new ChatRequest(
                List.of(
                        ChatMessage.system("你是严谨的 SWOT 分析 Agent。只输出可解析 JSON。"),
                        ChatMessage.user(prompt)
                ),
                ChatOptions.analyst()
        ));
        return parseMarkdownField(raw, "swotMarkdown");
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

    private void recordParallelAnalystTrace(List<LlmSubtaskResult<?>> results) {
        String summary = results.stream()
                .map(result -> "%s=%s%s".formatted(
                        result.name(),
                        result.succeeded() ? "succeeded" : "failed",
                        result.succeeded() ? "" : " (" + result.errorMessage() + ")"
                ))
                .collect(Collectors.joining("\n"));
        AgentTraceContext.recordOutputSummary("Parallel Analyst LLM subtasks:\n" + summary);
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
            String matrix = root.has("competitiveMatrixMarkdown") ? root.get("competitiveMatrixMarkdown").asText() : null;
            String swot = root.has("swotMarkdown") ? root.get("swotMarkdown").asText() : null;
            return new AnalysisDraft(claims, matrix, swot);
        } catch (IllegalArgumentException | JsonProcessingException ex) {
            return null;
        }
    }

    private String parseMarkdownField(String raw, String fieldName) {
        if (!hasText(raw)) {
            throw new IllegalStateException("模型输出为空");
        }
        try {
            JsonNode root = objectMapper.readTree(extractJson(raw));
            String markdown = root.has(fieldName) ? root.get(fieldName).asText() : "";
            if (!hasText(markdown)) {
                throw new IllegalStateException("模型输出缺少字段：" + fieldName);
            }
            return markdown;
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("无法解析 " + fieldName + " JSON", ex);
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
        claim.setCompetitorNames(draft.competitorNames == null || draft.competitorNames.isEmpty()
                ? run.getRequirement().getCompetitors()
                : draft.competitorNames);
        // evidenceIds 是 claim 进入 Writer/Reviewer 的硬约束，只允许已知 citation；
        // 模型编造的 [S404] 会被过滤，避免后续报告携带不可追溯引用。
        claim.setEvidenceIds(distinctKnownEvidenceIds(run, draft.evidenceIds));
        if (claim.getEvidenceIds().isEmpty() && claim.getConfidence() != ConfidenceLevel.LOW) {
            claim.setConfidence(ConfidenceLevel.LOW);
        }
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
        }
        return claim;
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
        String trimmed = raw.trim();
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
        }
        int objectStart = trimmed.indexOf('{');
        int arrayStart = trimmed.indexOf('[');
        int start;
        if (objectStart < 0) {
            start = arrayStart;
        } else if (arrayStart < 0) {
            start = objectStart;
        } else {
            start = Math.min(objectStart, arrayStart);
        }
        if (start > 0) {
            trimmed = trimmed.substring(start);
        }
        return trimmed;
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

    private String profileBlock(AnalysisRun run) {
        return run.getCompetitorProfiles().stream()
                .map(profile -> """
                        - 产品：%s
                          定位：%s
                          目标用户：%s
                          优势：%s
                          弱势：%s
                          定价：%s
                          证据：%s
                        """.formatted(
                        profile.getProductName(),
                        profile.getPositioning(),
                        profile.getTargetUsers(),
                        profile.getStrengths(),
                        profile.getWeaknesses(),
                        pricingSummary(profile),
                        profile.getEvidenceIds()
                ))
                .collect(Collectors.joining("\n"));
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

    private String evidenceBlock(AnalysisRun run) {
        String sources = run.getEvidenceSources().stream()
                .map(source -> "[%s] %s | type=%s | status=%s | snippet=%s".formatted(
                        source.getCitationKey(),
                        source.getTitle(),
                        source.getSourceType(),
                        source.getCollectionStatus(),
                        source.getSnippet()
                ))
                .collect(Collectors.joining("\n"));
        String chunks = relevantChunks(run, requirementSummary(run), 8).stream()
                .map(chunk -> "- [%s/%s] %s".formatted(chunk.getSourceCitationKey(), chunk.getChunkKey(), chunk.getText()))
                .collect(Collectors.joining("\n"));
        if (!hasText(chunks)) {
            return sources;
        }
        return sources + "\n\n相关证据切片:\n" + chunks;
    }

    private String compactEvidenceBlock(AnalysisRun run) {
        String sources = run.getEvidenceSources().stream()
                .limit(8)
                .map(source -> "[%s] %s | type=%s | status=%s | %s".formatted(
                        source.getCitationKey(),
                        abbreviate(source.getTitle(), 80),
                        source.getSourceType(),
                        source.getCollectionStatus(),
                        abbreviate(source.getSnippet(), 180)
                ))
                .collect(Collectors.joining("\n"));
        String chunks = relevantChunks(run, requirementSummary(run), 4).stream()
                .map(chunk -> "- [%s/%s] %s".formatted(
                        chunk.getSourceCitationKey(),
                        chunk.getChunkKey(),
                        abbreviate(chunk.getText(), 180)
                ))
                .collect(Collectors.joining("\n"));
        if (!hasText(chunks)) {
            return sources;
        }
        return sources + "\n相关证据切片:\n" + chunks;
    }

    private String sourceText(EvidenceSource source) {
        return ("%s %s %s %s %s").formatted(
                nullToEmpty(source.getTitle()),
                nullToEmpty(source.getSourceType()),
                nullToEmpty(source.getUrl()),
                nullToEmpty(source.getSnippet()),
                nullToEmpty(source.getRawText())
        );
    }

    private String pricingSummary(CompetitorProfile profile) {
        if (profile.getPricingModel() == null || !hasText(profile.getPricingModel().getStrategySummary())) {
            return "定价证据不足";
        }
        return profile.getPricingModel().getStrategySummary();
    }

    private String citationText(List<String> evidenceIds) {
        if (evidenceIds == null || evidenceIds.isEmpty()) {
            return "证据不足";
        }
        return evidenceIds.stream().map(id -> "[" + id + "]").collect(Collectors.joining(" "));
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
        String normalized = nullToEmpty(text).toLowerCase(Locale.ROOT);
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
        String normalized = nullToEmpty(text).toLowerCase(Locale.ROOT)
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

    private record ChunkScore(com.aiinsight.model.run.EvidenceChunk chunk, double score) {
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
