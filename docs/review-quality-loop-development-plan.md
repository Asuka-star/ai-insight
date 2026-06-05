# 质检闭环驱动的主流程质量优化开发文档

## 1. 背景

当前主流程已经形成稳定的 Agent 链路：

```text
Researcher -> Extractor -> Analyst -> Writer -> Reviewer
```

近期多次重跑同一分析任务后，可以观察到一个明确现象：Reviewer 的 finding 数量和类别不只是质检展示问题，而是在反向暴露主流程生成质量的薄弱环节。

最新一次重跑的关键结果：

- 从 `Researcher` 开始补采后，证据数从 `62` 增加到 `73`。
- 下游自动级联执行 `Extractor -> Analyst -> Writer -> Reviewer`。
- 高风险问题从上一轮 `2 HIGH` 降为 `0 HIGH`。
- 最终保留 `17 MEDIUM`，状态为 `SUCCEEDED`，ReviewDecision 为 `PASS`。
- finding 已带上 `targetAgent`，可以定位到 `RESEARCHER / EXTRACTOR / ANALYST / WRITER`。

这说明补采和级联重跑是有效的，但还没有形成稳定的“越修越干净”质量闭环。

## 2. 当前问题归因

### 2.1 Researcher

Researcher 能通过补采解决一部分阻断问题，但补采还不够精准。

当前暴露的问题：

- repair 补采仍可能抓到泛 solution 页、个人博客、403 页面或不够贴合 finding 的来源。
- 对安全、权限、定价、企业部署等高风险维度，没有足够强制地优先找一手来源。
- 部分官方页面的 `sourceType/sourceQuality` 识别偏保守，例如企业产品页被识别成普通 article。
- 当公开资料不足时，没有明确把“无法补足”反馈给 Analyst 进行降级。

### 2.2 Extractor

Extractor 剩余问题主要集中在 `fact_unsupported_by_evidence`。

当前暴露的问题：

- 会把证据中“隐约相关”的内容抽成确定事实。
- 事实只绑定到 source 级别的 `evidenceIds`，缺少更短、更可验证的支持片段。
- 对价格、安全、部署、权限等高风险事实没有更高抽取门槛。
- JSON repair 当前主要修格式，但需要确保不会在修复过程中改变事实语义。

### 2.3 Analyst

Analyst 是当前最关键的优化点。

当前暴露的问题：

- 重跑后可能清掉 HIGH，但让 `claimCoverage` 下降。
- claim、matrix、SWOT、dimension coverage 之间仍可能漂移。
- `UNVERIFIED / VALIDATION_BACKLOG / LOW / 无 evidenceIds` 的 claim 仍可能被放进主矩阵或报告主判断。
- repair 模式下可能重新生成更强、更具体的 claim，但证据没有同步变强。
- 部分用户指定维度没有稳定的“已支撑 / 部分支撑 / 待补证”状态。

### 2.4 Writer

Writer 目前主要问题是表达层会把弱支撑内容写成确定结论。

当前暴露的问题：

- 会把待验证 claim 改写成自然、顺滑但偏确定的报告句子。
- 可能遗漏用户指定维度，例如“上下文管理”。
- 可能写出“核心能力存在重叠”“符合企业需求”等超过证据边界的判断。
- repair 模式下有时会整体重写报告，而不是只修 Reviewer 定位的段落。

### 2.5 Reviewer / Workflow

Reviewer 已经能发现问题并路由到目标 Agent，但 Workflow 的闭环策略还偏粗。

当前暴露的问题：

- 默认只有 HIGH 阻断；MEDIUM 会保留为人工复核提醒。
- 严格质量场景下，用户希望继续重跑直至尽量清空问题，但系统没有区分普通模式和严格模式。
- 同一个 finding 连续多轮无法解决时，缺少“转人工补资料 / 强制降级 / 公开资料不足”的终止策略。

## 3. 优化目标

### 3.1 产品目标

让用户能理解：

- 当前报告是否可用。
- 哪些问题可以自动重跑修复。
- 哪些问题必须补资料或接受“待验证”。
- 每次重跑是否真的让质量变好。

### 3.2 工程目标

让主流程形成更稳定的质量契约：

```text
EvidenceSource / EvidenceChunk
-> ExtractedFact
-> AnalysisClaim
-> Matrix / SWOT
-> Report
-> ReviewFinding / ReviewDecision
```

每一层只能消费上一层明确支持的内容，不能越级补事实、补结论或补证据。

