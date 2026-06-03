package com.aiinsight.agent.node;

import com.aiinsight.model.enums.ReviewSeverity;
import com.aiinsight.model.enums.AgentName;
import com.aiinsight.model.run.AnalysisArtifact;
import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.model.enums.ArtifactType;
import com.aiinsight.model.enums.ReviewAction;
import com.aiinsight.model.run.AnalysisRequirement;
import com.aiinsight.model.review.ReviewDecision;
import com.aiinsight.model.review.ReviewFinding;
import com.aiinsight.model.review.ReviewRepairTask;
import com.aiinsight.llm.ChatMessage;
import com.aiinsight.llm.ChatOptions;
import com.aiinsight.llm.ChatRequest;
import com.aiinsight.llm.LlmClient;
import com.aiinsight.service.CitationCoverageEvaluator;
import com.aiinsight.service.ResearchCoverageService;
import com.aiinsight.service.fallback.FallbackReviewReportFactory;
import com.aiinsight.agent.AgentNode;
import com.aiinsight.observability.AgentTraceContext;
import com.aiinsight.util.AgentUtils;
import com.aiinsight.util.JsonResponseExtractor;
import com.aiinsight.util.LlmSubtaskSupport;
import com.aiinsight.util.LlmSubtaskSupport.LlmSubtaskResult;
import static com.aiinsight.util.AgentUtils.CITATION_PATTERN;
import static com.aiinsight.util.AgentUtils.abbreviate;
import static com.aiinsight.util.AgentUtils.containsIgnoreCase;
import static com.aiinsight.util.AgentUtils.countBySeverity;
import static com.aiinsight.util.AgentUtils.hasText;
import static com.aiinsight.util.AgentUtils.latestArtifact;
import static com.aiinsight.util.AgentUtils.normalizeLower;
import static com.aiinsight.util.AgentUtils.nullToEmpty;
import static com.aiinsight.util.AgentUtils.textOrDash;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
// Reviewer 是可信度防线：先跑确定性规则，再让 LLM 做更语义化的质检。
// ReviewDecision 会驱动工作流打回采集或修订节点，形成可观测反馈闭环。
public class ReviewerNode implements AgentNode {

    private static final Pattern CITATION_KEY_PATTERN = Pattern.compile("\\bS\\d+\\b");
    private static final int MAX_FINDING_CATEGORY_LENGTH = 128;
    private static final int MAX_LLM_FINDINGS_PER_SUBTASK = 4;
    private static final int MAX_LLM_FINDING_MESSAGE_LENGTH = 180;
    private static final int MAX_LLM_FINDING_RECOMMENDATION_LENGTH = 180;
    private static final int MAX_LLM_FINDING_EXCERPT_LENGTH = 240;
    private static final int MAX_REPAIR_TASKS = 12;
    private static final Set<String> MANUAL_ONLY_EVIDENCE_TYPES = Set.of(
            "survey_result",
            "interview_note",
            "user_survey",
            "user_interview",
            "first_party_survey",
            "first_party_interview"
    );

    private final CitationCoverageEvaluator citationCoverageEvaluator;
    private final LlmClient llmClient;
    private final FallbackReviewReportFactory fallbackReviewReportFactory;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private ResearchCoverageService researchCoverageService = new ResearchCoverageService();

    @Autowired(required = false)
    public void setResearchCoverageService(ResearchCoverageService researchCoverageService) {
        if (researchCoverageService != null) {
            this.researchCoverageService = researchCoverageService;
        }
    }

    @Override
    public AgentName name() {
        return AgentName.REVIEWER;
    }

    @Override
    public String title() {
        return "复核事实一致性与引用覆盖";
    }

    @Override
    public AnalysisRun execute(AnalysisRun run) {
        AnalysisArtifact draft = latestArtifact(run.getArtifacts(), ArtifactType.REPORT_DRAFT).orElse(null);
        ReviewDecision previousDecision = run.getReviewDecision();
        run.getReviewFindings().clear();
        if (draft != null) {
            // 规则结果进入结构化 finding，不能只存在于 LLM 文本回复里。
            run.getReviewFindings().addAll(citationCoverageEvaluator.evaluate(draft.getContent(), run));
            enrichFindingLocations(run, draft);
        }
        String semanticReviewContent = "";
        boolean deterministicFallback = false;
        if (llmClient.isAvailable() && draft != null) {
            try {
                semanticReviewContent = reviewWithLlm(run, draft);
                enrichFindingLocations(run, draft);
            } catch (RuntimeException ex) {
                log.warn("Reviewer fallback activated: runId={}, reason=llm_exception, exceptionType={}, message={}, draftId={}, evidenceSources={}, claims={}, ruleFindings={}",
                        run.getId(),
                        ex.getClass().getName(),
                        ex.getMessage(),
                        draft.getId(),
                        run.getEvidenceSources().size(),
                        run.getClaims().size(),
                        run.getReviewFindings().size());
                run.getRecommendedActions().add("LLM 质检失败，已使用确定性 Reviewer 结果：" + ex.getMessage());
                deterministicFallback = true;
            }
        } else {
            log.warn("Reviewer fallback activated: runId={}, reason={}, draftPresent={}, evidenceSources={}, claims={}, ruleFindings={}",
                    run.getId(),
                    llmClient.isAvailable() ? "missing_report_draft" : "llm_unavailable",
                    draft != null,
                    run.getEvidenceSources().size(),
                    run.getClaims().size(),
                    run.getReviewFindings().size());
            deterministicFallback = true;
        }
        applyRepairVerificationScope(run, previousDecision);
        run.setReviewDecision(buildDecision(run));
        researchCoverageService.enrichRepairTasks(run);
        researchCoverageService.refreshRepairTargets(run);
        String content = StringUtils.hasText(semanticReviewContent)
                ? semanticReviewContent + "\n\n" + fallbackReviewReportFactory.build(run)
                : fallbackReviewReportFactory.build(run);
        if (deterministicFallback) {
            AgentTraceContext.recordFallback("deterministic-reviewer-fallback", content);
        }
        run.addArtifact(new AnalysisArtifact(ArtifactType.REVIEW_FINDINGS, "Reviewer 复核结果", content, List.of()));
        return run;
    }

    private void applyRepairVerificationScope(AnalysisRun run, ReviewDecision previousDecision) {
        if (!isRepairVerificationMode(previousDecision)) {
            return;
        }
        int downgradedNewHighFindings = 0;
        for (ReviewFinding finding : run.getReviewFindings()) {
            if (finding.getSeverity() == ReviewSeverity.HIGH
                    && isLlmSemanticFinding(finding)
                    && !matchesPreviousRepairTask(finding, previousDecision.getRepairTasks())) {
                finding.setSeverity(ReviewSeverity.MEDIUM);
                finding.setMessage("返工验证模式中发现的新问题（不阻断本轮修复验收）：" + finding.getMessage());
                downgradedNewHighFindings++;
            }
        }
        if (downgradedNewHighFindings > 0) {
            run.getRecommendedActions().add(
                    "返工验证模式已将 " + downgradedNewHighFindings
                            + " 个非上一轮 blocker 的新 HIGH 问题降为质量提醒；本轮优先验证上一轮修复任务是否完成。");
        }
    }

    private boolean isLlmSemanticFinding(ReviewFinding finding) {
        String category = normalizeLower(finding.getCategory());
        return category.startsWith("llm_")
                || category.contains("semantic")
                || category.contains("overclaim")
                || category.startsWith("report_")
                || category.contains("actionability")
                || category.contains("schema_consistency")
                || category.contains("matrix_claim_conflict")
                || category.contains("swot_claim_conflict");
    }

