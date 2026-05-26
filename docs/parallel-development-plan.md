# AI Insight 并行开发文档

## 1. 项目定位

AI Insight 是一个面向内部产品、业务、战略和运营分析团队的竞品分析 Agent 工作台。

它不是一个“一句话生成报告”的聊天机器人，而是一个“分析任务工作流系统”。用户用自然语言或结构化表单创建竞品分析任务，系统先帮助用户澄清分析范围，再通过多 Agent 协作完成资料采集、结构化抽取、竞品对比、报告撰写、质检打回和最终报告生成。

核心产品定义：

> 用户用一句自然语言发起竞品分析任务，系统将其转化为结构化分析工单；用户确认或补充上下文后，多 Agent 按 DAG 执行，最终产出带证据来源、结构化 Schema、质检结果和执行轨迹的竞品分析报告。

## 2. 推荐产品形态

本项目不建议做成纯会话型，也不建议做成纯表单型。

推荐形态：

> 任务型工作流为主，会话式补充为辅。

### 2.1 为什么不是纯会话型

纯聊天容易变成普通 AI 助手：

- 结构化状态不清楚。
- Agent 编排和 DAG 不明显。
- 证据来源难以稳定管理。
- 质检打回容易变成自然语言解释，而不是真正流程控制。
- 评委不容易看出“多 Agent 协作系统”的工程价值。

### 2.2 为什么不是纯表单型

纯表单虽然清晰，但不够体现 AI Agent 的能力：

- 用户补充上下文不自然。
- 范围确认如果做成主流程 Agent，价值不明显。
- 质检打回和人工介入体验弱。
- 更像自动报表系统，而不是 Agent 协作系统。

### 2.3 最终交互模型

用户围绕一个 `AnalysisRun` 持续操作：

```text
填写范围确认
→ 范围确认阶段解析并生成结构化分析范围
→ 用户确认或修改范围
→ 开始多 Agent 分析
→ 采集 / 抽取 / 分析 / 撰写 / 质检
→ Reviewer 发现问题并给出 ReviewDecision
→ 自动或人工打回指定 Agent
→ 生成最终报告
→ 用户确认、补充上下文或局部重跑
```

## 3. 用户使用流程

### 3.1 创建任务

用户输入自然语言需求：

```text
分析 Notion 和飞书文档在 AI 协作文档方向的竞品机会，重点关注 AI 搜索、权限协作、价格策略和用户评价。
```

也可以补充结构化字段：

- 行业方向：AI 协作文档
- 竞品列表：Notion、飞书文档、Confluence
- 分析维度：产品定位、AI 能力、权限协作、定价、用户评价
- 信息源偏好：官网、价格页、文档、更新日志、公开评价
- 报告用途：产品评审、立项判断、季度规划、向上汇报

### 3.2 澄清范围

范围确认不应该直接让全流程跑到底，而是先生成一个结构化范围工单。

示例：

```json
{
  "industry": "AI 协作文档",
  "competitors": ["Notion", "飞书文档"],
  "dimensions": ["产品定位", "AI 能力", "权限协作", "定价", "用户评价"],
  "sourcePreferences": ["official_site", "pricing_page", "product_docs", "release_notes", "public_reviews"],
  "outputGoal": "支持产品团队判断下一季度 AI 文档能力建设重点",
  "clarificationQuestions": [
    "是否需要加入 Confluence 或语雀作为对照竞品？",
    "是否重点关注企业权限和 AI 搜索能力？"
  ]
}
```

前端展示该范围，用户可以确认或修改。

### 3.3 开始执行

用户确认范围后，后端执行多 Agent DAG。

推荐 Agent：

- 范围确认：生成结构化任务范围。
- `RESEARCHER`：采集公开资料、价格页、文档、更新日志、用户评价、问卷/访谈摘要。
- `EXTRACTOR`：抽取竞品知识 Schema，包括功能树、定价模型、用户画像。
- `ANALYST`：生成横向对比、SWOT、机会点、风险和结构化 Claim。
- `WRITER`：生成报告草稿。
- `REVIEWER`：检查引用覆盖、事实一致性、过度推断和证据缺口。
- `REVISION`：根据质检结果生成最终报告。

### 3.4 补充上下文

用户可以在任务生命周期中继续补充：

```text
再加入 Confluence，重点看企业权限和 AI 搜索。
```

```text
这段访谈记录可以作为用户评价来源：……
```

```text
把没有证据的机会点降级成待验证假设，然后重跑 Writer。
```

这些输入不是普通聊天，而是对当前 `AnalysisRun` 的上下文补充或任务调整。

### 3.5 最终确认

最终用户看到：

