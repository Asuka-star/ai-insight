import type { AgentName, AgentStep, AnalysisArtifact, AnalysisRun } from "./types";
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
  return run?.status === "PENDING" || run?.status === "RUNNING";
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
