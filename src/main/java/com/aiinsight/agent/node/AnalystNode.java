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
import com.aiinsight.model.review.ReviewDecision;
import com.aiinsight.model.review.ReviewRepairTask;
import com.aiinsight.model.run.AnalysisArtifact;
import com.aiinsight.model.run.AnalysisRequirement;
import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.model.run.EvidenceChunk;
import com.aiinsight.model.run.EvidenceSource;
import com.aiinsight.model.schema.AnalysisClaim;
import com.aiinsight.model.schema.CompetitorFactSet;
import com.aiinsight.model.schema.CompetitorProfile;
import com.aiinsight.model.schema.ExtractedFact;
import com.aiinsight.model.schema.UnknownFact;
import com.aiinsight.observability.AgentTraceContext;
import com.aiinsight.service.AnalysisDraft;
import com.aiinsight.service.fallback.FallbackAnalysisDraftFactory;
import com.aiinsight.util.AgentUtils;
import com.aiinsight.util.JsonResponseExtractor;
import com.aiinsight.util.LlmSubtaskSupport;
import com.aiinsight.util.LlmSubtaskSupport.LlmSubtaskResult;
import com.aiinsight.util.TermExtractor;
import com.aiinsight.util.TermExtractor.TermOptions;
import static com.aiinsight.agent.node.AnalysisClaimRules.PLACEMENT_MATRIX;
import static com.aiinsight.agent.node.AnalysisClaimRules.PLACEMENT_NONE;
import static com.aiinsight.agent.node.AnalysisClaimRules.PLACEMENT_SWOT;
import static com.aiinsight.agent.node.AnalysisClaimRules.PLACEMENT_VALIDATION_BACKLOG;
import static com.aiinsight.agent.node.AnalysisClaimRules.SUPPORT_STATUS_PARTIAL;
import static com.aiinsight.agent.node.AnalysisClaimRules.SUPPORT_STATUS_SUPPORTED;
import static com.aiinsight.agent.node.AnalysisClaimRules.SUPPORT_STATUS_UNVERIFIED;
import static com.aiinsight.agent.node.AnalysisClaimRules.containsUncertaintyMarker;
import static com.aiinsight.agent.node.AnalysisClaimRules.defaultPlacementFor;
import static com.aiinsight.agent.node.AnalysisClaimRules.displayableClaim;
import static com.aiinsight.agent.node.AnalysisClaimRules.normalizeRecommendedPlacement;
import static com.aiinsight.agent.node.AnalysisClaimRules.normalizeSupportStatus;
import static com.aiinsight.util.AgentUtils.CITATION_PATTERN;
import static com.aiinsight.util.AgentUtils.abbreviate;
import static com.aiinsight.util.AgentUtils.containsAny;
import static com.aiinsight.util.AgentUtils.containsIgnoreCase;
import static com.aiinsight.util.AgentUtils.distinctKnownEvidenceIds;
import static com.aiinsight.util.AgentUtils.hasText;
import static com.aiinsight.util.AgentUtils.knownCitationKeys;
import static com.aiinsight.util.AgentUtils.normalizeLower;
import static com.aiinsight.util.AgentUtils.normalizeUpper;
import static com.aiinsight.util.AgentUtils.nullToEmpty;
import static com.aiinsight.util.AgentUtils.safeList;
import static com.aiinsight.util.AgentUtils.sanitizeCitationText;
import static com.aiinsight.util.AgentUtils.textOrDash;
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
// Analyst 是竞品分析层：把 Extractor 沉淀的竞品画像和证据索引转化为可复核 Claims，
// 再基于这些 Claims 生成矩阵和 SWOT，避免 Writer 在报告阶段重新承担分析判断。
public class AnalystNode implements AgentNode {

    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;
    private final FallbackAnalysisDraftFactory fallbackAnalysisDraftFactory;
    private final ClaimEvidenceBinder claimEvidenceBinder = new ClaimEvidenceBinder();
    private final AnalysisProductRenderer analysisProductRenderer = new AnalysisProductRenderer();
    private static final TermOptions CLAIM_DEDUP_TERM_OPTIONS = TermOptions.basic(2);

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
        List<AnalysisClaim> previousClaims = new ArrayList<>(run.getClaims());

