export type AnalysisStatus =
  | "DRAFT"
  | "AWAITING_CONFIRMATION"
  | "PENDING"
  | "RUNNING"
  | "REVIEWING"
  | "NEEDS_USER_INPUT"
  | "REVISING"
  | "SUCCEEDED"
  | "FAILED"
  | "CANCELLED";
export type StepStatus = "PENDING" | "RUNNING" | "SUCCEEDED" | "FAILED" | "CANCELLED";
export type AgentName =
  | "CLARIFIER"
  | "RESEARCHER"
  | "EXTRACTOR"
  | "ANALYST"
  | "WRITER"
  | "REVIEWER"
  | "FINALIZER";

export type ArtifactType =
  | "CLARIFICATION_BRIEF"
  | "SOURCE_LIST"
  | "RESEARCH_PLAN"
  | "COMPETITOR_PROFILE"
  | "COMPETITIVE_MATRIX"
  | "SWOT_ANALYSIS"
  | "REPORT_DRAFT"
  | "REVIEW_FINDINGS"
  | "FINALIZATION_NOTE"
  | "FINAL_REPORT";

export interface CreateAnalysisRunRequest {
  prompt: string;
  industry?: string;
  competitors: string[];
  dimensions: string[];
  sourcePreferences: string[];
  sourceUrls?: string[];
  outputGoal?: string;
  maxReviewReworkAttempts?: number;
}

export interface AnalysisRequirement {
  originalPrompt?: string;
  industry?: string;
  competitors: string[];
  dimensions: string[];
  sourcePreferences: string[];
  sourceUrls?: string[];
  outputGoal?: string;
}

export interface AnalysisRunSummary {
  id: string;
  status: AnalysisStatus;
  industry?: string;
  competitors: string[];
  outputGoal?: string;
  originalPrompt?: string;
  evidenceCount: number;
  artifactCount: number;
  findingCount: number;
  stepCount: number;
  createdAt?: string;
  updatedAt?: string;
}

export interface AnalysisRunMetrics {
  runId: string;
  agentStepCount: number;
  evidenceCount: number;
  reviewFindingCount: number;
  citationMentionCount: number;
  claimCoverage: number;
  schemaCompleteness: number;
  reworkCount: number;
  evidencePerClaim: number;
  totalTokens: number;
  totalLatencyMs: number;
  highFindingCount: number;
  mediumFindingCount: number;
  lowFindingCount: number;
}

export interface UpdateAnalysisRequirementRequest {
  industry?: string;
  competitors: string[];
  dimensions: string[];
  sourcePreferences: string[];
  sourceUrls?: string[];
  outputGoal?: string;
  maxReviewReworkAttempts?: number;
}

export interface ClarificationOption {
  label: string;
  description?: string;
  values: string[];
  recommended?: boolean;
}

export interface ClarificationItem {
  field: "industry" | "competitors" | "dimensions" | "sourcePreferences" | "sourceUrls" | "outputGoal" | string;
  question: string;
  reason?: string;
  required?: boolean;
  options: ClarificationOption[];
  selectedValues?: string[];
}

export interface ClarificationDraft {
  industry?: string;
  competitors: string[];
  dimensions: string[];
  sourcePreferences: string[];
  sourceUrls?: string[];
  outputGoal?: string;
  clarificationQuestions: string[];
  clarificationItems?: ClarificationItem[];
  confirmed: boolean;
}

export type ContextIntent = "ADJUST_SCOPE" | "ADD_EVIDENCE" | "REQUEST_RERUN" | "REVISE_REPORT" | "COMMENT";
export type ContextRole = "USER" | "SYSTEM" | "AGENT";

export interface AnalysisContextMessage {
  id: string;
  role: ContextRole;
  intent: ContextIntent;
  content: string;
  targetAgent?: AgentName;
  createdAt?: string;
}

export interface AddContextRequest {
  content: string;
  intent: ContextIntent;
  targetAgent?: AgentName;
}

