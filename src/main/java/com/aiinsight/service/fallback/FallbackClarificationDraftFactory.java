package com.aiinsight.service.fallback;

import com.aiinsight.model.run.AnalysisRequirement;
import com.aiinsight.model.run.ClarificationDraft;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
public class FallbackClarificationDraftFactory {

    private static final String UNKNOWN_INDUSTRY = "待澄清行业";
    private static final Set<String> PLACEHOLDER_COMPETITORS = Set.of("竞品 A", "竞品 B");

    // 创建/更新阶段和 Clarifier fallback 共用这份规则草稿，保证无 LLM 时问题口径一致。
    public ClarificationDraft build(AnalysisRequirement requirement) {
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

    private boolean hasMeaningfulIndustry(String value) {
        return StringUtils.hasText(value) && !UNKNOWN_INDUSTRY.equals(value);
    }

    private boolean hasPlaceholderCompetitors(List<String> competitors) {
        return competitors.stream().anyMatch(PLACEHOLDER_COMPETITORS::contains);
    }
}
