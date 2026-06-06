## AI Insight 代码质量分析与优化路线图

### 量化数据总览

**后端（131 个 Java 文件，28,558 行）**

| 文件 | 行数 | 大小 | private 方法数 | 问题 |
|------|------|------|---------------|------|
| ExtractorNode.java | 1,958 | 99.7 KB | ~110 | God Class |
| SourceCollectionService.java | 1,929 | 98.5 KB | ~80 | God Class |
| AnalystNode.java | 1,877 | 95.0 KB | ~115 | God Class |
| ReviewerNode.java | 1,519 | 84.8 KB | ~94 | God Class |
| AnalysisWorkflowService.java | 1,119 | 58.2 KB | ~50 | 偏大 |
| PostgresAnalysisRunRepository.java | 1,098 | 50.0 KB | ~40 | 偏大 |
| WebPageFetchService.java | 891 | 41.3 KB | ~35 | 可接受 |
| CitationCoverageEvaluator.java | 828 | 42.1 KB | ~30 | 可接受 |

**前端（24 个 TS/TSX 文件，约 7,000 行）**

| 文件 | 行数 | 大小 | useState 数 | 问题 |
|------|------|------|------------|------|
| App.tsx | 1,735 | 71.1 KB | 43 | God Component |
| ReviewPanel.tsx | 480 | 23.6 KB | — | 可接受 |
| SchemaPanel.tsx | 457 | 19.9 KB | — | 可接受 |

**测试覆盖**

| 指标 | 数值 | 健康标准 |
|------|------|---------|
| 测试/生产代码比 | 0.51:1 | 0.75-1.5:1 |
| 无测试的业务类 | 30+ | 0 |
| 前端测试 | 0 行 | 有基本覆盖 |
| 集成测试 | 0 个 | 有基本覆盖 |
| 共享测试 fixture | 无 | 有 Builder/Factory |

---

### P0：提取公共工具层，消除复制粘贴（预计 2-3 天）

这是投入产出比最高的改进。当前项目中有大量"复制后微调"的工具方法散布在各个文件中，任何 bug 修复都需要同步修改多处。

**1.1 统一 `supportTerms` 分词逻辑**

当前状态：AnalystNode（L758-800）、ExtractorNode（L808-829）、WriterNode（L388-401）各有一份实现，核心逻辑相同但停用词集和跨语言扩展不同。

建议：提取到 `com.aiinsight.util.TermExtractor`，通过参数控制行为：

```java
public final class TermExtractor {
    public static Set<String> extract(String text, TermOptions options);
    // TermOptions: minTermLength, stopWords, enableCrossLingual, enableChineseBigram
}
```

**1.2 统一 Citation 正则**

当前状态：`[S\d+]` 模式在 AgentUtils（L31）、WriterNode（L49）、CitationCoverageEvaluator（L29-30）、ReviewerNode（L68）四处独立定义，其中 ReviewerNode 用的是 `\bS\d+\b`（不带方括号），和其他三处不一致。

建议：在 AgentUtils 中保留唯一的 `CITATION_PATTERN` 和 `CITATION_KEY_PATTERN` 常量，其他文件引用。

**1.3 消除 `containsIgnoreCase` / `nullToEmpty` / `hasText` 重复**

当前状态：`containsIgnoreCase` 在 14 个文件中出现 77 次。AgentUtils 已有公共版本，但大多数类仍使用自己的 private 副本。

建议：全局替换为 AgentUtils 或 Spring 的 `StringUtils` / `ObjectUtils`。可以一次性完成机械替换。

**1.4 统一 `knownEvidenceIds` 方法**

当前状态：ExtractorNode.knownEvidenceIds()（L1832）和 AnalystNode.distinctKnownEvidenceIds()（L556）功能完全相同。

建议：移入 AgentUtils。

---

### P1：拆分三大 God Class（预计 5-8 天）

ExtractorNode、AnalystNode、ReviewerNode 各含近百个 private 方法，单个文件承担 5-8 个不同职责。拆分的核心收益是降低修改风险——当前改一处很容易影响另一个不相关的功能。

**2.1 拆分 ExtractorNode（1,958 行 → 4 个类）**

