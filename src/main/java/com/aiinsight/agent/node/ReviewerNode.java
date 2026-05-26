package com.aiinsight.agent.node;

import com.aiinsight.model.enums.ReviewSeverity;
import com.aiinsight.model.enums.AgentName;
import com.aiinsight.model.run.AnalysisArtifact;
import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.model.enums.ArtifactType;
import com.aiinsight.model.enums.ClaimType;
import com.aiinsight.model.enums.ReviewAction;
import com.aiinsight.model.review.ReviewDecision;
import com.aiinsight.model.review.ReviewFinding;
import com.aiinsight.llm.ChatMessage;
import com.aiinsight.llm.ChatOptions;
import com.aiinsight.llm.ChatRequest;
import com.aiinsight.llm.LlmClient;
import com.aiinsight.service.CitationCoverageEvaluator;
import com.aiinsight.service.fallback.FallbackReviewReportFactory;
import com.aiinsight.agent.AgentNode;
import com.aiinsight.observability.AgentTraceContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

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
// Reviewer 是可信度防线：先跑确定性规则，再让 LLM 做更语义化的质检。
// ReviewDecision 会驱动工作流打回采集或修订节点，形成可观测反馈闭环。
public class ReviewerNode implements AgentNode {

    private static final Pattern CITATION_PATTERN = Pattern.compile("\\[(S\\d+)]");

