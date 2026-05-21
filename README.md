# AI Insight

AI Insight 是面向字节跳动 AI 全栈挑战赛 AI-3 课题的后端原型项目，目标是实现一个**可溯源、可复核、可观测、可重跑**的竞品分析 Agent 协作系统。

项目当前采用 Spring Boot 后端优先的方式推进，已经把 LangGraph4j 多 Agent 工作流、结构化 Schema、Reviewer 反馈闭环和 SSE 运行态打通，后续会逐步接入真实搜索、RAG、数据库持久化和前端工作台。

## 项目定位

- 可溯源：资料片段、竞品画像、分析结论和报告引用都绑定 citationId。
- 可复核：Reviewer Agent 会检查引用缺失、结论风险和证据覆盖不足。
- 可观测：每个 Agent 的执行步骤、输入输出摘要、Trace、产物和状态都挂在 `analysis_run` 上。
- 可重跑：支持单个 Agent 手动重跑，也支持 Reviewer 基于结构化决策触发一次自动补采与下游重跑。
- 结构化协作：Agent 之间通过 `ResearchPackage`、`CompetitorProfile`、`AnalysisClaim`、`ReviewDecision` 等强类型对象传递状态，而不是只依赖自然语言文本。

## 当前能力

已实现的原型链路：

```text
用户输入竞品分析需求
-> Clarifier 确认分析范围
-> Researcher 采集证据
-> Extractor 抽取竞品知识 Schema
-> Analyst 生成横向对比和分析结论
-> Writer 生成报告草稿
-> Reviewer 检查引用覆盖和证据缺口
   -> 如需补采，打回 Researcher 并重跑下游 Agent
-> Revision 输出最终报告
```

当前闭环示例：

1. Writer 首轮报告保留一个无引用机会点。
2. Reviewer 识别无引用结论，并根据 `ReviewDecision.RECOLLECT_EVIDENCE` 打回 Researcher。
3. Researcher 第二轮补充价格页和用户评价证据。
4. Extractor、Analyst、Writer、Reviewer 自动重跑。
5. Writer 补上引用后，Reviewer 最终通过。

## 核心模块

```text
src/main/java/com/aiinsight
├── agent              # Agent 节点接口与各角色节点
├── config             # 异步执行等 Spring 配置
├── controller         # REST 与 SSE 接口
├── dto                # 请求与事件 DTO
├── exception          # 业务异常
├── llm                # 小米 LLM OpenAI 兼容客户端与 fallback
├── model              # 运行态、Schema、质检和枚举模型
│   ├── enums          # Agent、状态、产物、复核动作等枚举
│   ├── review         # Reviewer 发现的问题与结构化决策
│   ├── run            # analysis_run、步骤、Trace、证据、产物和工作流跳转
│   └── schema         # 竞品画像、功能树、定价、用户画像和分析结论
├── repository         # 当前内存仓储，后续替换为数据库
├── service            # 任务服务、事件推送、规则质检等服务
└── workflow           # LangGraph4j 状态图、图状态和节点执行器
```

## 竞品知识 Schema

当前已落地的关键结构：

- `ResearchPackage`：Researcher 输出的资料包，包含证据来源和缺失证据类型。
- `CompetitorProfile`：单个竞品画像，包含定位、功能树、定价模型、用户画像、优劣势和证据 ID。
- `FeatureTree` / `FeatureNode`：竞品功能树。
- `PricingModel` / `PricingPlan`：定价策略与套餐信息。
- `UserPersona`：目标用户画像。
- `AnalysisClaim`：分析结论原子，包含结论类型、置信度和 evidenceIds。
- `ReviewDecision`：Reviewer 输出的结构化决策，用于驱动通过、修订或打回采集。
- `AgentTrace`：Agent 执行 Trace，后续会扩展 Prompt、模型名、Token 消耗等字段。
- `WorkflowTransition`：LangGraph4j 条件边决策记录，用于回放 REVIEW_GATE 的路由选择。

## 本地运行

要求：

- JDK 17
- Maven 3.9+

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

## 小米 LLM 配置

项目通过环境变量读取小米 LLM 配置。不要提交真实 API Key。

PowerShell 示例：

```powershell
$env:XIAOMI_LLM_API_KEY="your-api-key"
$env:XIAOMI_LLM_BASE_URL="https://token-plan-cn.xiaomimimo.com/v1"
$env:XIAOMI_LLM_MODEL="mimo-v2.5-pro"
mvn spring-boot:run
```

也可以在本地 `.env` 中维护配置，`.env` 已加入 `.gitignore`。

未配置 `XIAOMI_LLM_API_KEY` 时，Writer 和 Reviewer 会使用 deterministic fallback，保证本地测试和演示不依赖外部模型。

## API 示例

创建分析任务：

```bash
curl -X POST http://localhost:8080/api/analysis-runs \
  -H "Content-Type: application/json" \
  -d "{\"prompt\":\"分析 Notion 和飞书文档在 AI 协作文档方向的竞品机会\"}"
```

查询任务详情：

```bash
curl http://localhost:8080/api/analysis-runs/{runId}
```

订阅 SSE 进度：

```bash
curl -N http://localhost:8080/api/analysis-runs/{runId}/events
```

查看 LangGraph4j Mermaid 图：

```bash
curl http://localhost:8080/api/analysis-runs/workflow/mermaid
```

重跑单个 Agent：

```bash
curl -X POST http://localhost:8080/api/analysis-runs/{runId}/agents/REVIEWER/rerun
```

## Docker 依赖

项目已经准备了 PostgreSQL + pgvector 和 Redis 的 Docker Compose 配置，当前后端暂未强依赖它们。

启动依赖：

```bash
docker compose up -d
```

后续计划：

- PostgreSQL + pgvector：保存资料切片、Embedding、证据引用和任务运行态。
- Redis：任务锁、短期事件广播、异步任务状态缓存。

## 后续规划

- 接入真实公开信息采集，包括官网、价格页、文档、更新日志和公开评价。
- 使用 Spring AI 构建文档切分、Embedding、向量召回和引用绑定链路。
- 将内存仓储替换为 PostgreSQL 持久化模型。
- 实现前端 Workbench，展示 Agent 时间线、证据面板、报告版本、质检问题和单节点重跑。
- 补充评测指标：引用覆盖率、字段完整率、Reviewer 检出数、补采前后评分变化和生成耗时。

## 当前状态

当前阶段重点已经完成：

- 多 Agent 顺序协作
- LangGraph4j DAG 状态图编排
- REVIEW_GATE 条件边决策追踪
- 结构化 Schema 状态传递
- Reviewer 自动打回 Researcher 的反馈闭环
- SSE 事件推送
- 单 Agent 重跑接口
- 小米 LLM 可选接入
- 后端单元测试覆盖核心流程
