# 澄清 Agent 实施记录

## 当前结论

项目采用“工作流编排下的多 Agent 协作”。范围确认仍然是启动前的人机确认步骤，但 LLM 澄清不再放在 `createDraft` 接口里同步执行，而是作为主 DAG 的第一个轻量 Agent 节点执行。

这样可以同时满足两个目标：

- 填写范围确认时只生成规则版确认内容，接口响应稳定，不再因为 LLM 空回复或超时影响用户进入确认步骤。
- 启动后仍然有可观测的 `CLARIFIER` Agent，负责把已确认范围沉淀为结构化任务摘要，进入 Trace、Timeline 和 Artifact。

## 当前实现状态

已完成：

- `AnalysisWorkflowService.createDraft` 只生成规则版 `ClarificationDraft`，产品语义上表现为“范围确认内容”。
- 用户确认范围后才能调用 `startExecution`。
- 主 DAG 从 `CLARIFIER` 开始，再进入 `RESEARCHER`。
- `ClarifierNode` 调用 `ClarificationDraftService.clarifyScope` 执行 LLM 澄清。
- `ClarifierNode` 产出 `CLARIFICATION_BRIEF` artifact。
- LLM 不可用、空回复或 JSON 解析失败时，Clarifier 使用规则草稿兜底，不阻断主流程。

## 当前主流程 DAG

```text
START
-> CLARIFIER
-> RESEARCHER
-> EXTRACTOR
-> ANALYST
-> WRITER
-> REVIEWER
-> REVIEW_GATE
   -> RESEARCHER  when route = recollect
   -> ANALYST     when route = reanalyze
   -> WRITER      when route = revise
   -> REVISION    when route = finish
-> END
```

## 设计边界

- `createDraft`：只负责快速生成可编辑的范围确认内容。
- `ScopeConfirmationPanel`：负责让用户确认或修改范围。
- `ClarifierNode`：负责在工作流启动后做任务理解、范围摘要和下游输入固化。
- `ReviewerNode`：仍然只负责证据、结论、Schema 和报告质量，不承担范围澄清。

## 验收标准

- 填写范围确认后进入 `AWAITING_CONFIRMATION`，并生成 `clarificationDraft`。
- 用户确认范围前，主 Agent DAG 不启动。
- 启动后第一个 Agent 是 `CLARIFIER`。
- `CLARIFIER` 有 Step、Trace 和 `CLARIFICATION_BRIEF` artifact。
- `RESEARCHER` 之后继续执行资料采集、抽取、分析、写作、质检和修订。
- `mvn test` 和 `npm run build` 通过。
