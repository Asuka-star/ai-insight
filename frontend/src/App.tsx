import { Suspense, lazy, type CSSProperties, type PointerEvent as ReactPointerEvent, useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  Activity,
  AlertTriangle,
  ArrowDownRight,
  ArrowUpRight,
  BookOpenCheck,
  Clock3,
  FolderOpen,
  Gauge,
  GripVertical,
  History,
  Plus,
  RefreshCw,
  Search,
  ShieldCheck,
  Sparkles,
  UploadCloud
} from "lucide-react";
import type { AgentStep, AgentName, AnalysisArtifact, ArtifactLocateRequest, AnalysisRun, AnalysisRunMetrics, AnalysisRunSummary, Questionnaire, ReviewFinding, RunEvent } from "./types";
import { addEvidence, clarifyRequirement, createRun, deleteDocument, deleteResearchInsight, deleteRun, downloadSurveyTemplate, getRun, getRunMetrics, importSurveyResults, listRunSummaries, rerunAgent, startAnalysis, updateRequirement, updateSurveyQuestionnaire, uploadDocument } from "./api";
import { AGENT_LABELS, ARTIFACT_LABELS } from "./constants";
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
import { ResearchDesignPanel } from "./components/ResearchDesignPanel";
import { CollapsiblePanel } from "./components/CollapsiblePanel";
import { TraceDrawer } from "./components/TraceDrawer";
import { ScopeConfirmationPanel } from "./components/ScopeConfirmationPanel";
import { ContextPanel } from "./components/ContextPanel";
import { HistoryDrawer } from "./components/HistoryDrawer";
import { DeleteHistoryDialog } from "./components/DeleteHistoryDialog";
import { ResourcePackDrawer } from "./components/ResourcePackDrawer";
import { SegmentedTabs, type SegmentedTabOption } from "./components/SegmentedTabs";

type MainView = "dag" | "research" | "report" | "schema";
type LeftPanelId = "scope" | "context" | "evidence";
type RightPanelId = "timeline" | "evidence" | "review" | "metrics";
type BackendStatus = "checking" | "connected" | "failed";

const MIN_LEFT_RAIL_WIDTH = 240;
const MAX_LEFT_RAIL_WIDTH = 420;
const MIN_CENTER_WIDTH = 420;
const STACKED_RIGHT_RAIL_BREAKPOINT = 1280;
const STACKED_MIN_CENTER_WIDTH = 460;
const MIN_RIGHT_RAIL_WIDTH = 280;
const MAX_RIGHT_RAIL_WIDTH = 520;
const RESIZE_LAYOUT_RESERVE = 72;
const CURRENT_RUN_STORAGE_KEY = "ai-insight.currentRunId";
const SCOPE_DRAFT_STORAGE_KEY = "ai-insight.scopeDraft";
const WorkflowGraph = lazy(() => import("./components/WorkflowGraph").then((module) => ({ default: module.WorkflowGraph })));
const ArtifactViewer = lazy(() => import("./components/ArtifactViewer").then((module) => ({ default: module.ArtifactViewer })));
const SchemaPanel = lazy(() => import("./components/SchemaPanel").then((module) => ({ default: module.SchemaPanel })));

interface ScopeDraft {
  industry: string;
  competitors: string;
  dimensions: string;
  outputGoal: string;
  sourceUrls: string;
  maxReviewReworkAttempts: number;
}

