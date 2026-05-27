package com.aiinsight.service;

import com.aiinsight.dto.AddAnalysisContextRequest;
import com.aiinsight.dto.AddUserEvidenceRequest;
import com.aiinsight.dto.CreateAnalysisRunRequest;
import com.aiinsight.dto.UpdateAnalysisRequirementRequest;
import com.aiinsight.exception.InvalidRunStateException;
import com.aiinsight.exception.RunNotFoundException;
import com.aiinsight.model.enums.AgentName;
import com.aiinsight.model.enums.AnalysisStatus;
import com.aiinsight.model.enums.ContextIntent;
import com.aiinsight.model.enums.ContextRole;
import com.aiinsight.model.run.AgentTrace;
import com.aiinsight.model.run.AnalysisContextMessage;
import com.aiinsight.model.run.AnalysisRequirement;
import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.model.run.ClarificationDraft;
import com.aiinsight.model.run.EvidenceChunk;
import com.aiinsight.model.run.EvidenceSource;
import com.aiinsight.model.run.UserProvidedEvidence;
import com.aiinsight.repository.AnalysisRunRepository;
import com.aiinsight.workflow.AnalysisLangGraphWorkflow;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;

@Service
public class AnalysisWorkflowService {

    private final AnalysisRunRepository repository;
    private final AnalysisRequestNormalizer normalizer;
    private final AnalysisEventBroker eventBroker;
    private final AsyncTaskExecutor analysisTaskExecutor;
    private final AnalysisLangGraphWorkflow graphWorkflow;
    private final EvidenceRetrievalService evidenceRetrievalService;
    private final SourceCollectionService sourceCollectionService;
    private final EvidenceChunkService evidenceChunkService;

    public AnalysisWorkflowService(AnalysisRunRepository repository,
            AnalysisRequestNormalizer normalizer,
            AnalysisEventBroker eventBroker,
            AsyncTaskExecutor analysisTaskExecutor,
            AnalysisLangGraphWorkflow graphWorkflow,
            EvidenceRetrievalService evidenceRetrievalService,
            SourceCollectionService sourceCollectionService,
            EvidenceChunkService evidenceChunkService) {
        this.repository = repository;
        this.normalizer = normalizer;
        this.eventBroker = eventBroker;
        this.analysisTaskExecutor = analysisTaskExecutor;
        this.graphWorkflow = graphWorkflow;
        this.evidenceRetrievalService = evidenceRetrievalService;
        this.sourceCollectionService = sourceCollectionService;
        this.evidenceChunkService = evidenceChunkService;
    }

    public AnalysisRun createDraft(CreateAnalysisRunRequest request) {
        AnalysisRun run = new AnalysisRun(normalizer.normalize(request));
        // 创建阶段只生成可编辑草稿，不立即跑 Agent；前端会先让用户确认范围，避免“一句话任务”直接进入长流程。
        run.setStatus(AnalysisStatus.AWAITING_CONFIRMATION);
        run.setClarificationDraft(buildClarificationDraft(run.getRequirement()));
        repository.save(run);
        eventBroker.publish(run, "run_created", "分析任务已创建");
        eventBroker.publish(run, "clarification_ready", "澄清草稿已生成");
        return run;
    }

    public AnalysisRun start(CreateAnalysisRunRequest request) {
        AnalysisRun run = createDraft(request);
        return startExecution(run.getId());
    }

    public AnalysisRun get(UUID runId) {
        return repository.findById(runId).orElseThrow(() -> new RunNotFoundException(runId));
    }

    public Collection<AnalysisRun> list() {
        return repository.findAll();
    }

    public Collection<AgentTrace> traces(UUID runId) {
        return get(runId).getTraces();
    }

