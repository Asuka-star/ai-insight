# 证据支撑匹配器统一开发文档

## 0. 文档状态

状态：待实现。

本文用于指导后续把 `AnalystNode`、`WriterNode` 和 `ReviewerNode` 中分散的“证据是否支撑文本”判断收口为共享能力。目标不是增加更多僵硬规则，而是把当前散落在多个 Agent 中的启发式逻辑统一为可解释、可测试、可逐步替换的支撑匹配层。

建议优先级：P1。

当前不建议把这项工作混入报告格式修复、Reviewer 阻断策略或来源分类修复中。它涉及主流程质量契约，应该单独开发、单独验证、分阶段接入。

## 1. 背景

当前主流程中的可信链路是：

```text
EvidenceSource / EvidenceChunk
-> ExtractedFact
-> AnalysisClaim
-> Matrix / SWOT
-> Report
-> ReviewFinding / ReviewDecision
```

这条链路中的多个节点都在判断类似问题：

- `AnalystNode`：某个 `evidenceId`、`chunkKey` 或 `factId` 是否真的支撑一个 `AnalysisClaim`。
- `WriterNode`：报告中的某一行是否匹配某个可进入主报告的 Claim，并应补哪些 citation。
- `ReviewerNode`：报告和 Claim 是否存在证据不匹配、弱支撑、过度推断或缺引用。

当前这些判断分散在各节点内部，各自维护词项提取、重叠阈值、来源强度和高风险判断。短期内它们能工作，但长期会带来口径漂移：

- Analyst 认为 Claim 由 `[S4]` 支撑，Writer 可能给对应报告句子补 `[S7]`。
- Writer 认为某行可以自动补 citation，Reviewer 可能认为该 citation 与语义不一致。
- Reviewer 将问题打回 Analyst 或 Writer，但上游修复时使用的匹配口径和 Reviewer 不同，导致返工不稳定。

## 2. 当前问题

### 2.1 匹配逻辑分散

现有逻辑大致分布如下：

- `AnalystNode.pruneUnsupportedClaimEvidence`
- `AnalystNode.supportTextMatches`
- `AnalystNode.factMatchScore`
- `WriterNode.claimMatchesLine`
- `WriterNode.citationsForLine`
- `ReviewerNode` 中的 claim-evidence 语义质检上下文构建和 finding 合并
- `EvidenceRetrievalService` 中的关键词、语义、来源质量和 contentKind 评分

这些逻辑并不完全重复，但底层问题相同：文本、事实、Claim 与证据之间如何建立可解释的支撑关系。

### 2.2 词项重叠过于脆弱

当前词项重叠适合做兜底，但不适合作为唯一判断：

- 中文只用 bigram，三字以上产品名、能力名和专有名词会被拆散。
- 抽象 Claim 与具体证据之间可能没有足够词项重叠。
- 任一侧词项很少时，阈值会退化为 1 个词，容易误匹配。
- 英文、中文、缩写和同义表达之间缺少稳定归一化。

### 2.3 证据强度与语义支撑耦合

有些判断混合了两个问题：

- 证据内容是否相关。
- 来源是否足够强。

例如价格、安全、权限、合规等高风险 Claim 需要一手强证据。这是质量策略，不应和“文本是否相关”的基础匹配完全混在一起。否则后续想调阈值时会牵动太多节点。

### 2.4 Reviewer 返工口径不稳定

Reviewer 输出 finding 后，上游 Agent 按自己的局部匹配逻辑修复。即使修复有效，Reviewer 下一轮仍可能因口径不同再次报问题；反过来，修复无效也可能被局部规则误判为已修。

## 3. 设计目标

### 3.1 产品目标

让前端和最终报告可以解释：

- 某个 Claim 由哪些证据支撑。
- 支撑强度是强、部分、弱还是不支持。
- 证据支撑来自 source、chunk、fact 还是 Analyst 摘录。
- 为什么一个结论被允许进入主报告，或者为什么只能进入“风险与证据缺口”。

### 3.2 工程目标

把分散逻辑收口为一个共享服务：

```text
EvidenceSupportMatcher
```

该服务输出结构化匹配结果，而不是简单返回 boolean。

