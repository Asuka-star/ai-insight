# 并发采集 Agent 优化开发文档

## 1. 文档目的

本文记录 AI Insight 信息采集 Agent 的并发优化和后续开发方案，用于后端实现、前端展示、Reviewer 补采和 Lead Research Agent 规划对齐。

本轮优化的目标不是通过减少采集功能来提速，而是让同样的研究量以更好的方式执行：

```text
更强并发
更少重复搜索和重复抓取
更明确的证据预算
更可观察的子任务状态
更精准的 Reviewer 补采
```

当前系统已经具备 Tavily 搜索、网页抓取、正文清洗、证据沉淀、RAG 检索、Reviewer 返工和 SSE 事件能力。下一阶段重点是把采集过程从一次性内部 batch 升级为可规划、可调度、可观察、可补采的研究子任务系统。

## 2. 当前判断

### 2.1 已有能力

- `SearchQueryPlanner` / `LlmSearchQueryPlanner` 可以根据竞品、维度、来源偏好和 Reviewer 返工要求生成搜索 query。
- `LlmSearchCandidateSelector` 可以从搜索候选里选择更值得抓取的 URL。
- `SourceCollectionService` 已经支持按 batch 并行执行搜索采集，但 batch 内部搜索和抓取仍偏串行。
- `WebPageFetchService` 负责网页抓取、robots 检查、缓存、正文清洗、质量判断和 Playwright 渲染兜底。
- `EvidenceSource` / `EvidenceChunk` 是后续 Extractor、Analyst、Writer 和 Reviewer 的证据主链路。
- `EvidenceRetrievalService` 可以从已有证据和用户资料中做 RAG 检索。
- `AnalysisEventBroker` 已支持 run 级 SSE，后续可以复用来推送采集子任务事件。
- `AnalysisRun` 通过 `run_payload` JSON 持久化完整聚合状态，第一阶段不必急着新增独立表。

### 2.2 当前不足

- 现有并发主要是竞品 batch 级并发，query 搜索、URL 抓取、RAG 检索还没有拆成独立并发队列。
- 采集 batch 是内部实现，前端和用户看不到每个采集子任务的状态、耗时、失败原因和证据采纳情况。
- 竞品之间缺少明确证据预算，容易出现某些竞品证据很多、某些竞品证据不足。
- Reviewer 返工虽然可以触发重新采集，但还没有稳定落到“某竞品 + 某维度 + 某来源类型”的补采子任务。
- 如果简单把每个竞品做成完全独立 Agent，会带来重复 Tavily 搜索、重复网页抓取和证据口径不统一的问题。

## 3. 核心设计原则

### 3.1 不通过删减功能提速

本方案不以减少 query、减少候选、减少证据类型作为主要提速手段。提速来自：

- 搜索和抓取分层并发。
- 多个竞品 worker 并发判断缺口。
- 全局 URL 去重和页面缓存复用。
- 证据预算控制，减少无意义重复采纳。
- 超时、失败和回填由确定性调度器统一处理。

### 3.2 竞品可以拆 worker，但资源调用必须共享

推荐架构不是“每个竞品一个完整独立 Agent 各自搜索和抓取”，而是：

```text
ResearchCoordinator
  -> CompetitorResearchWorker[竞品 A]
  -> CompetitorResearchWorker[竞品 B]
  -> CompetitorResearchWorker[竞品 C]
  -> SharedSearchExecutor
  -> SharedFetchExecutor
  -> SharedEvidenceRegistry
  -> EvidenceRanker
```

含义：

- 竞品 worker 负责判断本竞品缺哪些维度、哪些证据已经够、是否需要补采。
- 搜索、抓取、URL 去重、缓存、证据预算和证据落库走共享层。
- 这样既能并发，又避免重复调用 Tavily、重复抓同一个 URL、重复采纳相似证据。

### 3.3 Lead Agent 负责规划，确定性代码负责调度

Lead Research Agent 可以参与生成研究计划，但不直接控制线程、并发数、重试、限流、超时和预算。

推荐分工：

