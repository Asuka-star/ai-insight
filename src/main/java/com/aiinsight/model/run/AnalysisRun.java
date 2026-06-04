package com.aiinsight.model.run;

import com.aiinsight.model.enums.AnalysisStatus;
import com.aiinsight.model.enums.AgentName;
import com.aiinsight.model.enums.ArtifactType;
import com.aiinsight.model.enums.ReviewAction;
import com.aiinsight.model.review.ReviewDecision;
import com.aiinsight.model.review.ReviewFinding;
import com.aiinsight.model.review.ReviewRepairTask;
import com.aiinsight.model.schema.AnalysisClaim;
import com.aiinsight.model.schema.CompetitorFactSet;
import com.aiinsight.model.schema.CompetitorProfile;
import com.aiinsight.model.schema.ResearchPackage;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
// analysis_run 是工作台展示和故障恢复的核心聚合：所有 Agent 步骤、证据、
// 中间产物和质检问题都挂在这里，避免最终报告变成不可解释的黑盒输出。
public class AnalysisRun {

    private UUID id = UUID.randomUUID();
    private AnalysisStatus status = AnalysisStatus.PENDING;
    private AnalysisRequirement requirement;
    // Agent 执行时间线，用于前端回放每个节点的输入、输出摘要和异常。
    private List<AgentStep> steps = new ArrayList<>();
    // 当前阶段先用内存对象表达来源片段，后续会映射到 pgvector / 文档块表。
    private List<EvidenceSource> evidenceSources = new ArrayList<>();
    // EvidenceChunk 是 RAG 和 pgvector 的最小检索单元，用于把长网页拆成可召回片段。
    private List<EvidenceChunk> evidenceChunks = new ArrayList<>();
    // 报告、竞品矩阵、复核结果等中间产物都以 artifact 形式保存，便于版本化和单节点重跑。
    private List<AnalysisArtifact> artifacts = new ArrayList<>();
    // 结构化 Schema 结果，供 Agent 间通过共享状态传递，而不是只解析 Markdown。
    private ResearchPackage researchPackage = new ResearchPackage();
    private List<CompetitorFactSet> competitorFactSets = new ArrayList<>();
    private List<CompetitorProfile> competitorProfiles = new ArrayList<>();
    private List<AnalysisClaim> claims = new ArrayList<>();
    private ClarificationDraft clarificationDraft = new ClarificationDraft();
    private List<AnalysisContextMessage> contextMessages = new ArrayList<>();
    private List<UserProvidedEvidence> userProvidedEvidence = new ArrayList<>();
    private ReviewDecision reviewDecision = new ReviewDecision();
    private ReviewDecision manualRerunDecision;
    private ReviewRepairDelta lastReviewRepairDelta;
    // AgentTrace 后续用于记录 Prompt、输入输出、模型和 token 消耗，支撑可观测性评分项。
    private List<AgentTrace> traces = new ArrayList<>();
    private List<WorkflowTransition> workflowTransitions = new ArrayList<>();
    private List<ReviewFinding> reviewFindings = new ArrayList<>();
    private List<String> recommendedActions = new ArrayList<>();
    private Integer maxReviewReworkAttempts;
    private String errorMessage;
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();

    public AnalysisRun(AnalysisRequirement requirement) {
        this.requirement = requirement;
    }

    public AnalysisArtifact addArtifact(AnalysisArtifact artifact) {
        if (artifact == null) {
            return null;
        }
        // Artifact 采用追加语义，保证重跑可审计；版本按类型递增，报告和质检结果可独立演进。
        artifact.setVersion(nextArtifactVersion(artifact.getType()));
        artifacts.add(artifact);
        touch();
        return artifact;
    }

    private int nextArtifactVersion(ArtifactType type) {
        if (type == null) {
            return 1;
        }
        return artifacts.stream()
                .filter(artifact -> artifact.getType() == type)
                .mapToInt(AnalysisArtifact::getVersion)
                .max()
                .orElse(0) + 1;
    }