### 3.3 质量目标

- Analyst、Writer、Reviewer 使用同一套基础匹配口径。
- 匹配结果可测试、可回放、可写入 Trace 或 ReviewFinding。
- 保留调用方的策略差异：Analyst 可以更严格，Writer 可以更保守，Reviewer 可以更敏感。
- 后续可以把 embedding、LLM rerank 或更好的中文分词接入同一个接口。

## 4. 非目标

本项目不在第一阶段实现以下内容：

- 不一次性重写 `AnalystNode`、`WriterNode`、`ReviewerNode`。
- 不引入必须联网或必须调用 LLM 的匹配器。
- 不把所有质量判断都改成黑盒语义分数。
- 不要求匹配器替代 Reviewer 的 LLM 语义质检。
- 不在第一阶段修改数据库 schema。
- 不强制前端新增展示，先保证后端结果稳定。

## 5. 核心原则

### 5.1 少做硬规则，多做结构化证据解释

“减少规则”不等于完全不用规则。建议把规则降级为可解释特征：

- 匹配到哪些关键词。
- 命中哪些 citation。
- 命中哪些 chunk。
- 是否含同一竞品名。
- 是否含相同维度。
- 来源是否一手。
- 是否属于高风险主题。

最终由 matcher 输出分数和理由，调用方再决定如何使用。

### 5.2 匹配器只判断支撑，不替 Agent 做决策

匹配器回答：

```text
证据是否支撑这段文本？支撑到什么程度？为什么？
```

调用方回答：

```text
这个支撑程度是否足以进入 Claim、报告、矩阵或自动 PASS？
```

### 5.3 先稳定契约，再优化算法

第一阶段重点是接口、返回结构和测试语料。算法可以先比当前逻辑略保守，不追求一次达到最佳效果。

### 5.4 保留确定性 fallback

未配置 embedding 或 LLM 时，匹配器仍必须可用。确定性结果是本地测试、演示和 CI 的底线。

## 6. 建议包结构

新增包：

```text
src/main/java/com/aiinsight/service/support
├── EvidenceSupportMatcher.java
├── EvidenceSupportRequest.java
├── EvidenceSupportResult.java
├── EvidenceSupportCandidate.java
├── EvidenceSupportMatch.java
├── EvidenceSupportReason.java
├── EvidenceSupportLevel.java
├── EvidenceSupportPolicy.java
├── EvidenceSupportTextAnalyzer.java
└── DefaultEvidenceSupportMatcher.java
```

也可以放在 `com.aiinsight.service` 根包下，但建议单独建 `service.support`，避免继续扩大已有 service 文件。

## 7. 核心接口设计

### 7.1 EvidenceSupportMatcher

```java
public interface EvidenceSupportMatcher {
    EvidenceSupportResult match(EvidenceSupportRequest request);
}
```

### 7.2 EvidenceSupportRequest

建议字段：

```java
public class EvidenceSupportRequest {
    private AnalysisRun run;
    private String statement;
    private List<String> competitorNames;
    private String dimension;
    private ClaimType claimType;
    private ConfidenceLevel confidence;
    private List<String> evidenceIds;
    private List<String> chunkKeys;
    private List<String> factIds;
    private List<String> evidenceQuotes;
    private EvidenceSupportPolicy policy;
}
```

说明：

- `statement` 可以是 Claim 内容、报告行、表格单元格或 Reviewer 检查文本。
- `evidenceIds/chunkKeys/factIds` 为空时，matcher 可以从 run 中搜索候选证据。
- `policy` 用于表达调用场景，不直接把场景写死在 matcher 内。

### 7.3 EvidenceSupportPolicy

建议枚举：

```java
public enum EvidenceSupportPolicy {
    CLAIM_BINDING,
    REPORT_CITATION,
    REVIEW_STRICT,
    REVIEW_REPAIR_VERIFICATION,
    HIGH_RISK_CLAIM
}
```

含义：