export interface AddUserEvidenceRequest {
  title: string;
  sourceType?: "url" | "interview" | "survey" | "note" | string;
  content: string;
  url?: string;
  sensitive?: boolean;
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
  sourceType?: string;
  collectionStatus?: string;
  freshness?: string;
  sourceQuality?: string;
  failureReason?: string;
  contentHash?: string;
  cacheHit?: boolean;
  snippet: string;
  complianceNote?: string;
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
  artifactId?: string;
  claimId?: string;
  citationKey?: string;
  paragraphIndex?: number;
  excerpt?: string;
}

export interface AgentTrace {
  id: string;
  stepId?: string;
  agentName: AgentName;
  status?: StepStatus;
  prompt?: string;
  inputSnapshot?: string;
  outputSnapshot?: string;
  processSnapshot?: string;
  rawModelOutput?: string;
  decisionSummary?: string;
  modelName?: string;
  fallbackUsed?: boolean;
  promptTokens?: number;
  completionTokens?: number;
  totalTokens?: number;
  latencyMs?: number;
  errorMessage?: string;
  startedAt?: string;
  completedAt?: string;
  createdAt?: string;
}

export interface ReviewDecision {
  action?: string;
  targetAgent?: AgentName;
  reason?: string;
  affectedClaimIds?: string[];
  requiredEvidenceTypes?: string[];
  findingCategories?: string[];
  blockingFindingIds?: string[];
  repairInstructions?: string[];
  repairTasks?: ReviewRepairTask[];
  repairScopeSummary?: string;
  decidedAt?: string;
}

export interface ReviewRepairTask {
  id?: string;
  targetAgent?: AgentName;
  findingId?: string;
  artifactId?: string;
  claimId?: string;
  citationKey?: string;
  paragraphIndex?: number;
  excerpt?: string;
  currentText?: string;
  category?: string;
  action?: string;
  instruction?: string;
  expectedFix?: string;
  acceptanceCriteria?: string;
  requiredEvidenceTypes?: string[];
}

export interface ResearchPackage {
  sources: EvidenceSource[];
  missingEvidenceTypes: string[];
  actualSearchQueries?: string[];
  researchPlan?: ResearchPlan;
  interviewInsights?: InterviewInsight[];
  collectedAt?: string;
}

export interface ResearchPlan {
  objective?: string;
  evidenceGaps: string[];
  searchQueries?: string[];
  publicSourceTasks: ResearchTask[];
  questionnaire?: Questionnaire;
  interviewGuide?: InterviewGuide;
}

export interface ResearchTask {
  type?: string;
  target?: string;
  rationale?: string;
  status?: string;
}

export interface Questionnaire {
  title?: string;
  targetRespondents?: string;
  recommendedSampleSize?: string;
  questions: SurveyQuestion[];
}

export interface SurveyQuestion {
  dimension?: string;
  question?: string;
  options: string[];
}

export interface InterviewGuide {
  title?: string;
  targetRoles: string[];
  questions: string[];
  probingQuestions: string[];
}

export interface InterviewInsight {
  id?: string;
  evidenceId?: string;
  sourceTitle?: string;
  intervieweeRole?: string;
  scenario?: string;
  painPoints: string[];
  positiveSignals: string[];
  negativeSignals: string[];
  buyingConcerns: string[];
  competitorMentions: string[];
  relatedDimensions: string[];
  directQuotes: string[];
  confidence?: string;
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
  trigger?: string;
  resolutionStatus?: string;
  blockingFindingIds?: string[];
  blockingFindingSignatures?: string[];
  resolvedFindingSignatures?: string[];
  unresolvedFindingSignatures?: string[];
  createdAt?: string;
}

export interface AnalysisRun {
  id: string;
  status: AnalysisStatus;
  phase?: AnalysisStatus | string;
  requirement?: AnalysisRequirement;
  clarificationDraft?: ClarificationDraft;
  contextMessages?: AnalysisContextMessage[];
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
  maxReviewReworkAttempts?: number;
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