    private final CitationCoverageEvaluator citationCoverageEvaluator;
    private final LlmClient llmClient;
    private final FallbackReviewReportFactory fallbackReviewReportFactory;
    private final ObjectMapper objectMapper = new ObjectMapper();

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
        AnalysisArtifact draft = latestArtifact(run, ArtifactType.REPORT_DRAFT);
        run.getReviewFindings().clear();
        if (draft != null) {
            // 规则结果进入结构化 finding，不能只存在于 LLM 文本回复里。
            run.getReviewFindings().addAll(citationCoverageEvaluator.evaluate(draft.getContent(), run));
            enrichFindingLocations(run, draft);
        }
        String content;
        if (llmClient.isAvailable() && draft != null) {
            try {
                content = reviewWithLlm(run, draft);
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
                content = fallbackReviewReportFactory.build(run);
                AgentTraceContext.recordFallback("deterministic-reviewer-fallback", content);
            }
        } else {
            log.warn("Reviewer fallback activated: runId={}, reason={}, draftPresent={}, evidenceSources={}, claims={}, ruleFindings={}",
                    run.getId(),
                    llmClient.isAvailable() ? "missing_report_draft" : "llm_unavailable",
                    draft != null,
                    run.getEvidenceSources().size(),
                    run.getClaims().size(),
                    run.getReviewFindings().size());
            content = fallbackReviewReportFactory.build(run);
            AgentTraceContext.recordFallback("deterministic-reviewer-fallback", content);
        }
        run.setReviewDecision(buildDecision(run));
        run.addArtifact(new AnalysisArtifact(ArtifactType.REVIEW_FINDINGS, "Reviewer 复核结果", content, List.of()));
        return run;
    }

    private ReviewDecision buildDecision(AnalysisRun run) {
        ReviewDecision decision = new ReviewDecision();
        // 只有 HIGH finding 会阻断自动流程；MEDIUM/LOW 作为质量提醒保留给前端和最终报告。
        List<ReviewFinding> blockingFindings = run.getReviewFindings().stream()
                .filter(finding -> finding.getSeverity() == ReviewSeverity.HIGH)
                .toList();
        if (blockingFindings.isEmpty()) {
            decision.setAction(ReviewAction.PASS);
            decision.setReason(run.getReviewFindings().isEmpty()
                    ? "规则检查未发现高风险问题。"
                    : "仅发现中低风险质检提醒，不阻断当前报告流程。");
            return decision;
        }
        applyBlockingDecision(run, decision, blockingFindings);
        // Decision metadata should follow the same claim bindings that the UI uses for "locate finding".
        // If no finding can be bound, fall back to claims that still have no evidence.
        List<String> affectedClaimIds = run.getReviewFindings().stream()
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
        return decision;
    }

    private void applyBlockingDecision(AnalysisRun run, ReviewDecision decision, List<ReviewFinding> blockingFindings) {
        List<String> missingEvidenceTypes = run.getResearchPackage().getMissingEvidenceTypes();
        // 路由优先级：如果确实缺采集证据，先回 Researcher；否则结构化 claim 问题回 Analyst；
        // 剩下的引用写法、报告措辞或 LLM overclaim 交给 Writer 修订。
        if (!missingEvidenceTypes.isEmpty() && blockingFindings.stream().anyMatch(this::needsMoreEvidence)) {
            decision.setAction(ReviewAction.RECOLLECT_EVIDENCE);
            decision.setTargetAgent(AgentName.RESEARCHER);
            decision.setReason("质检发现高风险证据缺口（%s），需要 Researcher 优先补采：%s。".formatted(
                    categorySummary(blockingFindings),
                    String.join("、", missingEvidenceTypes)
            ));
            decision.setRequiredEvidenceTypes(missingEvidenceTypes);
            return;
        }
        if (blockingFindings.stream().anyMatch(this::needsAnalysisRework)) {
            decision.setAction(ReviewAction.REWORK_ANALYSIS);
            decision.setTargetAgent(AgentName.ANALYST);
            decision.setReason("质检发现结构化分析结论存在高风险问题（%s），需要 Analyst 重新绑定证据、调整置信度或降级结论。".formatted(
                    categorySummary(blockingFindings)
            ));
            return;
        }
        decision.setAction(ReviewAction.REVISE_REPORT);
        decision.setTargetAgent(AgentName.WRITER);
        decision.setReason("质检发现报告表达或引用存在高风险问题（%s），需要 Writer 补充引用、修正 citation 或降级过度推断。".formatted(
                categorySummary(blockingFindings)
        ));
    }

    private boolean needsMoreEvidence(ReviewFinding finding) {
        String category = normalizedCategory(finding);
        return category.equals("citation_missing")
                || category.equals("claim_missing_evidence")
                || category.contains("low_quality_source")
                || category.contains("snippet_only")
                || category.contains("blocked_source");
    }

    private boolean needsAnalysisRework(ReviewFinding finding) {
        String category = normalizedCategory(finding);
        return category.startsWith("claim_")
                || category.contains("analysis")
                || category.contains("schema")
                || category.contains("matrix")
                || category.contains("swot");
    }

    private String categorySummary(List<ReviewFinding> findings) {
        return findings.stream()
                .map(ReviewFinding::getCategory)
                .filter(StringUtils::hasText)
                .distinct()
                .collect(Collectors.joining("、"));
    }

    private String normalizedCategory(ReviewFinding finding) {
        return finding.getCategory() == null ? "" : finding.getCategory().trim().toLowerCase(Locale.ROOT);
    }

    private String reviewWithLlm(AnalysisRun run, AnalysisArtifact draft) {
        // LLM 只做语义层面的增量抽查：规则引擎已经覆盖 citation/claim 的确定性问题，
        // 这里压缩输入，避免把整篇报告和完整规则报告塞进模型导致 LENGTH 截断。
        String prompt = """
                你是竞品分析小组中的 Reviewer Agent。请基于规则引擎摘要和关键报告片段，补充发现事实一致性、引用弱支撑和过度推断问题。

                输出要求:
                1. 只输出可解析 JSON，不要输出 Markdown，不要包裹代码块。
                2. JSON 格式为 {"summary":"一句话总结","findings":[...]}。
                3. findings 最多 5 项，每项必须包含 severity、category、message、recommendation。
                4. severity 只能是 HIGH、MEDIUM、LOW。
                5. 如果能定位，请填写 claimId、citationKey、paragraphIndex、excerpt。
                6. message 和 recommendation 各不超过 80 字。
                7. 检查结构化 claim 的置信度是否与证据质量匹配。
                8. 对 snippet-only、抓取失败、robots 阻断的来源给出人工复核建议。
                9. 不要复述规则引擎已有问题，不要替 Writer 重写全文。

                重点证据:
                %s

                结构化 Claims:
                %s

                规则引擎摘要:
                %s

                报告关键片段:
                %s
                """.formatted(
                compactEvidenceBlock(run, draft),
                compactClaimsBlock(run),
                compactRuleFindings(run),
                compactReportExcerpts(draft)
        );
        String raw = llmClient.complete(new ChatRequest(
                List.of(
                        ChatMessage.system("你是严格的事实核查和引用覆盖 Reviewer Agent。"),
                        ChatMessage.user(prompt)
                ),
                ChatOptions.reviewer()
        ));
        LlmReviewResult result = parseLlmReviewResult(raw);
        int added = mergeLlmFindings(run, result.findings());
        if (result.findings().isEmpty()) {
            return "## LLM 语义质检\n\n模型未返回可结构化的问题。\n\n## 原始输出\n\n" + raw;
        }
        String summary = StringUtils.hasText(result.summary()) ? result.summary() : "LLM 已完成语义质检。";
        return "## LLM 语义质检\n\n"
                + summary
                + "\n\n结构化新增问题：" + added
                + "\n\n"
                + fallbackReviewReportFactory.build(run);
    }

    private LlmReviewResult parseLlmReviewResult(String raw) {
        if (!StringUtils.hasText(raw)) {
            return new LlmReviewResult("", List.of());
        }
        try {
            JsonNode root = objectMapper.readTree(extractJson(raw));
            String summary = root.has("summary") ? root.get("summary").asText() : "";
            JsonNode findingsNode = root.has("findings") ? root.get("findings") : root;
            List<LlmFindingDraft> findings = objectMapper.convertValue(findingsNode, new TypeReference<>() {
            });
            return new LlmReviewResult(summary, findings == null ? List.of() : findings);
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
        ReviewFinding finding = new ReviewFinding(
                parseSeverity(draft.severity),
                StringUtils.hasText(draft.category) ? draft.category.trim() : "llm_semantic_review",
                draft.message.trim(),
                StringUtils.hasText(draft.recommendation) ? draft.recommendation.trim() : "请人工复核该问题并补充证据或修订报告。"
        );
        finding.setClaimId(blankToNull(draft.claimId));
        finding.setCitationKey(blankToNull(draft.citationKey));
        finding.setParagraphIndex(draft.paragraphIndex);
        finding.setExcerpt(blankToNull(draft.excerpt));
        return finding;
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

    private String compactClaimsBlock(AnalysisRun run) {
        if (run.getClaims().isEmpty()) {
            return "暂无结构化 claim。";
        }
        return run.getClaims().stream()
                .limit(10)
                .map(claim -> "- id=%s type=%s confidence=%s evidence=%s content=%s".formatted(
                        claim.getId(),
                        claim.getType(),
                        claim.getConfidence(),
                        claim.getEvidenceIds(),
                        abbreviate(claim.getContent(), 140)
                ))
                .collect(Collectors.joining("\n"));
    }

    private String compactEvidenceBlock(AnalysisRun run, AnalysisArtifact draft) {
        Set<String> citedKeys = citationKeys(draft.getContent());
        return run.getEvidenceSources().stream()
                .filter(source -> citedKeys.contains(source.getCitationKey()) || isWeakSource(source))
                .limit(10)
                .map(source -> "[%s] %s | type=%s | status=%s | freshness=%s | %s".formatted(
                        source.getCitationKey(),
                        abbreviate(source.getTitle(), 80),
                        source.getSourceType(),
                        source.getCollectionStatus(),
                        source.getFreshness(),
                        abbreviate(source.getSnippet(), 180)
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
                        blankToDash(finding.getClaimId()),
                        blankToDash(finding.getCitationKey()),
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
                || "search_result_snippet".equals(source.getSourceType());
    }

    private String blankToDash(String value) {
        return StringUtils.hasText(value) ? value : "-";
    }

    private String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String abbreviate(String value, int maxLength) {
        String normalized = nullToEmpty(value).replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength) + "...";
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
        // The deterministic reviewer only sees report excerpts, so this is a conservative bridge
        // from rule findings back to structured claims for demo-time navigation.
        if (excerpt != null && excerpt.contains("风险")) {
            return run.getClaims().stream()
                    .filter(claim -> claim.getType() == ClaimType.RISK)
                    .map(claim -> claim.getId())
                    .findFirst()
                    .orElse(null);
        }
        if (excerpt != null && excerpt.contains("机会")) {
            return run.getClaims().stream()
                    .filter(claim -> claim.getType() == ClaimType.OPPORTUNITY)
                    .map(claim -> claim.getId())
                    .findFirst()
                    .orElse(null);
        }
        return run.getClaims().stream()
                .filter(claim -> claim.getType() == ClaimType.OPPORTUNITY || claim.getEvidenceIds().isEmpty())
                .map(claim -> claim.getId())
                .findFirst()
                .orElse(null);
    }

    private AnalysisArtifact latestArtifact(AnalysisRun run, ArtifactType type) {
        List<AnalysisArtifact> artifacts = run.getArtifacts();
        for (int i = artifacts.size() - 1; i >= 0; i--) {
            if (artifacts.get(i).getType() == type) {
                return artifacts.get(i);
            }
        }
        return null;
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
