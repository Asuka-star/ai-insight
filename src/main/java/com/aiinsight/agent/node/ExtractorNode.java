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
import com.aiinsight.model.run.EvidenceChunk;
import com.aiinsight.model.run.EvidenceSource;
import com.aiinsight.model.schema.CompetitorProfile;
import com.aiinsight.model.schema.FeatureNode;
import com.aiinsight.model.schema.FeatureTree;
import com.aiinsight.model.schema.PricingModel;
import com.aiinsight.model.schema.PricingPlan;
import com.aiinsight.model.schema.UserPersona;
import com.aiinsight.observability.AgentTraceContext;
import com.aiinsight.service.EvidenceRetrievalService;
import com.aiinsight.service.fallback.FallbackExtractionFactory;
import com.aiinsight.util.JsonResponseExtractor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@Slf4j
public class ExtractorNode implements AgentNode {

    private final LlmClient llmClient;
    private final FallbackExtractionFactory fallbackExtractionFactory;
    private final EvidenceRetrievalService evidenceRetrievalService;
    private final ObjectMapper objectMapper;

    public ExtractorNode(LlmClient llmClient, FallbackExtractionFactory fallbackExtractionFactory) {
        this(llmClient, fallbackExtractionFactory, new EvidenceRetrievalService(), new ObjectMapper());
    }

