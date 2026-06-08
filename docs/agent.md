# AI Insight Agent 角色与协议文档

本文档定义系统中每个 Agent 的角色职责、输入输出、Schema 约束、LLM 配置和通信协议。

---

## 统一接口

所有 Agent 实现 `AgentNode` 接口：

```java
public interface AgentNode {
    AgentName name();
    String title();
    AnalysisRun execute(AnalysisRun run);
}
```

Agent 通过读写共享的 `AnalysisRun` 聚合对象传递状态，而非自由文本。关键中间结果写入结构化字段（`EvidenceSource`、`CompetitorProfile`、`AnalysisClaim`、`ReviewDecision` 等），确保下游 Agent 可以可靠消费。

### 通信协议

Agent 之间不直接调用方法，而是通过 `AnalysisRun` 的结构化状态实现隐式通信：

| 传递方向 | 结构化载体 | 说明 |
|---------|-----------|------|
| Clarifier → 全局 | `ClarificationDraft` | 确认后的行业、竞品、维度、来源偏好 |
| Researcher → Extractor | `EvidenceSource` + `EvidenceChunk` | 采集到的证据来源和检索切片 |
| Researcher → Analyst/Writer | `ResearchPackage` | 调研计划、缺口类型、访谈/问卷洞察 |
| Extractor → Analyst | `CompetitorProfile` + `CompetitorFactSet` | 竞品结构化画像和事实集 |
| Analyst → Writer | `AnalysisClaim` + Artifact（矩阵/SWOT） | 结构化结论和可视化产物 |
| Writer → Reviewer | `REPORT_DRAFT` Artifact | 带 citation 的报告草稿 |
| Reviewer → 任意 Agent | `ReviewDecision` + `ReviewFinding` | 质检结论和打回路由 |

每个 Agent 执行时由 `WorkflowNodeExecutor` 统一记录 `AgentStep`（时间线）和 `AgentTrace`（Prompt、输入输出快照、Token 消耗、模型信息、降级标记），并通过 SSE 实时推送前端。

---

## DAG 编排

主分析流程使用 LangGraph4j 构建有向无环图（DAG），结构如下：

```
START
  → RESEARCHER
  → EXTRACTOR
  → ANALYST
  → WRITER
  → REVIEWER
  → REVIEW_GATE
      → RESEARCHER   (ReviewAction = RECOLLECT_EVIDENCE)
      → EXTRACTOR    (ReviewAction = RECOLLECT_EVIDENCE, escalation)
      → ANALYST      (ReviewAction = REWORK_ANALYSIS)
      → WRITER       (ReviewAction = REVISE_REPORT)
      → END          (ReviewAction = PASS / 重做上限 / 重复阻塞检测)
```

CLARIFIER 不在 DAG 内，在主流程启动前独立运行。

自动打回上限为 2 次（`maxReviewReworkAttempts`，可在 `[0, 2]` 内配置）。系统通过 finding 签名（category + claim + fact + chunk + citation + paragraph + excerpt hash）检测重复阻塞，避免无限循环。

---

## Agent 详细规格

### 1. CLARIFIER — 澄清任务范围

| 属性 | 值 |
|------|-----|
| 类名 | `ClarifierNode` |
| AgentName | `CLARIFIER` |
| 标题 | 澄清任务范围 |
| LLM 模型 | Doubao `Seed-2.0-lite`（轻量模型，通过 `AgentRoutingLlmClient` 路由） |
| ChatOptions | temperature=0.2, maxTokens=700 |
| 降级工厂 | `FallbackClarificationDraftFactory` |

**角色描述**

Clarifier 是主流程的前置环节，负责将用户的原始需求解析为结构化的分析范围。它提取行业、竞品列表、分析维度、来源偏好和目标输出等字段，生成 `ClarificationDraft` 供用户在前端确认或修改。Clarifier 遵循"用户填写优先"原则：LLM 只填充用户未提供的字段，不会覆盖已有值；LLM 不能凭空生成 URL，只保留用户提供的来源。

