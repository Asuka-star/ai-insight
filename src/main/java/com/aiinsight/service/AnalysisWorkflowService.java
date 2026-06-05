package com.aiinsight.service;

import com.aiinsight.agent.node.ClarifierNode;
import com.aiinsight.dto.AddAnalysisContextRequest;
import com.aiinsight.dto.AddUserEvidenceRequest;
import com.aiinsight.dto.AnalysisRunMetrics;
import com.aiinsight.dto.AnalysisRunSummary;
import com.aiinsight.dto.CreateAnalysisRunRequest;
import com.aiinsight.dto.UpdateAnalysisRequirementRequest;
import com.aiinsight.exception.InvalidRunStateException;
import com.aiinsight.exception.RunNotFoundException;
import com.aiinsight.model.enums.AgentName;
import com.aiinsight.model.enums.AnalysisStatus;
import com.aiinsight.model.enums.ArtifactType;
import com.aiinsight.model.enums.ContextIntent;
import com.aiinsight.model.enums.ContextRole;
import com.aiinsight.model.enums.ReviewAction;
import com.aiinsight.model.enums.ReviewSeverity;
import com.aiinsight.model.run.AgentTrace;
import com.aiinsight.model.run.AnalysisContextMessage;
import com.aiinsight.model.run.AnalysisArtifact;
import com.aiinsight.model.run.AnalysisRequirement;
import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.model.run.ClarificationDraft;
import com.aiinsight.model.run.EvidenceChunk;
import com.aiinsight.model.run.EvidenceSource;
import com.aiinsight.model.run.ReviewRepairDelta;
import com.aiinsight.model.run.UserProvidedEvidence;
import com.aiinsight.model.schema.CompetitorProfile;
import com.aiinsight.model.schema.Questionnaire;
import com.aiinsight.model.schema.ResearchCollectionPlan;
import com.aiinsight.model.schema.ResearchCoverageGap;
import com.aiinsight.model.schema.ResearchPlan;
import com.aiinsight.model.schema.ResearchRepairTarget;
import com.aiinsight.model.schema.ResearchSubtask;
import com.aiinsight.model.schema.SurveyQuestion;
import com.aiinsight.model.schema.SurveyResultImport;
import com.aiinsight.repository.AnalysisRunRepository;
import com.aiinsight.service.fallback.FallbackClarificationDraftFactory;
import com.aiinsight.workflow.AnalysisLangGraphWorkflow;
import com.aiinsight.workflow.WorkflowNodeExecutor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

@Service
public class AnalysisWorkflowService {

    private static final Pattern CITATION_PATTERN = Pattern.compile("\\[S\\d+]");

    private final AnalysisRunRepository repository;
    private final AnalysisRequestNormalizer normalizer;
    private final AnalysisEventBroker eventBroker;
    private final AsyncTaskExecutor analysisTaskExecutor;
    private final AnalysisLangGraphWorkflow graphWorkflow;
    private final WorkflowNodeExecutor nodeExecutor;
    private final ClarifierNode clarifierNode;
    private final FallbackClarificationDraftFactory fallbackClarificationDraftFactory;
    private final EvidenceRetrievalService evidenceRetrievalService;
    private final SourceCollectionService sourceCollectionService;
    private final EvidenceChunkService evidenceChunkService;
    private final EvidenceEmbeddingService evidenceEmbeddingService;
    private final DocumentIngestionService documentIngestionService;
    private final ConcurrentMap<UUID, AgentName> activeReruns = new ConcurrentHashMap<>();
    private InterviewInsightExtractor interviewInsightExtractor = new InterviewInsightExtractor();
    private SurveyInsightExtractor surveyInsightExtractor = new SurveyInsightExtractor();
    private SurveyResultImportService surveyResultImportService = new SurveyResultImportService();

    @Autowired
    public AnalysisWorkflowService(AnalysisRunRepository repository,
            AnalysisRequestNormalizer normalizer,
            AnalysisEventBroker eventBroker,
            AsyncTaskExecutor analysisTaskExecutor,
            AnalysisLangGraphWorkflow graphWorkflow,
            WorkflowNodeExecutor nodeExecutor,
            ClarifierNode clarifierNode,
            FallbackClarificationDraftFactory fallbackClarificationDraftFactory,
            EvidenceRetrievalService evidenceRetrievalService,
            SourceCollectionService sourceCollectionService,
            EvidenceChunkService evidenceChunkService,
            EvidenceEmbeddingService evidenceEmbeddingService,
            DocumentIngestionService documentIngestionService) {
        this.repository = repository;
        this.normalizer = normalizer;
        this.eventBroker = eventBroker;
        this.analysisTaskExecutor = analysisTaskExecutor;
        this.graphWorkflow = graphWorkflow;
        this.nodeExecutor = nodeExecutor;
        this.clarifierNode = clarifierNode;
        this.fallbackClarificationDraftFactory = fallbackClarificationDraftFactory;
        this.evidenceRetrievalService = evidenceRetrievalService;
        this.sourceCollectionService = sourceCollectionService;
        this.evidenceChunkService = evidenceChunkService;
        this.evidenceEmbeddingService = evidenceEmbeddingService;
        this.documentIngestionService = documentIngestionService == null
                ? new DocumentIngestionService(new DocumentTextExtractor(), evidenceChunkService, evidenceEmbeddingService)
                : documentIngestionService;
    }

    public AnalysisWorkflowService(AnalysisRunRepository repository,
            AnalysisRequestNormalizer normalizer,
            AnalysisEventBroker eventBroker,
            AsyncTaskExecutor analysisTaskExecutor,
            AnalysisLangGraphWorkflow graphWorkflow,
            WorkflowNodeExecutor nodeExecutor,
            ClarifierNode clarifierNode,
            FallbackClarificationDraftFactory fallbackClarificationDraftFactory,
            EvidenceRetrievalService evidenceRetrievalService,
            SourceCollectionService sourceCollectionService,
            EvidenceChunkService evidenceChunkService,
            EvidenceEmbeddingService evidenceEmbeddingService) {
        this(repository,
                normalizer,
                eventBroker,
                analysisTaskExecutor,
                graphWorkflow,
                nodeExecutor,
                clarifierNode,
                fallbackClarificationDraftFactory,
                evidenceRetrievalService,
                sourceCollectionService,
                evidenceChunkService,
                evidenceEmbeddingService,
                null);
    }

