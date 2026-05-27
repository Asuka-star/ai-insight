# AI Insight 剩余功能路线图

## 1. 文档目的

本文记录 AI Insight 在当前代码基础上，为满足课题要求、MVP 演示和高分验收仍需要补齐的功能。

当前项目已经具备范围确认、LangGraph4j DAG、Reviewer 条件打回、结构化 Schema、SSE 进度、Trace 数据、前端工作台和单 Agent 重跑等核心能力。后续重点不再是“从零搭链路”，而是把演示可信度、可观测细节、真实资料采集、结构化展示和答辩材料做扎实。

信息采集 Agent 的专项差距和后续优化计划见 `docs/research-agent-roadmap.md`。

## 2. 当前已具备能力

### 2.1 后端链路

- `POST /api/analysis-runs` 生成范围确认内容。
- `PUT /api/analysis-runs/{runId}/requirement` 确认或修改分析范围。
- `POST /api/analysis-runs/{runId}/start` 启动 Agent DAG。
- `POST /api/analysis-runs/{runId}/context` 记录上下文补充。
- `POST /api/analysis-runs/{runId}/evidence` 记录用户补充资料。
- `POST /api/analysis-runs/{runId}/agents/{agentName}/rerun` 支持单 Agent 重跑。
- `GET /api/analysis-runs/{runId}/events` 支持 SSE 进度事件。
- `GET /api/analysis-runs/{runId}/traces` 支持 Agent Trace 查询。
- `GET /api/analysis-runs/{runId}/retrieval` 支持证据片段召回。

### 2.2 多 Agent 与结构化产物

- 范围确认：生成 `ClarificationDraft`，用户确认后再进入主 Agent DAG。
- `RESEARCHER`：采集资料，生成 `ResearchPackage`、`EvidenceSource` 和 `EvidenceChunk`。
- `EXTRACTOR`：抽取 `CompetitorProfile`、`FeatureTree`、`PricingModel`、`UserPersona`。
- `ANALYST`：生成竞品矩阵和结构化 `AnalysisClaim`。
- `WRITER`：生成带 citation 的报告草稿。
- `REVIEWER`：检查引用覆盖，生成 `ReviewFinding` 和 `ReviewDecision`。
- `REVISION`：根据复核结果生成最终报告。

### 2.3 前端工作台

- 左侧任务创建、范围确认、上下文补充。
- 中间 Agent DAG、最终报告、结构化 Schema、竞品矩阵、报告版本。
- 右侧 Agent 时间线、证据来源、Reviewer 质检和运行指标。
- 点击报告 citation 可以选中证据来源。
- 点击 Agent 可以打开 Trace 抽屉。
- 支持手动重跑单个 Agent。

### 2.4 验证情况

- `mvn test` 已通过，当前后端单测 21 个。
- `npm run build` 已通过，前端 TypeScript 和 Vite 构建可用。
- Vite 构建存在 chunk size 警告，不影响当前运行，但后续可以做代码拆分优化。

## 3. 验收差距总览

| 编号 | 模块 | 差距 | 优先级 | 状态 |
| --- | --- | --- | --- | --- |
| G1 | Trace 展示 | 前端没有完整展示 Prompt、输入、输出、原始模型输出 | P0 | 已实现 |
| G2 | Schema 展示 | 功能树、定价模型、用户画像只显示摘要，缺少详情展开 | P0 | 已实现 |
| G3 | 证据输入 | 前端没有公开 URL 和用户资料录入入口 | P0 | 已实现 |
| G4 | 真实采集 | 已移除内置来源表和 seed evidence，改为用户 URL 抓取 + 可配置搜索 provider；未配置搜索时只记录证据缺口 | P1 | 已实现 |
| G5 | 上下文理解 | `ADJUST_SCOPE` 只做少量关键词匹配，中文表达覆盖不足 | P1 | 已实现 |
| G6 | 版本链路 | artifact 版本号和重跑前后对比不够明确 | P1 | 已实现 |
| G7 | SWOT | Analyst 没有显式输出 SWOT artifact 或结构化 SWOT | P1 | 已实现 |
| G8 | Review 定位 | ReviewFinding 不能点击定位到 claim 或报告片段 | P2 | 已实现 |
| G9 | citation 体验 | citation 只选中证据，没有 hover 摘要或跳转行为 | P2 | 已实现 |
| G10 | 持久化拆分 | 当前 PostgreSQL 保存 run 聚合 JSON，并同步 artifact、step、trace、evidence、chunk、review finding 明细表 | P2 | 已实现 |
| G11 | 评测指标 | 缺少引用覆盖率、字段完整率、补采改善分等指标面板 | P2 | 已实现 |
| G12 | 答辩材料 | 缺少架构图、演示脚本、评分点映射和合规说明文档 | P0 | 已实现 |

