package com.aiinsight.agent.node;

import com.aiinsight.agent.AgentNode;
import com.aiinsight.llm.ChatMessage;
import com.aiinsight.llm.ChatOptions;
import com.aiinsight.llm.ChatRequest;
import com.aiinsight.llm.LlmClient;
import com.aiinsight.model.enums.AgentName;
import com.aiinsight.model.enums.ArtifactType;
import com.aiinsight.model.run.AnalysisArtifact;
import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.model.run.EvidenceSource;
import com.aiinsight.model.schema.CompetitorProfile;
import com.aiinsight.model.schema.FeatureNode;
import com.aiinsight.model.schema.FeatureTree;
import com.aiinsight.model.schema.InterviewInsight;
import com.aiinsight.model.schema.PricingModel;
import com.aiinsight.model.schema.PricingPlan;
import com.aiinsight.model.schema.UserPersona;
import com.aiinsight.observability.AgentTraceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class ExtractorNode implements AgentNode {

    private final LlmClient llmClient;

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
                .map(competitor -> toCompetitorProfile(competitor, sourcesFor(run, competitor), run))
                .forEach(profile -> run.getCompetitorProfiles().add(profile));

        String fallbackContent = run.getEvidenceSources().stream()
                .map(this::toProfileMarkdown)
                .collect(Collectors.joining("\n\n"));
        String content;
        if (llmClient.isAvailable()) {
            content = extractWithLlm(run, fallbackContent);
        } else {
            content = fallbackContent;
            AgentTraceContext.recordFallback("deterministic-extractor-fallback", content);
        }
        run.addArtifact(new AnalysisArtifact(
                ArtifactType.COMPETITOR_PROFILE,
                "竞品知识 Schema",
                content,
                run.getEvidenceSources().stream().map(EvidenceSource::getCitationKey).toList()
        ));
        return run;
    }

    private String extractWithLlm(AnalysisRun run, String deterministicSchema) {
        String prompt = """
                你是竞品分析工作流中的结构化抽取 Agent。请把证据片段整理成简洁的中文 Markdown Schema 视图。
                输出约束：
                1. 除产品名、专有名词、枚举值、URL 和 [S1] 这类引用编号外，全部使用中文。
                2. 必须保留原始产品名和证据编号。
                3. 不要编造价格、套餐、客户案例、营收或任何证据中没有的信息。
                4. 不确定字段请标注“待验证”。
                5. 输出应覆盖产品定位、功能树、定价模型、用户画像、优势、弱势和证据编号。

                竞品列表：
                %s

                证据片段：
                %s

                确定性 Schema 草稿：
                %s
                """.formatted(
                run.getRequirement().getCompetitors(),
                run.getEvidenceSources().stream()
                        .map(source -> "[%s] %s: %s".formatted(source.getCitationKey(), source.getTitle(), source.getSnippet()))
                        .collect(Collectors.joining("\n")),
                deterministicSchema
        );
        return llmClient.complete(new ChatRequest(
                List.of(
                        ChatMessage.system("你负责从证据中抽取竞品知识 Schema。请保留证据编号，并使用中文输出。"),
                        ChatMessage.user(prompt)
                ),
                ChatOptions.deterministic()
        ));
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
        CompetitorProfile profile = new CompetitorProfile();
        profile.setProductName(productName);
        profile.setCompanyName(productName);
        profile.setPositioning("AI 协作与知识沉淀工具");
        profile.setTargetUsers(interviewRoles.isEmpty()
                ? List.of("团队知识管理用户", "项目协作团队")
                : mergeDistinct(List.of("团队知识管理用户", "项目协作团队"), interviewRoles));
        profile.setStrengths(List.of("协作能力明确", "知识沉淀场景清晰"));
        profile.setWeaknesses(!painPoints.isEmpty()
                ? painPoints.stream().map(point -> "访谈显示：" + point).limit(3).toList()
                : pricingEvidencePresent
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
        persona.setPainPoints(painPoints.isEmpty()
                ? List.of("信息分散", "权限治理复杂", "重复内容生产")
                : painPoints);
        persona.setBuyingConcerns(buyingConcerns.isEmpty()
                ? List.of("迁移成本", "成员学习成本", "价格方案")
                : buyingConcerns);
        persona.setEvidenceIds(personaEvidenceIds);
        profile.setPersonas(List.of(persona));
        return profile;
    }

    private String toProfileMarkdown(EvidenceSource source) {
        return """
                ### %s
                - 产品定位: AI 协作与知识沉淀工具 [%s]
                - 核心功能: 文档协同、权限、模板、AI 生成 [%s]
                - 目标用户: 团队知识管理和项目协作用户 [%s]
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
        List<EvidenceSource> matched = run.getEvidenceSources().stream()
                .filter(source -> source.getTitle() != null && source.getTitle().startsWith(competitor + " "))
                .toList();
        if (!matched.isEmpty()) {
            return matched;
        }
        return run.getEvidenceSources();
    }

    private boolean hasPricingEvidence(List<EvidenceSource> sources) {
        return sources.stream().anyMatch(source ->
                containsIgnoreCase(source.getTitle(), "pricing")
                        || containsIgnoreCase(source.getTitle(), "价格")
                        || containsIgnoreCase(source.getSourceType(), "pricing"));
    }

    private boolean containsIgnoreCase(String text, String pattern) {
        return text != null && text.toLowerCase().contains(pattern.toLowerCase());
    }

    private List<InterviewInsight> insightsFor(AnalysisRun run, String productName) {
        if (run.getResearchPackage() == null || run.getResearchPackage().getInterviewInsights() == null) {
            return List.of();
        }
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
        if (!pricingEvidencePresent) {
            return List.of();
        }
        return List.of(new PricingPlan(
                "团队版 / 企业版",
                "以价格页为准",
                "month_or_year",
                "团队与企业客户",
                List.of("文档协同", "权限管理", "AI 内容生成"),
                evidenceIds
        ));
    }
}