    private boolean isRepairVerificationMode(ReviewDecision previousDecision) {
        return previousDecision != null
                && previousDecision.getAction() != ReviewAction.PASS
                && previousDecision.getRepairTasks() != null
                && !previousDecision.getRepairTasks().isEmpty();
    }

    private boolean matchesPreviousRepairTask(ReviewFinding finding, List<ReviewRepairTask> repairTasks) {
        return repairTasks.stream().anyMatch(task -> matchesRepairTask(finding, task));
    }

    private boolean matchesRepairTask(ReviewFinding finding, ReviewRepairTask task) {
        if (task == null || finding == null) {
            return false;
        }
        boolean hasLocator = false;
        boolean matchedLocator = false;
        if (StringUtils.hasText(task.getClaimId())) {
            hasLocator = true;
            matchedLocator = task.getClaimId().equals(finding.getClaimId());
        }
        if (StringUtils.hasText(task.getFactId())) {
            hasLocator = true;
            matchedLocator = matchedLocator || task.getFactId().equals(finding.getFactId());
        }
        if (StringUtils.hasText(task.getChunkKey())) {
            hasLocator = true;
            matchedLocator = matchedLocator || task.getChunkKey().equals(finding.getChunkKey());
        }
        if (StringUtils.hasText(task.getCitationKey())) {
            hasLocator = true;
            matchedLocator = matchedLocator || task.getCitationKey().equals(finding.getCitationKey());
        }
        if (task.getParagraphIndex() != null) {
            hasLocator = true;
            matchedLocator = matchedLocator || task.getParagraphIndex().equals(finding.getParagraphIndex());
        }
        if (StringUtils.hasText(task.getExcerpt())) {
            hasLocator = true;
            matchedLocator = matchedLocator
                    || containsIgnoreCase(finding.getExcerpt(), task.getExcerpt())
                    || containsIgnoreCase(finding.getMessage(), task.getExcerpt());
        }
        boolean matchedCategory = StringUtils.hasText(task.getCategory())
                && StringUtils.hasText(finding.getCategory())
                && normalizeLower(finding.getCategory()).equals(task.getCategory().trim().toLowerCase(Locale.ROOT));
        if (hasLocator) {
            return matchedLocator;
        }
        return matchedCategory;
    }

    private ReviewDecision buildDecision(AnalysisRun run) {
        ReviewDecision decision = new ReviewDecision();
        decision.setFindingCategories(distinctCategories(run.getReviewFindings()));
        // 只有定位明确、类别确实需要返工的 HIGH finding 会阻断自动流程；
        // 非阻断 HIGH 和 MEDIUM/LOW 都作为质量提醒保留给前端和最终报告。
        List<ReviewFinding> blockingFindings = run.getReviewFindings().stream()
                .filter(this::isBlockingFinding)
                .toList();
        if (blockingFindings.isEmpty()) {
            decision.setAction(ReviewAction.PASS);
            decision.setReason(run.getReviewFindings().isEmpty()
                    ? "规则检查未发现高风险问题。"
                    : "仅发现 %d 个高优先级提醒、%d 个质量提醒和 %d 个人工复核项，不阻断当前报告流程。".formatted(
                            countBySeverity(run.getReviewFindings(), ReviewSeverity.HIGH),
                            countBySeverity(run.getReviewFindings(), ReviewSeverity.MEDIUM),
                            countBySeverity(run.getReviewFindings(), ReviewSeverity.LOW)
                    ));
            decision.setRepairScopeSummary("无需自动修复；非阻断问题保留为人工复核提醒。");
            return decision;
        }
        applyBlockingDecision(run, decision, blockingFindings);
        List<ReviewFinding> targetFindings = targetFindings(decision, blockingFindings);
        decision.setBlockingFindingIds(blockingFindings.stream()
                .map(finding -> finding.getId().toString())
                .toList());
        decision.setFindingCategories(distinctCategories(targetFindings));
        decision.setRepairInstructions(repairInstructions(decision, targetFindings));
        decision.setRepairTasks(repairTasks(run, decision, targetFindings));
        // 决策元数据沿用前端“定位问题”使用的 claim 绑定；没有可绑定 finding 时再退回无证据 claim。
        List<String> affectedClaimIds = targetFindings.stream()
                .map(finding -> finding.getClaimId())
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        if (affectedClaimIds.isEmpty()) {
            affectedClaimIds = run.getClaims().stream()
                    .filter(claim -> claim.getEvidenceIds().isEmpty())
                    .map(claim -> claim.getId())
                    .toList();
        }
        decision.setAffectedClaimIds(affectedClaimIds);
        decision.setRepairScopeSummary(repairScopeSummary(decision, targetFindings));
        return decision;
    }

    private List<ReviewFinding> targetFindings(ReviewDecision decision, List<ReviewFinding> blockingFindings) {
        List<ReviewFinding> matched = blockingFindings.stream()
                .filter(finding -> isTargetFinding(decision, finding))
                .toList();
        return matched.isEmpty() ? blockingFindings : matched;
    }

    private boolean isTargetFinding(ReviewDecision decision, ReviewFinding finding) {
        if (decision.getAction() == ReviewAction.RECOLLECT_EVIDENCE) {
            return needsMoreEvidence(finding);
        }
        if (decision.getAction() == ReviewAction.REWORK_ANALYSIS && decision.getTargetAgent() == AgentName.EXTRACTOR) {
            return needsExtractionRework(finding);
        }
        if (decision.getAction() == ReviewAction.REWORK_ANALYSIS && decision.getTargetAgent() == AgentName.ANALYST) {
            return needsAnalysisRework(finding);
        }
        if (decision.getAction() == ReviewAction.REVISE_REPORT) {
            return !needsMoreEvidence(finding) && !needsExtractionRework(finding) && !needsAnalysisRework(finding);
        }
        return true;
    }

    private boolean isBlockingFinding(ReviewFinding finding) {
        if (finding == null || finding.getSeverity() != ReviewSeverity.HIGH) {
            return false;
        }
        String category = normalizeLower(finding.getCategory());
        return !isQualityReminderOnly(category);
    }

    private boolean isQualityReminderOnly(String category) {
        return category.contains("marketing_only")
                || category.contains("thin_source")
                || category.contains("low_quality_source")
                || category.contains("snippet_only");
    }

    private List<String> distinctCategories(List<ReviewFinding> findings) {
        return findings.stream()
                .map(ReviewFinding::getCategory)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
    }

