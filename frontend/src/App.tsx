import { useCallback, useEffect, useMemo, useState } from "react";
import {
  Activity,
  AlertTriangle,
  BookOpenCheck,
  GitBranch,
  Play,
  RefreshCw,
  RotateCcw,
  Search,
  ShieldCheck,
  Sparkles
} from "lucide-react";
import type { AnalysisContextMessage, AgentName, AnalysisArtifact, AnalysisRun, ContextIntent, RunEvent } from "./types";
import { addContext, createRun, getRun, getWorkflowMermaid, listRuns, rerunAgent, startAnalysis, updateRequirement } from "./api";
import { AGENTS, AGENT_LABELS, ARTIFACT_LABELS, SOURCE_OPTIONS } from "./constants";
import { countCitedClaims, displayRunPhase, findDefaultArtifact, isActiveRun, resolveRunPhase, splitList } from "./utils";
import { StatusBadge } from "./components/StatusBadge";
import { WorkflowGraph } from "./components/WorkflowGraph";
import { AgentTimeline } from "./components/AgentTimeline";
import { ArtifactViewer } from "./components/ArtifactViewer";
import { EvidencePanel } from "./components/EvidencePanel";
import { ReviewPanel } from "./components/ReviewPanel";
import { TraceDrawer } from "./components/TraceDrawer";
import { SchemaPanel } from "./components/SchemaPanel";
import { ScopeConfirmationPanel } from "./components/ScopeConfirmationPanel";
import { ContextPanel } from "./components/ContextPanel";
import { ArtifactVersionsPanel } from "./components/ArtifactVersionsPanel";

type MainView = "dag" | "report" | "schema" | "matrix" | "versions";

