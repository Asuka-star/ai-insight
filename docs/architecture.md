# AI Insight 架构与 Agent 协议

## 1. 架构目标

AI Insight 的架构目标是把竞品分析从单次文本生成升级为一个可观测、可复核、可溯源、可重跑的多 Agent 工作流。

设计原则：

- 任务状态集中在 `AnalysisRun` 聚合中。
- Agent 通过结构化对象共享状态，而不是只传 Markdown。
- DAG 编排和反馈路由由 LangGraph4j 承载。
- 证据、结论、报告和质检结果都保留 citation 或定位线索。
- 前端以 `AnalysisRun` 为单一事实源。

## 2. 系统分层

```text
Frontend Workbench
  |
  | REST / SSE
  v
Spring Boot API
  |
  +-- AnalysisWorkflowService
  |     |
  |     +-- AnalysisLangGraphWorkflow
  |     |     |
  |     |     +-- WorkflowNodeExecutor
  |     |           |
  |     |           +-- AgentNode implementations
  |     |
  |     +-- SourceCollectionService
  |     +-- SearchProvider / TavilySearchProvider
  |     +-- EvidenceChunkService
  |     +-- EvidenceRetrievalService
  |     +-- CitationCoverageEvaluator
  |
  +-- AnalysisRunRepository
        |
        +-- PostgreSQL jsonb analysis_run payload
```

## 3. 前端工作台

前端采用三栏工作台：

```text
左侧：任务配置 / 范围确认 / 上下文补充 / 补充资料 / 指标
中间：Agent DAG / 最终报告 / 结构化 Schema / 竞品矩阵 / 报告版本
右侧：Agent 时间线 / 证据来源 / Reviewer 质检 / Mermaid 源码
```

关键组件：

- `App.tsx`：页面状态和 API 编排。
- `ScopeConfirmationPanel`：结构化范围确认。
- `ContextPanel`：上下文补充。
- `WorkflowGraph`：Agent DAG。
- `ArtifactViewer`：报告和矩阵展示，支持 citation hover。
- `SchemaPanel`：ResearchPackage、CompetitorProfile、AnalysisClaim、WorkflowTransition。
- `EvidencePanel`：证据来源。
- `ReviewPanel`：ReviewFinding 和 ReviewDecision。
- `TraceDrawer`：Agent Prompt、输入、输出、模型、token 和耗时。
- `ArtifactVersionsPanel`：artifact 版本记录。

## 4. 后端核心聚合

`AnalysisRun` 是核心聚合，包含：

- `AnalysisRequirement`：原始需求和结构化范围。
- `ClarificationDraft`：待确认的结构化范围。
- `AgentStep`：Agent 执行时间线。
- `AgentTrace`：Prompt、输入、输出、模型、token、耗时和异常。
- `EvidenceSource`：可引用来源。
- `EvidenceChunk`：检索切片。
- `ResearchPackage`：Researcher 输出资料包。
- `CompetitorProfile`：竞品画像。
- `AnalysisClaim`：结构化结论。
- `AnalysisArtifact`：报告、矩阵、SWOT、质检等产物。
- `ReviewFinding`：质检问题。
- `ReviewDecision`：复核决策。
- `WorkflowTransition`：DAG 条件边路由记录。
- `AnalysisContextMessage`：用户补充上下文。
- `UserProvidedEvidence`：用户补充资料。

## 5. Agent 协议

所有 Agent 实现统一接口：

```java
public interface AgentNode {
    AgentName name();
    String title();
    AnalysisRun execute(AnalysisRun run);
}
```

Agent 直接读取并更新 `AnalysisRun`：

- 不返回自由文本作为唯一状态。
- 关键中间结果写入结构化字段。
- 用户可在前端查看每个产物和 Trace。

### 5.1 前置范围确认

职责：

- 填写范围确认时生成 `ClarificationDraft`。
- 推荐用户确认竞品、维度和来源。
- 在主分析 DAG 启动前完成人机范围确认。

输入：

- `AnalysisRequirement`

输出：

- `ClarificationDraft`
- `recommendedActions`

说明：

- 这不是主分析 DAG 节点。
- 主流程启动后第一个 Agent 是 `RESEARCHER`。

### 5.2 RESEARCHER

职责：

- 收集用户资料、用户提供的公开 URL，并在搜索服务已配置时主动搜索公开网页。
- 生成可引用证据。
- 生成 evidence chunks。
- 生成调研计划、问卷草案和访谈提纲。

输入：

- `AnalysisRequirement.sourceUrls`
- `UserProvidedEvidence`
- `ReviewDecision.requiredEvidenceTypes`

输出：

- `EvidenceSource`
- `EvidenceChunk`
- `ResearchPackage`
- `ResearchPlan`
- `SOURCE_LIST` artifact。
- `RESEARCH_PLAN` artifact。

### 5.3 EXTRACTOR

职责：

