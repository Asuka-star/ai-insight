import { Suspense, lazy, type CSSProperties, type PointerEvent as ReactPointerEvent, useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  Activity,
  AlertTriangle,
  BookOpenCheck,
  Clock3,
  Gauge,
  GripVertical,
  History,
  Plus,
  RefreshCw,
  RotateCcw,
  Search,
  ShieldCheck,
  Sparkles,
  UploadCloud
} from "lucide-react";
import type { AnalysisContextMessage, AgentName, AnalysisArtifact, AnalysisRun, AnalysisRunMetrics, AnalysisRunSummary, ContextIntent, ReviewFinding, RunEvent } from "./types";
import { addContext, addEvidence, clarifyRequirement, createRun, getRun, getRunMetrics, listRunSummaries, rerunAgent, startAnalysis, updateRequirement } from "./api";
import { AGENTS, AGENT_LABELS, ARTIFACT_LABELS, SOURCE_OPTIONS } from "./constants";
import {
  calculateRunMetrics,
  countCitedClaims,
  displayRunPhase,
  findDefaultArtifact,
  formatDuration,
  formatPercent,
  isActiveRun,
  resolveRunPhase,
  splitList
} from "./utils";
import { StatusBadge } from "./components/StatusBadge";
import { AgentTimeline } from "./components/AgentTimeline";
import { EvidencePanel } from "./components/EvidencePanel";
import { ReviewPanel } from "./components/ReviewPanel";
import { CollapsiblePanel } from "./components/CollapsiblePanel";
import { TraceDrawer } from "./components/TraceDrawer";
import { ScopeConfirmationPanel } from "./components/ScopeConfirmationPanel";
import { ContextPanel } from "./components/ContextPanel";
import { ArtifactVersionsPanel } from "./components/ArtifactVersionsPanel";
import { HistoryDrawer } from "./components/HistoryDrawer";

type MainView = "dag" | "report" | "schema" | "matrix" | "versions";
type RightPanelId = "timeline" | "evidence" | "review" | "metrics";

const MIN_LEFT_RAIL_WIDTH = 240;
const MAX_LEFT_RAIL_WIDTH = 420;
const MIN_CENTER_WIDTH = 420;
const MIN_RIGHT_RAIL_WIDTH = 280;
const MAX_RIGHT_RAIL_WIDTH = 520;
const RESIZE_LAYOUT_RESERVE = 72;
const CURRENT_RUN_STORAGE_KEY = "ai-insight.currentRunId";
const WorkflowGraph = lazy(() => import("./components/WorkflowGraph").then((module) => ({ default: module.WorkflowGraph })));
const ArtifactViewer = lazy(() => import("./components/ArtifactViewer").then((module) => ({ default: module.ArtifactViewer })));
const SchemaPanel = lazy(() => import("./components/SchemaPanel").then((module) => ({ default: module.SchemaPanel })));