### 3.3 质量目标

短期目标：

- HIGH finding 能通过正确上游重跑稳定减少。
- MEDIUM finding 能按 targetAgent 分类，并进入可解释的修复路径。
- 主报告不再把弱支撑内容写成确定结论。

中期目标：

- 严格模式下，非 LOW finding 也可以进入有限轮次的自动闭环。
- 无法自动解决的问题能明确标记为“需要人工补资料”或“公开资料不足”。
- 重跑后 finding 数量、HIGH 数量、claim 覆盖率、证据数和报告指纹变化可量化展示。

## 4. 关键设计原则

### 4.1 Claim 是分析层唯一事实源

Matrix、SWOT、Report 不应该独立发明结论。它们应该从 `AnalysisClaim` 渲染或改写而来。

约束：

- `SUPPORTED + MATRIX/SWOT + MEDIUM/HIGH` 的 claim 才能进入主矩阵、SWOT 和报告主判断。
- `UNVERIFIED / VALIDATION_BACKLOG / LOW / 无 evidenceIds` 的 claim 只能进入“证据缺口”“待验证结论”“下一步补证清单”。
- Writer 不允许把待验证 claim 改写为确定判断。

### 4.2 Extractor 只抽显性事实

Extractor 不做策略判断，不写建议，不补分析。

约束：

- 每个 fact 必须能被 evidence chunk 直接支持。
- 不确定字段进入 unknowns 或 validation backlog。
- 高风险事实需要更高来源门槛。

### 4.3 Researcher 对高风险 claim 优先找一手来源

对于价格、安全、权限、部署、企业能力等问题，Researcher 应优先补：

- 官方产品页。
- 官方文档。
- 官方定价页。
- 官方安全/权限/企业部署文档。
- 官方 release note 或技术博客。

如果找不到，应明确输出“公开资料不足”，让 Analyst 降级 claim。

### 4.4 Reviewer 是质量裁判，不是内容生产者

Reviewer 负责：

- 判断问题是否影响可靠性。
- 给出目标 Agent。
- 生成结构化 repair task。
- 记录是否阻断。

Reviewer 不负责直接修改 claim、fact 或 report。

## 5. 数据契约优化

### 5.1 ReviewFinding

已新增：

```java
private AgentName targetAgent;
```

后续建议补充：

```java
private String unresolvedReason;
private Integer repeatedCount;
private Boolean requiresHumanInput;
private Boolean autoRepairable;
```

用途：

- `targetAgent`：前端展示建议处理 Agent，Workflow 路由时优先使用。
- `unresolvedReason`：解释为什么多轮重跑仍未解决。
- `repeatedCount`：识别同一类问题是否反复出现。
- `requiresHumanInput`：标记必须人工补资料的问题。
- `autoRepairable`：区分可自动修复和不可自动修复的问题。

### 5.2 ReviewRepairTask

现有字段已经较完整。建议强化以下约束：

- `targetAgent` 必须来自 finding，不再由 decision 全局目标覆盖。
- `action` 必须表达修复方式，例如 `RECOLLECT_EVIDENCE`、`REPAIR_FACT_BINDING`、`REPAIR_CLAIM_EVIDENCE`、`REVISE_REPORT_TEXT`。
- `acceptanceCriteria` 必须能被后续 Reviewer 验证。
- `requiredEvidenceTypes` 对 Researcher 必须明确。

建议新增：

```java
private Boolean allowDowngrade;
private Boolean allowDelete;
private String failurePolicy;
```

用途：

- `allowDowngrade`：证据不足时允许把 claim/fact/report 表述降级。
- `allowDelete`：证据不足且影响质量时允许删除对应结论。
- `failurePolicy`：例如 `ASK_USER`、`MARK_UNVERIFIED`、`KEEP_WITH_WARNING`。

### 5.3 ExtractedFact

建议强化 fact 支撑字段：

```java
private List<String> evidenceIds;
private List<String> chunkKeys;
private String supportQuote;
private String supportStrength;
private String riskLevel;
```

规则：

- `supportQuote` 必须是短片段，不应超过 120 字。
- `supportStrength` 可取 `DIRECT / PARTIAL / WEAK / UNSUPPORTED`。
- `riskLevel` 可取 `NORMAL / PRICING / SECURITY / PERMISSION / DEPLOYMENT / CUSTOMER_SIGNAL`。

### 5.4 AnalysisClaim