| 新类 | 职责 | 预估行数 |
|------|------|---------|
| `ExtractorNode` | 入口编排（LLM 调用 + fallback 决策） | ~300 |
| `CompetitorProfileExtractor` | 竞品画像抽取（功能树、定价、用户画像） | ~500 |
| `FactExtractionEngine` | 结构化事实抽取（ExtractedFact、CompetitorFactSet） | ~600 |
| `EvidenceBindingService` | 证据-事实绑定验证（supportTerms、evidenceTextSupports） | ~400 |

**2.2 拆分 AnalystNode（1,877 行 → 4 个类）**

| 新类 | 职责 | 预估行数 |
|------|------|---------|
| `AnalystNode` | 入口编排 | ~250 |
| `ClaimGenerationEngine` | Claim 生成 + 清洗 + 稳定化 + repair guard | ~600 |
| `ClaimEvidenceBinder` | 证据绑定、修剪、验证（supportTerms、pruneUnsupported） | ~500 |
| `AnalysisProductRenderer` | 矩阵渲染 + SWOT 渲染 | ~400 |

**2.3 拆分 ReviewerNode（1,519 行 → 3 个类）**

| 新类 | 职责 | 预估行数 |
|------|------|---------|
| `ReviewerNode` | 入口编排 + finding 合并 + decision 构建 | ~400 |
| `LlmReviewSubtaskExecutor` | 5 个并发 LLM 子任务的 prompt 构造 + 结果解析 | ~500 |
| `ReviewRoutingStrategy` | repair agent 路由 + 升级策略 + 修复验证模式 | ~400 |

---

### P2：前端 App.tsx 拆分（预计 3-5 天）

App.tsx 是一个 1,735 行、43 个 useState 的 God Component。所有状态管理、事件处理、布局逻辑和 API 调用都集中在一个文件中。

**3.1 提取自定义 Hooks（状态逻辑抽离）**

| 新 Hook | 管理状态 | 预估行数 |
|---------|---------|---------|
| `useRunState` | run, serverRunMetrics, historyRuns | ~150 |
| `useScopeForm` | industry, competitors, dimensions, outputGoal, sourceUrls, scope 同步逻辑 | ~120 |
| `useSseConnection` | EventSource 创建/销毁、事件监听、轮询兜底 | ~150 |
| `useEvidenceForm` | evidenceTitle/Url/Content/Sensitive, isAddingEvidence | ~80 |
| `useDocumentUpload` | documentFile/Title/SourceType/Sensitive/Notes, isUploading | ~80 |
| `useLayoutState` | collapsedPanels, railWidths, historyOpen, resourcePackOpen, mainView | ~60 |

**3.2 提取布局子组件**

App.tsx 的 JSX 渲染部分（约 800 行）按三栏布局拆分为 `LeftRail`、`CenterStage`、`RightRail` 三个组件，各自接收所需 props。

---

### P3：Prompt 管理优化（预计 2-3 天）

当前所有 Agent 的 prompt 都用 `String.formatted()` 内联在方法体中，大段多行字符串和业务逻辑混杂。

**4.1 提取 Prompt 模板常量**

每个 Agent 的 system message 和 user message template 提取到对应的 `XxxPrompts` 类：

```java
public final class AnalystPrompts {
    public static final String SYSTEM = "你是严谨的竞品分析 Agent...";
    public static String claimsUserMessage(AnalystPromptContext ctx) { ... }
}
```

**4.2 统一 PromptContext DTO**

当前每个 Node 各自从 AnalysisRun 中提取字段拼字符串。提取一个 `PromptContext` DTO 作为中间层，Node 只负责填充 context，prompt 模板只消费 context。

**4.3 统一 ChatOptions 工厂**

当前所有 Agent 的 temperature 都是 0.2，仅 maxTokens 不同。把 7 个工厂方法合并为一个参数化方法 `ChatOptions.forAgent(AgentName, int maxTokens)`。

---

### P4：测试基础设施补齐（预计 3-5 天）

**5.1 创建测试数据 Builder**

当前 `new AnalysisRun(new AnalysisRequirement(...))` 嵌套构造在 19 个测试文件中出现 186 次，EvidenceSource 的 11 参数构造函数可读性差。

建议创建 `TestData` 工具类：