- 最终报告。
- 竞品结构化 Schema。
- 每条结论的证据来源。
- Reviewer 质检问题。
- Agent 执行时间线。
- Prompt、输入输出、模型、token、耗时等 Trace。
- 重跑和版本记录。

## 4. 后端开发目标

后端会话的目标是把当前“一次性 start 并跑完整 pipeline”的模式，升级为“范围确认 + 用户确认 + 多 Agent 执行 + 上下文补充 + 反馈闭环”。

### 4.1 后端职责

后端负责：

- 管理 `AnalysisRun` 生命周期。
- 维护结构化任务状态。
- 执行多 Agent DAG。
- 产出结构化 Schema。
- 维护证据来源和引用映射。
- 记录 Agent Trace。
- 根据 Reviewer 决策触发打回或重跑。
- 给前端提供稳定 API。

### 4.2 建议新增状态

当前 `AnalysisStatus` 可以扩展为：

```text
DRAFT
AWAITING_CONFIRMATION
RUNNING
REVIEWING
NEEDS_USER_INPUT
REVISING
SUCCEEDED
FAILED
```

如果短期不想大改 enum，也可以先用现有 `PENDING / RUNNING / SUCCEEDED / FAILED`，再额外增加 `phase` 字段：

```json
{
  "status": "PENDING",
  "phase": "AWAITING_CONFIRMATION"
}
```

### 4.3 建议新增核心模型

#### AnalysisContextMessage

记录用户和系统在任务中的补充上下文。

```java
public class AnalysisContextMessage {
    private UUID id;
    private ContextRole role; // USER, SYSTEM, AGENT
    private ContextIntent intent; // ADJUST_SCOPE, ADD_EVIDENCE, REQUEST_RERUN, COMMENT, CLARIFICATION
    private String content;
    private AgentName targetAgent;
    private Instant createdAt;
}
```

#### ClarificationDraft

记录范围确认阶段解析出的任务范围。

```java
public class ClarificationDraft {
    private String industry;
    private List<String> competitors;
    private List<String> dimensions;
    private List<String> sourcePreferences;
    private String outputGoal;
    private List<String> clarificationQuestions;
    private boolean confirmed;
}
```

#### UserProvidedEvidence

用户补充的访谈、问卷、URL、手动资料。

```java
public class UserProvidedEvidence {
    private UUID id;
    private String title;
    private String sourceType; // url, interview, survey, note
    private String content;
    private String url;
    private boolean sensitive;
    private Instant createdAt;
}
```

#### WorkflowTransition

记录 DAG 跳转和 Review 打回。

```java
public class WorkflowTransition {
    private UUID id;
    private AgentName sourceAgent;
    private AgentName targetAgent;
    private String reason;
    private ReviewAction action;
    private Instant createdAt;
}
```

### 4.4 API 契约建议

#### 填写范围确认

```http
POST /api/analysis-runs
```

请求：

```json
{
  "prompt": "分析 Notion 和飞书文档在 AI 协作文档方向的竞品机会",
  "industry": "",
  "competitors": [],
  "dimensions": [],
  "sourcePreferences": ["official_site", "pricing_page", "product_docs"]
}
```

建议行为：

- 创建 `AnalysisRun`。
- 生成 `ClarificationDraft`。
- 状态进入 `AWAITING_CONFIRMATION`。
- 不立即执行完整 Agent 流程。

返回：

```json
{
  "id": "run-id",
  "status": "PENDING",
  "phase": "AWAITING_CONFIRMATION",
  "requirement": {},
  "clarificationDraft": {},
  "steps": [],
  "artifacts": []
}
```

#### 确认或修改范围

```http
PUT /api/analysis-runs/{runId}/requirement
```

请求：

```json
{
  "industry": "AI 协作文档",
  "competitors": ["Notion", "飞书文档", "Confluence"],
  "dimensions": ["AI 搜索", "权限协作", "定价", "用户评价"],
  "sourcePreferences": ["official_site", "pricing_page", "product_docs", "public_reviews"],
  "outputGoal": "支持产品评审"
}
```

建议行为：

- 更新 `AnalysisRequirement`。
- 标记 ClarificationDraft 已确认。
- 状态仍保持待执行。

#### 开始执行分析

```http
POST /api/analysis-runs/{runId}/start
```

建议行为：

- 从当前 requirement 启动 DAG。
- 异步执行 Researcher、Extractor、Analyst、Writer、Reviewer、Revision。
- 前端通过 SSE 订阅进度。

#### 补充上下文

```http
POST /api/analysis-runs/{runId}/context
```

请求：

```json
{
  "content": "再加入 Confluence，重点看企业权限和 AI 搜索能力。",
  "intent": "ADJUST_SCOPE"
}
```

建议行为：