    private List<String> repairInstructions(ReviewDecision decision, List<ReviewFinding> blockingFindings) {
        List<String> instructions = new java.util.ArrayList<>();
        if (isExtractorFactRepair(decision)) {
            instructions.add("Extractor should repair only affected extracted facts and fact-to-evidence bindings.");
            instructions.add("Unsupported values must be corrected, rebound to supporting chunks, or moved to unknowns.");
        } else if (decision.getAction() == ReviewAction.RECOLLECT_EVIDENCE) {
            String evidenceTypes = decision.getRequiredEvidenceTypes().isEmpty()
                    ? "Reviewer 指出的缺口类型"
                    : String.join("、", decision.getRequiredEvidenceTypes());
            instructions.add("Researcher 仅围绕必补证据类型补采：" + evidenceTypes + "。");
            instructions.add("保留既有可用来源，只追加能支撑阻断 finding 的新证据。");
        } else if (decision.getAction() == ReviewAction.REWORK_ANALYSIS) {
            instructions.add("Analyst 优先修复 affectedClaimIds 指向的结构化结论，避免重写无关 claims。");
            instructions.add("无法补足证据时，将相关 claim 降级为 LOW 并明确标注“证据不足，待验证”。");
        } else if (decision.getAction() == ReviewAction.REVISE_REPORT) {
            instructions.add("Writer 只修订 Reviewer 定位的段落或 citation，不重新撰写整份报告。");
            instructions.add("过度推断应降级为待验证假设，并补充缺失 citation 或删除无证据表述。");
        }
        blockingFindings.stream()
                .limit(3)
                .map(finding -> "修复问题：" + finding.getCategory() + " - " + finding.getRecommendation())
                .forEach(instructions::add);
        return instructions;
    }

    private List<ReviewRepairTask> repairTasks(AnalysisRun run, ReviewDecision decision, List<ReviewFinding> blockingFindings) {
        Map<String, ReviewFinding> uniqueFindings = new LinkedHashMap<>();
        for (ReviewFinding finding : blockingFindings) {
            uniqueFindings.putIfAbsent(repairTaskDedupeKey(finding), finding);
        }
        return uniqueFindings.values().stream()
                .limit(MAX_REPAIR_TASKS)
                .map(finding -> repairTask(run, decision, finding))
                .toList();
    }

    private String repairTaskDedupeKey(ReviewFinding finding) {
        String category = normalizeLower(finding.getCategory());
        if (StringUtils.hasText(finding.getFactId())) {
            return category + "|fact|" + finding.getFactId();
        }
        if (StringUtils.hasText(finding.getClaimId())) {
            return category + "|claim|" + finding.getClaimId();
        }
        if (StringUtils.hasText(finding.getChunkKey())) {
            return category + "|chunk|" + finding.getChunkKey();
        }
        if (StringUtils.hasText(finding.getCitationKey())) {
            return category + "|citation|" + finding.getCitationKey();
        }
        if (finding.getParagraphIndex() != null) {
            return category + "|paragraph|" + finding.getParagraphIndex();
        }
        return category + "|message|" + normalizeLower(finding.getMessage());
    }

    private ReviewRepairTask repairTask(AnalysisRun run, ReviewDecision decision, ReviewFinding finding) {
        ReviewRepairTask task = new ReviewRepairTask();
        task.setTargetAgent(decision.getTargetAgent());
        task.setFindingId(finding.getId() == null ? null : finding.getId().toString());
        task.setArtifactId(finding.getArtifactId());
        task.setClaimId(finding.getClaimId());
        task.setFactId(finding.getFactId());
        task.setChunkKey(finding.getChunkKey());
        task.setCitationKey(finding.getCitationKey());
        task.setParagraphIndex(finding.getParagraphIndex());
        task.setExcerpt(finding.getExcerpt());
        task.setCurrentText(repairCurrentText(run, finding));
        task.setCategory(finding.getCategory());
        task.setRequiredEvidenceTypes(decision.getRequiredEvidenceTypes());
        task.setAction(repairAction(decision));
        task.setInstruction(targetedRepairTaskInstruction(decision, finding));
        task.setExpectedFix(targetedRepairExpectedFix(decision, finding));
        task.setAcceptanceCriteria(targetedRepairAcceptanceCriteria(decision, finding));
        return task;
    }

    private String repairAction(ReviewDecision decision) {
        if (decision.getAction() == ReviewAction.RECOLLECT_EVIDENCE) {
            return "COLLECT_TARGETED_EVIDENCE";
        }
        if (decision.getAction() == ReviewAction.REWORK_ANALYSIS) {
            if (decision.getTargetAgent() == AgentName.EXTRACTOR) {
                return "REPAIR_FACT_EXTRACTION";
            }
            return "REPAIR_CLAIM_EVIDENCE";
        }
        if (decision.getAction() == ReviewAction.REVISE_REPORT) {
            return "REVISE_REPORT_CITATION";
        }
        return "MANUAL_REVIEW";
    }

    private String targetedRepairTaskInstruction(ReviewDecision decision, ReviewFinding finding) {
        if (isExtractorFactRepair(decision)) {
            return "Fix " + repairLocation(finding) + " in extracted facts or fact-to-evidence bindings: "
                    + finding.getRecommendation();
        }
        return repairTaskInstruction(decision, finding);
    }

    private String targetedRepairExpectedFix(ReviewDecision decision, ReviewFinding finding) {
        if (isExtractorFactRepair(decision)) {
            return "Regenerate only the affected facts with valid evidenceIds/chunkKeys; unsupported values should be corrected or moved to unknowns.";
        }
        return repairExpectedFix(decision, finding);
    }

    private String targetedRepairAcceptanceCriteria(ReviewDecision decision, ReviewFinding finding) {
        if (isExtractorFactRepair(decision)) {
            return "Affected facts must either cite existing evidence/chunks that support the value, or be removed from facts and recorded as unknowns.";
        }
        return repairAcceptanceCriteria(decision, finding);
    }

    private boolean isExtractorFactRepair(ReviewDecision decision) {
        return decision.getAction() == ReviewAction.REWORK_ANALYSIS
                && decision.getTargetAgent() == AgentName.EXTRACTOR;
    }

    private String repairLocation(ReviewFinding finding) {
        if (StringUtils.hasText(finding.getFactId())) {
            return "fact=" + finding.getFactId();
        }
        if (StringUtils.hasText(finding.getChunkKey())) {
            return "chunk=" + finding.getChunkKey();
        }
        if (StringUtils.hasText(finding.getClaimId())) {
            return "claim=" + finding.getClaimId();
        }
        if (StringUtils.hasText(finding.getCitationKey())) {
            return "citation=" + finding.getCitationKey();
        }
        if (finding.getParagraphIndex() != null) {
            return "paragraph=" + finding.getParagraphIndex();
        }
        return "unscoped finding";
    }

    private String repairCurrentText(AnalysisRun run, ReviewFinding finding) {
        if (StringUtils.hasText(finding.getExcerpt())) {
            return abbreviate(finding.getExcerpt(), 240);
        }
        if (StringUtils.hasText(finding.getClaimId())) {
            return run.getClaims().stream()
                    .filter(claim -> finding.getClaimId().equals(claim.getId()))
                    .map(claim -> abbreviate(claim.getContent(), 240))
                    .findFirst()
                    .orElse("-");
        }
        return "-";
    }

    private String repairTaskInstruction(ReviewDecision decision, ReviewFinding finding) {
        String location = finding.getFactId() != null ? "fact=" + finding.getFactId()
                : finding.getChunkKey() != null ? "chunk=" + finding.getChunkKey()
                : finding.getClaimId() != null ? "claim=" + finding.getClaimId()
                : finding.getCitationKey() != null ? "citation=" + finding.getCitationKey()
                : finding.getParagraphIndex() != null ? "paragraph=" + finding.getParagraphIndex()
                : "未定位到具体对象";
        if (decision.getAction() == ReviewAction.RECOLLECT_EVIDENCE) {
            return "围绕 " + location + " 补采可验证证据：" + finding.getRecommendation();
        }
        if (decision.getAction() == ReviewAction.REWORK_ANALYSIS) {
            return "修复 " + location + " 的结构化结论、证据绑定或置信度：" + finding.getRecommendation();
        }
        if (decision.getAction() == ReviewAction.REVISE_REPORT) {
            return "修订 " + location + " 对应报告表述或引用：" + finding.getRecommendation();
        }
        return finding.getRecommendation();
    }