```text
LeadResearchPlanner
  判断需要研究哪些竞品、维度、来源类型和补采重点

ResearchTaskScheduler
  把计划转成可执行队列，控制并发、重试、取消、超时和状态更新

Collector Subtask
  执行单个 competitor + dimension/sourceType 采集任务

EvidenceRanker
  做证据去重、质量评分、预算控制和采纳排序
```

### 3.4 先显性化，再智能化

第一阶段不直接重构成大量新服务，也不先上复杂 Lead Agent 规划。优先把当前采集过程显性化为可记录、可展示、可测试的子任务。

## 4. 目标架构

```text
AnalysisRun
  -> ResearchCollectionPlan
     -> ResearchSubtask[]
  -> ResearchTaskScheduler
     -> SearchExecutor
        -> TavilySearchProvider
        -> CandidateUrl[]
     -> CandidateDeduplicator
     -> FetchExecutor
        -> WebPageFetchService
        -> FetchedPage[]
     -> RagExecutor
        -> EvidenceRetrievalService
        -> RetrievedChunk[]
  -> EvidenceRanker
     -> Accepted EvidenceSource / EvidenceChunk
  -> CompetitorResearchWorker
     -> per-competitor coverage and gaps
  -> Extractor / Analyst / Writer / Reviewer
  -> Reviewer Repair Targets
     -> high-priority ResearchSubtask[]
```

## 5. 命名和模型约束

项目中已经存在 `com.aiinsight.model.schema.ResearchPlan`，用于当前研究资料包和后续 Agent 传递。为了避免概念冲突，本方案不直接复用或替换该类，而是新增更具体的采集模型。

### 5.1 ResearchCollectionPlan

`ResearchCollectionPlan` 表示一次采集阶段的可执行计划。

```java
public class ResearchCollectionPlan {
    private UUID id;
    private UUID runId;
    private String goal;
    private List<String> competitors;
    private List<String> dimensions;
    private List<ResearchSubtask> subtasks;
    private String planSource; // RULE_BASED, REVIEW_REPAIR, LEAD_AGENT
    private Instant createdAt;
}
```

第一阶段建议挂到 `ResearchPackage` 上，并随 `AnalysisRun.run_payload` 一起持久化，不新增独立表。

### 5.2 ResearchSubtask

`ResearchSubtask` 是并发采集的最小可观察任务单元。

```java
public class ResearchSubtask {
    private UUID id;
    private UUID runId;
    private String competitorName;
    private String dimension;
    private List<String> queries;
    private List<String> sourcePreferences;
    private ResearchSubtaskStatus status;
    private ResearchSubtaskPriority priority;
    private int attempt;
    private int candidateUrlCount;
    private int fetchedPageCount;
    private int acceptedEvidenceCount;
    private String failureReason;
    private long searchLatencyMs;
    private long fetchLatencyMs;
    private long ragLatencyMs;
    private Instant startedAt;
    private Instant finishedAt;
}
```

推荐状态：

```text
PENDING
SEARCHING
SEARCHED
FETCHING
RETRIEVING_RAG
RANKING
SUCCEEDED
FAILED
CANCELLED
SKIPPED
```

推荐优先级：

```text
REVIEW_REPAIR
USER_SOURCE_URL
OFFICIAL_SOURCE
NORMAL_SEARCH
BACKFILL
```

### 5.3 CandidateUrl

`CandidateUrl` 记录搜索阶段拿到但未必最终采纳的 URL。

```java
public class CandidateUrl {
    private UUID id;
    private UUID runId;
    private UUID subtaskId;
    private String url;
    private String normalizedUrl;
    private String title;
    private String snippet;
    private String sourceProvider; // TAVILY, OFFICIAL_DISCOVERY, MANUAL
    private String sourceTypeHint;
    private double searchScore;
    private boolean duplicate;
    private UUID duplicateOf;
    private String rejectionReason;
}
```

### 5.4 EvidenceBudget

`EvidenceBudget` 用于控制每个竞品和维度的证据目标，避免证据分布失衡。

```java
public class EvidenceBudget {
    private String competitorName;
    private String dimension;
    private int minOfficialSources;
    private int minThirdPartySources;
    private int minRagChunks;
    private int maxAcceptedSources;
}
```

默认预算建议：

```text
每个竞品：
  官网 / 产品页：1-2 条
  定价：1 条
  文档 / 功能：1-3 条
  客户案例：0-2 条
  新闻 / 第三方：1-2 条
  用户资料 RAG：1-3 条
```

