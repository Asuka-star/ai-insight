# AI Insight 项目成果提交文档

本文档按“AI 全栈项目挑战赛”项目成果提报模板整理，覆盖基础信息、功能说明、交付材料、技术说明、结果说明、补充材料和合规声明。带 `TODO` 的内容需要在最终提交前按真实信息补齐。

---

## 一、基础信息

| 字段 | 内容 |
| --- | --- |
| 项目名称 | AI Insight - AI 驱动的竞品分析 Agent 协作系统 |
| 参赛课题 | CIS - AI 驱动的竞品分析 Agent 协作系统 |
| 团队名称 | TODO：填写团队名称 |
| 队长 | TODO：姓名 / 学校 / 专业 / 年级 |
| 队员 | TODO：如独立完成可写“独立完成”；如小队完成请列出所有成员 |

### 分工说明

| 成员 | 角色 | 负责模块 |
| --- | --- | --- |
| TODO：成员姓名 | 架构师 / 全栈开发 / AI 工程师 | 系统架构设计、多 Agent 编排、Agent 节点开发、Prompt 工程、前端工作台、后端 API、数据库设计、测试、部署与文档 |

> 如为独立完成，可在最终提交时写明：本项目由队长独立完成，覆盖架构设计、前后端开发、LangGraph4j Agent 编排、Prompt 工程、数据库设计、测试、部署运维和答辩材料整理。

---

## 二、功能说明

### 核心功能清单

1. **六角色 Agent 协作流水线**：Clarifier（范围确认）、Researcher（信息采集）、Extractor（知识抽取）、Analyst（竞品分析）、Writer（报告撰写）、Reviewer（质检复核）分工协作，主流程由 LangGraph4j DAG 编排。
2. **结构化竞品知识 Schema**：系统定义 `ResearchPackage`、`CompetitorProfile`、`FeatureTree`、`PricingModel`、`UserPersona`、`AnalysisClaim`、`ReviewDecision` 等强类型对象，Agent 间通过结构化状态协作，而不是只传自然语言。
3. **Reviewer 反馈闭环与局部重跑**：Reviewer 生成 `ReviewFinding`、`ReviewDecision` 和 `ReviewRepairTask`，可打回 Researcher / Extractor / Analyst / Writer，并触发下游级联重跑；用户也可以手动重跑单个 Agent。
4. **证据链与 RAG 检索**：公开网页、用户资料、文档、问卷和访谈会沉淀为 `EvidenceSource` 与 `EvidenceChunk`；报告 citation 使用 `[S1]` 形式，结构化 Claim 绑定 `evidenceIds`，支持关键词召回与可选 pgvector 语义召回。
5. **可观测工作台**：前端三栏 Workbench 展示 Agent DAG、时间线、报告、结构化 Schema、证据来源、Reviewer 质检、报告版本和运行指标；TraceDrawer 可回放 Prompt、输入输出、原始模型输出、token、耗时和 fallback 状态。
6. **主动调研能力**：Researcher 生成问卷草案和访谈提纲，前端支持导出腾讯问卷 DSL、导入 CSV/XLSX 调研结果，并将问卷/访谈洞察转入证据链参与后续分析。

### 端到端使用流程

1. 用户进入前端工作台，输入竞品分析需求，例如“分析 Notion、飞书文档和 Confluence 在 AI 协作文档方向的竞品机会，重点关注 AI 搜索、权限协作、价格策略和用户评价”。
2. 系统先运行 Clarifier Agent，把自然语言需求整理成结构化范围确认草稿，包含行业方向、竞品列表、分析维度、来源偏好和报告用途。
3. 用户在范围确认面板中修改并确认分析范围，也可以补充公开 URL、访谈摘要、问卷结果或上传资料文档。
4. 用户启动主分析流程后，Researcher 负责公开来源搜索、网页抓取、证据切片、调研计划、问卷草案和访谈提纲生成。
5. Extractor 从证据中抽取竞品画像、功能树、定价模型和用户画像；Analyst 生成结构化分析结论 `AnalysisClaim`，并标注哪些结论适合进入矩阵、SWOT 或补证清单。
6. Writer 基于需求、证据、claims 和竞品画像生成带 citation 的报告草稿，在 `REPORT_DRAFT` 中统一编排竞品矩阵和 SWOT 章节，报告产物按版本保存。
7. Reviewer 对报告、Claim 和证据链执行复核，发现引用缺失、证据不足或过度推断时，生成结构化打回决策并自动重跑对应 Agent 与下游节点。
8. 用户在工作台查看最终报告、证据来源、结构化 Schema、Reviewer 质检结果、Agent Trace 和运行指标，也可以对指定 Agent 发起手动重跑。