## 4. P0：必须优先补齐的演示能力

### 4.1 Trace 面板完整展示

目标：满足高分验收中“Trace 面板展示 Prompt、输入、输出、模型、token、耗时”的要求。

当前情况：

- 后端 `AgentTrace` 已包含 `prompt`、`inputSnapshot`、`outputSnapshot`、`rawModelOutput`、`modelName`、`promptTokens`、`completionTokens`、`totalTokens`、`latencyMs`。
- 前端 `TraceDrawer` 当前只显示模型名、决策摘要、token 和耗时。

需要实现：

- 在 TraceDrawer 中增加分区：
  - 执行元信息：Agent、状态、开始/结束时间、耗时、fallback 标记。
  - Prompt：展示完整 `trace.prompt`，支持折叠。
  - 输入快照：展示 `trace.inputSnapshot`。
  - 输出摘要：展示 `trace.outputSnapshot`。
  - 原始模型输出：展示 `trace.rawModelOutput`，支持折叠。
  - Token：展示 prompt、completion、total。
- fallback 场景也要清楚展示“deterministic fallback”，避免评委误以为 Trace 缺失。
- 对长文本使用 `pre` 或折叠区域，避免抽屉排版被撑坏。

涉及文件：

- `frontend/src/components/TraceDrawer.tsx`
- `frontend/src/styles.css`
- `frontend/src/types.ts`

验收标准：

- 点击任意 Agent 节点后，右侧 Trace 抽屉能看到 Prompt、输入、输出、模型、token、耗时。
- 没有配置 LLM 时，也能看到 fallback 输出和估算 token。
- 前端 `npm run build` 通过。

### 4.2 Schema 视图展开详情

目标：满足高分验收中“Schema 视图展示功能树、定价模型、用户画像和 Claim”的要求。

当前情况：

- 后端已经写入 `CompetitorProfile`、`FeatureTree`、`PricingModel`、`UserPersona`、`AnalysisClaim`。
- 前端 SchemaPanel 只展示画像摘要、功能根节点数量和定价方案数量。

需要实现：

- 在 SchemaPanel 中按竞品展示：
  - 产品定位。
  - 目标用户。
  - 优势和弱势。
  - 功能树根节点列表，显示名称、描述、证据 ID。
  - 定价模型：策略摘要、是否有免费版、套餐列表、证据 ID。
  - 用户画像：名称、segment、公司规模、JTBD、痛点、购买顾虑、证据 ID。
- Claim 区域增加：
  - claim 类型。
  - 置信度。
  - 涉及竞品。
  - 证据 ID。
  - 生成 Agent。
- `ResearchPackage` 区域展示：
  - 来源数量。
  - 缺失证据类型。
  - 采集时间。

涉及文件：

- `frontend/src/components/SchemaPanel.tsx`
- `frontend/src/styles.css`
- `frontend/src/types.ts`

验收标准：

- Schema 视图可以直接展示完整功能树、定价模型、用户画像和 Claim。
- 证据 ID 在 Schema 中可读，最好能复用 citation chip 样式或支持选中证据。
- 前端 `npm run build` 通过。

