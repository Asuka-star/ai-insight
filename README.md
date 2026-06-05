# AI Insight

AI Insight 是面向字节跳动 AI 全栈挑战赛 AI-3 课题的后端原型项目，目标是实现一个**可溯源、可复核、可观测、可重跑**的竞品分析 Agent 协作系统。

项目采用 Spring Boot + React Workbench 推进，已经打通 LangGraph4j 多 Agent 工作流、结构化 Schema、Reviewer 反馈闭环、SSE 运行态、PostgreSQL JSONB 持久化、历史会话和前端演示工作台。后续增强重点是更细粒度的指标评测、语义检索和更丰富的数据源。

## 项目定位

- 可溯源：资料片段、竞品画像、分析结论和报告引用都绑定 citationId。
- 可复核：Reviewer Agent 会检查引用缺失、结论风险和证据覆盖不足。
- 可观测：每个 Agent 的执行步骤、输入输出摘要、Prompt、模型输出、耗时、fallback 状态和 Trace 都挂在 `analysis_run` 上。
- 可重跑：支持单个 Agent 手动重跑，也支持 Reviewer 基于结构化决策触发一次自动补采与下游重跑。
- 结构化协作：Agent 之间通过 `ResearchPackage`、`CompetitorProfile`、`AnalysisClaim`、`ReviewDecision` 等强类型对象传递状态，而不是只依赖自然语言文本。

## 当前能力

已实现的原型链路：

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

## 核心模块

```text
src/main/java/com/aiinsight
├── agent              # Agent 节点接口与各角色节点
├── config             # 异步执行等 Spring 配置
├── controller         # REST 与 SSE 接口
├── dto                # 请求与事件 DTO
├── exception          # 业务异常
├── llm                # 基于 Spring AI 的小米 LLM 适配器与 fallback
├── model              # 运行态、Schema、质检和枚举模型
│   ├── enums          # Agent、状态、产物、复核动作等枚举
│   ├── review         # Reviewer 发现的问题与结构化决策
│   ├── run            # analysis_run、步骤、Trace、证据、产物和工作流跳转
│   └── schema         # 竞品画像、功能树、定价、用户画像和分析结论
├── repository         # PostgreSQL JSONB 运行态仓储
├── service            # 任务服务、事件推送、规则质检等服务
└── workflow           # LangGraph4j 状态图、图状态和节点执行器
```

## 项目文档

- `docs/architecture.md`：系统架构、Agent 协议、DAG 和持久化说明。
- `docs/development-guide.md`：开发维护入口、关键代码地图、清库和验证命令。
- `docs/demo-script.md`：答辩演示脚本和讲解顺序。
- `docs/scoring-map.md`：课题评分点与系统能力映射。
- `docs/remaining-feature-roadmap.md`：剩余功能和优先级记录。
- `docs/research-agent-roadmap.md`：信息采集 Agent、问卷访谈和调研能力后续路线。

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

运行测试：

```bash
mvn clean test
```

启动后端：

```bash
mvn spring-boot:run
```

默认端口：

```text
http://localhost:8080
```

启动前端：

```bash
cd frontend
npm install
npm run dev
```

## Spring AI 与 LLM 配置

项目通过 Spring AI 的 OpenAI ChatModel 接入 OpenAI-compatible 接口，业务侧仍然只依赖 `LlmClient` 门面。默认主模型使用小米接口；如果配置了豆包接口，Clarifier 会单独路由到豆包小模型。不要提交真实 API Key。

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

## Docker 依赖

项目已经准备了 PostgreSQL + pgvector 和 Redis 的 Docker Compose 配置。后端默认使用 PostgreSQL 保存 `analysis_run`，启动应用前需要先启动 PostgreSQL。

启动依赖：

```bash
docker compose up -d
```

仅启动 PostgreSQL：

```bash
docker compose up -d postgres
```

如果使用仓库自带 `docker-compose.yml`，宿主机端口是 `5433`。启动后端：

```powershell
$env:POSTGRES_URL="jdbc:postgresql://localhost:5433/ai_insight"
$env:POSTGRES_USER="ai_insight"
$env:POSTGRES_PASSWORD="ai_insight"
mvn spring-boot:run
```

当前 PostgreSQL 仓储会自动创建 `analysis_run` 表，并以 `jsonb` 保存完整运行态聚合，同时保留 `status`、`original_prompt`、`created_at`、`updated_at` 等查询字段。保存运行态时还会同步刷新 `analysis_artifact`、`agent_step`、`agent_trace`、`evidence_source`、`evidence_chunk`、`review_finding` 明细表，便于后续做分页查询、审计和指标看板。

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

- PostgreSQL + pgvector：当前已支持 embedding 缓存和 evidence chunk 向量投影；后续可继续补分页、过滤和审计查询接口。
- Redis：任务锁、短期事件广播、异步任务状态缓存。

## 后续增强方向

- 扩展采集来源，继续增强搜索结果、问卷、访谈、更新日志和公开评价数据。
- 强化问卷/访谈调研能力：补充访谈记录模板、多份访谈聚合、PII 脱敏和 LLM 精抽。
- 继续增强来源质量评分和质量原因展示；补采/重跑前后改善指标已经接入运行指标面板。
- 使用 Spring AI 继续优化文档切分、Embedding、向量召回和引用绑定链路。
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
- 前端问卷访谈模块支持编辑问卷、下载结果模板、导入 CSV/XLSX 问卷结果和展示访谈提纲
- 问卷结果会转成用户问卷证据和结构化洞察；同一任务只使用最新问卷结果，访谈证据按多份累积；导入后先标记为待应用，用户手动重跑 Extractor 后再刷新下游分析
- EvidenceChunk 证据切片与关键词召回接口
- 配置 embedding 后支持 evidence chunk 向量投影和语义召回，未配置时保留关键词 fallback
- Reviewer 自动打回 Researcher、Analyst 或 Writer 的反馈闭环
- SSE 事件推送
- 单 Agent 重跑接口
- 小米 LLM 可选接入
- 后端单元测试覆盖核心流程
