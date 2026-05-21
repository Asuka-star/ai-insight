import { useCallback, useEffect, useMemo, useState } from "react";
import {
  Activity,
  AlertTriangle,
  BookOpenCheck,
  FileText,
  GitBranch,
  Play,
  RefreshCw,
  RotateCcw,
  Search,
  ShieldCheck,
  Sparkles
} from "lucide-react";
import type { AgentName, AnalysisArtifact, AnalysisRun, RunEvent } from "./types";
import { createRun, getRun, getWorkflowMermaid, listRuns, rerunAgent } from "./api";
import { AGENTS, AGENT_LABELS, ARTIFACT_LABELS, SOURCE_OPTIONS } from "./constants";
import { countCitedClaims, findDefaultArtifact, isActiveRun, splitList } from "./utils";
import { StatusBadge } from "./components/StatusBadge";
import { WorkflowGraph } from "./components/WorkflowGraph";
import { AgentTimeline } from "./components/AgentTimeline";
import { ArtifactViewer } from "./components/ArtifactViewer";
import { EvidencePanel } from "./components/EvidencePanel";
import { ReviewPanel } from "./components/ReviewPanel";
import { TraceDrawer } from "./components/TraceDrawer";
import { SchemaPanel } from "./components/SchemaPanel";

const samplePrompt = "分析 Notion 和飞书文档在 AI 协作文档方向的竞品机会";