建议强化 claim 的可渲染边界：

```java
private String supportLevel;
private String placementReason;
private Boolean eligibleForMainReport;
private Boolean eligibleForMatrix;
private Boolean eligibleForSwot;
```

规则：

- `eligibleForMainReport` 只在 `supportStatus=SUPPORTED/PARTIAL` 且有有效 evidence 时为 true。
- `eligibleForMatrix` 只在 `recommendedPlacement=MATRIX` 且 `confidence != LOW` 时为 true。
- `eligibleForSwot` 同理。
- 如果 `supportStatus=UNVERIFIED`，上述字段必须为 false。

## 6. 分阶段开发计划

## P0：稳定 Analyst 质量边界

### 6.1 目标

解决 claim、矩阵、SWOT、报告之间的漂移问题，让 Analyst 成为主流程质量的稳定中枢。

### 6.2 任务

1. 增加 claim eligibility 计算。

   在 `AnalystNode` 生成 claim 后，统一计算：

   - 是否可进入主矩阵。
   - 是否可进入 SWOT。
   - 是否可进入主报告。
   - 如果不可进入，原因是什么。

2. 强化 `normalizeRecommendedPlacement`。

   规则：

   - 无证据 claim 强制 `VALIDATION_BACKLOG` 或 `NONE`。
   - `UNVERIFIED` 强制 `VALIDATION_BACKLOG`。
   - `LOW` 强制 `VALIDATION_BACKLOG` 或 `NONE`。
   - 高风险 claim 如果只有弱来源，不能进入 `MATRIX/SWOT`。

3. Matrix/SWOT 只从 eligible claims 渲染。

   不能让 matrix 或 SWOT 独立引入新判断。

4. repair 模式下优先修原 claim。

   处理顺序：

   - 根据 `claimId` 找到原 claim。
   - 尝试更换 evidenceIds。
   - 尝试降低 confidence/supportStatus。
   - 尝试移动 placement。
   - 无法修复时进入 validation backlog。

5. 增加维度覆盖状态。

   对用户指定维度输出：

   - `SUPPORTED`：有主结论。
   - `PARTIAL`：有弱支撑或局部支撑。
   - `MISSING`：无可用结论。
   - `BACKLOG`：只能待验证。

### 6.3 验收标准

- `schema_consistency` 和 `matrix_claim_conflict` 明显减少。
- `UNVERIFIED` claim 不再进入主矩阵。
- 重跑 Analyst 后 `claimCoverage` 不应无解释下降。
- 如果 claimCoverage 下降，ReviewRepairDelta 或 artifact 中能解释原因。

### 6.4 建议测试

- `AnalystNodeTest` 增加无证据 claim 强制 backlog 测试。
- `AnalystNodeTest` 增加 repair task 指向 claim 后降级测试。
- `AnalysisWorkflowServiceTest` 增加矩阵不渲染 unverified claim 测试。
- `ReviewerNodeTest` 增加 matrix conflict 修复回归测试。

## P1：强化 Extractor 事实支撑

### 7.1 目标

减少 `fact_unsupported_by_evidence`，避免 Extractor 把推断写成事实。

### 7.2 任务

1. 给 ExtractedFact 增加短引用支撑。

   每个事实至少绑定：

   - `evidenceIds`
   - `chunkKeys`
   - `supportQuote`

2. 增加 fact 支撑强度判断。

   支撑强度：

   - `DIRECT`：证据直接陈述。
   - `PARTIAL`：证据部分支撑。
   - `WEAK`：只间接相关。
   - `UNSUPPORTED`：不应进入明确事实。

3. 高风险字段强制直接证据。

   适用字段：

   - pricing
   - security
   - permission
   - deployment
   - enterprise
   - customer count
   - market share

4. JSON repair 只修结构，不补语义。

   如果原始输出 JSON 不合法，repair prompt 必须要求：

   - 不新增事实。
   - 不新增 evidenceIds。
   - 不改变字段含义。
   - 无法确认时写 `待验证`。

### 7.3 验收标准

- `fact_unsupported_by_evidence` 数量下降。
- Extractor 输出里的高风险事实都能定位到具体 chunk。
- JSON parse failed 后，不产生新增强事实。

## P1：强化 Writer 报告边界

### 8.1 目标

让报告只表达已被结构化 claim 支撑的内容，避免过度推断和维度遗漏。

### 8.2 任务

