# AI Insight 答辩演示脚本

## 1. 演示目标

本脚本用于 8 到 12 分钟现场演示，重点让评委看到 AI Insight 不是一次性报告生成器，而是一个可确认范围、可追踪证据、可审查质量、可回放 Agent 决策、可局部重跑的竞品分析 Agent 协作系统。

演示主线：

```text
创建分析任务
-> 确认结构化范围
-> 补充公开来源和用户资料
-> 启动 LangGraph4j DAG
-> 展示 Reviewer 打回补采
-> 展示最终报告、Schema、SWOT、证据和 Trace
-> 手动重跑 Writer
-> 展示版本和指标变化
```

## 2. 演示前准备

### 2.1 启动依赖

如果使用 PostgreSQL 持久化：

```powershell
docker compose up -d postgres
```

如果只做本地原型演示，也可以直接使用当前 Spring Boot 配置。

### 2.2 启动后端

```powershell
mvn spring-boot:run
```

默认地址：

```text
http://localhost:8080
```

### 2.3 启动前端

```powershell
cd frontend
npm run dev
```

默认地址通常为：

```text
http://localhost:5173
```

### 2.4 推荐演示输入

分析需求：

```text
分析 Notion、飞书文档和 Confluence 在 AI 协作文档方向的竞品机会，重点关注 AI 搜索、权限协作、价格策略和用户评价。
```

公开来源 URL 可填：

```text
https://www.notion.com/product
https://www.notion.com/pricing
https://docs.feishu.cn/
https://www.atlassian.com/software/confluence
https://www.atlassian.com/software/confluence/pricing
```

补充资料示例：

```text
访谈摘要：团队用户普遍希望 AI 生成内容能保留引用来源，并能在报告交付前看到 Reviewer 的质检意见。企业用户尤其关注权限治理、审计记录和内容可追溯性。
```

## 3. 演示步骤

### 3.1 填写范围确认

操作：

1. 在左侧“分析需求”输入推荐 prompt。
2. 勾选官网、价格页、产品文档、更新日志、公开评价。
3. 在“公开来源 URL”输入真实公开页面。
4. 点击“填写范围确认”。

讲解要点：

- 填写范围确认不会马上跑完整报告，而是先进入待确认状态。
- 范围确认阶段负责把自然语言需求转成结构化分析工单，并生成待确认范围。
- 这一步体现系统不是纯聊天，而是任务型 Agent 工作流。

观察点：

- 状态显示“待确认”。
- 范围确认面板出现行业、竞品、维度、信息源偏好和澄清问题。

### 3.2 确认分析范围

操作：

1. 在“竞品列表”确认 Notion、飞书文档、Confluence。
2. 在“分析维度”确认 AI 搜索、权限协作、价格策略、用户评价。
3. 填写报告用途，例如“支持产品团队下一季度 AI 文档能力规划”。
4. 点击“确认范围”。

讲解要点：

- 用户可以修改 Agent 解析出的范围。
- 确认前不执行采集和报告，避免 Agent 在目标不清楚时自由发挥。

观察点：

- 状态进入待执行。
- ClarificationDraft 标记为 confirmed。

### 3.3 补充人工资料

操作：

1. 在左侧“补充资料”输入标题“内部访谈摘要”。
2. 类型选择“访谈”。
3. 粘贴推荐访谈摘要。
4. 如是内部资料，勾选“内部敏感资料”。
5. 点击“加入证据链”。

讲解要点：

- 用户资料会变成可引用的 `EvidenceSource`。
- 敏感资料会带 internal-only 合规说明。
- Agent 后续只能引用证据链中的内容，降低编造风险。

观察点：

- 右侧 EvidencePanel 出现新的 `[Sx]` 证据。
- 证据有标题、摘要、URL 或 `user-evidence://` 标识。

### 3.4 启动 Agent 分析

操作：

1. 点击“开始 Agent 分析”。
2. 切到“Agent DAG”。
3. 观察节点状态变化。

讲解要点：

- 后端使用 LangGraph4j 编排 DAG。
- 主流程节点顺序为 Researcher、Extractor、Analyst、Writer、Reviewer、Finalizer。
- Reviewer 后面有条件边 `REVIEW_GATE`，可按 `ReviewDecision` 打回 Researcher、Analyst 或 Writer。

观察点：

- 时间线逐个显示 Agent 执行。
- DAG 中反馈边是可见的。
- SSE 事件推动前端实时刷新。

### 3.5 展示 Reviewer 自动打回

操作：

1. 等待流程跑完。
2. 打开右侧“质检与打回”。
3. 查看 ReviewDecision 和 WorkflowTransition。
4. 切到“结构化 Schema”，查看复核路由。

讲解要点：