### 4.3 前端资料来源输入

目标：让用户在创建任务或任务运行前明确提供公开 URL、访谈摘要、问卷、手动资料，而不是只依赖后端 seed evidence。

当前情况：

- 后端 `CreateAnalysisRunRequest` 支持 `sourceUrls`。
- 后端 `/evidence` 已支持用户资料录入。
- 前端创建任务表单没有 `sourceUrls` 输入。
- 前端没有调用 `/evidence` 的表单。

需要实现：

- 在任务配置区增加“公开来源 URL”输入。
  - 支持多行输入，每行一个 URL。
  - 创建任务时写入 `sourceUrls`。
  - 确认范围时也能更新 `sourceUrls`。
- 在上下文区域或独立资料面板增加“补充资料”表单。
  - title。
  - sourceType：url、interview、survey、note。
  - content。
  - url。
  - sensitive。
  - 提交后调用 `/api/analysis-runs/{runId}/evidence`。
- 提交成功后刷新 run，并在 EvidencePanel 立刻看到新增 citation。

涉及文件：

- `frontend/src/App.tsx`
- `frontend/src/api.ts`
- `frontend/src/types.ts`
- `frontend/src/components/ContextPanel.tsx` 或新增 `EvidenceInputPanel.tsx`
- `frontend/src/styles.css`

后端可能涉及：

- `CreateAnalysisRunRequest`
- `UpdateAnalysisRequirementRequest`
- `AnalysisWorkflowService`

验收标准：

- 用户可以在创建任务时填入 Notion、飞书、Confluence 等真实 URL。
- 用户可以在任务确认后补充一段访谈/问卷/备注资料。
- 新资料进入证据链并生成 citation。
- 前端 `npm run build` 通过，后端 `mvn test` 通过。

### 4.4 答辩材料基础文档

目标：把当前系统能力和课题评分点对应起来，降低后续答辩准备成本。

需要新增文档：

- `docs/demo-script.md`：演示脚本。
- `docs/scoring-map.md`：评分点映射。
- `docs/architecture.md`：系统架构与 Agent 协议。
- `docs/compliance-notes.md`：公开数据采集、robots、敏感资料和 API Key 合规说明。

建议内容：

- 演示脚本：
  - 创建任务。
  - 确认范围。
  - 添加真实 URL。
  - 启动 DAG。
  - 展示 Reviewer 打回。
  - 展示 citation 和证据。
  - 展示 Trace。
  - 手动重跑 Agent。
- 评分点映射：
  - 多 Agent 协作与可信度。
  - 技术深度与工程完整度。
  - 业务价值与产品体验。
  - 代码质量与文档。
  - 合规、材料与答辩。

验收标准：

- 答辩时可以直接照着脚本演示。
- 每个评分点都有代码或界面证据。

## 5. P1：高分体验与可信度补强

### 5.1 真实公开来源采集策略

目标：替换 `example.com` seed evidence，提升“公开信息采集”和“每条结论可定位到数据来源”的可信度。

当前情况：

- 如果用户提供 URL，后端会抓取网页并检查 robots。
- 如果没有 URL，后端会按竞品和来源偏好生成搜索 query；配置 `TAVILY_API_KEY` 后会调用真实搜索服务。
- 搜索结果 URL 会继续走网页抓取和 robots 检查；抓取失败或内容不可用时直接跳过，不进入前端证据列表。只有用户主动提供的 URL 抓取失败时才保留 `FETCH_FAILED` 来源。
- EvidenceSource 已记录 `collectionStatus` 和 `freshness`，前端证据面板会展示实时抓取、搜索摘要、用户资料等状态。

需要实现：