    public Collection<EvidenceChunk> retrieveEvidence(UUID runId, String query, Integer topK) {
        return evidenceRetrievalService.retrieve(get(runId), query, topK);
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

        ClarificationDraft draft = buildClarificationDraft(requirement);
        draft.setConfirmed(true);
        draft.setConfirmedAt(Instant.now());
        run.setClarificationDraft(draft);
        run.setStatus(AnalysisStatus.PENDING);

        repository.save(run);
        eventBroker.publish(run, "requirement_confirmed", "分析范围已确认");
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
        ContextIntent intent = request.getIntent() == null ? ContextIntent.COMMENT : request.getIntent();
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
        repository.save(run);
        eventBroker.publish(run, "evidence_added", "用户补充资料已加入证据链：" + citationKey);
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
        AnalysisRun current = get(runId);
        if (current.getStatus() == AnalysisStatus.CANCELLED) {
            throw new InvalidRunStateException(runId, "cancelled run cannot rerun agents");
        }
        AnalysisRun run = graphWorkflow.rerunAgent(runId, agentName);
        eventBroker.publish(run, "agent_rerun_completed", agentName + " rerun completed");
        return run;
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
            run.setStatus(AnalysisStatus.SUCCEEDED);
            repository.save(run);
            eventBroker.publish(run, "run_succeeded", "分析工作流已完成");
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

    private boolean isTerminal(AnalysisStatus status) {
        return status == AnalysisStatus.SUCCEEDED || status == AnalysisStatus.FAILED
                || status == AnalysisStatus.CANCELLED;
    }

    private ClarificationDraft buildClarificationDraft(AnalysisRequirement requirement) {
        ClarificationDraft draft = new ClarificationDraft(requirement);
        draft.getClarificationQuestions().addAll(clarificationQuestions(requirement));
        return draft;
    }

    // 这些问题是前端范围确认表单的确定性提示，不是 LLM 生成结果。
    private List<String> clarificationQuestions(AnalysisRequirement requirement) {
        List<String> questions = new ArrayList<>();
        if (requirement.getCompetitors().size() < 3) {
            questions.add("是否需要加入 Confluence、Airtable 等标杆产品作为对照？");
        }
        if (requirement.getSourceUrls().isEmpty()) {
            questions.add("是否有官网、价格页、产品文档、公开评价或访谈记录可以作为资料来源？");
        }
        if (!StringUtils.hasText(requirement.getOutputGoal())) {
            questions.add("这份报告主要用于支持什么决策：产品评审、规划立项，还是向上汇报？");
        }
        return questions;
    }

    private void applyRequirementUpdate(AnalysisRequirement requirement, UpdateAnalysisRequirementRequest request) {
        if (StringUtils.hasText(request.getIndustry())) {
            requirement.setIndustry(request.getIndustry());
        }
        if (!request.getCompetitors().isEmpty()) {
            requirement.setCompetitors(new ArrayList<>(request.getCompetitors()));
        }
        if (!request.getDimensions().isEmpty()) {
            requirement.setDimensions(new ArrayList<>(request.getDimensions()));
        }
        if (!request.getSourcePreferences().isEmpty()) {
            requirement.setSourcePreferences(new ArrayList<>(request.getSourcePreferences()));
        }
        if (!request.getSourceUrls().isEmpty()) {
            requirement.setSourceUrls(new ArrayList<>(request.getSourceUrls()));
        }
        if (StringUtils.hasText(request.getOutputGoal())) {
            requirement.setOutputGoal(request.getOutputGoal());
        }
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
        run.getEvidenceChunks().addAll(evidenceChunkService.chunk(List.of(source)));
        run.getResearchPackage().setSources(new ArrayList<>(run.getEvidenceSources()));
        run.getResearchPackage().setCollectedAt(Instant.now());
        run.getRecommendedActions().add("用户证据 " + citationKey + " 已加入。可重跑 RESEARCHER 或下游 Agent 刷新输出。");
        return citationKey;
    }

    private void applyScopeHints(AnalysisRequirement requirement, String content) {
        if (requirement == null || content == null) {
            return;
        }
        appendIfMentionedAny(requirement.getCompetitors(), content, "Notion", "notion");
        appendIfMentionedAny(requirement.getCompetitors(), content, "飞书文档", "飞书文档", "飞书 docs", "feishu docs",
                "lark docs");
        appendIfMentionedAny(requirement.getCompetitors(), content, "钉钉文档", "钉钉文档", "dingdocs");
        appendIfMentionedAny(requirement.getCompetitors(), content, "语雀", "语雀", "yuque");
        appendIfMentionedAny(requirement.getCompetitors(), content, "Confluence", "confluence");
        appendIfMentionedAny(requirement.getCompetitors(), content, "Airtable", "airtable");
        appendIfMentionedAny(requirement.getCompetitors(), content, "腾讯文档", "腾讯文档", "tencent docs");

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
}
