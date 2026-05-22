import type {
  AddContextRequest,
  AddUserEvidenceRequest,
  AgentName,
  AnalysisRun,
  CreateAnalysisRunRequest,
  UpdateAnalysisRequirementRequest
} from "./types";

export async function listRuns(): Promise<AnalysisRun[]> {
  return requestJson("/api/analysis-runs");
}

export async function createRun(payload: CreateAnalysisRunRequest): Promise<AnalysisRun> {
  return requestJson("/api/analysis-runs", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload)
  });
}

export async function getRun(runId: string): Promise<AnalysisRun> {
  return requestJson(`/api/analysis-runs/${runId}`);
}

export async function updateRequirement(runId: string, payload: UpdateAnalysisRequirementRequest): Promise<AnalysisRun> {
  return requestJson(`/api/analysis-runs/${runId}/requirement`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload)
  });
}

export async function startAnalysis(runId: string): Promise<AnalysisRun> {
  return requestJson(`/api/analysis-runs/${runId}/start`, {
    method: "POST"
  });
}

export async function addContext(runId: string, payload: AddContextRequest): Promise<AnalysisRun> {
  return requestJson(`/api/analysis-runs/${runId}/context`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload)
  });
}

export async function addEvidence(runId: string, payload: AddUserEvidenceRequest): Promise<AnalysisRun> {
  return requestJson(`/api/analysis-runs/${runId}/evidence`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload)
  });
}

export async function rerunAgent(runId: string, agentName: AgentName): Promise<AnalysisRun> {
  return requestJson(`/api/analysis-runs/${runId}/agents/${agentName}/rerun`, {
    method: "POST"
  });
}

export async function getWorkflowMermaid(): Promise<string> {
  const response = await fetch("/api/analysis-runs/workflow/mermaid");
  if (!response.ok) {
    throw new Error(`HTTP ${response.status}`);
  }
  return response.text();
}

async function requestJson<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, init);
  if (!response.ok) {
    let message = `HTTP ${response.status}`;
    try {
      const body = await response.json();
      message = body.message || body.error || message;
    } catch {
      message = await response.text();
    }
    throw new Error(message);
  }
  return response.json() as Promise<T>;
}
