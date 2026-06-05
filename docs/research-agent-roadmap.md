# 信息采集 Agent 后续优化记录

## 1. 文档目的

本文记录当前信息采集 Agent 与课题要求之间的差距，以及后续可迭代的优化方向。

当前系统已经能完成公开 URL 抓取、Tavily 搜索、采集子任务规划、竞品/维度级覆盖缺口计算、问卷/访谈设计、用户资料录入、问卷结果导入、问卷洞察、访谈洞察抽取和补采/重跑前后量化对比。但从课题中“数字调研小组”的目标看，信息采集 Agent 还应进一步增强访谈管理、来源质量评分和演示稳定性。

## 2. 当前已具备能力

### 2.1 公开信息采集

- 支持用户在任务中提供公开 URL。
- 支持配置 `TAVILY_API_KEY` 后调用 Tavily 搜索。
- 搜索结果会继续尝试抓取网页正文。
- 抓取成功时保存 `search_result_web_page`。
- 系统搜索结果抓取失败时直接跳过；只有成功抓取到可用正文的页面才保存为搜索证据，用户主动提供的 URL 抓取失败时才记录失败状态。
- 未配置搜索时不会生成伪造证据，只记录需要补充来源。

### 2.2 信息溯源

- 每个来源生成 `citationKey`，例如 `S1`。
- `EvidenceSource` 保存标题、URL、snippet、rawText、采集状态、freshness、complianceNote。
- 后续 Extractor、Analyst、Writer 会沿用 citation，支撑报告溯源。

### 2.3 问卷设计

- `ResearcherNode` 现在优先通过 LLM 生成 `ResearchPlan`。
- LLM 输出失败、LLM 不可用或 JSON 解析失败时，使用 `FallbackResearchPlanFactory` 生成问卷草案和访谈提纲。
- 问卷内容会基于行业、竞品、分析维度生成，不再只固定在 AI 文档协作场景。

### 2.4 用户访谈资料

- 用户可以补充 `interview` 类型资料。
- 访谈资料会进入证据链，sourceType 为 `user_interview`。
- `InterviewInsightExtractor` 会抽取受访者角色、场景、痛点、正负反馈、采购顾虑、竞品提及、关联维度和可引用原句。
- Extractor 会把访谈痛点和采购顾虑写入用户画像。

### 2.5 问卷结果导入

- Researcher 会生成问卷草案，前端“问卷访谈”模块支持用户编辑后保存。
- 用户可以下载 CSV 模板，自行发放问卷后导入 CSV/XLSX 结果表。
- `SurveyResultImportService` 会解析样本量、选项分布和开放反馈。
- `SurveyInsightExtractor` 会写入 `ResearchPackage.surveyInsights`，导入后先标记为待应用，用户手动重跑 Extractor 后再刷新后续 Agent 产物。
- 同一分析任务中，多次导入问卷结果时只保留最新问卷证据参与分析；访谈证据按多份累积。

### 2.6 采集计划与覆盖缺口

- `ResearchCollectionPlan` 记录采集目标、子任务、候选 URL、证据预算、覆盖缺口和补采目标。
- `ResearchCoverageGap` 已按竞品、维度和缺失来源类型表达证据缺口。
- `ResearchRepairTarget` 会把覆盖缺口或 Reviewer repair task 转成可展示的补采目标。
- 前端右侧采集面板会展示 Lead 规划、子任务、覆盖缺口和补采建议。

## 3. 主要差距

### 3.1 覆盖缺口已有结构，但演示表达还可加强

当前已经有 `ResearchCoverageGap` 和 `ResearchRepairTarget`，可以表达“哪个竞品、哪个维度、缺哪些来源类型”。后续主要不是从零新增 schema，而是增强两件事：

- 在主工作台中更显眼地展示补采前后变化，例如证据数、覆盖缺口数、Reviewer 高危问题数。
- 让 Reviewer repair task 与 coverage gap 的映射更可解释，减少“为什么要补这个来源”的黑箱感。

### 3.2 搜索 Query 仍可优化

当前搜索已经接入真实 provider，并已拆出规则/LLM query planner；但 query 仍有一定模板化倾向，部分长尾行业的维度表达还可以继续优化。

问题：

- CRM、BI、采购、审批等行业可能被固定 query 带偏。
- query 没有充分结合来源偏好，例如价格页、更新日志、公开评价、产品文档。
- query 没有明确针对每个维度生成。

后续应改成基于如下输入动态生成：

- 行业：CRM、BI、采购、审批、文档协作等。
- 竞品：Salesforce、HubSpot、Notion 等。
- 维度：价格策略、权限治理、AI 搜索、用户体验等。
- 来源偏好：官网、价格页、产品文档、更新日志、公开评价。

示例：

```text
{competitor} pricing enterprise CRM
{competitor} user reviews sales automation
{competitor} security permissions documentation
{competitor} release notes AI sales forecast
```

### 3.3 问卷调研采用“用户采集 + 结果导入”

当前系统生成问卷草案后，由用户自行发放和采集问卷，再把 CSV/XLSX 结果导入系统。

已实现：

- 下载问卷结果模板。
- 导入 CSV/XLSX 结果表。
- 解析选项分布、样本量、开放题答案。
- 把问卷结果结构化成可复用洞察。
- 同一分析任务中，问卷结果按最新导入批次覆盖；访谈资料按多份累加。

