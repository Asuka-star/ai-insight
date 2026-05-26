package com.aiinsight.service.fallback;

import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.model.schema.InterviewGuide;
import com.aiinsight.model.schema.Questionnaire;
import com.aiinsight.model.schema.ResearchPlan;
import com.aiinsight.model.schema.ResearchTask;
import com.aiinsight.model.schema.SurveyQuestion;
import com.aiinsight.service.SearchQueryPlanner;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class FallbackResearchPlanFactory {

    private final SearchQueryPlanner searchQueryPlanner = new SearchQueryPlanner();

    public ResearchPlan build(AnalysisRun run) {
        ResearchPlan plan = new ResearchPlan();
        plan.setObjective("围绕 %s 的竞品分析，补齐公开资料、用户评价、问卷和访谈证据。"
                .formatted(run.getRequirement().getOriginalPrompt()));
        plan.setEvidenceGaps(new ArrayList<>(run.getResearchPackage().getMissingEvidenceTypes()));
        plan.setSearchQueries(buildPlannedSearchQueries(run));
        plan.setPublicSourceTasks(buildPublicSourceTasks(run));
        plan.setQuestionnaire(buildQuestionnaire(run));
        plan.setInterviewGuide(buildInterviewGuide(run));
        return plan;
    }

    private List<String> buildPlannedSearchQueries(AnalysisRun run) {
        return searchQueryPlanner.plan(run, false);
    }

    private List<ResearchTask> buildPublicSourceTasks(AnalysisRun run) {
        List<ResearchTask> tasks = new ArrayList<>();
        for (String competitor : run.getRequirement().getCompetitors()) {
            tasks.add(new ResearchTask(
                    "public_web",
                    competitor + " 官网 / 产品资料",
                    "验证产品定位、核心能力、目标用户和关键场景。",
                    "prepared"
            ));
            tasks.add(new ResearchTask(
                    "pricing_page",
                    competitor + " 价格页",
                    "验证免费版、团队版、企业版和商业模式线索。",
                    hasEvidenceType(run, "pricing") ? "covered" : "needs_collection"
            ));
            tasks.add(new ResearchTask(
                    "public_review",
                    competitor + " 公开评价 / 社区反馈",
                    "补充真实用户的上手成本、满意度、采购顾虑和替换意愿证据。",
                    hasEvidenceType(run, "feedback") || hasEvidenceType(run, "review") ? "covered" : "needs_collection"
            ));
        }
        return tasks;
    }

    private Questionnaire buildQuestionnaire(AnalysisRun run) {
        Questionnaire questionnaire = new Questionnaire();
        String industry = researchDomain(run);
        String competitors = competitorLabel(run);
        questionnaire.setTitle(industry + "竞品使用体验问卷");
        questionnaire.setTargetRespondents("使用过或评估过 " + competitors
                + " 的目标用户、采购/决策参与者、业务负责人或 IT/运营支持角色");
        questionnaire.setRecommendedSampleSize("15-30 份用于课程/比赛原型验证，生产环境建议按用户分层扩大样本。");
        questionnaire.getQuestions().add(new SurveyQuestion(
                "使用场景",
                "你主要在什么场景使用或评估 " + competitors + "？",
                scenarioOptions(run)
        ));
        for (String dimension : prioritizedDimensions(run)) {
            questionnaire.getQuestions().add(new SurveyQuestion(
                    dimension,
                    "针对“" + dimension + "”，你对 " + competitors + " 的体验或评价如何？",
                    dimensionOptions(dimension)
            ));
        }
        questionnaire.getQuestions().add(new SurveyQuestion(
                "满意度",
                "整体来看，你对这些竞品满足当前业务需求的满意度如何？",
                List.of("1 非常不满意", "2", "3 一般", "4", "5 非常满意")
        ));
        questionnaire.getQuestions().add(new SurveyQuestion(
                "购买顾虑",
                "团队采购、续费或替换这类产品时最大的顾虑是什么？",
                buyingConcernOptions(run)
        ));
        return questionnaire;
    }

    private InterviewGuide buildInterviewGuide(AnalysisRun run) {
        InterviewGuide guide = new InterviewGuide();
        String industry = researchDomain(run);
        String competitors = competitorLabel(run);
        guide.setTitle(industry + "竞品用户访谈提纲");
        guide.setTargetRoles(List.of("核心使用者", "业务负责人/采购决策参与者", "管理员或运营支持角色"));
        List<String> questions = new ArrayList<>();
        questions.add("请描述最近一次使用或评估 " + competitors + " 的具体场景，任务目标是什么？");
        questions.add("哪些功能或体验真正影响了你的效率、风险控制或业务结果？");
        for (String dimension : prioritizedDimensions(run)) {
            questions.add("围绕“" + dimension + "”，你觉得各竞品最明显的差异是什么？能否举例？");
        }
        questions.add("如果让你切换到另一个竞品，最主要的阻力或触发条件是什么？");
        guide.setQuestions(questions);
        guide.setProbingQuestions(List.of(
                "能否举一个具体页面、功能、流程或业务事件作为例子？",
                "这个问题发生频率如何，对团队效率或风险影响多大？",
                "你愿意为哪些能力、服务或治理保障付费？"
        ));
        return guide;
    }

    private boolean needsFieldResearch(AnalysisRun run) {
        return mentionsAny(run.getRequirement().getDimensions(), "用户", "评价", "痛点", "画像", "满意度", "调研", "访谈", "问卷")
                || mentionsAny(run.getRequirement().getSourcePreferences(), "public_reviews", "review", "survey", "interview", "访谈", "问卷");
    }

    private boolean hasEvidenceType(AnalysisRun run, String keyword) {
        return run.getEvidenceSources().stream().anyMatch(source ->
                containsIgnoreCase(source.getSourceType(), keyword)
                        || containsIgnoreCase(source.getTitle(), keyword)
                        || containsIgnoreCase(source.getComplianceNote(), keyword));
    }

    private String researchDomain(AnalysisRun run) {
        String industry = run.getRequirement().getIndustry();
        if (industry == null || industry.isBlank() || "待澄清行业".equals(industry)) {
            return "目标领域";
        }
        return industry;
    }

    private String competitorLabel(AnalysisRun run) {
        return run.getRequirement().getCompetitors().isEmpty()
                ? "相关竞品"
                : String.join("、", run.getRequirement().getCompetitors());
    }

    private List<String> prioritizedDimensions(AnalysisRun run) {
        List<String> dimensions = run.getRequirement().getDimensions().stream()
                .filter(dimension -> dimension != null && !dimension.isBlank())
                .distinct()
                .limit(4)
                .toList();
        if (dimensions.isEmpty()) {
            return List.of("核心功能", "价格策略", "用户体验");
        }
        return dimensions;
    }

    private List<String> scenarioOptions(AnalysisRun run) {
        if (mentionsAny(run.getRequirement().getIndustry(), "文档", "知识", "协作")) {
            return List.of("知识沉淀", "项目协作", "内容创作", "审批/流程协同", "跨部门共享", "其他");
        }
        if (mentionsAny(run.getRequirement().getIndustry(), "CRM", "销售", "客户")) {
            return List.of("线索管理", "客户跟进", "销售预测", "营销自动化", "客户服务", "其他");
        }
        if (mentionsAny(run.getRequirement().getIndustry(), "BI", "数据", "分析")) {
            return List.of("经营看板", "自助分析", "报表制作", "指标监控", "数据问答", "其他");
        }
        return List.of("日常核心任务", "团队协作", "管理决策", "客户/用户服务", "自动化提效", "其他");
    }

    private List<String> dimensionOptions(String dimension) {
        if (containsIgnoreCase(dimension, "价格") || containsIgnoreCase(dimension, "定价")
                || containsIgnoreCase(dimension, "商业模式")) {
            return List.of("价格清晰", "价格偏高", "套餐能力不匹配", "企业版门槛高", "愿意付费", "需要更多信息");
        }
        if (containsIgnoreCase(dimension, "用户") || containsIgnoreCase(dimension, "评价")
                || containsIgnoreCase(dimension, "体验")) {
            return List.of("非常满意", "基本满意", "体验一般", "学习成本高", "稳定性不足", "需要进一步访谈");
        }
        if (containsIgnoreCase(dimension, "AI") || containsIgnoreCase(dimension, "智能")) {
            return List.of("明显提效", "结果可信", "需要引用来源", "权限/隐私顾虑", "效果不稳定", "暂未使用");
        }
        if (containsIgnoreCase(dimension, "权限") || containsIgnoreCase(dimension, "安全")
                || containsIgnoreCase(dimension, "合规")) {
            return List.of("权限清晰", "审计完善", "治理复杂", "合规风险高", "需要管理员支持", "不确定");
        }
        return List.of("领先", "基本满足", "差异不明显", "存在短板", "证据不足", "需要补充说明");
    }

    private List<String> buyingConcernOptions(AnalysisRun run) {
        List<String> options = new ArrayList<>(List.of("价格", "迁移成本", "学习成本", "集成成本", "服务支持", "供应商稳定性"));
        if (mentionsAny(run.getRequirement().getDimensions(), "权限", "安全", "合规", "隐私")) {
            options.add("安全/合规风险");
        }
        if (mentionsAny(run.getRequirement().getDimensions(), "AI", "智能")) {
            options.add("AI 输出可信度");
        }
        return options.stream().distinct().toList();
    }

    private boolean mentionsAny(List<String> values, String... patterns) {
        return values.stream().anyMatch(value -> {
            for (String pattern : patterns) {
                if (containsIgnoreCase(value, pattern)) {
                    return true;
                }
            }
            return false;
        });
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
}