export function App() {
  const [run, setRun] = useState<AnalysisRun | null>(null);
  const [prompt, setPrompt] = useState(samplePrompt);
  const [industry, setIndustry] = useState("");
  const [competitors, setCompetitors] = useState("");
  const [dimensions, setDimensions] = useState("");
  const [sources, setSources] = useState<string[]>(SOURCE_OPTIONS.map((source) => source.value));
  const [selectedArtifactId, setSelectedArtifactId] = useState<string>();
  const [artifactPinned, setArtifactPinned] = useState(false);
  const [selectedCitationKey, setSelectedCitationKey] = useState<string>();
  const [selectedAgent, setSelectedAgent] = useState<AgentName | null>(null);
  const [eventMessage, setEventMessage] = useState("等待创建任务");
  const [backendOk, setBackendOk] = useState(false);
  const [workflowMermaid, setWorkflowMermaid] = useState("");
  const [isCreating, setIsCreating] = useState(false);

  const refreshRun = useCallback(async (runId?: string) => {
    const id = runId ?? run?.id;
    if (!id) {
      const runs = await listRuns();
      setBackendOk(true);
      const latest = [...runs].sort((a, b) => Date.parse(b.updatedAt ?? "") - Date.parse(a.updatedAt ?? ""))[0];
      if (latest) {
        setRun(latest);
      }
      return;
    }
    const latest = await getRun(id);
    setBackendOk(true);
    setRun(latest);
  }, [run?.id]);

  useEffect(() => {
    refreshRun().catch((error) => {
      setBackendOk(false);
      setEventMessage(error.message);
    });
    getWorkflowMermaid()
      .then(setWorkflowMermaid)
      .catch(() => setWorkflowMermaid("后端暂未返回 Mermaid 定义"));
  }, []);

  useEffect(() => {
    if (!run?.id) return;
    const events = new EventSource(`/api/analysis-runs/${run.id}/events`);
    const eventTypes = [
      "subscribed",
      "run_created",
      "run_started",
      "agent_started",
      "agent_succeeded",
      "agent_failed",
      "agent_rerun_completed",
      "run_succeeded",
      "run_failed"
    ];
    eventTypes.forEach((type) => {
      events.addEventListener(type, (event) => {
        const data = safeParseEvent(event);
        setEventMessage(data?.message || type);
        refreshRun(run.id).catch((error) => setEventMessage(error.message));
      });
    });
    events.onerror = () => setEventMessage("SSE 暂不可用，使用轮询刷新");
    return () => events.close();
  }, [run?.id, refreshRun]);

  useEffect(() => {
    if (!run || !isActiveRun(run)) return;
    const activeRunId = run.id;
    const timer = window.setInterval(() => {
      refreshRun(activeRunId).catch((error) => setEventMessage(error.message));
    }, 1400);
    return () => window.clearInterval(timer);
  }, [run, refreshRun]);

  const selectedArtifact = useMemo(() => {
    const artifacts = run?.artifacts ?? [];
    if (!artifacts.length) return undefined;
    if (!artifactPinned) return findDefaultArtifact(artifacts);
    return artifacts.find((artifact) => artifact.id === selectedArtifactId) ?? findDefaultArtifact(artifacts);
  }, [artifactPinned, run?.artifacts, selectedArtifactId]);

  useEffect(() => {
    if (selectedArtifact && selectedArtifact.id !== selectedArtifactId) {
      setSelectedArtifactId(selectedArtifact.id);
    }
  }, [selectedArtifact, selectedArtifactId]);

  async function handleCreateRun() {
    setIsCreating(true);
    setEventMessage("正在创建分析任务");
    setArtifactPinned(false);
    setSelectedCitationKey(undefined);
    try {
      const nextRun = await createRun({
        prompt,
        industry,
        competitors: splitList(competitors),
        dimensions: splitList(dimensions),
        sourcePreferences: sources
      });
      setBackendOk(true);
      setRun(nextRun);
    } catch (error) {
      setBackendOk(false);
      setEventMessage(error instanceof Error ? error.message : "创建任务失败");
    } finally {
      setIsCreating(false);
    }
  }

  async function handleRerun(agentName: AgentName) {
    if (!run) return;
    setEventMessage(`正在重跑 ${AGENT_LABELS[agentName]}`);
    const nextRun = await rerunAgent(run.id, agentName);
    setRun(nextRun);
  }

  const metricCards = [
    { label: "Agent 步骤", value: run?.steps.length ?? 0, icon: Activity },
    { label: "证据来源", value: run?.evidenceSources.length ?? 0, icon: Search },
    { label: "质检问题", value: run?.reviewFindings.length ?? 0, icon: ShieldCheck },
    { label: "引用标记", value: countCitedClaims(run), icon: BookOpenCheck }
  ];

  return (
    <div className="app-shell">
      <header className="topbar">
        <div className="brand-block">
          <span className="brand-mark"><Sparkles size={18} /></span>
          <div>
            <p className="eyebrow">AI Insight</p>
            <h1>竞品分析 Agent 工作台</h1>
          </div>
        </div>
        <div className="header-actions">
          <StatusBadge label={backendOk ? "后端已连接" : "后端未连接"} tone={backendOk ? "success" : "danger"} />
          <StatusBadge label={run?.status ?? "等待任务"} tone={statusTone(run?.status)} />
          <button className="icon-button" type="button" onClick={() => refreshRun()} aria-label="刷新任务">
            <RefreshCw size={17} />
          </button>
        </div>
      </header>

      <main className="workspace">
        <aside className="left-rail">
          <section className="panel task-panel">
            <div className="section-title">
              <div>
                <p className="eyebrow">Request</p>
                <h2>分析任务</h2>
              </div>
              <button className="ghost-button" type="button" onClick={() => setPrompt(samplePrompt)}>
                示例
              </button>
            </div>
            <label>
              分析需求
              <textarea value={prompt} onChange={(event) => setPrompt(event.target.value)} rows={5} />
            </label>
            <label>
              行业方向
              <input value={industry} onChange={(event) => setIndustry(event.target.value)} placeholder="AI 协作文档" />
            </label>
            <label>
              竞品列表
              <input value={competitors} onChange={(event) => setCompetitors(event.target.value)} placeholder="Notion, 飞书文档" />
            </label>
            <label>
              分析维度
              <input value={dimensions} onChange={(event) => setDimensions(event.target.value)} placeholder="产品定位, 定价, 用户画像, 风险" />
            </label>
            <div className="source-options">
              {SOURCE_OPTIONS.map((source) => (
                <label key={source.value} className="check-row">
                  <input
                    type="checkbox"
                    checked={sources.includes(source.value)}
                    onChange={(event) => {
                      setSources((current) =>
                        event.target.checked
                          ? [...current, source.value]
                          : current.filter((value) => value !== source.value)
                      );
                    }}
                  />
                  {source.label}
                </label>
              ))}
            </div>
            <button className="primary-button" type="button" onClick={handleCreateRun} disabled={isCreating || !prompt.trim()}>
              <Play size={16} /> 开始分析
            </button>
          </section>

          <section className="panel">
            <div className="section-title">
              <div>
                <p className="eyebrow">Metrics</p>
                <h2>运行指标</h2>
              </div>
            </div>
            <div className="metric-grid">
              {metricCards.map((metric) => {
                const Icon = metric.icon;
                return (
                  <div className="metric-card" key={metric.label}>
                    <Icon size={18} />
                    <strong>{metric.value}</strong>
                    <span>{metric.label}</span>
                  </div>
                );
              })}
            </div>
          </section>
        </aside>

        <section className="center-stage">
          <section className="panel graph-panel">
            <div className="section-title">
              <div>
                <p className="eyebrow">DAG</p>
                <h2>Agent 编排</h2>
              </div>
              <span className="event-line">{eventMessage}</span>
            </div>
            <WorkflowGraph run={run} onSelectAgent={setSelectedAgent} />
          </section>

          <section className="panel artifact-panel">
            <div className="section-title">
              <div>
                <p className="eyebrow">Artifacts</p>
                <h2>报告与结构化产物</h2>
              </div>
              <select
                value={selectedArtifact?.id ?? ""}
                onChange={(event) => {
                  setSelectedArtifactId(event.target.value);
                  setArtifactPinned(true);
                }}
              >
                {(run?.artifacts ?? []).map((artifact) => (
                  <option key={artifact.id} value={artifact.id}>
                    {artifact.title || ARTIFACT_LABELS[artifact.type]} · v{artifact.version || 1}
                  </option>
                ))}
              </select>
            </div>
            <ArtifactViewer artifact={selectedArtifact} onSelectCitation={setSelectedCitationKey} />
          </section>
        </section>

        <aside className="right-rail">
          <section className="panel">
            <div className="section-title">
              <div>
                <p className="eyebrow">Timeline</p>
                <h2>执行回放</h2>
              </div>
            </div>
            <AgentTimeline run={run} selectedAgent={selectedAgent} onSelectAgent={setSelectedAgent} />
            <div className="rerun-grid">
              {AGENTS.map((agent) => (
                <button key={agent} type="button" onClick={() => handleRerun(agent)} disabled={!run}>
                  <RotateCcw size={14} /> {AGENT_LABELS[agent]}
                </button>
              ))}
            </div>
          </section>

          <EvidencePanel
            sources={run?.evidenceSources ?? []}
            selectedCitationKey={selectedCitationKey}
            onSelectCitation={setSelectedCitationKey}
          />

          <ReviewPanel
            findings={run?.reviewFindings ?? []}
            decision={run?.reviewDecision}
            onRerunTarget={handleRerun}
            disabled={!run}
          />

          <SchemaPanel
            researchPackage={run?.researchPackage}
            profiles={run?.competitorProfiles ?? []}
            claims={run?.claims ?? []}
            transitions={run?.workflowTransitions ?? []}
          />

          <section className="panel">
            <div className="section-title">
              <div>
                <p className="eyebrow">Mermaid</p>
                <h2>后端图定义</h2>
              </div>
              <GitBranch size={18} />
            </div>
            <pre className="mermaid-source">{workflowMermaid}</pre>
          </section>
        </aside>
      </main>

      <TraceDrawer
        agent={selectedAgent}
        steps={(run?.steps ?? []).filter((step) => step.agentName === selectedAgent)}
        traces={(run?.traces ?? []).filter((trace) => trace.agentName === selectedAgent)}
        onClose={() => setSelectedAgent(null)}
      />

      {run?.errorMessage ? (
        <div className="toast danger">
          <AlertTriangle size={16} /> {run.errorMessage}
        </div>
      ) : null}
    </div>
  );
}

function statusTone(status?: string) {
  if (status === "SUCCEEDED") return "success";
  if (status === "FAILED") return "danger";
  if (status === "RUNNING" || status === "PENDING") return "running";
  return "neutral";
}

function safeParseEvent(event: MessageEvent<string>): RunEvent | null {
  try {
    return JSON.parse(event.data) as RunEvent;
  } catch {
    return null;
  }
}