    private String repairExpectedFix(ReviewDecision decision, ReviewFinding finding) {
        String category = normalizeLower(finding.getCategory());
        if (decision.getAction() == ReviewAction.RECOLLECT_EVIDENCE) {
            String evidenceTypes = decision.getRequiredEvidenceTypes().isEmpty()
                    ? "Reviewer 指定的证据类型"
                    : String.join("、", decision.getRequiredEvidenceTypes());
            return "补充可公开引用的 " + evidenceTypes + " 证据；新增来源必须能支撑当前 claim/段落，而不是泛泛搜索。";
        }
        if (decision.getAction() == ReviewAction.REWORK_ANALYSIS) {
            if (category.contains("missing_evidence") || category.contains("mismatch")) {
                return "重绑有效 evidenceIds；若现有证据无法支撑，降低置信度并把结论改成“待验证/证据不足”。";
            }
            return "只修复受影响 claim 的证据绑定、置信度或措辞，不重写无关 claims。";
        }
        if (decision.getAction() == ReviewAction.REVISE_REPORT) {
            if (category.contains("citation")) {
                return "在定位段落补有效 [S] citation；找不到可用证据时删除该强结论或改成待验证假设。";
            }
            if (category.contains("overclaim")) {
                return "把超出证据边界的表述降级为“公开资料显示/待验证”，并保留或补齐 citation。";
            }
            return "只修订定位段落，补 citation、降级措辞或删除无证据表述。";
        }
        return "人工确认该问题是否已处理，或保留为非阻断风险。";
    }

    private String repairAcceptanceCriteria(ReviewDecision decision, ReviewFinding finding) {
        if (decision.getAction() == ReviewAction.RECOLLECT_EVIDENCE) {
            String evidenceTypes = decision.getRequiredEvidenceTypes().isEmpty()
                    ? "Reviewer 指定的证据类型"
                    : String.join("、", decision.getRequiredEvidenceTypes());
            return "新增证据应覆盖 " + evidenceTypes + "，且可被相关 claim 或报告段落引用。";
        }
        if (decision.getAction() == ReviewAction.REWORK_ANALYSIS) {
            return "相关 claim 必须绑定有效 evidenceIds；无法支撑时应降低置信度并标注待验证。";
        }
        if (decision.getAction() == ReviewAction.REVISE_REPORT) {
            return "报告段落应补齐有效 citation，或删除/降级无证据支撑的强结论。";
        }
        return "人工确认该问题已处理或保留为非阻断风险。";
    }

    private String repairScopeSummary(ReviewDecision decision, List<ReviewFinding> blockingFindings) {
        String claims = decision.getAffectedClaimIds().isEmpty()
                ? "未指定 Claim"
                : String.join("、", decision.getAffectedClaimIds());
        String evidenceTypes = decision.getRequiredEvidenceTypes().isEmpty()
                ? "未指定必补证据"
                : String.join("、", decision.getRequiredEvidenceTypes());
        return "目标 Agent=%s；阻断问题=%d；问题类别=%s；Claim=%s；证据类型=%s。".formatted(
                decision.getTargetAgent() == null ? "无需自动修复" : decision.getTargetAgent(),
                blockingFindings.size(),
                String.join("、", decision.getFindingCategories()),
                claims,
                evidenceTypes
        );
    }

    private void applyBlockingDecision(AnalysisRun run, ReviewDecision decision, List<ReviewFinding> blockingFindings) {
        List<String> missingEvidenceTypes = run.getResearchPackage().getMissingEvidenceTypes();
        List<String> autoCollectableEvidenceTypes = autoCollectableEvidenceTypes(missingEvidenceTypes);
        List<String> manualOnlyEvidenceTypes = manualOnlyEvidenceTypes(missingEvidenceTypes);
        // 路由优先级：如果确实缺采集证据，先回 Researcher；否则结构化 claim 问题回 Analyst；
        // 剩下的引用写法、报告措辞或 LLM overclaim 交给 Writer 修订。
        if (!autoCollectableEvidenceTypes.isEmpty() && blockingFindings.stream().anyMatch(this::needsMoreEvidence)) {
            decision.setAction(ReviewAction.RECOLLECT_EVIDENCE);
            decision.setTargetAgent(AgentName.RESEARCHER);
            decision.setReason("质检发现高风险公开证据缺口（%s），需要 Researcher 优先补采：%s%s。".formatted(
                    categorySummary(blockingFindings),
                    String.join("、", autoCollectableEvidenceTypes),
                    manualOnlyEvidenceTypes.isEmpty()
                            ? ""
                            : "；一手资料缺口（%s）另列为人工补证，不交给公开搜索自动返工".formatted(String.join("、", manualOnlyEvidenceTypes))
            ));
            decision.setRequiredEvidenceTypes(autoCollectableEvidenceTypes);
            return;
        }
        if (!manualOnlyEvidenceTypes.isEmpty() && blockingFindings.stream().anyMatch(this::needsMoreEvidence)) {
            decision.setRequiredEvidenceTypes(manualOnlyEvidenceTypes);
            run.getRecommendedActions().add("质检发现一手调研证据缺口（%s）：公开搜索不能自动生成真实问卷或访谈，请上传对应资料；自动流程将改为降级相关结论或修订报告。"
                    .formatted(String.join("、", manualOnlyEvidenceTypes)));
        }
        if (blockingFindings.stream().anyMatch(this::needsExtractionRework)) {
            decision.setAction(ReviewAction.REWORK_ANALYSIS);
            decision.setTargetAgent(AgentName.EXTRACTOR);
            decision.setReason("Review found high-risk extracted fact issues (%s); rerun Extractor to repair fact values, evidenceIds, or chunk bindings.".formatted(
                    categorySummary(blockingFindings)
            ));
            return;
        }
        if (blockingFindings.stream().anyMatch(this::needsAnalysisRework)) {
            decision.setAction(ReviewAction.REWORK_ANALYSIS);
            decision.setTargetAgent(AgentName.ANALYST);
            decision.setReason("质检发现结构化分析结论存在高风险问题（%s），需要 Analyst 重新绑定证据、调整置信度或降级结论%s。".formatted(
                    categorySummary(blockingFindings),
                    manualOnlyEvidenceTypes.isEmpty()
                            ? ""
                            : "；其中 %s 属于一手调研缺口，不能由公开搜索自动补齐".formatted(String.join("、", manualOnlyEvidenceTypes))
            ));
            return;
        }
        decision.setAction(ReviewAction.REVISE_REPORT);
        decision.setTargetAgent(AgentName.WRITER);
        decision.setReason("质检发现报告表达或引用存在高风险问题（%s），需要 Writer 补充引用、修正 citation 或降级过度推断%s。".formatted(
                categorySummary(blockingFindings),
                manualOnlyEvidenceTypes.isEmpty()
                        ? ""
                        : "；其中 %s 属于一手调研缺口，不能由公开搜索自动补齐".formatted(String.join("、", manualOnlyEvidenceTypes))
        ));
    }

    private List<String> autoCollectableEvidenceTypes(List<String> evidenceTypes) {
        return evidenceTypes.stream()
                .filter(StringUtils::hasText)
                .filter(type -> !isManualOnlyEvidenceType(type))
                .distinct()
                .toList();
    }

