# AI Insight

项目目标不是只生成一篇竞0品报告，而是把竞品分析拆成一个**可确认范围、可追踪证据、可复核质量、可回放过程、可局部重跑**的工作流。

项目采用 Spring Boot + React Workbench 实现，已经打通 LangGraph4j 多 Agent 编排、结构化知识 Schema、Reviewer 反馈闭环、RAG 证据链、SSE 运行态、PostgreSQL JSONB + pgvector 持久化、历史会话、问卷访谈调研和前端演示工作台。未配置外部 LLM / 搜索 / embedding 时，系统会使用 deterministic fallback，保证本地测试不中断。

## 要求对齐

课题关注的是“数字调研小组”式的 Agent 协作能力：能够围绕用户给出的竞品分析目标，规划采集、沉淀证据、抽取结构化知识、生成分析报告，并通过复核机制提升输出可信度。AI Insight 当前按以下评分点落地：

| 课题 / 评分关注点  | 项目实现                                                                                              | 可演示证据                                          |
| ------------------ | ----------------------------------------------------------------------------------------------------- | --------------------------------------------------- |
| 多 Agent 分工协作  | Clarifier、Researcher、Extractor、Analyst、Writer、Reviewer 六角色协作，主流程由 LangGraph4j DAG 编排 | Agent DAG、时间线、单 Agent Trace                   |
| 输出可信度与证据链 | `EvidenceSource`、`EvidenceChunk`、`AnalysisClaim.evidenceIds`、报告 `[S1]` citation、citation hover  | EvidencePanel、报告 citation、Schema 中 evidenceIds |
| Reviewer 反馈闭环  | `ReviewDecision` 驱动 `REVIEW_GATE`，可打回 Researcher / Extractor / Analyst / Writer 并级联重跑      | ReviewPanel、WorkflowTransition、报告版本变化       |
| 工程完整度         | Spring Boot API、SSE、PostgreSQL JSONB 权威快照、明细投影表、pgvector 语义召回、测试覆盖              | REST API、TraceDrawer、`mvn test`、数据库表         |
| 业务工作台体验     | 范围确认、上下文补充、用户资料包、问卷访谈、报告/Schema/指标三栏工作台                                | React Workbench、历史任务、运行指标                 |
| 合规与可降级       | robots 检查、PII 脱敏、API Key 本地 `.env`、无 key fallback                                           | `complianceNote`、`.gitignore`、fallback Trace      |

## 项目定位

- 可溯源：公开网页、用户资料、问卷/访谈结果都会沉淀为 citation，资料片段、竞品画像、分析结论和报告引用都能回到来源。
- 可复核：Reviewer Agent 同时做规则质检和语义质检，检查引用缺失、事实一致性、过度推断、来源质量和证据覆盖不足。
- 可观测：每个 Agent 的执行步骤、输入输出摘要、Prompt、原始模型输出、耗时、token、fallback 状态和异常都挂在 `analysis_run` 上。
- 可重跑：支持 Agent 手动重跑，也支持 Reviewer 基于结构化 `ReviewDecision` 触发自动补采、重抽取、重分析或修订报告。
- 结构化协作：Agent 之间通过 `ResearchPackage`、`CompetitorProfile`、`AnalysisClaim`、`ReviewDecision`、`ReviewRepairTask` 等强类型对象传递状态，而不是只依赖自然语言文本。
- 可降级：外部 LLM、搜索、embedding 或 pgvector 不可用时，系统保留确定性 fallback、关键词召回和 JSONB 权威快照。

## 当前能力

已实现的端到端链路：

```text
用户输入竞品分析需求
-> Clarifier 生成范围确认草稿
-> 用户确认范围并启动主流程
-> Researcher 采集证据
-> Extractor 抽取竞品知识 Schema
-> Analyst 生成横向对比和分析结论
-> Writer 生成报告草稿
-> Reviewer 检查引用覆盖和证据缺口
   -> 如需补采、重做分析或修订报告，打回对应 Agent 并重跑下游节点
-> Reviewer 通过或达到自动返工上限后结束流程
```