export function App() {
  const initialScopeDraftRef = useRef(readScopeDraft());
  const workspaceRef = useRef<HTMLElement>(null);
  const leftRailRef = useRef<HTMLElement>(null);
  const surveyImportInputRef = useRef<HTMLInputElement>(null);
  const [run, setRun] = useState<AnalysisRun | null>(null);
  const [serverRunMetrics, setServerRunMetrics] = useState<AnalysisRunMetrics | null>(null);
  const [historyRuns, setHistoryRuns] = useState<AnalysisRunSummary[]>([]);
  const [industry, setIndustry] = useState(() => initialScopeDraftRef.current?.industry ?? "");
  const [competitors, setCompetitors] = useState(() => initialScopeDraftRef.current?.competitors ?? "");
  const [dimensions, setDimensions] = useState(() => initialScopeDraftRef.current?.dimensions ?? "");
  const [outputGoal, setOutputGoal] = useState(() => initialScopeDraftRef.current?.outputGoal ?? "");
  const [sourceUrls, setSourceUrls] = useState(() => initialScopeDraftRef.current?.sourceUrls ?? "");
  const [maxReviewReworkAttempts, setMaxReviewReworkAttempts] = useState(() => initialScopeDraftRef.current?.maxReviewReworkAttempts ?? 1);
  const [selectedArtifactId, setSelectedArtifactId] = useState<string>();
  const [artifactPinned, setArtifactPinned] = useState(false);
  const [selectedCitationKey, setSelectedCitationKey] = useState<string>();
  const [selectedCitationRequestId, setSelectedCitationRequestId] = useState(0);
  const [selectedClaimId, setSelectedClaimId] = useState<string>();
  const [selectedClaimRequestId, setSelectedClaimRequestId] = useState(0);
  const [artifactLocateRequest, setArtifactLocateRequest] = useState<ArtifactLocateRequest>();
  const [selectedAgent, setSelectedAgent] = useState<AgentName | null>(null);
  const [traceDrawerAgent, setTraceDrawerAgent] = useState<AgentName | null>(null);
  const [contextTargetAgent, setContextTargetAgent] = useState<AgentName>();
  const [rerunningAgent, setRerunningAgent] = useState<AgentName | null>(null);
  const [surveyBusy, setSurveyBusy] = useState(false);
  const [deletingInsightKey, setDeletingInsightKey] = useState<string>();
  const [evidenceSourceType, setEvidenceSourceType] = useState("url");
  const [evidenceUrl, setEvidenceUrl] = useState("");
  const [evidenceContent, setEvidenceContent] = useState("");
  const [isAddingEvidence, setIsAddingEvidence] = useState(false);
  const [documentFile, setDocumentFile] = useState<File | null>(null);
  const [documentTitle, setDocumentTitle] = useState("");
  const [documentSourceType, setDocumentSourceType] = useState("document");
  const [documentNotes, setDocumentNotes] = useState("");
  const [isUploadingDocument, setIsUploadingDocument] = useState(false);
  const [documentInputKey, setDocumentInputKey] = useState(0);
  const [mainView, setMainView] = useState<MainView>("dag");
  const [collapsedLeftPanels, setCollapsedLeftPanels] = useState<Record<LeftPanelId, boolean>>({
    scope: false,
    context: false,
    evidence: false
  });
  const [collapsedRightPanels, setCollapsedRightPanels] = useState<Record<RightPanelId, boolean>>({
    timeline: true,
    evidence: false,
    review: false,
    metrics: true
  });
  const [localScopeConfirmed, setLocalScopeConfirmed] = useState(false);
  const [scopeEditMode, setScopeEditMode] = useState(false);
  const [scopeNeedsReclarify, setScopeNeedsReclarify] = useState(false);
  const [eventMessage, setEventMessage] = useState("等待填写范围确认");
  const [backendStatus, setBackendStatus] = useState<BackendStatus>("checking");
  const [isCreating, setIsCreating] = useState(false);
  const [pendingClarificationRunId, setPendingClarificationRunId] = useState<string>();
  const [isScopeBusy, setIsScopeBusy] = useState(false);
  const [isHistoryLoading, setIsHistoryLoading] = useState(false);
  const [deletingRunId, setDeletingRunId] = useState<string>();
  const [deleteCandidate, setDeleteCandidate] = useState<AnalysisRunSummary>();
  const [historyOpen, setHistoryOpen] = useState(false);
  const [resourcePackOpen, setResourcePackOpen] = useState(false);
  const [deletingResourceKey, setDeletingResourceKey] = useState<string>();
  const [leftRailWidth, setLeftRailWidth] = useState(286);
  const [rightRailWidth, setRightRailWidth] = useState(342);
  const refreshTimerRef = useRef<number>();
  const refreshTimerRunIdRef = useRef<string>();
  const workspaceRequestTokenRef = useRef(0);
  const handleMainViewChange = useCallback((nextView: MainView) => {
    if (nextView !== "schema") {
      setSelectedClaimId(undefined);
    }
    if (nextView !== "report") {
      setArtifactLocateRequest(undefined);
    }
    setMainView(nextView);
  }, []);
  // Requirement may change without changing run.id after re-clarify or requirement updates.
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
      sourceUrls: scope.sourceUrls ?? [],
      maxReviewReworkAttempts: run.maxReviewReworkAttempts ?? 1,
      confirmed: Boolean(run.clarificationDraft?.confirmed),
      clarificationQuestions: run.clarificationDraft?.clarificationQuestions ?? [],
      clarificationItems: (run.clarificationDraft?.clarificationItems ?? []).map((item) => ({
        field: item.field,
        question: item.question,
        options: item.options?.map((option) => ({
          label: option.label,
          values: option.values ?? [],
          recommended: Boolean(option.recommended)
        })) ?? []
      }))
    });
  }, [run]);

  const loadHistory = useCallback(async () => {
    const runs = await listRunSummaries();
    setHistoryRuns(runs);
    setBackendStatus("connected");
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
      setBackendStatus("connected");
      setRun(latest);
      setArtifactPinned(false);
      setSelectedArtifactId(undefined);
      setSelectedCitationKey(undefined);
      setSelectedClaimId(undefined);
      setSelectedAgent(null);
      setRerunningAgent(null);
      setPendingClarificationRunId(undefined);
      setScopeEditMode(false);
      setScopeNeedsReclarify(false);
      setMainView("dag");
      setEventMessage("历史会话已恢复");
      setHistoryOpen(false);
      setHistoryRuns((runs) => upsertHistorySummary(runs, summaryFromRun(latest)));
    } catch (error) {
      if (requestToken !== workspaceRequestTokenRef.current) return;
      window.localStorage.removeItem(CURRENT_RUN_STORAGE_KEY);
      setBackendStatus("failed");
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
    setBackendStatus("connected");
    setRun((current) => mergeRunSnapshotWithLocalRunningStep(current, latest));
    setHistoryRuns((runs) => upsertHistorySummary(runs, summaryFromRun(latest)));
  }, [loadHistory, run?.id]);

  const clearPendingRunRefresh = useCallback(() => {
    if (refreshTimerRef.current) {
      window.clearTimeout(refreshTimerRef.current);
      refreshTimerRef.current = undefined;
    }
    refreshTimerRunIdRef.current = undefined;
  }, []);

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

  const requestRunRefresh = useCallback((runId: string, delayMs = 500) => {
    if (refreshTimerRef.current) {
      if (refreshTimerRunIdRef.current === runId && delayMs > 0) return;
      clearPendingRunRefresh();
    }
    if (delayMs <= 0) {
      if (!isCurrentWorkspaceRun(runId)) return;
      refreshRun(runId).catch((error) => {
        if (isCurrentWorkspaceRun(runId)) {
          setEventMessage(error.message);
        }
      });
      return;
    }
    refreshTimerRunIdRef.current = runId;
    refreshTimerRef.current = window.setTimeout(() => {
      const pendingRunId = runId;
      refreshTimerRef.current = undefined;
      refreshTimerRunIdRef.current = undefined;
      if (!isCurrentWorkspaceRun(pendingRunId)) return;
      refreshRun(pendingRunId).catch((error) => {
        if (isCurrentWorkspaceRun(pendingRunId)) {
          setEventMessage(error.message);
        }
      });
    }, delayMs);
  }, [clearPendingRunRefresh, refreshRun]);

  useEffect(() => {
    let cancelled = false;
    async function restoreWorkspace() {
      try {
        setBackendStatus("checking");
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
        setBackendStatus("failed");
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
    const activeRunId = run.id;
    // SSE 用于驱动演示中的实时回放；失败时保留轮询兜底，避免浏览器或代理不支持事件流时页面停住。
    const events = new EventSource(`/api/analysis-runs/${activeRunId}/events`);
    const eventTypes = [
      "subscribed",
      "run_created",
      "clarification_ready",
      "requirement_confirmed",
      "run_start_requested",
      "run_started",
      "context_added",
      "evidence_added",
      "document_added",
      // 文档解析是后台异步流程，必须监听这些事件才能及时解除“处理中”禁用态。
      "document_ingestion_started",
      "document_ingestion_progress",
      "document_ingestion_completed",
      "document_ingestion_failed",
      "document_deleted",
      "agent_started",
      "agent_succeeded",
      "agent_failed",
      "agent_cancelled",
      "agent_rerun_started",
      "agent_rerun_completed",
      "agent_rerun_failed",
      "survey_questionnaire_updated",
      "survey_results_imported",
      "research.collection.plan.updated",
      "research.repair.targets.updated",
      "review_rework_started",
      "review_rework_completed",
      "run_cancelled",
      "run_needs_user_input",
      "run_succeeded",
      "run_failed"
    ];
    events.addEventListener("run_snapshot", (event) => {
      const snapshot = safeParseRunSnapshot(event);
      if (!snapshot || snapshot.id !== activeRunId || !isCurrentWorkspaceRun(snapshot.id)) return;
      setBackendStatus("connected");
      setRun((current) => mergeRunSnapshotWithLocalRunningStep(current, snapshot));
      if (isClarificationSettled(snapshot)) {
        setPendingClarificationRunId(undefined);
      }
      setHistoryRuns((runs) => upsertHistorySummary(runs, summaryFromRun(snapshot)));
    });
    eventTypes.forEach((type) => {
      events.addEventListener(type, (event) => {
        const data = safeParseEvent(event);
        setEventMessage(data?.message || type);
        if (type === "clarification_ready" || type === "run_failed" || type === "run_cancelled") {
          setPendingClarificationRunId(undefined);
        }
        // agent_started 使用延迟刷新，避免在 Clarifier 等 Agent 执行期间立即拉取到过时的中间态数据
        // （例如 clarifyRequirement 先写入空草稿再跑 Clarifier，即时刷新会把空草稿覆盖到前端）。
        // clarification_ready 说明 Clarifier 已完成，使用较短延迟确保 API 响应先到达。
        const refreshDelay = type === "agent_started" ? 800
          : type === "clarification_ready" ? 200
          : 500;
        requestRunRefresh(activeRunId, refreshDelay);
      });
    });
    events.onerror = () => setEventMessage("SSE 暂不可用，使用轮询刷新");
    return () => events.close();
  }, [run?.id]);

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
      clearPendingRunRefresh();
    };
  }, [clearPendingRunRefresh]);

  useEffect(() => {
    if (!selectedAgent && traceDrawerAgent) {
      setTraceDrawerAgent(null);
    }
  }, [selectedAgent, traceDrawerAgent]);

  useEffect(() => {
    if (!run || pendingClarificationRunId !== run.id) return;
    if (isClarificationSettled(run)) {
      setPendingClarificationRunId(undefined);
    }
  }, [pendingClarificationRunId, run?.id, run?.status, run?.steps.length]);

  const selectedArtifact = useMemo(() => {
    const artifacts = run?.artifacts ?? [];
    if (!artifacts.length) return undefined;
    // 用户未手动选择时始终跟随后端最新推荐产物；手动选择后保持 pinned，方便对比报告版本。
    if (!artifactPinned) return findDefaultArtifact(artifacts);
    return artifacts.find((artifact) => artifact.id === selectedArtifactId) ?? findDefaultArtifact(artifacts);
  }, [artifactPinned, run?.artifacts, selectedArtifactId]);

  const reportArtifacts = useMemo(() => {
    return (run?.artifacts ?? []).filter(isReportArtifact);
  }, [run?.artifacts]);

  const reportDisplayArtifact = useMemo(() => {
    return selectedArtifact && isReportArtifact(selectedArtifact)
      ? selectedArtifact
      // FINAL_REPORT is only expected on historical runs; current runs display the latest REPORT_DRAFT.
      : [...reportArtifacts].reverse().find((artifact) => artifact.type === "FINAL_REPORT")
        ?? reportArtifacts.at(-1);
  }, [reportArtifacts, selectedArtifact]);



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
    setMaxReviewReworkAttempts(run.maxReviewReworkAttempts ?? 1);
    setLocalScopeConfirmed(Boolean(run.clarificationDraft?.confirmed));
  }, [scopeSyncKey]);

  const restoreScopeFormFromRun = useCallback((sourceRun: AnalysisRun | null) => {
    if (!sourceRun?.id) return;
    const scope = sourceRun.clarificationDraft ?? sourceRun.requirement;
    if (!scope) return;
    setIndustry(scope.industry ?? "");
    setCompetitors((scope.competitors ?? []).join(", "));
    setDimensions((scope.dimensions ?? []).join(", "));
    setOutputGoal(scope.outputGoal ?? "");
    setSourceUrls((scope.sourceUrls ?? []).join("\n"));
    setMaxReviewReworkAttempts(sourceRun.maxReviewReworkAttempts ?? 1);
    setLocalScopeConfirmed(Boolean(sourceRun.clarificationDraft?.confirmed));
  }, []);

  useEffect(() => {
    if (run?.id) return;
    writeScopeDraft({
      industry,
      competitors,
      dimensions,
      outputGoal,
      sourceUrls,
      maxReviewReworkAttempts
    });
  }, [
    run?.id,
    industry,
    competitors,
    dimensions,
    outputGoal,
    sourceUrls,
    maxReviewReworkAttempts
  ]);

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

  const resetLeftRailHorizontalScroll = useCallback(() => {
    const reset = (element: HTMLElement | null) => {
      if (element) element.scrollLeft = 0;
    };
    reset(workspaceRef.current);
    reset(leftRailRef.current);
    window.requestAnimationFrame(() => {
      reset(workspaceRef.current);
      reset(leftRailRef.current);
    });
  }, []);

  const toggleLeftPanel = useCallback((panel: LeftPanelId) => {
    resetLeftRailHorizontalScroll();
    setCollapsedLeftPanels((current) => ({
      ...current,
      [panel]: !current[panel]
    }));
  }, [resetLeftRailHorizontalScroll]);

  useEffect(() => {
    resetLeftRailHorizontalScroll();
  }, [collapsedLeftPanels, resetLeftRailHorizontalScroll]);

  const handleSelectCitation = useCallback((citationKey: string) => {
    setSelectedCitationKey(citationKey);
    setSelectedCitationRequestId((requestId) => requestId + 1);
    setCollapsedRightPanels((current) => {
      if (!current.evidence) return current;
      return {
        ...current,
        evidence: false
      };
    });
  }, []);

  const handleOpenAgentTrace = useCallback((agentName: AgentName) => {
    setSelectedAgent(agentName);
    setTraceDrawerAgent(agentName);
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
    const rightRailStacked = window.matchMedia(`(max-width: ${STACKED_RIGHT_RAIL_BREAKPOINT}px)`).matches;
    document.body.classList.add("is-resizing-rail");

    const handlePointerMove = (moveEvent: PointerEvent) => {
      const deltaX = moveEvent.clientX - startX;
      if (side === "left") {
        const centerMinWidth = rightRailStacked ? STACKED_MIN_CENTER_WIDTH : MIN_CENTER_WIDTH;
        const reservedRightRailWidth = rightRailStacked ? 0 : startRightWidth;
        const maxWidth = Math.min(
          MAX_LEFT_RAIL_WIDTH,
          workspaceWidth - reservedRightRailWidth - centerMinWidth - RESIZE_LAYOUT_RESERVE
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
    clearPendingRunRefresh();
    window.localStorage.removeItem(CURRENT_RUN_STORAGE_KEY);
    const draft = readScopeDraft();
    setRun(null);
    setPendingClarificationRunId(undefined);
    setServerRunMetrics(null);
    setIndustry(draft?.industry ?? "");
    setCompetitors(draft?.competitors ?? "");
    setDimensions(draft?.dimensions ?? "");
    setOutputGoal(draft?.outputGoal ?? "");
    setSourceUrls(draft?.sourceUrls ?? "");
    setMaxReviewReworkAttempts(draft?.maxReviewReworkAttempts ?? 1);
    setSelectedArtifactId(undefined);
    setArtifactPinned(false);
    setSelectedCitationKey(undefined);
    setSelectedClaimId(undefined);
    setSelectedAgent(null);
    setContextTargetAgent(undefined);
    setRerunningAgent(null);
    setEvidenceSourceType("url");
    setEvidenceUrl("");
    setEvidenceContent("");
    setLocalScopeConfirmed(false);
    setScopeEditMode(false);
    setScopeNeedsReclarify(false);
    setIsCreating(false);
    setIsHistoryLoading(false);
    setMainView("dag");
    setHistoryOpen(false);
    setEventMessage(draft ? "已恢复未提交的范围草稿" : "已切换到新建分析");
  }

  async function handleDeleteHistoryRun(summary: AnalysisRunSummary) {
    setDeleteCandidate(summary);
  }

  function handleCancelDeleteHistoryRun() {
    if (deletingRunId) {
      return;
    }
    setDeleteCandidate(undefined);
  }

  async function confirmDeleteHistoryRun() {
    const summary = deleteCandidate;
    if (!summary || deletingRunId) return;
    const deletingCurrentRun = run?.id === summary.id;
    setDeletingRunId(summary.id);
    setEventMessage("正在删除历史会话");
    try {
      await deleteRun(summary.id);
      setBackendStatus("connected");
      setHistoryRuns((runs) => runs.filter((item) => item.id !== summary.id));
      if (window.localStorage.getItem(CURRENT_RUN_STORAGE_KEY) === summary.id) {
        window.localStorage.removeItem(CURRENT_RUN_STORAGE_KEY);
      }
      if (deletingCurrentRun) {
        handleNewRun();
        setHistoryOpen(true);
      }
      setDeleteCandidate(undefined);
      setEventMessage("历史会话已删除");
    } catch (error) {
      setBackendStatus("failed");
      setEventMessage(error instanceof Error ? `历史会话删除失败：${error.message}` : "历史会话删除失败");
    } finally {
      setDeletingRunId(undefined);
    }
  }

  async function handleCreateRun() {
    const requestToken = ++workspaceRequestTokenRef.current;
    setIsCreating(true);
    setEventMessage("正在生成范围确认内容");
    setArtifactPinned(false);
    setSelectedCitationKey(undefined);
    setSelectedAgent(null);
    setTraceDrawerAgent(null);
    setPendingClarificationRunId(undefined);
    try {
      const competitorList = splitList(competitors);
      const dimensionList = splitList(dimensions);
      const sourceUrlList = splitLines(sourceUrls);
      // 返工次数属于 run 级执行选项，不写入需求正文；创建草稿时就带上，后续启动接口只负责开跑。
      const nextRun = await createRun({
        prompt: buildScopePrompt(industry, outputGoal, competitorList, dimensionList, sourceUrlList),
        industry,
        competitors: competitorList,
        dimensions: dimensionList,
        sourceUrls: sourceUrlList,
        outputGoal,
        maxReviewReworkAttempts
      });
      if (requestToken !== workspaceRequestTokenRef.current) return;
      setBackendStatus("connected");
      setRun(nextRun);
      if (!isClarificationSettled(nextRun)) {
        setPendingClarificationRunId(nextRun.id);
      }
      requestRunRefresh(nextRun.id);
      removeScopeDraft();
      window.localStorage.setItem(CURRENT_RUN_STORAGE_KEY, nextRun.id);
      setScopeEditMode(false);
      setScopeNeedsReclarify(false);
      setHistoryRuns((runs) => upsertHistorySummary(runs, summaryFromRun(nextRun)));
    } catch (error) {
      if (requestToken !== workspaceRequestTokenRef.current) return;
      setPendingClarificationRunId(undefined);
      setBackendStatus("failed");
      setEventMessage(error instanceof Error ? error.message : "范围确认生成失败");
    } finally {
      if (requestToken === workspaceRequestTokenRef.current) {
        setIsCreating(false);
      }
    }
  }

  async function handleConfirmRequirement() {
    if (!run) return;
    const requestToken = ++workspaceRequestTokenRef.current;
    setIsScopeBusy(true);
    setEventMessage("正在确认分析范围");
    try {
      const nextRun = await updateRequirement(run.id, {
        industry,
        competitors: splitList(competitors),
        dimensions: splitList(dimensions),
        sourceUrls: splitLines(sourceUrls),
        outputGoal,
        maxReviewReworkAttempts
      });
      if (requestToken !== workspaceRequestTokenRef.current) return;
      setRun(nextRun);
      setScopeEditMode(true);
      setScopeNeedsReclarify(false);
      setEventMessage("分析范围已确认");
    } catch (error) {
      if (requestToken !== workspaceRequestTokenRef.current) return;
      setEventMessage(error instanceof Error ? `范围确认失败：${error.message}` : "范围确认失败");
    } finally {
      if (requestToken === workspaceRequestTokenRef.current) {
        setIsScopeBusy(false);
      }
    }
  }

  const handleApplyClarificationOption = useCallback((field: string, values: string[]) => {
    const normalizedValues = values.filter((value) => value.trim());
    if (field === "industry") {
      setIndustry(normalizedValues[0] ?? "");
    } else if (field === "competitors") {
      setCompetitors(normalizedValues.join("、"));
    } else if (field === "dimensions") {
      setDimensions(normalizedValues.join("、"));
    } else if (field === "sourceUrls") {
      setSourceUrls(normalizedValues.join("\n"));
    } else if (field === "outputGoal") {
      setOutputGoal(normalizedValues[0] ?? "");
    }
    setLocalScopeConfirmed(false);
    setEventMessage("已应用澄清选项，请确认范围");
  }, []);

  const handleManualScopeEdit = useCallback(() => {
    setLocalScopeConfirmed(false);
    setScopeNeedsReclarify(true);
  }, []);

  const handleStartScopeEdit = useCallback(() => {
    setScopeEditMode(true);
    setLocalScopeConfirmed(false);
    setScopeNeedsReclarify(true);
    setEventMessage("已进入范围编辑，可重新澄清后再次启动分析");
  }, []);

  const handleCancelScopeEdit = useCallback(() => {
    restoreScopeFormFromRun(run);
    setScopeEditMode(false);
    setScopeNeedsReclarify(false);
    setEventMessage("已取消范围编辑");
  }, [restoreScopeFormFromRun, run]);

  async function handleReclarifyScope() {
    if (!run) return;
    const requestToken = ++workspaceRequestTokenRef.current;
    setIsScopeBusy(true);
    setSelectedAgent(null);
    setTraceDrawerAgent(null);
    setRun((current) => current ? withOptimisticClarifierStep(current, buildClarifierScopeInputSummary(
      industry,
      outputGoal,
      splitList(competitors),
      splitList(dimensions),
      splitLines(sourceUrls)
    )) : current);
    setPendingClarificationRunId(run.id);
    // 必须在 await 之前清除 requiresReclarify，否则 ScopeConfirmationPanel 的渲染链
    // 会在整个 Clarifier 执行期间命中 "请先重新澄清范围" 分支，同时把新的澄清项过滤成空数组。
    // 清除后配合 pendingClarification=true，面板会正确显示 "正在生成范围确认" 加载态。
    setScopeNeedsReclarify(false);
    setEventMessage("正在重新澄清范围");
    try {
      const nextRun = await clarifyRequirement(run.id, {
        industry,
        competitors: splitList(competitors),
        dimensions: splitList(dimensions),
        sourceUrls: splitLines(sourceUrls),
        outputGoal,
        maxReviewReworkAttempts
      });
      if (requestToken !== workspaceRequestTokenRef.current) return;
      // 取消 agent_started 等 SSE 事件可能已排期的过时刷新，防止空草稿覆盖 API 返回的最新澄清结果。
      clearPendingRunRefresh();
      setRun(nextRun);
      setScopeEditMode(true);
      // 重新澄清后一定需要用户重新确认，不信任后端可能泄漏的旧 confirmed 状态。
      setLocalScopeConfirmed(false);
      // 用延迟刷新兜底，确保 Clarifier 异步完成时前端能拿到最终数据。
      requestRunRefresh(nextRun.id, 500);
      setEventMessage("范围已重新澄清");
    } catch (error) {
      if (requestToken !== workspaceRequestTokenRef.current) return;
      // 失败时恢复 reclarify 标记，让用户可以重试。
      setScopeNeedsReclarify(true);
      setEventMessage(error instanceof Error ? `重新澄清失败：${error.message}` : "重新澄清失败");
    } finally {
      if (requestToken === workspaceRequestTokenRef.current) {
        setIsScopeBusy(false);
      }
    }
  }

  async function handleStartAnalysis() {
    if (!run) return;
    if (runMutationDisabled) return;
    const requestToken = ++workspaceRequestTokenRef.current;
    setIsScopeBusy(true);
    setEventMessage("正在保存范围并启动 Agent 分析");
    try {
      // startAnalysis 没有请求体，先把最新范围和执行选项落库，再启动 DAG，避免前端选择被旧 run 覆盖。
      await updateRequirement(run.id, {
        industry,
        competitors: splitList(competitors),
        dimensions: splitList(dimensions),
        sourceUrls: splitLines(sourceUrls),
        outputGoal,
        maxReviewReworkAttempts
      });
      const nextRun = await startAnalysis(run.id);
      if (requestToken !== workspaceRequestTokenRef.current) return;
      setRun(nextRun);
      setScopeEditMode(false);
      setScopeNeedsReclarify(false);
      setMainView("dag");
    } catch (error) {
      if (requestToken !== workspaceRequestTokenRef.current) return;
      const phase = resolveRunPhase(run);
      setEventMessage(error instanceof Error ? `启动 Agent 分析失败：${error.message}` : "启动 Agent 分析失败");
      if (phase === "AWAITING_CONFIRMATION") {
        refreshRun(run.id).catch(() => undefined);
      }
    } finally {
      if (requestToken === workspaceRequestTokenRef.current) {
        setIsScopeBusy(false);
      }
    }
  }

  async function handleSubmitRerun() {
    if (!contextTargetAgent || contextTargetAgent === "CLARIFIER") {
      setContextTargetAgent(undefined);
      return;
    }
    await handleRerun(contextTargetAgent);
  }

  async function handleAddEvidence() {
    if (!run || runMutationDisabled) return;
    const normalizedType = evidenceSourceType.toLowerCase();
    const normalizedUrl = evidenceUrl.trim();
    const normalizedContent = evidenceContent.trim();
    if (normalizedType === "url" && !normalizedUrl) return;
    if (normalizedType === "interview" && !normalizedContent) return;
    if (normalizedType === "survey") return;
    const runId = run.id;
    const recommendedActionStart = run.recommendedActions?.length ?? 0;
    setIsAddingEvidence(true);
    setEventMessage(normalizedType === "url" ? "正在加入公开来源" : "正在加入访谈资料");
    try {
      const nextRun = await addEvidence(runId, {
        sourceType: normalizedType,
        content: normalizedType === "interview" ? normalizedContent : undefined,
        url: normalizedType === "url" ? normalizedUrl : undefined
      });
      if (!isCurrentWorkspaceRun(runId)) return;
      setRun(nextRun);
      setEvidenceSourceType("url");
      setEvidenceUrl("");
      setEvidenceContent("");
      setEventMessage(latestRecommendedActionSince(
        nextRun,
        recommendedActionStart,
        normalizedType === "url" ? "公开来源已加入证据链" : "访谈资料已加入证据链"
      ));
    } catch (error) {
      if (!isCurrentWorkspaceRun(runId)) return;
      setEventMessage(error instanceof Error ? `资料加入失败：${error.message}` : "资料加入失败");
    } finally {
      if (isCurrentWorkspaceRun(runId)) {
        setIsAddingEvidence(false);
      }
    }
  }

  async function handleDownloadSurveyTemplate() {
    if (!run || runMutationDisabled || surveyBusy) return;
    const runId = run.id;
    setSurveyBusy(true);
    setEventMessage("正在生成腾讯问卷文本");
    try {
      const blob = await downloadSurveyTemplate(runId);
      if (!isCurrentWorkspaceRun(runId)) return;
      const url = URL.createObjectURL(blob);
      const link = document.createElement("a");
      link.href = url;
      link.download = `survey-questionnaire-${runId.slice(0, 8)}.txt`;
      document.body.appendChild(link);
      link.click();
      link.remove();
      URL.revokeObjectURL(url);
      setEventMessage("调研问卷文本已下载，可粘贴到腾讯问卷内容编辑器");
    } catch (error) {
      if (!isCurrentWorkspaceRun(runId)) return;
      setEventMessage(error instanceof Error ? `模板下载失败：${error.message}` : "模板下载失败");
    } finally {
      if (isCurrentWorkspaceRun(runId)) {
        setSurveyBusy(false);
      }
    }
  }

  async function handleSaveQuestionnaire(questionnaire: Questionnaire) {
    if (!run || runMutationDisabled || surveyBusy) return;
    const runId = run.id;
    setSurveyBusy(true);
    setEventMessage("正在保存问卷草案");
    try {
      const nextRun = await updateSurveyQuestionnaire(runId, questionnaire);
      if (!isCurrentWorkspaceRun(runId)) return;
      setRun(nextRun);
      setHistoryRuns((runs) => upsertHistorySummary(runs, summaryFromRun(nextRun)));
      setEventMessage("问卷草案已保存，后续模板会使用新题目");
    } catch (error) {
      if (!isCurrentWorkspaceRun(runId)) return;
      setEventMessage(error instanceof Error ? `问卷草案保存失败：${error.message}` : "问卷草案保存失败");
    } finally {
      if (isCurrentWorkspaceRun(runId)) {
        setSurveyBusy(false);
      }
    }
  }

  async function handleImportSurveyResults(file: File) {
    if (!run || runMutationDisabled || surveyBusy) return;
    const runId = run.id;
    setSurveyBusy(true);
    setEventMessage("正在导入问卷结果，导入完成后需要手动应用到分析链路");
    try {
      const nextRun = await importSurveyResults(runId, file);
      if (!isCurrentWorkspaceRun(runId)) return;
      setRun(nextRun);
      setHistoryRuns((runs) => upsertHistorySummary(runs, summaryFromRun(nextRun)));
      setEventMessage("问卷结果已导入为待应用调研数据，点击“应用并重跑 Extractor”后刷新分析结论");
    } catch (error) {
      if (!isCurrentWorkspaceRun(runId)) return;
      setEventMessage(error instanceof Error ? `问卷结果导入失败：${error.message}` : "问卷结果导入失败");
    } finally {
      if (isCurrentWorkspaceRun(runId)) {
        refreshRun(runId).catch(() => undefined);
        setSurveyBusy(false);
      }
    }
  }

  async function handleApplyResearchInputs() {
    if (!run || runMutationDisabled || surveyBusy || rerunningAgent) return;
    handleMainViewChange("dag");
    await handleRerun("EXTRACTOR");
  }

  async function handleDeleteResearchInsight(insightType: "survey" | "interview", insightId: string) {
    if (!run || runMutationDisabled || surveyBusy || !insightId) return;
    const label = insightType === "survey" ? "问卷洞察" : "访谈洞察";
    const confirmed = window.confirm(`确定删除这条${label}吗？关联的调研原始证据也会移除，后续重跑不会再使用。`);
    if (!confirmed) return;
    const runId = run.id;
    const deletingKey = `${insightType}:${insightId}`;
    setDeletingInsightKey(deletingKey);
    setSurveyBusy(true);
    setEventMessage(`正在删除${label}`);
    try {
      const nextRun = await deleteResearchInsight(runId, insightType, insightId);
      if (!isCurrentWorkspaceRun(runId)) return;
      setRun(nextRun);
      setHistoryRuns((runs) => upsertHistorySummary(runs, summaryFromRun(nextRun)));
      setEventMessage(`${label}已删除，关联调研资料已从证据链移除`);
    } catch (error) {
      if (!isCurrentWorkspaceRun(runId)) return;
      setEventMessage(error instanceof Error ? `${label}删除失败：${error.message}` : `${label}删除失败`);
    } finally {
      if (isCurrentWorkspaceRun(runId)) {
        setDeletingInsightKey(undefined);
        setSurveyBusy(false);
      }
    }
  }

  async function handleUploadDocument() {
    if (!run || !documentFile || runMutationDisabled || isUploadingDocument) return;
    const runId = run.id;
    setIsUploadingDocument(true);
    setEventMessage("正在把文件加入当前任务资源包");
    try {
      const nextRun = await uploadDocument(runId, {
        file: documentFile,
        title: documentTitle.trim() || undefined,
        sourceType: documentSourceType,
        sensitive: false,
        notes: documentNotes.trim() || undefined,
        global: false
      });
      if (!isCurrentWorkspaceRun(runId)) return;
      setRun(nextRun);
      setDocumentFile(null);
      setDocumentTitle("");
      setDocumentSourceType("document");
      setDocumentNotes("");
      setDocumentInputKey((value) => value + 1);
      setHistoryRuns((runs) => upsertHistorySummary(runs, summaryFromRun(nextRun)));
      setEventMessage("文件已加入当前任务资源包");
    } catch (error) {
      if (!isCurrentWorkspaceRun(runId)) return;
      setEventMessage(error instanceof Error ? `文件上传失败：${error.message}` : "文件上传失败");
    } finally {
      if (isCurrentWorkspaceRun(runId)) {
        setIsUploadingDocument(false);
      }
    }
  }

  async function handleDeleteUserResource(citationKey: string) {
    if (!run || runMutationDisabled || deletingResourceKey) return;
    const runId = run.id;
    setDeletingResourceKey(citationKey);
    setEventMessage(`正在移除用户资源 ${citationKey}`);
    try {
      const nextRun = await deleteDocument(runId, citationKey);
      if (!isCurrentWorkspaceRun(runId)) return;
      setRun(nextRun);
      setHistoryRuns((runs) => upsertHistorySummary(runs, summaryFromRun(nextRun)));
      setEventMessage(`用户资源 ${citationKey} 已移除`);
    } catch (error) {
      if (!isCurrentWorkspaceRun(runId)) return;
      setEventMessage(error instanceof Error ? `用户资源删除失败：${error.message}` : "用户资源删除失败");
    } finally {
      if (isCurrentWorkspaceRun(runId)) {
        setDeletingResourceKey(undefined);
      }
    }
  }

  async function handleRerun(agentName: AgentName) {
    if (!run || runMutationDisabled || rerunningAgent) return;
    const runId = run.id;
    setRerunningAgent(agentName);
    setRun((current) => withOptimisticRerunStep(current, agentName));
    setEventMessage(`正在从 ${AGENT_LABELS[agentName]} 继续重跑下游链路`);
    try {
      const nextRun = await rerunAgent(runId, agentName);
      if (!isCurrentWorkspaceRun(runId)) return;
      setRun(nextRun);
      setEventMessage(`${AGENT_LABELS[agentName]} 及下游链路已重跑`);
    } catch (error) {
      if (!isCurrentWorkspaceRun(runId)) return;
      setEventMessage(error instanceof Error ? `重跑失败：${error.message}` : "重跑失败");
    } finally {
      if (isCurrentWorkspaceRun(runId)) {
        refreshRun(runId).catch(() => undefined);
        setRerunningAgent(null);
      }
    }
  }

  function handleLocateFinding(finding: ReviewFinding) {
    const effectiveCitationKey = finding.citationKey ?? citationKeyFromFinding(finding);
    const locationType = locationTypeFromFinding(finding, effectiveCitationKey);
    const claim = run?.claims?.find((item) => item.id === finding.claimId);
    const targetReportArtifact = reportArtifactForFinding(finding, reportArtifacts, reportDisplayArtifact);

    if (locationType === "CLAIM" && finding.claimId) {
      setArtifactLocateRequest(undefined);
      setSelectedClaimId(finding.claimId);
      setSelectedClaimRequestId((requestId) => requestId + 1);
      handleMainViewChange("schema");
      setEventMessage(`已定位到结构化结论 ${finding.claimId}`);
      return;
    }

    if (locationType === "REPORT_PARAGRAPH" && targetReportArtifact && (finding.paragraphIndex !== undefined || finding.excerpt)) {
      setSelectedArtifactId(targetReportArtifact.id);
      setArtifactPinned(true);
      setArtifactLocateRequest((current) => ({
        requestId: (current?.requestId ?? 0) + 1,
        artifactId: targetReportArtifact.id,
        paragraphIndex: finding.paragraphIndex,
        excerpt: finding.excerpt,
        claimId: finding.claimId,
        claimText: claim?.content,
        citationKey: effectiveCitationKey
      }));
      handleMainViewChange("report");
      setEventMessage(`已定位到报告${finding.paragraphIndex !== undefined ? `段落 ${finding.paragraphIndex}` : ""}${finding.claimId ? `，关联结论 ${finding.claimId}` : ""}`);
      return;
    }

    if (locationType === "EVIDENCE_SOURCE" && effectiveCitationKey) {
      setArtifactLocateRequest(undefined);
      setSelectedClaimId(undefined);
      handleSelectCitation(effectiveCitationKey);
      setEventMessage(`已定位到证据 [${effectiveCitationKey}]`);
      return;
    }

    if (locationType === "SCHEMA") {
      setArtifactLocateRequest(undefined);
      setSelectedClaimId(undefined);
      handleMainViewChange("schema");
      setEventMessage("已打开结构化信息");
      return;
    }

    if (targetReportArtifact) {
      setArtifactLocateRequest(undefined);
      setSelectedClaimId(undefined);
      setSelectedArtifactId(targetReportArtifact.id);
      setArtifactPinned(true);
      handleMainViewChange("report");
      setEventMessage("已打开关联报告");
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
    lowFindingCount: run?.reviewFindings.filter((finding) => finding.severity === "LOW").length ?? 0,
    latestImprovement: localRunMetrics.latestImprovement
  };
  const phase = String(resolveRunPhase(run));
  const userResourceSources = useMemo(
    () => (run?.evidenceSources ?? []).filter(isUserResourceSource),
    [run?.evidenceSources]
  );
  const processingResourceCount = userResourceSources.filter(isProcessingUserResource).length;
  // 资源包文件完成解析前，不允许启动/重跑/继续修改会触发 Agent 的入口。
  const runMutationDisabled = !run
    || Boolean(rerunningAgent)
    || isAddingEvidence
    || surveyBusy
    || isUploadingDocument
    || processingResourceCount > 0
    || ["RUNNING", "REVIEWING", "REVISING", "CANCELLED"].includes(phase);
  const pendingClarification = isCreating
    || Boolean(run?.id && pendingClarificationRunId === run.id && !isClarificationSettled(run));
  const metricCards = [
    { label: "智能体步骤", value: runMetrics.agentStepCount, icon: Activity },
    { label: "证据来源", value: runMetrics.evidenceCount, icon: Search },
    { label: "质检问题", value: runMetrics.reviewFindingCount, icon: ShieldCheck },
    { label: "引用标记", value: runMetrics.citationMentionCount, icon: BookOpenCheck },
    { label: "结论覆盖", value: formatPercent(runMetrics.claimCoverage), icon: ShieldCheck },
    { label: "结构化完整度", value: formatPercent(runMetrics.schemaCompleteness), icon: BookOpenCheck },
    { label: "打回次数", value: runMetrics.reworkCount, icon: RefreshCw },
    { label: "证据/结论", value: runMetrics.evidencePerClaim, icon: Search },
    { label: "令牌", value: runMetrics.totalTokens, icon: Gauge },
    { label: "耗时", value: formatDuration(runMetrics.totalLatencyMs), icon: Clock3 }
  ];

  const latestImprovement = runMetrics.latestImprovement;
  const improvementRows = latestImprovement ? [
    {
      label: "证据来源",
      before: String(latestImprovement.evidenceBefore),
      after: String(latestImprovement.evidenceAfter),
      deltaValue: latestImprovement.evidenceDelta,
      delta: formatSignedDelta(latestImprovement.evidenceDelta),
      improved: latestImprovement.evidenceDelta > 0
    },
    {
      label: "覆盖缺口",
      before: String(latestImprovement.coverageGapsBefore),
      after: String(latestImprovement.coverageGapsAfter),
      deltaValue: latestImprovement.coverageGapDelta,
      delta: formatSignedDelta(latestImprovement.coverageGapDelta),
      improved: latestImprovement.coverageGapDelta < 0
    },
    {
      label: "质检问题",
      before: String(latestImprovement.findingsBefore),
      after: String(latestImprovement.findingsAfter),
      deltaValue: latestImprovement.findingDelta,
      delta: formatSignedDelta(latestImprovement.findingDelta),
      improved: latestImprovement.findingDelta < 0
    },
    {
      label: "阻断问题",
      before: String(latestImprovement.highFindingsBefore),
      after: String(latestImprovement.highFindingsAfter),
      deltaValue: latestImprovement.highFindingDelta,
      delta: formatSignedDelta(latestImprovement.highFindingDelta),
      improved: latestImprovement.highFindingDelta < 0
    },
    {
      label: "结论覆盖",
      before: formatPercent(latestImprovement.claimCoverageBefore),
      after: formatPercent(latestImprovement.claimCoverageAfter),
      deltaValue: latestImprovement.claimCoverageDelta,
      delta: formatSignedDelta(latestImprovement.claimCoverageDelta, "%"),
      improved: latestImprovement.claimCoverageDelta > 0
    }
  ] : [];

  const backendBadge = backendStatusBadge(backendStatus);
  const mainTabs: Array<SegmentedTabOption<MainView>> = [
    { value: "dag", label: "智能体流程" },
    { value: "report", label: "报告" },
    { value: "research", label: "问卷访谈" },
    { value: "schema", label: "结构化信息" }
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
          <button className="toolbar-button" type="button" onClick={() => setResourcePackOpen(true)}>
            <FolderOpen size={16} /> 用户资源包 · {userResourceSources.length}{processingResourceCount ? ` · 处理中 ${processingResourceCount}` : ""}
          </button>
          <button className="toolbar-button" type="button" onClick={() => setHistoryOpen(true)}>
            <History size={16} /> 历史会话
          </button>
          <button className="toolbar-button" type="button" onClick={handleNewRun}>
            <Plus size={16} /> 新建分析
          </button>
          <StatusBadge label={backendBadge.label} tone={backendBadge.tone} />
          <StatusBadge label={displayRunPhase(String(resolveRunPhase(run)))} tone={statusTone(run?.status)} />
          <button className="icon-button" type="button" onClick={() => refreshRun()} aria-label="刷新任务">
            <RefreshCw size={17} />
          </button>
        </div>
      </header>

      <main className="workspace" style={workspaceStyle} ref={workspaceRef}>
        <aside className="left-rail" ref={leftRailRef}>
          <ScopeConfirmationPanel
            run={run}
            localConfirmed={localScopeConfirmed}
            industry={industry}
            competitors={competitors}
            dimensions={dimensions}
            outputGoal={outputGoal}
            sourceUrls={sourceUrls}
            maxReviewReworkAttempts={maxReviewReworkAttempts}
            onIndustryChange={setIndustry}
            onCompetitorsChange={setCompetitors}
            onDimensionsChange={setDimensions}
            onOutputGoalChange={setOutputGoal}
            onSourceUrlsChange={setSourceUrls}
            onMaxReviewReworkAttemptsChange={setMaxReviewReworkAttempts}
            onApplyClarificationOption={handleApplyClarificationOption}
            onManualEdit={handleManualScopeEdit}
            onCreate={handleCreateRun}
            onReclarify={handleReclarifyScope}
            onConfirm={handleConfirmRequirement}
            onStart={handleStartAnalysis}
            editing={scopeEditMode}
            requiresReclarify={scopeNeedsReclarify}
            onStartEditing={handleStartScopeEdit}
            onCancelEditing={handleCancelScopeEdit}
            processingResourceCount={processingResourceCount}
            creating={pendingClarification}
            busy={isScopeBusy}
            collapsed={collapsedLeftPanels.scope}
            onToggle={() => toggleLeftPanel("scope")}
          />

          <ContextPanel
            runReady={Boolean(run)}
            targetAgent={contextTargetAgent}
            disabled={runMutationDisabled}
            onTargetAgentChange={setContextTargetAgent}
            onSubmit={handleSubmitRerun}
            collapsed={collapsedLeftPanels.context}
            onToggle={() => toggleLeftPanel("context")}
          />

          <CollapsiblePanel
            eyebrow="资料"
            title="补充资料"
            icon={<UploadCloud size={18} />}
            summary={run ? `${run.evidenceSources.length} 个来源` : "等待创建任务"}
            collapsed={collapsedLeftPanels.evidence}
            onToggle={() => toggleLeftPanel("evidence")}
            className="evidence-input-panel"
          >
            <label>
              类型
              <select value={evidenceSourceType} onChange={(event) => setEvidenceSourceType(event.target.value)} disabled={!run || runMutationDisabled}>
                <option value="url">公开 URL</option>
                <option value="interview">访谈</option>
                <option value="survey">问卷</option>
              </select>
            </label>

            {evidenceSourceType === "url" ? (
              <>
                <label>
                  公开 URL
                  <input
                    value={evidenceUrl}
                    onChange={(event) => setEvidenceUrl(event.target.value)}
                    placeholder="请输入完整 http/https 地址，例如 https://www.notion.com/product"
                    disabled={!run || runMutationDisabled}
                  />
                </label>
                <p className="muted-text">一次提交一个公开网页 URL。系统会尝试抓取正文并加入证据链。</p>
                <button className="primary-button" type="button" onClick={handleAddEvidence} disabled={runMutationDisabled || !evidenceUrl.trim()}>
                  <UploadCloud size={15} /> 加入公开来源
                </button>
              </>
            ) : null}

            {evidenceSourceType === "interview" ? (
              <>
                <label>
                  访谈内容
                  <textarea
                    value={evidenceContent}
                    onChange={(event) => setEvidenceContent(event.target.value)}
                    placeholder="请输入一段访谈摘要、用户反馈或原始访谈记录"
                    rows={5}
                    disabled={!run || runMutationDisabled}
                  />
                </label>
                <p className="muted-text">直接粘贴文本即可，系统会把它作为访谈证据并参与后续结构化洞察。</p>
                <button className="primary-button" type="button" onClick={handleAddEvidence} disabled={runMutationDisabled || !evidenceContent.trim()}>
                  <UploadCloud size={15} /> 加入访谈资料
                </button>
              </>
            ) : null}

            {evidenceSourceType === "survey" ? (
              <>
                <p className="muted-text">导入 CSV 或 XLSX 问卷结果文件。每行一份答卷，表头保留题干。</p>
                <button
                  className="primary-button"
                  type="button"
                  onClick={() => surveyImportInputRef.current?.click()}
                  disabled={runMutationDisabled || surveyBusy}
                >
                  <UploadCloud size={15} /> 导入问卷结果
                </button>
                <input
                  ref={surveyImportInputRef}
                  type="file"
                  accept=".csv,.xlsx,text/csv,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                  hidden
                  onChange={(event) => {
                    const file = event.currentTarget.files?.[0];
                    event.currentTarget.value = "";
                    if (file) {
                      void handleImportSurveyResults(file);
                    }
                  }}
                />
              </>
            ) : null}
          </CollapsiblePanel>

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
            <SegmentedTabs ariaLabel="主工作区视图" options={mainTabs} value={mainView} onChange={handleMainViewChange} />

            {mainView === "dag" ? (
              <Suspense fallback={<PanelLoading label="正在加载工作流视图" />}>
                <WorkflowGraph run={run} onSelectAgent={handleOpenAgentTrace} />
              </Suspense>
            ) : null}

            {mainView === "research" ? (
              <div className="tab-content">
                <ResearchDesignPanel
                  questionnaire={run?.researchPackage?.researchPlan?.questionnaire}
                  interviewGuide={run?.researchPackage?.researchPlan?.interviewGuide}
                  interviewInsights={run?.researchPackage?.interviewInsights ?? []}
                  surveyInsights={run?.researchPackage?.surveyInsights ?? []}
                  disabled={runMutationDisabled}
                  busy={surveyBusy}
                  pendingRevision={Boolean(run?.pendingResearchInputRevision)}
                  pendingRevisionReason={run?.pendingResearchInputReason}
                  onDownloadTemplate={handleDownloadSurveyTemplate}
                  onSaveQuestionnaire={handleSaveQuestionnaire}
                  onApplyResearchInputs={handleApplyResearchInputs}
                  onDeleteInsight={handleDeleteResearchInsight}
                  deletingInsightKey={deletingInsightKey}
                />
              </div>
            ) : null}

            {mainView === "report" ? (
              <div className="tab-content">
                <div className="artifact-toolbar">
                  <span>报告产物</span>
                  <select
                    value={reportDisplayArtifact?.id ?? ""}
                    onChange={(event) => {
                      setArtifactLocateRequest(undefined);
                      setSelectedArtifactId(event.target.value);
                      setArtifactPinned(true);
                    }}
                  >
                    {reportArtifacts.map((artifact) => (
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
                    onSelectCitation={handleSelectCitation}
                    locateRequest={artifactLocateRequest}
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
                    selectedClaimRequestId={selectedClaimRequestId}
                    onSelectCitation={handleSelectCitation}
                  />
                </Suspense>
              </div>
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
            summary={pendingClarification ? "澄清执行中" : `${run?.steps.length ?? 0} 个步骤`}
            collapsed={collapsedRightPanels.timeline}
            onToggle={() => toggleRightPanel("timeline")}
          >
            <AgentTimeline
              run={run}
              selectedAgent={selectedAgent}
              onSelectAgent={handleOpenAgentTrace}
              pendingClarification={pendingClarification}
            />
          </CollapsiblePanel>

          <EvidencePanel
            sources={run?.evidenceSources ?? []}
            chunks={run?.evidenceChunks ?? []}
            selectedCitationKey={selectedCitationKey}
            selectedCitationRequestId={selectedCitationRequestId}
            onSelectCitation={handleSelectCitation}
            collapsed={collapsedRightPanels.evidence}
            onToggle={() => toggleRightPanel("evidence")}
          />

          <ReviewPanel
            findings={run?.reviewFindings ?? []}
            decision={run?.reviewDecision}
            status={run?.status}
            workflowTransitions={run?.workflowTransitions}
            maxReviewReworkAttempts={run?.maxReviewReworkAttempts}
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
            summary={`${runMetrics.highFindingCount} 阻断 · ${formatDuration(runMetrics.totalLatencyMs)}`}
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
            <div className="metric-improvement">
              <div className="metric-improvement-header">
                <strong>最近一次重跑改善</strong>
                <span>
                  {latestImprovement
                    ? `${latestImprovement.agentName ? AGENT_LABELS[latestImprovement.agentName] ?? latestImprovement.agentName : "Agent"} · ${latestImprovement.changed ? "有变化" : "无变化"}`
                    : "暂无快照"}
                </span>
              </div>
              {latestImprovement ? (
                <div className="metric-delta-list">
                  {improvementRows.map((row) => {
                    const neutral = row.deltaValue === 0;
                    const Icon = neutral ? RefreshCw : row.deltaValue > 0 ? ArrowUpRight : ArrowDownRight;
                    return (
                      <div className={`metric-delta-row ${row.improved ? "improved" : neutral ? "neutral" : ""}`} key={row.label}>
                        <span>{row.label}</span>
                        <small>{row.before} → {row.after}</small>
                        <strong>
                          <Icon size={14} />
                          {row.delta}
                        </strong>
                      </div>
                    );
                  })}
                </div>
              ) : (
                <p className="metric-empty">重跑后会在这里显示证据、覆盖缺口、质检问题和 结论覆盖的变化。</p>
              )}
            </div>
          </CollapsiblePanel>
        </aside>
      </main>

      <TraceDrawer
        agent={traceDrawerAgent}
        traces={(run?.traces ?? []).filter((trace) => trace.agentName === traceDrawerAgent)}
        onClose={() => {
          setTraceDrawerAgent(null);
          setSelectedAgent(null);
        }}
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
        onDeleteRun={handleDeleteHistoryRun}
        deletingRunId={deletingRunId}
      />
      <ResourcePackDrawer
        open={resourcePackOpen}
        sources={userResourceSources}
        disabled={runMutationDisabled}
        uploading={isUploadingDocument}
        processingCount={processingResourceCount}
        deletingCitationKey={deletingResourceKey}
        file={documentFile}
        inputKey={documentInputKey}
        title={documentTitle}
        sourceType={documentSourceType}
        notes={documentNotes}
        onClose={() => setResourcePackOpen(false)}
        onFileChange={setDocumentFile}
        onTitleChange={setDocumentTitle}
        onSourceTypeChange={setDocumentSourceType}
        onNotesChange={setDocumentNotes}
        onUpload={handleUploadDocument}
        onDelete={handleDeleteUserResource}
        onSelectCitation={handleSelectCitation}
      />
      <DeleteHistoryDialog
        summary={deleteCandidate}
        deleting={Boolean(deleteCandidate && deletingRunId === deleteCandidate.id)}
        onCancel={handleCancelDeleteHistoryRun}
        onConfirm={confirmDeleteHistoryRun}
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

function backendStatusBadge(status: BackendStatus): { label: string; tone: "success" | "running" | "danger" } {
  if (status === "connected") return { label: "后端已连接", tone: "success" };
  if (status === "failed") return { label: "连接失败", tone: "danger" };
  return { label: "连接检测中", tone: "running" };
}

function isReportArtifact(artifact?: AnalysisArtifact) {
  return artifact?.type === "FINAL_REPORT" || artifact?.type === "REPORT_DRAFT";
}

function citationKeyFromFinding(finding: ReviewFinding) {
  return /\[(S\d+)]/.exec(`${finding.message ?? ""} ${finding.excerpt ?? ""}`)?.[1];
}

function locationTypeFromFinding(finding: ReviewFinding, citationKey?: string): ReviewFinding["locationType"] {
  if (finding.locationType) return finding.locationType;
  if (finding.paragraphIndex !== undefined) return "REPORT_PARAGRAPH";
  if (finding.claimId) return "CLAIM";
  if (citationKey) return "EVIDENCE_SOURCE";
  if (finding.artifactId) return "GLOBAL_REPORT";
  if (finding.excerpt) return "REPORT_PARAGRAPH";
  return undefined;
}

function reportArtifactForFinding(
  finding: ReviewFinding,
  reportArtifacts: AnalysisArtifact[],
  reportDisplayArtifact?: AnalysisArtifact
) {
  if (finding.artifactId) {
    const matchedArtifact = reportArtifacts.find((artifact) => artifact.id === finding.artifactId);
    if (matchedArtifact) return matchedArtifact;
  }
  return reportDisplayArtifact ?? reportArtifacts.at(-1);
}

function readScopeDraft(): ScopeDraft | null {
  try {
    const raw = window.localStorage.getItem(SCOPE_DRAFT_STORAGE_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as Partial<ScopeDraft>;
    return normalizeScopeDraft(parsed);
  } catch {
    return null;
  }
}

function writeScopeDraft(draft: ScopeDraft) {
  if (!hasScopeDraftContent(draft)) {
    removeScopeDraft();
    return;
  }
  try {
    window.localStorage.setItem(SCOPE_DRAFT_STORAGE_KEY, JSON.stringify(normalizeScopeDraft(draft)));
  } catch {
    // Draft persistence is a convenience; storage failures should not interrupt the form.
  }
}

function removeScopeDraft() {
  try {
    window.localStorage.removeItem(SCOPE_DRAFT_STORAGE_KEY);
  } catch {
    // Ignore unavailable storage.
  }
}

function normalizeScopeDraft(draft: Partial<ScopeDraft>): ScopeDraft {
  return {
    industry: draft.industry ?? "",
    competitors: draft.competitors ?? "",
    dimensions: draft.dimensions ?? "",
    outputGoal: draft.outputGoal ?? "",
    sourceUrls: draft.sourceUrls ?? "",
    maxReviewReworkAttempts: Number.isFinite(draft.maxReviewReworkAttempts) ? Number(draft.maxReviewReworkAttempts) : 1
  };
}

function hasScopeDraftContent(draft: ScopeDraft) {
  return Boolean(
    draft.industry.trim()
    || draft.competitors.trim()
    || draft.dimensions.trim()
    || draft.outputGoal.trim()
    || draft.sourceUrls.trim()
    || draft.maxReviewReworkAttempts !== 1
  );
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

function withOptimisticRerunStep(run: AnalysisRun | null, agentName: AgentName): AnalysisRun | null {
  if (!run) return run;
  const latestSameAgent = [...run.steps].reverse().find((step) => step.agentName === agentName);
  if (latestSameAgent?.status === "RUNNING") {
    return run;
  }
  const startedAt = new Date().toISOString();
  const step: AgentStep = {
    id: `local-rerun-${agentName}-${Date.now()}`,
    agentName,
    title: AGENT_LABELS[agentName] ?? agentName,
    status: "RUNNING",
    inputSummary: `手动重跑：正在从 ${AGENT_LABELS[agentName] ?? agentName} 继续重跑下游链路`,
    startedAt,
    issues: []
  };
  return {
    ...run,
    steps: [...run.steps, step],
    updatedAt: startedAt
  };
}

function latestRecommendedActionSince(run: AnalysisRun, startIndex: number, fallback: string) {
  const actions = run.recommendedActions ?? [];
  for (let index = actions.length - 1; index >= Math.max(0, startIndex); index -= 1) {
    const action = actions[index]?.trim();
    if (action) return action;
  }
  return fallback;
}

function withOptimisticClarifierStep(run: AnalysisRun, inputSummary: string): AnalysisRun {
  const latestClarifierStep = [...run.steps].reverse().find((step) => step.agentName === "CLARIFIER");
  if (latestClarifierStep?.id.startsWith("local-clarifier-step-") && latestClarifierStep.status === "RUNNING") {
    return run;
  }
  const startedAt = new Date().toISOString();
  const stepId = `local-clarifier-step-${Date.now()}`;
  return {
    ...run,
    status: "AWAITING_CONFIRMATION",
    steps: [...run.steps, {
      id: stepId,
      agentName: "CLARIFIER",
      title: AGENT_LABELS.CLARIFIER,
      status: "RUNNING",
      inputSummary,
      startedAt,
      issues: []
    }],
    traces: [...(run.traces ?? []), {
      id: `local-clarifier-trace-${Date.now()}`,
      stepId,
      agentName: "CLARIFIER",
      status: "RUNNING",
      inputSnapshot: inputSummary,
      processSnapshot: "Clarifier 正在根据左侧最新范围输入重新整理澄清草稿。",
      decisionSummary: "等待 Clarifier 输出新的澄清问题与结构化范围建议。",
      modelName: "Clarifier",
      startedAt,
      createdAt: startedAt
    }],
    updatedAt: startedAt
  };
}

function buildClarifierScopeInputSummary(
  industry: string,
  outputGoal: string,
  competitors: string[],
  dimensions: string[],
  sourceUrls: string[]
) {
  return [
    `行业方向: ${industry.trim() || "未填写"}`,
    `报告用途: ${outputGoal.trim() || "未填写"}`,
    `竞品列表: ${competitors.length ? competitors.join("、") : "未填写"}`,
    `分析维度: ${dimensions.length ? dimensions.join("、") : "未填写"}`,
    `公开来源 URL: ${sourceUrls.length ? sourceUrls.join("、") : "未填写"}`
  ].join("\n");
}

function mergeRunSnapshotWithLocalRunningStep(current: AnalysisRun | null, snapshot: AnalysisRun): AnalysisRun {
  if (!current || current.id !== snapshot.id) return snapshot;
  const localRunningSteps = current.steps.filter((step) =>
    (step.id.startsWith("local-rerun-") || step.id.startsWith("local-clarifier-step-")) && step.status === "RUNNING");
  const localRunningTraces = (current.traces ?? []).filter((trace) =>
    trace.id.startsWith("local-clarifier-trace-") && trace.status === "RUNNING");
  if (!localRunningSteps.length && !localRunningTraces.length) return snapshot;
  const preservedSteps = localRunningSteps.filter((localStep) => {
    const localStartedAt = timestampValue(localStep.startedAt);
    const latestSameAgent = [...snapshot.steps]
      .reverse()
      .find((step) => step.agentName === localStep.agentName);
    if (!latestSameAgent) {
      return true;
    }
    const latestStartedAt = timestampValue(latestSameAgent.startedAt);
    return latestStartedAt > 0 && localStartedAt > 0 && latestStartedAt < localStartedAt;
  });
  const preservedTraces = localRunningTraces.filter((localTrace) => {
    const localStartedAt = timestampValue(localTrace.startedAt ?? localTrace.createdAt);
    const latestSameAgent = [...(snapshot.traces ?? [])]
      .reverse()
      .find((trace) => trace.agentName === localTrace.agentName);
    if (!latestSameAgent) {
      return true;
    }
    const latestStartedAt = timestampValue(latestSameAgent.startedAt ?? latestSameAgent.createdAt);
    return latestStartedAt > 0 && localStartedAt > 0 && latestStartedAt < localStartedAt;
  });
  if (!preservedSteps.length && !preservedTraces.length) return snapshot;
  return {
    ...snapshot,
    steps: [...snapshot.steps, ...preservedSteps],
    traces: [...(snapshot.traces ?? []), ...preservedTraces]
  };
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

function isUserResourceSource(source: { url?: string }) {
  const url = source.url ?? "";
  return url.startsWith("user-document://");
}

function isProcessingUserResource(source: { url?: string; ingestionStatus?: string }) {
  return isUserResourceSource(source) && source.ingestionStatus === "PROCESSING";
}

function timestampValue(value?: string) {
  if (!value) return 0;
  const time = new Date(value).getTime();
  return Number.isNaN(time) ? 0 : time;
}

function formatSignedDelta(value: number, suffix = "") {
  if (value === 0) return `0${suffix}`;
  return `${value > 0 ? "+" : ""}${value}${suffix}`;
}

function safeParseEvent(event: MessageEvent<string>): RunEvent | null {
  try {
    return JSON.parse(event.data) as RunEvent;
  } catch {
    return null;
  }
}

function safeParseRunSnapshot(event: MessageEvent<string>): AnalysisRun | null {
  try {
    return JSON.parse(event.data) as AnalysisRun;
  } catch {
    return null;
  }
}

function isCurrentWorkspaceRun(runId: string) {
  return window.localStorage.getItem(CURRENT_RUN_STORAGE_KEY) === runId;
}

function isClarificationSettled(run: AnalysisRun) {
  if (run.status === "FAILED" || run.status === "CANCELLED") {
    return true;
  }
  // 只看最后一个 CLARIFIER 步骤的状态：重新澄清时会产生新的 RUNNING 步骤，
  // 旧的 SUCCEEDED 步骤不应让系统误判为"澄清已完成"。
  const clarifierSteps = run.steps.filter((step) => step.agentName === "CLARIFIER");
  if (clarifierSteps.length === 0) return false;
  const latest = clarifierSteps[clarifierSteps.length - 1];
  return latest.status === "SUCCEEDED" || latest.status === "FAILED" || latest.status === "CANCELLED";
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