- `CLAIM_BINDING`：Analyst 绑定 evidence 到 Claim，偏严格。
- `REPORT_CITATION`：Writer 给报告句子补 citation，偏保守，不确定时宁可不补或标待验证。
- `REVIEW_STRICT`：Reviewer 判断是否需要 finding，偏敏感。
- `REVIEW_REPAIR_VERIFICATION`：返工验证时使用，要求能解释是否解决上一轮定位问题。
- `HIGH_RISK_CLAIM`：价格、安全、权限、合规、部署等敏感结论，要求强来源。

### 7.4 EvidenceSupportResult

建议字段：

```java
public class EvidenceSupportResult {
    private EvidenceSupportLevel level;
    private double score;
    private List<EvidenceSupportMatch> matches;
    private List<String> matchedEvidenceIds;
    private List<String> matchedChunkKeys;
    private List<String> matchedFactIds;
    private List<String> missingEvidenceTypes;
    private List<EvidenceSupportReason> reasons;
    private String explanation;
}
```

### 7.5 EvidenceSupportLevel

建议枚举：

```java
public enum EvidenceSupportLevel {
    STRONG,
    PARTIAL,
    WEAK,
    UNSUPPORTED
}
```

建议语义：

- `STRONG`：内容相关、竞品一致、维度一致，且来源强度满足场景。
- `PARTIAL`：内容相关，但来源强度、维度覆盖或直接摘录不足。
- `WEAK`：只有浅层词项相关，不能支撑确定结论。
- `UNSUPPORTED`：找不到有效支撑。

### 7.6 EvidenceSupportMatch

建议字段：

```java
public class EvidenceSupportMatch {
    private String evidenceId;
    private String chunkKey;
    private String factId;
    private double score;
    private EvidenceSupportLevel level;
    private List<String> matchedTerms;
    private List<String> matchedCompetitors;
    private List<String> matchedDimensions;
    private String quote;
    private String sourceAuthority;
    private String sourceQuality;
    private String sourceType;
    private List<EvidenceSupportReason> reasons;
}
```

## 8. 匹配流程设计

### 8.1 总体流程

```text
输入 statement / claim / report line
-> 归一化文本
-> 提取候选证据
-> 对 source / chunk / fact / quote 逐一评分
-> 合并 evidence-level 分数
-> 应用 policy
-> 输出 EvidenceSupportResult
```

### 8.2 文本归一化

`EvidenceSupportTextAnalyzer` 负责：

- 小写化英文。
- 去除 Markdown citation、claim id、表格符号。
- 保留产品名、英文缩写、版本名。
- 中文提取 bigram，同时保留可能的 3 到 6 字连续词窗口。
- 英文提取 3 字符以上 token。
- 归一化常见跨语言词：
  - `权限` -> `permission`, `access control`
  - `安全` -> `security`, `trust`
  - `定价` -> `pricing`, `price`
  - `部署` -> `deployment`
  - `评价` -> `review`, `feedback`
  - `智能体` -> `agent`
  - `工作流` -> `workflow`

注意：跨语言词表应小而稳定，作为解释性增强，不要无限扩展。

### 8.3 候选证据选择

候选证据来源：

1. 请求显式传入的 `evidenceIds`。
2. 请求显式传入的 `chunkKeys`。
3. 请求显式传入的 `factIds`。
4. Claim 中已有的 `evidenceQuotes`。
5. 当前 run 的 `EvidenceChunk` 中与 statement 初步相关的片段。
6. 可选：通过 `EvidenceRetrievalService` 获取 topK。

第一阶段建议先使用 1 到 5，避免引入额外服务循环依赖。第二阶段再考虑接 `EvidenceRetrievalService`。

### 8.4 基础评分特征

建议输出可解释分数，不要只写死阈值。

候选特征：

- `termOverlapScore`：文本词项重叠。
- `competitorScore`：竞品名是否一致。
- `dimensionScore`：维度词是否一致。
- `quoteScore`：Analyst 提供的 evidenceQuote 是否能在 source/chunk 中找到。
- `factScore`：ExtractedFact 是否与 statement 匹配。
- `chunkKindScore`：contentKind 是否符合维度，例如 pricing/security/permission。
- `authorityScore`：sourceAuthority 强度。
- `qualityScore`：sourceQuality 强度。
- `riskPenalty`：高风险主题缺少强来源时降级。

### 8.5 分数到等级

建议第一版：

