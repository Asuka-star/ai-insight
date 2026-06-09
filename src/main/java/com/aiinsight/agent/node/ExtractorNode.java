package com.aiinsight.agent.node;

import com.aiinsight.agent.AgentNode;
import com.aiinsight.llm.ChatMessage;
import com.aiinsight.llm.ChatOptions;
import com.aiinsight.llm.ChatRequest;
import com.aiinsight.llm.LlmClient;
import com.aiinsight.model.enums.AgentName;
import com.aiinsight.model.enums.ArtifactType;
import com.aiinsight.model.enums.FactType;
import com.aiinsight.model.enums.ReviewAction;
import com.aiinsight.model.review.ReviewDecision;
import com.aiinsight.model.review.ReviewRepairTask;
import com.aiinsight.model.run.AnalysisArtifact;
import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.model.run.EvidenceChunk;
import com.aiinsight.model.run.EvidenceSource;
import com.aiinsight.model.schema.CompetitorFactSet;
import com.aiinsight.model.schema.CompetitorProfile;
import com.aiinsight.model.schema.ExtractedFact;
import com.aiinsight.model.schema.FeatureNode;
import com.aiinsight.model.schema.FeatureTree;
import com.aiinsight.model.schema.PricingModel;
import com.aiinsight.model.schema.PricingPlan;
import com.aiinsight.model.schema.UnknownFact;
import com.aiinsight.model.schema.UserPersona;
import com.aiinsight.observability.AgentTraceContext;
import com.aiinsight.service.EvidenceRetrievalService;
import com.aiinsight.service.fallback.FallbackExtractionFactory;
import com.aiinsight.util.JsonResponseExtractor;
import static com.aiinsight.util.AgentUtils.abbreviate;
import static com.aiinsight.util.AgentUtils.containsAny;
import static com.aiinsight.util.AgentUtils.containsIgnoreCase;
import static com.aiinsight.util.AgentUtils.knownEvidenceIds;
import static com.aiinsight.util.AgentUtils.latestArtifact;
import static com.aiinsight.util.AgentUtils.normalizeLower;
import static com.aiinsight.util.AgentUtils.nullToEmpty;
import static com.aiinsight.util.AgentUtils.safeList;
import static com.aiinsight.util.AgentUtils.textOrDash;
import static com.aiinsight.util.AgentUtils.textOrDefault;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSetter;
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

    private static final int MAX_FALLBACK_EVIDENCE_SOURCES_FOR_PROMPT = 16;
    private static final int MAX_RAG_COMPETITOR_DIMENSION_PAIRS = 18;
    private static final int MAX_RAG_EVIDENCE_PACK_CHARS = 16_000;
    private static final int RAG_CHUNKS_PER_DIMENSION = 2;
    private static final int MAX_FALLBACK_RAW_TEXT_CHARS = 300;
    private static final int MAX_RAG_CHUNK_TEXT_CHARS = 420;
    private static final int MAX_JSON_REPAIR_INPUT_CHARS = 9_000;
    private static final int MAX_EXTRACTOR_REPAIR_TASKS_IN_PROMPT = 6;
    private final LlmClient llmClient;
    private final FallbackExtractionFactory fallbackExtractionFactory;
    private final EvidenceRetrievalService evidenceRetrievalService;
    private final ObjectMapper objectMapper;
    private final ExtractorEvidenceBinder evidenceBinder = new ExtractorEvidenceBinder();
    private final FactExtractionEngine factExtractionEngine = new FactExtractionEngine();
    private final CompetitorProfileProjector profileProjector = new CompetitorProfileProjector();

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
                List<CompetitorProfile> rawProfiles = extractProfilesWithLlm(run);
                List<CompetitorFactSet> factSets = publishFactSets(run, rawProfiles);
                List<CompetitorProfile> profiles = profileProjector.projectProfilesFromFacts(rawProfiles, factSets);
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
        List<CompetitorProfile> rawProfiles = fallbackExtractionFactory.buildProfiles(run);
        List<CompetitorFactSet> factSets = publishFactSets(run, rawProfiles);
        List<CompetitorProfile> profiles = profileProjector.projectProfilesFromFacts(rawProfiles, factSets);
        run.getCompetitorProfiles().clear();
        run.getCompetitorProfiles().addAll(profiles);
        String content = fallbackExtractionFactory.buildMarkdown(run) + "\n\n## Fact-projected profile\n\n" + profilesMarkdown(profiles);
        AgentTraceContext.recordFallback("deterministic-extractor-fallback", content);
        run.addArtifact(new AnalysisArtifact(
                ArtifactType.COMPETITOR_PROFILE,
                "竞品知识 Schema",
                content,
                run.getEvidenceSources().stream().map(EvidenceSource::getCitationKey).toList()
        ));
        return run;
    }

    private List<CompetitorFactSet> publishFactSets(AnalysisRun run, List<CompetitorProfile> profiles) {
        List<CompetitorFactSet> factSets = buildFactSets(profiles, run);
        run.getCompetitorFactSets().clear();
        run.getCompetitorFactSets().addAll(factSets);
        run.addArtifact(new AnalysisArtifact(
                ArtifactType.FACT_EXTRACTION,
                "Fact Extraction",
                factSetsMarkdown(factSets),
                factSetCitationKeys(factSets)
        ));
        return factSets;
    }

    private List<CompetitorFactSet> buildFactSets(List<CompetitorProfile> profiles, AnalysisRun run) {
        return factExtractionEngine.buildFactSets(profiles, run);
    }

    private List<String> factSetCitationKeys(List<CompetitorFactSet> factSets) {
        return factSets.stream()
                .flatMap(factSet -> factSet.getFacts().stream())
                .flatMap(fact -> fact.getEvidenceIds().stream())
                .distinct()
                .toList();
    }

    private String factSetsMarkdown(List<CompetitorFactSet> factSets) {
        return factSets.stream()
                .map(factSet -> """
                        ### %s
                        #### Extracted facts
                        %s
                        #### Unknowns
                        %s
                        """.formatted(
                        factSet.getCompetitorName(),
                        factMarkdown(factSet.getFacts()),
                        unknownMarkdown(factSet.getUnknowns())
                ))
                .collect(Collectors.joining("\n\n"));
    }

    private String factMarkdown(List<ExtractedFact> facts) {
        if (facts == null || facts.isEmpty()) {
            return "- No evidence-bound facts extracted.";
        }
        return facts.stream()
                .map(fact -> "- %s %s.%s: %s evidence=%s chunks=%s confidence=%s support=%s risk=%s quote=%s".formatted(
                        fact.getId(),
                        fact.getFactType(),
                        fact.getAttribute(),
                        abbreviate(fact.getValue(), 180),
                        fact.getEvidenceIds(),
                        fact.getChunkKeys(),
                        fact.getExtractionConfidence(),
                        textOrDash(fact.getSupportStrength()),
                        textOrDash(fact.getRiskLevel()),
                        abbreviate(fact.getSupportQuote(), 120)
                ))
                .collect(Collectors.joining("\n"));
    }

    private String unknownMarkdown(List<UnknownFact> unknowns) {
        if (unknowns == null || unknowns.isEmpty()) {
            return "- None.";
        }
        return unknowns.stream()
                .map(unknown -> "- %s: %s needed=%s".formatted(
                        unknown.getField(),
                        unknown.getReason(),
                        unknown.getNeededEvidenceTypes()
                ))
                .collect(Collectors.joining("\n"));
    }

    private List<CompetitorProfile> extractProfilesWithLlm(AnalysisRun run) {
        if (run.getRequirement() != null
                && run.getRequirement().getCompetitors() != null
                && !run.getRequirement().getCompetitors().isEmpty()) {
            return extractProfilesPerCompetitor(run);
        }
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
                9. 如果处于复核修复模式，必须优先处理 repairTasks 指向的 fact/chunk/citation；不能原样保留 currentText 中被 Reviewer 指出的问题。
                10. 无法用证据支撑的字段写“待验证”，不要把错误 fact 继续放入明确事实字段。

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

                复核修复任务：
                %s
                """.formatted(
                run.getRequirement().getCompetitors(),
                evidenceBlock(run),
                repairPlanBlock(run)
        );
        String raw = llmClient.complete(new ChatRequest(
                List.of(
                        ChatMessage.system("你负责从证据中抽取竞品知识 Schema。必须输出 JSON，并保留证据编号。"),
                        ChatMessage.user(prompt)
                ),
                ChatOptions.extractor()
        ).tagged(name().name(), "profile-extraction"));
        List<CompetitorProfile> fallbackProfiles = fallbackExtractionFactory.buildProfiles(run);
        List<CompetitorProfile> llmProfiles = parseProfilesWithRepairRetry(raw, run, fallbackProfiles);
        if (llmProfiles.isEmpty()) {
            throw new IllegalStateException("模型未返回可用 profiles");
        }
        return llmProfiles;
    }

    private String repairPlanBlock(AnalysisRun run) {
        ReviewDecision decision = run.getRepairDecisionFor(AgentName.EXTRACTOR);
        if (decision == null
                || decision.getAction() != ReviewAction.REWORK_ANALYSIS
                || decision.getTargetAgent() != AgentName.EXTRACTOR) {
            return "当前不是 Extractor 复核修复模式。";
        }
        String instructions = decision.getRepairInstructions().isEmpty()
                ? "暂无具体修复指令。"
                : decision.getRepairInstructions().stream()
                .map(instruction -> "- " + instruction)
                .collect(Collectors.joining("\n"));
        String tasks = decision.getRepairTasks().stream()
                .filter(task -> task.getTargetAgent() == AgentName.EXTRACTOR)
                .map(this::repairTaskLine)
                .collect(Collectors.joining("\n"));
        return """
                修复范围：%s
                修复指令：
                %s
                结构化修复任务：
                %s
                """.formatted(
                nullToEmpty(decision.getRepairScopeSummary()),
                instructions,
                tasks.isBlank() ? "暂无结构化修复任务。" : tasks
        );
    }

    private String repairPlanBlock(AnalysisRun run, String competitor) {
        ReviewDecision decision = run.getRepairDecisionFor(AgentName.EXTRACTOR);
        if (decision == null
                || decision.getAction() != ReviewAction.REWORK_ANALYSIS
                || decision.getTargetAgent() != AgentName.EXTRACTOR) {
            return "当前不是 Extractor 复核修复模式。";
        }
        List<ReviewRepairTask> extractorTasks = decision.getRepairTasks().stream()
                .filter(task -> task.getTargetAgent() == AgentName.EXTRACTOR)
                .toList();
        List<ReviewRepairTask> scopedTasks = extractorTasks.stream()
                .filter(task -> repairTaskMentionsCompetitor(task, competitor))
                .toList();
        List<ReviewRepairTask> tasksForPrompt = (scopedTasks.isEmpty() ? extractorTasks : scopedTasks).stream()
                .limit(MAX_EXTRACTOR_REPAIR_TASKS_IN_PROMPT)
                .toList();
        String tasks = tasksForPrompt.stream()
                .map(this::compactRepairTaskLine)
                .collect(Collectors.joining("\n"));
        String instructions = decision.getRepairInstructions().stream()
                .limit(3)
                .map(instruction -> "- " + abbreviate(instruction, 180))
                .collect(Collectors.joining("\n"));
        return """
                修复范围：%s
                修复指令：
                %s
                结构化修复任务：
                %s
                """.formatted(
                nullToEmpty(decision.getRepairScopeSummary()),
                instructions.isBlank() ? "- 暂无具体修复指令。" : instructions,
                tasks.isBlank() ? "- 暂无结构化修复任务。" : tasks
        );
    }

    private boolean repairTaskMentionsCompetitor(ReviewRepairTask task, String competitor) {
        if (!StringUtils.hasText(competitor)) {
            return false;
        }
        if (StringUtils.hasText(task.getCompetitorName())) {
            return containsIgnoreCase(task.getCompetitorName(), competitor);
        }
        String text = "%s %s %s %s %s".formatted(
                nullToEmpty(task.getCurrentText()),
                nullToEmpty(task.getInstruction()),
                nullToEmpty(task.getExpectedFix()),
                nullToEmpty(task.getAcceptanceCriteria()),
                nullToEmpty(task.getExcerpt())
        );
        return containsIgnoreCase(text, competitor);
    }

    private CompetitorProfile previousProfileFor(List<CompetitorProfile> previousProfiles, String competitor) {
        if (previousProfiles == null || previousProfiles.isEmpty()) {
            return null;
        }
        return previousProfiles.stream()
                .filter(p -> normalizeLower(p.getProductName()).equals(normalizeLower(competitor)))
                .findFirst()
                .orElse(null);
    }

    private String formatPreviousProfile(CompetitorProfile profile) {
        return """
                productName: %s
                companyName: %s
                positioning: %s
                targetUsers: %s
                features: %s
                pricing.strategySummary: %s
                pricing.plans: %s
                personas: %s
                strengths: %s
                weaknesses: %s
                evidenceIds: %s
                """.formatted(
                profile.getProductName(),
                profile.getCompanyName(),
                profile.getPositioning(),
                profile.getTargetUsers(),
                profile.getFeatureTree() == null ? "[]" : profile.getFeatureTree().getRoots().stream()
                        .map(n -> "%s(%s)%s".formatted(n.getName(), abbreviate(n.getDescription(), 60), n.getEvidenceIds()))
                        .collect(Collectors.joining(", ")),
                profile.getPricingModel() == null ? "待验证" : profile.getPricingModel().getStrategySummary(),
                profile.getPricingModel() == null ? "[]" : profile.getPricingModel().getPlans().stream()
                        .map(p -> "%s/%s/%s".formatted(p.getName(), p.getPriceText(), p.getBillingCycle()))
                        .collect(Collectors.joining(", ")),
                profile.getPersonas() == null ? "[]" : profile.getPersonas().stream()
                        .map(p -> "%s(%s)".formatted(p.getName(), p.getSegment()))
                        .collect(Collectors.joining(", ")),
                profile.getStrengths(),
                profile.getWeaknesses(),
                profile.getEvidenceIds()
        );
    }

    /**
     * 结构性保留：当 LLM 在增量模式下重新生成 profile 时，对于 repair tasks 未涉及的区域，
     * 恢复为上一轮的原始数据，防止 LLM 非确定性导致未标记区域震荡。
     */
    private void preserveUnchangedSections(CompetitorProfile previous, CompetitorProfile revised,
                                            AnalysisRun run, String competitor) {
        ReviewDecision decision = run.getRepairDecisionFor(AgentName.EXTRACTOR);
        if (decision == null || decision.getRepairTasks().isEmpty()) {
            return;
        }
        List<ReviewRepairTask> tasks = decision.getRepairTasks().stream()
                .filter(t -> t.getTargetAgent() == AgentName.EXTRACTOR)
                .filter(t -> repairTaskMentionsCompetitor(t, competitor))
                .toList();
        if (tasks.isEmpty()) {
            return;
        }
        String taskText = tasks.stream()
                .map(t -> "%s %s %s %s %s".formatted(
                        nullToEmpty(t.getCategory()),
                        nullToEmpty(t.getInstruction()),
                        nullToEmpty(t.getCurrentText()),
                        nullToEmpty(t.getExpectedFix()),
                        nullToEmpty(t.getExcerpt())))
                .collect(Collectors.joining(" "))
                .toLowerCase(Locale.ROOT);
        boolean mentionsPricing = containsAny(taskText, "pricing", "price", "plan", "价格", "定价", "套餐");
        boolean mentionsFeatures = containsAny(taskText, "feature", "功能", "能力", "capability");
        boolean mentionsPersonas = containsAny(taskText, "persona", "用户画像", "用户", "target user", "用户群");
        if (!mentionsFeatures && previous.getFeatureTree() != null) {
            revised.setFeatureTree(previous.getFeatureTree());
        }
        if (!mentionsPricing && previous.getPricingModel() != null) {
            revised.setPricingModel(previous.getPricingModel());
        }
        if (!mentionsPersonas && previous.getPersonas() != null) {
            revised.setPersonas(previous.getPersonas());
        }
    }

    private boolean hasRepairTasksFor(AnalysisRun run, String competitor) {
        ReviewDecision decision = run.getRepairDecisionFor(AgentName.EXTRACTOR);
        if (decision == null
                || decision.getAction() != ReviewAction.REWORK_ANALYSIS
                || decision.getTargetAgent() != AgentName.EXTRACTOR) {
            return false;
        }
        return decision.getRepairTasks().stream()
                .filter(task -> task.getTargetAgent() == AgentName.EXTRACTOR)
                .anyMatch(task -> repairTaskMentionsCompetitor(task, competitor));
    }

    private String compactRepairTaskLine(ReviewRepairTask task) {
        return "- action=%s fact=%s claim=%s citation=%s currentText=%s instruction=%s expectedFix=%s".formatted(
                nullToEmpty(task.getAction()),
                nullToEmpty(task.getFactId()),
                nullToEmpty(task.getClaimId()),
                nullToEmpty(task.getCitationKey()),
                abbreviate(nullToEmpty(task.getCurrentText()), 120),
                abbreviate(nullToEmpty(task.getInstruction()), 160),
                abbreviate(nullToEmpty(task.getExpectedFix()), 160)
        );
    }

    private List<CompetitorProfile> parseProfilesWithRepairRetry(String raw,
                                                                 AnalysisRun run,
                                                                 List<CompetitorProfile> fallbackProfiles) {
        try {
            return parseProfiles(raw, run, fallbackProfiles);
        } catch (IllegalStateException firstFailure) {
            String repairedRaw;
            try {
                repairedRaw = repairExtractorJson(raw, run, firstFailure);
            } catch (RuntimeException repairFailure) {
                repairFailure.addSuppressed(firstFailure);
                throw repairFailure;
            }
            try {
                List<CompetitorProfile> profiles = parseProfiles(repairedRaw, run, fallbackProfiles);
                AgentTraceContext.recordProcessSummary("""
                        Extractor JSON repair retry succeeded:
                        initialFailure=%s
                        repairedOutputChars=%d
                        """.formatted(
                        abbreviate(firstFailure.getMessage(), 260),
                        repairedRaw == null ? 0 : repairedRaw.length()
                ));
                return profiles;
            } catch (IllegalStateException retryFailure) {
                retryFailure.addSuppressed(firstFailure);
                throw retryFailure;
            }
        }
    }

    private List<CompetitorProfile> extractProfilesPerCompetitor(AnalysisRun run) {
        List<CompetitorProfile> fallbackProfiles = fallbackExtractionFactory.buildProfiles(run);
        List<CompetitorProfile> previousProfiles = latestArtifact(run.getArtifacts(), ArtifactType.COMPETITOR_PROFILE)
                .<List<CompetitorProfile>>map(artifact -> run.getCompetitorProfiles())
                .orElse(List.of());
        List<CompetitorProfile> profiles = new ArrayList<>();
        for (String competitor : run.getRequirement().getCompetitors()) {
            if (!StringUtils.hasText(competitor)) {
                continue;
            }
            CompetitorProfile previousProfile = previousProfileFor(previousProfiles, competitor);
            CompetitorProfile extracted = extractSingleProfileWithLlm(run, competitor.trim(),
                    fallbackFor(fallbackProfiles, competitor), previousProfile);
            if (previousProfile != null && hasRepairTasksFor(run, competitor)) {
                preserveUnchangedSections(previousProfile, extracted, run, competitor);
            }
            profiles.add(extracted);
        }
        return profiles;
    }

    private CompetitorProfile extractSingleProfileWithLlm(AnalysisRun run,
                                                          String competitor,
                                                          CompetitorProfile fallbackProfile,
                                                          CompetitorProfile previousProfile) {
        String raw = llmClient.complete(new ChatRequest(
                List.of(
                        ChatMessage.system("你是竞品结构化抽取 Agent。只输出合法 JSON 对象，不要输出 Markdown。只抽取证据支持的事实。"),
                        ChatMessage.user(singleCompetitorPrompt(run, competitor, previousProfile))
                ),
                new ChatOptions(0.1, 1800)
        ).tagged(name().name(), "profile-extraction:%s".formatted(competitor)));
        List<CompetitorProfile> parsedProfiles = parseProfilesWithRepairRetry(raw, run, List.of(fallbackProfile));
        if (parsedProfiles.isEmpty()) {
            throw new IllegalStateException("模型未返回当前竞品可用 profile: " + competitor);
        }
        return parsedProfiles.stream()
                .filter(profile -> normalizeLower(profile.getProductName()).equals(normalizeLower(competitor)))
                .findFirst()
                .orElseGet(() -> parsedProfiles.get(0));
    }

    private String singleCompetitorPrompt(AnalysisRun run, String competitor, CompetitorProfile previousProfile) {
        return """
                只抽取一个竞品：%s。

                输出要求：
                1. 只输出可解析 JSON，不要 Markdown、解释或代码块。
                2. JSON 格式必须是 {"profiles":[profile]}，profiles 数组只能有 1 个对象。
                3. productName 必须等于 "%s"。
                4. evidenceIds 只能使用证据包里出现的 S 编号；不要输出 S1-C2 这种 chunkKey 到 evidenceIds。
                5. 不确定或证据不支持的字段写“待验证”，不要编造。
                6. features、pricing、personas、strengths、weaknesses 必须来自证据事实，不要写建议、机会、风险、战略判断。
                7. 尽量紧凑：features 最多 6 个，personas 最多 3 个，pricing.plans 最多 4 个，strengths/weaknesses 各最多 4 条。

                profile schema:
                {
                  "productName": "%s",
                  "companyName": "公司名或待验证",
                  "positioning": "不超过60字",
                  "targetUsers": ["目标用户或待验证"],
                  "features": [{"name":"功能名","description":"不超过60字","evidenceIds":["S1"]}],
                  "pricing": {"strategySummary":"定价事实或待验证","hasFreePlan":false,"plans":[{"name":"套餐名","priceText":"价格文本或待验证","billingCycle":"monthly|yearly|usage_based|unknown","targetSegment":"目标客群或待验证","includedFeatures":["能力"],"evidenceIds":["S1"]}],"evidenceIds":["S1"]},
                  "personas": [{"name":"画像名","segment":"细分人群","companySize":"规模或待验证","jobsToBeDone":["任务"],"painPoints":["痛点"],"buyingConcerns":["采购顾虑"],"evidenceIds":["S1"]}],
                  "strengths": ["证据明确支持的优势事实"],
                  "weaknesses": ["证据明确支持的限制或待验证"],
                  "evidenceIds": ["S1"]
                }

                证据片段索引：
                %s

                %s

                复核修复任务：
                %s
                """.formatted(
                competitor,
                competitor,
                competitor,
                evidenceBlock(run, competitor),
                previousProfile != null
                        ? "上一轮抽取结果（仅修改修复任务指名的部分，其余必须原样保留）：\n" + formatPreviousProfile(previousProfile)
                        : "",
                repairPlanBlock(run, competitor)
        );
    }

    private String repairExtractorJson(String raw, AnalysisRun run, RuntimeException failure) {
        String prompt = """
                下面是 Extractor Agent 返回的 JSON，但它无法被解析。请只修复 JSON 语法，不要新增事实、不要改写证据编号、不要输出 Markdown。
                必须输出一个可被 JSON.parse 解析的对象，格式为 {"profiles":[...]}。
                只允许保留这些竞品：%s。
                如果某个字段不确定，保留原值或写“待验证”。不要解释。

                解析错误：
                %s

                待修复输出：
                %s
                """.formatted(
                run.getRequirement().getCompetitors(),
                abbreviate(failure.getMessage(), 600),
                abbreviate(raw, MAX_JSON_REPAIR_INPUT_CHARS)
        );
        return llmClient.complete(new ChatRequest(
                List.of(
                        ChatMessage.system("你是 JSON 修复器。只输出合法 JSON 对象，不要输出任何解释、Markdown 或代码块。"),
                        ChatMessage.user(prompt)
                ),
                new ChatOptions(0.0, 3200)
        ).tagged(name().name(), "profile-json-repair"));
    }

    private String repairTaskLine(ReviewRepairTask task) {
        return "- action=%s fact=%s chunk=%s claim=%s citation=%s currentText=%s instruction=%s expectedFix=%s criteria=%s".formatted(
                nullToEmpty(task.getAction()),
                nullToEmpty(task.getFactId()),
                nullToEmpty(task.getChunkKey()),
                nullToEmpty(task.getClaimId()),
                nullToEmpty(task.getCitationKey()),
                nullToEmpty(task.getCurrentText()),
                nullToEmpty(task.getInstruction()),
                nullToEmpty(task.getExpectedFix()),
                nullToEmpty(task.getAcceptanceCriteria())
        );
    }

    private List<CompetitorProfile> parseProfiles(String raw, AnalysisRun run, List<CompetitorProfile> fallbackProfiles) {
        if (!StringUtils.hasText(raw)) {
            return List.of();
        }
        String extractedJson = null;
        JsonNode root = null;
        JsonNode profilesNode = null;
        try {
            extractedJson = JsonResponseExtractor.extractJsonValue(raw);
            root = objectMapper.readTree(extractedJson);
            profilesNode = profilesNode(root);
            List<ProfileDraft> drafts = profileDrafts(profilesNode);
            Map<String, ProfileDraft> draftByName = (drafts == null ? List.<ProfileDraft>of() : drafts).stream()
                    .filter(draft -> StringUtils.hasText(draft.productName))
                    .collect(Collectors.toMap(
                            draft -> normalizeLower(draft.productName),
                            draft -> draft,
                            (first, ignored) -> first
                    ));
            List<CompetitorProfile> profiles = run.getRequirement().getCompetitors().stream()
                    .map(competitor -> {
                        CompetitorProfile fallback = fallbackFor(fallbackProfiles, competitor);
                        ProfileDraft draft = draftByName.get(normalizeLower(competitor));
                        return draft == null ? fallback : toProfile(draft, fallback, run);
                    })
                    .toList();
            recordExtractorParseSuccess(raw, extractedJson, root, profilesNode, drafts, draftByName.keySet(), run);
            return profiles;
        } catch (IllegalArgumentException | JsonProcessingException ex) {
            recordExtractorParseFailure(raw, extractedJson, root, profilesNode, ex, run);
            throw new IllegalStateException(extractorJsonFailureMessage(raw, ex), ex);
        }
    }

    private JsonNode profilesNode(JsonNode root) {
        if (root == null || root.isNull() || root.isMissingNode()) {
            return root;
        }
        if (!root.isObject() || looksLikeProfileDraft(root)) {
            return root;
        }
        for (String field : List.of("profiles", "competitorProfiles", "competitors", "products", "items")) {
            JsonNode direct = root.get(field);
            if (direct != null && !direct.isNull() && !direct.isMissingNode()) {
                return direct;
            }
        }
        for (String field : List.of("data", "result", "output")) {
            JsonNode nested = root.get(field);
            if (nested != null && !nested.isNull() && !nested.isMissingNode()) {
                return profilesNode(nested);
            }
        }
        return root;
    }

    private List<ProfileDraft> profileDrafts(JsonNode profilesNode) throws JsonProcessingException {
        profilesNode = parseTextualJsonIfNeeded(profilesNode);
        if (profilesNode == null || profilesNode.isNull()) {
            return List.of();
        }
        if (profilesNode.isArray()) {
            return objectMapper.convertValue(profilesNode, new TypeReference<>() {
            });
        }
        if (profilesNode.isObject()) {
            if (looksLikeProfileDraft(profilesNode)) {
                return List.of(objectMapper.convertValue(profilesNode, ProfileDraft.class));
            }
            List<ProfileDraft> drafts = new ArrayList<>();
            profilesNode.fields().forEachRemaining(entry -> {
                JsonNode value = entry.getValue();
                if (value != null && value.isObject() && looksLikeProfileDraft(value)) {
                    ProfileDraft draft = objectMapper.convertValue(value, ProfileDraft.class);
                    if (!StringUtils.hasText(draft.productName)) {
                        draft.productName = entry.getKey();
                    }
                    drafts.add(draft);
                }
            });
            if (!drafts.isEmpty()) {
                return drafts;
            }
        }
        throw new IllegalArgumentException("profiles 字段不是数组、profile 对象或产品名映射");
    }

    private JsonNode parseTextualJsonIfNeeded(JsonNode node) throws JsonProcessingException {
        if (node == null || !node.isTextual() || !StringUtils.hasText(node.asText())) {
            return node;
        }
        String text = node.asText().trim();
        if (!text.startsWith("{") && !text.startsWith("[") && !text.startsWith("```")) {
            return node;
        }
        return objectMapper.readTree(JsonResponseExtractor.extractJsonValue(text));
    }

    private boolean looksLikeProfileDraft(JsonNode node) {
        if (node == null || !node.isObject()) {
            return false;
        }
        return node.has("productName")
                || node.has("companyName")
                || node.has("positioning")
                || node.has("targetUsers")
                || node.has("features")
                || node.has("pricing")
                || node.has("personas")
                || node.has("strengths")
                || node.has("weaknesses")
                || node.has("evidenceIds");
    }

    private String extractorJsonFailureMessage(String raw, Exception ex) {
        String cause = ex.getClass().getSimpleName() + ": " + textOrDefault(ex.getMessage(), "no message");
        String preview = abbreviate(compact(raw), 700);
        return "无法解析 Extractor JSON: cause=" + cause + ", outputPreview=" + preview;
    }

    private void recordExtractorParseSuccess(String raw,
                                             String extractedJson,
                                             JsonNode root,
                                             JsonNode profilesNode,
                                             List<ProfileDraft> drafts,
                                             Set<String> parsedProfileNames,
                                             AnalysisRun run) {
        Set<String> requested = run.getRequirement().getCompetitors().stream()
                .map(competitor -> normalizeLower(competitor))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> matched = parsedProfileNames.stream()
                .filter(requested::contains)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        AgentTraceContext.recordProcessSummary("""
                Extractor JSON parse succeeded:
                - rawChars=%d
                - extractedJsonChars=%d
                - rootShape=%s
                - profilesShape=%s
                - draftCount=%d
                - requestedCompetitors=%s
                - matchedCompetitors=%s
                """.formatted(
                raw == null ? 0 : raw.length(),
                extractedJson == null ? 0 : extractedJson.length(),
                nodeShape(root),
                nodeShape(profilesNode),
                drafts == null ? 0 : drafts.size(),
                run.getRequirement().getCompetitors(),
                matched
        ).trim());
    }

    private void recordExtractorParseFailure(String raw,
                                             String extractedJson,
                                             JsonNode root,
                                             JsonNode profilesNode,
                                             Exception ex,
                                             AnalysisRun run) {
        String diagnostic = """
                Extractor JSON parse failed:
                - runId=%s
                - exception=%s
                - rawChars=%d
                - extractedJsonChars=%d
                - rootShape=%s
                - rootFields=%s
                - profilesShape=%s
                - profilesFields=%s
                - requestedCompetitors=%s
                - rawPreview=%s
                - extractedJsonPreview=%s
                """.formatted(
                run.getId(),
                exceptionSummary(ex),
                raw == null ? 0 : raw.length(),
                extractedJson == null ? 0 : extractedJson.length(),
                nodeShape(root),
                objectFieldNames(root),
                nodeShape(profilesNode),
                objectFieldNames(profilesNode),
                run.getRequirement().getCompetitors(),
                abbreviate(compact(raw), 1000),
                abbreviate(compact(extractedJson), 1000)
        ).trim();
        AgentTraceContext.recordProcessSummary(diagnostic);
        log.warn("Extractor JSON parse failed: runId={}, exception={}, rawChars={}, extractedJsonChars={}, rootShape={}, profilesShape={}, rootFields={}, profilesFields={}, rawPreview={}, extractedJsonPreview={}",
                run.getId(),
                exceptionSummary(ex),
                raw == null ? 0 : raw.length(),
                extractedJson == null ? 0 : extractedJson.length(),
                nodeShape(root),
                nodeShape(profilesNode),
                objectFieldNames(root),
                objectFieldNames(profilesNode),
                abbreviate(compact(raw), 400),
                abbreviate(compact(extractedJson), 400));
    }

    private String nodeShape(JsonNode node) {
        if (node == null) {
            return "missing";
        }
        if (node.isObject()) {
            return "object(fields=%d)".formatted(node.size());
        }
        if (node.isArray()) {
            return "array(size=%d)".formatted(node.size());
        }
        return node.getNodeType().name().toLowerCase(Locale.ROOT);
    }

    private String objectFieldNames(JsonNode node) {
        if (node == null || !node.isObject()) {
            return "[]";
        }
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names.stream().limit(20).toList().toString();
    }

    private String exceptionSummary(Exception ex) {
        String message = textOrDefault(ex.getMessage(), "no message");
        Throwable cause = ex.getCause();
        if (cause == null) {
            return ex.getClass().getSimpleName() + ": " + message;
        }
        return ex.getClass().getSimpleName() + ": " + message
                + " | cause=" + cause.getClass().getSimpleName() + ": " + textOrDefault(cause.getMessage(), "no message");
    }

    private String compact(String value) {
        return nullToEmpty(value).replaceAll("\\s+", " ").trim();
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
            return sanitizePricingModel(fallback, run);
        }
        PricingModel model = new PricingModel();
        String fallbackStrategySummary = fallback == null ? "\u5f85\u9a8c\u8bc1" : fallback.getStrategySummary();
        List<String> fallbackEvidenceIds = fallback == null ? List.of() : fallback.getEvidenceIds();
        List<PricingPlan> fallbackPlans = fallback == null ? List.of() : fallback.getPlans();
        String strategySummary = textOrDefault(draft.strategySummary, fallbackStrategySummary);
        model.setStrategySummary(factExtractionEngine.looksLikeTemplatePricingValue(strategySummary) ? "\u5f85\u9a8c\u8bc1" : strategySummary);
        model.setHasFreePlan(draft.hasFreePlan == null ? false : draft.hasFreePlan);
        List<String> draftEvidenceIds = evidenceBinder.pricingEvidenceIds(run, draft.evidenceIds);
        model.setEvidenceIds(draftEvidenceIds.isEmpty() ? evidenceBinder.pricingEvidenceIds(run, fallbackEvidenceIds) : draftEvidenceIds);
        List<PricingPlan> plans = (draft.plans == null ? List.<PricingPlanDraft>of() : draft.plans).stream()
                .map(plan -> pricingPlan(plan, run))
                .filter(plan -> plan != null)
                .limit(6)
                .toList();
        model.setPlans(plans.isEmpty() ? sanitizedPricingPlans(fallbackPlans, run) : plans);
        return model;
    }

    private PricingModel sanitizePricingModel(PricingModel fallback, AnalysisRun run) {
        PricingModel model = new PricingModel();
        if (fallback == null) {
            model.setStrategySummary("\u5f85\u9a8c\u8bc1");
            model.setHasFreePlan(false);
            model.setEvidenceIds(List.of());
            model.setPlans(List.of());
            return model;
        }
        String strategySummary = textOrDefault(fallback.getStrategySummary(), "\u5f85\u9a8c\u8bc1");
        model.setStrategySummary(factExtractionEngine.looksLikeTemplatePricingValue(strategySummary) ? "\u5f85\u9a8c\u8bc1" : strategySummary);
        model.setHasFreePlan(false);
        model.setEvidenceIds(evidenceBinder.pricingEvidenceIds(run, fallback.getEvidenceIds()));
        model.setPlans(sanitizedPricingPlans(fallback.getPlans(), run));
        return model;
    }

    private List<PricingPlan> sanitizedPricingPlans(List<PricingPlan> plans, AnalysisRun run) {
        return (plans == null ? List.<PricingPlan>of() : plans).stream()
                .filter(plan -> plan != null && !looksLikeTemplatePricingPlan(plan))
                .map(plan -> {
                    List<String> evidenceIds = evidenceBinder.pricingEvidenceIds(run, plan.getEvidenceIds());
                    if (evidenceIds.isEmpty()) {
                        return null;
                    }
                    return new PricingPlan(
                            textOrDefault(plan.getName(), "\u5f85\u9a8c\u8bc1"),
                            textOrDefault(plan.getPriceText(), "\u5f85\u9a8c\u8bc1"),
                            textOrDefault(plan.getBillingCycle(), "unknown"),
                            textOrDefault(plan.getTargetSegment(), "\u5f85\u9a8c\u8bc1"),
                            nonEmptyStrings(plan.getIncludedFeatures(), List.of("\u5f85\u9a8c\u8bc1")),
                            evidenceIds
                    );
                })
                .filter(plan -> plan != null)
                .toList();
    }

    private PricingPlan pricingPlan(PricingPlanDraft draft, AnalysisRun run) {
        List<String> evidenceIds = evidenceBinder.pricingEvidenceIds(run, draft.evidenceIds);
        if (evidenceIds.isEmpty()) {
            return null;
        }
        PricingPlan plan = new PricingPlan(
                textOrDefault(draft.name, "未命名套餐"),
                textOrDefault(draft.priceText, "待验证"),
                textOrDefault(draft.billingCycle, "unknown"),
                textOrDefault(draft.targetSegment, "待验证"),
                nonEmptyStrings(draft.includedFeatures, List.of("待验证")),
                evidenceIds
        );
        return looksLikeTemplatePricingPlan(plan) ? null : plan;
    }

    private boolean looksLikeTemplatePricingPlan(PricingPlan plan) {
        if (plan == null) {
            return false;
        }
        String value = "%s | %s | %s | %s | %s".formatted(
                nullToEmpty(plan.getName()),
                nullToEmpty(plan.getPriceText()),
                nullToEmpty(plan.getBillingCycle()),
                nullToEmpty(plan.getTargetSegment()),
                plan.getIncludedFeatures() == null ? "" : String.join(", ", plan.getIncludedFeatures())
        );
        return factExtractionEngine.looksLikeTemplatePricingValue(value);
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
                .limit(MAX_FALLBACK_EVIDENCE_SOURCES_FOR_PROMPT)
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
                        abbreviate(source.getRawText(), MAX_FALLBACK_RAW_TEXT_CHARS)
                ))
                .collect(Collectors.joining("\n"));
    }

    private String evidenceBlock(AnalysisRun run, String competitor) {
        if (!run.getEvidenceChunks().isEmpty()) {
            String pack = ragEvidencePack(run, List.of(competitor));
            AgentTraceContext.recordProcessSummary("Extractor per-competitor RAG evidence pack selected for %s:\n%s".formatted(
                    competitor,
                    abbreviate(pack, 3000)
            ));
            return pack;
        }
        String filtered = run.getEvidenceSources().stream()
                .filter(source -> evidenceMentionsCompetitor(source, competitor))
                .limit(MAX_FALLBACK_EVIDENCE_SOURCES_FOR_PROMPT)
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
                        abbreviate(source.getRawText(), MAX_FALLBACK_RAW_TEXT_CHARS)
                ))
                .collect(Collectors.joining("\n"));
        if (StringUtils.hasText(filtered)) {
            return filtered;
        }
        return evidenceBlock(run);
    }

    private String ragEvidencePack(AnalysisRun run) {
        return ragEvidencePack(run, run.getRequirement().getCompetitors());
    }

    private String ragEvidencePack(AnalysisRun run, List<String> competitors) {
        List<String> dimensions = extractionDimensions(run);
        StringBuilder pack = new StringBuilder();
        int pairCount = 0;
        for (String competitor : competitors) {
            for (String dimension : dimensions) {
                List<EvidenceChunk> chunks = evidenceRetrievalService.retrieve(
                        run,
                        competitor + " " + dimension,
                        competitor,
                        dimension,
                        RAG_CHUNKS_PER_DIMENSION
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
                if (pairCount >= MAX_RAG_COMPETITOR_DIMENSION_PAIRS || pack.length() > MAX_RAG_EVIDENCE_PACK_CHARS) {
                    return pack.toString();
                }
            }
        }
        if (!pack.isEmpty()) {
            return pack.toString();
        }
        return run.getEvidenceChunks().stream()
                .limit(MAX_FALLBACK_EVIDENCE_SOURCES_FOR_PROMPT)
                .map(this::formatChunk)
                .collect(Collectors.joining("\n"));
    }

    private boolean evidenceMentionsCompetitor(EvidenceSource source, String competitor) {
        if (!StringUtils.hasText(competitor)) {
            return true;
        }
        String text = "%s %s %s %s".formatted(
                nullToEmpty(source.getTitle()),
                nullToEmpty(source.getUrl()),
                nullToEmpty(source.getSnippet()),
                nullToEmpty(source.getRawText())
        );
        return containsIgnoreCase(text, competitor);
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
                abbreviate(chunk.getText(), MAX_RAG_CHUNK_TEXT_CHARS)
        );
    }

    private CompetitorProfile fallbackFor(List<CompetitorProfile> fallbackProfiles, String competitor) {
        return fallbackProfiles.stream()
                .filter(profile -> normalizeLower(profile.getProductName()).equals(normalizeLower(competitor)))
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
        String normalized = normalizeLower(value);
        if (containsAny(normalized,
                "风险管理", "风险控制", "风险仪表盘", "机会管理",
                "risk management", "risk control", "risk dashboard", "opportunity management")) {
            return false;
        }
        return containsAny(normalized,
                "建议", "应该", "机会", "风险", "威胁", "战略", "取舍", "推荐", "优先",
                "recommend", "should", "opportunity", "risk", "threat", "strategy", "priority");
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
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

        @JsonSetter("targetUsers")
        public void setTargetUsers(JsonNode value) {
            this.targetUsers = flexibleStringList(value);
        }

        @JsonSetter("strengths")
        public void setStrengths(JsonNode value) {
            this.strengths = flexibleStringList(value);
        }

        @JsonSetter("weaknesses")
        public void setWeaknesses(JsonNode value) {
            this.weaknesses = flexibleStringList(value);
        }

        @JsonSetter("evidenceIds")
        public void setEvidenceIds(JsonNode value) {
            this.evidenceIds = flexibleStringList(value);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class FeatureDraft {
        public String name;
        public String description;
        public List<String> evidenceIds = List.of();

        @JsonSetter("evidenceIds")
        public void setEvidenceIds(JsonNode value) {
            this.evidenceIds = flexibleStringList(value);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class PricingDraft {
        public String strategySummary;
        private Boolean hasFreePlan;
        public List<PricingPlanDraft> plans = List.of();
        public List<String> evidenceIds = List.of();

        @JsonSetter("hasFreePlan")
        public void setHasFreePlan(JsonNode value) {
            this.hasFreePlan = flexibleBoolean(value);
        }

        @JsonSetter("evidenceIds")
        public void setEvidenceIds(JsonNode value) {
            this.evidenceIds = flexibleStringList(value);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class PricingPlanDraft {
        public String name;
        public String priceText;
        public String billingCycle;
        public String targetSegment;
        public List<String> includedFeatures = List.of();
        public List<String> evidenceIds = List.of();

        @JsonSetter("includedFeatures")
        public void setIncludedFeatures(JsonNode value) {
            this.includedFeatures = flexibleStringList(value);
        }

        @JsonSetter("evidenceIds")
        public void setEvidenceIds(JsonNode value) {
            this.evidenceIds = flexibleStringList(value);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class PersonaDraft {
        public String name;
        public String segment;
        public String companySize;
        public List<String> jobsToBeDone = List.of();
        public List<String> painPoints = List.of();
        public List<String> buyingConcerns = List.of();
        public List<String> evidenceIds = List.of();

        @JsonSetter("jobsToBeDone")
        public void setJobsToBeDone(JsonNode value) {
            this.jobsToBeDone = flexibleStringList(value);
        }

        @JsonSetter("painPoints")
        public void setPainPoints(JsonNode value) {
            this.painPoints = flexibleStringList(value);
        }

        @JsonSetter("buyingConcerns")
        public void setBuyingConcerns(JsonNode value) {
            this.buyingConcerns = flexibleStringList(value);
        }

        @JsonSetter("evidenceIds")
        public void setEvidenceIds(JsonNode value) {
            this.evidenceIds = flexibleStringList(value);
        }
    }

    private static List<String> flexibleStringList(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return List.of();
        }
        if (value.isArray()) {
            List<String> values = new ArrayList<>();
            value.forEach(item -> {
                if (item != null && item.isValueNode() && StringUtils.hasText(item.asText())) {
                    values.add(item.asText().trim());
                }
            });
            return values.stream().distinct().toList();
        }
        if (value.isValueNode() && StringUtils.hasText(value.asText())) {
            return List.of(value.asText().trim());
        }
        return List.of();
    }

    private static Boolean flexibleBoolean(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return null;
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        if (value.isNumber()) {
            return value.asInt() != 0;
        }
        if (!value.isTextual()) {
            return null;
        }
        String normalized = value.asText("").trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()
                || List.of("unknown", "n/a", "na", "null", "待验证", "待驗證", "未验证", "未驗證", "不确定", "不確定").contains(normalized)) {
            return null;
        }
        if (List.of("true", "yes", "y", "1", "有", "是", "支持", "包含").contains(normalized)) {
            return true;
        }
        if (List.of("false", "no", "n", "0", "无", "無", "否", "不支持", "没有", "沒有").contains(normalized)) {
            return false;
        }
        return null;
    }
}