```java
public final class TestData {
    public static AnalysisRun run(String prompt, String... competitors);
    public static EvidenceSource source(String key, String title, String type);
    public static AnalysisClaim claim(String id, String content, String... evidenceIds);
    // ...
}
```

**5.2 创建共享 Mock 模块**

当前 113 处 `new LlmClient() { ... }` 匿名 stub 散布在 8 个测试文件中，每个 15-30 行。

建议创建 `TestLlmClient`（可配置返回值 + 记录调用参数）和 `TestAnalysisRunRepository`（内存存储），放入 `src/test/java/com/aiinsight/testutil/`。

**5.3 补齐关键缺失测试**

优先补齐以下无测试的核心类（按影响排序）：

| 优先级 | 类 | 行数 | 建议测试数量 |
|--------|-----|------|------------|
| P0 | `WorkflowNodeExecutor` | 730 | 15-20 |
| P0 | `AnalysisLangGraphWorkflow` | 749 | 10-15 |
| P1 | `ResearchAgent` | 708 | 10-15 |
| P1 | `ResearcherNode` | 554 | 8-10 |
| P2 | 6 个 Fallback 工厂 | ~1,360 | 15-20 |
| P2 | `DocumentIngestionService` | 570 | 8-10 |

---

### P5：SourceCollectionService 拆分（预计 2-3 天）

1,929 行的采集服务承担了 URL 推导、搜索查询规划、并发调度、robots 检查、质量评估、缓存管理、覆盖度计算等职责。

| 新类 | 职责 | 预估行数 |
|------|------|---------|
| `SourceCollectionService` | 入口编排 | ~400 |
| `OfficialReferenceCandidateGenerator` | 官网子页面推导（/pricing、/docs 等） | ~200 |
| `CollectionConcurrencyOrchestrator` | 滑动窗口并发、批次调度、超时控制 | ~400 |
| `SearchResultProcessor` | 搜索结果过滤、去重、轮询选择 | ~300 |
| `CollectionPlanTracker` | ResearchCollectionPlan 状态管理 | ~200 |

---

### P6：Fallback 工厂统一抽象（预计 1-2 天）

当前 6 个 Fallback 工厂没有共同基类，`researchDomain()`、`nullToEmpty()`、`hasText()` 等工具方法各自实现。

建议：
- 创建 `AbstractFallbackFactory<T>` 基类，提供共享工具方法
- 或者更轻量地：把所有工具方法移入 `FallbackSupport` 工具类
- 补齐 Fallback 工厂的单元测试（当前 0 个测试）

---

### P7：修复重跑改善对比 bug（预计 0.5 天）

上次分析已定位的问题：`RepairSnapshot` 在级联重跑时只捕获目标 Agent 的 before/after，后续 Agent 的变化丢失。

修复方案：在 `AnalysisLangGraphWorkflow.rerunAgent()` 的级联循环外层包裹一层全局 before/after 快照，级联完成后写入 `lastReviewRepairDelta`。

---

### 执行优先级总览

| 优先级 | 改进项 | 预计工期 | 核心收益 |
|--------|--------|---------|---------|
| **P0** | 提取公共工具层 | 2-3 天 | 消除 77 处复制，降低 bug 修复成本 |
| **P1** | 拆分三大 God Class | 5-8 天 | 单文件从 2000 行降到 300-600 行，修改风险大幅降低 |
| **P2** | 前端 App.tsx 拆分 | 3-5 天 | 43 个 useState 分组管理，组件可独立开发 |
| **P3** | Prompt 管理优化 | 2-3 天 | prompt 修改不再需要改业务代码 |
| **P4** | 测试基础设施补齐 | 3-5 天 | 为后续重构提供安全网 |
| **P5** | SourceCollectionService 拆分 | 2-3 天 | 采集逻辑模块化 |
| **P6** | Fallback 工厂统一 | 1-2 天 | 消除 1360 行重复代码 |
| **P7** | 重跑改善对比 bug 修复 | 0.5 天 | 修复已知功能缺陷 |

**建议执行顺序**：P7（快速止血）→ P0（消除重复）→ P4（补齐测试安全网）→ P1（核心重构）→ P2/P3/P5/P6（按需推进）

**总体预估**：约 18-29 天，可根据竞赛时间节点选择性地执行 P0-P4。