```text
STRONG:
  内容相关 + 竞品一致 + 至少一个强支撑来源

PARTIAL:
  内容相关，但来源强度不足、缺少直接摘录或维度不完全一致

WEAK:
  只有浅层词项相关，或来源质量较弱

UNSUPPORTED:
  无有效候选或候选与 statement 不匹配
```

具体分数可以先内部使用，不直接暴露为产品承诺。

## 9. Agent 接入方式

### 9.1 AnalystNode

替换目标：

- `pruneUnsupportedClaimEvidence`
- `evidenceSupportsClaim`
- `supportTextMatches`
- 部分 `factMatchScore`

建议接入：

```text
AnalysisClaim
-> EvidenceSupportMatcher.match(policy=CLAIM_BINDING)
-> 根据 result 更新 evidenceIds / chunkKeys / factIds / supportStatus / supportReason
```

策略：

- `STRONG` 和 `PARTIAL` 可保留 evidence。
- `WEAK` 不用于 HIGH Claim，可降为 PARTIAL 或 VALIDATION_BACKLOG。
- `UNSUPPORTED` 清空 evidenceIds 或进入待验证。
- 对 high-risk Claim，必须要求 `STRONG` 或足够一手的 `PARTIAL`。

### 9.2 WriterNode

替换目标：

- `claimMatchesLine`
- `citationsForLine`
- 部分 `needsOverclaimDowngrade`

建议接入：

```text
report line / table cell
-> 从 eligible main claims 中构造候选
-> EvidenceSupportMatcher.match(policy=REPORT_CITATION)
-> STRONG/PARTIAL 才补 citation
-> WEAK/UNSUPPORTED 标为待验证或不补
```

策略：

- Writer 不应根据弱匹配自动补 citation。
- 表格行也应使用相同逻辑。
- 如果句子是强建议、优先级或优势判断，至少需要 `PARTIAL` 以上且不能只有三方弱来源。

### 9.3 ReviewerNode

替换目标：

- claim-evidence prompt 中的证据摘要构造。
- LLM findings 合并前的 deterministic precheck。
- repair verification 中判断上一轮问题是否已解决。

建议接入：

```text
claim / report excerpt
-> EvidenceSupportMatcher.match(policy=REVIEW_STRICT)
-> WEAK/UNSUPPORTED 形成 deterministic finding 或增强 LLM prompt
```

策略：

- 不用 matcher 完全替代 LLM 语义质检。
- matcher 结果可作为 LLM review 的输入上下文。
- 对 `UNSUPPORTED` 的 HIGH confidence claim，可以直接形成或强化 `claim_evidence_mismatch`。

## 10. 与 EvidenceRetrievalService 的关系

`EvidenceRetrievalService` 回答：

```text
给定 query，从 run/global chunks 中召回相关片段。
```

`EvidenceSupportMatcher` 回答：

```text
给定 statement 和候选证据，判断候选证据是否支撑 statement。
```

二者关系：

- 第一阶段 matcher 不依赖 retrieval，避免复杂依赖。
- 第二阶段可以把 retrieval 作为候选生成器。
- 不建议把 matcher 直接塞进 retrieval，否则召回和验证会混在一起。

## 11. 测试设计

### 11.1 新增测试类

建议新增：

```text
src/test/java/com/aiinsight/service/support/EvidenceSupportTextAnalyzerTest.java
src/test/java/com/aiinsight/service/support/DefaultEvidenceSupportMatcherTest.java
```

后续改造 Agent 时更新：

```text
src/test/java/com/aiinsight/agent/node/AnalystNodeTest.java
src/test/java/com/aiinsight/agent/node/WriterNodeTest.java
src/test/java/com/aiinsight/agent/node/ReviewerNodeTest.java
src/test/java/com/aiinsight/service/AnalysisWorkflowServiceTest.java
```

### 11.2 核心语料

#### 强支撑

Claim：

```text
Cursor 与 Claude Code 在 AI 编程助手的核心能力上路径分明。
```

Evidence：

```text
Cursor provides IDE-based AI coding assistance. Claude Code focuses on terminal-based agentic coding workflows.
```