**输入**

- `AnalysisRequirement`：`originalPrompt`, `industry`, `competitors`, `dimensions`, `sourceUrls`, `outputGoal`, `sourcePreferences`
- `ClarificationDraft`（前次草稿，用于保持确认状态）

**输出**

- `ClarificationDraft`：`industry`, `competitors`, `dimensions`, `sourceUrls`, `outputGoal`, `clarificationQuestions`, `clarificationItems`, `sourcePreferences`, `confirmed`, `confirmedAt`
- `AnalysisRequirement`：从 draft 回写结构化字段
- `CLARIFICATION_BRIEF` Artifact：Markdown 范围摘要

---

### 2. RESEARCHER — 采集资料与证据

| 属性 | 值 |
|------|-----|
| 类名 | `ResearcherNode` |
| AgentName | `RESEARCHER` |
| 标题 | 采集资料与证据 |
| LLM 模型 | Xiaomi `mimo-v2.5-pro` |
| ChatOptions | temperature=0.2, maxTokens=1600 |
| 降级工厂 | `FallbackResearchPlanFactory` |

**角色描述**

Researcher 负责从多个渠道采集竞品分析的公开证据。它首先观察用户提供的 URL 和文档，然后通过 LLM 规划搜索策略，调用搜索 API 发现新的公开网页，抓取并切片。同时生成调研计划、问卷草案和访谈提纲，支持一手资料采集。在 Reviewer 打回（`RECOLLECT_EVIDENCE`）时进入补采模式，基于当前证据缺口刷新计划而非从零生成。

**输入**

- `AnalysisRequirement`：`originalPrompt`, `industry`, `competitors`, `dimensions`, `sourcePreferences`, `sourceUrls`
- `ResearchPackage`：`missingEvidenceTypes`, `actualSearchQueries`（补采模式）
- `EvidenceSource`：已有来源列表（补采模式）
- `UserProvidedEvidence`：用户补充的资料和文档

**输出**

- `EvidenceSource`：可引用的证据来源（citationKey, title, url, sourceType, collectionStatus, complianceNote 等）
- `EvidenceChunk`：按段落切分的检索切片
- `ResearchPackage`：`researchPlan`, `missingEvidenceTypes`, `interviewInsights`, `surveyInsights`, `collectedAt`
- `SOURCE_LIST` Artifact：资料采集清单
- `RESEARCH_PLAN` Artifact：调研计划与一手资料设计

**内部组件**

- `ResearchAgent`：核心采集循环（plan → search → select → collect → reconcile）
- `InterviewInsightExtractor`：从访谈记录提取结构化洞察
- `SurveyInsightExtractor`：从问卷结果提取结构化洞察
- `ResearchCoverageService`：计算竞品-维度覆盖缺口

**特殊行为**

- 3 个 LLM 子任务并行执行：搜索策略、问卷设计、访谈提纲
- 未配置搜索 API 时不生成伪造网页证据，只记录缺口和建议
- 遵循 robots.txt 策略（`RobotsPolicyService`），被阻止的页面标记 `ROBOTS_BLOCKED`

---

### 3. EXTRACTOR — 抽取竞品结构化信息

| 属性 | 值 |
|------|-----|
| 类名 | `ExtractorNode` |
| AgentName | `EXTRACTOR` |
| 标题 | 抽取竞品结构化信息 |
| LLM 模型 | Xiaomi `mimo-v2.5-pro` |
| ChatOptions | temperature=0.2, maxTokens=2500（抽取）; temperature=0.0, maxTokens=3200（JSON 修复） |
| 降级工厂 | `FallbackExtractionFactory` |

**角色描述**

Extractor 从证据中抽取每个竞品的结构化画像，严格遵循预定义的竞品知识 Schema。它为每个竞品独立调用 LLM，生成功能树、定价模型、用户画像、优劣势等结构化数据，并通过 RAG 检索为每个竞品-维度对提供相关证据上下文。抽取结果经过证据绑定验证，确保每个事实都可追溯到具体证据。