    public AnalysisWorkflowService(AnalysisRunRepository repository,
            AnalysisRequestNormalizer normalizer,
            AnalysisEventBroker eventBroker,
            AsyncTaskExecutor analysisTaskExecutor,
            AnalysisLangGraphWorkflow graphWorkflow,
            WorkflowNodeExecutor nodeExecutor,
            ClarifierNode clarifierNode,
            FallbackClarificationDraftFactory fallbackClarificationDraftFactory,
            EvidenceRetrievalService evidenceRetrievalService,
            SourceCollectionService sourceCollectionService,
            EvidenceChunkService evidenceChunkService) {
        this(repository,
                normalizer,
                eventBroker,
                analysisTaskExecutor,
                graphWorkflow,
                nodeExecutor,
                clarifierNode,
                fallbackClarificationDraftFactory,
                evidenceRetrievalService,
                sourceCollectionService,
                evidenceChunkService,
                EvidenceEmbeddingService.disabled(),
                null);
    }

    public AnalysisRun createDraft(CreateAnalysisRunRequest request) {
        AnalysisRun run = initializeDraft(request);
        run = executeClarifier(run.getId(), "preflight clarification", "澄清草稿已生成");
        return run;
    }

    public AnalysisRun createDraftAsync(CreateAnalysisRunRequest request) {
        AnalysisRun run = initializeDraft(request);
        UUID runId = run.getId();
        analysisTaskExecutor.execute(() -> executeClarifier(runId, "preflight clarification", "澄清草稿已生成"));
        return get(runId);
    }

    public AnalysisRun start(CreateAnalysisRunRequest request) {
        AnalysisRun run = createDraft(request);
        return startExecution(run.getId());
    }

    private AnalysisRun initializeDraft(CreateAnalysisRunRequest request) {
        AnalysisRun run = new AnalysisRun(normalizer.normalize(request));
        // 自动返工是运行级策略，不属于用户需求语义；创建 Clarifier 草稿时先持久化，启动时直接读取。
        run.setMaxReviewReworkAttempts(normalizeReviewReworkAttempts(request.getMaxReviewReworkAttempts()));
        // Clarifier 作为主流程前置 Agent 单独运行：保留回放记录，但不进入长耗时分析 DAG。
        run.setStatus(AnalysisStatus.AWAITING_CONFIRMATION);
        run.setClarificationDraft(buildClarificationDraft(run.getRequirement()));
        repository.save(run);
        eventBroker.publish(run, "run_created", "分析任务已创建");
        return run;
    }

    private AnalysisRun executeClarifier(UUID runId, String inputSummary, String readyMessage) {
        AnalysisRun run = get(runId);
        try {
            run = nodeExecutor.executeNode(runId, clarifierNode, inputSummary);
            eventBroker.publish(run, "clarification_ready", readyMessage);
            return run;
        } catch (CancellationException ex) {
            run = repository.findById(runId).orElse(run);
            if (run.getStatus() != AnalysisStatus.CANCELLED) {
                run.setStatus(AnalysisStatus.CANCELLED);
                repository.save(run);
            }
            eventBroker.publish(run, "run_cancelled", "澄清流程已取消");
            return run;
        } catch (RuntimeException ex) {
            run = repository.findById(runId).orElse(run);
            run.setStatus(AnalysisStatus.FAILED);
            run.setErrorMessage(ex.getMessage());
            repository.save(run);
            eventBroker.publish(run, "run_failed", ex.getMessage());
            return run;
        }
    }

    public AnalysisRun get(UUID runId) {
        return repository.findById(runId).orElseThrow(() -> new RunNotFoundException(runId));
    }

    public Collection<AnalysisRun> list() {
        return repository.findAll();
    }

    public Collection<AnalysisRunSummary> listSummaries() {
        return repository.findSummaries();
    }

    public void delete(UUID runId) {
        if (!repository.existsById(runId)) {
            throw new RunNotFoundException(runId);
        }
        repository.deleteById(runId);
        eventBroker.close(runId);
    }

    public Collection<AgentTrace> traces(UUID runId) {
        return get(runId).getTraces();
    }

    public AnalysisRunMetrics metrics(UUID runId) {
        AnalysisRun run = get(runId);
        int claimCount = run.getClaims().size();
        int citedClaims = (int) run.getClaims().stream()
                .filter(claim -> claim.getEvidenceIds() != null && !claim.getEvidenceIds().isEmpty())
                .count();
        int profileCount = run.getCompetitorProfiles().size();
        int completeProfiles = (int) run.getCompetitorProfiles().stream()
                .filter(this::isCompleteProfile)
                .count();
        // Count historical final reports for old runs; current runs use REPORT_DRAFT as the report artifact.
        int citationMentions = run.getArtifacts().stream()
                .filter(artifact -> artifact.getType() == ArtifactType.FINAL_REPORT
                        || artifact.getType() == ArtifactType.REPORT_DRAFT)
                .mapToInt(this::countCitationMentions)
                .sum();
        int reworkCount = (int) run.getWorkflowTransitions().stream()
                .filter(transition -> StringUtils.hasText(transition.getRoute()))
                .filter(transition -> !"finish".equalsIgnoreCase(transition.getRoute()))
                .count();
        int totalTokens = run.getTraces().stream()
                .mapToInt(this::traceTotalTokens)
                .sum();
        long totalLatencyMs = run.getTraces().stream()
                .mapToLong(trace -> trace.getLatencyMs() == null ? 0 : trace.getLatencyMs())
                .sum();

        return new AnalysisRunMetrics(
                run.getId(),
                run.getSteps().size(),
                run.getEvidenceSources().size(),
                run.getReviewFindings().size(),
                citationMentions,
                percent(citedClaims, claimCount),
                percent(completeProfiles, profileCount),
                reworkCount,
                claimCount == 0 ? 0 : round(run.getEvidenceSources().size() / (double) claimCount, 1),
                totalTokens,
                totalLatencyMs,
                countFindings(run, ReviewSeverity.HIGH),
                countFindings(run, ReviewSeverity.MEDIUM),
                countFindings(run, ReviewSeverity.LOW),
                latestImprovement(run.getLastReviewRepairDelta())
        );
    }

    private AnalysisRunMetrics.LatestImprovementMetrics latestImprovement(ReviewRepairDelta delta) {
        if (delta == null) {
            return null;
        }
        return new AnalysisRunMetrics.LatestImprovementMetrics(
                delta.getAgentName(),
                delta.isChanged(),
                delta.getRecordedAt(),
                delta.getEvidenceSourcesBefore(),
                delta.getEvidenceSourcesAfter(),
                delta.getEvidenceSourcesAfter() - delta.getEvidenceSourcesBefore(),
                delta.getCoverageGapsBefore(),
                delta.getCoverageGapsAfter(),
                delta.getCoverageGapsAfter() - delta.getCoverageGapsBefore(),
                delta.getFindingsBefore(),
                delta.getFindingsAfter(),
                delta.getFindingsAfter() - delta.getFindingsBefore(),
                delta.getHighFindingsBefore(),
                delta.getHighFindingsAfter(),
                delta.getHighFindingsAfter() - delta.getHighFindingsBefore(),
                delta.getClaimCoverageBefore(),
                delta.getClaimCoverageAfter(),
                delta.getClaimCoverageAfter() - delta.getClaimCoverageBefore()
        );
    }

