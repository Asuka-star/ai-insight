package com.aiinsight.service;

import com.aiinsight.dto.CreateAnalysisRunRequest;
import com.aiinsight.model.run.AnalysisRequirement;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
public class AnalysisRequestNormalizer {

    private static final List<String> DEFAULT_DIMENSIONS = List.of(
            "产品定位", "核心功能", "目标用户", "商业模式", "价格策略", "优劣势", "机会点", "风险提示"
    );

    private static final List<String> DEFAULT_SOURCES = List.of(
            "official_site", "pricing_page", "product_docs", "release_notes", "public_reviews"
    );

    public AnalysisRequirement normalize(CreateAnalysisRunRequest request) {
        String industry = StringUtils.hasText(request.getIndustry()) ? request.getIndustry() : inferIndustry(request.getPrompt());
        List<String> competitors = request.getCompetitors().isEmpty()
                ? inferCompetitors(request.getPrompt())
                : request.getCompetitors();
        List<String> dimensions = request.getDimensions().isEmpty() ? DEFAULT_DIMENSIONS : request.getDimensions();
        List<String> sourcePreferences = request.getSourcePreferences().isEmpty() ? DEFAULT_SOURCES : request.getSourcePreferences();
        return new AnalysisRequirement(request.getPrompt(), industry, competitors, dimensions, sourcePreferences);
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
        Set<String> competitors = new LinkedHashSet<>();
        List.of("Notion", "飞书文档", "钉钉文档", "语雀", "Confluence", "Airtable").forEach(candidate -> {
            if (prompt.toLowerCase().contains(candidate.toLowerCase())) {
                competitors.add(candidate);
            }
        });
        if (competitors.size() < 2) {
            competitors.add("竞品 A");
            competitors.add("竞品 B");
        }
        return new ArrayList<>(competitors);
    }
}
