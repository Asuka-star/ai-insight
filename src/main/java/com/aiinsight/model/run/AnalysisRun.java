package com.aiinsight.model.run;

import com.aiinsight.model.enums.AnalysisStatus;
import com.aiinsight.model.review.ReviewDecision;
import com.aiinsight.model.review.ReviewFinding;
import com.aiinsight.model.schema.AnalysisClaim;
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
    private List<CompetitorProfile> competitorProfiles = new ArrayList<>();
    private List<AnalysisClaim> claims = new ArrayList<>();
    private ReviewDecision reviewDecision = new ReviewDecision();
    // AgentTrace 后续用于记录 Prompt、输入输出、模型和 token 消耗，支撑可观测性评分项。
    private List<AgentTrace> traces = new ArrayList<>();
    private List<WorkflowTransition> workflowTransitions = new ArrayList<>();
    private List<ReviewFinding> reviewFindings = new ArrayList<>();
    private List<String> recommendedActions = new ArrayList<>();
    private String errorMessage;
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();

    public AnalysisRun(AnalysisRequirement requirement) {
        this.requirement = requirement;
    }

    // 状态变化必须更新时间戳，前端可以用 updatedAt 做轻量轮询或排序。
    public void setStatus(AnalysisStatus status) {
        this.status = status;
        touch();
    }

    public void touch() {
        this.updatedAt = Instant.now();
    }
}
