import type {
  AddContextRequest,
  AddUserEvidenceRequest,
  AgentName,
  AnalysisRun,
  AnalysisRunMetrics,
  AnalysisRunSummary,
  CreateAnalysisRunRequest,
  UpdateAnalysisRequirementRequest,
  UploadDocumentRequest
} from "./types";

export async function listRunSummaries(): Promise<AnalysisRunSummary[]> {
  return requestJson("/api/analysis-runs/summaries");
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

export async function deleteRun(runId: string): Promise<void> {
  await requestVoid(`/api/analysis-runs/${runId}`, {
    method: "DELETE"
  });
}

export async function getRunMetrics(runId: string): Promise<AnalysisRunMetrics> {
  return requestJson(`/api/analysis-runs/${runId}/metrics`);
}

export async function updateRequirement(runId: string, payload: UpdateAnalysisRequirementRequest): Promise<AnalysisRun> {
  return requestJson(`/api/analysis-runs/${runId}/requirement`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload)
  });
}

export async function clarifyRequirement(runId: string, payload: UpdateAnalysisRequirementRequest): Promise<AnalysisRun> {
  return requestJson(`/api/analysis-runs/${runId}/clarify`, {
    method: "POST",
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

export async function uploadDocument(runId: string, payload: UploadDocumentRequest): Promise<AnalysisRun> {
  const body = new FormData();
  body.append("file", payload.file);
  if (payload.title) body.append("title", payload.title);
  if (payload.sourceType) body.append("sourceType", payload.sourceType);
  body.append("sensitive", String(Boolean(payload.sensitive)));
  if (payload.notes) body.append("notes", payload.notes);
  return requestJson(`/api/analysis-runs/${runId}/documents`, {
    method: "POST",
    body
  });
}

export async function deleteDocument(runId: string, citationKey: string): Promise<AnalysisRun> {
  return requestJson(`/api/analysis-runs/${runId}/documents/${encodeURIComponent(citationKey)}`, {
    method: "DELETE"
  });
}

export async function rerunAgent(runId: string, agentName: AgentName): Promise<AnalysisRun> {
  return requestJson(`/api/analysis-runs/${runId}/agents/${agentName}/rerun`, {
    method: "POST"
  });
}

async function requestJson<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, init);
  await ensureOk(response);
  return response.json() as Promise<T>;
}

async function requestVoid(path: string, init?: RequestInit): Promise<void> {
  const response = await fetch(path, init);
  await ensureOk(response);
}

async function ensureOk(response: Response): Promise<void> {
  if (!response.ok) {
    let message = `HTTP ${response.status}`;
    const text = await response.text();
    if (text) {
      try {
        const body = JSON.parse(text);
        message = body.message || body.error || text;
      } catch {
        message = text;
      }
    }
    throw new Error(message);
  }
}
