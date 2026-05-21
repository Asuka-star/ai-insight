import type { AgentName, AgentStep, AnalysisArtifact, AnalysisRun, AnalysisStatus } from "./types";
import { AGENTS } from "./constants";

export function splitList(value: string): string[] {
  return value
    .split(/[,，\n]/)
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
  return run?.status === "PENDING" || run?.status === "RUNNING" || run?.status === "CLARIFYING" || run?.status === "REVIEWING" || run?.status === "REVISING";
}

export function resolveRunPhase(run?: AnalysisRun | null): AnalysisStatus | string {
  if (!run) return "EMPTY";
  if (run.phase) return run.phase;
  if (run.status === "PENDING" && (run.clarificationDraft || !run.steps?.length)) {
    return "AWAITING_CONFIRMATION";
  }
  return run.status;
}

export function displayRunPhase(status?: string): string {
  const labels: Record<string, string> = {
    EMPTY: "未创建",
    DRAFT: "草稿",
    CLARIFYING: "澄清中",
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
  return [...artifacts].reverse().find((artifact) => artifact.type === "FINAL_REPORT") ?? artifacts.at(-1);
}

export function countCitedClaims(run?: AnalysisRun | null): number {
  return (run?.artifacts ?? [])
    .filter((artifact) => artifact.type === "FINAL_REPORT" || artifact.type === "REPORT_DRAFT")
    .reduce((count, artifact) => count + (artifact.content.match(/\[S\d+]/g)?.length ?? 0), 0);
}
