package com.aiinsight.service;

import com.aiinsight.llm.ChatMessage;
import com.aiinsight.llm.ChatOptions;
import com.aiinsight.llm.ChatRequest;
import com.aiinsight.llm.LlmClient;
import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.model.run.EvidenceSource;
import com.aiinsight.model.schema.InterviewInsight;
import com.aiinsight.util.JsonResponseExtractor;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import static com.aiinsight.util.AgentUtils.containsIgnoreCase;
import static com.aiinsight.util.AgentUtils.abbreviate;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
@Slf4j
public class InterviewInsightExtractor {

    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;

    public InterviewInsightExtractor() {
        this(null, new ObjectMapper());
    }

    @Autowired
    public InterviewInsightExtractor(LlmClient llmClient, ObjectMapper objectMapper) {
        this.llmClient = llmClient;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    public List<InterviewInsight> extract(AnalysisRun run) {
        return run.getEvidenceSources().stream()
                .filter(source -> containsIgnoreCase(source.getSourceType(), "interview"))
                .map(source -> toInterviewInsight(run, source))
                .toList();
    }

    private InterviewInsight toInterviewInsight(AnalysisRun run, EvidenceSource source) {
        InterviewInsight fallback = ruleInterviewInsight(run, source);
        InterviewInsight llmInsight = llmInterviewInsight(run, source);
        return llmInsight == null ? fallback : mergeWithFallback(llmInsight, fallback, source);
    }

    private InterviewInsight ruleInterviewInsight(AnalysisRun run, EvidenceSource source) {
        String text = sourceText(source);
        String searchableText = source.getTitle() + " " + text;
        List<String> sentences = splitSentences(text);
        InterviewInsight insight = new InterviewInsight();
        insight.setEvidenceId(source.getCitationKey());
        insight.setSourceTitle(source.getTitle());
        insight.setIntervieweeRole(inferIntervieweeRole(searchableText));
        insight.setScenario(cleanInsightText(inferScenario(sentences)));
        insight.setPainPoints(matchingSentences(sentences, 4,
                "痛点", "问题", "困难", "复杂", "慢", "成本高", "成本", "担心", "顾虑", "阻力", "风险", "权限", "审计",
                "合规", "隐私", "迁移", "学习", "集成", "不愿", "不满意", "不足"));
        insight.setPositiveSignals(matchingSentences(sentences, 3,
                "喜欢", "满意", "认可", "提升", "提效", "方便", "好用", "愿意", "改善", "节省"));
        insight.setNegativeSignals(matchingSentences(sentences, 3,
                "不满", "不满意", "差", "失败", "不足", "抱怨", "不用", "拒绝", "不愿", "风险", "慢", "复杂"));
        insight.setBuyingConcerns(inferBuyingConcerns(text, insight.getPainPoints()));
        insight.setCompetitorMentions(mentionedValues(searchableText, run.getRequirement().getCompetitors()));
        insight.setRelatedDimensions(inferRelatedDimensions(text, run.getRequirement().getDimensions()));
        insight.setDirectQuotes(extractDirectQuotes(sentences, insight.getPainPoints(), insight.getPositiveSignals()));
        insight.setConfidence(insight.getPainPoints().isEmpty() && insight.getPositiveSignals().isEmpty() ? "LOW" : "MEDIUM");
        return insight;
    }

    private InterviewInsight llmInterviewInsight(AnalysisRun run, EvidenceSource source) {
        if (llmClient == null || !llmClient.isAvailable()) {
            return null;
        }
        String text = sourceText(source);
        if (text.isBlank()) {
            return null;
        }
        try {
            String raw = llmClient.complete(new ChatRequest(
                    List.of(
                            ChatMessage.system("你是 Extractor 的一手访谈结构化抽取器。只输出 JSON 对象，不要 Markdown。"),
                            ChatMessage.user(interviewPrompt(run, source, text))
                    ),
                    new ChatOptions(0.1, 1200)
            ).tagged("EXTRACTOR", "research-input:interview"));
            InterviewInsight insight = objectMapper.readValue(JsonResponseExtractor.extractJsonObject(raw), InterviewInsight.class);
            sanitizeInsight(insight, source, run, text);
            return insight;
        } catch (RuntimeException ex) {
            log.warn("LLM interview insight extraction failed, falling back to rules: source={}, exception={}, message={}",
                    source.getCitationKey(), ex.getClass().getSimpleName(), ex.getMessage());
            return null;
        } catch (Exception ex) {
            log.warn("LLM interview insight JSON parse failed, falling back to rules: source={}, message={}",
                    source.getCitationKey(), ex.getMessage());
            return null;
        }
    }

    private String interviewPrompt(AnalysisRun run, EvidenceSource source, String text) {
        return """
                请从用户提供的一手访谈纪要中抽取结构化洞察。

                约束：
                1. 只输出 JSON 对象，字段必须符合 schema。
                2. evidenceId 必须等于 "%s"。
                3. competitorMentions 只能从候选竞品中选择；没有则为空数组。
                4. relatedDimensions 优先从候选维度中选择；可以补充明显相关的短维度名。
                5. directQuotes 必须来自原文或是非常贴近原文的摘录，不要编造。
                6. 不确定就写空数组或 LOW，不要硬凑。
                7. 字段内容使用中文，数组最多 5 项。

                schema:
                {
                  "evidenceId":"%s",
                  "sourceTitle":"资料标题",
                  "intervieweeRole":"角色",
                  "scenario":"使用/评估场景",
                  "painPoints":["痛点"],
                  "positiveSignals":["正向信号"],
                  "negativeSignals":["负向信号"],
                  "buyingConcerns":["购买/引入顾虑"],
                  "competitorMentions":["竞品"],
                  "relatedDimensions":["维度"],
                  "directQuotes":["原话或摘录"],
                  "confidence":"LOW|MEDIUM|HIGH"
                }

                候选竞品：%s
                候选维度：%s
                标题：%s
                原文：
                %s
                """.formatted(
                source.getCitationKey(),
                source.getCitationKey(),
                run.getRequirement().getCompetitors(),
                run.getRequirement().getDimensions(),
                source.getTitle(),
                abbreviate(text, 4500)
        );
    }

    private InterviewInsight mergeWithFallback(InterviewInsight insight, InterviewInsight fallback, EvidenceSource source) {
        insight.setEvidenceId(source.getCitationKey());
        insight.setSourceTitle(firstText(insight.getSourceTitle(), fallback.getSourceTitle()));
        insight.setIntervieweeRole(firstText(insight.getIntervieweeRole(), fallback.getIntervieweeRole()));
        insight.setScenario(cleanInsightText(firstText(insight.getScenario(), fallback.getScenario())));
        insight.setPainPoints(firstList(insight.getPainPoints(), fallback.getPainPoints()));
        insight.setPositiveSignals(firstList(insight.getPositiveSignals(), fallback.getPositiveSignals()));
        insight.setNegativeSignals(firstList(insight.getNegativeSignals(), fallback.getNegativeSignals()));
        insight.setBuyingConcerns(firstList(insight.getBuyingConcerns(), fallback.getBuyingConcerns()));
        insight.setCompetitorMentions(firstList(insight.getCompetitorMentions(), fallback.getCompetitorMentions()));
        insight.setRelatedDimensions(firstList(insight.getRelatedDimensions(), fallback.getRelatedDimensions()));
        insight.setDirectQuotes(firstList(insight.getDirectQuotes(), fallback.getDirectQuotes()));
        insight.setConfidence(firstText(insight.getConfidence(), fallback.getConfidence()));
        return insight;
    }

    private void sanitizeInsight(InterviewInsight insight, EvidenceSource source, AnalysisRun run, String sourceText) {
        insight.setEvidenceId(source.getCitationKey());
        insight.setSourceTitle(firstText(insight.getSourceTitle(), source.getTitle()));
        insight.setScenario(cleanInsightText(insight.getScenario()));
        insight.setPainPoints(cleanList(insight.getPainPoints(), 5));
        insight.setPositiveSignals(cleanList(insight.getPositiveSignals(), 5));
        insight.setNegativeSignals(cleanList(insight.getNegativeSignals(), 5));
        insight.setBuyingConcerns(cleanList(insight.getBuyingConcerns(), 5));
        insight.setCompetitorMentions(allowedValues(insight.getCompetitorMentions(), run.getRequirement().getCompetitors()));
        insight.setRelatedDimensions(cleanList(insight.getRelatedDimensions(), 6));
        insight.setDirectQuotes(sourceBackedQuotes(insight.getDirectQuotes(), sourceText));
        if (!List.of("LOW", "MEDIUM", "HIGH").contains(firstText(insight.getConfidence(), "").toUpperCase())) {
            insight.setConfidence("MEDIUM");
        } else {
            insight.setConfidence(insight.getConfidence().toUpperCase());
        }
    }

    private String sourceText(EvidenceSource source) {
        if (source.getRawText() != null && !source.getRawText().isBlank()) {
            return source.getRawText();
        }
        return source.getSnippet() == null ? "" : source.getSnippet();
    }

    private List<String> splitSentences(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        String[] parts = normalized.split("(?<=[。！？!?；;])\\s*|\\n+");
        List<String> sentences = new ArrayList<>();
        for (String part : parts) {
            String sentence = part.trim();
            if (!sentence.isBlank()) {
                sentences.add(truncate(cleanInsightText(sentence), 140));
            }
        }
        return sentences;
    }

    private String inferIntervieweeRole(String text) {
        if (mentionsAny(text, "销售", "客户", "线索", "CRM")) {
            return "销售/客户运营";
        }
        if (mentionsAny(text, "管理员", "IT", "权限", "安全", "审计", "合规")) {
            return "IT/管理员";
        }
        if (mentionsAny(text, "采购", "决策", "预算", "续费")) {
            return "采购/决策参与者";
        }
        if (mentionsAny(text, "产品", "运营", "增长")) {
            return "产品/运营";
        }
        return "受访用户";
    }

    private String inferScenario(List<String> sentences) {
        return sentences.stream()
                .filter(sentence -> mentionsAny(sentence, "场景", "使用", "评估", "流程", "任务", "最近"))
                .findFirst()
                .or(() -> sentences.stream().findFirst())
                .orElse("待补充具体使用场景");
    }

    private List<String> matchingSentences(List<String> sentences, int limit, String... patterns) {
        Set<String> matched = new LinkedHashSet<>();
        for (String sentence : sentences) {
            if (mentionsAny(sentence, patterns)) {
                matched.add(sentence);
            }
            if (matched.size() >= limit) {
                break;
            }
        }
        return new ArrayList<>(matched);
    }

    private List<String> inferBuyingConcerns(String text, List<String> painPoints) {
        String joined = text + " " + String.join(" ", painPoints);
        List<String> concerns = new ArrayList<>();
        if (mentionsAny(joined, "价格", "费用", "预算", "续费", "付费", "贵", "cost")) {
            concerns.add("价格/预算");
        }
        if (mentionsAny(joined, "权限", "安全", "合规", "隐私", "审计", "风险")) {
            concerns.add("安全/合规风险");
        }
        if (mentionsAny(joined, "迁移", "导入", "替换", "切换")) {
            concerns.add("迁移成本");
        }
        if (mentionsAny(joined, "学习", "培训", "上手", "复杂")) {
            concerns.add("学习成本");
        }
        if (mentionsAny(joined, "集成", "对接", "API", "系统")) {
            concerns.add("集成成本");
        }
        if (mentionsAny(joined, "稳定", "慢", "卡", "性能")) {
            concerns.add("稳定性/性能");
        }
        if (concerns.isEmpty()) {
            concerns.add("需进一步访谈验证");
        }
        return concerns.stream().distinct().toList();
    }

    private List<String> mentionedValues(String text, List<String> candidates) {
        return candidates.stream()
                .filter(candidate -> containsIgnoreCase(text, candidate))
                .distinct()
                .toList();
    }

    private List<String> inferRelatedDimensions(String text, List<String> dimensions) {
        List<String> related = new ArrayList<>(mentionedValues(text, dimensions));
        if (mentionsAny(text, "权限", "安全", "合规", "隐私", "审计")) {
            related.add("权限/安全/合规");
        }
        if (mentionsAny(text, "价格", "费用", "预算", "续费")) {
            related.add("价格策略");
        }
        if (mentionsAny(text, "AI", "智能", "准确", "幻觉", "可信")) {
            related.add("AI 输出可信度");
        }
        if (mentionsAny(text, "学习", "上手", "体验", "复杂", "满意")) {
            related.add("用户体验");
        }
        return related.stream().distinct().limit(5).toList();
    }

    private List<String> extractDirectQuotes(List<String> sentences, List<String> painPoints, List<String> positiveSignals) {
        List<String> quotes = sentences.stream()
                .filter(sentence -> sentence.contains("“") || sentence.contains("”") || sentence.contains("\""))
                .limit(2)
                .toList();
        if (!quotes.isEmpty()) {
            return quotes;
        }
        return java.util.stream.Stream.concat(painPoints.stream(), positiveSignals.stream())
                .limit(2)
                .toList();
    }

    private boolean mentionsAny(String value, String... patterns) {
        if (value == null) {
            return false;
        }
        for (String pattern : patterns) {
            if (containsIgnoreCase(value, pattern)) {
                return true;
            }
        }
        return false;
    }

    private String truncate(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...";
    }

    private String cleanInsightText(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replaceAll("[、，,；;]\\s*[-•*]+\\s*", "；")
                .replaceAll("^\\s*[-•*]+\\s*", "")
                .replaceAll("\\s*[-•*]+\\s*$", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String firstText(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? (fallback == null ? "" : fallback) : preferred.trim();
    }

    private List<String> firstList(List<String> preferred, List<String> fallback) {
        List<String> cleaned = cleanList(preferred, 6);
        return cleaned.isEmpty() ? cleanList(fallback, 6) : cleaned;
    }

    private List<String> cleanList(List<String> values, int limit) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .map(this::cleanInsightText)
                .filter(value -> !value.isBlank())
                .distinct()
                .limit(limit)
                .toList();
    }

    private List<String> allowedValues(List<String> values, List<String> allowed) {
        if (values == null || allowed == null || allowed.isEmpty()) {
            return List.of();
        }
        String joinedValues = String.join(" ", cleanList(values, 12));
        return allowed.stream()
                .filter(candidate -> containsIgnoreCase(joinedValues, candidate))
                .map(this::cleanInsightText)
                .distinct()
                .limit(6)
                .toList();
    }

    private List<String> sourceBackedQuotes(List<String> values, String sourceText) {
        if (sourceText == null || sourceText.isBlank()) {
            return List.of();
        }
        return cleanList(values, 8).stream()
                .filter(quote -> sourceText.contains(quote))
                .limit(4)
                .toList();
    }
}