        run.getClaims().clear();
        // prompt 约束最多 8 条，代码层兜底截断，防止 LLM 超量输出
        List<AnalysisClaim> boundedClaims = draft.claims().size() <= MAX_CLAIMS
                ? draft.claims()
                : draft.claims().subList(0, MAX_CLAIMS);
        boundedClaims = stabilizeClaimIds(previousClaims, boundedClaims);
        boundedClaims = applyAnalystRepairGuard(run, boundedClaims);
        AnalysisDraft finalDraft = renderDraftFromClaims(run, boundedClaims);
        List<String> citationKeys = artifactCitationKeys(run, finalDraft);
        run.getClaims().addAll(boundedClaims);
        run.addArtifact(new AnalysisArtifact(
                ArtifactType.COMPETITIVE_MATRIX,
                "竞品横向矩阵",
                finalDraft.matrixMarkdown(),
                citationKeys
        ));
        run.addArtifact(new AnalysisArtifact(
                ArtifactType.SWOT_ANALYSIS,
                "SWOT 分析",
                finalDraft.swotMarkdown(),
                citationKeys
        ));
        return run;
    }

    private AnalysisDraft analysisDraftWithLlm(AnalysisRun run) {
        AnalysisDraft fallback = fallbackAnalysisDraftFactory.build(run);
        AnalystContext context = analystContext(run);
        LlmSubtaskResult<List<AnalysisClaim>> claimsResult = LlmSubtaskSupport.runSubtask(
                "Analyst",
                "claims",
                () -> generateClaimsWithLlm(context)
        );
        List<AnalysisClaim> effectiveClaims = claimsResult.succeeded() && !claimsResult.value().isEmpty()
                ? claimsResult.value()
                : fallback.claims();
        List<LlmSubtaskResult<?>> results = List.of(claimsResult);
        LlmSubtaskSupport.recordSubtaskTrace("Analyst LLM subtasks", results);
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
                analysisProductRenderer.renderMatrix(context.run(), effectiveClaims),
                analysisProductRenderer.renderSwot(effectiveClaims)
        );
    }

    private List<AnalysisClaim> generateClaimsWithLlm(AnalystContext context) {
        String prompt = """
                你是本工作流中的竞品分析 Agent。请只生成结构化 claims，不要生成矩阵或 SWOT。
                你的职责是把 Extractor 生成的事实画像转化为可复核的分析断言。
                矩阵和 SWOT 会由系统基于你生成的 claims 统一渲染。

                输出约束：
                1. 只输出 JSON，不要 Markdown 代码块。
                2. claims 必须是数组，最多 8 条；每条 claim 包含 type、content、confidence、dimension、supportStatus、recommendedPlacement、supportReason、evidenceQuotes、missingEvidenceTypes、rewriteSuggestion、competitorNames、factIds、evidenceIds、chunkKeys。
                3. type 只能取 FACT、COMPARISON、STRENGTH、WEAKNESS、OPPORTUNITY、RISK、RECOMMENDATION。
                4. confidence 只能取 LOW、MEDIUM、HIGH。
                5. content 不超过 120 字，必须围绕用户关注维度、业务目标或已采集证据生成。
                6. evidenceIds 只能使用已知证据编号；证据不足时可以为空，但 content 必须明确写“待验证”或“证据不足”。
                7. supportStatus 只能取 SUPPORTED、PARTIAL、UNVERIFIED；recommendedPlacement 只能取 MATRIX、SWOT、VALIDATION_BACKLOG、NONE。
                8. evidenceQuotes 是你从已知证据或 chunk 中摘出的短支撑片段；SUPPORTED 必须至少给 1 条 evidenceQuote，PARTIAL 给能部分支撑的片段，UNVERIFIED 留空。
                9. supportReason 用一句话说明 evidenceQuotes 如何支撑 content；如果只是部分支撑，必须写清楚边界。
                10. missingEvidenceTypes 写仍缺的证据类型；rewriteSuggestion 写更保守、更可被证据支撑的改写方案；没有缺口时用空数组/空字符串。
                11. MATRIX/SWOT 只给有 evidenceIds、evidenceQuotes 且 confidence 为 MEDIUM/HIGH 的结论；LOW、UNVERIFIED 或无 evidenceIds 的结论必须放 VALIDATION_BACKLOG 或 NONE。
                12. 不要输出矩阵、SWOT、报告正文或其他展示型字段。
                13. 不要编造价格、营收、客户案例、市场份额或证据中没有的信息。
                14. 不要把“证据不足”本身当成主要洞察；RISK 类型最多 1 条，其余优先产出有证据支撑的差异、取舍和建议。
                15. 对已有 strong/medium 证据覆盖的维度，不要写“待验证”；应给出保守但可行动的判断。
                16. 如果 Reviewer 修复计划包含结构化修复任务，必须逐条处理 task；不要原样保留 task.currentText 中被点名的问题结论。
                17. 无法找到更强证据时，不要继续维护原 claim 的强判断；必须降为 LOW，并在 content 写明“证据不足/待验证”。
                18. 如果 task 指出 citation mismatch，不要继续把同一个 citationKey 绑定到相同判断；改用更相关证据，或清空 evidenceIds 并降级。
                19. 返工输出必须相对原 affected claim 有可见变化：改证据、改置信度、改措辞或删除/替换该 claim。

                JSON 结构：
                {
                  "claims": [
                    {
                      "type": "OPPORTUNITY",
                      "content": "结论正文",
                      "confidence": "MEDIUM",
                      "dimension": "用户关注维度",
                      "supportStatus": "SUPPORTED",
                      "recommendedPlacement": "MATRIX",
                      "supportReason": "证据片段直接说明该能力或事实，因此可以作为保守结论。",
                      "evidenceQuotes": ["来自证据或 chunk 的短支撑片段"],
                      "missingEvidenceTypes": [],
                      "rewriteSuggestion": "",
                      "competitorNames": ["竞品名"],
                      "factIds": ["F1"],
                      "evidenceIds": ["S1"],
                      "chunkKeys": []
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
        ).tagged(name().name(), "claims"));
        AnalysisDraft parsed = parseAnalysisDraft(raw, context.run());
        if (parsed == null || parsed.claims().isEmpty()) {
            throw new IllegalStateException("无法解析 claims JSON");
        }
        return parsed.claims();
    }

    private AnalysisDraft parseAnalysisDraft(String raw, AnalysisRun run) {
        if (!hasText(raw)) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(JsonResponseExtractor.extractJsonValue(raw));
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
        claim.setDimension(normalizeClaimDimension(run, draft.dimension, draft.content));
        claim.setSupportStatus(normalizeSupportStatus(draft.supportStatus));
        claim.setRecommendedPlacement(normalizeRecommendedPlacement(draft.recommendedPlacement, claim.getType()));
        claim.setSupportReason(sanitizeShortText(draft.supportReason, 220));
        claim.setRewriteSuggestion(sanitizeShortText(draft.rewriteSuggestion, 220));
        claim.setCompetitorNames(normalizeCompetitorNames(run, draft.competitorNames));
        // evidenceIds 是 claim 进入 Writer/Reviewer 的硬约束，只允许已知 citation；
        // 模型编造的 [S404] 会被过滤，避免后续报告携带不可追溯引用。
        claim.setEvidenceIds(distinctKnownEvidenceIds(run, draft.evidenceIds));
        claim.setFactIds(distinctKnownFactIds(run, draft.factIds));
        claim.setChunkKeys(distinctKnownChunkKeys(run, draft.chunkKeys));
        claim.setEvidenceQuotes(sanitizeShortList(draft.evidenceQuotes, 4, 180));
        claim.setMissingEvidenceTypes(sanitizeShortList(draft.missingEvidenceTypes, 4, 80));
        claimEvidenceBinder.bindClaimFacts(run, claim);
        claimEvidenceBinder.pruneUnsupportedClaimEvidence(run, claim);
        adjustClaimConfidence(run, claim);
        if (claim.getEvidenceIds().isEmpty() && !containsUncertaintyMarker(claim.getContent())) {
            claim.setContent(claim.getContent() + "（证据不足，待验证）");
        }
        refreshClaimAssessment(run, claim);
        return claim;
    }

    private AnalysisDraft sanitizeDraft(AnalysisRun run, AnalysisDraft draft) {
        List<AnalysisClaim> claims = draft.claims().stream()
                .map(claim -> sanitizeClaim(run, claim))
                .toList();
        return new AnalysisDraft(
                claims,
                sanitizeCitationText(run, analysisProductRenderer.renderMatrix(run, claims)),
                sanitizeCitationText(run, analysisProductRenderer.renderSwot(claims))
        );
    }

    private AnalysisDraft renderDraftFromClaims(AnalysisRun run, List<AnalysisClaim> claims) {
        return new AnalysisDraft(
                claims,
                sanitizeCitationText(run, analysisProductRenderer.renderMatrix(run, claims)),
                sanitizeCitationText(run, analysisProductRenderer.renderSwot(claims))
        );
    }

    private AnalysisClaim sanitizeClaim(AnalysisRun run, AnalysisClaim claim) {
        claim.setEvidenceIds(distinctKnownEvidenceIds(run, claim.getEvidenceIds()));
        claim.setFactIds(distinctKnownFactIds(run, claim.getFactIds()));
        claim.setChunkKeys(distinctKnownChunkKeys(run, claim.getChunkKeys()));
        claim.setDimension(normalizeClaimDimension(run, claim.getDimension(), claim.getContent()));
        claim.setSupportStatus(normalizeSupportStatus(claim.getSupportStatus()));
        claim.setRecommendedPlacement(normalizeRecommendedPlacement(claim.getRecommendedPlacement(), claim.getType()));
        claim.setSupportReason(sanitizeShortText(claim.getSupportReason(), 220));
        claim.setRewriteSuggestion(sanitizeShortText(claim.getRewriteSuggestion(), 220));
        claim.setEvidenceQuotes(sanitizeShortList(claim.getEvidenceQuotes(), 4, 180));
        claim.setMissingEvidenceTypes(sanitizeShortList(claim.getMissingEvidenceTypes(), 4, 80));
        claimEvidenceBinder.bindClaimFacts(run, claim);
        claimEvidenceBinder.pruneUnsupportedClaimEvidence(run, claim);
        if (claim.getEvidenceIds().isEmpty()) {
            claim.setConfidence(ConfidenceLevel.LOW);
            if (!containsUncertaintyMarker(claim.getContent())) {
                claim.setContent(claim.getContent() + "（证据不足，待验证）");
            }
            refreshClaimAssessment(run, claim);
            return claim;
        }
        adjustClaimConfidence(run, claim);
        refreshClaimAssessment(run, claim);
        return claim;
    }

    private List<AnalysisClaim> stabilizeClaimIds(List<AnalysisClaim> previousClaims, List<AnalysisClaim> newClaims) {
        List<AnalysisClaim> stabilized = new ArrayList<>();
        LinkedHashSet<String> usedPreviousIds = new LinkedHashSet<>();
        for (AnalysisClaim claim : newClaims) {
            matchPreviousClaim(previousClaims, claim, usedPreviousIds).ifPresent(previous -> {
                claim.setId(previous.getId());
                usedPreviousIds.add(previous.getId());
            });
            stabilized.add(claim);
        }
        return stabilized;
    }

    private java.util.Optional<AnalysisClaim> matchPreviousClaim(List<AnalysisClaim> previousClaims,
                                                                 AnalysisClaim claim,
                                                                 Set<String> usedPreviousIds) {
        String currentKey = claimContentKey(claim.getContent());
        return previousClaims.stream()
                .filter(previous -> !usedPreviousIds.contains(previous.getId()))
                .filter(previous -> currentKey.equals(claimContentKey(previous.getContent()))
                        || likelySameClaim(previous, claim))
                .findFirst();
    }

    private boolean likelySameClaim(AnalysisClaim previous, AnalysisClaim claim) {
        if (previous.getType() != claim.getType()) {
            return false;
        }
        boolean sameCompetitor = previous.getCompetitorNames().isEmpty()
                || claim.getCompetitorNames().isEmpty()
                || previous.getCompetitorNames().stream().anyMatch(claim.getCompetitorNames()::contains);
        boolean sharesEvidence = !previous.getEvidenceIds().isEmpty()
                && previous.getEvidenceIds().stream().anyMatch(claim.getEvidenceIds()::contains);
        return sameCompetitor && sharesEvidence && claimContentOverlap(previous.getContent(), claim.getContent()) >= 0.65;
    }

    private List<AnalysisClaim> applyAnalystRepairGuard(AnalysisRun run, List<AnalysisClaim> claims) {
        ReviewDecision decision = run.getRepairDecisionFor(AgentName.ANALYST);
        if (decision == null
                || decision.getAction() != ReviewAction.REWORK_ANALYSIS
                || decision.getTargetAgent() != AgentName.ANALYST
                || decision.getRepairTasks().isEmpty()) {
            return claims;
        }
        List<ReviewRepairTask> tasks = decision.getRepairTasks().stream()
                .filter(task -> task.getTargetAgent() == AgentName.ANALYST)
                .toList();
        if (tasks.isEmpty()) {
            return claims;
        }
        List<AnalysisClaim> guarded = new ArrayList<>();
        for (AnalysisClaim claim : claims) {
            tasks.stream()
                    .filter(task -> matchesRepairTask(claim, task))
                    .filter(task -> repairStillUnresolved(claim, task))
                    .findFirst()
                    .ifPresent(task -> downgradeUnresolvedRepairClaim(claim, task));
            guarded.add(claim);
        }
        return guarded;
    }

    private boolean matchesRepairTask(AnalysisClaim claim, ReviewRepairTask task) {
        if (hasText(task.getClaimId()) && task.getClaimId().equals(claim.getId())) {
            return true;
        }
        if (hasText(task.getCitationKey()) && claim.getEvidenceIds().contains(task.getCitationKey())) {
            return true;
        }
        if (hasText(task.getCurrentText()) && claimContentOverlap(claim.getContent(), task.getCurrentText()) >= 0.55) {
            return true;
        }
        return hasText(task.getExcerpt()) && claimContentOverlap(claim.getContent(), task.getExcerpt()) >= 0.55;
    }

    private boolean repairStillUnresolved(AnalysisClaim claim, ReviewRepairTask task) {
        String category = normalizeLower(task.getCategory());
        if (!isRiskyRepairCategory(category)) {
            return false;
        }
        boolean stillUsesProblemCitation = hasText(task.getCitationKey())
                && claim.getEvidenceIds().contains(task.getCitationKey());
        boolean lacksEvidence = claim.getEvidenceIds().isEmpty();
        boolean stillSameText = hasText(task.getCurrentText())
                && claimContentOverlap(claim.getContent(), task.getCurrentText()) >= 0.88;
        boolean stillSameExcerpt = hasText(task.getExcerpt())
                && claimContentOverlap(claim.getContent(), task.getExcerpt()) >= 0.88;
        boolean alreadyDowngraded = claim.getConfidence() == ConfidenceLevel.LOW
                || containsUncertaintyMarker(claim.getContent());
        if (alreadyDowngraded && !stillUsesProblemCitation) {
            return false;
        }
        if (category.contains("missing") || category.contains("unsupported") || category.contains("internal_evidence")) {
            return lacksEvidence || stillUsesProblemCitation;
        }
        if (category.contains("mismatch") || category.contains("citation")) {
            return stillUsesProblemCitation || (lacksEvidence && (stillSameText || stillSameExcerpt));
        }
        if (category.contains("overclaim")) {
            return !alreadyDowngraded && (stillSameText || stillSameExcerpt);
        }
        return lacksEvidence || stillUsesProblemCitation;
    }

    private void downgradeUnresolvedRepairClaim(AnalysisClaim claim, ReviewRepairTask task) {
        String category = normalizeLower(task.getCategory());
        if (!isRiskyRepairCategory(category)) {
            return;
        }
        claim.setConfidence(ConfidenceLevel.LOW);
        if (hasText(task.getCitationKey()) && claim.getEvidenceIds().contains(task.getCitationKey())) {
            claim.setEvidenceIds(claim.getEvidenceIds().stream()
                    .filter(evidenceId -> !task.getCitationKey().equals(evidenceId))
                    .toList());
        }
        if (!containsUncertaintyMarker(claim.getContent())) {
            claim.setContent(claim.getContent() + "（证据不足，待验证）");
        }
        refreshClaimAssessment(null, claim);
    }

    private boolean isRiskyRepairCategory(String category) {
        return category.contains("missing")
                || category.contains("mismatch")
                || category.contains("unsupported")
                || category.contains("overclaim")
                || category.contains("citation")
                || category.contains("internal_evidence");
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
        Map<String, EvidenceSource> sources = AgentEvidenceSupport.sourceByCitationKey(run);
        int strongestEvidence = claim.getEvidenceIds().stream()
                .map(sources::get)
                .mapToInt(AgentEvidenceSupport::evidenceConfidenceScore)
                .max()
                .orElse(0);
        if (claim.getConfidence() == ConfidenceLevel.HIGH && strongestEvidence < 3) {
            claim.setConfidence(ConfidenceLevel.MEDIUM);
        }
        if (claim.getConfidence() == ConfidenceLevel.MEDIUM && strongestEvidence < 2) {
            claim.setConfidence(ConfidenceLevel.LOW);
        }
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
    private List<String> distinctKnownFactIds(AnalysisRun run, List<String> factIds) {
        Set<String> known = run.getCompetitorFactSets().stream()
                .flatMap(factSet -> factSet.getFacts().stream())
                .map(ExtractedFact::getId)
                .filter(AgentUtils::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return safeList(factIds).stream()
                .filter(AgentUtils::hasText)
                .filter(known::contains)
                .distinct()
                .toList();
    }

    private List<String> distinctKnownChunkKeys(AnalysisRun run, List<String> chunkKeys) {
        Set<String> known = run.getEvidenceChunks().stream()
                .map(com.aiinsight.model.run.EvidenceChunk::getChunkKey)
                .filter(AgentUtils::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return safeList(chunkKeys).stream()
                .filter(AgentUtils::hasText)
                .filter(known::contains)
                .distinct()
                .toList();
    }

    private static final int MAX_CLAIMS = 8;

    private String claimContentKey(String text) {
        if (!hasText(text)) {
            return "";
        }
        return text.toLowerCase(Locale.ROOT)
                .replaceAll("（证据不足，待验证）", "")
                .replaceAll("\\(insufficient evidence; needs verification\\)", "")
                .replaceAll("[^\\p{IsHan}a-z0-9]+", "");
    }

    private Set<String> termsForBinding(String text) {
        return TermExtractor.extract(text, CLAIM_DEDUP_TERM_OPTIONS);
    }

    private double claimContentOverlap(String left, String right) {
        Set<String> leftTerms = termsForBinding(left);
        Set<String> rightTerms = termsForBinding(right);
        if (leftTerms.isEmpty() || rightTerms.isEmpty()) {
            return 0.0;
        }
        long overlap = leftTerms.stream().filter(rightTerms::contains).count();
        return overlap / (double) Math.min(leftTerms.size(), rightTerms.size());
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

    private String normalizeClaimDimension(AnalysisRun run, String value, String claimContent) {
        if (hasText(value)) {
            return value.trim();
        }
        List<String> dimensions = run.getRequirement() == null
                ? List.of()
                : safeList(run.getRequirement().getDimensions()).stream()
                .filter(AgentUtils::hasText)
                .toList();
        String text = normalizeLower(claimContent);
        return dimensions.stream()
                .filter(dimension -> containsIgnoreCase(text, dimension)
                        || dimensionKeywords(dimension).stream().anyMatch(keyword -> containsIgnoreCase(text, keyword)))
                .findFirst()
                .orElse("综合判断");
    }

    private String sanitizeShortText(String text, int maxLength) {
        if (!hasText(text)) {
            return null;
        }
        return abbreviate(text.trim(), maxLength);
    }

    private List<String> sanitizeShortList(List<String> values, int maxItems, int maxLength) {
        return safeList(values).stream()
                .filter(AgentUtils::hasText)
                .map(String::trim)
                .map(value -> abbreviate(value, maxLength))
                .distinct()
                .limit(maxItems)
                .toList();
    }

    private void refreshClaimAssessment(AnalysisRun run, AnalysisClaim claim) {
        boolean lacksEvidence = claim.getEvidenceIds() == null || claim.getEvidenceIds().isEmpty();
        boolean uncertain = containsUncertaintyMarker(claim.getContent());
        if (lacksEvidence || uncertain || claim.getConfidence() == ConfidenceLevel.LOW) {
            claim.setSupportStatus(SUPPORT_STATUS_UNVERIFIED);
            if (!PLACEMENT_NONE.equals(claim.getRecommendedPlacement())) {
                claim.setRecommendedPlacement(PLACEMENT_VALIDATION_BACKLOG);
            }
            applyClaimEligibility(claim, "证据不足、待验证或低置信度，不能进入主展示。");
            return;
        }
        if (SUPPORT_STATUS_UNVERIFIED.equals(claim.getSupportStatus())) {
            claim.setConfidence(ConfidenceLevel.LOW);
            if (!PLACEMENT_NONE.equals(claim.getRecommendedPlacement())) {
                claim.setRecommendedPlacement(PLACEMENT_VALIDATION_BACKLOG);
            }
            applyClaimEligibility(claim, "Analyst 已标记为待验证，不能进入主展示。");
            return;
        }
        boolean analystSelfVerified = analystSelfVerified(claim);
        if (!analystSelfVerified && run != null && highRiskClaimNeedsStrongerEvidence(run, claim)) {
            claim.setSupportStatus(SUPPORT_STATUS_PARTIAL);
            if (claim.getConfidence() == ConfidenceLevel.HIGH) {
                claim.setConfidence(ConfidenceLevel.MEDIUM);
            }
            if (!PLACEMENT_NONE.equals(claim.getRecommendedPlacement())) {
                claim.setRecommendedPlacement(PLACEMENT_VALIDATION_BACKLOG);
            }
            applyClaimEligibility(claim, "价格、安全、权限或部署类结论缺少足够强的一手来源，先放入待验证。");
            return;
        }
        if (run != null && analystSelfVerified && !hasStrongFirstPartyEvidence(run, claim)) {
            claim.setSupportStatus(SUPPORT_STATUS_PARTIAL);
            if (claim.getConfidence() == ConfidenceLevel.HIGH) {
                claim.setConfidence(ConfidenceLevel.MEDIUM);
            }
            if (!hasText(claim.getSupportReason())) {
                claim.setSupportReason("证据相关但缺少足够强的一手来源，不能作为高置信优势判断。");
            }
        }
        if (analystSelfVerified
                && SUPPORT_STATUS_SUPPORTED.equals(claim.getSupportStatus())
                && safeList(claim.getEvidenceQuotes()).isEmpty()) {
            claim.setSupportStatus(SUPPORT_STATUS_PARTIAL);
            if (claim.getConfidence() == ConfidenceLevel.HIGH) {
                claim.setConfidence(ConfidenceLevel.MEDIUM);
            }
            if (!hasText(claim.getSupportReason())) {
                claim.setSupportReason("Analyst 未提供直接支撑摘录，只能按部分支撑处理。");
            }
        } else if (!SUPPORT_STATUS_SUPPORTED.equals(claim.getSupportStatus())
                && !SUPPORT_STATUS_PARTIAL.equals(claim.getSupportStatus())) {
            claim.setSupportStatus(claim.getConfidence() == ConfidenceLevel.HIGH
                    ? SUPPORT_STATUS_SUPPORTED
                    : SUPPORT_STATUS_PARTIAL);
        }
        if (!PLACEMENT_MATRIX.equals(claim.getRecommendedPlacement())
                && !PLACEMENT_SWOT.equals(claim.getRecommendedPlacement())
                && !PLACEMENT_NONE.equals(claim.getRecommendedPlacement())) {
            claim.setRecommendedPlacement(defaultPlacementFor(claim.getType()));
        }
        applyClaimEligibility(claim, analystSelfVerified
                ? "Analyst 已给出证据摘录与支撑理由，结构校验通过。"
                : "证据与置信度满足主展示条件。");
    }

    private boolean analystSelfVerified(AnalysisClaim claim) {
        return hasText(claim.getSupportReason())
                || hasText(claim.getRewriteSuggestion())
                || !safeList(claim.getEvidenceQuotes()).isEmpty()
                || !safeList(claim.getMissingEvidenceTypes()).isEmpty();
    }

    private boolean hasStrongFirstPartyEvidence(AnalysisRun run, AnalysisClaim claim) {
        Map<String, EvidenceSource> sources = AgentEvidenceSupport.sourceByCitationKey(run);
        return safeList(claim.getEvidenceIds()).stream()
                .map(sources::get)
                .anyMatch(source -> AgentEvidenceSupport.evidenceConfidenceScore(source) >= 3 && firstPartyAuthority(source));
    }

    private boolean firstPartyAuthority(EvidenceSource source) {
        if (source == null) {
            return false;
        }
        String authority = normalizeUpper(source.getSourceAuthority());
        if (authority.startsWith("FIRST_PARTY")) {
            return true;
        }
        return AgentEvidenceSupport.isAuthoritativeSourceType(source) && !AgentEvidenceSupport.thirdPartyLikeSource(source);
    }

    private void applyClaimEligibility(AnalysisClaim claim, String reason) {
        boolean displayable = displayableClaim(claim);
        String placement = normalizeRecommendedPlacement(claim.getRecommendedPlacement(), claim.getType());
        claim.setEligibleForMatrix(displayable && PLACEMENT_MATRIX.equals(placement));
        claim.setEligibleForSwot(displayable && PLACEMENT_SWOT.equals(placement));
        claim.setEligibleForMainReport(displayable
                && (Boolean.TRUE.equals(claim.getEligibleForMatrix()) || Boolean.TRUE.equals(claim.getEligibleForSwot())));
        claim.setPlacementReason(reason);
    }

    private boolean highRiskClaimNeedsStrongerEvidence(AnalysisRun run, AnalysisClaim claim) {
        if (!highRiskClaim(claim)) {
            return false;
        }
        Map<String, EvidenceSource> sources = AgentEvidenceSupport.sourceByCitationKey(run);
        return safeList(claim.getEvidenceIds()).stream()
                .noneMatch(citationKey -> {
                    EvidenceSource source = sources.get(citationKey);
                    return source != null
                            && AgentEvidenceSupport.evidenceConfidenceScore(source) >= 3
                            && highRiskSourceMatchesClaim(source, claim)
                            && directEvidenceSupportsHighRiskClaim(run, citationKey, claim);
                });
    }

    private boolean highRiskClaim(AnalysisClaim claim) {
        String text = normalizeLower("%s %s".formatted(nullToEmpty(claim.getDimension()), nullToEmpty(claim.getContent())));
        return containsAny(text,
                "pricing", "price", "plan", "subscription", "billing", "$",
                "security", "permission", "compliance", "privacy", "deployment", "deploy", "bedrock", "proxy", "vpc",
                "sso", "scim", "saml", "soc",
                "价格", "定价", "套餐", "订阅", "付费", "安全", "权限", "合规", "隐私", "部署", "代理", "审计");
    }

    private boolean highRiskSourceMatchesClaim(EvidenceSource source, AnalysisClaim claim) {
        String sourceType = normalizeLower(source.getSourceType());
        String claimText = normalizeLower("%s %s".formatted(nullToEmpty(claim.getDimension()), nullToEmpty(claim.getContent())));
        if (containsAny(claimText, "pricing", "price", "plan", "subscription", "$", "价格", "定价", "套餐", "订阅", "付费")) {
            return containsAny(sourceType, "pricing", "official", "docs", "product")
                    && AgentEvidenceSupport.sourceTextHasRiskSignal(claimEvidenceBinder.evidenceSourceText(source), "pricing");
        }
        if (containsAny(claimText, "security", "permission", "compliance", "privacy", "sso", "scim", "saml", "soc",
                "安全", "权限", "合规", "隐私", "审计")) {
            return containsAny(sourceType, "security", "docs", "product", "official", "trust", "privacy")
                    && AgentEvidenceSupport.sourceTextHasRiskSignal(claimEvidenceBinder.evidenceSourceText(source), "security");
        }
        if (containsAny(claimText, "deployment", "deploy", "bedrock", "proxy", "vpc", "部署", "代理")) {
            return containsAny(sourceType, "docs", "product", "official", "security", "integration")
                    && AgentEvidenceSupport.sourceTextHasRiskSignal(claimEvidenceBinder.evidenceSourceText(source), "deployment");
        }
        return true;
    }

    private boolean directEvidenceSupportsHighRiskClaim(AnalysisRun run, String citationKey, AnalysisClaim claim) {
        boolean sourceSupports = claimEvidenceBinder.evidenceSupportsClaim(run, citationKey, claim);
        boolean chunkSupports = run.getEvidenceChunks().stream()
                .filter(chunk -> citationKey.equals(chunk.getSourceCitationKey()))
                .filter(chunk -> chunkRiskCompatibleWithClaim(chunk, claim))
                .anyMatch(chunk -> claimEvidenceBinder.supportTextMatches(claim.getContent(), claimEvidenceBinder.evidenceChunkText(chunk)));
        return sourceSupports && chunkSupports;
    }

    private boolean chunkRiskCompatibleWithClaim(EvidenceChunk chunk, AnalysisClaim claim) {
        String claimText = normalizeLower("%s %s".formatted(nullToEmpty(claim.getDimension()), nullToEmpty(claim.getContent())));
        String chunkText = claimEvidenceBinder.evidenceChunkText(chunk);
        if (containsAny(claimText, "pricing", "price", "plan", "subscription", "$", "价格", "定价", "套餐", "订阅", "付费")) {
            return AgentEvidenceSupport.sourceTextHasRiskSignal(chunkText, "pricing");
        }
        if (containsAny(claimText, "security", "permission", "compliance", "privacy", "sso", "scim", "saml", "soc",
                "安全", "权限", "合规", "隐私", "审计")) {
            return AgentEvidenceSupport.sourceTextHasRiskSignal(chunkText, "security");
        }
        if (containsAny(claimText, "deployment", "deploy", "bedrock", "proxy", "vpc", "部署", "代理")) {
            return AgentEvidenceSupport.sourceTextHasRiskSignal(chunkText, "deployment");
        }
        return true;
    }

    private List<String> dimensionKeywords(String dimension) {
        LinkedHashSet<String> keywords = new LinkedHashSet<>();
        if (hasText(dimension)) {
            keywords.add(dimension);
        }
        String normalized = normalizeLower(dimension);
        if (containsAny(normalized, "价格", "定价", "pricing", "套餐", "商业模式")) {
            keywords.addAll(List.of("价格", "定价", "pricing", "price", "plan", "套餐", "billing"));
        }
        if (containsAny(normalized, "上下文", "context")) {
            keywords.addAll(List.of("上下文", "context", "代码库", "仓库", "repository", "repo"));
        }
        if (containsAny(normalized, "团队", "协作", "collaboration", "team")) {
            keywords.addAll(List.of("团队", "协作", "team", "collaboration", "共享", "技能"));
        }
        if (containsAny(normalized, "权限", "治理", "安全", "合规", "审计", "security")) {
            keywords.addAll(List.of("权限", "安全", "security", "permission", "governance", "合规", "审计", "sso", "scim"));
        }
        if (containsAny(normalized, "ide", "终端", "集成", "integration")) {
            keywords.addAll(List.of("ide", "终端", "terminal", "集成", "integration", "vscode", "jetbrains"));
        }
        if (containsAny(normalized, "工作流", "workflow", "agent")) {
            keywords.addAll(List.of("agent", "workflow", "工作流", "规划模式", "自动化任务", "skills"));
        }
        if (containsAny(normalized, "代码", "生成", "理解")) {
            keywords.addAll(List.of("代码", "生成", "理解", "code", "generation", "understanding", "构建", "调试"));
        }
        return new ArrayList<>(keywords);
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
                compactProfileBlock(run) + "\n\nExtractor facts:\n" + factBlock(run),
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
                .map(claim -> "- id=%s type=%s confidence=%s status=%s placement=%s dimension=%s competitors=%s evidence=%s content=%s".formatted(
                        claim.getId(),
                        claim.getType(),
                        claim.getConfidence(),
                        textOrDash(claim.getSupportStatus()),
                        textOrDash(claim.getRecommendedPlacement()),
                        textOrDash(claim.getDimension()),
                        claim.getCompetitorNames(),
                        claim.getEvidenceIds(),
                        claim.getContent()
                ))
                .collect(Collectors.joining("\n"));
    }

    private List<String> normalizeCompetitorNames(AnalysisRun run, List<String> candidateNames) {
        List<String> configuredCompetitors = run.getRequirement() == null
                ? List.of()
                : safeList(run.getRequirement().getCompetitors()).stream()
                .filter(AgentUtils::hasText)
                .toList();
        List<String> candidates = safeList(candidateNames).stream()
                .filter(AgentUtils::hasText)
                .map(String::trim)
                .toList();
        if (candidates.isEmpty()) {
            return configuredCompetitors;
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String candidate : candidates) {
            normalized.add(matchConfiguredCompetitor(candidate, configuredCompetitors));
        }
        return normalized.stream().filter(AgentUtils::hasText).toList();
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
                        AgentEvidenceSupport.evidenceTier(source),
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
                .filter(AgentUtils::hasText)
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
                                    AgentEvidenceSupport.evidenceTier(source)
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
        return AgentEvidenceSupport.evidenceConfidenceScore(source) * 100
                + sourceTypeScore(source) * 10
                + collectionStatusScore(source);
    }

    private int sourceTypeScore(EvidenceSource source) {
        return switch (normalizeLower(source.getSourceType())) {
            case "docs", "product_docs" -> 6;
            case "pricing_page" -> 5;
            case "official_site" -> 4;
            case "release_notes", "technical_blog" -> 3;
            case "authoritative_media", "third_party_docs", "third_party_pricing_reference", "pricing_reference" -> 2;
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
        ReviewDecision decision = run.getRepairDecisionFor(AgentName.ANALYST);
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
                decision.getAction(),
                nullToEmpty(decision.getRepairScopeSummary()),
                decision.getAffectedClaimIds(),
                decision.getFindingCategories(),
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

    private String factBlock(AnalysisRun run) {
        if (run.getCompetitorFactSets().isEmpty()) {
            return "No extracted fact layer is available; use competitor profiles and evidence directly.";
        }
        return run.getCompetitorFactSets().stream()
                .map(factSet -> """
                        Competitor: %s
                        Facts:
                        %s
                        Unknowns:
                        %s
                        """.formatted(
                        factSet.getCompetitorName(),
                        factsForPrompt(factSet.getFacts()),
                        unknownsForPrompt(factSet.getUnknowns())
                ))
                .collect(Collectors.joining("\n"));
    }

    private String factsForPrompt(List<ExtractedFact> facts) {
        if (facts == null || facts.isEmpty()) {
            return "- none";
        }
        return facts.stream()
                .limit(20)
                .map(fact -> "- id=%s type=%s attr=%s evidence=%s chunks=%s confidence=%s value=%s".formatted(
                        fact.getId(),
                        fact.getFactType(),
                        nullToEmpty(fact.getAttribute()),
                        fact.getEvidenceIds(),
                        fact.getChunkKeys(),
                        nullToEmpty(fact.getExtractionConfidence()),
                        abbreviate(fact.getValue(), 180)
                ))
                .collect(Collectors.joining("\n"));
    }

    private String unknownsForPrompt(List<UnknownFact> unknowns) {
        if (unknowns == null || unknowns.isEmpty()) {
            return "- none";
        }
        return unknowns.stream()
                .limit(12)
                .map(unknown -> "- field=%s reason=%s needed=%s".formatted(
                        nullToEmpty(unknown.getField()),
                        nullToEmpty(unknown.getReason()),
                        unknown.getNeededEvidenceTypes()
                ))
                .collect(Collectors.joining("\n"));
    }
    private boolean containsUncertaintyMarker(String text) {
        String normalized = nullToEmpty(text);
        return normalized.contains("待验证")
                || normalized.contains("证据不足")
                || normalized.toLowerCase(Locale.ROOT).contains("insufficient evidence");
    }
    private static class ClaimDraft {
        public String type;
        public String content;
        public String confidence;
        public String dimension;
        public String supportStatus;
        public String recommendedPlacement;
        public String supportReason;
        public String rewriteSuggestion;
        public List<String> competitorNames = List.of();
        public List<String> factIds = List.of();
        public List<String> evidenceIds = List.of();
        public List<String> chunkKeys = List.of();
        public List<String> evidenceQuotes = List.of();
        public List<String> missingEvidenceTypes = List.of();
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
}