当前闭环示例：

1. Writer 首轮报告保留一个无引用机会点。
2. Reviewer 识别无引用结论，并根据 `ReviewDecision.RECOLLECT_EVIDENCE` 打回 Researcher。
3. Researcher 第二轮补充价格页和用户评价证据。
4. Extractor、Analyst、Writer、Reviewer 自动重跑。
5. Writer 补上引用后，Reviewer 最终通过。
6. 前端展示 Writer 最新报告草稿，并保留 Reviewer 复核结果供人工确认。

说明：当前流程已经移除独立封版 Agent；历史运行中的最终报告类产物仍可读取，但新运行以 Writer 的最新报告草稿作为报告展示内容。

核心能力清单：

- 范围确认：Clarifier 将自然语言需求整理成行业、竞品、分析维度、来源偏好和报告目标，用户确认后再启动主流程。
- 公开采集：Researcher 支持用户 URL、Tavily 搜索、网页抓取、JS 渲染兜底、robots 合规说明、来源去重和采集子任务。
- RAG 证据：EvidenceChunk 切片支持关键词召回；配置 embedding 后写入 pgvector 并启用语义召回。
- 结构化抽取：Extractor 生成 `CompetitorProfile`、功能树、定价模型、用户画像和事实集。
- 分析产物：Analyst 生成带证据 ID 的 `AnalysisClaim`，并标注哪些结论适合进入矩阵 / SWOT / 补证清单。
- 报告生成：Writer 基于 claims、竞品画像和证据索引编排 `REPORT_DRAFT`，在报告正文中统一写出竞品矩阵和 SWOT，同类 artifact 自动版本化。
- 复核闭环：Reviewer 生成 `ReviewFinding`、`ReviewDecision`、`ReviewRepairTask`，并由 `REVIEW_GATE` 路由返工。
- 主动调研：Researcher 生成问卷草案和访谈提纲，前端支持导出腾讯问卷 DSL、导入 CSV/XLSX 调研结果并转为证据和洞察。
- 用户资料包：支持上传文档、补充 URL、访谈摘要、问卷结果和内部资料；敏感资料可标记 internal-only。
- 运行指标：展示步骤数、证据数、质检问题、引用数、Claim 覆盖率、Schema 完整率、打回次数、token、耗时和最近一次重跑改善。
- 可观测性：TraceDrawer 可回放 Prompt、输入快照、输出摘要、原始模型输出、模型名、token、耗时、fallback 和异常。

## 核心模块

```text
src/main/java/com/aiinsight
├── agent              # Agent 节点接口与各角色节点；Analyst 产出 claims，Writer 负责报告正文中的矩阵 / SWOT 编排
├── config             # 异步执行、HTTP 客户端、代理、SSE 断连处理等 Spring 配置
├── controller         # REST 与 SSE 接口
├── dto                # 请求与事件 DTO
├── exception          # 业务异常
├── llm                # 基于 Spring AI 的 OpenAI-compatible LLM 路由、配置与 fallback
├── observability      # Agent Trace 上下文传播
├── model              # 运行态聚合、Schema、Reviewer 质检模型和枚举
│   ├── enums          # Agent、状态、artifact、复核动作等枚举
│   ├── review         # Reviewer 的 finding、decision、repair task
│   ├── run            # analysis_run、步骤、Trace、证据、artifact、上下文和工作流跳转
│   └── schema         # ResearchPackage、CompetitorProfile、FeatureTree、PricingModel、AnalysisClaim 等结构化对象
├── repository         # PostgreSQL JSONB 权威快照、明细投影表和 pgvector 相关仓储逻辑
├── service            # 任务编排、资料采集、文档导入、证据切片 / 召回、搜索、embedding、指标与质检支撑服务
└── workflow           # LangGraph4j DAG、REVIEW_GATE 路由、图状态和节点执行器
```