- 从证据中抽取竞品结构化画像。

输出：

- `CompetitorProfile`
- `FeatureTree`
- `PricingModel`
- `UserPersona`
- `COMPETITOR_PROFILE` artifact。

### 5.4 ANALYST

职责：

- 生成横向矩阵。
- 生成结构化 Claim。
- 生成 SWOT 分析。

输出：

- `AnalysisClaim`
- `COMPETITIVE_MATRIX` artifact。
- `SWOT_ANALYSIS` artifact。

### 5.5 WRITER

职责：

- 基于需求、证据、Schema 和分析产物生成报告草稿。
- 保持 citation 意识。

输出：

- `REPORT_DRAFT` artifact。

### 5.6 REVIEWER

职责：

- 检查引用覆盖、事实一致性、过度推断和证据缺口。
- 生成结构化决策。

输出：

- `ReviewFinding`
- `ReviewDecision`
- `REVIEW_FINDINGS` artifact。

决策动作：

- `PASS`
- `REVISE_REPORT`
- `REWORK_ANALYSIS`
- `RECOLLECT_EVIDENCE`
- `ASK_USER`

### 5.7 REVISION

职责：

- 根据质检结果生成最终报告。

输出：

- `FINAL_REPORT` artifact。

## 6. LangGraph4j DAG

当前图结构：

```text
START
-> RESEARCHER
-> EXTRACTOR
-> ANALYST
-> WRITER
-> REVIEWER
-> REVIEW_GATE
   -> RESEARCHER  when route = recollect
   -> ANALYST     when route = reanalyze
   -> WRITER      when route = revise
   -> REVISION    when route = finish
-> END
```

`REVIEW_GATE` 根据 `ReviewDecision.action` 决定路由：

- `RECOLLECT_EVIDENCE` -> Researcher。
- `REWORK_ANALYSIS` -> Analyst。
- `REVISE_REPORT` -> Writer。
- `PASS` -> Revision。

当前为避免演示无限循环，自动打回最多 1 次。

## 7. Trace 机制

`WorkflowNodeExecutor` 统一负责：

- 创建 `AgentStep`。
- 创建 `AgentTrace`。
- 发布 SSE 事件。
- 记录成功、失败和耗时。

`AgentTraceContext` 使用 ThreadLocal 让 LLM 客户端记录：

- prompt。
- rawModelOutput。
- modelName。
- token usage。
- fallbackUsed。

未配置 LLM 时，Agent 会使用 deterministic fallback，并把 fallback 输出写入 Trace。

## 8. Artifact 版本机制

`AnalysisRun.addArtifact` 统一为同类型 artifact 计算版本号。

示例：

```text
REPORT_DRAFT v1
REPORT_DRAFT v2
REPORT_DRAFT v3
```

触发场景：

- Reviewer 打回后自动重跑 Writer。
- 用户手动重跑 Writer。
- 后续 Agent 生成同类型新产物。

## 9. 证据链协议

证据对象：

```text
EvidenceSource
  citationKey
  title
  url
  sourceType
  collectionStatus
  freshness
  snippet
  rawText
  complianceNote
```

采集状态约定：

- `FETCHED` + `LIVE_FETCHED`：已按 robots 策略抓取到公开页面正文。
- `FETCHED` + `LIVE_FETCHED` + `search_result_web_page`：搜索结果网页已抓取到正文。
- `FETCH_FAILED` + `SEARCH_RESULT_SNIPPET`：搜索命中了 URL，但页面正文抓取失败，仅保留搜索结果摘要，报告前需要人工确认。
- `USER_PROVIDED`：用户补充资料，敏感资料会标记 `INTERNAL_ONLY`。

未配置搜索 API key 时，Researcher 不生成伪造网页证据，只记录证据缺口和补充 URL/问卷/访谈资料的建议。

引用约定：

- 报告正文使用 `[S1]`、`[S2]`。
- Claim 使用 `evidenceIds`。
- Schema 中的 evidenceIds 可点击选中 EvidencePanel。
- citation hover 显示标题、URL 和摘要。

## 10. 持久化

当前持久化：

- `analysis_run` 表。
- `run_payload jsonb` 保存完整聚合。
- `status`、`original_prompt`、`created_at`、`updated_at` 作为查询字段。
- 保存聚合时同步刷新明细表，保留完整快照的同时支持后续直接查询 trace、artifact、evidence 和 review finding。

当前明细表：

- `analysis_artifact`
- `agent_step`
- `agent_trace`
- `evidence_source`
- `evidence_chunk`
- `review_finding`

## 11. 扩展方向

后续技术增强：

- 使用 pgvector 保存 `EvidenceChunk` embedding。
- 使用语义召回替代关键词召回。
- 引入真实搜索 API。
- 增加用户确认后的人工审批流。
- 支持 artifact diff。
- 支持多轮 Reviewer 打回和人工介入。