- 根据竞品名称、维度和 `sourcePreferences` 生成搜索 query。
- 接入 Tavily Search provider，并保留 `SearchProvider` 抽象以便后续替换其他搜索服务。
- 用户没有填 URL 且搜索未配置时，不生成伪证据，只提示补充 URL、问卷或访谈材料。
- EvidenceSource 记录来源状态：
  - `FETCHED`。
  - `BLOCKED_BY_ROBOTS`。
  - `FETCH_FAILED`。
  - 搜索结果抓取失败时跳过，不生成 snippet-only 证据。

涉及文件：

- `src/main/java/com/aiinsight/service/SourceCollectionService.java`
- `src/main/java/com/aiinsight/service/WebPageFetchService.java`
- `src/main/java/com/aiinsight/model/run/EvidenceSource.java`
- `src/test/java/com/aiinsight/service/SourceCollectionServiceTest.java`

验收标准：

- 演示常见竞品时，EvidencePanel 中优先出现用户 URL 或搜索结果 URL。
- robots 禁止或抓取失败时，不中断流程，并能看到合规说明。
- 后端 `mvn test` 通过。

### 5.2 上下文补充语义解析

目标：让用户输入中文自然语言补充时，系统能更准确更新任务范围。

当前情况：

- `applyScopeHints` 只匹配少量固定英文词。
- 中文“加入 Confluence，重点看企业权限和 AI 搜索”无法完整解析成结构化字段。

需要实现：

- 增强规则解析：
  - 竞品：Notion、飞书、飞书文档、Confluence、Airtable、语雀、腾讯文档。
  - 维度：AI 搜索、权限协作、价格策略、用户评价、产品定位、核心功能、商业模式、风险。
  - 来源偏好：官网、价格页、产品文档、更新日志、公开评价、访谈、问卷。
- 当 LLM 可用时，新增一个轻量 `ContextInterpreter`，把上下文解析成结构化 patch。
- 保存系统解释记录，作为 `AnalysisContextMessage` 的系统回复或 `recommendedActions`。

涉及文件：

- `src/main/java/com/aiinsight/service/AnalysisWorkflowService.java`
- 可新增 `src/main/java/com/aiinsight/service/AnalysisContextInterpreter.java`
- `src/test/java/com/aiinsight/service/AnalysisWorkflowServiceTest.java`

验收标准：

- 输入“再加入 Confluence，重点看企业权限和 AI 搜索能力”，requirement 中能出现 Confluence、权限协作、AI 搜索。
- 输入“补充价格页和公开评价”，sourcePreferences 能更新。
- 后端 `mvn test` 通过。

### 5.3 artifact 版本链路和对比

目标：让“重跑后产物版本增加、输出有改善”可见。

当前情况：

- Artifact 会追加到列表。
- 大多数 artifact 默认版本是 `v1`。
- 最终报告手动设置为 draft version + 1。
- 前端版本页按 artifact type 分组，但没有差异对比。

需要实现：

- 后端创建 artifact 时，按同类型 artifact 自动计算下一个 version。
- Agent 重跑时不删除旧 artifact，只追加新版本。
- 记录 `sourceStepId` 或 `generatedByAgent`，方便前端说明产物来自哪次执行。
- 前端版本页展示：
  - 版本号。
  - 生成时间。
  - 生成 Agent。
  - citation 数量。
  - 可选 diff 或“前后引用数量变化”。

涉及文件：

- `src/main/java/com/aiinsight/model/run/AnalysisArtifact.java`
- `src/main/java/com/aiinsight/agent/node/*.java`
- 可新增 `ArtifactVersionService`
- `frontend/src/components/ArtifactVersionsPanel.tsx`

验收标准：

- 手动重跑 Writer 后，报告草稿版本号增加。
- Reviewer 打回后，报告或证据列表能展示新版本。
- 前端能清楚看到当前选中的是哪个版本。

### 5.4 SWOT 分析产物

目标：贴合课题目标中的“SWOT 分析”要求。

当前情况：

- Analyst 输出横向矩阵、机会点和风险。
- 没有显式 `SWOT` artifact 或结构化字段。

需要实现：

- 新增 ArtifactType：
  - `SWOT_ANALYSIS`
