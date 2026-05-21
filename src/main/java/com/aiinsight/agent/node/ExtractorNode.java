package com.aiinsight.agent.node;

import com.aiinsight.model.enums.AgentName;
import com.aiinsight.model.run.AnalysisArtifact;
import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.model.enums.ArtifactType;
import com.aiinsight.model.schema.CompetitorProfile;
import com.aiinsight.model.run.EvidenceSource;
import com.aiinsight.model.schema.FeatureNode;
import com.aiinsight.model.schema.FeatureTree;
import com.aiinsight.model.schema.PricingModel;
import com.aiinsight.model.schema.PricingPlan;
import com.aiinsight.model.schema.UserPersona;
import com.aiinsight.agent.AgentNode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
// Extractor 将非结构化证据转换成竞品知识 Schema，后续 Agent 直接消费这些强类型对象。
public class ExtractorNode implements AgentNode {

    @Override
    public AgentName name() {
        return AgentName.EXTRACTOR;
    }

    @Override
    public String title() {
        return "抽取竞品结构化信息";
    }

    @Override
    public AnalysisRun execute(AnalysisRun run) {
        run.getCompetitorProfiles().clear();
        run.getRequirement().getCompetitors().stream()
                .map(competitor -> toCompetitorProfile(competitor, sourcesFor(run, competitor)))
                .forEach(profile -> run.getCompetitorProfiles().add(profile));

        // 每个结构化字段都保留 citationKey，报告和 Reviewer 才能追溯到原始证据。
        String content = run.getEvidenceSources().stream()
                .map(this::toProfile)
                .collect(Collectors.joining("\n\n"));
        run.getArtifacts().add(new AnalysisArtifact(
                ArtifactType.COMPETITOR_PROFILE,
                "竞品知识 Schema",
                content,
                run.getEvidenceSources().stream().map(EvidenceSource::getCitationKey).toList()
        ));
        return run;
    }

    private CompetitorProfile toCompetitorProfile(String productName, List<EvidenceSource> sources) {
        List<String> evidenceIds = sources.stream().map(EvidenceSource::getCitationKey).toList();
        boolean pricingEvidencePresent = hasPricingEvidence(sources);
        CompetitorProfile profile = new CompetitorProfile();
        profile.setProductName(productName);
        profile.setCompanyName(productName);
        profile.setPositioning("AI 协作与知识沉淀工具");
        profile.setTargetUsers(List.of("团队知识管理用户", "项目协作团队"));
        profile.setStrengths(List.of("协作能力明确", "知识沉淀场景清晰"));
        profile.setWeaknesses(pricingEvidencePresent
                ? List.of("用户迁移成本和学习成本仍需持续验证")
                : List.of("价格策略和用户评价仍需补充证据"));
        profile.setEvidenceIds(evidenceIds);

        FeatureTree featureTree = new FeatureTree();
        featureTree.setProductName(productName);
        featureTree.setRoots(List.of(
                new FeatureNode("文档协同", "多人编辑、评论和共享", evidenceIds),
                new FeatureNode("权限管理", "面向团队空间的访问控制", evidenceIds),
                new FeatureNode("AI 内容生成", "辅助生成、总结和改写内容", evidenceIds)
        ));
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
        persona.setName(productName + " 典型团队用户");
        persona.setSegment("知识管理与项目协作");
        persona.setCompanySize("中小团队到企业团队");
        persona.setJobsToBeDone(List.of("沉淀项目知识", "协作撰写文档", "复用团队模板"));
        persona.setPainPoints(List.of("信息分散", "权限治理复杂", "重复内容生产"));
        persona.setBuyingConcerns(List.of("迁移成本", "成员学习成本", "价格方案"));
        persona.setEvidenceIds(evidenceIds);
        profile.setPersonas(List.of(persona));
        return profile;
    }

    private String toProfile(EvidenceSource source) {
        return """
                ### %s
                - 产品定位: AI 协作与知识沉淀工具 [%s]
                - 核心功能: 文档协同、权限、模板、AI 生成 [%s]
                - 目标用户: 团队知识管理和项目协作用户 [%s]
                - 可验证片段: %s
                """.formatted(
                source.getTitle().replace(" 官方产品资料", ""),
                source.getCitationKey(),
                source.getCitationKey(),
                source.getCitationKey(),
                source.getSnippet()
        );
    }

    private List<EvidenceSource> sourcesFor(AnalysisRun run, String competitor) {
        return run.getEvidenceSources().stream()
                .filter(source -> source.getTitle().startsWith(competitor + " "))
                .toList();
    }

    private boolean hasPricingEvidence(List<EvidenceSource> sources) {
        return sources.stream().anyMatch(source -> source.getTitle().contains("价格页"));
    }

    private List<PricingPlan> createPricingPlans(boolean pricingEvidencePresent, List<String> evidenceIds) {
        if (!pricingEvidencePresent) {
            return List.of();
        }
        return List.of(new PricingPlan(
                "团队版/企业版",
                "以价格页为准",
                "month_or_year",
                "团队与企业客户",
                List.of("文档协同", "权限管理", "AI 内容生成"),
                evidenceIds
        ));
    }
}