**输入**

- `AnalysisRequirement`：`competitors`, `dimensions`
- `EvidenceSource` + `EvidenceChunk`：用于 RAG 检索
- `ReviewDecision`（修复模式）：Reviewer 的返工指令

**输出**

- `CompetitorProfile`：`productName`, `companyName`, `positioning`, `targetUsers`, `featureTree`, `pricingModel`, `personas`, `strengths`, `weaknesses`, `evidenceIds`
- `CompetitorFactSet`：`competitorName`, `facts`（ExtractedFact 列表）, `unknowns`（UnknownFact 列表）
- `FACT_EXTRACTION` Artifact：事实抽取明细
- `COMPETITOR_PROFILE` Artifact：竞品知识 Schema 可视化

**Schema 约束**

```
CompetitorProfile
├── featureTree: FeatureTree
│   └── roots: List<FeatureNode>  (name, description, children)
├── pricingModel: PricingModel
│   ├── pricingType (FREE / FREEMIUM / TIERED / ENTERPRISE / UNKNOWN)
│   └── plans: List<PricingPlan>  (name, priceText, features, limits)
└── personas: List<UserPersona>  (name, role, jobs, goals, painPoints)
```

**内部组件**

- `ExtractorEvidenceBinder`：验证事实的证据绑定，评估风险等级和支持强度
- `FactExtractionEngine`：从画像构建 CompetitorFactSet，检测模板化定价值
- `CompetitorProfileProjector`：从事实集反向投影画像（事实验证层）
- `EvidenceRetrievalService`：RAG 风格检索，按竞品-维度对获取相关 chunk

**特殊行为**

- 按竞品逐一抽取，每次独立 LLM 调用
- RAG 证据包限额：最多 18 个竞品-维度对，每对 2 个 chunk，总字符上限 16000
- JSON 解析失败时自动修复重试（将损坏的 JSON 发给 LLM 做语法修复，最多 1 次）
- 过滤分析性判断（含"建议""应该""风险"等词的优劣势条目）

---

### 4. ANALYST — 横向对比与机会点分析

| 属性 | 值 |
|------|-----|
| 类名 | `AnalystNode` |
| AgentName | `ANALYST` |
| 标题 | 横向对比与机会点分析 |
| LLM 模型 | Xiaomi `mimo-v2.5-pro` |
| ChatOptions | temperature=0.2, maxTokens=2200 |
| 降级工厂 | `FallbackAnalysisDraftFactory` |

**角色描述**

Analyst 基于 Extractor 输出的竞品画像和事实集，生成横向对比矩阵、SWOT 分析和结构化结论（Claim）。每个 Claim 必须绑定到具体的事实和证据，并通过置信度评估、高风险检测和证据支持验证来确保结论的可信度。矩阵和 SWOT 由确定性渲染器生成，不依赖 LLM。

**输入**

- `AnalysisRequirement`：`originalPrompt`, `industry`, `competitors`, `dimensions`, `outputGoal`, `sourcePreferences`
- `CompetitorProfile` + `CompetitorFactSet`：来自 Extractor
- `EvidenceSource` + `EvidenceChunk`：证据索引
- `ResearchPackage`：`missingEvidenceTypes`, `interviewInsights`
- `AnalysisClaim`（前次 claims，用于 ID 稳定化）
- `ReviewDecision`（修复模式）

**输出**

- `AnalysisClaim`（最多 8 条）：`id`, `type`, `content`, `confidence`, `dimension`, `supportStatus`, `recommendedPlacement`, `competitorNames`, `factIds`, `evidenceIds`, `chunkKeys`, `evidenceQuotes`, `eligibleForMatrix`, `eligibleForSwot`, `eligibleForMainReport`
- `COMPETITIVE_MATRIX` Artifact：竞品横向对比矩阵
- `SWOT_ANALYSIS` Artifact：SWOT 分析

