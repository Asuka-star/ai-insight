# 澄清 Agent 实施记录

## 当前结论

项目采用“启动前人机确认 + 主流程多 Agent 协作”的形态。范围确认仍然发生在正式分析启动前；Clarifier 作为主 DAG 前置的预检 Agent 执行，负责把用户原始输入澄清成可编辑、可回放的结构化范围草稿。

这样可以同时满足两个目标：

- 用户创建任务后立刻看到可确认的澄清草稿，并且 Clarifier 的 Step、Trace、Artifact 会进入执行回放。
- 用户确认范围后，长耗时主分析 DAG 直接从 `RESEARCHER` 开始，避免每次正式分析重复跑澄清节点。

## 当前实现状态

已完成：

- `AnalysisWorkflowService.createDraft` 创建 `AWAITING_CONFIRMATION` 运行，并执行一次前置 `CLARIFIER`。
- `POST /api/analysis-runs/{runId}/clarify` 支持在确认前重新澄清。
- `ClarifierNode` 调用 LLM 生成结构化范围草稿，并在 LLM 不可用、空回复或 JSON 解析失败时使用规则草稿兜底。
- `FallbackClarificationDraftFactory` 统一创建阶段和 Clarifier fallback 的规则草稿，避免两套问题口径漂移。
- `ClarifierNode` 产出 `CLARIFICATION_BRIEF` artifact，并补充可选澄清项 `ClarificationItem` / `ClarificationOption`。
- 前端范围确认面板支持点击澄清选项、重新澄清，并在工作流图中以预检虚线展示 `CLARIFIER -> RESEARCHER`。
- 主 DAG 不再包含 `CLARIFIER`，正式执行从 `RESEARCHER` 开始。

## 当前流程

```text
PRECHECK:
createDraft / clarify
-> CLARIFIER
-> AWAITING_CONFIRMATION

MAIN DAG:
START
-> RESEARCHER
-> EXTRACTOR
-> ANALYST
-> WRITER
-> REVIEWER
-> REVIEW_GATE
   -> RESEARCHER  when route = recollect
   -> ANALYST     when route = reanalyze
   -> WRITER      when route = revise
   -> END         when route = finish
```

## 设计边界

- `createDraft`：创建运行并触发前置 Clarifier，返回可编辑的范围确认内容。
- `clarifyRequirement`：在确认前重新执行前置 Clarifier，不自动启动主流程。
- `ScopeConfirmationPanel`：让用户确认、修改范围，或选择 Clarifier 给出的澄清选项。
- `ClarifierNode`：负责任务理解、范围摘要和下游输入固化，但不参与正式长耗时分析 DAG。
- `ReviewerNode`：只负责证据、结论、Schema 和报告质量，不承担范围澄清。

## 验收标准

- 创建任务后进入 `AWAITING_CONFIRMATION`，并生成 `clarificationDraft`。
- 用户确认范围前，主分析 DAG 不启动。
- `CLARIFIER` 有 Step、Trace 和 `CLARIFICATION_BRIEF` artifact，可在执行回放中查看。
- 用户确认后，主流程第一个正式 Agent 是 `RESEARCHER`。
- `RESEARCHER` 之后继续执行资料采集、抽取、分析、写作、质检和修订。
- `mvn test` 和 `npm run build` 通过。