    public Collection<EvidenceChunk> retrieveEvidence(UUID runId, String query, Integer topK) {
        AnalysisRun run = get(runId);
        int sourceCount = run.getEvidenceSources().size();
        Collection<EvidenceChunk> chunks = evidenceRetrievalService.retrieve(run, query, topK);
        if (run.getEvidenceSources().size() != sourceCount) {
            // 检索全局 RAG 时可能会把 global-document 来源挂载到当前 run，
            // 这里保存一次，让后续 Extractor/Analyst/前端证据面板都能看到本地 citation。
            repository.save(run);
        }
        return chunks;
    }

    public ResearchCollectionPlan researchCollectionPlan(UUID runId) {
        AnalysisRun run = get(runId);
        return run.getResearchPackage().getResearchCollectionPlan();
    }

    public Collection<ResearchSubtask> researchSubtasks(UUID runId,
                                                        String status,
                                                        String competitorName,
                                                        String dimension) {
        ResearchCollectionPlan plan = researchCollectionPlan(runId);
        if (plan == null || plan.getSubtasks() == null) {
            return List.of();
        }
        return plan.getSubtasks().stream()
                .filter(subtask -> !StringUtils.hasText(status)
                        || subtask.getStatus() != null && status.equalsIgnoreCase(subtask.getStatus().name()))
                .filter(subtask -> !StringUtils.hasText(competitorName)
                        || containsIgnoreCase(subtask.getCompetitorName(), competitorName))
                .filter(subtask -> !StringUtils.hasText(dimension)
                        || containsIgnoreCase(subtask.getDimension(), dimension))
                .toList();
    }

    public Collection<ResearchCoverageGap> researchCoverageGaps(UUID runId,
                                                                String competitorName,
                                                                String dimension) {
        ResearchCollectionPlan plan = researchCollectionPlan(runId);
        if (plan == null || plan.getCoverageGaps() == null) {
            return List.of();
        }
        return plan.getCoverageGaps().stream()
                .filter(gap -> !StringUtils.hasText(competitorName)
                        || containsIgnoreCase(gap.getCompetitorName(), competitorName))
                .filter(gap -> !StringUtils.hasText(dimension)
                        || containsIgnoreCase(gap.getDimension(), dimension))
                .toList();
    }

    public Collection<ResearchRepairTarget> researchRepairTargets(UUID runId,
                                                                  String competitorName,
                                                                  String dimension,
                                                                  String status) {
        ResearchCollectionPlan plan = researchCollectionPlan(runId);
        if (plan == null || plan.getRepairTargets() == null) {
            return List.of();
        }
        return plan.getRepairTargets().stream()
                .filter(target -> !StringUtils.hasText(competitorName)
                        || containsIgnoreCase(target.getCompetitorName(), competitorName))
                .filter(target -> !StringUtils.hasText(dimension)
                        || containsIgnoreCase(target.getDimension(), dimension))
                .filter(target -> !StringUtils.hasText(status)
                        || status.equalsIgnoreCase(target.getStatus()))
                .toList();
    }

    public AnalysisRun updateRequirement(UUID runId, UpdateAnalysisRequirementRequest request) {
        AnalysisRun run = get(runId);
        ensureRequirementEditable(run);
        AnalysisRequirement requirement = run.getRequirement();
        if (requirement == null) {
            requirement = new AnalysisRequirement();
            run.setRequirement(requirement);
        }
        applyRequirementUpdate(requirement, request);
        applyRunOptions(run, request);

        ClarificationDraft draft = buildClarificationDraft(requirement);
        draft.setConfirmed(true);
        draft.setConfirmedAt(Instant.now());
        run.setClarificationDraft(draft);
        run.setStatus(AnalysisStatus.PENDING);

        repository.save(run);
        eventBroker.publish(run, "requirement_confirmed", "分析范围已确认");
        return run;
    }

    public AnalysisRun clarifyRequirement(UUID runId, UpdateAnalysisRequirementRequest request) {
        AnalysisRun run = get(runId);
        ensureRequirementEditable(run);
        AnalysisRequirement requirement = run.getRequirement();
        if (requirement == null) {
            requirement = new AnalysisRequirement();
            run.setRequirement(requirement);
        }
        applyRequirementUpdate(requirement, request);
        applyRunOptions(run, request);

        run.setClarificationDraft(buildClarificationDraft(requirement));
        run.setStatus(AnalysisStatus.AWAITING_CONFIRMATION);
        repository.save(run);
        eventBroker.publish(run, "clarification_requested", "范围重新澄清已请求");
        run = executeClarifier(run.getId(), "preflight clarification rerun", "澄清草稿已更新");
        return run;
    }

    public AnalysisRun startExecution(UUID runId) {
        AnalysisRun run = get(runId);
        ensureStartable(run);
        // 启动时允许把未显式确认的澄清草稿补记为已确认，兼容“一键开始”与“先确认再开始”两种入口。
        if (run.getClarificationDraft() != null && !run.getClarificationDraft().isConfirmed()) {
            run.getClarificationDraft().setConfirmed(true);
            run.getClarificationDraft().setConfirmedAt(Instant.now());
        }
        run.setStatus(AnalysisStatus.RUNNING);
        repository.save(run);
        eventBroker.publish(run, "run_start_requested", "分析工作流已请求启动");
        // LangGraph 执行可能包含外部采集和 LLM 调用，放到异步线程后接口可以立即返回当前 run 状态。
        analysisTaskExecutor.execute(() -> executePipeline(run.getId()));
        return get(runId);
    }

    public AnalysisRun addContext(UUID runId, AddAnalysisContextRequest request) {
        AnalysisRun run = get(runId);
        ensureContextAcceptable(run);
        if (request.getIntent() == null) {
            throw new IllegalArgumentException("context intent is required");
        }
        ContextIntent intent = request.getIntent();
        AnalysisContextMessage message = new AnalysisContextMessage(
                ContextRole.USER,
                intent,
                request.getContent(),
                request.getTargetAgent());
        run.getContextMessages().add(message);
        // ContextIntent 是前端到后端的结构化协作协议：不同意图会改变范围、补证据或生成重跑建议。
        applyContextIntent(run, message);
        repository.save(run);
        eventBroker.publish(run, "context_added", "上下文补充已写入");
        if (request.isStartAfterUpdate()) {
            return startExecution(runId);
        }
        return run;
    }

