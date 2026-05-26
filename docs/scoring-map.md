# AI Insight 评分点映射

## 1. 总览

本文把 AI Insight 当前实现与课题评分维度逐项对应，便于答辩时快速说明“评分点在哪里体现、用哪个界面或代码证明”。

| 评分维度 | 权重 | 当前支撑能力 | 演示证据 |
| --- | --- | --- | --- |
| 多 Agent 协作与输出可信度 | 35% | LangGraph4j DAG、Reviewer 打回、citation、ReviewDecision、证据链 | Agent DAG、质检与打回、EvidencePanel、WorkflowTransitions |
| 技术深度与工程完整度 | 25% | Spring Boot、LangGraph4j、SSE、Trace、PostgreSQL JSONB、Web fetch、单 Agent 重跑 | Mermaid 图、TraceDrawer、API、测试结果 |
| 业务价值与产品体验 | 20% | 任务确认流、前端 workbench、上下文补充、版本面板、指标面板 | 三栏工作台、范围确认、报告版本、运行指标 |
| 代码质量与文档 | 10% | 分层结构、单元测试、路线图、演示脚本、架构文档 | `src/main/java` 模块结构、`docs/` 文档、`mvn test` |
| 合规、材料与答辩 | 10% | robots 检查、敏感资料标记、API Key 隔离、搜索来源说明、合规说明 | EvidenceSource complianceNote、`.env` 忽略、合规文档 |

## 2. 多 Agent 协作与输出可信度

### 2.1 角色职责清晰

实现位置：

- 范围确认：澄清结构化分析范围，不进入主分析 DAG。
- `ResearcherNode`：采集证据。
- `ExtractorNode`：抽取竞品 Schema。
- `AnalystNode`：生成矩阵、Claim 和 SWOT。
- `WriterNode`：生成报告草稿。
- `ReviewerNode`：质检引用覆盖和证据缺口。
- `RevisionNode`：生成最终报告。

演示方式：

- 打开 Agent DAG。
- 点击不同 Agent 查看 Trace。
- 展示每个 Agent 的 title、步骤状态和产物。

### 2.2 协作与反馈闭环

实现位置：

- `AnalysisLangGraphWorkflow` 中的 `REVIEW_GATE` 条件节点。
- `ReviewDecision` 的 `RECOLLECT_EVIDENCE`、`REWORK_ANALYSIS`、`REVISE_REPORT`、`PASS`。
- `WorkflowTransition` 记录每次路由。

演示方式：

- 展示 Reviewer 首轮发现 citation_missing。
- 展示 ReviewDecision 请求回到 Researcher。
- 展示 Researcher、Extractor、Analyst、Writer、Reviewer 自动重跑。
- 展示最终 PASS。

### 2.3 输出可信度

实现位置：

- `EvidenceSource` 保存 citationKey、url、snippet、rawText、complianceNote。
- `AnalysisClaim` 保存 evidenceIds。
- `CitationCoverageEvaluator` 检查无引用结论。
- 前端 citation hover 和 EvidencePanel 选中。

演示方式：

- 在最终报告中 hover `[S1]`。
- 点击 citation，看右侧证据来源。
- 在 Schema 中点击 evidence ID。
- 展示 Claim 覆盖率和质检问题数量。

## 3. 技术深度与工程完整度

### 3.1 LangGraph4j 编排

实现位置：

- `AnalysisLangGraphWorkflow`
- `AnalysisGraphState`
- `WorkflowNodeExecutor`

演示方式：

- 展示 `/api/analysis-runs/workflow/mermaid`。
- 展示前端 Mermaid 源码和 DAG。
- 说明条件边由 ReviewDecision 驱动。

### 3.2 可观测性

实现位置：

- `AgentTrace`
- `AgentTraceContext`
- `SpringAiLlmClient`
- `WorkflowNodeExecutor`
- `TraceDrawer`

Trace 字段：

- stepId。
- agentName。
- prompt。
- inputSnapshot。
- outputSnapshot。
- rawModelOutput。
- modelName。
- fallbackUsed。
- promptTokens。
- completionTokens。
- totalTokens。
- latencyMs。
- errorMessage。

演示方式：

- 点击 Writer 或 Reviewer。
- 展开 Prompt、输入快照、输出摘要和原始模型输出。
- 展示 fallback 状态、token 和耗时。

### 3.3 工程 API

核心接口：