## 竞品知识 Schema

当前已落地的关键结构：

- `ResearchPackage`：Researcher 输出的资料包，包含证据来源和缺失证据类型。
- `CompetitorProfile`：单个竞品画像，包含定位、功能树、定价模型、用户画像、优劣势和证据 ID。
- `FeatureTree` / `FeatureNode`：竞品功能树。
- `PricingModel` / `PricingPlan`：定价策略与套餐信息。
- `UserPersona`：目标用户画像。
- `AnalysisClaim`：分析结论原子，包含结论类型、置信度和 evidenceIds。
- `ReviewDecision`：Reviewer 输出的结构化决策，用于驱动通过、修订、重做分析或打回采集。
- `AgentTrace`：Agent 执行 Trace，记录 stepId、Prompt、模型名、原始模型输出、fallback 状态、耗时、异常和 Token 消耗。
- `WorkflowTransition`：LangGraph4j 条件边决策记录，用于回放 REVIEW_GATE 的路由选择。

## 本地运行

要求：

- JDK 17
- Maven 3.9+
- Node.js 18+
- Docker Desktop（用于本地 PostgreSQL/Redis）

### 方式一：开发模式启动

启动依赖：

```bash
docker compose up -d postgres
```

运行后端：

```bash
mvn spring-boot:run
```

后端默认端口：

```text
http://localhost:8080
```

运行前端：

```bash
cd frontend
npm install
npm run dev
```

前端默认端口：

```text
http://localhost:5173
```

Vite 开发服务会把 `/api` 请求代理到 `http://localhost:8080`。

### 方式二：Docker Compose 一体化启动

当前 Dockerfile 会构建前端静态资源并注入 Spring Boot，`app` 容器通过 `8080` 对外提供前后端一体化服务：

```bash
docker compose up -d --build
```

访问：

```text
http://localhost:8080
```

说明：仓库根目录存在 `.env` 时，Compose 会读取其中的 LLM、搜索和 embedding 配置；不要提交真实 API Key。

### 验证命令

```bash
mvn clean test
```

前端构建：

```bash
cd frontend
npm run build
```

如果改动了包含中文的 `.java`、`.ts`、`.tsx`、`.css`、`.xml`、`.md` 或资源文本文件，请额外运行编码守卫：

```bash
mvn -Dtest=SourceEncodingGuardTest test
```

## Spring AI 与 LLM 配置

项目通过 Spring AI 的 OpenAI ChatModel 接入 OpenAI-compatible 接口，业务侧统一依赖 `LlmClient` 门面。默认主模型使用小米接口；如果配置了豆包接口，Clarifier 会单独路由到豆包小模型。不要提交真实 API Key。

PowerShell 示例：

```powershell
$env:XIAOMI_LLM_API_KEY="your-api-key"
$env:XIAOMI_LLM_BASE_URL="https://token-plan-cn.xiaomimimo.com/v1"
$env:XIAOMI_LLM_COMPLETIONS_PATH="/chat/completions"
$env:XIAOMI_LLM_MODEL="mimo-v2.5-pro"
$env:DOUBAO_LLM_API_KEY="your-doubao-key"
$env:DOUBAO_LLM_BASE_URL="https://ark.cn-beijing.volces.com/api/v3"
$env:DOUBAO_LLM_COMPLETIONS_PATH="/chat/completions"
$env:DOUBAO_LLM_ENDPOINT_ID="ep-xxxxxxxx"
$env:DOUBAO_LLM_DISPLAY_MODEL="Doubao-Seed-2.0-lite"
mvn spring-boot:run
```

`XIAOMI_LLM_BASE_URL` 当前默认包含 `/v1`，因此 `XIAOMI_LLM_COMPLETIONS_PATH` 默认是 `/chat/completions`。如果后续换成不带 `/v1` 的 OpenAI-compatible base url，可以把 completions path 改成 `/v1/chat/completions`。