    public AnalysisRun addEvidence(UUID runId, AddUserEvidenceRequest request) {
        AnalysisRun run = get(runId);
        ensureEvidenceAcceptable(run);
        UserProvidedEvidence evidence = new UserProvidedEvidence(
                request.getTitle(),
                request.getSourceType(),
                request.getContent(),
                request.getUrl(),
                request.isSensitive());
        String citationKey = attachUserEvidence(run, evidence);
        if (isResearchInputType(request.getSourceType())) {
            refreshResearchInputInsights(run);
            markResearchInputPending(run, "新增调研资料 " + citationKey + " 待应用到分析链路");
        }
        repository.save(run);
        eventBroker.publish(run, "evidence_added", "用户补充资料已加入证据链：" + citationKey);
        return run;
    }

    @Autowired(required = false)
    public void setSurveyInsightExtractor(SurveyInsightExtractor surveyInsightExtractor) {
        if (surveyInsightExtractor != null) {
            this.surveyInsightExtractor = surveyInsightExtractor;
        }
    }

    @Autowired(required = false)
    public void setSurveyResultImportService(SurveyResultImportService surveyResultImportService) {
        if (surveyResultImportService != null) {
            this.surveyResultImportService = surveyResultImportService;
        }
    }

    public AnalysisRun updateSurveyQuestionnaire(UUID runId, Questionnaire questionnaire) {
        AnalysisRun run = get(runId);
        ensureAgentRerunnable(run);
        ResearchPlan plan = run.getResearchPackage().getResearchPlan();
        if (plan == null) {
            plan = new ResearchPlan();
            run.getResearchPackage().setResearchPlan(plan);
        }
        plan.setQuestionnaire(sanitizeQuestionnaire(runId, questionnaire));
        run.getRecommendedActions().add("Survey questionnaire updated. Download a fresh template before importing new survey results.");
        repository.save(run);
        eventBroker.publish(run, "survey_questionnaire_updated", "Survey questionnaire updated");
        return run;
    }

    public byte[] surveyQuestionnaireDsl(UUID runId) {
        AnalysisRun run = get(runId);
        Questionnaire questionnaire = questionnaireOrNull(run);
        if (!hasAnyUsableQuestion(questionnaire)) {
            throw new InvalidRunStateException(runId, "questionnaire is not ready; run Researcher first");
        }
        return surveyResultImportService.buildQuestionnaireDslText(run);
    }

    public AnalysisRun importSurveyResults(UUID runId, MultipartFile file) {
        AnalysisRun run = get(runId);
        ensureAgentRerunnable(run);
        Questionnaire questionnaire = questionnaireOrNull(run);
        SurveyResultBatch results = surveyResultImportService.importResults(questionnaire, file);
        String fileName = surveyImportFileName(file);
        SurveyResultImport resultImport = new SurveyResultImport();
        resultImport.setRunId(run.getId());
        resultImport.setBatchId(results.batchId());
        resultImport.setTitle(results.title());
        resultImport.setQuestionnaire(questionnaire);
        resultImport.setFileName(fileName);
        resultImport.setResultCount(results.responseCount());

        UserProvidedEvidence evidence = new UserProvidedEvidence(
                "Imported survey results - " + results.title(),
                "survey",
                results.rawText(),
                "survey-import://" + fileName,
                false);
        String citationKey = attachUserEvidence(run, evidence);
        resultImport.getEvidenceIds().add(citationKey);
        run.getResearchPackage().getSurveyResultImports().add(resultImport);
        refreshResearchInputInsights(run);
        markResearchInputPending(run, "Imported survey results as " + citationKey + "; click apply to rerun Extractor and downstream agents.");
        repository.save(run);
        eventBroker.publish(run, "survey_results_imported", "Survey results imported: " + citationKey);
        return run;
    }

    public AnalysisRun addDocument(UUID runId,
                                   MultipartFile file,
                                   String title,
                                   String sourceType,
                                   boolean sensitive,
                                   String notes) {
        return addDocument(runId, file, title, sourceType, sensitive, notes, false);
    }

    public AnalysisRun addDocument(UUID runId,
                                   MultipartFile file,
                                   String title,
                                   String sourceType,
                                   boolean sensitive,
                                   String notes,
                                   boolean globalResource) {
        AnalysisRun run = get(runId);
        ensureEvidenceAcceptable(run);
        String citationKey = nextCitationKey(run);
        markResearchInputPendingIfNeeded(run, sourceType, "新增调研文件 " + citationKey + " 待应用到分析链路");
        documentIngestionService.ingest(run, file, citationKey, title, sourceType, sensitive, notes, globalResource);
        if (!documentIngestionService.managesPersistence()) {
            repository.save(run);
        }
        eventBroker.publish(run, "document_added", (globalResource ? "文件已加入全局用户资源包：" : "文件已加入当前任务资源：") + citationKey);
        return run;
    }