预期：`PARTIAL` 或 `STRONG`，取决于来源强度。

#### 抽象 Claim 与具体证据

Claim：

```text
Cursor 更适合 IDE 内高频编码协作。
```

Evidence：

```text
Cursor integrates AI assistance inside the editor and supports multi-file editing.
```

预期：`PARTIAL`，不能因为词项不完全重叠而误杀。

#### 过度推断

Claim：

```text
Cursor 能直接提升团队研发效率并带来 ROI。
```

Evidence：

```text
Cursor supports code completion and multi-file edits.
```

预期：`WEAK` 或 `UNSUPPORTED`。

#### 高风险定价

Claim：

```text
Notion 企业版价格更适合中大型团队。
```

Evidence：

```text
Third-party blog compares Notion pricing plans.
```

预期：内容可能 `PARTIAL`，但 policy=`HIGH_RISK_CLAIM` 下不能 `STRONG`。

#### 表格行补 citation

Statement：

```text
| 核心能力 | Cursor 与 Claude Code 在 AI 编程助手的核心能力上路径分明。 |
```

预期：matcher 能忽略表格符号，返回匹配 Claim 和 evidence。

#### 待验证内容

Claim：

```text
Claude Code 在企业安全治理上领先。
```

Evidence：

```text
Claude Code supports terminal-based coding workflows.
```

预期：`UNSUPPORTED` 或 `WEAK`。

### 11.3 回归断言

必须覆盖：

- 中文短句不因长度阈值被跳过。
- 三字以上中文词不会只靠 bigram 产生误判。
- citation 不因表格行丢失。
- Analyst 保留的 evidence 与 Writer 自动补的 citation 一致。
- Reviewer 对同一 claim/evidence 的判断与 Analyst 基础支撑等级一致。

## 12. 分阶段实施计划

### 阶段 1：抽接口与确定性 matcher

目标：

- 新增 `service.support` 包。
- 实现 `EvidenceSupportTextAnalyzer`。
- 实现 `DefaultEvidenceSupportMatcher`。
- 新增 matcher 单元测试。
- 不接入 Agent。

验收：

```powershell
mvn "-Dtest=EvidenceSupportTextAnalyzerTest,DefaultEvidenceSupportMatcherTest,SourceEncodingGuardTest" test
```

### 阶段 2：接入 WriterNode

原因：

Writer 的影响面最直观，且回归测试容易观察报告文本变化。

改造点：

- `claimMatchesLine` 改为调用 matcher。
- `citationsForLine` 基于 `EvidenceSupportResult.matchedEvidenceIds`。
- 保留现有 citation 插入和待验证格式修复。

验收：

```powershell
mvn "-Dtest=WriterNodeTest,SourceEncodingGuardTest" test
```

### 阶段 3：接入 AnalystNode

改造点：

- `pruneUnsupportedClaimEvidence` 使用 matcher。
- 更新 Claim 的 `supportStatus`、`supportReason`、`missingEvidenceTypes`。
- 避免有效抽象 Claim 被误杀。

验收：

```powershell
mvn "-Dtest=AnalystNodeTest,WriterNodeTest,SourceEncodingGuardTest" test
```

### 阶段 4：接入 ReviewerNode

改造点：

- deterministic review 中对 Claim 支撑状态使用 matcher 结果。
- LLM prompt 中加入 matcher explanation，而不是只放 evidence snippet。
- repair verification 使用 matcher 判断是否解决。

验收：

```powershell
mvn "-Dtest=ReviewerNodeTest,AnalysisWorkflowServiceTest,SourceEncodingGuardTest" test
```

### 阶段 5：全流程验证与指标

目标：

- 跑全量测试。
- 选 2 到 3 个历史 demo prompt 做人工对比。
- 观察 citation 一致性、finding 数量、claim coverage 和报告可读性。

验收：

```powershell
mvn test
cd frontend
npm run build
```

## 13. 迁移注意事项

### 13.1 不要一次删除旧方法

第一轮接入时可以保留旧方法，先把新 matcher 用在新增路径或旁路校验中。确认测试稳定后再删除旧方法。

### 13.2 Trace 中记录 matcher 摘要