预算不是硬限制，而是排序和采纳策略，用来避免某个竞品独占证据额度。

## 6. 采集执行流程

### 6.1 第一轮采集

```text
1. 根据 AnalysisRequirement 生成 ResearchCollectionPlan
2. 按 competitor + dimension/sourceType 拆分 ResearchSubtask
3. ResearchTaskScheduler 将 subtask 放入调度队列
4. SearchExecutor 并发执行 query 搜索
5. 汇总 CandidateUrl，统一 URL normalize 和去重
6. LlmSearchCandidateSelector / 规则排序决定优先抓取候选
7. FetchExecutor 并发抓取候选 URL
8. RagExecutor 并行检索用户资料和已有证据
9. EvidenceRanker 按质量、来源类型、竞品和维度预算采纳证据
10. 保存 EvidenceSource / EvidenceChunk
11. CompetitorResearchWorker 汇总每个竞品覆盖情况和缺口
12. 将采集摘要交给 Extractor
```

### 6.2 并发层次

推荐拆成三个受控并发池：

```text
SearchExecutor
  控制 Tavily / 搜索 API 并发

FetchExecutor
  控制网页抓取、正文清洗、Playwright fallback 并发

RagExecutor
  控制用户资料和已有证据检索并发
```

原因：

- Tavily 受外部 API 限流影响。
- 网页抓取受网络、robots、host 限速和 Playwright 成本影响。
- RAG 检索受 embedding / vector 检索成本影响。
- 三者混在一个线程池里，会导致慢网页抓取拖住搜索，或搜索限流拖住 RAG。

### 6.3 推荐配置

```yaml
ai-insight:
  source-collection:
    max-parallel-research-subtasks: 8
    max-parallel-searches: 4
    max-parallel-fetches: 8
    max-parallel-rag-retrievals: 4
    max-fetches-per-host: 1
    subtask-timeout: 90s
    search-timeout: 30s
    fetch-timeout: 30s
    max-retry-attempts: 2
    min-evidence-per-competitor: 4
    max-evidence-per-competitor: 10
```

配置默认值应保守，允许后续通过环境配置放大。

## 7. Reviewer 精准补采

Reviewer 不应只返回“重新采集”，而应尽量返回结构化补采目标。

示例：

```json
{
  "repairAction": "RECOLLECT",
  "targets": [
    {
      "competitorName": "Notion",
      "dimension": "pricing",
      "reason": "缺少官方定价来源",
      "sourcePreferences": ["official_site", "pricing_page"],
      "queries": ["Notion pricing", "Notion plans"]
    }
  ]
}
```

当前项目已经有 `ReviewDecision.repairTasks`，第一阶段可以先从现有 repair task 文本中提取竞品和证据类型，生成高优先级 `ResearchSubtask`。后续再扩展 `ReviewDecision`，让 Reviewer 直接输出结构化 repair target。

补采原则：

- 只补缺口，不泛泛重搜。
- 补采任务优先级高于普通搜索。
- 补采完成后只级联必要下游 Agent，避免整条链路无意义重跑。
- 前端展示补采原因、目标竞品、目标维度和最终证据覆盖变化。

## 8. API 和 SSE 设计

### 8.1 查询采集计划

```http
GET /api/analysis-runs/{runId}/research-collection-plan
```

返回当前 run 的 `ResearchCollectionPlan`。

### 8.2 查询采集子任务

```http
GET /api/analysis-runs/{runId}/research-subtasks
```

支持过滤：

```text
?status=FAILED
?competitorName=Notion
?dimension=pricing
```

### 8.3 重跑采集子任务

```http
POST /api/analysis-runs/{runId}/research-subtasks/{subtaskId}/rerun
```

第一阶段可以先只提供查询接口，重跑接口放到 Reviewer 精准补采阶段实现。

### 8.4 SSE 事件

复用现有 `AnalysisEventBroker`，新增事件类型：

```text
research.collection.plan.created
research.subtask.started
research.subtask.search.completed
research.subtask.fetch.completed
research.subtask.rag.completed
research.subtask.failed
research.subtask.succeeded
research.evidence.accepted
research.evidence.rejected
```