- Writer 首轮 fallback 会保留一个无引用机会点，用于演示质检。
- Reviewer 检出 citation_missing。
- 采集包显示价格页和用户评价证据不足时，ReviewDecision 选择 `RECOLLECT_EVIDENCE`。
- LangGraph4j 根据决策自动回到 Researcher，并重跑下游 Agent。

观察点：

- WorkflowTransitions 通常会看到 `recollect -> finish`。
- Researcher 和 Reviewer 的执行次数大于 1。
- 最终 ReviewDecision 变成 PASS。

### 3.6 展示最终报告和 citation

操作：

1. 切到“最终报告”。
2. 找到报告中的 `[S1]`、`[S2]` 等 citation。
3. hover citation 查看来源摘要。
4. 点击 citation，观察右侧证据来源选中。

讲解要点：

- 报告结论不是孤立文本，每条关键结论都能追溯到证据。
- citation hover 和 EvidencePanel 让评委能快速核验来源。

观察点：

- citation hover 显示标题、URL、snippet。
- EvidencePanel 高亮对应来源。
- 证据 URL 指向公开页面或用户资料标识。

### 3.7 展示结构化 Schema 和 SWOT

操作：

1. 切到“结构化 Schema”。
2. 展示 ResearchPackage、AnalysisClaim、CompetitorProfile。
3. 展开功能树、定价模型、用户画像。
4. 切到“竞品矩阵”或报告版本中查看 SWOT 分析产物。

讲解要点：

- Agent 间共享的是结构化状态，而不是只传 Markdown。
- `CompetitorProfile` 包含功能树、定价、用户画像和证据 ID。
- `AnalysisClaim` 保存结论类型、置信度和 evidenceIds。
- SWOT 单独作为 artifact 输出，贴合课题目标。

观察点：

- Schema 中的 evidenceIds 可点击选中证据。
- SWOT 表格包含 Strengths、Weaknesses、Opportunities、Threats。

### 3.8 展示 Agent Trace

操作：

1. 点击 DAG 或时间线中的 Writer / Reviewer。
2. 打开 Trace 抽屉。
3. 展示 Prompt、输入快照、输出摘要、原始模型输出、模型、token、耗时、fallback 状态。

讲解要点：

- 每个 Agent 的输入、输出和决策过程可回放。
- 未配置 LLM 时 deterministic fallback 仍可运行，并记录 fallback trace。
- 配置小米 LLM 后，Trace 会记录真实模型名和 token usage。

观察点：

- TraceDrawer 中有 Prompt、输入、输出、rawModelOutput。
- token 和 latency 可见。

### 3.9 手动重跑 Agent

操作：

1. 在右侧时间线下点击“报告撰写”重跑。
2. 切到“报告版本”。
3. 查看 `REPORT_DRAFT` 版本增加。

讲解要点：

- 支持局部重跑，不需要整条 pipeline 从头开始。
- Artifact 按类型自动版本化。
- 用户可以对比版本和引用数量。

观察点：

- Writer 新增一次 AgentStep。
- 报告草稿版本从 v1/v2 增加到 v3。
- 指标面板中的步骤数、token 或耗时变化。

### 3.10 展示指标面板

操作：

1. 回到左侧“运行指标”。
2. 展示 Agent 步骤、证据来源、质检问题、引用标记、Claim 覆盖率、Schema 完整率、打回次数、Token 和耗时。

讲解要点：

- 系统不只生成内容，还能用指标衡量可信度和完整度。
- 质检问题清零、Claim 覆盖率和 Schema 完整率用于支撑结果可信度。

## 4. 备用演示路径

如果网络无法抓取公开 URL 或搜索服务未配置：

1. 保留用户提供 URL，让系统展示抓取失败和 robots / fetch complianceNote。
2. 在“补充资料”中加入脱敏访谈摘要或问卷结果。
3. 展示 ResearchPlan 中的搜索 query、证据缺口、问卷草案和访谈提纲。
4. 说明系统不会把内置文本伪装成真实搜索证据，缺证据时会进入 Reviewer 打回或人工补充链路。

如果 LLM 不可用：

1. 系统自动使用 deterministic fallback。
2. Trace 中会显示 fallbackUsed。
3. 演示仍可覆盖 DAG、Schema、Reviewer 打回、证据链和版本化。

## 5. 结束总结话术

AI Insight 的核心价值是把竞品分析从“一句话生成报告”升级成“可确认范围、可追踪证据、可复核质量、可回放过程、可局部重跑”的 Agent 协作工作流。

它对应课题核心要求：

- 角色 Agent 分工明确。
- 知识以 Schema 结构化沉淀。
- Reviewer 能生成 ReviewDecision 并触发反馈闭环。
- 每条结论尽量绑定 citation。
- 每个 Agent 的 Prompt、输入、输出、模型、token 和耗时可观测。
