package com.aiinsight.service.fallback;

import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.model.run.EvidenceSource;
import com.aiinsight.model.schema.CompetitorProfile;
import com.aiinsight.model.schema.FeatureNode;
import com.aiinsight.model.schema.FeatureTree;
import com.aiinsight.model.schema.InterviewInsight;
import com.aiinsight.model.schema.PricingModel;
import com.aiinsight.model.schema.PricingPlan;
import com.aiinsight.model.schema.UserPersona;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class FallbackExtractionFactory {

    public List<CompetitorProfile> buildProfiles(AnalysisRun run) {
        // 这里产出的是供 Agent 之间传递的结构化 Schema，不是最终展示文本。
        // 证据只分配给能匹配竞品名的来源，避免一个网页被硬塞给所有竞品。
        return run.getRequirement().getCompetitors().stream()
                .map(competitor -> toCompetitorProfile(competitor, sourcesFor(run, competitor), run))
                .toList();
    }

    public String buildMarkdown(AnalysisRun run) {
        return run.getEvidenceSources().stream()
                .map(this::toProfileMarkdown)
                .collect(Collectors.joining("\n\n"));
    }

    private CompetitorProfile toCompetitorProfile(String productName, List<EvidenceSource> sources, AnalysisRun run) {
        List<String> evidenceIds = sources.stream().map(EvidenceSource::getCitationKey).toList();
        List<InterviewInsight> interviewInsights = insightsFor(run, productName);
        List<String> interviewEvidenceIds = interviewInsights.stream()
                .map(InterviewInsight::getEvidenceId)
                .filter(id -> id != null && !id.isBlank())
                .toList();
        List<String> personaEvidenceIds = mergeDistinct(evidenceIds, interviewEvidenceIds);
        List<String> interviewRoles = interviewInsights.stream()
                .map(InterviewInsight::getIntervieweeRole)
                .filter(role -> role != null && !role.isBlank())
                .distinct()
                .toList();
        List<String> painPoints = interviewInsights.stream()
                .flatMap(insight -> insight.getPainPoints().stream())
                .distinct()
                .limit(4)
                .toList();
        List<String> buyingConcerns = interviewInsights.stream()
                .flatMap(insight -> insight.getBuyingConcerns().stream())
                .distinct()
                .limit(4)
                .toList();
        boolean pricingEvidencePresent = hasPricingEvidence(sources);
        String domain = researchDomain(run);
        CompetitorProfile profile = new CompetitorProfile();
        profile.setProductName(productName);
        profile.setCompanyName(productName);
        profile.setPositioning("%s 领域竞品，具体定位需结合证据继续验证。".formatted(domain));
        profile.setTargetUsers(interviewRoles.isEmpty()
                ? List.of(domain + "相关目标用户", "评估或决策参与者")
                : mergeDistinct(List.of(domain + "相关目标用户", "评估或决策参与者"), interviewRoles));
        profile.setStrengths(evidenceIds.isEmpty()
                ? List.of("优势待验证")
                : List.of("已采集到可核验证据，可支持初步画像"));
        profile.setWeaknesses(!painPoints.isEmpty()
                ? painPoints.stream().map(point -> "访谈显示：" + point).limit(3).toList()
                : pricingEvidencePresent
                ? List.of("采用成本、学习成本和差异化弱势仍需继续验证")
                : List.of("价格策略、用户评价和差异化弱势仍需补充证据"));
        profile.setEvidenceIds(evidenceIds);

        FeatureTree featureTree = new FeatureTree();
        featureTree.setProductName(productName);
        featureTree.setRoots(featureNodesFor(run, evidenceIds));
        profile.setFeatureTree(featureTree);

        PricingModel pricingModel = new PricingModel();
        pricingModel.setStrategySummary(pricingEvidencePresent
                ? "已补充价格页证据，可初步描述套餐策略，具体金额仍以原始页面为准。"
                : "当前采集资料不足，定价模型待补充价格页证据。");
        pricingModel.setHasFreePlan(pricingEvidencePresent);
        pricingModel.setPlans(createPricingPlans(pricingEvidencePresent, evidenceIds));
        pricingModel.setEvidenceIds(evidenceIds);
        profile.setPricingModel(pricingModel);

        UserPersona persona = new UserPersona();
        persona.setName(productName + " 典型使用或评估者");
        persona.setSegment(domain);
        persona.setCompanySize("需按目标场景继续确认");
        persona.setJobsToBeDone(jobsFor(run));
        persona.setPainPoints(painPoints.isEmpty()
                ? List.of("价值验证不足", "采用或集成成本待评估", "实际使用阻力待验证")
                : painPoints);
        persona.setBuyingConcerns(buyingConcerns.isEmpty()
                ? List.of("采用成本", "学习成本", "价格或商业条款")
                : buyingConcerns);
        persona.setEvidenceIds(personaEvidenceIds);
        profile.setPersonas(List.of(persona));
        return profile;
    }

    private String toProfileMarkdown(EvidenceSource source) {
        return """
                ### %s
                - 产品定位: 需结合原始资料继续验证 [%s]
                - 关键能力: 从用户指定维度和证据片段抽取，不套用固定行业模板 [%s]
                - 目标用户: 需结合行业、访谈和公开资料确认 [%s]
                - 可验证片段: %s
                """.formatted(
                source.getTitle(),
                source.getCitationKey(),
                source.getCitationKey(),
                source.getCitationKey(),
                source.getSnippet()
        );
    }

    private List<EvidenceSource> sourcesFor(AnalysisRun run, String competitor) {
        return run.getEvidenceSources().stream()
                .filter(source -> sourceMatchesCompetitor(source, competitor))
                .toList();
    }

    private boolean hasPricingEvidence(List<EvidenceSource> sources) {
        return sources.stream().anyMatch(source ->
                containsIgnoreCase(sourceText(source), "pricing")
                        || containsIgnoreCase(sourceText(source), "价格")
                        || containsIgnoreCase(sourceText(source), "定价"));
    }

    private List<InterviewInsight> insightsFor(AnalysisRun run, String productName) {
        if (run.getResearchPackage() == null || run.getResearchPackage().getInterviewInsights() == null) {
            return List.of();
        }
        // 访谈没有显式提及竞品时视为通用用户洞察；有竞品提及时只绑定到对应画像。
        return run.getResearchPackage().getInterviewInsights().stream()
                .filter(insight -> insight.getCompetitorMentions().isEmpty()
                        || insight.getCompetitorMentions().stream().anyMatch(value -> containsIgnoreCase(value, productName)))
                .toList();
    }

    private List<String> mergeDistinct(List<String> first, List<String> second) {
        Set<String> values = new LinkedHashSet<>();
        values.addAll(first == null ? List.of() : first);
        values.addAll(second == null ? List.of() : second);
        return new ArrayList<>(values);
    }

    private List<PricingPlan> createPricingPlans(boolean pricingEvidencePresent, List<String> evidenceIds) {
        return List.of();
    }

    private boolean sourceMatchesCompetitor(EvidenceSource source, String competitor) {
        if (competitor == null || competitor.isBlank()) {
            return false;
        }
        return containsIgnoreCase(sourceText(source), competitor);
    }

    private String sourceText(EvidenceSource source) {
        return "%s %s %s %s %s".formatted(
                nullToEmpty(source.getTitle()),
                nullToEmpty(source.getUrl()),
                nullToEmpty(source.getSnippet()),
                nullToEmpty(source.getRawText()),
                nullToEmpty(source.getComplianceNote())
        );
    }

    private List<FeatureNode> featureNodesFor(AnalysisRun run, List<String> evidenceIds) {
        List<String> dimensions = run.getRequirement().getDimensions().stream()
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .limit(5)
                .toList();
        if (dimensions.isEmpty()) {
            dimensions = List.of("核心能力", "价格策略", "用户反馈");
        }
        return dimensions.stream()
                .map(dimension -> new FeatureNode(
                        dimension,
                        "围绕该维度的能力、差异和证据覆盖情况需结合来源继续评估。",
                        evidenceIds
                ))
                .toList();
    }

    private List<String> jobsFor(AnalysisRun run) {
        List<String> dimensions = run.getRequirement().getDimensions().stream()
                .filter(value -> value != null && !value.isBlank())
                .limit(3)
                .map(value -> "评估" + value + "表现")
                .toList();
        if (!dimensions.isEmpty()) {
            return dimensions;
        }
        return List.of("比较竞品能力", "评估采购或产品规划价值", "识别落地风险");
    }

    private String researchDomain(AnalysisRun run) {
        String industry = run.getRequirement().getIndustry();
        if (industry == null || industry.isBlank() || "待澄清行业".equals(industry)) {
            return "目标业务";
        }
        return industry;
    }

    private boolean containsIgnoreCase(String text, String pattern) {
        return text != null && pattern != null && text.toLowerCase(Locale.ROOT).contains(pattern.toLowerCase(Locale.ROOT));
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