---

## 三、交付材料

| 材料类型 | 链接 / 说明 |
| --- | --- |
| 在线 Demo | TODO：填写可访问 Demo 链接；如无在线 Demo，请填写演示视频或录屏链接作为替代 |
| 体验账号 | TODO：如 Demo 需要登录，请填写账号 / 密码；如无需登录可写“无需登录” |
| 演示视频 | TODO：填写 3-8 分钟演示视频链接，建议覆盖创建任务、范围确认、Agent DAG、Reviewer 打回、报告 citation、Trace、问卷访谈和手动重跑 |
| 源代码仓库 | TODO：填写 GitHub / GitLab 仓库链接、提交分支和最后提交记录 |
| README / 运行说明 | 仓库根目录 `README.md`，包含项目简介、课题要求对齐、依赖环境、启动步骤、目录结构、配置说明、API 示例和演示流程 |
| 架构与评分材料 | `docs/architecture.md`、`docs/scoring-map.md`、`docs/demo-script.md`、`docs/compliance-notes.md` |

---

## 四、技术说明

### 系统架构图

完整架构图见：

- `docs/architecture-diagram-v2.svg`
- `docs/architecture-diagram-v2.png`

![系统架构图](architecture-diagram-v2.svg)

系统分层：

```text
React Workbench
  -> Spring Boot REST / SSE API
  -> AnalysisWorkflowService
  -> LangGraph4j DAG + WorkflowNodeExecutor
  -> Clarifier / Researcher / Extractor / Analyst / Writer / Reviewer
  -> PostgreSQL JSONB + detail tables + optional pgvector
  -> External LLM / Search / Web fetch / Embedding services
```

### 核心技术栈

| 层级 | 技术选型 |
| --- | --- |
| 前端 | React 18 + TypeScript + Vite 5 + @xyflow/react + react-markdown + rehype-sanitize + lucide-react |
| 后端 | Java 17 + Spring Boot 3.5.14 + Spring AI 1.1.3 + LangGraph4j 1.8.16 |
| Agent 编排 | LangGraph4j StateGraph，`REVIEW_GATE` 条件边驱动反馈路由 |
| 大模型接入 | OpenAI-compatible Chat API；默认主模型为 Xiaomi MiMo v2.5 Pro，Clarifier 可路由到 Doubao Seed 2.0 Lite |
| 搜索与抓取 | Tavily Search API、Jsoup、Playwright Java、robots 策略检查、网页缓存 |
| RAG / 向量 | EvidenceChunk 切片、OpenAI-compatible Embedding API、PostgreSQL pgvector 投影、关键词 fallback |
| 数据库 | PostgreSQL JSONB 权威聚合快照，明细表投影 artifact / step / trace / evidence / chunk / review finding |
| 部署 | Dockerfile 多阶段构建（前端构建、后端构建、Playwright Java 运行时）+ Docker Compose |
| 可观测 | 自建 `AgentTrace`、`AgentTraceContext`、SSE 事件、运行指标面板 |
| 测试 | JUnit 5 + Spring Boot Test，覆盖 Agent、Service、Workflow、Repository、编码守卫等 |

### 大模型 / AI 能力使用说明

**模型调用**

- 主模型：Xiaomi MiMo v2.5 Pro，用于 Researcher、Extractor、Analyst、Writer、Reviewer 等复杂推理任务。
- 轻量模型：Doubao Seed 2.0 Lite，可选用于 Clarifier 范围确认，降低前置澄清成本。
- 调用方式：通过 Spring AI 接入 OpenAI-compatible Chat API；豆包通过火山方舟 OpenAI-compatible Endpoint ID 路由。
- Embedding：OpenAI-compatible Embedding API，默认模型为 `text-embedding-3-small`，用于 evidence chunk 语义召回。
- 降级策略：未配置 LLM、搜索或 embedding key 时，系统使用 deterministic fallback、用户 URL 采集和关键词召回，保证演示与测试可运行。

**Agent 设计**

- 编排框架：LangGraph4j StateGraph。主流程为 `RESEARCHER -> EXTRACTOR -> ANALYST -> WRITER -> REVIEWER -> REVIEW_GATE`，Clarifier 作为主流程前置范围确认 Agent 单独运行。
- 角色分工：
  - Clarifier：把自然语言需求整理为可编辑的结构化范围确认草稿。
  - Researcher：规划搜索、采集公开资料、生成证据切片、问卷草案和访谈提纲。
  - Extractor：从证据中抽取竞品事实、功能树、定价模型、用户画像和结构化画像。
  - Analyst：生成结构化 `AnalysisClaim`、置信度、证据绑定和推荐放置位置（矩阵 / SWOT / 补证清单）。
  - Writer：基于 claims、竞品画像和证据索引生成 `REPORT_DRAFT`，在报告正文中统一编排竞品矩阵和 SWOT。
  - Reviewer：检查 citation、证据覆盖、事实一致性和过度推断，并输出结构化复核结果。
