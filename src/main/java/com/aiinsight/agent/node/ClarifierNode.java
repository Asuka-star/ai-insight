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
import com.aiinsight.model.run.ClarificationItem;
import com.aiinsight.model.run.ClarificationOption;
import com.aiinsight.observability.AgentTraceContext;
import com.aiinsight.service.fallback.FallbackClarificationDraftFactory;
import com.aiinsight.util.JsonResponseExtractor;
import static com.aiinsight.util.AgentUtils.nullToEmpty;
import static com.aiinsight.util.AgentUtils.safeList;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

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
        var result = clarifyScope(run.getRequirement());
        ClarificationDraft draft = result.draft();
        // 每次澄清都产出全新的未确认草稿，不继承历史 confirmed 状态。
        // 用户必须在看到新的澄清结果后手动确认范围。
        draft.setConfirmed(false);
        draft.setConfirmedAt(null);
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

    // Clarifier 是范围澄清阶段唯一允许调用 LLM 的节点；失败时统一回退到规则草稿。
    private ClarificationDraftResult clarifyScope(AnalysisRequirement requirement) {
        if (!llmClient.isAvailable()) {
            return ClarificationDraftResult.fallback(fallbackClarificationDraftFactory.build(requirement), null);
        }
        try {
            ClarificationDraft fallback = fallbackClarificationDraftFactory.build(requirement);
            String raw = completeWithLlm(requirement);
            ClarificationDraft llmDraft;
            try {
                llmDraft = parseLlmDraft(raw);
            } catch (RuntimeException parseEx) {
                // 把模型原始输出打到日志，方便排查 JSON 格式问题（之前只记录了异常信息，看不到模型到底返回了什么）。
                log.warn("Clarifier JSON parse failed, raw LLM output (first 2000 chars): {}",
                        raw.length() > 2000 ? raw.substring(0, 2000) + "...(truncated)" : raw,
                        parseEx);
                throw parseEx;
            }
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
                  "sourceUrls": ["只允许复述用户已提供的 URL，不要编造 URL"],
                  "outputGoal": "报告用途",
                  "clarificationQuestions": ["需要用户确认的问题"],
                  "clarificationItems": [
                    {
                      "field": "industry|competitors|dimensions|sourceUrls|outputGoal",
                      "question": "需要用户确认的问题",
                      "reason": "为什么要确认",
                      "required": true,
                      "options": [
                        {"label": "选项名称", "description": "选择影响", "values": ["回填值"], "recommended": true}
                      ]
                    }
                  ]
                }

                约束：
                1. 用户已明确给出的竞品、维度、URL 或报告用途必须原样保留。
                2. 只补全占位或缺失字段，不确定就写入 clarificationQuestions。
                3. sourceUrls 只能复述用户已提供的 URL，不要编造 URL。
                4. clarificationItems 要给出可点选的正常选项；如果是单值字段，values 放一个值；如果是列表字段，values 放完整列表。
                5. 如果竞品名称疑似拼写错误、错别字或品牌别名，不要静默改写；保留用户原值，并在 clarificationItems 中给出修正选项，values 放修正后的完整竞品列表。
                6. 如果竞品过少或用户表达“同类产品/标杆产品”等模糊范围，可以在 clarificationItems 中给出补充竞品选项，values 放补充后的完整竞品列表。
                7. 输出要短，确保 JSON 完整闭合。

                当前编辑后的结构化范围是唯一权威输入；如果它和历史原始需求不一致，以当前范围为准，不要恢复历史值。
                当前范围：
                industry=%s
                competitors=%s
                dimensions=%s
                sourceUrls=%s
                outputGoal=%s
                """.formatted(
                nullToEmpty(requirement.getIndustry()),
                requirement.getCompetitors(),
                requirement.getDimensions(),
                requirement.getSourceUrls(),
                nullToEmpty(requirement.getOutputGoal())
        );
        return llmClient.complete(new ChatRequest(
                List.of(
                        ChatMessage.system("你负责把竞品分析需求澄清成结构化工单，必须输出严格 JSON。"),
                        ChatMessage.user(prompt)
                ),
                ChatOptions.clarifier()
        ).tagged(name().name(), "scope-clarification"));
    }

    private ClarificationDraft parseLlmDraft(String raw) {
        try {
            String json = JsonResponseExtractor.extractJsonObject(raw);
            JsonNode root = safeReadTree(json);
            ClarificationDraft draft = new ClarificationDraft();
            draft.setIndustry(text(root, "industry"));
            draft.setCompetitors(textList(root, "competitors"));
            draft.setDimensions(textList(root, "dimensions"));
            draft.setSourceUrls(urlList(root, "sourceUrls"));
            draft.setOutputGoal(text(root, "outputGoal"));
            draft.setClarificationQuestions(textList(root, "clarificationQuestions"));
            draft.setClarificationItems(clarificationItems(root));
            return draft;
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("LLM 范围确认内容 JSON 解析失败: " + ex.getMessage(), ex);
        }
    }

    // 部分小模型会在 JSON 中留下尾逗号或字面换行，先尝试原样解析，失败后做一次轻量修复。
    private JsonNode safeReadTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException firstAttempt) {
            String repaired = json
                    .replaceAll(",\\s*([}\\]])", "$1")   // 删除 } 或 ] 前的尾逗号
                    .replaceAll("(?<=\")\\n", "\\\\n");  // 字符串内的字面换行转义为 \\n
            try {
                return objectMapper.readTree(repaired);
            } catch (JsonProcessingException secondAttempt) {
                throw new IllegalArgumentException("JSON 解析失败（已尝试修复尾逗号和字面换行）: " + firstAttempt.getMessage(), firstAttempt);
            }
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
        // LLM 成功时，前端可见的澄清问题必须只展示模型返回内容；规则 fallback 只负责补齐结构化字段。
        merged.setClarificationQuestions(mergeQuestions(llmDraft.getClarificationQuestions()));
        merged.setClarificationItems(mergeItems(llmDraft.getClarificationItems()));
        return merged;
    }

    private List<String> mergeQuestions(List<String> llmQuestions) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        addAllText(merged, llmQuestions);
        return new ArrayList<>(merged);
    }

    private List<ClarificationItem> mergeItems(List<ClarificationItem> llmItems) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<ClarificationItem> merged = new ArrayList<>();
        addItems(merged, seen, userVisibleItems(llmItems));
        return merged;
    }

    private List<ClarificationItem> userVisibleItems(List<ClarificationItem> items) {
        return items.stream()
                .filter(item -> !"sourcePreferences".equals(item.getField()))
                .toList();
    }

    private void addItems(List<ClarificationItem> target, LinkedHashSet<String> seen, List<ClarificationItem> items) {
        for (ClarificationItem item : safeList(items)) {
            if (item == null || !StringUtils.hasText(item.getField()) || !StringUtils.hasText(item.getQuestion())) {
                continue;
            }
            String key = item.getField().trim().toLowerCase() + "::" + item.getQuestion().trim();
            if (seen.add(key)) {
                target.add(item);
            }
        }
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

    private List<ClarificationItem> clarificationItems(JsonNode root) {
        List<ClarificationItem> items = new ArrayList<>();
        JsonNode node = root.get("clarificationItems");
        if (node == null || !node.isArray()) {
            return items;
        }
        node.forEach(itemNode -> {
            ClarificationItem item = new ClarificationItem();
            item.setField(text(itemNode, "field"));
            item.setQuestion(text(itemNode, "question"));
            item.setReason(text(itemNode, "reason"));
            item.setRequired(booleanValue(itemNode, "required"));
            item.setOptions(clarificationOptions(itemNode));
            item.setSelectedValues(textList(itemNode, "selectedValues"));
            if (StringUtils.hasText(item.getField()) && StringUtils.hasText(item.getQuestion())) {
                items.add(item);
            }
        });
        return items;
    }

    private List<ClarificationOption> clarificationOptions(JsonNode itemNode) {
        List<ClarificationOption> options = new ArrayList<>();
        JsonNode node = itemNode.get("options");
        if (node == null || !node.isArray()) {
            return options;
        }
        node.forEach(optionNode -> {
            ClarificationOption option = new ClarificationOption();
            option.setLabel(text(optionNode, "label"));
            option.setDescription(text(optionNode, "description"));
            option.setValues(optionValues(optionNode));
            option.setRecommended(booleanValue(optionNode, "recommended"));
            if (StringUtils.hasText(option.getLabel())) {
                options.add(option);
            }
        });
        return options;
    }

    private List<String> optionValues(JsonNode optionNode) {
        JsonNode valuesNode = optionNode.get("values");
        if (valuesNode == null) {
            valuesNode = optionNode.get("value");
        }
        List<String> values = new ArrayList<>();
        if (valuesNode == null || valuesNode.isNull()) {
            return values;
        }
        if (valuesNode.isArray()) {
            valuesNode.forEach(valueNode -> {
                String value = valueNode.asText();
                if (StringUtils.hasText(value)) {
                    values.add(value.trim());
                }
            });
            return values;
        }
        String value = valuesNode.asText();
        if (StringUtils.hasText(value)) {
            values.add(value.trim());
        }
        return values;
    }

    private boolean booleanValue(JsonNode root, String field) {
        JsonNode node = root.get(field);
        return node != null && node.asBoolean(false);
    }

    private void addAllText(LinkedHashSet<String> target, List<String> values) {
        values.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .forEach(target::add);
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

                ## 可选澄清项

                %s
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
                        .collect(Collectors.joining("\n")),
                clarificationItemsMarkdown(draft.getClarificationItems())
        );
    }

    private String clarificationItemsMarkdown(List<ClarificationItem> items) {
        if (items == null || items.isEmpty()) {
            return "- 暂无可选澄清项。";
        }
        return items.stream()
                .map(item -> {
                    String options = item.getOptions() == null || item.getOptions().isEmpty()
                            ? "无可选项"
                            : item.getOptions().stream()
                            .map(option -> "%s%s：%s".formatted(
                                    option.isRecommended() ? "推荐：" : "",
                                    option.getLabel(),
                                    listText(option.getValues())
                            ))
                            .collect(Collectors.joining("；"));
                    return "- %s：%s（%s）".formatted(item.getField(), item.getQuestion(), options);
                })
                .collect(Collectors.joining("\n"));
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

    private record ClarificationDraftResult(ClarificationDraft draft, boolean fallbackUsed, String fallbackReason) {

        static ClarificationDraftResult llm(ClarificationDraft draft) {
            return new ClarificationDraftResult(draft, false, null);
        }

        static ClarificationDraftResult fallback(ClarificationDraft draft, String fallbackReason) {
            return new ClarificationDraftResult(draft, true, fallbackReason);
        }
    }
}