- `POST /api/analysis-runs`
- `PUT /api/analysis-runs/{runId}/requirement`
- `POST /api/analysis-runs/{runId}/start`
- `POST /api/analysis-runs/{runId}/context`
- `POST /api/analysis-runs/{runId}/evidence`
- `GET /api/analysis-runs/{runId}/events`
- `GET /api/analysis-runs/{runId}/traces`
- `GET /api/analysis-runs/{runId}/retrieval`
- `POST /api/analysis-runs/{runId}/agents/{agentName}/rerun`

### 3.4 持久化

当前能力：

- `PostgresAnalysisRunRepository` 使用 `analysis_run` 表保存完整 run payload。
- `jsonb` 保存聚合状态。
- 保留 `status`、`original_prompt`、`created_at`、`updated_at` 索引字段。

后续增强：

- 拆分 artifact、trace、evidence、chunk 明细表。
- 引入 pgvector 做语义召回。

## 4. 业务价值与产品体验

### 4.1 从聊天升级为分析工单

产品机制：

- 填写范围确认。
- 范围确认阶段生成结构化范围。
- 用户确认或修改后再启动。
- 上下文补充不是普通聊天，而是带 intent 的任务变更。

演示方式：

- 展示范围确认面板。
- 输入“再加入 Confluence，重点看企业权限和 AI 搜索”。
- 后端更新 competitors、dimensions、sourcePreferences。

### 4.2 面向分析工作流的工作台

界面结构：

- 左侧：任务配置、范围确认、上下文补充、补充资料、运行指标。
- 中间：DAG、最终报告、Schema、竞品矩阵、报告版本。
- 右侧：时间线、证据来源、质检与打回、Mermaid 源码。

演示方式：

- 展示从任务创建到最终报告的完整操作。
- 展示报告版本和单 Agent 重跑。

### 4.3 可量化的可信度指标

当前指标：

- Agent 步骤。
- 证据来源。
- 质检问题。
- 引用标记。
- Claim 覆盖率。
- Schema 完整率。
- 打回次数。
- 证据/Claim。
- Token。
- 耗时。

演示方式：

- 展示 Reviewer 打回后质检问题减少。
- 展示 Claim 覆盖率和 Schema 完整率。

## 5. 代码质量与文档

### 5.1 代码结构

后端分层：

- `controller`：REST 和 SSE。
- `service`：任务编排、采集、切片、召回、质检。
- `agent`：多 Agent 节点。
- `workflow`：LangGraph4j DAG。
- `model`：运行态和结构化 Schema。
- `repository`：持久化。
- `llm`：模型接入门面和 Spring AI 适配。

### 5.2 测试覆盖

当前覆盖：

- LLM 配置。
- request normalizer。
- workflow service 主流程。
- ReviewDecision 打回。
- AgentTrace。
- Schema 初始化。
- citation coverage evaluator。
- source collection。
- evidence chunk 和 retrieval。

验证命令：

```powershell
mvn test
```

### 5.3 文档

文档清单：

- `README.md`：项目说明和运行方式。
- `docs/parallel-development-plan.md`：并行开发方案。
- `docs/remaining-feature-roadmap.md`：剩余功能路线图。
- `docs/demo-script.md`：答辩演示脚本。
- `docs/scoring-map.md`：评分点映射。
- `docs/architecture.md`：架构和 Agent 协议。
- `docs/compliance-notes.md`：合规说明。

## 6. 合规、材料与答辩

### 6.1 公开数据合规

实现位置：

- `WebPageFetchService` 校验 http/https URL。
- 抓取前尝试读取 robots.txt。
- `EvidenceSource.complianceNote` 记录允许、失败、阻止或 fallback 说明。

### 6.2 敏感资料处理

实现位置：

- `UserProvidedEvidence.sensitive`。
- `SourceCollectionService.fromUserProvidedEvidence`。

行为：

- 敏感资料 sourceType 标记为 `user_*`。
- complianceNote 写入 internal-only。
- 演示和文档不复述真实 API Key 或敏感内容。

### 6.3 API Key 管理

实现位置：

- `.env` 本地维护。
- `.gitignore` 忽略 `.env`。
- README 使用 `your-api-key` 示例。

## 7. 答辩强调点

推荐结尾：

AI Insight 的价值不在于“生成了一篇报告”，而在于把竞品分析拆成可确认、可追踪、可审查、可重跑的 Agent 协作流程。评委可以看到每个 Agent 做了什么、用了什么证据、哪里被 Reviewer 打回、为什么最终报告可信。