- 通信协议：不依赖自由文本串联，而是通过 `AnalysisRun` 聚合传递结构化状态；核心对象包括 `ResearchPackage`、`CompetitorProfile`、`AnalysisClaim`、`EvidenceSource`、`EvidenceChunk`、`ReviewDecision` 和 `ReviewRepairTask`。
- 输出约束：各 Agent prompt 明确要求只基于证据链和结构化状态输出；报告 citation 使用 `[S1]` 形式，Claim 通过 `evidenceIds` 绑定来源。
- 反馈机制：Reviewer 的 `ReviewDecision.action` 会被 `REVIEW_GATE` 映射成 LangGraph 条件边，可自动回到 Researcher 补证、Extractor 修复事实、Analyst 重做结论或 Writer 修订报告。
- 防循环机制：运行级 `maxReviewReworkAttempts` 控制自动返工次数，`WorkflowTransition` 记录每次路由和 blocking finding signature，避免质检返工无限循环。
- 可观测性：`WorkflowNodeExecutor` 统一记录 AgentStep、AgentTrace 和 SSE 事件；Trace 中包含 prompt、输入快照、输出摘要、原始模型输出、模型名、token、耗时、fallback 和异常。

**RAG 与证据能力**

- 证据沉淀：公开网页、用户资料、上传文档、问卷和访谈都会转为 `EvidenceSource`，并分片为 `EvidenceChunk`。
- 召回方式：配置 embedding 时优先使用 pgvector 语义召回；未配置或 pgvector 不可用时使用关键词召回。
- 证据复核：Reviewer 和 `CitationCoverageEvaluator` 会检查报告 citation、Claim evidenceIds、无来源结论和证据缺口。

### 关键工程难点与解决方案

| 难点 | 解决方案 |
| --- | --- |
| **Agent 间上下文传递与状态管理** | 以 `AnalysisRun` 作为全局运行态聚合，集中保存需求、证据、Schema、claims、artifact、Trace、ReviewDecision 和 WorkflowTransition。LangGraph4j 节点只接收 runId，并通过仓储读取 / 更新同一个聚合，避免 Agent 之间靠长文本传递隐式上下文。 |
| **长网页、文档和调研资料的结构化抽取** | Researcher / DocumentIngestionService 先把公开网页、上传文档、问卷和访谈转成 `EvidenceSource`，再由 `EvidenceChunkService` 分块；Extractor 先抽取 `CompetitorFactSet`，再投影成 `CompetitorProfile`、功能树、定价模型和用户画像，降低一次性让 LLM 直接生成大 Schema 的失败率。 |
| **证据链与报告结论容易脱节** | 统一使用 `citationKey`、`chunkKey` 和 `evidenceIds` 串联证据、Claim、Schema 和报告；Writer 只能使用已知 `[Sx]` citation，Reviewer 和 `CitationCoverageEvaluator` 会检查无引用结论、幻觉引用、Claim 证据缺失和证据不足。 |
| **Reviewer 反馈闭环的无限循环风险** | 设置运行级 `maxReviewReworkAttempts` 自动返工上限；`REVIEW_GATE` 把 `ReviewDecision.action` 映射到 Researcher / Extractor / Analyst / Writer；`WorkflowTransition` 记录每次路由、阻塞 finding 和 signature，重复问题未改善时停止自动循环并提示人工检查。 |
| **局部修复不能破坏未受影响内容** | Reviewer 生成 `ReviewRepairTask` 精确描述目标 Agent、claim、citation、段落和期望修复；Analyst 增量修订只修改被点名的 claims，Writer 增量修订只修改被点名的段落，未命中的内容保留原样，减少“修一处引入新问题”。 |
| **外部 LLM / 搜索 / embedding 不稳定** | LLM 不可用时使用 deterministic fallback；未配置 Tavily 时不伪造搜索证据，只采集用户 URL 并记录证据缺口；未配置 embedding 或 pgvector 不可用时退回关键词召回，JSONB payload 始终作为权威数据源。 |
| **可观测性和答辩解释成本** | `WorkflowNodeExecutor` 统一记录 AgentStep、AgentTrace、SSE 事件、耗时和异常；`AgentTraceContext` 捕获 prompt、原始模型输出、token 和 fallback 状态；前端 TraceDrawer、时间线、ReviewPanel 和指标面板用于现场解释“为什么这么分析、为什么打回、修复后变好了什么”。 |