**Claim Schema 约束**

```
AnalysisClaim
├── confidence: HIGH / MEDIUM / LOW
├── supportStatus: SUPPORTED / PARTIAL / UNVERIFIED
├── recommendedPlacement: MATRIX / SWOT / MAIN_REPORT / VALIDATION_BACKLOG
├── evidenceIds: List<String>        // 绑定的 EvidenceSource citationKey
├── factIds: List<String>            // 绑定的 ExtractedFact ID
├── chunkKeys: List<String>          // 绑定的 EvidenceChunk key
└── evidenceQuotes: List<String>     // 证据原文摘录
```

**内部组件**

- `ClaimEvidenceBinder`：绑定 claim 与事实/证据，修剪无证据支持的 claim 证据
- `AnalysisProductRenderer`：确定性渲染矩阵和 SWOT Markdown
- `TermExtractor`：基于分词的 claim 去重和内容重叠计算
- `AnalysisClaimRules`：静态规则——placement 决策、supportStatus 判定、不确定性标记检测

**特殊行为**

- Claim ID 稳定化：通过内容 key 或分词重叠匹配新旧 claim，保持 ID 跨重跑一致
- 修复守卫（repair guard）：修复任务仍未解决的 claim 被强制降级为 LOW 置信度
- 高风险 claim 检测：定价/安全/部署类 claim 要求更强的一手证据
- 置信度调整：基于最强证据分数，HIGH 要求 >= 3，MEDIUM 要求 >= 2

---

### 5. WRITER — 生成竞品分析报告草稿

| 属性 | 值 |
|------|-----|
| 类名 | `WriterNode` |
| AgentName | `WRITER` |
| 标题 | 生成竞品分析报告草稿 |
| LLM 模型 | Xiaomi `mimo-v2.5-pro` |
| ChatOptions | temperature=0.2, maxTokens=4500 |
| 降级工厂 | `FallbackReportDraftFactory` |

**角色描述**

Writer 基于需求、证据、Schema 和 Analyst 的分析产物生成结构化的竞品分析报告草稿。报告中的每条结论性陈述必须标注 `[S1]` 形式的引用标记，指向具体的证据来源。Writer 实现了 5 步报告净化流水线，确保输出的报告不包含内部 ID 泄漏、未知引用或无证据支撑的过度推断。

**输入**

- `AnalysisRequirement`：`originalPrompt`, `outputGoal`, `competitors`, `dimensions`
- `AnalysisClaim`：结构化结论（含 eligibility 标记）
- `CompetitorProfile`：竞品画像
- `COMPETITIVE_MATRIX` + `SWOT_ANALYSIS` Artifact：来自 Analyst 的可视化产物
- `ResearchPackage`：`missingEvidenceTypes`, `interviewInsights`
- `EvidenceSource`：证据索引
- `ReviewDecision`（修复模式）

**输出**

- `REPORT_DRAFT` Artifact：Markdown 格式的竞品分析报告草稿

**报告净化流水线（5 步）**

1. **元数据移除** — 去除报告编号、日期、Agent 名称、"报告结束"标记
2. **内部引用移除** — 去除 `[C-...]` 形式的内部 claim ID
3. **验证标记规范化** — 修正错位的"待验证:"前缀
4. **引用文本净化** — 移除未知引用 key
5. **引用纪律强制** — 逐行检查：含竞品名+判断性语言的行必须有 `[S]` 引用，缺失则加"待验证:"前缀，过度推断语言则降级

**内部组件**

- `TermExtractor`：基于分词的 line-to-claim 匹配，用于引用强制

**特殊行为**

