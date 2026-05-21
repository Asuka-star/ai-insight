# AI Insight

AI Insight is a Spring Boot prototype for the AI-3 topic: a traceable, reviewable, observable competitive analysis Agent collaboration system.

The project is intentionally backend-first for the first milestone. It already models analysis runs, Agent steps, evidence sources, report artifacts, review findings, revision output, and SSE progress events. The current workflow uses deterministic mock Agent nodes so the runtime surface can be tested before real LLM/RAG providers are wired in.

## Positioning

- Traceable: report claims should be connected to source snippets and citation IDs.
- Reviewable: a Reviewer Agent checks citation coverage, conclusion risk, and missing evidence before the final report is emitted.
- Observable: every Agent step, artifact, status change, and recommended next action belongs to an `analysis_run`.
- Rerunnable: a single Agent can be rerun without starting the whole run from scratch.

## First API

Start a run:

```bash
curl -X POST http://localhost:8080/api/analysis-runs \
  -H "Content-Type: application/json" \
  -d "{\"prompt\":\"分析 Notion 和飞书文档在 AI 协作文档方向的竞品机会\"}"
```

Read a run:

```bash
curl http://localhost:8080/api/analysis-runs/{runId}
```

Subscribe to progress:

```bash
curl -N http://localhost:8080/api/analysis-runs/{runId}/events
```

Rerun one Agent:

```bash
curl -X POST http://localhost:8080/api/analysis-runs/{runId}/agents/REVIEWER/rerun
```

## Local Development

```bash
mvn test
mvn spring-boot:run
```

## Xiaomi LLM

The app reads Xiaomi LLM settings from environment variables. Do not commit real keys.

PowerShell example:

```powershell
$env:XIAOMI_LLM_API_KEY="your-api-key"
$env:XIAOMI_LLM_BASE_URL="https://token-plan-cn.xiaomimimo.com/v1"
$env:XIAOMI_LLM_MODEL="mimo-v2.5-pro"
mvn spring-boot:run
```

When `XIAOMI_LLM_API_KEY` is present, the Writer and Reviewer agents call the Xiaomi OpenAI-compatible `chat/completions` API. Without a key, they use deterministic fallback output so tests and local demos still run.

## Planned Integrations

- LangGraph4j: replace the deterministic pipeline with a graph of Researcher, Extractor, Analyst, Writer, Reviewer, and Revision nodes.
- Spring AI: model calls, prompt templates, document readers, embeddings, and vector store access.
- PostgreSQL + pgvector: source chunks, embeddings, evidence references, and run persistence.
- Redis: async task progress, run locks, and short-lived event fanout.
- Vue/React workbench: live Agent timeline, evidence panel, report versions, review issues, and single-node reruns.