### 部署与访问说明

1. 开发模式：先启动 PostgreSQL/pgvector，再运行 `mvn spring-boot:run` 启动后端，进入 `frontend` 执行 `npm run dev` 启动前端。
2. 一体化模式：执行 `docker compose up -d --build`，Dockerfile 会构建前端静态资源并注入 Spring Boot，最终通过 `http://localhost:8080` 访问。
3. 本地运行要求：JDK 17、Maven 3.9+、Node.js 18+、Docker Desktop。
4. 外部服务配置通过 `.env` 或环境变量提供，包括 `XIAOMI_LLM_API_KEY`、`DOUBAO_LLM_API_KEY`、`TAVILY_API_KEY`、`AI_INSIGHT_EMBEDDING_API_KEY` 等。
5. 未配置真实 API Key 时仍可通过 fallback 演示主流程、DAG、Schema、Reviewer 打回、报告版本、Trace 和指标。

---

## 五、结果说明

### 项目完成度

当前状态：**可用 Demo 版本 / 本地可完整体验版本**。

系统已完成核心功能闭环，支持从范围确认、证据采集、结构化抽取、竞品分析、报告生成、Reviewer 复核到自动或手动重跑的端到端流程。当前仓库统计：约 173 个后端 Java 源文件、43 个 Java 测试文件、79 次 Git 提交记录（以最终提交页为准）。

已完成能力：

- 六角色 Agent 协作与 LangGraph4j DAG 编排。
- Clarifier 前置范围确认。
- Researcher 公开 URL / 搜索采集、网页抓取、证据切片、调研计划、问卷和访谈设计。
- Extractor 竞品画像、功能树、定价模型和用户画像抽取。
- Analyst 结构化 Claim 生成、置信度评估和矩阵 / SWOT 放置边界判定。
- Writer 带 citation 报告草稿生成、矩阵 / SWOT 编排和版本化。
- Reviewer 引用覆盖、证据缺口、事实一致性和过度推断复核。
- ReviewDecision 驱动的自动打回和下游级联重跑。
- 用户资料包、文档上传、问卷结果导入和访谈洞察。
- PostgreSQL JSONB 持久化、明细表投影和可选 pgvector 语义召回。
- SSE 实时事件、TraceDrawer、运行指标和最近一次重跑改善展示。
- deterministic fallback、编码守卫和核心流程单元测试。

### 项目亮点 / 创新点

**亮点 1：把竞品分析从“一次性生成”升级为“可审计的 Agent 工单”**

常见竞品分析工具往往只输出一篇报告，过程不可见、证据不可查、结论难复核。AI Insight 把任务拆成范围确认、证据采集、知识抽取、结构化分析、报告撰写和 Reviewer 复核六个阶段，并把每一步写入 `AnalysisRun` 聚合。评委不仅能看到最终 `REPORT_DRAFT`，还可以在前端回放 Agent 时间线、Prompt、输入输出、证据来源、ReviewFinding 和 WorkflowTransition，从而回答“这份报告为什么可信、哪里还不确定、是谁做出的判断”。

**亮点 2：Reviewer 不只是打分，而是能驱动可定位的自动返工**

Reviewer 的输出不是一句“质量不合格”，而是结构化的 `ReviewDecision` 和 `ReviewRepairTask`：它会指出问题类型、关联 claim、citation、段落、目标 Agent 和期望修复方式。`REVIEW_GATE` 会把这些决策映射为 LangGraph4j 条件边，自动回到 Researcher 补证、Extractor 修复事实、Analyst 重做结论或 Writer 修订报告。系统还记录返工前后的 evidence、coverage gap、finding 和 Claim 覆盖变化，让“质检是否真的改善结果”可以被量化展示。

**亮点 3：结构化 Claim 作为 Analyst 与 Writer 之间的中间协议**

项目没有让 Analyst 直接产出一段不可控 Markdown，再由 Writer 复制粘贴进报告；当前设计是 Analyst 只生成可复核的 `AnalysisClaim`，包括置信度、证据 ID、支撑状态和推荐放置位置（矩阵 / SWOT / 补证清单）。Writer 再基于 claims、竞品画像和证据索引统一编排报告正文中的竞品矩阵与 SWOT。这个中间协议让“分析判断”和“报告表达”解耦，Reviewer 也能精确定位到底是上游结论问题还是下游写作问题。

**亮点 4：公开资料、上传文档、问卷访谈共用同一条证据链**