建议在 AgentTrace 中通过 `AgentTraceContext.recordProcessSummary` 记录关键匹配摘要：

```text
Evidence support match:
- statement=...
- policy=REPORT_CITATION
- level=PARTIAL
- evidence=[S1,S4]
- reasons=competitor_match, dimension_match, first_party_source
```

### 13.3 不要让分数成为用户可见承诺

内部 score 用于排序和测试。前端如需展示，优先展示 level 和 explanation，不展示裸分。

### 13.4 保持 fallback 可读

未配置 embedding 或 LLM 时，matcher 也必须返回解释，而不是空结果。

## 14. 风险与缓解

### 14.1 风险：统一后误伤现有可用报告

缓解：

- 先接 Writer，再接 Analyst。
- matcher 初期保持保守。
- 所有降级必须写 reason。

### 14.2 风险：变成另一套复杂规则

缓解：

- 限制跨语言词表规模。
- 所有规则输出为 reason，不直接散落在 Agent 中。
- 每个新增特征必须有测试语料。

### 14.3 风险：Reviewer 过度阻断

缓解：

- 区分 `WEAK` 和 `UNSUPPORTED`。
- HIGH finding 只给真正会误导用户的主结论。
- 低质量来源问题可保留为 MEDIUM，除非支撑了高风险确定结论。

### 14.4 风险：性能变差

缓解：

- 候选证据先限制在 Claim 绑定 evidence 和 top chunks。
- 文本分析结果可在 request 内局部缓存。
- 不在 matcher 内做全库扫描。

## 15. 建议实现细节

### 15.1 TextAnalyzer 输出

建议：

```java
public class EvidenceSupportTextFeatures {
    private String normalizedText;
    private Set<String> terms;
    private Set<String> chineseWindows;
    private Set<String> competitorMentions;
    private Set<String> dimensionTerms;
    private Set<String> riskTerms;
}
```

### 15.2 Reason 枚举

建议：

```java
public enum EvidenceSupportReason {
    TERM_OVERLAP,
    COMPETITOR_MATCH,
    DIMENSION_MATCH,
    FACT_MATCH,
    QUOTE_MATCH,
    CHUNK_KIND_MATCH,
    FIRST_PARTY_SOURCE,
    HIGH_QUALITY_SOURCE,
    THIRD_PARTY_ONLY,
    LOW_QUALITY_SOURCE,
    HIGH_RISK_REQUIRES_STRONGER_SOURCE,
    NO_COMPETITOR_MATCH,
    NO_DIRECT_SUPPORT
}
```

### 15.3 Claim 字段写入建议

已有 `AnalysisClaim` 字段可以承接结果：

- `supportStatus`
- `supportReason`
- `rewriteSuggestion`
- `evidenceQuotes`
- `missingEvidenceTypes`
- `eligibleForMainReport`
- `eligibleForMatrix`
- `eligibleForSwot`

建议不要新增 Claim 字段，除非后续前端需要展示 `supportLevel`。

## 16. 完成标准

### 16.1 工程完成标准

- `EvidenceSupportMatcher` 和测试落地。
- Writer/Analyst/Reviewer 至少两个节点接入共享 matcher。
- 删除或弱化重复的本地词项匹配方法。
- 全量 Maven 测试通过。
- 编码守卫通过。

### 16.2 质量完成标准

使用同一组 demo prompt 对比改造前后：

- Writer 自动补 citation 与 Analyst Claim evidence 更一致。
- Reviewer 不再因为同一实质问题换措辞而 finding 膨胀。
- 待验证 Claim 更稳定地停留在风险/补证区域。
- 高风险 Claim 不因弱来源进入强建议。
- 报告可读性不下降。

## 17. 推荐下一步

建议下一轮先做阶段 1：

1. 新增 `service.support` 包和数据结构。
2. 实现不依赖 LLM 的 `DefaultEvidenceSupportMatcher`。
3. 用本文第 11 节语料写单元测试。
4. 暂不接 Agent，只比较 matcher 输出是否符合预期。

阶段 1 完成后，再决定是先接 Writer 还是 Analyst。若目标是最快改善报告表现，先接 Writer；若目标是从源头提高 Claim 质量，先接 Analyst。
