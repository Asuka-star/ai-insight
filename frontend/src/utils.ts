import type { AgentName, AgentStep, AnalysisArtifact, AnalysisRun, AnalysisStatus } from "./types";
import { AGENTS } from "./constants";

export function splitList(value: string): string[] {
  return value
    .split(/[,，、\n]/)
    .map((item) => item.trim())
    .filter(Boolean);
}

export function latestStepByAgent(run?: AnalysisRun): Map<AgentName, AgentStep[]> {
  const map = new Map<AgentName, AgentStep[]>();
  for (const agent of AGENTS) {
    map.set(agent, []);
  }
  for (const step of run?.steps ?? []) {
    const steps = map.get(step.agentName) ?? [];
    steps.push(step);
    map.set(step.agentName, steps);
  }
  return map;
}

export function isActiveRun(run?: AnalysisRun | null): boolean {
  return run?.status === "PENDING"
    || run?.status === "RUNNING"
    || run?.status === "REVIEWING"
    || run?.status === "REVISING"
    // AWAITING_CONFIRMATION 下也可能有后台文档解析；纳入轮询，防止资源包状态卡住。
    || Boolean(run?.evidenceSources?.some((source) => source.ingestionStatus === "PROCESSING"))
    || Boolean(run?.steps?.some((step) => step.status === "RUNNING"));
}

export function resolveRunPhase(run?: AnalysisRun | null): AnalysisStatus | string {
  if (!run) return "EMPTY";
  if (run.phase) return run.phase;
  if (
    run.status === "AWAITING_CONFIRMATION"
    || (run.status === "PENDING" && run.clarificationDraft && !run.clarificationDraft.confirmed)
  ) {
    return "AWAITING_CONFIRMATION";
  }
  return run.status;
}

export function displayRunPhase(status?: string): string {
  const labels: Record<string, string> = {
    EMPTY: "未创建",
    DRAFT: "草稿",
    AWAITING_CONFIRMATION: "待确认",
    PENDING: "待执行",
    RUNNING: "运行中",
    REVIEWING: "复核中",
    NEEDS_USER_INPUT: "待补充",
    REVISING: "修订中",
    SUCCEEDED: "已完成",
    FAILED: "失败",
    CANCELLED: "已取消"
  };
  return labels[status ?? ""] ?? (status || "未知状态");
}

export function statusClass(status?: string): string {
  return (status || "pending").toLowerCase();
}

export function formatTime(value?: string): string {
  if (!value) return "-";
  return new Intl.DateTimeFormat("zh-CN", {
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit"
  }).format(new Date(value));
}

export function findDefaultArtifact(artifacts: AnalysisArtifact[]): AnalysisArtifact | undefined {
  // Prefer historical FINAL_REPORT when opening old runs; new runs usually fall back to REPORT_DRAFT.
  return [...artifacts].reverse().find((artifact) => artifact.type === "FINAL_REPORT") ?? artifacts.at(-1);
}

export function countCitedClaims(run?: AnalysisRun | null): number {
  return (run?.artifacts ?? [])
    .filter((artifact) => artifact.type === "FINAL_REPORT" || artifact.type === "REPORT_DRAFT")
    .reduce((count, artifact) => count + (artifact.content.match(/\[S\d+]/g)?.length ?? 0), 0);
}

export function calculateRunMetrics(run?: AnalysisRun | null) {
  // Local fallback for older/failed metrics endpoints; backend metrics are authoritative when available.
  const claims = run?.claims ?? [];
  const profiles = run?.competitorProfiles ?? [];
  const traces = run?.traces ?? [];
  const citedClaims = claims.filter((claim) => claim.evidenceIds?.length).length;
  const completeProfiles = profiles.filter((profile) =>
    Boolean(profile.positioning)
      && Boolean(profile.featureTree?.roots?.length)
      && Boolean(profile.pricingModel?.strategySummary)
      && Boolean(profile.personas?.length)
      && Boolean(profile.evidenceIds?.length)
  ).length;
  const citationMentions = countCitedClaims(run);
  const reworkCount = (run?.workflowTransitions ?? []).filter((transition) => transition.route && transition.route !== "finish").length;
  const totalTokens = traces.reduce((sum, trace) => sum + (trace.totalTokens ?? 0), 0);
  const totalLatencyMs = traces.reduce((sum, trace) => sum + (trace.latencyMs ?? 0), 0);

  return {
    claimCoverage: percent(citedClaims, claims.length),
    schemaCompleteness: percent(completeProfiles, profiles.length),
    citationMentions,
    reworkCount,
    totalTokens,
    totalLatencyMs,
    evidencePerClaim: claims.length ? round((run?.evidenceSources.length ?? 0) / claims.length, 1) : 0
  };
}

export function formatPercent(value: number): string {
  return `${value}%`;
}

export function formatDuration(ms: number): string {
  if (!ms) return "0ms";
  if (ms < 1000) return `${ms}ms`;
  return `${round(ms / 1000, 1)}s`;
}

function percent(part: number, total: number): number {
  if (!total) return 0;
  return Math.round((part / total) * 100);
}

function round(value: number, precision: number): number {
  const factor = 10 ** precision;
  return Math.round(value * factor) / factor;
}