export function App() {
  const [run, setRun] = useState<AnalysisRun | null>(null);
  const [serverRunMetrics, setServerRunMetrics] = useState<AnalysisRunMetrics | null>(null);
  const [historyRuns, setHistoryRuns] = useState<AnalysisRunSummary[]>([]);
  const [industry, setIndustry] = useState("");
  const [competitors, setCompetitors] = useState("");
  const [dimensions, setDimensions] = useState("");
  const [outputGoal, setOutputGoal] = useState("");
  const [sources, setSources] = useState<string[]>(SOURCE_OPTIONS.map((source) => source.value));
  const [sourceUrls, setSourceUrls] = useState("");
  const [selectedArtifactId, setSelectedArtifactId] = useState<string>();
  const [artifactPinned, setArtifactPinned] = useState(false);
  const [selectedCitationKey, setSelectedCitationKey] = useState<string>();
  const [selectedClaimId, setSelectedClaimId] = useState<string>();
  const [selectedAgent, setSelectedAgent] = useState<AgentName | null>(null);
  const [contextText, setContextText] = useState("");
  const [contextIntent, setContextIntent] = useState<ContextIntent>("ADJUST_SCOPE");
  const [contextTargetAgent, setContextTargetAgent] = useState<AgentName>();
  const [evidenceTitle, setEvidenceTitle] = useState("");
  const [evidenceSourceType, setEvidenceSourceType] = useState("note");
  const [evidenceUrl, setEvidenceUrl] = useState("");
  const [evidenceContent, setEvidenceContent] = useState("");
  const [evidenceSensitive, setEvidenceSensitive] = useState(false);
  const [localContextMessages, setLocalContextMessages] = useState<AnalysisContextMessage[]>([]);
  const [mainView, setMainView] = useState<MainView>("dag");
  const [collapsedRightPanels, setCollapsedRightPanels] = useState<Record<RightPanelId, boolean>>({
    timeline: false,
    evidence: false,
    review: false,
    metrics: true
  });
  const [localScopeConfirmed, setLocalScopeConfirmed] = useState(false);
  const [eventMessage, setEventMessage] = useState("等待填写范围确认");
  const [backendOk, setBackendOk] = useState(false);
  const [isCreating, setIsCreating] = useState(false);
  const [isScopeBusy, setIsScopeBusy] = useState(false);
  const [isHistoryLoading, setIsHistoryLoading] = useState(false);
  const [historyOpen, setHistoryOpen] = useState(false);
  const [leftRailWidth, setLeftRailWidth] = useState(286);
  const [rightRailWidth, setRightRailWidth] = useState(342);
  const refreshTimerRef = useRef<number>();
  const workspaceRequestTokenRef = useRef(0);
  // Requirement may change without changing run.id, for example after ADJUST_SCOPE context.
  // Keep a narrow sync key so the editable scope form follows backend scope updates.
  const scopeSyncKey = useMemo(() => {
    if (!run?.id) return "";
    const scope = run.clarificationDraft ?? run.requirement;
    if (!scope) return run.id;
    return JSON.stringify({
      id: run.id,
      industry: scope.industry ?? "",
      competitors: scope.competitors ?? [],
      dimensions: scope.dimensions ?? [],
      outputGoal: scope.outputGoal ?? "",
      sourcePreferences: scope.sourcePreferences ?? [],
      sourceUrls: scope.sourceUrls ?? [],
      confirmed: Boolean(run.clarificationDraft?.confirmed)
    });
  }, [run]);

  const loadHistory = useCallback(async () => {
    const runs = await listRunSummaries();
    setHistoryRuns(runs);
    setBackendOk(true);
    return runs;
  }, []);

  const selectRun = useCallback(async (runId: string) => {
    const requestToken = ++workspaceRequestTokenRef.current;
    setIsHistoryLoading(true);
    setServerRunMetrics(null);
    setEventMessage("正在恢复历史会话");
    try {
      const latest = await getRun(runId);
      if (requestToken !== workspaceRequestTokenRef.current) return;
      window.localStorage.setItem(CURRENT_RUN_STORAGE_KEY, runId);
      setBackendOk(true);
      setRun(latest);
      setArtifactPinned(false);
      setSelectedArtifactId(undefined);
      setSelectedCitationKey(undefined);
      setSelectedClaimId(undefined);
      setSelectedAgent(null);
      setMainView("dag");
      setLocalContextMessages([]);
      setEventMessage("历史会话已恢复");
      setHistoryOpen(false);
      setHistoryRuns((runs) => upsertHistorySummary(runs, summaryFromRun(latest)));
    } catch (error) {
      if (requestToken !== workspaceRequestTokenRef.current) return;
      window.localStorage.removeItem(CURRENT_RUN_STORAGE_KEY);
      setBackendOk(false);
      setEventMessage(error instanceof Error ? `历史会话恢复失败：${error.message}` : "历史会话恢复失败");
    } finally {
      if (requestToken === workspaceRequestTokenRef.current) {
        setIsHistoryLoading(false);
      }
    }
  }, []);

  const refreshRun = useCallback(async (runId?: string) => {
    const id = runId ?? run?.id;
    if (!id) {
      await loadHistory();
      return;
    }
    const latest = await getRun(id);
    if (window.localStorage.getItem(CURRENT_RUN_STORAGE_KEY) !== id) return;
    window.localStorage.setItem(CURRENT_RUN_STORAGE_KEY, id);
    setBackendOk(true);
    setRun(latest);
    setHistoryRuns((runs) => upsertHistorySummary(runs, summaryFromRun(latest)));
  }, [loadHistory, run?.id]);

  useEffect(() => {
    if (!run?.id) {
      setServerRunMetrics(null);
      return;
    }
    let cancelled = false;
    getRunMetrics(run.id)
      .then((metrics) => {
        if (!cancelled && metrics.runId === run.id) {
          setServerRunMetrics(metrics);
        }
      })
      .catch(() => {
        if (!cancelled) {
          setServerRunMetrics(null);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [
    run?.id,
    run?.updatedAt,
    run?.steps.length,
    run?.evidenceSources.length,
    run?.reviewFindings.length,
    run?.artifacts.length,
    run?.claims?.length,
    run?.competitorProfiles?.length,
    run?.traces?.length,
    run?.workflowTransitions?.length
  ]);

  const requestRunRefresh = useCallback((runId: string) => {
    if (refreshTimerRef.current) return;
    refreshTimerRef.current = window.setTimeout(() => {
      refreshTimerRef.current = undefined;
      refreshRun(runId).catch((error) => setEventMessage(error.message));
    }, 500);
  }, [refreshRun]);

  useEffect(() => {
    let cancelled = false;
    async function restoreWorkspace() {
      try {
        const runs = await loadHistory();
        if (cancelled) return;
        const storedRunId = window.localStorage.getItem(CURRENT_RUN_STORAGE_KEY);
        if (!storedRunId) {
          setEventMessage(runs.length ? "请选择历史会话或创建新分析" : "等待填写范围确认");
          return;
        }
        const exists = runs.some((item) => item.id === storedRunId);
        if (!exists) {
          window.localStorage.removeItem(CURRENT_RUN_STORAGE_KEY);
          setEventMessage("上次会话不存在，请从历史列表重新选择");
          return;
        }
        await selectRun(storedRunId);
      } catch (error) {
        if (cancelled) return;
        setBackendOk(false);
        setEventMessage(error instanceof Error ? error.message : "后端连接失败");
      }
    }
    restoreWorkspace();
    return () => {
      cancelled = true;
    };
  }, [loadHistory, selectRun]);

  useEffect(() => {
    if (!run?.id) return;
    // SSE 用于驱动演示中的实时回放；失败时保留轮询兜底，避免浏览器或代理不支持事件流时页面停住。
    const events = new EventSource(`/api/analysis-runs/${run.id}/events`);
    const eventTypes = [
      "subscribed",
      "run_created",
      "clarification_ready",
      "requirement_confirmed",
      "run_start_requested",
      "run_started",
      "context_added",
      "evidence_added",
      "agent_started",
      "agent_succeeded",
      "agent_failed",
      "agent_rerun_completed",
      "review_rework_started",
      "review_rework_completed",
      "run_cancelled",
      "run_succeeded",
      "run_failed"
    ];
    eventTypes.forEach((type) => {
      events.addEventListener(type, (event) => {
        const data = safeParseEvent(event);
        setEventMessage(data?.message || type);
        requestRunRefresh(run.id);
      });
    });
    events.onerror = () => setEventMessage("SSE 暂不可用，使用轮询刷新");
    return () => events.close();
  }, [run?.id, requestRunRefresh]);

  useEffect(() => {
    if (!run || !isActiveRun(run)) return;
    const activeRunId = run.id;
    const timer = window.setInterval(() => {
      refreshRun(activeRunId).catch((error) => setEventMessage(error.message));
    }, 2500);
    return () => window.clearInterval(timer);
  }, [run, refreshRun]);

  useEffect(() => {
    return () => {
      if (refreshTimerRef.current) {
        window.clearTimeout(refreshTimerRef.current);
      }
    };
  }, []);

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
    // The left-side scope form is editable, but backend clarification/context updates are authoritative.
    // Sync only when the semantic scope changes to avoid resetting unrelated UI state on every poll.
    if (!run?.id) return;
    const scope = run.clarificationDraft ?? run.requirement;
    if (!scope) return;
    setIndustry(scope.industry ?? "");
    setCompetitors((scope.competitors ?? []).join(", "));
    setDimensions((scope.dimensions ?? []).join(", "));
    setOutputGoal(scope.outputGoal ?? "");
    setSourceUrls((scope.sourceUrls ?? []).join("\n"));
    if (scope.sourcePreferences?.length) {
      setSources(scope.sourcePreferences);
    }
    setLocalScopeConfirmed(Boolean(run.clarificationDraft?.confirmed));
  }, [scopeSyncKey]);

  const workspaceStyle = useMemo(() => ({
    "--left-rail-width": `${leftRailWidth}px`,
    "--right-rail-width": `${rightRailWidth}px`
  }) as CSSProperties, [leftRailWidth, rightRailWidth]);

  const toggleRightPanel = useCallback((panel: RightPanelId) => {
    setCollapsedRightPanels((current) => ({
      ...current,
      [panel]: !current[panel]
    }));
  }, []);

  const startRailResize = useCallback((
    side: "left" | "right",
    event: ReactPointerEvent<HTMLButtonElement>
  ) => {
    event.preventDefault();
    const workspace = event.currentTarget.closest(".workspace") as HTMLElement | null;
    const workspaceWidth = workspace?.clientWidth ?? window.innerWidth;
    const startX = event.clientX;
    const startLeftWidth = leftRailWidth;
    const startRightWidth = rightRailWidth;
    document.body.classList.add("is-resizing-rail");

    const handlePointerMove = (moveEvent: PointerEvent) => {
      const deltaX = moveEvent.clientX - startX;
      if (side === "left") {
        const maxWidth = Math.min(
          MAX_LEFT_RAIL_WIDTH,
          workspaceWidth - startRightWidth - MIN_CENTER_WIDTH - RESIZE_LAYOUT_RESERVE
        );
        setLeftRailWidth(clamp(startLeftWidth + deltaX, MIN_LEFT_RAIL_WIDTH, Math.max(MIN_LEFT_RAIL_WIDTH, maxWidth)));
        return;
      }

      const maxWidth = Math.min(
        MAX_RIGHT_RAIL_WIDTH,
        workspaceWidth - startLeftWidth - MIN_CENTER_WIDTH - RESIZE_LAYOUT_RESERVE
      );
      setRightRailWidth(clamp(startRightWidth - deltaX, MIN_RIGHT_RAIL_WIDTH, Math.max(MIN_RIGHT_RAIL_WIDTH, maxWidth)));
    };

    const stopResize = () => {
      document.body.classList.remove("is-resizing-rail");
      window.removeEventListener("pointermove", handlePointerMove);
      window.removeEventListener("pointerup", stopResize);
      window.removeEventListener("pointercancel", stopResize);
    };

    window.addEventListener("pointermove", handlePointerMove);
    window.addEventListener("pointerup", stopResize);
    window.addEventListener("pointercancel", stopResize);
  }, [leftRailWidth, rightRailWidth]);

  function handleNewRun() {
    workspaceRequestTokenRef.current += 1;
    window.localStorage.removeItem(CURRENT_RUN_STORAGE_KEY);
    setRun(null);
    setServerRunMetrics(null);
    setIndustry("");
    setCompetitors("");
    setDimensions("");
    setOutputGoal("");
    setSources(SOURCE_OPTIONS.map((source) => source.value));
    setSourceUrls("");
    setSelectedArtifactId(undefined);
    setArtifactPinned(false);
    setSelectedCitationKey(undefined);
    setSelectedClaimId(undefined);
    setSelectedAgent(null);
    setContextText("");
    setContextIntent("ADJUST_SCOPE");
    setContextTargetAgent(undefined);
    setEvidenceTitle("");
    setEvidenceUrl("");
    setEvidenceContent("");
    setEvidenceSensitive(false);
    setLocalContextMessages([]);
    setLocalScopeConfirmed(false);
    setIsCreating(false);
    setIsHistoryLoading(false);
    setMainView("dag");
    setHistoryOpen(false);
    setEventMessage("已切换到新建分析");
  }

  async function handleCreateRun() {
    const requestToken = ++workspaceRequestTokenRef.current;
    setIsCreating(true);
    setEventMessage("正在生成范围确认内容");
    setArtifactPinned(false);
    setSelectedCitationKey(undefined);
    setLocalContextMessages([]);
    try {
      const competitorList = splitList(competitors);
      const dimensionList = splitList(dimensions);
      const sourceUrlList = splitLines(sourceUrls);
      const nextRun = await createRun({
        prompt: buildScopePrompt(industry, outputGoal, competitorList, dimensionList, sourceUrlList),
        industry,
        competitors: competitorList,
        dimensions: dimensionList,
        sourcePreferences: sources,
        sourceUrls: sourceUrlList,
        outputGoal
      });
      if (requestToken !== workspaceRequestTokenRef.current) return;
      setBackendOk(true);
      setRun(nextRun);
      window.localStorage.setItem(CURRENT_RUN_STORAGE_KEY, nextRun.id);
      setHistoryRuns((runs) => upsertHistorySummary(runs, summaryFromRun(nextRun)));
    } catch (error) {
      if (requestToken !== workspaceRequestTokenRef.current) return;
      setBackendOk(false);
      setEventMessage(error instanceof Error ? error.message : "范围确认生成失败");
    } finally {
      if (requestToken === workspaceRequestTokenRef.current) {
        setIsCreating(false);
      }
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
        sourceUrls: splitLines(sourceUrls),
        outputGoal
      });
      setRun(nextRun);
      setEventMessage("分析范围已确认");
    } catch (error) {
      setEventMessage(error instanceof Error ? `范围确认失败：${error.message}` : "范围确认失败");
    } finally {
      setIsScopeBusy(false);
    }
  }

  function handleApplyClarificationOption(field: string, values: string[]) {
    const normalizedValues = values.filter((value) => value.trim());
    if (field === "industry") {
      setIndustry(normalizedValues[0] ?? "");
    } else if (field === "competitors") {
      setCompetitors(normalizedValues.join("、"));
    } else if (field === "dimensions") {
      setDimensions(normalizedValues.join("、"));
    } else if (field === "sourcePreferences") {
      setSources(normalizedValues);
    } else if (field === "sourceUrls") {
      setSourceUrls(normalizedValues.join("\n"));
    } else if (field === "outputGoal") {
      setOutputGoal(normalizedValues[0] ?? "");
    }
    setLocalScopeConfirmed(false);
    setEventMessage("已应用澄清选项，请确认范围");
  }

  async function handleReclarifyScope() {
    if (!run) return;
    setIsScopeBusy(true);
    setEventMessage("正在重新澄清范围");
    try {
      const nextRun = await clarifyRequirement(run.id, {
        industry,
        competitors: splitList(competitors),
        dimensions: splitList(dimensions),
        sourcePreferences: sources,
        sourceUrls: splitLines(sourceUrls),
        outputGoal
      });
      setRun(nextRun);
      setLocalScopeConfirmed(Boolean(nextRun.clarificationDraft?.confirmed));
      setEventMessage("范围已重新澄清");
    } catch (error) {
      setEventMessage(error instanceof Error ? `重新澄清失败：${error.message}` : "重新澄清失败");
    } finally {
      setIsScopeBusy(false);
    }
  }

  async function handleStartAnalysis() {
    if (!run) return;
    setIsScopeBusy(true);
    setEventMessage("正在保存范围并启动 Agent 分析");
    try {
      await updateRequirement(run.id, {
        industry,
        competitors: splitList(competitors),
        dimensions: splitList(dimensions),
        sourcePreferences: sources,
        sourceUrls: splitLines(sourceUrls),
        outputGoal
      });
      const nextRun = await startAnalysis(run.id);
      setRun(nextRun);
      setMainView("dag");
    } catch (error) {
      const phase = resolveRunPhase(run);
      setEventMessage(error instanceof Error ? `启动 Agent 分析失败：${error.message}` : "启动 Agent 分析失败");
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
      setEventMessage(error instanceof Error ? `上下文提交失败，已在前端暂存：${error.message}` : "上下文提交失败，已在前端暂存");
    }
  }

  async function handleAddEvidence() {
    if (!run || !evidenceTitle.trim() || !evidenceContent.trim()) return;
    setEventMessage("正在加入用户资料");
    try {
      const nextRun = await addEvidence(run.id, {
        title: evidenceTitle.trim(),
        sourceType: evidenceSourceType,
        content: evidenceContent.trim(),
        url: evidenceUrl.trim() || undefined,
        sensitive: evidenceSensitive
      });
      setRun(nextRun);
      setEvidenceTitle("");
      setEvidenceUrl("");
      setEvidenceContent("");
      setEvidenceSensitive(false);
      setEventMessage("用户资料已加入证据链");
    } catch (error) {
      setEventMessage(error instanceof Error ? `资料加入失败：${error.message}` : "资料加入失败");
    }
  }

  async function handleRerun(agentName: AgentName) {
    if (!run || runMutationDisabled) return;
    setEventMessage(`正在重跑 ${AGENT_LABELS[agentName]}`);
    try {
      const nextRun = await rerunAgent(run.id, agentName);
      setRun(nextRun);
      setEventMessage(`${AGENT_LABELS[agentName]} 已重跑`);
    } catch (error) {
      setEventMessage(error instanceof Error ? `重跑失败：${error.message}` : "重跑失败");
    }
  }

  function handleLocateFinding(finding: ReviewFinding) {
    // Prefer the most structured target first: claim -> schema, otherwise fall back to the report artifact.
    // Citation selection is independent so EvidencePanel can still highlight the source.
    if (finding.citationKey) {
      setSelectedCitationKey(finding.citationKey);
    }
    if (finding.claimId) {
      setSelectedClaimId(finding.claimId);
      setMainView("schema");
      setEventMessage(`已定位到结构化结论 ${finding.claimId}`);
      return;
    }
    if (finding.artifactId) {
      setSelectedArtifactId(finding.artifactId);
      setArtifactPinned(true);
      setMainView("report");
      setEventMessage(`已定位到报告段落 ${finding.paragraphIndex ?? ""}`);
    }
  }

  const localRunMetrics = calculateRunMetrics(run);
  const authoritativeMetrics = serverRunMetrics?.runId === run?.id ? serverRunMetrics : null;
  const runMetrics = authoritativeMetrics ?? {
    runId: run?.id ?? "",
    agentStepCount: run?.steps.length ?? 0,
    evidenceCount: run?.evidenceSources.length ?? 0,
    reviewFindingCount: run?.reviewFindings.length ?? 0,
    citationMentionCount: countCitedClaims(run),
    claimCoverage: localRunMetrics.claimCoverage,
    schemaCompleteness: localRunMetrics.schemaCompleteness,
    reworkCount: localRunMetrics.reworkCount,
    evidencePerClaim: localRunMetrics.evidencePerClaim,
    totalTokens: localRunMetrics.totalTokens,
    totalLatencyMs: localRunMetrics.totalLatencyMs,
    highFindingCount: run?.reviewFindings.filter((finding) => finding.severity === "HIGH").length ?? 0,
    mediumFindingCount: run?.reviewFindings.filter((finding) => finding.severity === "MEDIUM").length ?? 0,
    lowFindingCount: run?.reviewFindings.filter((finding) => finding.severity === "LOW").length ?? 0
  };
  const phase = String(resolveRunPhase(run));
  const runMutationDisabled = !run || ["RUNNING", "REVIEWING", "REVISING", "CANCELLED"].includes(phase);
  const metricCards = [
    { label: "Agent 步骤", value: runMetrics.agentStepCount, icon: Activity },
    { label: "证据来源", value: runMetrics.evidenceCount, icon: Search },
    { label: "质检问题", value: runMetrics.reviewFindingCount, icon: ShieldCheck },
    { label: "引用标记", value: runMetrics.citationMentionCount, icon: BookOpenCheck },
    { label: "Claim 覆盖", value: formatPercent(runMetrics.claimCoverage), icon: ShieldCheck },
    { label: "Schema 完整", value: formatPercent(runMetrics.schemaCompleteness), icon: BookOpenCheck },
    { label: "打回次数", value: runMetrics.reworkCount, icon: RefreshCw },
    { label: "证据/Claim", value: runMetrics.evidencePerClaim, icon: Search },
    { label: "Token", value: runMetrics.totalTokens, icon: Gauge },
    { label: "耗时", value: formatDuration(runMetrics.totalLatencyMs), icon: Clock3 }
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
          <button className="toolbar-button" type="button" onClick={() => setHistoryOpen(true)}>
            <History size={16} /> 历史会话
          </button>
          <button className="toolbar-button" type="button" onClick={handleNewRun}>
            <Plus size={16} /> 新建分析
          </button>
          <StatusBadge label={backendOk ? "后端已连接" : "后端未连接"} tone={backendOk ? "success" : "danger"} />
          <StatusBadge label={displayRunPhase(String(resolveRunPhase(run)))} tone={statusTone(run?.status)} />
          <button className="icon-button" type="button" onClick={() => refreshRun()} aria-label="刷新任务">
            <RefreshCw size={17} />
          </button>
        </div>
      </header>

      <main className="workspace" style={workspaceStyle}>
        <aside className="left-rail">
          <ScopeConfirmationPanel
            run={run}
            localConfirmed={localScopeConfirmed}
            industry={industry}
            competitors={competitors}
            dimensions={dimensions}
            outputGoal={outputGoal}
            sources={sources}
            sourceUrls={sourceUrls}
            onIndustryChange={setIndustry}
            onCompetitorsChange={setCompetitors}
            onDimensionsChange={setDimensions}
            onOutputGoalChange={setOutputGoal}
            onSourcesChange={setSources}
            onSourceUrlsChange={setSourceUrls}
            onApplyClarificationOption={handleApplyClarificationOption}
            onCreate={handleCreateRun}
            onReclarify={handleReclarifyScope}
            onConfirm={handleConfirmRequirement}
            onStart={handleStartAnalysis}
            creating={isCreating}
            busy={isScopeBusy}
          />

          <ContextPanel
            messages={[...localContextMessages, ...(run?.contextMessages ?? [])]}
            value={contextText}
            intent={contextIntent}
            targetAgent={contextTargetAgent}
            disabled={runMutationDisabled}
            onValueChange={setContextText}
            onIntentChange={setContextIntent}
            onTargetAgentChange={setContextTargetAgent}
            onSubmit={handleSubmitContext}
          />

          <section className="panel evidence-input-panel">
            <div className="section-title">
              <div>
                <p className="eyebrow">资料</p>
                <h2>补充资料</h2>
              </div>
              <UploadCloud size={18} />
            </div>
            <label>
              标题
              <input value={evidenceTitle} onChange={(event) => setEvidenceTitle(event.target.value)} placeholder="例如：内部访谈摘要" />
            </label>
            <div className="evidence-input-grid">
              <label>
                类型
                <select value={evidenceSourceType} onChange={(event) => setEvidenceSourceType(event.target.value)}>
                  <option value="note">手动资料</option>
                  <option value="url">公开 URL</option>
                  <option value="interview">访谈</option>
                  <option value="survey">问卷</option>
                </select>
              </label>
              <label className="check-row evidence-sensitive">
                <input type="checkbox" checked={evidenceSensitive} onChange={(event) => setEvidenceSensitive(event.target.checked)} />
                内部敏感资料
              </label>
            </div>
            <label>
              URL
              <input value={evidenceUrl} onChange={(event) => setEvidenceUrl(event.target.value)} placeholder="可选，公开网页或资料链接" />
            </label>
            <label>
              内容
              <textarea
                value={evidenceContent}
                onChange={(event) => setEvidenceContent(event.target.value)}
                placeholder="粘贴访谈、问卷、网页摘要或手动资料内容"
                rows={4}
              />
            </label>
            <button className="primary-button" type="button" onClick={handleAddEvidence} disabled={runMutationDisabled || !evidenceTitle.trim() || !evidenceContent.trim()}>
              <UploadCloud size={15} /> 加入证据链
            </button>
          </section>

        </aside>

        <button
          className="rail-resize-handle"
          type="button"
          aria-label="调整左侧栏宽度"
          title="调整左侧栏宽度"
          onPointerDown={(event) => startRailResize("left", event)}
        >
          <GripVertical size={16} />
        </button>

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
              <Suspense fallback={<PanelLoading label="正在加载工作流视图" />}>
                <WorkflowGraph run={run} onSelectAgent={setSelectedAgent} />
              </Suspense>
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
                <Suspense fallback={<PanelLoading label="正在加载报告阅读器" />}>
                  <ArtifactViewer
                    artifact={reportDisplayArtifact}
                    sources={run?.evidenceSources ?? []}
                    onSelectCitation={setSelectedCitationKey}
                  />
                </Suspense>
              </div>
            ) : null}

            {mainView === "schema" ? (
              <div className="tab-content">
                <Suspense fallback={<PanelLoading label="正在加载结构化视图" />}>
                  <SchemaPanel
                    embedded
                    researchPackage={run?.researchPackage}
                    profiles={run?.competitorProfiles ?? []}
                    claims={run?.claims ?? []}
                    transitions={run?.workflowTransitions ?? []}
                    selectedClaimId={selectedClaimId}
                    onSelectCitation={setSelectedCitationKey}
                  />
                </Suspense>
              </div>
            ) : null}

            {mainView === "matrix" ? (
              <div className="tab-content">
                <Suspense fallback={<PanelLoading label="正在加载矩阵阅读器" />}>
                  <ArtifactViewer artifact={matrixArtifact} sources={run?.evidenceSources ?? []} onSelectCitation={setSelectedCitationKey} />
                </Suspense>
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

        <button
          className="rail-resize-handle"
          type="button"
          aria-label="调整右侧栏宽度"
          title="调整右侧栏宽度"
          onPointerDown={(event) => startRailResize("right", event)}
        >
          <GripVertical size={16} />
        </button>

        <aside className="right-rail">
          <CollapsiblePanel
            eyebrow="时间线"
            title="执行回放"
            icon={<Activity size={18} />}
            summary={`${run?.steps.length ?? 0} 个步骤`}
            collapsed={collapsedRightPanels.timeline}
            onToggle={() => toggleRightPanel("timeline")}
          >
            <AgentTimeline run={run} selectedAgent={selectedAgent} onSelectAgent={setSelectedAgent} />
            <div className="rerun-grid">
              {AGENTS.map((agent) => (
                <button key={agent} type="button" onClick={() => handleRerun(agent)} disabled={runMutationDisabled}>
                  <RotateCcw size={14} /> {AGENT_LABELS[agent]}
                </button>
              ))}
            </div>
          </CollapsiblePanel>

          <EvidencePanel
            sources={run?.evidenceSources ?? []}
            selectedCitationKey={selectedCitationKey}
            onSelectCitation={setSelectedCitationKey}
            collapsed={collapsedRightPanels.evidence}
            onToggle={() => toggleRightPanel("evidence")}
          />

          <ReviewPanel
            findings={run?.reviewFindings ?? []}
            decision={run?.reviewDecision}
            onRerunTarget={handleRerun}
            onLocateFinding={handleLocateFinding}
            disabled={runMutationDisabled}
            collapsed={collapsedRightPanels.review}
            onToggle={() => toggleRightPanel("review")}
          />

          <CollapsiblePanel
            eyebrow="指标"
            title="运行指标"
            icon={<Gauge size={18} />}
            summary={`${runMetrics.highFindingCount} HIGH · ${formatDuration(runMetrics.totalLatencyMs)}`}
            collapsed={collapsedRightPanels.metrics}
            onToggle={() => toggleRightPanel("metrics")}
          >
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
          </CollapsiblePanel>
        </aside>
      </main>

      <TraceDrawer
        agent={selectedAgent}
        traces={(run?.traces ?? []).filter((trace) => trace.agentName === selectedAgent)}
        onClose={() => setSelectedAgent(null)}
      />

      {run?.errorMessage ? (
        <div className="toast danger">
          <AlertTriangle size={16} /> {run.errorMessage}
        </div>
      ) : null}

      <HistoryDrawer
        open={historyOpen}
        summaries={historyRuns}
        currentRunId={run?.id}
        loading={isHistoryLoading}
        onClose={() => setHistoryOpen(false)}
        onNewRun={handleNewRun}
        onRefresh={() => loadHistory().catch((error) => setEventMessage(error.message))}
        onSelectRun={selectRun}
      />
    </div>
  );
}