    public AnalysisRun deleteUserResource(UUID runId, String citationKey) {
        AnalysisRun run = get(runId);
        ensureEvidenceAcceptable(run);
        EvidenceSource source = run.getEvidenceSources().stream()
                .filter(item -> citationKey.equals(item.getCitationKey()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("User resource not found: " + citationKey));
        if (!isUserDocumentResource(source)) {
            throw new InvalidRunStateException(runId, "only uploaded documents can be deleted: " + citationKey);
        }
        if (isProcessingDocument(source)) {
            // 删除后台仍在处理的文档会让异步线程找不到占位 source，容易留下半成品进度；
            // 等 READY/FAILED 后再删，路径更可预期。
            throw new InvalidRunStateException(runId, "document ingestion is still processing: " + citationKey);
        }
        // 只有顶部“用户资源包”的文档会带 globalResource；局部补充资料删除时不能误删全局 RAG。
        if (source.isGlobalResource()) {
            repository.deleteGlobalEvidence(globalDocumentUrl(source));
        }
        run.getEvidenceSources().removeIf(item -> citationKey.equals(item.getCitationKey()));
        run.getEvidenceChunks().removeIf(chunk -> citationKey.equals(chunk.getSourceCitationKey()));
        String documentUrl = "user-document://" + citationKey.toLowerCase(Locale.ROOT);
        run.getUserProvidedEvidence().removeIf(evidence -> documentUrl.equals(evidence.getUrl()));
        run.getResearchPackage().getSources().removeIf(item -> citationKey.equals(item.getCitationKey()));
        run.getResearchPackage().setCollectedAt(Instant.now());
        run.getRecommendedActions().add("用户资源 " + citationKey + " 已移除。建议重跑 EXTRACTOR、ANALYST 或 WRITER 刷新后续产物。");
        repository.save(run);
        eventBroker.publish(run, "document_deleted", "用户资源已从证据链移除：" + citationKey);
        return run;
    }

    public AnalysisRun cancel(UUID runId) {
        AnalysisRun run = get(runId);
        if (isTerminal(run.getStatus())) {
            throw new InvalidRunStateException(runId, "terminal run cannot be cancelled from " + run.getStatus());
        }
        run.setStatus(AnalysisStatus.CANCELLED);
        run.getRecommendedActions().add("任务已由用户取消。");
        repository.save(run);
        eventBroker.publish(run, "run_cancelled", "分析工作流已取消");
        return run;
    }

    public AnalysisRun rerunAgent(UUID runId, AgentName agentName) {
        ensureAgentRerunnable(get(runId));
        AgentName activeAgent = activeReruns.putIfAbsent(runId, agentName);
        if (activeAgent != null) {
            throw new InvalidRunStateException(runId, "agent rerun already in progress: " + activeAgent);
        }
        // 重跑前先移除已从全局资源库删除的来源，防止旧任务继续引用失效文件。
        AnalysisRun current = pruneUnavailableGlobalEvidence(get(runId));
        AnalysisStatus previousStatus = current.getStatus();
        try {
            ensureAgentRerunnable(current);
            current.setStatus(AnalysisStatus.REVISING);
            current.setErrorMessage(null);
            repository.save(current);
            eventBroker.publish(current, "agent_rerun_started", agentName + " rerun started");

            AnalysisRun run = graphWorkflow.rerunAgent(runId, agentName);
            run = repository.findById(runId).orElse(run);
            if (run.getStatus() == AnalysisStatus.CANCELLED) {
                eventBroker.publish(run, "run_cancelled", "重跑流程已取消");
                return run;
            }
            run.setStatus(statusAfterManualRerun(run, previousStatus, agentName));
            clearAppliedResearchInputPending(run, agentName);
            repository.save(run);
            eventBroker.publish(run, "agent_rerun_completed", agentName + " rerun completed");
            return run;
        } catch (CancellationException ex) {
            AnalysisRun run = repository.findById(runId).orElse(current);
            if (run.getStatus() != AnalysisStatus.CANCELLED) {
                run.setStatus(AnalysisStatus.CANCELLED);
                repository.save(run);
            }
            eventBroker.publish(run, "run_cancelled", "重跑流程已取消");
            throw ex;
        } catch (InvalidRunStateException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            AnalysisRun run = repository.findById(runId).orElse(current);
            run.setStatus(AnalysisStatus.FAILED);
            run.setErrorMessage(ex.getMessage());
            repository.save(run);
            eventBroker.publish(run, "agent_rerun_failed", ex.getMessage());
            throw ex;
        } finally {
            activeReruns.remove(runId, agentName);
        }
    }

    private AnalysisStatus statusAfterManualRerun(AnalysisRun run, AnalysisStatus previousStatus, AgentName agentName) {
        if (agentName == AgentName.CLARIFIER) {
            return previousStatus == AnalysisStatus.REVISING ? AnalysisStatus.AWAITING_CONFIRMATION : previousStatus;
        }
        return requiresUserInputAfterWorkflow(run) ? AnalysisStatus.NEEDS_USER_INPUT : AnalysisStatus.SUCCEEDED;
    }

    private void executePipeline(UUID runId) {
        AnalysisRun run = get(runId);
        if (run.getStatus() == AnalysisStatus.CANCELLED) {
            eventBroker.publish(run, "run_cancelled", "分析工作流启动前已取消");
            return;
        }
        run.setStatus(AnalysisStatus.RUNNING);
        repository.save(run);
        eventBroker.publish(run, "run_started", "分析工作流已启动");
        try {
            graphWorkflow.execute(runId);
            run = get(runId);
            if (run.getStatus() == AnalysisStatus.CANCELLED) {
                eventBroker.publish(run, "run_cancelled", "分析工作流已取消");
                return;
            }
            if (requiresUserInputAfterWorkflow(run)) {
                run.setStatus(AnalysisStatus.NEEDS_USER_INPUT);
                repository.save(run);
                eventBroker.publish(run, "run_needs_user_input", "分析工作流已封版，但仍有复核项需要人工确认");
            } else {
                run.setStatus(AnalysisStatus.SUCCEEDED);
                repository.save(run);
                eventBroker.publish(run, "run_succeeded", "分析工作流已完成");
            }
        } catch (CancellationException ex) {
            run = repository.findById(runId).orElse(run);
            if (run.getStatus() != AnalysisStatus.CANCELLED) {
                run.setStatus(AnalysisStatus.CANCELLED);
                repository.save(run);
            }
            eventBroker.publish(run, "run_cancelled", "分析工作流已取消");
        } catch (RuntimeException ex) {
            run = repository.findById(runId).orElse(run);
            run.setStatus(AnalysisStatus.FAILED);
            run.setErrorMessage(ex.getMessage());
            repository.save(run);
            eventBroker.publish(run, "run_failed", ex.getMessage());
        }
    }

    private boolean requiresUserInputAfterWorkflow(AnalysisRun run) {
        if (run.getReviewDecision() != null && run.getReviewDecision().getAction() != ReviewAction.PASS) {
            return true;
        }
        return run.getReviewFindings().stream()
                .anyMatch(finding -> finding.getSeverity() == ReviewSeverity.HIGH);
    }

    private boolean isCompleteProfile(CompetitorProfile profile) {
        if (profile == null) {
            return false;
        }
        return StringUtils.hasText(profile.getPositioning())
                && profile.getFeatureTree() != null
                && profile.getFeatureTree().getRoots() != null
                && !profile.getFeatureTree().getRoots().isEmpty()
                && profile.getPricingModel() != null
                && StringUtils.hasText(profile.getPricingModel().getStrategySummary())
                && profile.getPersonas() != null
                && !profile.getPersonas().isEmpty()
                && profile.getEvidenceIds() != null
                && !profile.getEvidenceIds().isEmpty();
    }

    private int countCitationMentions(AnalysisArtifact artifact) {
        if (artifact == null || artifact.getContent() == null) {
            return 0;
        }
        return (int) CITATION_PATTERN.matcher(artifact.getContent()).results().count();
    }

    private int traceTotalTokens(AgentTrace trace) {
        if (trace == null) {
            return 0;
        }
        if (trace.getTotalTokens() != null) {
            return trace.getTotalTokens();
        }
        return (trace.getPromptTokens() == null ? 0 : trace.getPromptTokens())
                + (trace.getCompletionTokens() == null ? 0 : trace.getCompletionTokens());
    }

    private int countFindings(AnalysisRun run, ReviewSeverity severity) {
        return (int) run.getReviewFindings().stream()
                .filter(finding -> finding.getSeverity() == severity)
                .count();
    }

    private Questionnaire questionnaireOrNull(AnalysisRun run) {
        if (run.getResearchPackage() == null || run.getResearchPackage().getResearchPlan() == null) {
            return null;
        }
        return run.getResearchPackage().getResearchPlan().getQuestionnaire();
    }

    private String surveyImportFileName(MultipartFile file) {
        String fileName = file == null ? null : file.getOriginalFilename();
        if (!StringUtils.hasText(fileName)) {
            return "survey-results";
        }
        return fileName.replace('\\', '/').replaceAll("^.*/", "").trim();
    }

    private Questionnaire sanitizeQuestionnaire(UUID runId, Questionnaire questionnaire) {
        if (questionnaire == null || !StringUtils.hasText(questionnaire.getTitle())) {
            throw new InvalidRunStateException(runId, "questionnaire title is required");
        }
        Questionnaire sanitized = new Questionnaire();
        sanitized.setTitle(questionnaire.getTitle().trim());
        sanitized.setTargetRespondents(trimToEmpty(questionnaire.getTargetRespondents()));
        sanitized.setRecommendedSampleSize(trimToEmpty(questionnaire.getRecommendedSampleSize()));
        if (questionnaire.getQuestions() != null) {
            questionnaire.getQuestions().stream()
                    .map(this::sanitizeSurveyQuestion)
                    .filter(this::isUsableQuestion)
                    .limit(20)
                    .forEach(sanitized.getQuestions()::add);
        }
        if (sanitized.getQuestions().isEmpty()) {
            throw new InvalidRunStateException(runId, "questionnaire requires at least one question with two options");
        }
        return sanitized;
    }

    private SurveyQuestion sanitizeSurveyQuestion(SurveyQuestion question) {
        SurveyQuestion sanitized = new SurveyQuestion();
        if (question == null) {
            return sanitized;
        }
        sanitized.setDimension(trimToEmpty(question.getDimension()));
        sanitized.setQuestion(trimToEmpty(question.getQuestion()));
        if (question.getOptions() != null) {
            question.getOptions().stream()
                    .filter(StringUtils::hasText)
                    .map(String::trim)
                    .distinct()
                    .limit(12)
                    .forEach(sanitized.getOptions()::add);
        }
        return sanitized;
    }

    private boolean isUsableQuestion(SurveyQuestion question) {
        return question != null
                && StringUtils.hasText(question.getQuestion())
                && question.getOptions() != null
                && question.getOptions().size() >= 2;
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private boolean hasAnyUsableQuestion(Questionnaire questionnaire) {
        return questionnaire != null
                && StringUtils.hasText(questionnaire.getTitle())
                && questionnaire.getQuestions() != null
                && questionnaire.getQuestions().stream().anyMatch(this::isUsableQuestion);
    }

    private int percent(int part, int total) {
        if (total == 0) {
            return 0;
        }
        return Math.round((part * 100f) / total);
    }

    private double round(double value, int precision) {
        double factor = Math.pow(10, precision);
        return Math.round(value * factor) / factor;
    }

    private void ensureRequirementEditable(AnalysisRun run) {
        if (run.getStatus() == AnalysisStatus.RUNNING || run.getStatus() == AnalysisStatus.REVIEWING
                || run.getStatus() == AnalysisStatus.REVISING || run.getStatus() == AnalysisStatus.SUCCEEDED
                || run.getStatus() == AnalysisStatus.CANCELLED) {
            throw new InvalidRunStateException(run.getId(), "requirement cannot be updated from " + run.getStatus());
        }
    }

    private void ensureStartable(AnalysisRun run) {
        if (run.getStatus() == AnalysisStatus.RUNNING || run.getStatus() == AnalysisStatus.REVIEWING
                || run.getStatus() == AnalysisStatus.REVISING) {
            throw new InvalidRunStateException(run.getId(), "workflow is already running");
        }
        if (run.getStatus() == AnalysisStatus.SUCCEEDED || run.getStatus() == AnalysisStatus.CANCELLED) {
            throw new InvalidRunStateException(run.getId(), "workflow cannot be started from " + run.getStatus());
        }
        // 首次启动也必须等待上传文档完成解析；否则 Researcher/Extractor 会读到空的占位 source。
        if (hasProcessingDocuments(run)) {
            throw new InvalidRunStateException(run.getId(), "document ingestion is still processing; start after uploaded documents are ready");
        }
    }

    private void ensureContextAcceptable(AnalysisRun run) {
        if (run.getStatus() == AnalysisStatus.RUNNING || run.getStatus() == AnalysisStatus.REVIEWING
                || run.getStatus() == AnalysisStatus.REVISING || run.getStatus() == AnalysisStatus.CANCELLED) {
            throw new InvalidRunStateException(run.getId(), "context cannot be added from " + run.getStatus());
        }
    }

    private void ensureEvidenceAcceptable(AnalysisRun run) {
        if (run.getStatus() == AnalysisStatus.RUNNING || run.getStatus() == AnalysisStatus.REVIEWING
                || run.getStatus() == AnalysisStatus.REVISING || run.getStatus() == AnalysisStatus.CANCELLED) {
            throw new InvalidRunStateException(run.getId(), "evidence cannot be added from " + run.getStatus());
        }
    }

    private void ensureAgentRerunnable(AnalysisRun run) {
        if (run.getStatus() == AnalysisStatus.RUNNING || run.getStatus() == AnalysisStatus.REVIEWING
                || run.getStatus() == AnalysisStatus.REVISING) {
            throw new InvalidRunStateException(run.getId(), "agent cannot be rerun while workflow is " + run.getStatus());
        }
        if (run.getStatus() == AnalysisStatus.CANCELLED) {
            throw new InvalidRunStateException(run.getId(), "cancelled run cannot rerun agents");
        }
        if (hasProcessingDocuments(run)) {
            throw new InvalidRunStateException(run.getId(), "document ingestion is still processing; rerun after uploaded documents are ready");
        }
    }

    private boolean hasProcessingDocuments(AnalysisRun run) {
        return run.getEvidenceSources().stream()
                .anyMatch(this::isProcessingDocument);
    }

    private boolean isProcessingDocument(EvidenceSource source) {
        return source != null
                && source.getUrl() != null
                && source.getUrl().startsWith("user-document://")
                && DocumentIngestionService.STATUS_PROCESSING.equals(source.getIngestionStatus());
    }

    private boolean isTerminal(AnalysisStatus status) {
        return status == AnalysisStatus.SUCCEEDED || status == AnalysisStatus.FAILED
                || status == AnalysisStatus.CANCELLED;
    }

    private ClarificationDraft buildClarificationDraft(AnalysisRequirement requirement) {
        return fallbackClarificationDraftFactory.build(requirement);
    }

    private void applyRequirementUpdate(AnalysisRequirement requirement, UpdateAnalysisRequirementRequest request) {
        if (request.industryProvided()) {
            requirement.setIndustry(request.getIndustry());
        }
        if (request.competitorsProvided()) {
            requirement.setCompetitors(new ArrayList<>(request.getCompetitors()));
        }
        if (request.dimensionsProvided()) {
            requirement.setDimensions(new ArrayList<>(request.getDimensions()));
        }
        if (request.sourceUrlsProvided()) {
            requirement.setSourceUrls(new ArrayList<>(request.getSourceUrls()));
        }
        if (request.outputGoalProvided()) {
            requirement.setOutputGoal(request.getOutputGoal());
        }
    }

    private void applyRunOptions(AnalysisRun run, UpdateAnalysisRequirementRequest request) {
        // 前端可能在确认、重澄清或启动前保存执行选项；这里统一归一化，防止绕过 UI 后形成长循环。
        if (request.maxReviewReworkAttemptsProvided()) {
            run.setMaxReviewReworkAttempts(normalizeReviewReworkAttempts(request.getMaxReviewReworkAttempts()));
        }
    }

    private int normalizeReviewReworkAttempts(Integer attempts) {
        if (attempts == null) {
            return 1;
        }
        return Math.max(0, Math.min(attempts, 2));
    }

    private void applyContextIntent(AnalysisRun run, AnalysisContextMessage message) {
        if (message.getIntent() == ContextIntent.ADJUST_SCOPE) {
            // 范围调整会退回确认态，防止旧范围下的产物继续被当成最终结论使用。
            applyScopeHints(run.getRequirement(), message.getContent());
            run.setClarificationDraft(buildClarificationDraft(run.getRequirement()));
            run.getRecommendedActions().add("请先复核更新后的分析范围，再重新启动工作流。");
            run.setStatus(AnalysisStatus.AWAITING_CONFIRMATION);
        } else if (message.getIntent() == ContextIntent.ADD_EVIDENCE) {
            // 用户补充材料进入同一条 citation 链，后续报告和 Reviewer 可以继续按证据编号追溯。
            String citationKey = attachUserEvidence(run, new UserProvidedEvidence(
                    "User context evidence",
                    "note",
                    message.getContent(),
                    "",
                    false));
            run.getRecommendedActions().add("上下文证据 " + citationKey + " 已准备好，可用于后续重跑。");
        } else if (message.getIntent() == ContextIntent.REQUEST_RERUN && message.getTargetAgent() != null) {
            run.getRecommendedActions().add("请在复核新增上下文后重跑 " + message.getTargetAgent().name() + "。");
        } else if (message.getIntent() == ContextIntent.REVISE_REPORT) {
            run.getRecommendedActions().add("请重跑 WRITER，以纳入用户要求的报告修订。");
        }
    }

    private String attachUserEvidence(AnalysisRun run, UserProvidedEvidence evidence) {
        run.getUserProvidedEvidence().add(evidence);
        String citationKey = nextCitationKey(run);
        EvidenceSource source = sourceCollectionService.fromUserProvidedEvidence(citationKey, evidence);
        run.getEvidenceSources().add(source);
        run.getEvidenceChunks().addAll(evidenceEmbeddingService.embedChunks(evidenceChunkService.chunk(List.of(source))));
        if (isSurveyEvidenceType(source.getSourceType())) {
            keepOnlyLatestSurveyEvidence(run, source, evidence);
        }
        run.getResearchPackage().setSources(new ArrayList<>(run.getEvidenceSources()));
        run.getResearchPackage().setCollectedAt(Instant.now());
        run.getRecommendedActions().add("用户证据 " + citationKey + " 已加入。可重跑 RESEARCHER 或下游 Agent 刷新输出。");
        return citationKey;
    }

    private void keepOnlyLatestSurveyEvidence(AnalysisRun run, EvidenceSource latestSource, UserProvidedEvidence latestEvidence) {
        Set<String> removedCitationKeys = new LinkedHashSet<>();
        Set<String> removedUrls = new LinkedHashSet<>();
        run.getEvidenceSources().removeIf(source -> {
            if (source == latestSource || !isSurveyEvidenceType(source.getSourceType())) {
                return false;
            }
            if (StringUtils.hasText(source.getCitationKey())) {
                removedCitationKeys.add(source.getCitationKey());
            }
            if (StringUtils.hasText(source.getUrl())) {
                removedUrls.add(source.getUrl());
            }
            return true;
        });
        if (removedCitationKeys.isEmpty() && removedUrls.isEmpty()) {
            return;
        }
        run.getEvidenceChunks().removeIf(chunk ->
                removedCitationKeys.contains(chunk.getSourceCitationKey())
                        || removedUrls.contains(chunk.getUrl())
                        || (isSurveyEvidenceType(chunk.getSourceType())
                        && !latestSource.getCitationKey().equals(chunk.getSourceCitationKey())));
        run.getResearchPackage().getSources().removeIf(source ->
                removedCitationKeys.contains(source.getCitationKey()) || removedUrls.contains(source.getUrl()));
        run.getUserProvidedEvidence().removeIf(item -> item != latestEvidence && isSurveyEvidenceType(item.getSourceType()));
        run.getRecommendedActions().add("已用最新问卷结果替换旧问卷证据：" + String.join("、", removedCitationKeys));
    }

    private boolean isSurveyEvidenceType(String sourceType) {
        return containsIgnoreCase(sourceType, "survey");
    }

    private void markResearchInputPendingIfNeeded(AnalysisRun run, String sourceType, String reason) {
        if (isResearchInputType(sourceType)) {
            markResearchInputPending(run, reason);
        }
    }

    private void markResearchInputPending(AnalysisRun run, String reason) {
        run.setPendingResearchInputRevision(true);
        run.setPendingResearchInputReason(reason);
        run.getRecommendedActions().add(reason);
        run.touch();
    }

    private void refreshResearchInputInsights(AnalysisRun run) {
        run.getResearchPackage().setInterviewInsights(interviewInsightExtractor.extract(run));
        run.getResearchPackage().setSurveyInsights(surveyInsightExtractor.extract(run));
        run.getResearchPackage().setCollectedAt(Instant.now());
    }

    private void clearAppliedResearchInputPending(AnalysisRun run, AgentName agentName) {
        if (!run.isPendingResearchInputRevision()) {
            return;
        }
        if (agentName == AgentName.RESEARCHER || agentName == AgentName.EXTRACTOR) {
            run.setPendingResearchInputRevision(false);
            run.setPendingResearchInputReason(null);
            run.getRecommendedActions().add("新调研数据已通过 " + agentName.name() + " 重跑应用到分析链路。");
            run.touch();
        }
    }

    private boolean isResearchInputType(String sourceType) {
        return containsIgnoreCase(sourceType, "survey")
                || containsIgnoreCase(sourceType, "interview");
    }

    private boolean isUserDocumentResource(EvidenceSource source) {
        String url = source.getUrl() == null ? "" : source.getUrl();
        return url.startsWith("user-document://");
    }

    private AnalysisRun pruneUnavailableGlobalEvidence(AnalysisRun run) {
        Set<String> removedCitationKeys = new LinkedHashSet<>();
        Set<String> removedUrls = new LinkedHashSet<>();
        // 旧 run 中的全局资源可能是 global-document://，也可能是带 globalResource 标记的
        // user-document://；统一映射到 global URL 后再查全局库是否仍存在。
        run.getEvidenceSources().removeIf(source -> {
            if (!isGlobalScopedSource(source)) {
                return false;
            }
            String globalUrl = globalDocumentUrl(source);
            if (repository.globalEvidenceExists(globalUrl)) {
                return false;
            }
            if (StringUtils.hasText(source.getCitationKey())) {
                removedCitationKeys.add(source.getCitationKey());
            }
            if (StringUtils.hasText(source.getUrl())) {
                removedUrls.add(source.getUrl());
            }
            removedUrls.add(globalUrl);
            return true;
        });
        if (removedCitationKeys.isEmpty() && removedUrls.isEmpty()) {
            return run;
        }
        // 清理不只删 EvidenceSource。run 内还有 chunk、researchPackage.sources、
        // userProvidedEvidence 等多处引用，必须一起收口，避免旧 citation 在重跑后继续漏出来。
        run.getEvidenceChunks().removeIf(chunk ->
                removedCitationKeys.contains(chunk.getSourceCitationKey()) || removedUrls.contains(chunk.getUrl()));
        run.getResearchPackage().getSources().removeIf(source ->
                removedCitationKeys.contains(source.getCitationKey())
                        || removedUrls.contains(source.getUrl())
                        || removedUrls.contains(globalDocumentUrl(source)));
        run.getUserProvidedEvidence().removeIf(evidence -> removedUrls.contains(evidence.getUrl()));
        run.getResearchPackage().setSources(new ArrayList<>(run.getEvidenceSources()));
        run.getResearchPackage().setCollectedAt(Instant.now());
        run.getRecommendedActions().add("已移除全局资源库中已删除的来源：" + String.join("、", removedCitationKeys));
        repository.save(run);
        return run;
    }

    private boolean isGlobalScopedSource(EvidenceSource source) {
        return source != null
                && (source.isGlobalResource() || isGlobalDocumentUrl(source.getUrl()));
    }

    private boolean isGlobalDocumentUrl(String url) {
        return url != null && url.startsWith("global-document://");
    }

    private String globalDocumentUrl(EvidenceSource source) {
        if (source == null) {
            return "";
        }
        if (isGlobalDocumentUrl(source.getUrl())) {
            return source.getUrl();
        }
        // 对 user-document://Sx 这种当前任务内资源，用标题 + 原文重新计算全局 URL。
        // 这个算法和 DocumentIngestionService 中的保存路径是一组契约，改一处必须改另一处。
        String hashInput = String.join("\n",
                source.getTitle() == null ? "" : source.getTitle(),
                source.getRawText() == null ? "" : source.getRawText()
        );
        return "global-document://" + sha256(hashInput);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append("%02x".formatted(b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private void applyScopeHints(AnalysisRequirement requirement, String content) {
        if (requirement == null || content == null) {
            return;
        }
        List<String> mentionedCompetitors = normalizer.extractMentionedCompetitors(content);
        if (!mentionedCompetitors.isEmpty()) {
            requirement.getCompetitors().removeIf(value -> "竞品 A".equals(value) || "竞品 B".equals(value));
            mentionedCompetitors.forEach(competitor -> appendUnique(requirement.getCompetitors(), competitor));
        }

        appendIfMentionedAny(requirement.getDimensions(), content, "AI 搜索", "AI 搜索", "ai search", "智能搜索");
        appendIfMentionedAny(requirement.getDimensions(), content, "权限协作", "权限协作", "权限", "permission collaboration",
                "access control");
        appendIfMentionedAny(requirement.getDimensions(), content, "价格策略", "价格策略", "定价", "价格", "pricing");
        appendIfMentionedAny(requirement.getDimensions(), content, "用户评价", "用户评价", "公开评价", "评论", "口碑", "review");
        appendIfMentionedAny(requirement.getDimensions(), content, "产品定位", "产品定位", "定位");
        appendIfMentionedAny(requirement.getDimensions(), content, "核心功能", "核心功能", "功能对比", "功能");
        appendIfMentionedAny(requirement.getDimensions(), content, "商业模式", "商业模式", "付费模式", "business model");
        appendIfMentionedAny(requirement.getDimensions(), content, "风险提示", "风险", "风险提示", "threat");

        appendIfMentionedAny(requirement.getSourcePreferences(), content, "official_site", "官网", "官方网站",
                "official site");
        appendIfMentionedAny(requirement.getSourcePreferences(), content, "pricing_page", "价格页", "定价页", "pricing page",
                "pricing_page");
        appendIfMentionedAny(requirement.getSourcePreferences(), content, "product_docs", "产品文档", "帮助文档", "docs",
                "product docs");
        appendIfMentionedAny(requirement.getSourcePreferences(), content, "release_notes", "更新日志", "release notes",
                "changelog");
        appendIfMentionedAny(requirement.getSourcePreferences(), content, "public_reviews", "公开评价", "用户评价",
                "public reviews", "public_reviews");
    }

    private void appendIfMentionedAny(List<String> values, String content, String value, String... patterns) {
        if (!StringUtils.hasText(content)) {
            return;
        }
        for (String pattern : patterns) {
            if (content.toLowerCase().contains(pattern.toLowerCase())) {
                appendUnique(values, value);
                return;
            }
        }
    }

    private void appendUnique(List<String> values, String value) {
        boolean exists = values.stream().anyMatch(item -> item.equalsIgnoreCase(value));
        if (!exists) {
            values.add(value);
        }
    }

    private String nextCitationKey(AnalysisRun run) {
        int max = run.getEvidenceSources().stream()
                .map(EvidenceSource::getCitationKey)
                .filter(StringUtils::hasText)
                .mapToInt(this::citationNumber)
                .max()
                .orElse(0);
        return "S" + (max + 1);
    }

    private int citationNumber(String citationKey) {
        if (!citationKey.startsWith("S")) {
            return 0;
        }
        try {
            return Integer.parseInt(citationKey.substring(1));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private boolean containsIgnoreCase(String text, String pattern) {
        return text != null
                && pattern != null
                && text.toLowerCase(java.util.Locale.ROOT).contains(pattern.toLowerCase(java.util.Locale.ROOT));
    }
}