`DOUBAO_LLM_ENDPOINT_ID` 是火山方舟控制台里的 Endpoint ID，形如 `ep-...`；它会作为 OpenAI-compatible 请求里的 `model` 参数。`DOUBAO_LLM_API_KEY` 或 `DOUBAO_LLM_ENDPOINT_ID` 为空时，Clarifier 会继续使用默认主模型；两者都配置后，仅 `CLARIFIER/scope-clarification` 走豆包小模型，Researcher、Extractor、Analyst、Writer 和 Reviewer 仍使用默认主模型。

也可以在本地 `.env` 中维护配置，`.env` 已加入 `.gitignore`。

`.env` 示例：

```env
XIAOMI_LLM_API_KEY=your-xiaomi-key
DOUBAO_LLM_API_KEY=your-doubao-key
DOUBAO_LLM_ENDPOINT_ID=ep-xxxxxxxx
TAVILY_API_KEY=your-tavily-key
POSTGRES_URL=jdbc:postgresql://localhost:5433/ai_insight
POSTGRES_USER=ai_insight
POSTGRES_PASSWORD=ai_insight
```

启动时 Spring Boot 会自动读取项目根目录 `.env`。如果同时设置了系统环境变量，系统环境变量优先。

未配置 `XIAOMI_LLM_API_KEY` 时，Writer 和 Reviewer 会使用 deterministic fallback，保证本地测试和演示不依赖外部模型。

未配置 `TAVILY_API_KEY` 时，Researcher 不会生成伪造搜索证据，只会抓取用户提供的 URL，并在调研计划中提示需要补充公开来源、问卷或访谈资料。

Embedding 配置可选。未配置 `AI_INSIGHT_EMBEDDING_API_KEY` 时，证据召回使用关键词 fallback；配置后会把 evidence chunk embedding 缓存到 PostgreSQL，并在 pgvector 可用时启用语义召回。

## API 示例

创建分析任务：

```bash
curl -X POST http://localhost:8080/api/analysis-runs \
  -H "Content-Type: application/json" \
  -d "{\"prompt\":\"分析 Notion 和飞书文档在 AI 协作文档方向的竞品机会\"}"
```

创建带公开来源 URL 的分析任务：

```bash
curl -X POST http://localhost:8080/api/analysis-runs \
  -H "Content-Type: application/json" \
  -d "{\"prompt\":\"分析 Notion 和飞书文档在 AI 协作文档方向的竞品机会\",\"sourceUrls\":[\"https://www.notion.com/product\",\"https://docs.feishu.cn/\"]}"
```

查询任务详情：

```bash
curl http://localhost:8080/api/analysis-runs/{runId}
```

查询 Agent Trace：

```bash
curl http://localhost:8080/api/analysis-runs/{runId}/traces
```

查询运行指标：

```bash
curl http://localhost:8080/api/analysis-runs/{runId}/metrics
```

查询证据片段召回：

```bash
curl "http://localhost:8080/api/analysis-runs/{runId}/retrieval?query=价格%20套餐&topK=5"
```

订阅 SSE 进度：

```bash
curl -N http://localhost:8080/api/analysis-runs/{runId}/events
```

重跑单个 Agent：

```bash
curl -X POST http://localhost:8080/api/analysis-runs/{runId}/agents/REVIEWER/rerun
```

导出问卷 DSL：

```bash
curl http://localhost:8080/api/analysis-runs/{runId}/surveys/template
```

导入问卷结果或上传用户文档需要使用 `multipart/form-data`，建议直接通过前端工作台操作。

## 数据与部署说明

项目使用 PostgreSQL + pgvector 保存运行态和证据向量，Redis 预留给任务锁与事件缓存。后端默认连接：

```text
jdbc:postgresql://localhost:5433/ai_insight
```