事件消息应包含最小必要字段：

```text
runId
subtaskId
competitorName
dimension
status
message
timestamp
```

## 9. 前端展示建议

前端不要一开始展示所有 URL 细节，避免信息过载。建议分层展示。

### 9.1 摘要层

展示：

- 当前采集阶段状态。
- 子任务总数、成功数、失败数、运行中数量。
- 搜索 query 数、候选 URL 数、抓取页数、采纳证据数。
- 总耗时、搜索耗时、抓取耗时、RAG 耗时。

### 9.2 子任务列表

展示：

```text
Collector-1  Notion / 定价       FETCHING
Collector-2  Notion / 用户评价   SEARCHED
Collector-3  飞书文档 / 功能     RETRIEVING_RAG
Collector-4  Confluence / 安全   SUCCEEDED
```

### 9.3 展开详情

展开后展示：

- 查询词。
- 候选 URL。
- 抓取成功页面。
- 抓取失败原因。
- RAG 命中的用户资料。
- 最终采纳证据。
- 被丢弃原因，例如重复、正文过短、低质量、非目标维度、预算已满。

## 10. 推荐开发顺序

### 当前落地进度

- P1 已落地：`ResearchCollectionPlan`、`ResearchSubtask`、采集状态/耗时/计数记录、计划与子任务查询 API 已接入。
- P2 已落地：搜索并发和候选抓取并发已拆开配置，抓取阶段按 `max-parallel-fetches` 并发执行，候选池回填不再重复触发搜索。
- P3 已落地：`CandidateUrl` 记录、全局候选 URL 去重、`EvidenceBudget` 显式预算和预算采纳限制已接入。
- P4 已落地：新增竞品级 coverage/gap 计算，按 competitor + dimension/sourceType 输出缺口与 backfill target。
- P5 已落地：新增 coverage gap / repair target 查询 API，并在 Researcher / Reviewer 完成后推送结构化采集 SSE payload；前端右侧采集面板已展示 Lead 规划、子任务、覆盖缺口和补采目标。
- P6 已落地：Reviewer repair task 会补齐 competitor、dimension、sourcePreferences、queries，补采 query planner 优先使用这些结构化目标。
- P7 已落地：新增确定性 `LeadResearchPlanner` 和 `LeadResearchPlan`，挂入 `ResearchCollectionPlan`，记录目标、关注维度、推荐来源类型、补采优先级和规划理由；`planSource` 保持 `RULE_BASED` / `REVIEW_REPAIR` 兼容语义。
- 当前文档内核心后端与前端开发项均已实现；后续可选增强是把 `LeadResearchPlanner` 升级为 LLM + 规则兜底的混合规划器，并补充更细的前端 URL 展开明细。

### P1：采集子任务模型和状态记录

目标：不大改采集逻辑，先把当前 batch 和候选抓取过程显性化。

开发内容：

- 新增 `ResearchCollectionPlan`、`ResearchSubtask`、状态和优先级枚举。
- 将采集计划挂到 `ResearchPackage`。
- 在 `SourceCollectionService` / 新增 scheduler 中创建和更新 subtask 状态。
- 记录 query 数、候选数、抓取数、采纳证据数、失败原因和耗时。
- 新增查询采集计划和子任务的后端 API。

验收标准：

- 用户或前端可以看到每个竞品和维度的采集状态。
- 失败任务有明确失败原因。
- 不影响现有报告生成链路。

### P2：搜索队列和抓取队列分离

目标：提升真实并发能力，不靠减少采集量提速。

开发内容：

- 拆出 `SearchExecutor` 和 `FetchExecutor`。
- 增加搜索并发、抓取并发、RAG 并发配置项。
- 候选 URL 抓取改成并发执行。
- 增加 subtask timeout 和 retry。
- 保留现有 LLM 候选选择逻辑，但抓取阶段按并发池执行。

验收标准：

- 搜索慢不会阻塞网页抓取，网页抓取慢不会阻塞搜索。
- 多竞品任务下采集耗时更稳定。
- 超时任务能被标记，其他任务继续执行。

### P3：全局候选去重和证据预算

目标：减少重复抓取，提升证据分布均衡性。

开发内容：