    // 状态变化必须更新时间戳，前端可以用 updatedAt 做轻量轮询或排序。
    public void setStatus(AnalysisStatus status) {
        this.status = status;
        touch();
    }

    public void touch() {
        this.updatedAt = Instant.now();
    }

    public ReviewDecision getRepairDecisionFor(AgentName agentName) {
        ReviewDecision manualDecision = manualRerunDecisionFor(agentName);
        if (manualDecision != null) {
            return manualDecision;
        }
        return reviewDecision;
    }

    private ReviewDecision manualRerunDecisionFor(AgentName agentName) {
        if (manualRerunDecision == null
                || manualRerunDecision.getAction() == null
                || manualRerunDecision.getAction() == ReviewAction.PASS) {
            return null;
        }
        List<ReviewRepairTask> tasks = manualRerunDecision.getRepairTasks() == null
                ? List.of()
                : manualRerunDecision.getRepairTasks().stream()
                .filter(task -> task.getTargetAgent() == agentName)
                .toList();
        if (!isActiveDecisionFor(manualRerunDecision, agentName) && tasks.isEmpty()) {
            return null;
        }
        ReviewDecision decision = new ReviewDecision();
        decision.setAction(actionForAgent(agentName));
        decision.setTargetAgent(agentName);
        decision.setReason(manualRerunDecision.getReason());
        List<String> affectedClaimIds = tasks.stream()
                .map(ReviewRepairTask::getClaimId)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();
        List<String> findingCategories = tasks.stream()
                .map(ReviewRepairTask::getCategory)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();
        List<String> blockingFindingIds = tasks.stream()
                .map(ReviewRepairTask::getFindingId)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();
        List<String> requiredEvidenceTypes = new ArrayList<>(safeList(manualRerunDecision.getRequiredEvidenceTypes()));
        tasks.stream()
                .flatMap(task -> safeList(task.getRequiredEvidenceTypes()).stream())
                .filter(value -> value != null && !value.isBlank())
                .filter(value -> !requiredEvidenceTypes.contains(value))
                .forEach(requiredEvidenceTypes::add);
        decision.setAffectedClaimIds(affectedClaimIds.isEmpty()
                ? new ArrayList<>(safeList(manualRerunDecision.getAffectedClaimIds()))
                : affectedClaimIds);
        decision.setRequiredEvidenceTypes(requiredEvidenceTypes);
        decision.setFindingCategories(findingCategories.isEmpty()
                ? new ArrayList<>(safeList(manualRerunDecision.getFindingCategories()))
                : findingCategories);
        decision.setBlockingFindingIds(blockingFindingIds.isEmpty()
                ? new ArrayList<>(safeList(manualRerunDecision.getBlockingFindingIds()))
                : blockingFindingIds);
        decision.setRepairInstructions(new ArrayList<>(safeList(manualRerunDecision.getRepairInstructions())));
        decision.setRepairTasks(tasks);
        decision.setRepairScopeSummary(manualRerunDecision.getRepairScopeSummary());
        decision.setDecidedAt(manualRerunDecision.getDecidedAt());
        return decision;
    }

    private ReviewAction actionForAgent(AgentName agentName) {
        return switch (agentName) {
            case RESEARCHER -> ReviewAction.RECOLLECT_EVIDENCE;
            case EXTRACTOR, ANALYST -> ReviewAction.REWORK_ANALYSIS;
            case WRITER -> ReviewAction.REVISE_REPORT;
            case CLARIFIER, REVIEWER -> ReviewAction.PASS;
        };
    }

    private boolean isActiveDecisionFor(ReviewDecision decision, AgentName agentName) {
        return decision != null
                && decision.getAction() != null
                && decision.getAction() != ReviewAction.PASS
                && decision.getTargetAgent() == agentName;
    }

    private static <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }
}
