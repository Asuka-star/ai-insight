export type AnalysisStatus = "PENDING" | "RUNNING" | "SUCCEEDED" | "FAILED";
export type StepStatus = "PENDING" | "RUNNING" | "SUCCEEDED" | "FAILED";
export type AgentName =
  | "CLARIFIER"
  | "RESEARCHER"
  | "EXTRACTOR"
  | "ANALYST"
  | "WRITER"
  | "REVIEWER"
  | "REVISION";

export type ArtifactType =
  | "CLARIFICATION_BRIEF"
  | "SOURCE_LIST"
  | "COMPETITOR_PROFILE"
  | "COMPETITIVE_MATRIX"
  | "REPORT_DRAFT"
  | "REVIEW_FINDINGS"
  | "REPORT_REVISION"
  | "FINAL_REPORT";

export interface CreateAnalysisRunRequest {
  prompt: string;
  industry?: string;
  competitors: string[];
  dimensions: string[];
  sourcePreferences: string[];
}

export interface AnalysisRequirement {
  originalPrompt?: string;
  industry?: string;
  competitors: string[];
  dimensions: string[];
  sourcePreferences: string[];
}

export interface AgentStep {
  id: string;
  agentName: AgentName;
  title: string;
  status: StepStatus;
  inputSummary?: string;
  outputSummary?: string;
  startedAt?: string;
  completedAt?: string;
  issues: string[];
}

export interface EvidenceSource {
  id: string;
  citationKey: string;
  title: string;
  url: string;
  snippet: string;
  retrievedAt?: string;
}

export interface AnalysisArtifact {
  id: string;
  type: ArtifactType;
  title: string;
  content: string;
  version: number;
  citationKeys: string[];
  createdAt?: string;
}

export interface ReviewFinding {
  id: string;
  severity: "HIGH" | "MEDIUM" | "LOW";
  category: string;
  message: string;
  recommendation: string;
}

export interface AgentTrace {
  id: string;
  agentName: AgentName;
  prompt?: string;
  inputSnapshot?: string;
  outputSnapshot?: string;
  decisionSummary?: string;
  modelName?: string;
  promptTokens?: number;
  completionTokens?: number;
  latencyMs?: number;
  createdAt?: string;
}

export interface ReviewDecision {
  action?: string;
  targetAgent?: AgentName;
  reason?: string;
  affectedClaimIds?: string[];
  requiredEvidenceTypes?: string[];
  decidedAt?: string;
}

export interface ResearchPackage {
  sources: EvidenceSource[];
  missingEvidenceTypes: string[];
  collectedAt?: string;
}

export interface FeatureNode {
  name?: string;
  description?: string;
  children: FeatureNode[];
  evidenceIds: string[];
}

export interface FeatureTree {
  productName?: string;
  roots: FeatureNode[];
}

export interface PricingPlan {
  name?: string;
  priceText?: string;
  billingCycle?: string;
  targetSegment?: string;
  includedFeatures: string[];
  evidenceIds: string[];
}

export interface PricingModel {
  hasFreePlan: boolean;
  strategySummary?: string;
  plans: PricingPlan[];
  evidenceIds: string[];
}

export interface UserPersona {
  name?: string;
  segment?: string;
  companySize?: string;
  jobsToBeDone: string[];
  painPoints: string[];
  buyingConcerns: string[];
  evidenceIds: string[];
}

export interface CompetitorProfile {
  productName?: string;
  companyName?: string;
  positioning?: string;
  targetUsers: string[];
  featureTree: FeatureTree;
  pricingModel: PricingModel;
  personas: UserPersona[];
  strengths: string[];
  weaknesses: string[];
  evidenceIds: string[];
}

export interface AnalysisClaim {
  id: string;
  type?: string;
  content?: string;
  confidence?: string;
  generatedBy?: string;
  competitorNames: string[];
  evidenceIds: string[];
}

export interface WorkflowTransition {
  id: string;
  sourceNode?: string;
  targetNode?: string;
  route?: string;
  reviewAction?: string;
  reason?: string;
  attempt: number;
  createdAt?: string;
}

export interface AnalysisRun {
  id: string;
  status: AnalysisStatus;
  requirement?: AnalysisRequirement;
  steps: AgentStep[];
  evidenceSources: EvidenceSource[];
  artifacts: AnalysisArtifact[];
  reviewFindings: ReviewFinding[];
  recommendedActions: string[];
  researchPackage?: ResearchPackage;
  competitorProfiles?: CompetitorProfile[];
  claims?: AnalysisClaim[];
  traces?: AgentTrace[];
  workflowTransitions?: WorkflowTransition[];
  reviewDecision?: ReviewDecision;
  errorMessage?: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface RunEvent {
  runId: string;
  type: string;
  message: string;
  occurredAt?: string;
}