- 保存上下文消息。
- 根据 intent 更新 requirement、追加 evidence 或生成 recommendedAction。
- 不一定立即重跑，除非请求中显式指定。

#### 上传或粘贴资料

```http
POST /api/analysis-runs/{runId}/evidence
```

请求：

```json
{
  "title": "内部访谈摘要",
  "sourceType": "interview",
  "content": "用户反馈 Notion 模板能力强，但企业权限治理成本较高。",
  "sensitive": true
}
```

建议行为：

- 记录用户补充资料。
- 脱敏或标记敏感来源。
- 转换为 `EvidenceSource` 或进入 `ResearchPackage`。

#### 重跑 Agent

```http
POST /api/analysis-runs/{runId}/agents/{agentName}/rerun
```

建议行为：

- 记录新的 `AgentStep`。
- 不删除旧产物，使用 version 区分。
- 更新相关 artifact 和 schema。

#### 查询任务

```http
GET /api/analysis-runs/{runId}
```

返回完整 `AnalysisRun`，前端以该对象作为单一事实源。

#### 订阅事件

```http
GET /api/analysis-runs/{runId}/events
```

事件建议：

```text
run_created
clarification_ready
requirement_confirmed
run_started
agent_started
agent_succeeded
agent_failed
review_decision_created
rerun_requested
run_succeeded
run_failed
```

### 4.5 后端迭代优先级

#### P0：任务确认流

- 创建 run 后先进入 `AWAITING_CONFIRMATION`。
- 范围确认阶段生成结构化范围。
- 新增更新 requirement API。
- 新增 start API。

#### P1：上下文补充

- 新增 context message 模型。
- 新增 context API。
- 支持用户调整竞品、维度、来源偏好。

#### P2：真实反馈闭环

- Reviewer 写入 `ReviewDecision`。
- Workflow 根据 `ReviewAction` 打回 Researcher / Analyst / Writer。
- 记录 `WorkflowTransition`。

#### P3：可观测性

- 每个 Agent 写入 `AgentTrace`。
- 记录 prompt、输入快照、输出快照、模型、token、耗时。

## 5. 前端开发目标

前端会话的目标是把当前工作台升级为支持“范围确认 + 上下文补充 + 多 Agent 过程查看”的产品体验。

### 5.1 前端职责

前端负责：

- 创建分析任务。
- 展示范围确认阶段解析出的结构化范围。
- 允许用户修改并确认分析范围。
- 启动多 Agent 分析。
- 展示 DAG、时间线、报告、证据、质检和 Trace。
- 提供上下文补充入口。
- 提供手动重跑和局部修订入口。

### 5.2 页面结构建议

推荐三栏工作台：

```text
左侧：任务配置与上下文
中间：DAG / 报告 / Schema
右侧：时间线 / 证据 / 质检 / Trace
```

#### 左侧：任务配置

包含：

- 原始 prompt。
- 行业方向。
- 竞品列表。
- 分析维度。
- 信息源偏好。
- 报告用途。
- 创建任务按钮。
- 确认范围按钮。
- 开始分析按钮。

#### 中间：主工作区

使用 tabs：

- `Agent DAG`
- `最终报告`
- `结构化 Schema`
- `竞品矩阵`
- `报告版本`

#### 右侧：检查区

包含：

- Agent 时间线。
- 证据来源。
- Reviewer 质检问题。
- ReviewDecision。
- Agent Trace 抽屉。

### 5.3 前端状态机

前端应根据 run 状态切换按钮：

```text
无 run：
  展示“创建任务”

AWAITING_CONFIRMATION：
  展示“确认范围”和“开始分析”

RUNNING：
  禁用结构化字段编辑，展示实时 Agent 进度

NEEDS_USER_INPUT：
  高亮上下文输入框和待补充问题

SUCCEEDED：
  展示最终报告、证据、质检和重跑入口

FAILED：
  展示错误信息和重试入口
```

### 5.4 上下文补充面板

不要叫“聊天”，建议叫：

- 补充上下文
- 调整分析任务
- 人工介入

输入框下方提供 intent 快捷按钮：

- 调整范围
- 补充资料
- 指定重跑
- 修订报告
- 人工备注

示例输入：

```text
再加入 Confluence，重点看企业权限和 AI 搜索能力。
```

```text
这段访谈记录也作为用户评价来源：……
```

```text
把没有引用的机会点降级为待验证假设，然后重跑 Writer。
```

### 5.5 前端迭代优先级

#### P0：适配任务确认流

- 创建 run 后不默认假设已完成。
- 展示 clarificationDraft / requirement 编辑区。
- 支持确认 requirement。
- 支持点击开始分析。

#### P1：上下文补充 UI

- 新增 ContextPanel。
- 展示历史 contextMessages。
- 调用 context API。