- 新增 `CandidateUrl` 记录或内存结构。
- 抓取前统一 URL normalize 和去重。
- 引入 `EvidenceBudget`。
- `EvidenceRanker` 按竞品和维度控制采纳数量。
- 记录 rejected evidence reason。

验收标准：

- 同一 URL 不会被多个 subtask 重复抓取。
- 每个竞品至少获得基础证据覆盖。
- 某个竞品不会独占大部分证据额度。

### P4：竞品 Worker 并发缺口判断

目标：实现“每个竞品一个 worker”的分析决策层并发，但保持资源调用共享。

开发内容：

- 新增 `CompetitorResearchWorker`。
- 每个 worker 从共享 evidence pool 判断本竞品覆盖情况。
- 输出本竞品缺口和建议补采 subtask。
- 由 `ResearchCoordinator` 合并所有 worker 的结果。

验收标准：

- 多竞品缺口判断可以并发执行。
- A 竞品的证据不会误判为 B 竞品满足。
- 补采建议能精确到 competitor + dimension/sourceType。

### P5：前端采集面板和 SSE 增强

目标：让用户能看懂并发采集 Agent 正在做什么。

开发内容：

- 新增后端查询接口。
- 新增采集 SSE 事件。
- 前端增加采集摘要和子任务列表。
- 展开后显示 URL、失败原因、采纳证据和丢弃原因。

验收标准：

- 用户可以实时看到采集任务推进。
- 失败、超时、低质量来源可解释。
- 前端默认展示摘要，不造成信息过载。

### P6：Reviewer 精准补采

目标：把 Reviewer 返工从“重跑采集”升级成“缺什么补什么”。

开发内容：

- 从 `ReviewDecision.repairTasks` 生成高优先级 `ResearchSubtask`。
- 后续扩展结构化 repair target。
- 补采完成后只级联必要下游 Agent。
- 前端展示补采任务和触发原因。

验收标准：

- Reviewer 指出的证据缺口可以生成精准补采任务。
- 补采完成后 citation coverage 提升。
- 用户能看懂为什么系统发起第二轮采集。

### P7：Lead Research Agent 规划

目标：增强复杂需求下的研究计划质量。

开发内容：

- 新增 `LeadResearchPlanner`。
- 根据 `AnalysisRequirement`、用户资料和 `ReviewDecision` 生成 `ResearchCollectionPlan`。
- 增加规则兜底，避免 LLM 规划失败影响主流程。
- 记录 Lead Agent 的规划理由。
- 当前第一版采用确定性 planner，不直接控制并发、重试、超时和预算；这些调度细节仍由采集服务配置和确定性代码负责。

验收标准：

- 对复杂行业和多竞品任务，系统能生成更合理的采集维度。
- Lead Agent 输出可被前端展示。
- LLM 规划异常时可以退回规则规划。
- 第一版确定性 planner 不依赖 LLM，因此天然满足兜底要求；后续接入 LLM 时需保留该规则规划作为 fallback。

## 11. 最小可行版本

如果只做第一版，建议实现：

- `ResearchCollectionPlan`。
- `ResearchSubtask`。
- 当前 batch 到 subtask 的映射。
- subtask 状态更新。
- 采集耗时、候选数、抓取数、采纳证据数记录。
- API 查询 subtask。
- 后端测试覆盖，不改变现有采集结果。

这个版本不追求一步到位提速，但会先建立可观察基础。随后 P2 的搜索/抓取分层并发才能有明确指标证明是否真的变快。

## 12. 风险和注意事项

- 避免过早新增大量独立表。第一阶段优先挂在 `AnalysisRun.run_payload`，等查询和分页需求明确后再做投影表。
- 避免把竞品 worker 做成完全独立全流程 Agent，否则会重复搜索、重复抓取、重复消耗预算。
- 避免让 Lead Agent 控制工程调度细节，并发数、超时、重试和证据预算必须由确定性代码控制。
- 保持 `EvidenceSource`、`EvidenceChunk`、citationKey 和 Reviewer citation coverage 链路兼容。
- 并发默认值必须保守，避免 Tavily 限流、Playwright 过载或单域名请求过密。
- 前端默认展示摘要，细节按需展开，避免采集过程信息把用户淹没。