如果使用仓库自带 `docker-compose.yml` 一体化启动，Spring Boot 容器会通过 Docker 网络访问 `postgres:5432`，外部只需要访问 `http://localhost:8080`。

如果希望在宿主机用 `mvn spring-boot:run` 直连 Compose 中的 PostgreSQL，需要确保 compose 文件将 PostgreSQL 端口映射到宿主机，例如 `5433:5432`；或者自行启动一个本地 PostgreSQL/pgvector 实例并通过 `POSTGRES_URL` 指向它。

当前 PostgreSQL 仓储会自动创建 `analysis_run` 表，并以 `jsonb` 保存完整运行态聚合，同时保留 `status`、`original_prompt`、`created_at`、`updated_at` 等查询字段。保存运行态时还会同步刷新 `analysis_artifact`、`agent_step`、`agent_trace`、`evidence_source`、`evidence_chunk`、`review_finding` 明细表，便于后续做分页查询、审计和指标看板。配置 embedding 后，还会写入 `evidence_chunk_embedding` / `global_evidence_chunk_embedding` pgvector 投影；pgvector 不可用时 JSONB payload 仍是权威数据源。

清理本地历史会话：

```powershell
docker exec -it ai-insight-pg psql -U ai_insight -d ai_insight -c "truncate table analysis_run cascade;"
```

如果使用 compose 默认容器名，也可以改用：

```powershell
docker compose exec postgres psql -U ai_insight -d ai_insight -c "truncate table analysis_run cascade;"
```

如需同时清掉网页抓取缓存：

```powershell
docker exec -it ai-insight-pg psql -U ai_insight -d ai_insight -c "truncate table fetched_page_cache;"
```

可选后续增强：

- PostgreSQL 明细表：继续补 artifact、trace、evidence 和 review finding 的分页、过滤和审计查询接口。
- Redis：接入任务锁、短期事件广播和异步任务状态缓存。

## 后续增强方向

- 扩展采集来源，继续增强更新日志、公开评价、行业报告和更稳定的搜索结果筛选。
- 强化访谈管理：补充访谈记录模板、多份访谈聚合和人工审批流。
- 继续增强来源质量评分和质量原因展示，让官方/一手/第三方来源差异更直观。
- 使用 Spring AI 继续优化文档切分、Embedding、向量召回、引用绑定和证据支撑匹配。
- 将前端的 trace、artifact、evidence 查询逐步切到 PostgreSQL 明细表，并补充分页与过滤。
- 继续优化前端 Workbench 的报告对比、历史任务筛选和大包体代码拆分。
- 扩展评测指标：补采前后评分变化、更多质量规则和跨样例 benchmark。

## 当前状态

当前阶段重点已经完成：

- 多 Agent 顺序协作
- Clarifier 前置范围确认
- LangGraph4j DAG 状态图编排
- REVIEW_GATE 条件边决策追踪
- 结构化 Schema 状态传递
- Agent Prompt、模型输出、fallback、耗时和异常 Trace
- analysis_run PostgreSQL 持久化
- Researcher 支持用户提供公开 URL 并沉淀为可引用证据
- Researcher 支持问卷草案、访谈提纲、采集子任务、覆盖缺口和补采目标
- 前端问卷访谈模块支持编辑问卷、导出腾讯问卷内容 DSL、导入 CSV/XLSX 调研数据和展示访谈提纲
- 问卷结果会转成用户问卷证据和结构化洞察；同一任务只使用最新问卷结果，访谈证据按多份累积；导入后先标记为待应用，用户手动重跑 Extractor 后再刷新下游分析
- EvidenceChunk 证据切片与关键词召回接口
- 配置 embedding 后支持 evidence chunk 向量投影和语义召回，未配置时保留关键词 fallback
- Reviewer 自动打回 Researcher、Analyst 或 Writer 的反馈闭环
- SSE 事件推送
- 单 Agent 重跑接口
- 小米 LLM 可选接入
- 后端单元测试覆盖核心流程