#### P2：Schema 视图增强

- 展示 `researchPackage`。
- 展示 `competitorProfiles`。
- 展示 `claims`。
- 展示 `workflowTransitions`。

#### P3：体验增强

- 报告中的 citation hover 展示证据摘要。
- ReviewFinding 点击定位到相关 claim。
- Trace 抽屉展示 prompt / token / latency。
- 报告版本对比。

## 6. 前后端协作契约

### 6.1 单一事实源

前端以 `GET /api/analysis-runs/{runId}` 返回的 `AnalysisRun` 作为单一事实源。

后端字段可以逐步增加，但不要随意删除或改名已有字段。

### 6.2 兼容策略

前端必须容忍字段为空：

- `clarificationDraft` 可能为空。
- `contextMessages` 可能为空。
- `traces` 可能为空。
- `reviewDecision` 可能为空。
- `competitorProfiles` 可能为空。
- `claims` 可能为空。

后端新增字段时，应尽量使用默认空集合，而不是 `null`。

### 6.3 字段命名

统一使用 JSON camelCase：

```text
sourcePreferences
reviewFindings
recommendedActions
contextMessages
clarificationDraft
workflowTransitions
```

### 6.4 Agent 名称

前后端统一使用：

```text
RESEARCHER
EXTRACTOR
ANALYST
WRITER
REVIEWER
REVISION
```

### 6.5 ReviewAction

建议统一：

```text
PASS
REVISE_REPORT
REWORK_ANALYSIS
RECOLLECT_EVIDENCE
ASK_USER
```

## 7. 并行开发分工

### 7.1 后端会话任务

后端会话优先做：

1. 拆分“创建任务”和“开始执行”。
2. 新增 ClarificationDraft。
3. 新增更新 requirement API。
4. 新增 start API。
5. 新增 context message 模型和 API。
6. 保持原有接口兼容，避免前端完全断掉。

后端会话启动提示：

```text
请阅读 docs/parallel-development-plan.md，负责后端部分开发。目标是把创建任务、确认范围、开始执行拆开，并新增上下文补充接口。不要改前端。
```

### 7.2 前端会话任务

前端会话优先做：

1. 适配 `AWAITING_CONFIRMATION` 状态。
2. 增加任务范围确认 UI。
3. 增加“开始分析”按钮。
4. 增加上下文补充面板。
5. SchemaPanel 适配后端新增字段。
6. 保持旧后端接口可降级运行。

前端会话启动提示：

```text
请阅读 docs/parallel-development-plan.md，负责前端部分开发。目标是适配范围确认、开始分析和上下文补充 UI。不要改后端。
```

## 8. 验收标准

### 8.1 MVP 验收

必须能演示：

1. 用户输入一句自然语言需求。
2. 系统生成结构化分析范围。
3. 用户修改或确认范围。
4. 用户点击开始分析。
5. Agent DAG 开始执行。
6. 前端展示实时进度。
7. 最终报告带 citation。
8. 点击 citation 能看到证据来源。
9. Reviewer 展示质检结果。
10. 用户可以重跑某个 Agent。

### 8.2 高分验收

进一步演示：

1. 用户补充上下文后，系统更新任务范围。
2. Reviewer 发现证据缺口并生成 ReviewDecision。
3. Workflow 根据 ReviewDecision 打回对应 Agent。
4. 重跑后产物版本增加，输出有改善。
5. Trace 面板展示 Prompt、输入、输出、模型、token、耗时。
6. Schema 视图展示功能树、定价模型、用户画像和 Claim。

## 9. 当前风险

- 如果只保留“一句话直接生成报告”，项目会显得浅。
- 如果纯做聊天，会弱化 DAG、Schema 和证据链。
- 如果纯做表单，会弱化 Agent 澄清和人工介入能力。
- 前后端并行开发必须保持 API 契约，否则容易互相阻塞。
- `.env` 中可能存在本地真实 key，不能提交或展示。
- 生成的 `frontend/dist` 和 `node_modules` 不应提交。

## 10. 推荐下一步

第一轮并行开发建议：

后端：

1. 增加 `ClarificationDraft`。
2. `POST /api/analysis-runs` 改为生成范围确认内容。
3. 增加 `PUT /api/analysis-runs/{runId}/requirement`。
4. 增加 `POST /api/analysis-runs/{runId}/start`。

前端：

1. 增加“范围确认”区域。
2. 创建任务后展示范围确认结果，而不是默认等待最终报告。
3. 用户确认后再调用 start。
4. 增加 ContextPanel 的静态 UI，等待后端 API 对接。

这样可以快速把项目从“一次性 prompt 生成报告”升级成“分析工单 + Agent 协作工作流”。