    private List<String> manualOnlyEvidenceTypes(List<String> evidenceTypes) {
        return evidenceTypes.stream()
                .filter(StringUtils::hasText)
                .filter(this::isManualOnlyEvidenceType)
                .distinct()
                .toList();
    }

    private boolean isManualOnlyEvidenceType(String evidenceType) {
        return MANUAL_ONLY_EVIDENCE_TYPES.contains(evidenceType.trim().toLowerCase(Locale.ROOT));
    }

    private boolean needsMoreEvidence(ReviewFinding finding) {
        String category = normalizeLower(finding.getCategory());
        return category.equals("citation_missing")
                || category.equals("claim_missing_evidence")
                || category.contains("low_quality_source")
                || category.contains("snippet_only")
                || category.contains("blocked_source")
                || category.contains("fetch_failed_source");
    }

    private boolean needsAnalysisRework(ReviewFinding finding) {
        String category = normalizeLower(finding.getCategory());
        return category.startsWith("claim_")
                || category.contains("analysis")
                || category.contains("schema")
                || category.contains("matrix")
                || category.contains("swot");
    }

    private boolean needsExtractionRework(ReviewFinding finding) {
        String category = normalizeLower(finding.getCategory());
        return category.startsWith("fact_")
                || category.contains("fact_extraction")
                || category.contains("extracted_fact");
    }

    private String categorySummary(List<ReviewFinding> findings) {
        return findings.stream()
                .map(ReviewFinding::getCategory)
                .filter(StringUtils::hasText)
                .distinct()
                .collect(Collectors.joining("、"));
    }

    private String reviewWithLlm(AnalysisRun run, AnalysisArtifact draft) {
        CompletableFuture<LlmSubtaskResult<?>> claimEvidenceTask = CompletableFuture.supplyAsync(
                AgentTraceContext.wrap(() -> LlmSubtaskSupport.runSubtask("Reviewer", "claim-evidence", () -> reviewClaimEvidenceWithLlm(run)))
        );
        CompletableFuture<LlmSubtaskResult<?>> reportOverclaimTask = CompletableFuture.supplyAsync(
                AgentTraceContext.wrap(() -> LlmSubtaskSupport.runSubtask("Reviewer", "report-overclaim", () -> reviewReportOverclaimWithLlm(run, draft)))
        );
        CompletableFuture<LlmSubtaskResult<?>> schemaConsistencyTask = CompletableFuture.supplyAsync(
                AgentTraceContext.wrap(() -> LlmSubtaskSupport.runSubtask("Reviewer", "schema-consistency", () -> reviewSchemaConsistencyWithLlm(run)))
        );
        CompletableFuture<LlmSubtaskResult<?>> sourceQualityTask = sourceQualityNeedsSemanticReview(run)
                ? CompletableFuture.supplyAsync(
                AgentTraceContext.wrap(() -> LlmSubtaskSupport.runSubtask("Reviewer", "source-quality", () -> reviewSourceQualityWithLlm(run)))
        )
                : CompletableFuture.completedFuture(skippedReviewSubtask("source-quality", "No weak source signals detected."));
        CompletableFuture<LlmSubtaskResult<?>> reportActionabilityTask = CompletableFuture.supplyAsync(
                AgentTraceContext.wrap(() -> LlmSubtaskSupport.runSubtask("Reviewer", "report-actionability", () -> reviewReportActionabilityWithLlm(run, draft)))
        );
        CompletableFuture.allOf(claimEvidenceTask, reportOverclaimTask, schemaConsistencyTask, sourceQualityTask, reportActionabilityTask).join();

        List<LlmSubtaskResult<?>> results = List.of(
                claimEvidenceTask.join(),
                reportOverclaimTask.join(),
                schemaConsistencyTask.join(),
                sourceQualityTask.join(),
                reportActionabilityTask.join()
        );
        LlmSubtaskSupport.recordSubtaskTrace("Parallel Reviewer LLM subtasks", results);
        results.stream()
                .filter(result -> !result.succeeded())
                .forEach(result -> run.getRecommendedActions().add(
                        "LLM Reviewer 子任务失败，已跳过该语义检查：" + result.name() + " - " + result.errorMessage()));
        if (results.stream().noneMatch(LlmSubtaskResult::succeeded)) {
            String reasons = results.stream()
                    .map(result -> result.name() + ": " + result.errorMessage())
                    .collect(Collectors.joining("；"));
            throw new IllegalStateException("Reviewer LLM 子任务全部失败：" + reasons);
        }

        int added = results.stream()
                .filter(LlmSubtaskResult::succeeded)
                .mapToInt(result -> mergeLlmFindings(run, ((LlmReviewResult) result.value()).findings()))
                .sum();
        String subtaskSummary = results.stream()
                .map(result -> "- %s：%s%s".formatted(
                        result.name(),
                        result.succeeded()
                                ? "完成，新增候选问题 " + ((LlmReviewResult) result.value()).findings().size() + " 条"
                                : "失败",
                        result.succeeded() ? "" : "（" + result.errorMessage() + "）"
                ))
                .collect(Collectors.joining("\n"));
        return "## LLM 并发语义质检\n\n"
                + subtaskSummary
                + "\n\n结构化新增问题：" + added;
    }

    private LlmSubtaskResult<?> skippedReviewSubtask(String name, String summary) {
        return new LlmSubtaskResult<>(name, new LlmReviewResult(summary, List.of()), null);
    }

    private boolean sourceQualityNeedsSemanticReview(AnalysisRun run) {
        return run.getEvidenceSources().stream().anyMatch(this::isWeakSource)
                || run.getReviewFindings().stream()
                .map(ReviewFinding::getCategory)
                .filter(StringUtils::hasText)
                .map(category -> normalizeLower(category))
                .anyMatch(category -> category.contains("source") || category.contains("citation") || category.contains("evidence"));
    }

    private LlmReviewResult reviewClaimEvidenceWithLlm(AnalysisRun run) {
        String prompt = """
                你是竞品分析工作流中的 claim-evidence Reviewer。请只检查结构化 claim 是否被 evidenceIds 真正支撑。

                输出要求:
                1. 只输出可解析 JSON，不要输出 Markdown，不要包裹代码块。
                2. JSON 格式为 {"summary":"一句话总结","findings":[...]}。
                3. findings 最多 5 项，每项必须包含 severity、category、message、recommendation。
                4. category 优先使用 claim_evidence_mismatch、claim_weak_support、claim_confidence_mismatch。
                5. severity 只能是 HIGH、MEDIUM、LOW；证据完全不支撑高置信 claim 时用 HIGH。
                6. 必须尽量填写 claimId 和 citationKey；message 和 recommendation 各不超过 80 字。
                7. 不要复述规则引擎已有问题，只补充语义层面的不一致。

                Claim 与证据:
                %s

                规则引擎摘要:
                %s
                """.formatted(
                claimEvidencePairs(run),
                compactRuleFindings(run)
        );
        return completeReviewSubtask("claim-evidence", prompt);
    }

