package com.aiinsight.service.fallback;

import com.aiinsight.model.run.AnalysisRequirement;
import com.aiinsight.model.run.ClarificationDraft;
import com.aiinsight.model.run.ClarificationItem;
import com.aiinsight.model.run.ClarificationOption;
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
        draft.getClarificationItems().addAll(ruleItems(requirement));
        return draft;
    }

    private List<ClarificationItem> ruleItems(AnalysisRequirement requirement) {
        List<ClarificationItem> items = new ArrayList<>();
        if (hasPlaceholderCompetitors(requirement.getCompetitors()) || requirement.getCompetitors().size() < 3) {
            List<String> currentCompetitors = requirement.getCompetitors().stream()
                    .filter(StringUtils::hasText)
                    .filter(competitor -> !PLACEHOLDER_COMPETITORS.contains(competitor))
                    .toList();
            items.add(new ClarificationItem(
                    "competitors",
                    "是否需要补充或修正竞品列表？",
                    "竞品数量过少时，后续矩阵和 SWOT 的对比价值会下降。",
                    false,
                    List.of(
                            new ClarificationOption("保留当前竞品", "适合已经明确对比对象的场景。", currentCompetitors, currentCompetitors.size() >= 2),
                            new ClarificationOption("手动补充竞品", "请在输入框中补充具体竞品名称，系统不会自动编造对照对象。", currentCompetitors, currentCompetitors.size() < 2)
                    )
            ));
        }
        if (requirement.getSourceUrls().isEmpty()) {
            items.add(new ClarificationItem(
                    "sourceUrls",
                    "是否现在补充官方页面或资料 URL？",
                    "用户提供的 URL 会被优先采集，通常比搜索结果更稳定。",
                    false,
                    List.of(
                            new ClarificationOption("稍后补充 URL", "先让系统搜索公开资料，后续也可以追加证据。", List.of(), true)
                    )
            ));
        }
        if (!StringUtils.hasText(requirement.getOutputGoal())) {
            items.add(new ClarificationItem(
                    "outputGoal",
                    "这份报告主要用于什么决策？",
                    "报告用途会影响 Writer 的表达侧重点和 Analyst 的建议类型。",
                    true,
                    List.of(
                            new ClarificationOption("产品规划", "聚焦功能借鉴、差异化机会和版本路线。", List.of("产品规划与版本立项参考"), true),
                            new ClarificationOption("采购选型", "聚焦定价、安全、团队协作和落地风险。", List.of("企业采购与工具选型参考"), false),
                            new ClarificationOption("汇报材料", "聚焦结论摘要、市场格局和可解释证据。", List.of("向上汇报与战略讨论参考"), false)
                    )
            ));
        }
        if (!hasMeaningfulIndustry(requirement.getIndustry())) {
            items.add(new ClarificationItem(
                    "industry",
                    "分析所属行业或业务场景是否需要进一步明确？",
                    "行业场景会影响搜索关键词、竞品选择和报告术语。",
                    true,
                    List.of(
                            new ClarificationOption("手动填写行业", "请在行业方向输入框中补充你的真实业务场景。", List.of(), true)
                    )
            ));
        }
        return items;
    }

    private List<String> ruleQuestions(AnalysisRequirement requirement) {
        List<String> questions = new ArrayList<>();
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
