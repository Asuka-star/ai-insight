package com.aiinsight.agent.node;

import com.aiinsight.agent.AgentNode;
import com.aiinsight.llm.ChatMessage;
import com.aiinsight.llm.ChatOptions;
import com.aiinsight.llm.ChatRequest;
import com.aiinsight.llm.LlmClient;
import com.aiinsight.model.enums.AgentName;
import com.aiinsight.model.enums.ArtifactType;
import com.aiinsight.model.run.AnalysisArtifact;
import com.aiinsight.model.run.AnalysisRequirement;
import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.model.run.ClarificationDraft;
import com.aiinsight.observability.AgentTraceContext;
import com.aiinsight.service.fallback.FallbackClarificationDraftFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class ClarifierNode implements AgentNode {

    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;
    private final FallbackClarificationDraftFactory fallbackClarificationDraftFactory;

    private static final String UNKNOWN_INDUSTRY = "待澄清行业";
    private static final Set<String> PLACEHOLDER_COMPETITORS = Set.of("竞品 A", "竞品 B");

    @Override
    public AgentName name() {
        return AgentName.CLARIFIER;
    }

    @Override
    public String title() {
        return "澄清任务范围";
    }

    @Override
    public AnalysisRun execute(AnalysisRun run) {
        ClarificationDraft previous = run.getClarificationDraft();
        var result = clarifyScope(run.getRequirement());
        ClarificationDraft draft = result.draft();
        preserveConfirmationState(draft, previous);
        run.setClarificationDraft(draft);
        applyDraftToRequirement(run.getRequirement(), draft);

        String brief = clarificationBriefMarkdown(run.getRequirement(), draft);
        if (result.fallbackUsed()) {
            AgentTraceContext.recordFallback("deterministic-clarifier-fallback", brief);
        }
        if (StringUtils.hasText(result.fallbackReason())) {
            run.getRecommendedActions().add(result.fallbackReason());
        }
        run.addArtifact(new AnalysisArtifact(
                ArtifactType.CLARIFICATION_BRIEF,
                "任务理解与范围摘要",
                brief,
                List.of()
        ));
        return run;
    }

    // Clarifier 是范围澄清阶段唯一允许调用 LLM 的节点；创建草稿仍同步走规则 fallback。
    private ClarificationDraftResult clarifyScope(AnalysisRequirement requirement) {
        if (!llmClient.isAvailable()) {
            return ClarificationDraftResult.fallback(fallbackClarificationDraftFactory.build(requirement), null);
        }
        try {
            ClarificationDraft fallback = fallbackClarificationDraftFactory.build(requirement);
            ClarificationDraft llmDraft = parseLlmDraft(completeWithLlm(requirement));
            return ClarificationDraftResult.llm(mergeDraft(requirement, llmDraft, fallback));
        } catch (RuntimeException ex) {
            log.warn("Clarifier fallback activated: reason=llm_exception, exceptionType={}, message={}, prompt={}",
                    ex.getClass().getName(),
                    ex.getMessage(),
                    requirement.getOriginalPrompt());
            return ClarificationDraftResult.fallback(
                    fallbackClarificationDraftFactory.build(requirement),
                    "LLM 范围澄清失败，已使用规则范围确认内容兜底：" + ex.getMessage()
            );
        }
    }

    private String completeWithLlm(AnalysisRequirement requirement) {
        String prompt = """
                把竞品分析需求整理成结构化任务范围，只输出 JSON。

                JSON 字段：
                {
                  "industry": "行业或业务场景",
                  "competitors": ["竞品名称"],
                  "dimensions": ["分析维度"],
                  "sourcePreferences": ["official_site", "pricing_page", "product_docs", "release_notes", "technical_blog", "authoritative_media", "public_reviews"],
                  "sourceUrls": ["只允许复述用户已提供的 URL，不要编造 URL"],
                  "outputGoal": "报告用途",
                  "clarificationQuestions": ["需要用户确认的问题"]
                }

                约束：
                1. 用户已明确给出的竞品、维度、URL 或报告用途必须原样保留。
                2. 只补全占位或缺失字段，不确定就写入 clarificationQuestions。
                3. sourceUrls 只能复述用户已提供的 URL，不要编造 URL。
                4. 默认优先官方、权威和可复核来源，sourcePreferences 只表示重点覆盖类型。
                5. 输出要短，确保 JSON 完整闭合。

                原始需求：%s
                industry=%s
                competitors=%s
                dimensions=%s
                sources=%s
                urls=%s
                goal=%s
                """.formatted(
                nullToEmpty(requirement.getOriginalPrompt()),
                nullToEmpty(requirement.getIndustry()),
                requirement.getCompetitors(),
                requirement.getDimensions(),
                requirement.getSourcePreferences(),
                requirement.getSourceUrls(),
                nullToEmpty(requirement.getOutputGoal())
        );
        return llmClient.complete(new ChatRequest(
                List.of(
                        ChatMessage.system("你负责把竞品分析需求澄清成结构化工单，必须输出严格 JSON。"),
                        ChatMessage.user(prompt)
                ),
                ChatOptions.clarifier()
        ));
    }

    private ClarificationDraft parseLlmDraft(String raw) {
        try {
            JsonNode root = objectMapper.readTree(extractJsonObject(raw));
            ClarificationDraft draft = new ClarificationDraft();
            draft.setIndustry(text(root, "industry"));
            draft.setCompetitors(textList(root, "competitors"));
            draft.setDimensions(textList(root, "dimensions"));
            draft.setSourcePreferences(textList(root, "sourcePreferences"));
            draft.setSourceUrls(urlList(root, "sourceUrls"));
            draft.setOutputGoal(text(root, "outputGoal"));
            draft.setClarificationQuestions(textList(root, "clarificationQuestions"));
            return draft;
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("LLM 范围确认内容 JSON 解析失败", ex);
        }
    }

    // 用户显式填写的范围字段优先级最高；LLM 只能补齐占位或缺失信息。
    private ClarificationDraft mergeDraft(AnalysisRequirement requirement, ClarificationDraft llmDraft, ClarificationDraft fallback) {
        ClarificationDraft merged = new ClarificationDraft();
        merged.setIndustry(firstMeaningfulIndustry(requirement.getIndustry(), llmDraft.getIndustry(), fallback.getIndustry()));
        merged.setCompetitors(firstMeaningfulCompetitors(requirement.getCompetitors(), llmDraft.getCompetitors(), fallback.getCompetitors()));
        merged.setDimensions(firstNonEmpty(requirement.getDimensions(), llmDraft.getDimensions(), fallback.getDimensions()));
        merged.setSourcePreferences(firstNonEmpty(requirement.getSourcePreferences(), llmDraft.getSourcePreferences(), fallback.getSourcePreferences()));
        // URL 只接受用户输入，避免模型生成不可验证的来源链接。
        merged.setSourceUrls(new ArrayList<>(requirement.getSourceUrls()));
        merged.setOutputGoal(firstText(requirement.getOutputGoal(), llmDraft.getOutputGoal(), fallback.getOutputGoal()));
        merged.setClarificationQuestions(mergeQuestions(llmDraft.getClarificationQuestions(), fallback.getClarificationQuestions()));
        return merged;
    }

    private List<String> mergeQuestions(List<String> llmQuestions, List<String> fallbackQuestions) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        addAllText(merged, llmQuestions);
        addAllText(merged, fallbackQuestions);
        return new ArrayList<>(merged);
    }

    private String firstMeaningfulIndustry(String requirementIndustry, String llmIndustry, String fallbackIndustry) {
        if (hasMeaningfulIndustry(requirementIndustry)) {
            return requirementIndustry;
        }
        if (hasMeaningfulIndustry(llmIndustry)) {
            return llmIndustry;
        }
        return fallbackIndustry;
    }

    private List<String> firstMeaningfulCompetitors(List<String> requirementValues, List<String> llmValues, List<String> fallbackValues) {
        if (!requirementValues.isEmpty() && !hasPlaceholderCompetitors(requirementValues)) {
            return new ArrayList<>(requirementValues);
        }
        if (!llmValues.isEmpty()) {
            return new ArrayList<>(llmValues);
        }
        return new ArrayList<>(fallbackValues);
    }

    @SafeVarargs
    private final List<String> firstNonEmpty(List<String>... candidates) {
        for (List<String> candidate : candidates) {
            if (candidate != null && !candidate.isEmpty()) {
                return new ArrayList<>(candidate);
            }
        }
        return new ArrayList<>();
    }

    private String firstText(String... candidates) {
        for (String candidate : candidates) {
            if (StringUtils.hasText(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private boolean hasMeaningfulIndustry(String value) {
        return StringUtils.hasText(value) && !UNKNOWN_INDUSTRY.equals(value);
    }

    private boolean hasPlaceholderCompetitors(List<String> competitors) {
        return competitors.stream().anyMatch(PLACEHOLDER_COMPETITORS::contains);
    }

    private String extractJsonObject(String raw) {
        if (!StringUtils.hasText(raw)) {
            throw new IllegalArgumentException("LLM 范围确认内容为空");
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("LLM 范围确认内容缺少 JSON 对象");
        }
        return raw.substring(start, end + 1);
    }

    private String text(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asText();
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private List<String> textList(JsonNode root, String field) {
        List<String> values = new ArrayList<>();
        JsonNode node = root.get(field);
        if (node == null || !node.isArray()) {
            return values;
        }
        node.forEach(item -> {
            String value = item.asText();
            if (StringUtils.hasText(value)) {
                values.add(value.trim());
            }
        });
        return values;
    }

    private List<String> urlList(JsonNode root, String field) {
        return textList(root, field).stream()
                .filter(url -> url.startsWith("http://") || url.startsWith("https://"))
                .toList();
    }

    private void addAllText(LinkedHashSet<String> target, List<String> values) {
        values.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .forEach(target::add);
    }

    private void preserveConfirmationState(ClarificationDraft draft, ClarificationDraft previous) {
        if (previous == null) {
            return;
        }
        draft.setConfirmed(previous.isConfirmed());
        draft.setConfirmedAt(previous.getConfirmedAt());
        draft.setCreatedAt(previous.getCreatedAt() == null ? Instant.now() : previous.getCreatedAt());
    }

    private void applyDraftToRequirement(AnalysisRequirement requirement, ClarificationDraft draft) {
        if (requirement == null || draft == null) {
            return;
        }
        if (StringUtils.hasText(draft.getIndustry())) {
            requirement.setIndustry(draft.getIndustry());
        }
        if (!draft.getCompetitors().isEmpty()) {
            requirement.setCompetitors(new ArrayList<>(draft.getCompetitors()));
        }
        if (!draft.getDimensions().isEmpty()) {
            requirement.setDimensions(new ArrayList<>(draft.getDimensions()));
        }
        if (!draft.getSourcePreferences().isEmpty()) {
            requirement.setSourcePreferences(new ArrayList<>(draft.getSourcePreferences()));
        }
        if (!draft.getSourceUrls().isEmpty()) {
            requirement.setSourceUrls(new ArrayList<>(draft.getSourceUrls()));
        }
        if (StringUtils.hasText(draft.getOutputGoal())) {
            requirement.setOutputGoal(draft.getOutputGoal());
        }
    }

    private String clarificationBriefMarkdown(AnalysisRequirement requirement, ClarificationDraft draft) {
        return """
                ## 任务范围

                - 行业/场景：%s
                - 竞品：%s
                - 分析维度：%s
                - 资料偏好：%s
                - 报告用途：%s

                ## 待确认问题

                %s

                ## 执行说明

                Clarifier 已将确认后的范围同步为结构化任务输入；下游 Agent 只能围绕该范围采集证据、抽取 Schema、生成分析和报告。
                """.formatted(
                textOrFallback(draft.getIndustry(), requirement == null ? null : requirement.getIndustry(), "待澄清"),
                listText(draft.getCompetitors()),
                listText(draft.getDimensions()),
                listText(draft.getSourcePreferences()),
                textOrFallback(draft.getOutputGoal(), requirement == null ? null : requirement.getOutputGoal(), "待确认"),
                draft.getClarificationQuestions().isEmpty()
                        ? "- 暂无新增确认问题。"
                        : draft.getClarificationQuestions().stream()
                        .map(question -> "- " + question)
                        .collect(Collectors.joining("\n"))
        );
    }

    private String listText(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "待确认";
        }
        return String.join("、", values);
    }

    private String textOrFallback(String preferred, String fallback, String defaultValue) {
        if (StringUtils.hasText(preferred)) {
            return preferred;
        }
        if (StringUtils.hasText(fallback)) {
            return fallback;
        }
        return defaultValue;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private record ClarificationDraftResult(ClarificationDraft draft, boolean fallbackUsed, String fallbackReason) {

        static ClarificationDraftResult llm(ClarificationDraft draft) {
            return new ClarificationDraftResult(draft, false, null);
        }

        static ClarificationDraftResult fallback(ClarificationDraft draft, String fallbackReason) {
            return new ClarificationDraftResult(draft, true, fallbackReason);
        }
    }
}
