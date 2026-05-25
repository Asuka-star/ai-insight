package com.aiinsight.service;

import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.model.run.EvidenceSource;
import com.aiinsight.model.schema.InterviewInsight;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class InterviewInsightExtractor {

    public List<InterviewInsight> extract(AnalysisRun run) {
        return run.getEvidenceSources().stream()
                .filter(source -> containsIgnoreCase(source.getSourceType(), "interview"))
                .map(source -> toInterviewInsight(run, source))
                .toList();
    }

    private InterviewInsight toInterviewInsight(AnalysisRun run, EvidenceSource source) {
        String text = sourceText(source);
        String searchableText = source.getTitle() + " " + text;
        List<String> sentences = splitSentences(text);
        InterviewInsight insight = new InterviewInsight();
        insight.setEvidenceId(source.getCitationKey());
        insight.setSourceTitle(source.getTitle());
        insight.setIntervieweeRole(inferIntervieweeRole(searchableText));
        insight.setScenario(inferScenario(sentences));
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
                sentences.add(truncate(sentence, 140));
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

    private boolean containsIgnoreCase(String text, String pattern) {
        return text != null && pattern != null && text.toLowerCase(Locale.ROOT).contains(pattern.toLowerCase(Locale.ROOT));
    }

    private String truncate(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...";
    }
}
