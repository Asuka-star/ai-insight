package com.aiinsight.service;

import com.aiinsight.dto.CreateAnalysisRunRequest;
import com.aiinsight.model.run.AnalysisRequirement;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class AnalysisRequestNormalizer {

    private static final List<String> DEFAULT_DIMENSIONS = List.of(
            "产品定位", "核心功能", "目标用户", "商业模式", "价格策略", "优劣势", "机会点", "风险提示"
    );

    private static final List<String> DEFAULT_SOURCES = List.of(
            "official_site", "pricing_page", "product_docs", "release_notes", "technical_blog", "authoritative_media", "public_reviews"
    );
    private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s，。；、,;]+");
    private static final Pattern COMPETITOR_SEGMENT_PATTERN = Pattern.compile(
            "(?:分析|对比|比较|研究|加入|补充|新增|再加入|覆盖)\\s*([^，。；;\\n]+)"
    );
    private static final Set<String> NON_COMPETITOR_TERMS = Set.of(
            "官网", "官方网站", "价格页", "定价页", "产品文档", "帮助文档", "公开评价", "用户评价",
            "访谈记录", "问卷", "调研", "更新日志", "技术博客", "报告", "资料", "来源"
    );

    public AnalysisRequirement normalize(CreateAnalysisRunRequest request) {
        String industry = StringUtils.hasText(request.getIndustry()) ? request.getIndustry() : inferIndustry(request.getPrompt());
        List<String> competitors = request.getCompetitors().isEmpty()
                ? inferCompetitors(request.getPrompt())
                : request.getCompetitors();
        List<String> dimensions = request.getDimensions().isEmpty() ? DEFAULT_DIMENSIONS : request.getDimensions();
        List<String> sourcePreferences = request.getSourcePreferences().isEmpty() ? DEFAULT_SOURCES : request.getSourcePreferences();
        return new AnalysisRequirement(
                request.getPrompt(),
                industry,
                competitors,
                dimensions,
                sourcePreferences,
                normalizeSourceUrls(request),
                request.getOutputGoal()
        );
    }

    private String inferIndustry(String prompt) {
        if (prompt.contains("文档")) {
            return "AI 协作文档";
        }
        if (prompt.contains("CRM")) {
            return "企业服务 CRM";
        }
        if (prompt.contains("BI") || prompt.contains("数据分析")) {
            return "智能数据分析";
        }
        return "待澄清行业";
    }

    private List<String> inferCompetitors(String prompt) {
        List<String> extractedCompetitors = extractMentionedCompetitors(prompt);
        if (extractedCompetitors.size() >= 2) {
            return extractedCompetitors;
        }
        Set<String> competitors = new LinkedHashSet<>();
        competitors.addAll(extractedCompetitors);
        while (competitors.size() < 2) {
            competitors.add("竞品 " + (char) ('A' + competitors.size()));
        }
        return new ArrayList<>(competitors);
    }

    List<String> extractMentionedCompetitors(String text) {
        Set<String> competitors = new LinkedHashSet<>();
        if (!StringUtils.hasText(text)) {
            return List.of();
        }
        String textWithoutUrls = URL_PATTERN.matcher(text).replaceAll(" ");
        Matcher matcher = COMPETITOR_SEGMENT_PATTERN.matcher(textWithoutUrls);
        while (matcher.find()) {
            splitCompetitorSegment(matcher.group(1)).stream()
                    .map(this::cleanCompetitorName)
                    .filter(StringUtils::hasText)
                    .filter(this::isLikelyCompetitorName)
                    .forEach(competitors::add);
        }
        return new ArrayList<>(competitors);
    }

    private List<String> splitCompetitorSegment(String segment) {
        if (!StringUtils.hasText(segment)) {
            return List.of();
        }
        String normalized = segment
                .replaceAll("\\s+(?i:vs\\.?|versus)\\s+", "、")
                .replaceAll("\\s*(和|与|及|以及|、|/|,|，)\\s*", "、");
        return List.of(normalized.split("、"));
    }

    private String cleanCompetitorName(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String cleaned = value.trim()
                .replaceFirst("^(一下|下|一下子)", "")
                .replaceFirst("(在|重点|主要|用于|输出|围绕|关于|看|比较|对比).*$", "")
                .replaceFirst("(这个|这款|该|的).*$", "")
                .trim();
        if (cleaned.matches("[A-Za-z0-9_.-]+\\s+.*[\\u4e00-\\u9fa5].*")) {
            cleaned = cleaned.substring(0, cleaned.indexOf(' ')).trim();
        }
        return cleaned;
    }

    private boolean isLikelyCompetitorName(String value) {
        String trimmed = value.trim();
        if (NON_COMPETITOR_TERMS.contains(trimmed)) {
            return false;
        }
        return NON_COMPETITOR_TERMS.stream().noneMatch(trimmed::endsWith);
    }

    private List<String> normalizeSourceUrls(CreateAnalysisRunRequest request) {
        Set<String> urls = new LinkedHashSet<>(request.getSourceUrls());
        Matcher matcher = URL_PATTERN.matcher(request.getPrompt());
        while (matcher.find()) {
            urls.add(matcher.group());
        }
        return urls.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .toList();
    }
}