1. Writer 输入中区分 claim 区域。

   建议拆成：

   - 主报告可用 claims。
   - 待验证 claims。
   - 不可写入主结论 claims。

2. 报告生成后增加轻量自检。

   自检项：

   - 用户指定维度是否覆盖。
   - 主结论是否引用了待验证 claim。
   - 是否有无 citation 的关键事实。
   - 是否出现超过证据边界的强判断。

3. repair 模式下限制修改范围。

   如果 task 有 `paragraphIndex/excerpt/currentText`，Writer 优先定点修，不整体重写。

4. 建议优先级表绑定 claim。

   每条建议应来自可用 claim，并显示：

   - 建议。
   - 理由。
   - 证据。
   - 置信度。
   - 下一步。

### 8.3 验收标准

- `report_overclaim` 数量下降。
- `report_dimension_coverage_gap` 数量下降。
- 报告主结论不再包含 `UNVERIFIED` claim 的确定性表达。
- Writer repair 后被点名 excerpt 不应原样保留。

## P1：强化 Researcher 目标补采

### 9.1 目标

让 Researcher 的 repair 补采更贴合 Reviewer finding，减少泛搜和弱来源。

### 9.2 任务

1. Query Planner 使用结构化 repair task。

   query 生成必须使用：

   - competitorName
   - dimension
   - category
   - citationKey
   - currentText
   - requiredEvidenceTypes

2. 高风险类别默认来源偏好。

   映射建议：

   ```text
   pricing -> pricing_page, official_site
   security -> security_docs, product_docs, official_site
   permission -> security_docs, product_docs
   deployment -> product_docs, technical_blog, official_site
   agent_workflow -> product_docs, release_notes, technical_blog
   ```

3. 改进 source type 识别。

   例如：

   - `claude.com/product/.../enterprise` 应识别为 `official_site` 或 `product_docs`。
   - `cursor.com/blog/...` 可根据内容识别为 `official_blog` 或 `technical_blog`。
   - 403 页面不应进入可用证据。

4. 补采失败时产出明确状态。

   如果找不到一手来源，应记录：

   - 缺什么。
   - 搜了什么。
   - 为什么不可用。
   - 建议 Analyst 如何降级。

### 9.3 验收标准

- 补采后证据数增加时，HIGH finding 应下降或原因可解释。
- `low_quality_source` 不应因为补采而明显增加。
- 对价格/安全 finding，优先新增官方或高质量来源。

## P2：Workflow 严格质量闭环

### 10.1 目标

支持“普通模式”和“严格模式”两种质量策略。

### 10.2 普通模式

当前策略基本保留：

- HIGH 阻断。
- MEDIUM/LOW 作为质量提醒。
- 无 HIGH 时 PASS。

### 10.3 严格模式

新增策略：

- HIGH 和 MEDIUM 都可进入 repairTasks。
- 最多自动重跑 N 轮。
- 同一 finding 连续出现超过阈值后停止自动重跑。
- 停止原因必须明确展示。

### 10.4 finding 终止策略

建议状态：

```text
AUTO_REPAIRABLE
REPAIRED
REPEATED_UNRESOLVED
NEEDS_HUMAN_EVIDENCE
MARKED_UNVERIFIED
ACCEPTED_RISK
```

规则：

- Researcher 补采后仍无一手来源：`NEEDS_HUMAN_EVIDENCE` 或 `MARKED_UNVERIFIED`。
- Analyst 连续两轮无法支撑 claim：强制降级。
- Writer 连续两轮保留同一 overclaim：提高为阻断或改为定点删除。
- Extractor 连续两轮生成 unsupported fact：删除或移入 unknowns。

### 10.5 验收标准

- 严格模式下，用户可以看到自动闭环轮次。
- 每轮能看到 finding 数、HIGH 数、MEDIUM 数和目标 Agent 的变化。
- 无法清掉的问题不再无限重跑，而是转成可解释状态。

## P2：前端质量解释

### 11.1 目标

让用户看到“不只是有问题”，而是知道问题应该怎么解决。

### 11.2 任务

1. 质检卡片展示：

   - 严重程度。
   - 中文标题。
   - 建议处理 Agent。
   - 是否可自动修复。
   - 是否需要人工补资料。

2. 质量概览增加分组：

   - 可通过重跑解决。
   - 需要补采来源。
   - 需要人工资料。
   - 已降级待验证。

3. 重跑前后对比：

   - evidence 数变化。
   - finding 数变化。
   - HIGH/MEDIUM/LOW 变化。
   - claimCoverage 变化。
   - 当前是否有阻断问题。