- 引用位置规范化：将 `[S1]` 移到句末标点之前
- Markdown 表格支持：在表格单元格内追加引用
- 来源权威性追踪：USER_PROVIDED/INTERNAL_ONLY 来源必须标注，THIRD_PARTY 来源不能独立支撑优势 claim
- MAIN_REPORT vs BACKLOG 分离：UNVERIFIED/LOW/VALIDATION_BACKLOG 的 claim 只进入风险/缺口章节

---

### 6. REVIEWER — 复核事实一致性与引用覆盖

| 属性 | 值 |
|------|-----|
| 类名 | `ReviewerNode` |
| AgentName | `REVIEWER` |
| 标题 | 复核事实一致性与引用覆盖 |
| LLM 模型 | Xiaomi `mimo-v2.5-pro` |
| ChatOptions | temperature=0.2, maxTokens=1800（每个子任务） |
| 降级工厂 | `FallbackReviewReportFactory` |

**角色描述**

Reviewer 是系统的质量关卡，对 Writer 生成的报告草稿进行多维度质检。它采用两阶段审查策略：首先用确定性规则检查引用覆盖和结构完整性，然后用 LLM 并行执行 5 个语义审查子任务。审查结果生成结构化的 `ReviewFinding`（每个带严重级别、类别、定位和修复建议）和 `ReviewDecision`（决定打回到哪个 Agent 或通过）。

**输入**

- `REPORT_DRAFT` Artifact：报告草稿
- `AnalysisClaim`：所有结构化结论
- `CompetitorProfile` + `CompetitorFactSet`：竞品画像和事实集
- `EvidenceSource` + `EvidenceChunk`：证据来源和切片
- `ResearchPackage`：`missingEvidenceTypes`
- `ReviewDecision`（前次决策，修复验证模式）
- `ReviewFinding`（前次 findings，用于去重和阻塞检测）

**输出**

- `ReviewFinding`：质检问题列表（severity, category, message, recommendation, claimId, factId, chunkKey, citationKey, paragraphIndex, excerpt, targetAgent, locationType）
- `ReviewDecision`：`action` (PASS/REVISE_REPORT/REWORK_ANALYSIS/RECOLLECT_EVIDENCE), `targetAgent`, `reason`, `affectedClaimIds`, `blockingFindingIds`, `repairTasks`（最多 12 条）, `decidedAt`
- `REVIEW_FINDINGS` Artifact：复核结果 Markdown 报告

**两阶段审查**

| 阶段 | 方式 | 检查内容 |
|------|------|---------|
| 确定性阶段 | `CitationCoverageEvaluator` | 未知引用、未知证据 ID、未知事实、弱支持、缺失引用、内部证据冒充公开证据等 |
| LLM 语义阶段 | 5 个并行子任务 | claim-evidence（证据支持度）、report-overclaim（过度推断）、schema-consistency（Schema 一致性）、source-quality（来源质量）、report-actionability（可操作性） |

**打回路由逻辑**

- 每个 finding 根据类别分配 `targetAgent`：fact_ → EXTRACTOR, missing_source → RESEARCHER, claim_ → ANALYST, report/citation → WRITER
- 只有 HIGH 严重级别且非质量提醒类的 finding 成为阻塞项
- 选择最上游的责任 Agent 作为打回目标
- 升级策略：如果 Extractor 修复未改善且证据未变，升级到 Researcher；如果 Analyst 修复未改善且抽取状态未变，升级到 Extractor
- 仅手动可补充的证据类型（survey_result, interview_note 等）重定向到 Writer 降级而非 Researcher 补采

**内部组件**

- `CitationCoverageEvaluator`：确定性结构和引用完整性检查（42 KB，覆盖 20+ 规则）
- `ResearchCoverageService`：覆盖缺口数据充实修复任务

---

## LLM 配置与降级策略

### 模型路由