    private LlmReviewResult reviewReportOverclaimWithLlm(AnalysisRun run, AnalysisArtifact draft) {
        String prompt = """
                你是竞品分析工作流中的 report-overclaim Reviewer。请只检查报告是否把证据推断得过强、结论是否越过证据边界。

                输出要求:
                1. 只输出可解析 JSON，不要 Markdown。
                2. JSON 格式为 {"summary":"一句话总结","findings":[...]}。
                3. findings 最多 5 项，每项包含 severity、category、message、recommendation。
                4. category 优先使用 report_overclaim、citation_support_mismatch、unsupported_recommendation。
                5. 如果能定位，请填写 citationKey、paragraphIndex、excerpt。
                6. 仅当报告存在明确过度推断时输出 finding；不要替 Writer 重写全文。

                报告关键片段:
                %s

                相关证据:
                %s

                结构化 Claims:
                %s
                """.formatted(
                compactReportExcerpts(draft),
                compactEvidenceBlock(run, draft),
                compactClaimsBlock(run)
        );
        return completeReviewSubtask("report-overclaim", prompt);
    }

    private LlmReviewResult reviewSchemaConsistencyWithLlm(AnalysisRun run) {
        String prompt = """
                你是竞品分析工作流中的 schema-consistency Reviewer。请检查竞品画像、claims、矩阵和 SWOT 是否互相矛盾。

                输出要求:
                1. 只输出可解析 JSON，不要 Markdown。
                2. JSON 格式为 {"summary":"一句话总结","findings":[...]}。
                3. findings 最多 5 项，每项包含 severity、category、message、recommendation。
                4. category 优先使用 schema_consistency、matrix_claim_conflict、swot_claim_conflict。
                5. 如果定位到结构化结论，请填写 claimId；如果定位到文本，请填写 excerpt。
                6. 只指出会影响竞品分析可信度的问题。

                竞品画像摘要:
                %s

                结构化 Claims:
                %s

                矩阵与 SWOT:
                %s
                """.formatted(
                compactProfileBlock(run),
                compactClaimsBlock(run),
                compactAnalysisArtifacts(run)
        );
        return completeReviewSubtask("schema-consistency", prompt);
    }

    private LlmReviewResult reviewSourceQualityWithLlm(AnalysisRun run) {
        String prompt = """
                你是竞品分析工作流中的 source-quality Reviewer。请检查来源质量是否足以支撑最终报告。

                输出要求:
                1. 只输出可解析 JSON，不要 Markdown。
                2. JSON 格式为 {"summary":"一句话总结","findings":[...]}。
                3. findings 最多 5 项，每项包含 severity、category、message、recommendation。
                4. category 优先使用 low_quality_source、marketing_only_source、snippet_only_source、blocked_source、fetch_failed_source。
                5. 必须填写 citationKey；抓取失败或 snippet-only 影响关键结论时用 MEDIUM/HIGH。
                6. 默认优先官网、官方文档、更新日志、定价页、官方技术博客、权威媒体或行业报告。
                7. 如果关键结论只依赖营销软文、SEO 聚合页、二手摘要或明显推广内容，请提出 MEDIUM/HIGH 风险。
                8. 不要要求补充已经存在且质量足够的来源。

                来源质量摘要:
                %s

                规则引擎摘要:
                %s
                """.formatted(
                sourceQualityBlock(run),
                compactRuleFindings(run)
        );
        return completeReviewSubtask("source-quality", prompt);
    }

    private LlmReviewResult reviewReportActionabilityWithLlm(AnalysisRun run, AnalysisArtifact draft) {
        String prompt = """
                你是竞品分析工作流中的 report-actionability Reviewer。请检查最终报告是否真正能支持用户做竞品判断，而不是只做事实摘录。
                输出要求:
                1. 只输出可解析 JSON，不要 Markdown。
                2. JSON 格式为 {"summary":"一句话总结","findings":[...]}。
                3. findings 最多 4 项，每项包含 severity、category、message、recommendation。
                4. category 优先使用 report_quality_insufficient、report_missing_decision_summary、report_dimension_coverage_gap、report_actionability_gap。
                5. 如果报告缺少明确结论、优先级、维度覆盖、可执行建议，且会让用户无法据此选型或汇报，请给 HIGH。
                6. 如果只是措辞可优化或局部不够丰满，请给 MEDIUM/LOW。
                7. HIGH finding 必须填写 paragraphIndex 或 excerpt，方便 Writer 定向修订。
                8. 不要要求补充真实问卷或访谈；公开资料无法补齐的一手洞察只列为人工补充建议。

                用户需求:
                %s

                竞品画像:
                %s

                结构化 Claims:
                %s

                报告关键片段:
                %s
                """.formatted(
                requirementSummary(run),
                compactProfileBlock(run),
                compactClaimsBlock(run),
                abbreviate(draft.getContent(), 1800)
        );
        return completeReviewSubtask("report-actionability", prompt);
    }

    private LlmReviewResult completeReviewSubtask(String subtaskName, String prompt) {
        String raw = llmClient.complete(new ChatRequest(
                List.of(
                        ChatMessage.system("你是严格的事实核查和引用覆盖 Reviewer Agent。"),
                        ChatMessage.user(prompt)
                ),
                ChatOptions.reviewer()
        ).tagged(name().name(), subtaskName));
        return parseLlmReviewResult(raw);
    }

    private LlmReviewResult parseLlmReviewResult(String raw) {
        if (!StringUtils.hasText(raw)) {
            return new LlmReviewResult("", List.of());
        }
        try {
            JsonNode root = objectMapper.readTree(JsonResponseExtractor.extractJsonValue(raw));
            String summary = root.has("summary") ? root.get("summary").asText() : "";
            JsonNode findingsNode = root.has("findings") ? root.get("findings") : root;
            List<LlmFindingDraft> findings = objectMapper.convertValue(findingsNode, new TypeReference<>() {
            });
            List<LlmFindingDraft> boundedFindings = findings == null
                    ? List.of()
                    : findings.stream()
                    .limit(MAX_LLM_FINDINGS_PER_SUBTASK)
                    .toList();
            return new LlmReviewResult(summary, boundedFindings);
        } catch (IllegalArgumentException | JsonProcessingException ex) {
            return new LlmReviewResult("", List.of());
        }
    }

    private int mergeLlmFindings(AnalysisRun run, List<LlmFindingDraft> drafts) {
        // LLM 语义质检是规则质检的增量补充。合并时按 severity/category/claim/citation/message
        // 去重，避免模型复述规则问题导致前端重复展示。
        Set<String> existing = run.getReviewFindings().stream()
                .map(this::findingSignature)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        int added = 0;
        for (LlmFindingDraft draft : drafts) {
            ReviewFinding finding = toFinding(draft);
            if (finding == null) {
                continue;
            }
            String signature = findingSignature(finding);
            if (existing.add(signature)) {
                run.getReviewFindings().add(finding);
                added++;
            }
        }
        return added;
    }

    private ReviewFinding toFinding(LlmFindingDraft draft) {
        if (draft == null || !StringUtils.hasText(draft.message)) {
            return null;
        }
        ReviewSeverity severity = parseSeverity(draft.severity);
        if (severity == ReviewSeverity.HIGH && !hasFindingLocation(draft)) {
            severity = ReviewSeverity.MEDIUM;
        }
        ReviewFinding finding = new ReviewFinding(
                severity,
                sanitizeCategory(draft.category),
                abbreviate(draft.message.trim(), MAX_LLM_FINDING_MESSAGE_LENGTH),
                StringUtils.hasText(draft.recommendation)
                        ? abbreviate(draft.recommendation.trim(), MAX_LLM_FINDING_RECOMMENDATION_LENGTH)
                        : "请人工复核该问题并补充证据或修订报告。"
        );
        finding.setClaimId(blankToNull(draft.claimId));
        finding.setCitationKey(sanitizeCitationKey(draft.citationKey));
        finding.setParagraphIndex(draft.paragraphIndex);
        finding.setExcerpt(blankToNull(abbreviate(draft.excerpt, MAX_LLM_FINDING_EXCERPT_LENGTH)));
        return finding;
    }