    @Autowired
    public ExtractorNode(LlmClient llmClient,
                         FallbackExtractionFactory fallbackExtractionFactory,
                         EvidenceRetrievalService evidenceRetrievalService,
                         ObjectMapper objectMapper) {
        this.llmClient = llmClient;
        this.fallbackExtractionFactory = fallbackExtractionFactory;
        this.evidenceRetrievalService = evidenceRetrievalService;
        this.objectMapper = objectMapper;
    }

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
        if (llmClient.isAvailable()) {
            try {
                List<CompetitorProfile> profiles = extractProfilesWithLlm(run);
                run.getCompetitorProfiles().clear();
                run.getCompetitorProfiles().addAll(profiles);
                run.addArtifact(new AnalysisArtifact(
                        ArtifactType.COMPETITOR_PROFILE,
                        "竞品知识 Schema",
                        profilesMarkdown(profiles),
                        profileCitationKeys(profiles)
                ));
                return run;
            } catch (RuntimeException ex) {
                log.warn("Extractor fallback activated: runId={}, reason=llm_exception, exceptionType={}, message={}, competitors={}, evidenceSources={}",
                        run.getId(),
                        ex.getClass().getName(),
                        ex.getMessage(),
                        run.getRequirement().getCompetitors(),
                        run.getEvidenceSources().size());
                run.getRecommendedActions().add("LLM Schema 抽取失败，已使用规则 Schema 兜底：" + ex.getMessage());
                return fallback(run);
            }
        }
        log.warn("Extractor fallback activated: runId={}, reason=llm_unavailable, competitors={}, evidenceSources={}",
                run.getId(),
                run.getRequirement().getCompetitors(),
                run.getEvidenceSources().size());
        return fallback(run);
    }

    private AnalysisRun fallback(AnalysisRun run) {
        run.getCompetitorProfiles().clear();
        run.getCompetitorProfiles().addAll(fallbackExtractionFactory.buildProfiles(run));
        String content = fallbackExtractionFactory.buildMarkdown(run);
        AgentTraceContext.recordFallback("deterministic-extractor-fallback", content);
        run.addArtifact(new AnalysisArtifact(
                ArtifactType.COMPETITOR_PROFILE,
                "竞品知识 Schema",
                content,
                run.getEvidenceSources().stream().map(EvidenceSource::getCitationKey).toList()
        ));
        return run;
    }

    private List<CompetitorProfile> extractProfilesWithLlm(AnalysisRun run) {
        String prompt = """
                你是竞品分析工作流中的结构化抽取 Agent。请只从证据中抽取事实画像，不要做战略分析。
                输出约束：
                1. 只输出可解析 JSON，不要 Markdown，不要代码块。
                2. JSON 格式为 {"profiles":[...]}，每个竞品一个 profile。
                3. 除产品名、专有名词、枚举值、URL 和 S1 这类证据编号外，字段内容使用中文。
                4. 不要编造价格、套餐、客户案例、营收或任何证据中没有的信息。
                5. 不确定字段请写“待验证”，不要用营销话术补空。
                6. features、pricing、personas、strengths、weaknesses 都必须绑定 evidenceIds；只能使用证据片段里出现过的 S 编号。
                7. Extractor 只抽事实，不要写“建议”“机会”“风险”“应该”等分析性结论。
                8. 证据包可能包含 S1-C2 这样的 chunkKey，但 JSON evidenceIds 只能使用 S1 这样的来源编号。

                profile 字段：
                {
                  "productName": "原始竞品名",
                  "companyName": "公司名或待验证",
                  "positioning": "证据中的产品定位，不超过60字",
                  "targetUsers": ["证据中的目标用户或待验证"],
                  "features": [{"name":"功能名","description":"证据事实，不超过60字","evidenceIds":["S1"]}],
                  "pricing": {
                    "strategySummary": "定价事实或待验证",
                    "hasFreePlan": true/false,
                    "plans": [{"name":"套餐名","priceText":"价格文本或待验证","billingCycle":"monthly|yearly|usage_based|unknown","targetSegment":"目标客群或待验证","includedFeatures":["能力"],"evidenceIds":["S1"]}],
                    "evidenceIds": ["S1"]
                  },
                  "personas": [{"name":"画像名","segment":"细分人群","companySize":"规模或待验证","jobsToBeDone":["任务"],"painPoints":["痛点"],"buyingConcerns":["采购顾虑"],"evidenceIds":["S1"]}],
                  "strengths": ["证据明确支持的优势事实"],
                  "weaknesses": ["证据明确支持的限制或待验证"],
                  "evidenceIds": ["S1"]
                }

                竞品列表：
                %s

                证据片段索引：
                %s
                """.formatted(
                run.getRequirement().getCompetitors(),
                evidenceBlock(run)
        );
        String raw = llmClient.complete(new ChatRequest(
                List.of(
                        ChatMessage.system("你负责从证据中抽取竞品知识 Schema。必须输出 JSON，并保留证据编号。"),
                        ChatMessage.user(prompt)
                ),
                ChatOptions.extractor()
        ));
        List<CompetitorProfile> fallbackProfiles = fallbackExtractionFactory.buildProfiles(run);
        List<CompetitorProfile> llmProfiles = parseProfiles(raw, run, fallbackProfiles);
        if (llmProfiles.isEmpty()) {
            throw new IllegalStateException("模型未返回可用 profiles");
        }
        return llmProfiles;
    }

    private List<CompetitorProfile> parseProfiles(String raw, AnalysisRun run, List<CompetitorProfile> fallbackProfiles) {
        if (!StringUtils.hasText(raw)) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(extractJson(raw));
            JsonNode profilesNode = root.has("profiles") ? root.get("profiles") : root;
            List<ProfileDraft> drafts = profileDrafts(profilesNode);
            Map<String, ProfileDraft> draftByName = (drafts == null ? List.<ProfileDraft>of() : drafts).stream()
                    .filter(draft -> StringUtils.hasText(draft.productName))
                    .collect(Collectors.toMap(
                            draft -> normalizeName(draft.productName),
                            draft -> draft,
                            (first, ignored) -> first
                    ));
            return run.getRequirement().getCompetitors().stream()
                    .map(competitor -> {
                        CompetitorProfile fallback = fallbackFor(fallbackProfiles, competitor);
                        ProfileDraft draft = draftByName.get(normalizeName(competitor));
                        return draft == null ? fallback : toProfile(draft, fallback, run);
                    })
                    .toList();
        } catch (IllegalArgumentException | JsonProcessingException ex) {
            throw new IllegalStateException("无法解析 Extractor JSON", ex);
        }
    }

    private List<ProfileDraft> profileDrafts(JsonNode profilesNode) {
        if (profilesNode == null || profilesNode.isNull()) {
            return List.of();
        }
        if (profilesNode.isObject()) {
            Map<String, ProfileDraft> draftByKey = objectMapper.convertValue(profilesNode, new TypeReference<>() {
            });
            return draftByKey.entrySet().stream()
                    .map(entry -> {
                        ProfileDraft draft = entry.getValue();
                        if (draft != null && !StringUtils.hasText(draft.productName)) {
                            draft.productName = entry.getKey();
                        }
                        return draft;
                    })
                    .filter(draft -> draft != null)
                    .toList();
        }
        return objectMapper.convertValue(profilesNode, new TypeReference<>() {
        });
    }

    private CompetitorProfile toProfile(ProfileDraft draft, CompetitorProfile fallback, AnalysisRun run) {
        CompetitorProfile profile = new CompetitorProfile();
        profile.setProductName(textOrDefault(draft.productName, fallback.getProductName()));
        profile.setCompanyName(textOrDefault(draft.companyName, fallback.getCompanyName()));
        profile.setPositioning(textOrDefault(draft.positioning, fallback.getPositioning()));
        profile.setTargetUsers(nonEmptyStrings(draft.targetUsers, fallback.getTargetUsers()));
        profile.setStrengths(observedFactsOnly(draft.strengths, fallback.getStrengths(), run, profile.getProductName(), "strengths"));
        profile.setWeaknesses(observedFactsOnly(draft.weaknesses, fallback.getWeaknesses(), run, profile.getProductName(), "weaknesses"));
        profile.setEvidenceIds(knownEvidenceIds(run, draft.evidenceIds, fallback.getEvidenceIds()));
        profile.setFeatureTree(featureTree(profile.getProductName(), draft.features, fallback.getFeatureTree(), run));
        profile.setPricingModel(pricingModel(draft.pricing, fallback.getPricingModel(), run));
        profile.setPersonas(personas(draft.personas, fallback.getPersonas(), run));
        return profile;
    }

    private FeatureTree featureTree(String productName, List<FeatureDraft> drafts, FeatureTree fallback, AnalysisRun run) {
        FeatureTree tree = new FeatureTree();
        tree.setProductName(productName);
        List<FeatureNode> nodes = (drafts == null ? List.<FeatureDraft>of() : drafts).stream()
                .map(draft -> featureNode(draft, run))
                .filter(node -> node != null && StringUtils.hasText(node.getName()))
                .limit(8)
                .toList();
        tree.setRoots(nodes.isEmpty() ? fallback.getRoots() : nodes);
        return tree;
    }

    private FeatureNode featureNode(FeatureDraft draft, AnalysisRun run) {
        List<String> evidenceIds = knownEvidenceIds(run, draft.evidenceIds, List.of());
        if (evidenceIds.isEmpty()) {
            return null;
        }
        return new FeatureNode(
                textOrDefault(draft.name, "未命名功能"),
                textOrDefault(draft.description, "证据片段未提供明确描述。"),
                evidenceIds
        );
    }

    private PricingModel pricingModel(PricingDraft draft, PricingModel fallback, AnalysisRun run) {
        if (draft == null) {
            return fallback;
        }
        PricingModel model = new PricingModel();
        model.setStrategySummary(textOrDefault(draft.strategySummary, fallback.getStrategySummary()));
        model.setHasFreePlan(draft.hasFreePlan == null ? fallback.isHasFreePlan() : draft.hasFreePlan);
        model.setEvidenceIds(knownEvidenceIds(run, draft.evidenceIds, fallback.getEvidenceIds()));
        List<PricingPlan> plans = (draft.plans == null ? List.<PricingPlanDraft>of() : draft.plans).stream()
                .map(plan -> pricingPlan(plan, run))
                .filter(plan -> plan != null)
                .limit(6)
                .toList();
        model.setPlans(plans.isEmpty() ? fallback.getPlans() : plans);
        return model;
    }

    private PricingPlan pricingPlan(PricingPlanDraft draft, AnalysisRun run) {
        List<String> evidenceIds = knownEvidenceIds(run, draft.evidenceIds, List.of());
        if (evidenceIds.isEmpty()) {
            return null;
        }
        return new PricingPlan(
                textOrDefault(draft.name, "未命名套餐"),
                textOrDefault(draft.priceText, "待验证"),
                textOrDefault(draft.billingCycle, "unknown"),
                textOrDefault(draft.targetSegment, "待验证"),
                nonEmptyStrings(draft.includedFeatures, List.of("待验证")),
                evidenceIds
        );
    }

    private List<UserPersona> personas(List<PersonaDraft> drafts, List<UserPersona> fallback, AnalysisRun run) {
        List<UserPersona> personas = (drafts == null ? List.<PersonaDraft>of() : drafts).stream()
                .map(draft -> persona(draft, run))
                .filter(persona -> persona != null)
                .limit(4)
                .toList();
        return personas.isEmpty() ? fallback : personas;
    }

    private UserPersona persona(PersonaDraft draft, AnalysisRun run) {
        List<String> evidenceIds = knownEvidenceIds(run, draft.evidenceIds, List.of());
        if (evidenceIds.isEmpty()) {
            return null;
        }
        UserPersona persona = new UserPersona();
        persona.setName(textOrDefault(draft.name, "典型用户"));
        persona.setSegment(textOrDefault(draft.segment, "待验证"));
        persona.setCompanySize(textOrDefault(draft.companySize, "待验证"));
        persona.setJobsToBeDone(nonEmptyStrings(draft.jobsToBeDone, List.of("待验证")));
        persona.setPainPoints(nonEmptyStrings(draft.painPoints, List.of("待验证")));
        persona.setBuyingConcerns(nonEmptyStrings(draft.buyingConcerns, List.of("待验证")));
        persona.setEvidenceIds(evidenceIds);
        return persona;
    }

    private String evidenceBlock(AnalysisRun run) {
        if (!run.getEvidenceChunks().isEmpty()) {
            String pack = ragEvidencePack(run);
            AgentTraceContext.recordProcessSummary("Extractor RAG evidence pack selected:\n" + abbreviate(pack, 4000));
            return pack;
        }
        return run.getEvidenceSources().stream()
                .limit(24)
                .map(source -> """
                        [%s] title=%s | type=%s | quality=%s
                        snippet=%s
                        raw=%s
                        """.formatted(
                        source.getCitationKey(),
                        abbreviate(source.getTitle(), 100),
                        source.getSourceType(),
                        source.getSourceQuality(),
                        abbreviate(source.getSnippet(), 320),
                        abbreviate(source.getRawText(), 500)
                ))
                .collect(Collectors.joining("\n"));
    }

    private String ragEvidencePack(AnalysisRun run) {
        List<String> dimensions = extractionDimensions(run);
        StringBuilder pack = new StringBuilder();
        int pairCount = 0;
        for (String competitor : run.getRequirement().getCompetitors()) {
            for (String dimension : dimensions) {
                List<EvidenceChunk> chunks = evidenceRetrievalService.retrieve(
                        run,
                        competitor + " " + dimension,
                        competitor,
                        dimension,
                        3
                );
                if (chunks.isEmpty()) {
                    continue;
                }
                if (!pack.isEmpty()) {
                    pack.append("\n");
                }
                pack.append("Competitor: ").append(competitor).append("\n");
                pack.append("Dimension: ").append(dimension).append("\n");
                for (EvidenceChunk chunk : chunks) {
                    pack.append(formatChunk(chunk)).append("\n");
                }
                pairCount++;
                if (pairCount >= 30 || pack.length() > 24_000) {
                    return pack.toString();
                }
            }
        }
        if (!pack.isEmpty()) {
            return pack.toString();
        }
        return run.getEvidenceChunks().stream()
                .limit(24)
                .map(this::formatChunk)
                .collect(Collectors.joining("\n"));
    }

    private List<String> extractionDimensions(AnalysisRun run) {
        LinkedHashSet<String> dimensions = new LinkedHashSet<>();
        if (run.getRequirement().getDimensions() != null) {
            run.getRequirement().getDimensions().stream()
                    .filter(StringUtils::hasText)
                    .map(String::trim)
                    .limit(8)
                    .forEach(dimensions::add);
        }
        dimensions.add("product positioning and core features");
        dimensions.add("pricing and plans");
        dimensions.add("security permissions and enterprise controls");
        dimensions.add("target users and use cases");
        return new ArrayList<>(dimensions).stream()
                .limit(8)
                .toList();
    }

    private String formatChunk(EvidenceChunk chunk) {
        String sourceId = StringUtils.hasText(chunk.getSourceCitationKey())
                ? chunk.getSourceCitationKey()
                : chunk.getChunkKey();
        return """
                - [%s] source=[%s] title=%s | heading=%s | kind=%s | authority=%s | quality=%s | score=%.2f
                  text=%s
                """.formatted(
                chunk.getChunkKey(),
                sourceId,
                abbreviate(chunk.getTitle(), 100),
                abbreviate(String.join(" > ", chunk.getHeadingPath() == null ? List.of() : chunk.getHeadingPath()), 140),
                textOrDash(chunk.getContentKind()),
                textOrDash(chunk.getSourceAuthority()),
                textOrDash(chunk.getSourceQuality()),
                chunk.getScore(),
                abbreviate(chunk.getText(), 620)
        );
    }

    private List<String> knownEvidenceIds(AnalysisRun run, List<String> candidateIds, List<String> fallback) {
        Set<String> known = run.getEvidenceSources().stream()
                .map(EvidenceSource::getCitationKey)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<String> ids = (candidateIds == null ? List.<String>of() : candidateIds).stream()
                .filter(StringUtils::hasText)
                .map(this::normalizeEvidenceId)
                .filter(known::contains)
                .distinct()
                .toList();
        if (!ids.isEmpty()) {
            return ids;
        }
        return fallback == null ? List.of() : fallback.stream()
                .filter(StringUtils::hasText)
                .map(this::normalizeEvidenceId)
                .filter(known::contains)
                .distinct()
                .toList();
    }

    private String normalizeEvidenceId(String value) {
        String normalized = value.trim();
        if (normalized.startsWith("[") && normalized.endsWith("]") && normalized.length() > 2) {
            normalized = normalized.substring(1, normalized.length() - 1).trim();
        }
        return normalized;
    }

    private CompetitorProfile fallbackFor(List<CompetitorProfile> fallbackProfiles, String competitor) {
        return fallbackProfiles.stream()
                .filter(profile -> normalizeName(profile.getProductName()).equals(normalizeName(competitor)))
                .findFirst()
                .orElseGet(() -> {
                    CompetitorProfile profile = new CompetitorProfile();
                    profile.setProductName(competitor);
                    profile.setCompanyName(competitor);
                    profile.setPositioning("待验证");
                    return profile;
                });
    }

    private List<String> profileCitationKeys(List<CompetitorProfile> profiles) {
        Set<String> keys = new LinkedHashSet<>();
        for (CompetitorProfile profile : profiles) {
            keys.addAll(profile.getEvidenceIds());
            profile.getFeatureTree().getRoots().forEach(node -> keys.addAll(node.getEvidenceIds()));
            keys.addAll(profile.getPricingModel().getEvidenceIds());
            profile.getPricingModel().getPlans().forEach(plan -> keys.addAll(plan.getEvidenceIds()));
            profile.getPersonas().forEach(persona -> keys.addAll(persona.getEvidenceIds()));
        }
        return new ArrayList<>(keys);
    }

    private String profilesMarkdown(List<CompetitorProfile> profiles) {
        return profiles.stream()
                .map(profile -> """
                        ### %s
                        - 公司/产品: %s
                        - 产品定位: %s
                        - 目标用户: %s
                        - 关键能力:
                        %s
                        - 定价模型: %s
                        - 定价档位:
                        %s
                        - 优势: %s
                        - 弱势/限制: %s
                        - 证据: %s
                        """.formatted(
                        profile.getProductName(),
                        profile.getCompanyName(),
                        profile.getPositioning(),
                        String.join("、", profile.getTargetUsers()),
                        featureMarkdown(profile),
                        profile.getPricingModel().getStrategySummary(),
                        pricingPlansMarkdown(profile),
                        String.join("、", profile.getStrengths()),
                        String.join("、", profile.getWeaknesses()),
                        profile.getEvidenceIds()
                ))
                .collect(Collectors.joining("\n\n"));
    }

    private String featureMarkdown(CompetitorProfile profile) {
        if (profile.getFeatureTree().getRoots().isEmpty()) {
            return "  - 待验证";
        }
        return profile.getFeatureTree().getRoots().stream()
                .map(node -> "  - %s：%s %s".formatted(node.getName(), node.getDescription(), node.getEvidenceIds()))
                .collect(Collectors.joining("\n"));
    }

    private String pricingPlansMarkdown(CompetitorProfile profile) {
        if (profile.getPricingModel().getPlans().isEmpty()) {
            return "  - 待验证";
        }
        return profile.getPricingModel().getPlans().stream()
                .map(plan -> "  - %s：%s，周期=%s，客群=%s，能力=%s %s".formatted(
                        plan.getName(),
                        plan.getPriceText(),
                        plan.getBillingCycle(),
                        plan.getTargetSegment(),
                        plan.getIncludedFeatures(),
                        plan.getEvidenceIds()
                ))
                .collect(Collectors.joining("\n"));
    }

    private List<String> nonEmptyStrings(List<String> values, List<String> fallback) {
        List<String> cleaned = (values == null ? List.<String>of() : values).stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
        return cleaned.isEmpty() ? fallback : cleaned;
    }

    private List<String> observedFactsOnly(List<String> values, List<String> fallback, AnalysisRun run, String productName, String fieldName) {
        List<String> cleaned = (values == null ? List.<String>of() : values).stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .filter(value -> !looksLikeAnalysisJudgment(value))
                .distinct()
                .toList();
        if (cleaned.isEmpty() && values != null && values.stream().anyMatch(StringUtils::hasText)) {
            run.getRecommendedActions().add("Extractor filtered non-factual " + fieldName + " for " + productName);
        }
        return cleaned.isEmpty() ? fallback : cleaned;
    }

    private boolean looksLikeAnalysisJudgment(String value) {
        String normalized = normalizeName(value);
        if (containsAny(normalized,
                "风险管理", "风险控制", "风险仪表盘", "机会管理",
                "risk management", "risk control", "risk dashboard", "opportunity management")) {
            return false;
        }
        return containsAny(normalized,
                "建议", "应该", "机会", "风险", "威胁", "战略", "取舍", "推荐", "优先",
                "recommend", "should", "opportunity", "risk", "threat", "strategy", "priority");
    }

    private boolean containsAny(String text, String... patterns) {
        for (String pattern : patterns) {
            if (text.contains(pattern.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String textOrDefault(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private String textOrDash(String value) {
        return StringUtils.hasText(value) ? value.trim() : "-";
    }

    private String normalizeName(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String abbreviate(String value, int maxChars) {
        String normalized = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        return normalized.substring(0, maxChars) + "...";
    }

    private String extractJson(String raw) {
        return JsonResponseExtractor.extractJsonValue(raw);
    }

    private static class ProfileDraft {
        public String productName;
        public String companyName;
        public String positioning;
        public List<String> targetUsers = List.of();
        public List<FeatureDraft> features = List.of();
        public PricingDraft pricing;
        public List<PersonaDraft> personas = List.of();
        public List<String> strengths = List.of();
        public List<String> weaknesses = List.of();
        public List<String> evidenceIds = List.of();
    }

    private static class FeatureDraft {
        public String name;
        public String description;
        public List<String> evidenceIds = List.of();
    }

    private static class PricingDraft {
        public String strategySummary;
        public Boolean hasFreePlan;
        public List<PricingPlanDraft> plans = List.of();
        public List<String> evidenceIds = List.of();
    }

    private static class PricingPlanDraft {
        public String name;
        public String priceText;
        public String billingCycle;
        public String targetSegment;
        public List<String> includedFeatures = List.of();
        public List<String> evidenceIds = List.of();
    }

    private static class PersonaDraft {
        public String name;
        public String segment;
        public String companySize;
        public List<String> jobsToBeDone = List.of();
        public List<String> painPoints = List.of();
        public List<String> buyingConcerns = List.of();
        public List<String> evidenceIds = List.of();
    }
}
