# AI Insight 开发维护指南

这份文档给后续开发用，重点说明当前主线流程、关键代码入口、常用验证命令和本地数据维护方式。

## 主线流程

当前系统分成两个阶段：

1. 范围确认阶段：`createDraft` 创建任务后先执行 `CLARIFIER`，生成可编辑的 `ClarificationDraft`，等待用户确认。
2. 主分析阶段：用户确认后进入 LangGraph4j DAG。

主分析 DAG：

```text
RESEARCHER
-> EXTRACTOR
-> ANALYST
-> WRITER
-> REVIEWER
-> REVIEW_GATE
   -> RESEARCHER  when ReviewDecision = RECOLLECT_EVIDENCE
   -> ANALYST     when ReviewDecision = REWORK_ANALYSIS
   -> WRITER      when ReviewDecision = REVISE_REPORT
   -> FINALIZER   when ReviewDecision = PASS or rework limit reached
```

`FINALIZER` 是封版节点，不重写 Writer 正文，只追加 Reviewer 复核状态、修复计划和证据限制说明。

## 关键代码入口

- `AnalysisWorkflowService`：任务创建、范围确认、启动、取消、用户上下文和用户证据入口。
- `AnalysisLangGraphWorkflow`：主流程 DAG 和 `REVIEW_GATE` 条件边路由。
- `WorkflowNodeExecutor`：统一记录 AgentStep、AgentTrace、SSE 事件、日志和异常。
- `ResearcherNode`：采集证据、生成切片、调研计划和一手资料设计。
- `LlmSearchQueryPlanner`：为 Researcher 生成搜索 query；只决定搜什么，不决定证据可信度。
- `SourceCollectionService`：抓取 URL、执行搜索、去重、保留 citation 稳定性。
- `ExtractorNode`：从证据抽取竞品结构化画像。
- `AnalystNode`：生成矩阵、SWOT 和结构化 claims。
- `WriterNode`：基于需求、证据、Schema 和 Analyst 产物撰写报告草稿。
- `ReviewerNode`：规则质检 + LLM 语义质检，生成 `ReviewFinding` 和 `ReviewDecision`。
- `FinalizerNode`：生成最终封版报告。
- `PostgresAnalysisRunRepository`：保存 `analysis_run.run_payload` 权威快照，并同步刷新明细投影表。

## 状态与数据约定

- `analysis_run.run_payload` 是恢复运行态的权威来源。
- `analysis_artifact`、`agent_step`、`agent_trace`、`evidence_source`、`evidence_chunk`、`review_finding` 是查询和看板投影。
- Agent 之间优先通过结构化对象传递状态，例如 `ResearchPackage`、`CompetitorProfile`、`AnalysisClaim`、`ReviewDecision`。
- 报告引用使用 `[S1]` 这类 citation key，结构化 claim 使用 `evidenceIds`。

## 本地数据维护

清理历史会话：

```powershell
docker exec -it ai-insight-pg psql -U ai_insight -d ai_insight -c "truncate table analysis_run cascade;"
```

清理网页抓取缓存：

```powershell
docker exec -it ai-insight-pg psql -U ai_insight -d ai_insight -c "truncate table fetched_page_cache;"
```

如果使用 compose 默认容器名，把 `docker exec -it ai-insight-pg` 替换为 `docker compose exec postgres`。

## 常用验证命令

后端核心流程测试：

```powershell
mvn "-Dtest=AnalysisWorkflowServiceTest" test
```

后端全量测试：

```powershell
mvn test
```

前端构建：

```powershell
cd frontend
npm run build
```

提交前检查：

```powershell
git diff --check
rg -n "FINALIZER|FinalizerNode|FINALIZATION_NOTE" src frontend/src docs README.md
```