    private boolean hasFindingLocation(LlmFindingDraft draft) {
        return StringUtils.hasText(draft.claimId)
                || StringUtils.hasText(draft.citationKey)
                || StringUtils.hasText(draft.excerpt)
                || draft.paragraphIndex != null;
    }

    private String sanitizeCategory(String category) {
        String normalized = StringUtils.hasText(category) ? category.trim() : "llm_semantic_review";
        if (normalized.length() <= MAX_FINDING_CATEGORY_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, MAX_FINDING_CATEGORY_LENGTH);
    }

    private String sanitizeCitationKey(String citationKey) {
        if (!StringUtils.hasText(citationKey)) {
            return null;
        }
        Matcher matcher = CITATION_KEY_PATTERN.matcher(citationKey.trim());
        return matcher.find() ? matcher.group() : null;
    }

    private ReviewSeverity parseSeverity(String value) {
        if (!StringUtils.hasText(value)) {
            return ReviewSeverity.MEDIUM;
        }
        try {
            return ReviewSeverity.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return ReviewSeverity.MEDIUM;
        }
    }

    private String findingSignature(ReviewFinding finding) {
        return "%s|%s|%s|%s".formatted(
                finding.getSeverity(),
                nullToEmpty(finding.getCategory()),
                nullToEmpty(finding.getClaimId()),
                nullToEmpty(finding.getCitationKey()) + "|" + nullToEmpty(finding.getMessage())
        );
    }

    private String claimsBlock(AnalysisRun run) {
        if (run.getClaims().isEmpty()) {
            return "暂无结构化 claim。";
        }
        return run.getClaims().stream()
                .map(claim -> "- id=%s type=%s confidence=%s evidence=%s content=%s".formatted(
                        claim.getId(),
                        claim.getType(),
                        claim.getConfidence(),
                        claim.getEvidenceIds(),
                        claim.getContent()
                ))
                .collect(Collectors.joining("\n"));
    }

    private String requirementSummary(AnalysisRun run) {
        AnalysisRequirement requirement = run.getRequirement();
        if (requirement == null) {
            return "暂无用户需求。";
        }
        return "行业=%s；竞品=%s；维度=%s；目标=%s".formatted(
                nullToEmpty(requirement.getIndustry()),
                requirement.getCompetitors(),
                requirement.getDimensions(),
                nullToEmpty(requirement.getOutputGoal())
        );
    }

    private String compactClaimsBlock(AnalysisRun run) {
        if (run.getClaims().isEmpty()) {
            return "暂无结构化 claim。";
        }
        return run.getClaims().stream()
                .limit(10)
                .map(claim -> "- id=%s type=%s confidence=%s evidence=%s facts=%s chunks=%s content=%s".formatted(
                        claim.getId(),
                        claim.getType(),
                        claim.getConfidence(),
                        claim.getEvidenceIds(),
                        claim.getFactIds(),
                        claim.getChunkKeys(),
                        abbreviate(claim.getContent(), 140)
                ))
                .collect(Collectors.joining("\n"));
    }

    private String compactEvidenceBlock(AnalysisRun run, AnalysisArtifact draft) {
        Set<String> citedKeys = citationKeys(draft.getContent());
        return run.getEvidenceSources().stream()
                .filter(source -> citedKeys.contains(source.getCitationKey()) || isWeakSource(source))
                .limit(10)
                .map(source -> "[%s] %s | type=%s | authority=%s | status=%s | freshness=%s | chunkKinds=%s | %s".formatted(
                        source.getCitationKey(),
                        abbreviate(source.getTitle(), 80),
                        source.getSourceType(),
                        textOrDash(source.getSourceAuthority()),
                        source.getCollectionStatus(),
                        source.getFreshness(),
                        chunkKinds(run, source.getCitationKey()),
                        abbreviate(source.getSnippet(), 180)
                ))
                .collect(Collectors.joining("\n"));
    }

    private String claimEvidencePairs(AnalysisRun run) {
        if (run.getClaims().isEmpty()) {
            return "暂无结构化 claim。";
        }
        return run.getClaims().stream()
                .limit(8)
                .map(claim -> """
                        - claimId=%s type=%s confidence=%s competitors=%s factIds=%s chunkKeys=%s
                          content=%s
                          evidence=%s
                        """.formatted(
                        claim.getId(),
                        claim.getType(),
                        claim.getConfidence(),
                        claim.getCompetitorNames(),
                        claim.getFactIds(),
                        claim.getChunkKeys(),
                        abbreviate(claim.getContent(), 180),
                        evidenceSnippets(run, claim.getEvidenceIds())
                ))
                .collect(Collectors.joining("\n"));
    }

    private String evidenceSnippets(AnalysisRun run, List<String> evidenceIds) {
        if (evidenceIds == null || evidenceIds.isEmpty()) {
            return "未绑定证据";
        }
        return evidenceIds.stream()
                .map(id -> run.getEvidenceSources().stream()
                        .filter(source -> id.equals(source.getCitationKey()))
                        .findFirst()
                        .map(source -> "[%s] %s | type=%s | authority=%s | chunkKinds=%s | status=%s | %s".formatted(
                                source.getCitationKey(),
                                abbreviate(source.getTitle(), 70),
                                source.getSourceType(),
                                textOrDash(source.getSourceAuthority()),
                                chunkKinds(run, source.getCitationKey()),
                                source.getCollectionStatus(),
                                abbreviate(source.getSnippet(), 160)
                        ))
                        .orElse("[" + id + "] 未知来源"))
                .collect(Collectors.joining("\n"));
    }

    private String compactProfileBlock(AnalysisRun run) {
        if (run.getCompetitorProfiles().isEmpty()) {
            return "暂无竞品画像。";
        }
        return run.getCompetitorProfiles().stream()
                .limit(6)
                .map(profile -> "- 产品=%s | 定位=%s | 目标用户=%s | 优势=%s | 弱势=%s | 定价=%s | 证据=%s".formatted(
                        profile.getProductName(),
                        abbreviate(profile.getPositioning(), 80),
                        abbreviate(String.join("、", profile.getTargetUsers()), 80),
                        abbreviate(String.join("、", profile.getStrengths()), 90),
                        abbreviate(String.join("、", profile.getWeaknesses()), 90),
                        profile.getPricingModel() == null ? "暂无" : abbreviate(profile.getPricingModel().getStrategySummary(), 90),
                        profile.getEvidenceIds()
                ))
                .collect(Collectors.joining("\n"));
    }

    private String compactAnalysisArtifacts(AnalysisRun run) {
        AnalysisArtifact matrix = latestArtifact(run.getArtifacts(), ArtifactType.COMPETITIVE_MATRIX).orElse(null);
        AnalysisArtifact swot = latestArtifact(run.getArtifacts(), ArtifactType.SWOT_ANALYSIS).orElse(null);
        return """
                竞品矩阵:
                %s

                SWOT:
                %s
                """.formatted(
                matrix == null ? "暂无矩阵 artifact。" : abbreviate(matrix.getContent(), 1200),
                swot == null ? "暂无 SWOT artifact。" : abbreviate(swot.getContent(), 1200)
        );
    }