- 可选新增 Schema：
  - `SwotAnalysis`
  - `SwotItem`
- Analyst 生成 SWOT：
  - Strengths。
  - Weaknesses。
  - Opportunities。
  - Threats。
  - 每条绑定 evidenceIds。
- 前端增加 SWOT 展示区域，或放入 Schema / Matrix tab。

涉及文件：

- `src/main/java/com/aiinsight/model/enums/ArtifactType.java`
- `src/main/java/com/aiinsight/agent/node/AnalystNode.java`
- `frontend/src/constants.ts`
- `frontend/src/components/SchemaPanel.tsx` 或新增 `SwotPanel.tsx`

验收标准：

- 最终演示中能明确看到 SWOT 分析。
- SWOT 每条至少绑定一个证据 ID，证据不足时标注待验证。

## 6. P2：体验增强与工程完整度

### 6.1 ReviewFinding 定位能力

目标：从质检问题直接定位到相关 claim、报告段落或证据。

需要实现：

- ReviewFinding 增加：
  - `claimId`
  - `artifactId`
  - `citationKey`
  - `startOffset` / `endOffset` 或 paragraph index
- Reviewer 规则检查时尽量绑定相关段落或 claim。
- 前端 ReviewPanel 点击 finding：
  - 跳转到报告 tab。
  - 高亮相关 citation 或段落。
  - 如果绑定 claim，则切到 Schema 并高亮 claim。

验收标准：

- 点击 Reviewer 问题后，用户知道问题对应哪条结论或报告段落。

### 6.2 citation hover 和来源跳转

目标：让溯源体验更自然。

需要实现：

- citation chip hover 显示来源标题、URL、snippet。
- 点击 citation 选中 EvidencePanel 中对应来源。
- EvidencePanel 中 URL 支持打开新标签。
- Schema 中的 evidenceIds 也做成可点击 citation chip。

验收标准：

- 用户不用离开报告就能快速看到 citation 摘要。
- 点击证据 URL 能跳到原始公开页面。

### 6.3 评测指标面板

目标：用量化指标证明系统可信度改善。

建议指标：

- citation 覆盖率。
- Claim 证据绑定率。
- Schema 字段完整率。
- Reviewer finding 数量。
- ReviewDecision 类型。
- 打回次数。
- 补采前后证据数量变化。
- Agent 总耗时和 token 消耗。

需要实现：

- 后端新增 `RunMetrics` 或在服务层动态计算。
- 前端运行指标卡增加可信度指标。

验收标准：

- 演示时能展示“补采前后证据数量提升、Reviewer 问题清零”等量化结果。

### 6.4 PostgreSQL 明细表拆分

目标：提升工程完整度，但不是当前 MVP 必需。

当前情况：

- `analysis_run` 表通过 `jsonb` 保存完整聚合，作为运行态恢复的单一快照。
- `PostgresAnalysisRunRepository.save` 会同步刷新明细表，便于后续做筛选、审计和看板查询。

已同步的明细表：

- `analysis_artifact`
- `agent_step`
- `agent_trace`
- `evidence_source`
- `evidence_chunk`
- `review_finding`

后续可继续增强：

- 将前端的 trace、artifact、evidence 查询逐步切到明细接口。
- 增加分页、过滤和审计报表查询。

### 6.5 RAG 与向量召回

目标：提升技术深度。

当前情况：

- EvidenceChunk 支持简单关键词召回。
- Docker 已准备 PostgreSQL + pgvector。

需要实现：

- 使用 Spring AI Embedding 或兼容接口生成 chunk embedding。
- 保存到 pgvector。
- retrieval API 支持向量相似度召回。
- Writer 和 Reviewer 在 prompt 中优先使用召回片段。

验收标准：

- 输入“价格策略”“AI 搜索”等 query 时，能返回语义相关证据片段。

## 7. 推荐实施顺序

### 第一轮：把现有能力展示完整