export function App() {
  const [run, setRun] = useState<AnalysisRun | null>(null);
  const [prompt, setPrompt] = useState("");
  const [industry, setIndustry] = useState("");
  const [competitors, setCompetitors] = useState("");
  const [dimensions, setDimensions] = useState("");
  const [outputGoal, setOutputGoal] = useState("");
  const [sources, setSources] = useState<string[]>(SOURCE_OPTIONS.map((source) => source.value));
  const [selectedArtifactId, setSelectedArtifactId] = useState<string>();
  const [artifactPinned, setArtifactPinned] = useState(false);
  const [selectedCitationKey, setSelectedCitationKey] = useState<string>();
  const [selectedAgent, setSelectedAgent] = useState<AgentName | null>(null);
  const [contextText, setContextText] = useState("");
  const [contextIntent, setContextIntent] = useState<ContextIntent>("ADJUST_SCOPE");
  const [contextTargetAgent, setContextTargetAgent] = useState<AgentName>();
  const [localContextMessages, setLocalContextMessages] = useState<AnalysisContextMessage[]>([]);
  const [mainView, setMainView] = useState<MainView>("dag");
  const [localScopeConfirmed, setLocalScopeConfirmed] = useState(false);
  const [eventMessage, setEventMessage] = useState("等待创建任务");
  const [backendOk, setBackendOk] = useState(false);
  const [workflowMermaid, setWorkflowMermaid] = useState("");
  const [isCreating, setIsCreating] = useState(false);
  const [isScopeBusy, setIsScopeBusy] = useState(false);

  const refreshRun = useCallback(async (runId?: string) => {
    const id = runId ?? run?.id;
    if (!id) {
      // 初始进入工作台时只探测后端连通性，不自动选中历史任务，避免旧报告被误认为内置示例数据。
      await listRuns();
      setBackendOk(true);
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
    // SSE 用于驱动演示中的实时回放；失败时保留轮询兜底，避免浏览器或代理不支持事件流时页面停住。
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
    // 用户未手动选择时始终跟随后端最新推荐产物；手动选择后保持 pinned，方便对比报告版本。
    if (!artifactPinned) return findDefaultArtifact(artifacts);
    return artifacts.find((artifact) => artifact.id === selectedArtifactId) ?? findDefaultArtifact(artifacts);
  }, [artifactPinned, run?.artifacts, selectedArtifactId]);

  const reportDisplayArtifact = useMemo(() => {
    return selectedArtifact
      ?? [...(run?.artifacts ?? [])].reverse().find((artifact) => artifact.type === "FINAL_REPORT")
      ?? undefined;
  }, [run?.artifacts, selectedArtifact]);

  const matrixArtifact = useMemo(() => {
    return [...(run?.artifacts ?? [])].reverse().find((artifact) => artifact.type === "COMPETITIVE_MATRIX");
  }, [run?.artifacts]);

  useEffect(() => {
    if (selectedArtifact && selectedArtifact.id !== selectedArtifactId) {
      setSelectedArtifactId(selectedArtifact.id);
    }
  }, [selectedArtifact, selectedArtifactId]);

  useEffect(() => {
    if (!run?.id) return;
    const scope = run.clarificationDraft ?? run.requirement;
    if (!scope) return;
    setIndustry(scope.industry ?? "");
    setCompetitors((scope.competitors ?? []).join(", "));
    setDimensions((scope.dimensions ?? []).join(", "));
    setOutputGoal(scope.outputGoal ?? "");
    if (scope.sourcePreferences?.length) {
      setSources(scope.sourcePreferences);
    }
    setLocalScopeConfirmed(Boolean(run.clarificationDraft?.confirmed));
  }, [run?.id]);

  async function handleCreateRun() {
    setIsCreating(true);
    setEventMessage("正在创建分析任务草稿");
    setArtifactPinned(false);
    setSelectedCitationKey(undefined);
    setLocalContextMessages([]);
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

  async function handleConfirmRequirement() {
    if (!run) return;
    setIsScopeBusy(true);
    setEventMessage("正在确认分析范围");
    try {
      const nextRun = await updateRequirement(run.id, {
        industry,
        competitors: splitList(competitors),
        dimensions: splitList(dimensions),
        sourcePreferences: sources,
        outputGoal
      });
      setRun(nextRun);
      setEventMessage("分析范围已确认");
    } catch (error) {
      setLocalScopeConfirmed(true);
      setEventMessage(error instanceof Error ? `后端暂未支持范围确认：${error.message}` : "后端暂未支持范围确认");
    } finally {
      setIsScopeBusy(false);
    }
  }

  async function handleStartAnalysis() {
    if (!run) return;
    setIsScopeBusy(true);
    setEventMessage("正在启动 Agent 分析");
    try {
      const nextRun = await startAnalysis(run.id);
      setRun(nextRun);
      setMainView("dag");
    } catch (error) {
      const phase = resolveRunPhase(run);
      if (run.status === "RUNNING" || run.status === "SUCCEEDED") {
        setEventMessage(`当前后端仍使用创建即执行模式，任务已处于 ${run.status}`);
      } else {
        setEventMessage(error instanceof Error ? `后端暂未支持 start 接口：${error.message}` : "后端暂未支持 start 接口");
      }
      if (phase === "AWAITING_CONFIRMATION") {
        refreshRun(run.id).catch(() => undefined);
      }
    } finally {
      setIsScopeBusy(false);
    }
  }

  async function handleSubmitContext() {
    if (!run || !contextText.trim()) return;
    // 先乐观展示用户补充，后端写入成功后再以服务端状态为准，保证弱网下交互不显得断档。
    const optimisticMessage: AnalysisContextMessage = {
      id: `local-${Date.now()}`,
      role: "USER",
      intent: contextIntent,
      content: contextText.trim(),
      targetAgent: contextTargetAgent,
      createdAt: new Date().toISOString()
    };
    setLocalContextMessages((messages) => [optimisticMessage, ...messages]);
    setContextText("");
    setEventMessage("正在提交上下文补充");
    try {
      const nextRun = await addContext(run.id, {
        content: optimisticMessage.content,
        intent: contextIntent,
        targetAgent: contextTargetAgent
      });
      setRun(nextRun);
      setLocalContextMessages([]);
      setEventMessage("上下文已写入任务");
    } catch (error) {
      setEventMessage(error instanceof Error ? `后端暂未支持上下文接口，已在前端暂存：${error.message}` : "上下文已在前端暂存");
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

  const mainTabs: Array<{ key: MainView; label: string }> = [
    { key: "dag", label: "Agent DAG" },
    { key: "report", label: "最终报告" },
    { key: "schema", label: "结构化 Schema" },
    { key: "matrix", label: "竞品矩阵" },
    { key: "versions", label: "报告版本" }
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
          <StatusBadge label={displayRunPhase(String(resolveRunPhase(run)))} tone={statusTone(run?.status)} />
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
                <p className="eyebrow">任务</p>
                <h2>分析任务</h2>
              </div>
            </div>
            <label>
              分析需求
              <textarea
                value={prompt}
                onChange={(event) => setPrompt(event.target.value)}
                placeholder="请输入要分析的产品、竞品范围、关注维度或业务问题"
                rows={5}
              />
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
              <Play size={16} /> 创建任务草稿
            </button>
          </section>

          <ScopeConfirmationPanel
            run={run}
            localConfirmed={localScopeConfirmed}
            industry={industry}
            competitors={competitors}
            dimensions={dimensions}
            outputGoal={outputGoal}
            onIndustryChange={setIndustry}
            onCompetitorsChange={setCompetitors}
            onDimensionsChange={setDimensions}
            onOutputGoalChange={setOutputGoal}
            onConfirm={handleConfirmRequirement}
            onStart={handleStartAnalysis}
            busy={isScopeBusy}
          />

          <ContextPanel
            messages={[...localContextMessages, ...(run?.contextMessages ?? [])]}
            value={contextText}
            intent={contextIntent}
            targetAgent={contextTargetAgent}
            disabled={!run}
            onValueChange={setContextText}
            onIntentChange={setContextIntent}
            onTargetAgentChange={setContextTargetAgent}
            onSubmit={handleSubmitContext}
          />

          <section className="panel">
            <div className="section-title">
              <div>
                <p className="eyebrow">指标</p>
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
          <section className="panel main-stage-panel">
            <div className="section-title">
              <div>
                <p className="eyebrow">工作区</p>
                <h2>主工作区</h2>
              </div>
              <span className="event-line">{eventMessage}</span>
            </div>
            <div className="main-tabs">
              {mainTabs.map((tab) => (
                <button
                  key={tab.key}
                  type="button"
                  className={mainView === tab.key ? "selected" : ""}
                  onClick={() => setMainView(tab.key)}
                >
                  {tab.label}
                </button>
              ))}
            </div>

            {mainView === "dag" ? (
              <WorkflowGraph run={run} onSelectAgent={setSelectedAgent} />
            ) : null}

            {mainView === "report" ? (
              <div className="tab-content">
                <div className="artifact-toolbar">
                  <span>报告产物</span>
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
                <ArtifactViewer artifact={reportDisplayArtifact} onSelectCitation={setSelectedCitationKey} />
              </div>
            ) : null}

            {mainView === "schema" ? (
              <div className="tab-content">
                <SchemaPanel
                  embedded
                  researchPackage={run?.researchPackage}
                  profiles={run?.competitorProfiles ?? []}
                  claims={run?.claims ?? []}
                  transitions={run?.workflowTransitions ?? []}
                />
              </div>
            ) : null}

            {mainView === "matrix" ? (
              <div className="tab-content">
                <ArtifactViewer artifact={matrixArtifact} onSelectCitation={setSelectedCitationKey} />
              </div>
            ) : null}

            {mainView === "versions" ? (
              <ArtifactVersionsPanel
                artifacts={run?.artifacts ?? []}
                selectedArtifactId={selectedArtifact?.id}
                onSelectArtifact={(artifactId) => {
                  setSelectedArtifactId(artifactId);
                  setArtifactPinned(true);
                  setMainView("report");
                }}
              />
            ) : null}
          </section>
        </section>

        <aside className="right-rail">
          <section className="panel">
            <div className="section-title">
              <div>
                <p className="eyebrow">时间线</p>
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