function statusTone(status?: string) {
  if (status === "SUCCEEDED") return "success";
  if (status === "FAILED") return "danger";
  if (status === "RUNNING" || status === "PENDING") return "running";
  return "neutral";
}

function PanelLoading({ label }: { label: string }) {
  return (
    <div className="panel-loading">
      <RefreshCw size={16} />
      <span>{label}</span>
    </div>
  );
}

function upsertHistorySummary(runs: AnalysisRunSummary[], latest: AnalysisRunSummary) {
  const exists = runs.some((item) => item.id === latest.id);
  const nextRuns = exists
    ? runs.map((item) => item.id === latest.id ? latest : item)
    : [latest, ...runs];
  return [...nextRuns].sort((left, right) => timestampValue(right.updatedAt ?? right.createdAt) - timestampValue(left.updatedAt ?? left.createdAt));
}

function summaryFromRun(run: AnalysisRun): AnalysisRunSummary {
  const scope = run.clarificationDraft ?? run.requirement;
  return {
    id: run.id,
    status: run.status,
    industry: scope?.industry,
    competitors: scope?.competitors ?? [],
    outputGoal: scope?.outputGoal,
    originalPrompt: run.requirement?.originalPrompt,
    evidenceCount: run.evidenceSources.length,
    artifactCount: run.artifacts.length,
    findingCount: run.reviewFindings.length,
    stepCount: run.steps.length,
    createdAt: run.createdAt,
    updatedAt: run.updatedAt
  };
}

function timestampValue(value?: string) {
  if (!value) return 0;
  const time = new Date(value).getTime();
  return Number.isNaN(time) ? 0 : time;
}

function safeParseEvent(event: MessageEvent<string>): RunEvent | null {
  try {
    return JSON.parse(event.data) as RunEvent;
  } catch {
    return null;
  }
}

function splitLines(value: string) {
  return value
    .split(/[\r\n、]+/)
    .map((item) => item.trim())
    .filter(Boolean);
}

function buildScopePrompt(
  industry: string,
  outputGoal: string,
  competitors: string[],
  dimensions: string[],
  sourceUrls: string[]
) {
  return [
    industry.trim() ? `行业方向：${industry.trim()}` : "",
    outputGoal.trim() ? `报告用途：${outputGoal.trim()}` : "",
    competitors.length ? `竞品列表：${competitors.join("、")}` : "",
    dimensions.length ? `分析维度：${dimensions.join("、")}` : "",
    sourceUrls.length ? `公开来源：${sourceUrls.join("、")}` : ""
  ].filter(Boolean).join("\n") || "竞品分析";
}

function clamp(value: number, min: number, max: number) {
  return Math.min(Math.max(value, min), max);
}
