package com.aiinsight.service;

import com.aiinsight.llm.ChatMessage;
import com.aiinsight.llm.ChatOptions;
import com.aiinsight.llm.ChatRequest;
import com.aiinsight.llm.LlmClient;
import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.model.run.EvidenceSource;
import com.aiinsight.model.schema.SurveyFinding;
import com.aiinsight.model.schema.SurveyInsight;
import com.aiinsight.util.JsonResponseExtractor;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import static com.aiinsight.util.AgentUtils.abbreviate;
import static com.aiinsight.util.AgentUtils.containsIgnoreCase;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Slf4j
public class SurveyInsightExtractor {

    private static final Pattern SAMPLE_SIZE_PATTERN = Pattern.compile("(?i)(sample size|responses|respondents|样本量|样本数)\\D{0,12}(\\d+)");
    private static final Pattern QUESTION_BLOCK_PATTERN = Pattern.compile("(?ims)^Q:\\s*(.+?)(?=^Q:|\\z)");
    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;

    public SurveyInsightExtractor() {
        this(null, new ObjectMapper());
    }

    @Autowired
    public SurveyInsightExtractor(LlmClient llmClient, ObjectMapper objectMapper) {
        this.llmClient = llmClient;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    public List<SurveyInsight> extract(AnalysisRun run) {
        return run.getEvidenceSources().stream()
                .filter(source -> containsIgnoreCase(source.getSourceType(), "survey"))
                .reduce((previous, current) -> current)
                .map(source -> List.of(toSurveyInsight(run, source)))
                .orElseGet(List::of);
    }

    private SurveyInsight toSurveyInsight(AnalysisRun run, EvidenceSource source) {
        String text = sourceText(source);
        SurveyInsight insight = new SurveyInsight();
        insight.setEvidenceId(source.getCitationKey());
        insight.setTitle(source.getTitle());
        insight.setSampleSize(inferSampleSize(text));
        insight.setRespondentSegments(inferSegments(text));
        insight.setCompetitorMentions(mentionedValues(source.getTitle() + " " + text, run.getRequirement().getCompetitors()));
        insight.setRelatedDimensions(inferRelatedDimensions(text, run.getRequirement().getDimensions()));
        insight.setEvidenceIds(List.of(source.getCitationKey()));
        insight.setFindings(extractFindings(run, source, text));
        return enhanceWithLlm(run, source, text, insight);
    }

    private SurveyInsight enhanceWithLlm(AnalysisRun run, EvidenceSource source, String text, SurveyInsight fallback) {
        if (llmClient == null || !llmClient.isAvailable() || !StringUtils.hasText(text)) {
            return fallback;
        }
        try {
            String raw = llmClient.complete(new ChatRequest(
                    List.of(
                            ChatMessage.system("你是 Extractor 的问卷洞察增强器。保留样本量和分布事实，只输出 JSON 对象。"),
                            ChatMessage.user(surveyPrompt(run, source, text, fallback))
                    ),
                    new ChatOptions(0.1, 1600)
            ).tagged("EXTRACTOR", "research-input:survey"));
            SurveyInsight llmInsight = objectMapper.readValue(JsonResponseExtractor.extractJsonObject(raw), SurveyInsight.class);
            return mergeLlmSurveyInsight(llmInsight, fallback, source, run);
        } catch (RuntimeException ex) {
            log.warn("LLM survey insight enhancement failed, using rule result: source={}, exception={}, message={}",
                    source.getCitationKey(), ex.getClass().getSimpleName(), ex.getMessage());
            return fallback;
        } catch (Exception ex) {
            log.warn("LLM survey insight JSON parse failed, using rule result: source={}, message={}",
                    source.getCitationKey(), ex.getMessage());
            return fallback;
        }
    }

    private String surveyPrompt(AnalysisRun run, EvidenceSource source, String text, SurveyInsight fallback) {
        return """
                请基于问卷结果生成结构化洞察。规则解析结果中的 sampleSize、distribution、evidenceIds 是事实锚点，不要改错。

                约束：
                1. 只输出 JSON 对象。
                2. evidenceId 必须等于 "%s"，evidenceIds 只能包含 "%s"。
                3. findings 最多 6 条，必须来自问卷原文或规则解析结果。
                4. competitorMentions 只能从候选竞品中选择。
                5. relatedDimensions 优先从候选维度中选择，可补充短维度名。
                6. 不要编造样本量、百分比或选项分布。

                schema:
                {
                  "evidenceId":"%s",
                  "title":"问卷标题",
                  "sampleSize":"%s",
                  "respondentSegments":["分组"],
                  "competitorMentions":["竞品"],
                  "relatedDimensions":["维度"],
                  "evidenceIds":["%s"],
                  "findings":[{"question":"题目","finding":"发现","distribution":"分布","interpretation":"解释","relatedCompetitors":["竞品"],"relatedDimensions":["维度"],"evidenceIds":["%s"]}]
                }

                候选竞品：%s
                候选维度：%s
                规则解析结果：%s
                原文：
                %s
                """.formatted(
                source.getCitationKey(),
                source.getCitationKey(),
                source.getCitationKey(),
                fallback.getSampleSize(),
                source.getCitationKey(),
                source.getCitationKey(),
                run.getRequirement().getCompetitors(),
                run.getRequirement().getDimensions(),
                abbreviate(ruleSummary(fallback), 1800),
                abbreviate(text, 5000)
        );
    }

    private SurveyInsight mergeLlmSurveyInsight(SurveyInsight llmInsight, SurveyInsight fallback, EvidenceSource source, AnalysisRun run) {
        llmInsight.setEvidenceId(source.getCitationKey());
        llmInsight.setTitle(firstText(llmInsight.getTitle(), fallback.getTitle()));
        llmInsight.setSampleSize(firstText(fallback.getSampleSize(), llmInsight.getSampleSize()));
        llmInsight.setRespondentSegments(firstList(llmInsight.getRespondentSegments(), fallback.getRespondentSegments(), 8));
        llmInsight.setCompetitorMentions(allowedValues(llmInsight.getCompetitorMentions(), run.getRequirement().getCompetitors()));
        if (llmInsight.getCompetitorMentions().isEmpty()) {
            llmInsight.setCompetitorMentions(fallback.getCompetitorMentions());
        }
        llmInsight.setRelatedDimensions(firstList(llmInsight.getRelatedDimensions(), fallback.getRelatedDimensions(), 8));
        llmInsight.setEvidenceIds(List.of(source.getCitationKey()));
        List<SurveyFinding> findings = sanitizeFindings(llmInsight.getFindings(), source, run);
        llmInsight.setFindings(findings.isEmpty() ? fallback.getFindings() : findings);
        return llmInsight;
    }

    private List<SurveyFinding> extractFindings(AnalysisRun run, EvidenceSource source, String text) {
        List<SurveyFinding> findings = new ArrayList<>();
        Matcher matcher = QUESTION_BLOCK_PATTERN.matcher(text == null ? "" : text);
        while (matcher.find() && findings.size() < 8) {
            SurveyFinding finding = findingFromBlock(run, source, matcher.group(1));
            if (StringUtils.hasText(finding.getQuestion()) || StringUtils.hasText(finding.getFinding())) {
                findings.add(finding);
            }
        }
        if (!findings.isEmpty()) {
            return findings;
        }
        return fallbackFindings(run, source, text);
    }

    private SurveyFinding findingFromBlock(AnalysisRun run, EvidenceSource source, String block) {
        List<String> lines = block.lines()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .toList();
        SurveyFinding finding = new SurveyFinding();
        finding.setQuestion(lines.isEmpty() ? "" : stripLabel(lines.get(0), "question"));
        finding.setDistribution(lineValue(lines, "Distribution:"));
        finding.setFinding(lineValue(lines, "Finding:"));
        finding.setInterpretation(inferInterpretation(finding.getFinding(), finding.getDistribution()));
        String searchable = block + " " + finding.getQuestion();
        finding.setRelatedCompetitors(mentionedValues(searchable, run.getRequirement().getCompetitors()));
        finding.setRelatedDimensions(inferRelatedDimensions(searchable, run.getRequirement().getDimensions()));
        finding.setEvidenceIds(List.of(source.getCitationKey()));
        return finding;
    }

    private List<SurveyFinding> fallbackFindings(AnalysisRun run, EvidenceSource source, String text) {
        List<String> sentences = splitSentences(text);
        List<SurveyFinding> findings = new ArrayList<>();
        for (String sentence : sentences) {
            if (findings.size() >= 4) {
                break;
            }
            if (!looksLikeSurveySignal(sentence)) {
                continue;
            }
            SurveyFinding finding = new SurveyFinding();
            finding.setQuestion("Survey summary");
            finding.setFinding(sentence);
            finding.setDistribution(inferDistribution(sentence));
            finding.setInterpretation(inferInterpretation(sentence, finding.getDistribution()));
            finding.setRelatedCompetitors(mentionedValues(sentence, run.getRequirement().getCompetitors()));
            finding.setRelatedDimensions(inferRelatedDimensions(sentence, run.getRequirement().getDimensions()));
            finding.setEvidenceIds(List.of(source.getCitationKey()));
            findings.add(finding);
        }
        return findings;
    }

    private String inferSampleSize(String text) {
        Matcher matcher = SAMPLE_SIZE_PATTERN.matcher(text == null ? "" : text);
        if (matcher.find()) {
            return matcher.group(2) + " responses";
        }
        return "unknown";
    }

    private List<String> inferSegments(String text) {
        String normalized = text == null ? "" : text;
        String marker = "Respondent segments:";
        int index = normalized.toLowerCase(Locale.ROOT).indexOf(marker.toLowerCase(Locale.ROOT));
        if (index >= 0) {
            String line = normalized.substring(index + marker.length()).lines().findFirst().orElse("");
            List<String> values = splitValues(line);
            if (!values.isEmpty()) {
                return values.stream().limit(8).toList();
            }
        }
        List<String> segments = new ArrayList<>();
        if (containsIgnoreCase(normalized, "product manager") || containsIgnoreCase(normalized, "产品")) {
            segments.add("Product team");
        }
        if (containsIgnoreCase(normalized, "engineering") || containsIgnoreCase(normalized, "研发")) {
            segments.add("Engineering team");
        }
        if (containsIgnoreCase(normalized, "IT") || containsIgnoreCase(normalized, "admin") || containsIgnoreCase(normalized, "管理员")) {
            segments.add("IT/admin");
        }
        return segments.isEmpty() ? List.of("Unspecified respondents") : segments;
    }

    private List<String> inferRelatedDimensions(String text, List<String> dimensions) {
        Set<String> related = new LinkedHashSet<>(mentionedValues(text, dimensions));
        if (containsAny(text, "AI", "search", "智能", "搜索")) {
            related.add("AI search");
        }
        if (containsAny(text, "permission", "governance", "audit", "权限", "审计")) {
            related.add("Permission governance");
        }
        if (containsAny(text, "price", "pricing", "cost", "budget", "价格", "预算")) {
            related.add("Pricing");
        }
        if (containsAny(text, "review", "satisfaction", "feedback", "评价", "满意")) {
            related.add("User feedback");
        }
        return new ArrayList<>(related).stream().limit(8).toList();
    }

    private String inferDistribution(String text) {
        if (text == null) {
            return "";
        }
        Matcher percent = Pattern.compile("\\d+%").matcher(text);
        List<String> values = new ArrayList<>();
        while (percent.find() && values.size() < 4) {
            values.add(percent.group());
        }
        return values.isEmpty() ? "" : String.join(" / ", values);
    }

    private String inferInterpretation(String finding, String distribution) {
        String text = (finding == null ? "" : finding) + " " + (distribution == null ? "" : distribution);
        if (containsAny(text, "permission", "governance", "audit", "权限", "审计")) {
            return "Treat permission governance as a validated enterprise buying concern.";
        }
        if (containsAny(text, "AI", "search", "智能", "搜索")) {
            return "Treat AI search and source traceability as validated user value signals.";
        }
        if (containsAny(text, "price", "pricing", "cost", "budget", "价格", "预算")) {
            return "Treat pricing sensitivity as a validated adoption risk.";
        }
        return "Use this survey signal as first-party user research evidence, and keep the sample size visible.";
    }

    private boolean looksLikeSurveySignal(String sentence) {
        return containsAny(sentence, "%", "respondents", "selected", "sample", "用户", "受访", "选择", "认为", "样本");
    }

    private List<String> splitSentences(String text) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }
        String[] parts = text.replaceAll("\\s+", " ").split("(?<=[.!?。！？])\\s*");
        List<String> sentences = new ArrayList<>();
        for (String part : parts) {
            String sentence = part.trim();
            if (!sentence.isBlank()) {
                sentences.add(sentence.length() > 180 ? sentence.substring(0, 180) + "..." : sentence);
            }
        }
        return sentences;
    }

    private String lineValue(List<String> lines, String prefix) {
        return lines.stream()
                .filter(line -> line.regionMatches(true, 0, prefix, 0, prefix.length()))
                .map(line -> line.substring(prefix.length()).trim())
                .findFirst()
                .orElse("");
    }

    private String stripLabel(String value, String label) {
        if (value == null) {
            return "";
        }
        return value.replaceFirst("(?i)^" + Pattern.quote(label) + "\\s*:\\s*", "").trim();
    }

    private List<String> mentionedValues(String text, List<String> candidates) {
        if (candidates == null) {
            return List.of();
        }
        return candidates.stream()
                .filter(candidate -> containsIgnoreCase(text, candidate))
                .distinct()
                .toList();
    }

    private List<String> splitValues(String value) {
        if (!StringUtils.hasText(value)) {
            return List.of();
        }
        return List.of(value.split("[;,，、/]")).stream()
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
    }

    private String sourceText(EvidenceSource source) {
        if (source.getRawText() != null && !source.getRawText().isBlank()) {
            return source.getRawText();
        }
        return source.getSnippet() == null ? "" : source.getSnippet();
    }

    private boolean containsAny(String value, String... patterns) {
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

    private String ruleSummary(SurveyInsight insight) {
        return """
                title=%s
                sampleSize=%s
                respondentSegments=%s
                competitorMentions=%s
                relatedDimensions=%s
                findings=%s
                """.formatted(
                insight.getTitle(),
                insight.getSampleSize(),
                insight.getRespondentSegments(),
                insight.getCompetitorMentions(),
                insight.getRelatedDimensions(),
                insight.getFindings().stream()
                        .map(finding -> "%s | %s | %s | %s".formatted(
                                finding.getQuestion(),
                                finding.getDistribution(),
                                finding.getFinding(),
                                finding.getInterpretation()))
                        .toList()
        );
    }

    private List<SurveyFinding> sanitizeFindings(List<SurveyFinding> findings, EvidenceSource source, AnalysisRun run) {
        if (findings == null) {
            return List.of();
        }
        return findings.stream()
                .filter(finding -> StringUtils.hasText(finding.getFinding()) || StringUtils.hasText(finding.getInterpretation()))
                .limit(6)
                .peek(finding -> {
                    finding.setQuestion(cleanText(finding.getQuestion()));
                    finding.setFinding(cleanText(finding.getFinding()));
                    finding.setDistribution(cleanText(finding.getDistribution()));
                    finding.setInterpretation(cleanText(finding.getInterpretation()));
                    finding.setRelatedCompetitors(allowedValues(finding.getRelatedCompetitors(), run.getRequirement().getCompetitors()));
                    finding.setRelatedDimensions(cleanList(finding.getRelatedDimensions(), 6));
                    finding.setEvidenceIds(List.of(source.getCitationKey()));
                })
                .toList();
    }

    private String firstText(String preferred, String fallback) {
        return StringUtils.hasText(preferred) ? preferred.trim() : (fallback == null ? "" : fallback);
    }

    private List<String> firstList(List<String> preferred, List<String> fallback, int limit) {
        List<String> cleaned = cleanList(preferred, limit);
        return cleaned.isEmpty() ? cleanList(fallback, limit) : cleaned;
    }

    private List<String> cleanList(List<String> values, int limit) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .map(this::cleanText)
                .filter(StringUtils::hasText)
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
                .map(this::cleanText)
                .distinct()
                .limit(6)
                .toList();
    }

    private String cleanText(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

}
