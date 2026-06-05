# 信息采集 Agent 后续优化记录

## 1. 文档目的

本文记录当前信息采集 Agent 与课题要求之间的差距，以及后续可迭代的优化方向。

当前系统已经能完成公开 URL 抓取、Tavily 搜索、问卷/访谈设计、用户资料录入和访谈洞察抽取。但从课题中“数字调研小组”的目标看，信息采集 Agent 还应进一步具备更强的证据规划、问卷调研执行、访谈资料归纳和来源质量评估能力。

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

## 3. 主要差距

### 3.1 证据缺口还不够结构化

当前缺口主要是全局枚举，例如：

- `pricing_page`
- `user_review`
- `survey_result`
- `interview_note`

更符合调研工作流的方式是按竞品、维度和证据类型拆分缺口，例如：

- Salesforce 缺价格页证据。
- HubSpot 缺用户评价证据。
- “权限治理”维度缺访谈证据。
- “价格策略”维度缺公开来源证据。

后续可新增结构：

```java
ResearchEvidenceGap {
    String competitor;
    String dimension;
    String evidenceType;
    String reason;
    String priority;
    List<String> suggestedQueries;
}
```

这样 Reviewer 打回采集时，也能明确要求 Researcher 补哪类证据，而不是只给一个笼统缺口。

### 3.2 搜索 Query 仍可优化

当前搜索已经接入真实 provider，但 query 仍有一定模板化倾向，部分场景还会带上 `AI collaboration`。

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

建议结构：

```java
SurveyInsight {
    String evidenceId;
    String sampleSize;
    List<String> respondentSegments;
    List<SurveyFinding> findings;
    List<String> competitorMentions;
    List<String> relatedDimensions;
}

SurveyFinding {
    String question;
    String finding;
    String distribution;
    List<String> evidenceIds;
}
```

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

### P0：结构化证据缺口

目标：让 Researcher 明确知道“为哪个竞品、哪个维度、补什么证据”。

建议实现：

- 新增 `ResearchEvidenceGap` schema。
- `ResearchPackage` 增加 `evidenceGapsDetailed`。
- Reviewer 打回时能指定具体缺口。
- 前端 Schema 展示具体缺口。

收益：

- 最贴合信息采集 Agent 的职责。
- 能提升 Reviewer 打回采集的说服力。
- 便于答辩时展示“Agent 有计划地采集”。

### P1：搜索 Query 泛化

目标：让搜索 query 根据行业、竞品、维度、来源偏好动态生成。

建议实现：

- 从 `SourceCollectionService` 中抽出 `SearchQueryPlanner`。
- LLM 可用时由 LLM 生成 query。
- LLM 不可用时使用规则模板。
- query 记录到 `ResearchPlan.searchQueries`。

收益：

- 提升真实采集命中率。
- 避免所有行业都带 `AI collaboration`。
- 搜索功能更像真正可用的采集能力。

### P1：SurveyInsight

目标：稳定处理用户手动采集后导入的问卷结果。

建议实现：

- 支持 `sourceType=survey` 的用户资料进入 `SurveyInsightExtractor`。
- 从问卷结果中抽取样本量、主要结论、选项分布、开放题观点。
- 写入 `ResearchPackage.surveyInsights`。
- Extractor/Analyst 可以消费问卷洞察。

收益：

- 让“问卷调研”不止停留在问卷设计。
- 避免依赖外部问卷平台权限、付费和 API 变动。
- 适合比赛演示。

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

1. 新增结构化 `ResearchEvidenceGap`。
2. 优化搜索 query 规划。
3. 新增 `SurveyInsight` 和 `SurveyInsightExtractor`。
4. 将 `InterviewInsightExtractor` 升级为 LLM 优先、规则兜底。
5. 增加来源质量评分。

完成前三项后，信息采集 Agent 基本可以从“资料采集入口”升级为“可规划、可执行、可沉淀洞察的调研 Agent”。