    private String sourceQualityBlock(AnalysisRun run) {
        if (run.getEvidenceSources().isEmpty()) {
            return "暂无证据来源。";
        }
        return run.getEvidenceSources().stream()
                .limit(16)
                .map(source -> "[%s] title=%s | url=%s | type=%s | authority=%s | quality=%s | status=%s | freshness=%s | failure=%s | chunkKinds=%s | rawTextChars=%d | note=%s | snippet=%s".formatted(
                        source.getCitationKey(),
                        abbreviate(source.getTitle(), 70),
                        abbreviate(source.getUrl(), 90),
                        source.getSourceType(),
                        textOrDash(source.getSourceAuthority()),
                        source.getSourceQuality(),
                        source.getCollectionStatus(),
                        source.getFreshness(),
                        source.getFailureReason(),
                        chunkKinds(run, source.getCitationKey()),
                        source.getRawText() == null ? 0 : source.getRawText().length(),
                        abbreviate(source.getComplianceNote(), 100),
                        abbreviate(source.getSnippet(), 140)
                ))
                .collect(Collectors.joining("\n"));
    }

    private String compactRuleFindings(AnalysisRun run) {
        if (run.getReviewFindings().isEmpty()) {
            return "规则引擎未发现问题。";
        }
        return run.getReviewFindings().stream()
                .limit(12)
                .map(finding -> "- %s/%s claim=%s citation=%s msg=%s".formatted(
                        finding.getSeverity(),
                        finding.getCategory(),
                        textOrDash(finding.getClaimId()),
                        textOrDash(finding.getCitationKey()),
                        abbreviate(finding.getMessage(), 120)
                ))
                .collect(Collectors.joining("\n"));
    }

    private String compactReportExcerpts(AnalysisArtifact draft) {
        String content = draft.getContent() == null ? "" : draft.getContent();
        String[] paragraphs = content.split("\\n\\s*\\n");
        String excerpts = java.util.Arrays.stream(paragraphs)
                .map(String::trim)
                .filter(StringUtils::hasText)
                .filter(paragraph -> paragraph.contains("[S") || paragraph.contains("待验证") || paragraph.contains("证据不足"))
                .limit(8)
                .map(paragraph -> "- " + abbreviate(paragraph, 260))
                .collect(Collectors.joining("\n"));
        if (StringUtils.hasText(excerpts)) {
            return excerpts;
        }
        return abbreviate(content, 1200);
    }

    private Set<String> citationKeys(String text) {
        Set<String> keys = new LinkedHashSet<>();
        Matcher matcher = CITATION_PATTERN.matcher(text == null ? "" : text);
        while (matcher.find()) {
            keys.add(matcher.group(1));
        }
        return keys;
    }

    private boolean isWeakSource(com.aiinsight.model.run.EvidenceSource source) {
        return "FETCH_FAILED".equals(source.getCollectionStatus())
                || "SEARCH_RESULT_SNIPPET".equals(source.getFreshness())
                || "search_result_snippet".equals(source.getSourceType())
                || "LOW".equals(source.getSourceQuality())
                || "UNUSABLE".equals(source.getSourceQuality())
                || "video".equals(source.getSourceType())
                || "forum".equals(source.getSourceType());
    }

    private String chunkKinds(AnalysisRun run, String citationKey) {
        String kinds = run.getEvidenceChunks().stream()
                .filter(chunk -> citationKey.equals(chunk.getSourceCitationKey()))
                .map(chunk -> chunk.getContentKind())
                .filter(StringUtils::hasText)
                .distinct()
                .collect(Collectors.joining(","));
        return StringUtils.hasText(kinds) ? kinds : "-";
    }

    private String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private void enrichFindingLocations(AnalysisRun run, AnalysisArtifact draft) {
        for (var finding : run.getReviewFindings()) {
            finding.setArtifactId(draft.getId());
            if (finding.getClaimId() == null || finding.getClaimId().isBlank()) {
                finding.setClaimId(matchClaimId(run, finding.getExcerpt()));
            }
        }
    }

    private String matchClaimId(AnalysisRun run, String excerpt) {
        // 基于 Finding excerpt 和 Claim content 的术语重叠度匹配，
        // 而不是靠"风险""机会"等硬编码关键词。
        if (excerpt == null || excerpt.isBlank() || run.getClaims().isEmpty()) {
            return null;
        }
        Set<String> excerptTerms = tokenize(excerpt);
        if (excerptTerms.isEmpty()) {
            return null;
        }
        String bestClaimId = null;
        double bestScore = 0;
        for (var claim : run.getClaims()) {
            double score = 0;
            // 优先用 content 做文本相似度
            if (StringUtils.hasText(claim.getContent())) {
                Set<String> claimTerms = tokenize(claim.getContent());
                if (!claimTerms.isEmpty()) {
                    long overlap = excerptTerms.stream().filter(claimTerms::contains).count();
                    int denominator = Math.min(excerptTerms.size(), claimTerms.size());
                    score = denominator == 0 ? 0 : (double) overlap / denominator;
                }
            }
            // content 为空时，用 claim type 名称（含中文别名）做弱匹配兜底
            if (score == 0 && claim.getType() != null) {
                List<String> typeAliases = typeAliases(claim.getType().name());
                for (String alias : typeAliases) {
                    if (excerptTerms.contains(alias) || excerptTerms.stream().anyMatch(term -> term.contains(alias) || alias.contains(term))) {
                        score = 0.2;
                        break;
                    }
                }
            }
            if (score > bestScore) {
                bestScore = score;
                bestClaimId = claim.getId();
            }
        }
        // 至少要有 15% 的术语重叠才认为是有效匹配，避免误绑
        return bestScore >= 0.15 ? bestClaimId : null;
    }

    private Set<String> tokenize(String text) {
        String normalized = text.toLowerCase(Locale.ROOT).trim();
        Set<String> terms = new LinkedHashSet<>();
        // 英文按空格分词，中文按 bigram 分词
        for (String token : normalized.split("[\\s,，。；;：:、！？!?.·/\\\\()\\[\\]{}\"']+")) {
            if (token.length() <= 1) {
                continue;
            }
            terms.add(token);
            // 中文 bigram
            for (int i = 0; i < token.length() - 1; i++) {
                String bigram = token.substring(i, i + 2);
                if (bigram.chars().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN)) {
                    terms.add(bigram);
                }
            }
        }
        return terms;
    }

    // ClaimType 的中英文别名映射，方便 Finding excerpt 中的中文关键词匹配到无 content 的 Claim
    private List<String> typeAliases(String typeName) {
        String lower = typeName.toLowerCase(Locale.ROOT);
        List<String> aliases = new ArrayList<>();
        aliases.add(lower);
        switch (lower) {
            case "opportunity" -> aliases.addAll(List.of("机会", "机会点"));
            case "risk" -> aliases.addAll(List.of("风险", "威胁"));
            case "strength" -> aliases.addAll(List.of("优势", "强项"));
            case "weakness" -> aliases.addAll(List.of("劣势", "弱项", "不足"));
            case "recommendation" -> aliases.addAll(List.of("建议", "推荐"));
            case "comparison" -> aliases.addAll(List.of("对比", "比较"));
            default -> { /* no extra aliases */ }
        }
        return aliases;
    }

    private record LlmReviewResult(String summary, List<LlmFindingDraft> findings) {
    }

    private static class LlmFindingDraft {
        public String severity;
        public String category;
        public String message;
        public String recommendation;
        public String claimId;
        public String citationKey;
        public Integer paragraphIndex;
        public String excerpt;
    }
}