短期只保留用户手动采集与结果导入路线，避免认证、付费和回调机制影响比赛演示。

已落地结构：

- `SurveyResultImport`：记录导入批次、文件名、响应数和证据 ID。
- `SurveyInsight`：记录样本量、受访者分布、竞品提及、关联维度和 findings。
- `SurveyFinding`：记录题目、分布、结论解释和证据 ID。

### 3.4 用户访谈还不是完整访谈流程

当前系统支持访谈文本抽取，但还没有完整访谈管理能力。

尚未实现：

- 根据竞品和维度建议访谈对象画像。
- 生成访谈任务列表。
- 生成访谈记录模板。
- 多份访谈记录的聚类归纳。
- 访谈内容 PII/敏感信息脱敏。
- 使用 LLM 精抽访谈结论。

后续可把访谈拆成三层：

1. 访谈计划：谁应该被访谈、问什么、为什么问。
2. 访谈证据：用户上传访谈记录或摘要。
3. 访谈洞察：聚类后的共性痛点、差异观点、采购顾虑和原文引用。

### 3.5 来源质量评估还不够

当前来源有 URL、snippet、状态和合规说明，但还缺少来源质量评价。

建议后续补充字段：

- sourceCategory：官网、价格页、产品文档、更新日志、公开评价、访谈、问卷。
- relatedCompetitors：该来源关联哪些竞品。
- relatedDimensions：该来源支撑哪些分析维度。
- qualityScore：来源质量评分。
- qualityReason：评分原因。
- isFirstParty：是否一手来源。
- isOfficial：是否官方来源。
- isSnippetOnly：是否只来自搜索摘要。

这些字段可以帮助 Analyst 和 Reviewer 判断结论可信度。

## 4. 推荐实施优先级

### P0：补采前后改善指标（已接入）

目标：让评委和用户能直接看到“Agent 补采/重跑后是否真的变好”。

当前实现：

- `ReviewRepairDelta` 记录最近一次重跑前后的证据数、覆盖缺口、Claim 覆盖率、Reviewer 问题数和 HIGH 问题数。
- `/metrics` 返回 `latestImprovement`，前端运行指标面板展示“重跑前 -> 重跑后”和 delta。

收益：

- 直接支撑“可复核、可重跑、输出可信度提升”的评分点。
- 比单纯展示最终报告更容易讲清楚 Agent 协作价值。

### P1：搜索 Query 泛化

目标：让搜索 query 根据行业、竞品、维度、来源偏好动态生成。

建议实现：

- 继续扩展规则模板的行业词库和来源类型映射。
- LLM query planner 输出失败时保留当前规则 fallback。
- 在演示面板中突出实际执行 query 与采纳证据的关系。
- 针对 CRM、BI、审批、采购等非文档协作场景补回归样例。

收益：

- 提升真实采集命中率。
- 避免所有行业都带 `AI collaboration`。
- 搜索功能更像真正可用的采集能力。

### P1：访谈管理与聚合

目标：让访谈从“文本证据录入”升级为更完整的调研流程。

建议实现：

- 根据竞品和维度建议访谈对象画像。
- 生成访谈记录模板。
- 多份访谈记录聚合成共性痛点、分歧观点和采购顾虑。
- 增加敏感信息脱敏提示或规则处理。

收益：

- 更贴近“数字调研小组”的人群研究职责。
- 能和问卷导入能力形成一手资料闭环。

### P2：访谈洞察升级为 LLM 精抽

目标：提升访谈资料结构化质量。

建议实现：

- `InterviewInsightExtractor` 保留规则 fallback。
- LLM 可用时先用 LLM 抽取结构化 JSON。
- JSON 解析失败时回退规则抽取。
- 增加敏感信息脱敏提示。

收益：

- 访谈洞察更准确。
- 能处理更复杂的自然语言访谈记录。

### P2：来源质量评分

目标：让证据链不只是“有来源”，还可以判断“来源质量”。

建议实现：

- 新增 `EvidenceQualityEvaluator`。
- 根据来源类型、抓取状态、URL、是否官方、是否 snippet-only 给出评分。
- Reviewer 对低质量来源引用提出风险提示。

收益：

- 提升报告可信度。
- 支撑“输出可信度”评分点。

## 5. 暂不优先实现的能力

### 自动访谈对话

即 Agent 主动与用户或被访者进行多轮访谈。

原因：

- 需要独立会话管理、权限和隐私处理。
- 当前课题更需要证明“访谈资料如何进入分析链路”，不是必须做实时访谈机器人。

### 大规模爬虫采集

原因：

- 合规风险和工程成本较高。
- 当前系统应优先保持公开来源、用户授权资料和搜索摘要可解释。

## 6. 建议下一步实现顺序

1. 增加来源质量评分。
2. 将 `InterviewInsightExtractor` 升级为 LLM 优先、规则兜底。
3. 增强访谈管理和多份访谈聚合。
4. 继续优化搜索 query 规划和行业泛化。

完成前两项后，信息采集 Agent 在答辩中会更容易体现“有计划地采集、能解释来源质量、能证明重跑改善”的价值。