真实竞品分析不只依赖公开网页，还会结合内部访谈、问卷结果、产品资料和人工补充。AI Insight 将公开 URL、Tavily 搜索结果、上传文档、访谈摘要、问卷 CSV/XLSX 统一沉淀为 `EvidenceSource` 和 `EvidenceChunk`，再通过 citation、chunkKey 和 evidenceIds 串联到 Schema、Claim 和报告。Researcher 还能生成问卷草案和访谈提纲，并支持将调研回收结果转成结构化洞察，使系统从“搜索公开信息”进一步扩展到“主动调研 + 可溯源分析”。

**亮点 5：可量化的“重跑改善”而不是只展示最终结果**

很多 Agent 系统只能展示最终文本，很难证明多轮协作是否真的提升质量。AI Insight 在自动返工和手动重跑前后记录 `ReviewRepairDelta`，并在指标面板展示证据数、覆盖缺口、Reviewer finding、HIGH finding 和 Claim 覆盖率的变化。这样答辩时可以直接说明：补采是否增加了有效证据、质检问题是否减少、结论覆盖率是否提升，而不是只凭主观感受判断“报告变好了”。

**亮点 6：面向演示和真实使用的稳定降级机制**

项目没有把外部 LLM、搜索 API、embedding 或 pgvector 当成唯一成功路径。LLM 不可用时各 Agent 有 deterministic fallback；未配置 Tavily 时不会伪造搜索证据，而是明确提示补充 URL、问卷或访谈；未配置 embedding 时保留关键词召回；pgvector 写入异常时 JSONB payload 仍是权威状态。这种设计让系统在比赛演示、本地评审和真实网络波动下都能跑通核心流程，同时保留“哪些能力是外部服务增强”的可解释边界。

**亮点 7：为竞品分析定制的三栏工作台，而不是普通聊天界面**

前端不是通用聊天框，而是围绕竞品分析工单设计的 Workbench：左侧管理范围确认、上下文和资料包，中间展示 DAG、报告、问卷访谈和结构化 Schema，右侧集中呈现时间线、证据、Reviewer 质检和运行指标。这个界面让用户可以在同一页面完成“确认范围 -> 补充资料 -> 查看报告 -> 追溯证据 -> 定位质检问题 -> 触发重跑”，更贴近产品团队真实做竞品研究的工作方式。

---

## 六、选填补充材料

| 材料类别 | 材料名称 | 说明 / 链接 |
| --- | --- | --- |
| 产品材料 | 项目讲解 PPT | TODO：填写答辩 Slides 链接 |
| 产品材料 | 产品截图 | TODO：填写关键页面截图文件夹链接 |
| 技术材料 | API 接口说明 | 见 `README.md` 的 API 示例；核心接口位于 `AnalysisRunController` |
| 技术材料 | 系统架构文档 | `docs/architecture.md` |
| 技术材料 | 数据库设计说明 | `docs/architecture.md` 与 `PostgresAnalysisRunRepository`，核心为 JSONB 聚合 + 明细投影表 |
| AI 材料 | Prompt / Agent 协议 | `docs/agent.md`、`docs/architecture.md`，以及各 Agent Node 中的 prompt 模板 |
| AI 材料 | 评分点映射 | `docs/scoring-map.md` |
| AI 材料 | 演示脚本 | `docs/demo-script.md` |
| 合规材料 | 合规说明 | `docs/compliance-notes.md` |
| 过程材料 | 开发路线 | `docs/remaining-feature-roadmap.md`、`docs/research-agent-roadmap.md`、`docs/review-quality-loop-development-plan.md` |

---

## 七、合规声明

合规确认：

- 信息采集优先使用用户主动提供的公开 URL 或 Tavily 返回的公开网页，并在抓取前检查 robots.txt；抓取结果会记录 `EvidenceSource.complianceNote`。
- 未配置搜索 API Key 时，系统不会伪造搜索证据，只会使用用户提供 URL 和用户资料，并在调研计划中明确证据缺口。
- 用户提供资料支持敏感标记，内部资料以 internal-only 方式进入证据链；`PiiDesensitizer` 会对姓名、手机号、邮箱等个人信息进行脱敏处理。
- API Key 通过本地 `.env` 或环境变量维护，`.gitignore` 已排除 `.env`，README 和文档只使用占位示例。
- LLM 输出会经过 citation、证据覆盖和 Reviewer 复核约束，避免把不可核验内容伪装成事实。
- 工具、模型和数据使用应符合公司及挑战赛“工具与资源使用规范”要求；最终提交材料不展示真实密钥、敏感资料原文或未授权内容。