1. TraceDrawer 展示完整 Prompt、输入、输出、模型和 token。
2. SchemaPanel 展开功能树、定价模型、用户画像和 Claim。
3. 前端增加 sourceUrls 和用户资料录入。
4. 新增答辩基础文档。

原因：这些功能主要是把已经存在的数据展示出来，风险小、收益高。

### 第二轮：提升真实可信度

1. 增强公开来源采集，接入并调优真实搜索 provider。
2. 增强中文上下文解析。
3. 明确 artifact 版本链路。
4. 增加 SWOT 分析。

原因：这些功能会改变流程产物，需要补测试，但会显著提升课题贴合度。

### 第三轮：体验和工程加分

1. ReviewFinding 点击定位。
2. citation hover 和原始 URL 跳转。
3. 评测指标面板。
4. PostgreSQL 明细表拆分。
5. pgvector 语义召回。

原因：这些是高分和工程完整度加分项，适合在主链路稳定后推进。

## 8. 建议任务拆分

### 前端任务 A：Trace 与 Schema 深化

范围：

- `TraceDrawer.tsx`
- `SchemaPanel.tsx`
- `styles.css`
- `types.ts`

交付：

- Trace 抽屉完整展示可观测字段。
- Schema 页完整展示结构化知识对象。

验证：

- `npm run build`

### 前端任务 B：资料输入和溯源体验

范围：

- `App.tsx`
- `api.ts`
- `ContextPanel.tsx` 或新增 `EvidenceInputPanel.tsx`
- `EvidencePanel.tsx`
- `ArtifactViewer.tsx`

交付：

- 创建任务时支持 source URLs。
- 任务中支持补充用户资料。
- citation hover 或点击体验增强。

验证：

- `npm run build`
- 手工创建任务并补充资料。

### 后端任务 A：采集和上下文解析

范围：

- `SourceCollectionService`
- `WebPageFetchService`
- `AnalysisWorkflowService`
- 新增 `AnalysisContextInterpreter`

交付：

- 内置真实公开来源候选。
- 中文上下文补充能更新范围。
- 抓取失败时合规降级。

验证：

- `mvn test`
- 用真实 URL 创建任务并检查 EvidenceSource。

### 后端任务 B：版本、SWOT 和指标

范围：

- `AnalysisArtifact`
- `AnalystNode`
- `WriterNode`
- `ReviewerNode`
- 可新增 metrics service

交付：

- artifact 自动版本号。
- SWOT artifact。
- 可信度指标。

验证：

- `mvn test`
- 重跑 Agent 后版本增加。

### 文档任务

范围：

- `docs/demo-script.md`
- `docs/scoring-map.md`
- `docs/architecture.md`
- `docs/compliance-notes.md`

交付：

- 可直接答辩使用的演示脚本和评分点映射。

## 9. 近期最小闭环目标

如果时间有限，优先完成下面 6 项：

1. Trace 面板展示完整 Prompt、输入、输出、模型、token、耗时。
2. Schema 视图展开功能树、定价模型、用户画像和 Claim。
3. 前端支持输入真实公开来源 URL。
4. 前端支持补充用户资料并加入证据链。
5. Analyst 输出 SWOT 分析。
6. 新增 demo script 和 scoring map。

完成后，项目就能比较完整地覆盖：

- 多 Agent 协作。
- 结构化知识。
- 反馈闭环。
- 信息溯源。
- 可观测。
- 人工介入。
- 答辩材料。

## 10. 风险提醒

- 不要提交 `.env` 或任何真实 API Key。
- 不要提交 `frontend/dist`、`node_modules`、日志文件。
- 公开网页抓取必须保留 robots 和失败降级说明。
- 用户补充的敏感资料需要标记 internal-only，报告中避免对外扩散。
- 如果 LLM 不可用，fallback 链路仍要稳定可演示。
- 真实来源抓取可能受网络、robots、反爬、页面动态渲染影响，需要准备备用 URL、问卷结果和访谈摘要。
