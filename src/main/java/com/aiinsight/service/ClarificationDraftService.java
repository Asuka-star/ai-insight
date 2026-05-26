package com.aiinsight.service;

import com.aiinsight.llm.ChatMessage;
import com.aiinsight.llm.ChatOptions;
import com.aiinsight.llm.ChatRequest;
import com.aiinsight.llm.LlmClient;
import com.aiinsight.model.run.AnalysisRequirement;
import com.aiinsight.model.run.ClarificationDraft;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
@Slf4j
public class ClarificationDraftService {

    private static final String UNKNOWN_INDUSTRY = "待澄清行业";
    private static final Set<String> PLACEHOLDER_COMPETITORS = Set.of("竞品 A", "竞品 B");

    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;

    public ClarificationDraftService(LlmClient llmClient, ObjectMapper objectMapper) {
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
    }

    public ClarificationDraftResult createDraft(AnalysisRequirement requirement) {
        return ClarificationDraftResult.rules(createWithRules(requirement));
    }

    public ClarificationDraftResult clarifyScope(AnalysisRequirement requirement) {
        ClarificationDraft fallback = createWithRules(requirement);
        if (!llmClient.isAvailable()) {
            return ClarificationDraftResult.fallback(fallback, null);
        }
        try {
            ClarificationDraft llmDraft = parseLlmDraft(completeWithLlm(requirement));
            return ClarificationDraftResult.llm(mergeDraft(requirement, llmDraft, fallback));
        } catch (RuntimeException ex) {
            log.warn("Clarification draft LLM fallback activated: exceptionType={}, message={}, prompt={}",
                    ex.getClass().getName(),
                    ex.getMessage(),
                    requirement.getOriginalPrompt());
            return ClarificationDraftResult.fallback(
                    fallback,
                    "LLM 范围澄清失败，已使用规则范围确认内容兜底：" + ex.getMessage()
            );
        }
    }

    private ClarificationDraft createWithRules(AnalysisRequirement requirement) {
        ClarificationDraft draft = new ClarificationDraft(requirement);
        draft.getClarificationQuestions().addAll(ruleQuestions(requirement));
        return draft;
    }

    private List<String> ruleQuestions(AnalysisRequirement requirement) {
        List<String> questions = new ArrayList<>();
        if (hasPlaceholderCompetitors(requirement.getCompetitors()) || requirement.getCompetitors().size() < 3) {
            questions.add("是否需要加入 Confluence、Airtable 等标杆产品作为对照？");
        }
        if (requirement.getSourceUrls().isEmpty()) {
            questions.add("是否有官网、价格页、产品文档、公开评价或访谈记录可以作为资料来源？");
        }
        if (!StringUtils.hasText(requirement.getOutputGoal())) {
            questions.add("这份报告主要用于支持什么决策：产品评审、规划立项，还是向上汇报？");
        }
        if (!hasMeaningfulIndustry(requirement.getIndustry())) {
            questions.add("分析所属行业或业务场景是否需要进一步明确？");
        }
        return questions;
    }

    private String completeWithLlm(AnalysisRequirement requirement) {
        String prompt = """
                把竞品分析需求整理成结构化任务范围，只输出 JSON。

                JSON 字段：
                {
                  "industry": "行业或业务场景",
                  "competitors": ["竞品名称"],
                  "dimensions": ["分析维度"],
                  "sourcePreferences": ["official_site", "pricing_page", "product_docs", "release_notes", "public_reviews"],
                  "sourceUrls": ["只允许复述用户已提供的 URL，不要编造 URL"],
                  "outputGoal": "报告用途",
                  "clarificationQuestions": ["需要用户确认的问题"]
                }

                约束：
                1. 用户已明确给出的竞品、维度、URL 或报告用途必须原样保留。
                2. 只补全占位或缺失字段，不确定就写入 clarificationQuestions。
                3. sourceUrls 只能复述用户已提供的 URL，不要编造 URL。
                4. 输出要短，确保 JSON 完整闭合。

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

    private ClarificationDraft mergeDraft(AnalysisRequirement requirement, ClarificationDraft llmDraft, ClarificationDraft fallback) {
        ClarificationDraft merged = new ClarificationDraft();
        merged.setIndustry(firstMeaningfulIndustry(requirement.getIndustry(), llmDraft.getIndustry(), fallback.getIndustry()));
        merged.setCompetitors(firstMeaningfulCompetitors(requirement.getCompetitors(), llmDraft.getCompetitors(), fallback.getCompetitors()));
        merged.setDimensions(firstNonEmpty(requirement.getDimensions(), llmDraft.getDimensions(), fallback.getDimensions()));
        merged.setSourcePreferences(firstNonEmpty(requirement.getSourcePreferences(), llmDraft.getSourcePreferences(), fallback.getSourcePreferences()));
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

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    public record ClarificationDraftResult(ClarificationDraft draft, boolean fallbackUsed, String fallbackReason) {

        static ClarificationDraftResult rules(ClarificationDraft draft) {
            return new ClarificationDraftResult(draft, false, null);
        }

        static ClarificationDraftResult llm(ClarificationDraft draft) {
            return new ClarificationDraftResult(draft, false, null);
        }

        static ClarificationDraftResult fallback(ClarificationDraft draft, String fallbackReason) {
            return new ClarificationDraftResult(draft, true, fallbackReason);
        }
    }
}