| Agent | 模型 | 提供方 | 说明 |
|-------|------|-------|------|
| CLARIFIER | `Doubao-Seed-2.0-lite` | 字节豆包 | 轻量任务，成本优先 |
| RESEARCHER | `mimo-v2.5-pro` | 小米 MiMo | 复杂规划和抽取 |
| EXTRACTOR | `mimo-v2.5-pro` | 小米 MiMo | 结构化 JSON 抽取 |
| ANALYST | `mimo-v2.5-pro` | 小米 MiMo | 分析推理 |
| WRITER | `mimo-v2.5-pro` | 小米 MiMo | 长文本生成（maxTokens=4500） |
| REVIEWER | `mimo-v2.5-pro` | 小米 MiMo | 多维度并行审查 |

所有 Agent 使用 temperature=0.2 以获得近确定性输出。

### 降级机制

每个 Agent 都配备了确定性降级工厂（Fallback Factory），当 LLM 不可用或调用失败时自动回退：

| Agent | 降级工厂 | 降级策略 |
|-------|---------|---------|
| CLARIFIER | `FallbackClarificationDraftFactory` | 基于规则生成澄清问题 |
| RESEARCHER | `FallbackResearchPlanFactory` | 基于规则生成调研计划 |
| EXTRACTOR | `FallbackExtractionFactory` | 基于规则抽取事实 |
| ANALYST | `FallbackAnalysisDraftFactory` | 基于规则生成 claim 和矩阵 |
| WRITER | `FallbackReportDraftFactory` | 基于规则生成报告草稿 |
| REVIEWER | `FallbackReviewReportFactory` | 基于规则生成质检结果 |

降级使用时通过 `AgentTraceContext.recordFallback()` 记录降级原因和是否曾尝试 LLM，前端 TraceDrawer 会显示降级标记。

---

## 证据链协议

### 引用约定

- 报告正文使用 `[S1]`、`[S2]` 形式的 citation key
- `AnalysisClaim` 使用 `evidenceIds` 字段绑定证据来源
- `ExtractedFact` 使用 `evidenceIds` 和 `chunkKeys` 绑定证据切片
- 前端 citation hover 显示标题、URL 和摘要

### 证据状态约定

| collectionStatus | sourceType | 含义 |
|-----------------|------------|------|
| `FETCHED` + `LIVE_FETCHED` | article/docs/official_site | 已按 robots 策略抓取到公开页面正文 |
| `FETCHED` + `LIVE_FETCHED` | search_result_web_page | 搜索结果网页已抓取到正文 |
| `USER_PROVIDED` | user_document/user_note | 用户补充资料 |
| `USER_PROVIDED` + `INTERNAL_ONLY` | user_* + sensitive=true | 内部敏感资料，仅内部使用 |

### 合规约定

- 抓取前检查 robots.txt（`RobotsPolicyService`），被阻止的页面返回 `ROBOTS_BLOCKED`
- User-Agent 标识为 `AI-Insight-ResearchBot/0.1`
- `EvidenceSource.complianceNote` 记录每次抓取的合规决策
- 未配置搜索 API 时不生成伪造网页证据

---

## 可观测性

每个 Agent 执行时由 `WorkflowNodeExecutor` 统一记录：

| Trace 字段 | 说明 |
|-----------|------|
| `prompt` | 发送给 LLM 的完整 prompt |
| `inputSnapshot` | Agent 输入状态快照 |
| `outputSnapshot` | Agent 输出状态快照 |
| `processSnapshot` | 中间处理过程快照 |
| `rawModelOutput` | LLM 原始返回文本 |
| `decisionSummary` | 决策摘要 |
| `modelName` | 使用的模型名称 |
| `fallbackUsed` | 是否使用了降级工厂 |
| `fallbackReason` | 降级原因 |
| `promptTokens` | prompt token 数（实际值 + 估算值） |
| `completionTokens` | 生成 token 数 |
| `totalTokens` | 总 token 数 |
| `latencyMs` | 执行耗时（毫秒） |
| `errorMessage` | 异常信息 |
| `status` | COMPLETED / FAILED / CANCELLED |

前端 `TraceDrawer` 组件可展开查看每个 Agent 的完整 Trace 信息。