4. 定位体验：

   已完成基础定位闪烁后，继续增强：

   - claim 定位到结构化信息。
   - paragraph 定位到报告段落。
   - citation 定位到来源卡片。
   - matrix conflict 定位到矩阵行。

### 11.3 验收标准

- 用户不需要看日志，也能理解为什么本轮 PASS 但仍有 MEDIUM。
- 用户能知道下一次应该从哪个 Agent 重跑。
- 用户能区分“自动可修”和“需要人工补资料”。

## 12. 推荐实施顺序

### 阶段一：主质量边界

优先实现：

1. Analyst claim eligibility。
2. Matrix/SWOT 只从 eligible claims 渲染。
3. Writer 只消费主报告可用 claims。
4. Extractor 高风险 fact 强支撑约束。

预期收益：

- 报告确定性结论更稳。
- matrix conflict 和 report overclaim 下降。
- 重跑不会轻易把问题从 HIGH 改成一批新的 MEDIUM。

### 阶段二：repair 精准闭环

优先实现：

1. ReviewRepairTask 增加 downgrade/delete/failure policy。
2. Researcher repair query 精准化。
3. Analyst repair 优先修改原 claim。
4. Workflow 记录 repeated finding。

预期收益：

- 用户反复重跑时，系统能越来越明确：修好了、降级了、还是需要人工补资料。

### 阶段三：严格模式和前端解释

优先实现：

1. 质量闭环严格模式。
2. finding 自动修复状态。
3. 前端质量分组。
4. 重跑前后质量趋势。

预期收益：

- 系统从“能质检”升级为“能解释质量并推动修复”。

## 13. 回归测试矩阵

### 13.1 Researcher

- repair task 指定 pricing 时，query 优先包含 pricing page。
- repair task 指定 security 时，query 优先包含 security/docs。
- 403 或 unusable 页面不进入有效证据。
- 补采失败时能返回可解释原因。

### 13.2 Extractor

- 高风险字段无直接证据时进入 unknowns。
- fact 绑定未知 citation 时被过滤。
- fact 无 chunk/supportQuote 时不能作为强事实。
- JSON repair 不新增事实。

### 13.3 Analyst

- 无证据 claim 强制 validation backlog。
- unverified claim 不进入 matrix/SWOT。
- repair task 指向 claim 时，优先修改原 claim。
- claimCoverage 下降时保留解释。

### 13.4 Writer

- 主报告不消费 validation backlog。
- 用户指定维度缺失时自检发现。
- 被 Reviewer 点名 excerpt 不原样保留。
- 内部 Agent 名称和 Claim ID 不进入报告正文。

### 13.5 Reviewer / Workflow

- finding 带 targetAgent。
- HIGH 按最上游 targetAgent 打回。
- 严格模式下 MEDIUM 进入有限轮 repair。
- 连续 unresolved finding 转人工或降级状态。

## 14. 风险与取舍

### 14.1 不应过度规则化

本优化不是要把所有质量判断写成硬规则。硬规则只负责：

- 数据边界。
- eligibility。
- 不变量。
- 可追踪性。

语义判断仍然交给 LLM Reviewer 和各 Agent prompt，但 LLM 输出必须被结构化契约约束。

### 14.2 不应追求所有 finding 清零

公开资料分析场景下，有些问题天然不能自动清零：

- 缺少一手来源。
- 竞品未公开细节。
- 第三方评测不足。
- 用户需要内部实测或访谈。

系统目标不是伪造“无问题”，而是清楚地区分：

- 已修复。
- 已降级。
- 已接受风险。
- 需要人工补资料。

### 14.3 防止越修越差

需要特别关注：

- Researcher 补采增加低质量来源。
- Extractor 重新抽取引入新 unsupported fact。
- Analyst 重跑降低 claimCoverage。
- Writer 重写报告引入新 overclaim。

因此每次 repair 都应保留 `ReviewRepairDelta`，并在质量趋势中展示。

## 15. 建议下一步

建议下一步先做 P0：

1. 在 `AnalystNode` 增加 claim eligibility 计算。
2. 确保 matrix/SWOT 只渲染 eligible claims。
3. 让 Writer 输入区分主报告可用 claims 和 validation backlog claims。
4. 为这些约束补测试。

这一步的收益最大，因为它直接决定“主流程生成的报告是否会把弱支撑内容写成强结论”。
