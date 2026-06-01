import { useState } from "react";
import { AlertTriangle, CheckCircle2, ChevronDown, LocateFixed, RefreshCw, ShieldCheck } from "lucide-react";
import type { AgentName, AnalysisStatus, ReviewDecision, ReviewFinding, WorkflowTransition } from "../types";
import { AGENT_LABELS } from "../constants";
import { CollapsiblePanel } from "./CollapsiblePanel";

interface ReviewPanelProps {
  findings: ReviewFinding[];
  decision?: ReviewDecision;
  status?: AnalysisStatus;
  workflowTransitions?: WorkflowTransition[];
  maxReviewReworkAttempts?: number;
  onRerunTarget: (agent: AgentName) => void;
  onLocateFinding?: (finding: ReviewFinding) => void;
  disabled?: boolean;
  collapsed: boolean;
  onToggle: () => void;
}

const TEXT = {
  eyebrow: "\u590d\u6838",
  title: "\u8d28\u68c0\u4e0e\u6253\u56de",
  issue: "\u4e2a\u95ee\u9898",
  credibility: "\u53ef\u4fe1\u5ea6\u72b6\u6001",
  blocked: "\u963b\u65ad",
  suggestion: "\u5efa\u8bae",
  manualReview: "\u590d\u6838",
  noTarget: "\u65e0\u9700\u6253\u56de",
  waitingReason: "\u7b49\u5f85\u590d\u6838 Agent \u7ed9\u51fa\u7ed3\u6784\u5316\u51b3\u7b56",
  affectedClaim: "\u5f71\u54cd Claim",
  noClaim: "\u65e0\u6307\u5b9a Claim",
  requiredEvidence: "\u9700\u8865\u8bc1\u636e",
  noEvidenceType: "\u65e0\u6307\u5b9a\u8bc1\u636e\u7c7b\u578b",
  decisionTime: "\u51b3\u7b56\u65f6\u95f4",
  waitingRecord: "\u7b49\u5f85\u8bb0\u5f55",
  noHighRisk: "\u6682\u65e0\u9ad8\u98ce\u9669\u8d28\u68c0\u95ee\u9898",
  paragraph: "\u6bb5\u843d",
  excerpt: "\u6458\u5f55",
  expand: "\u5c55\u5f00",
  collapse: "\u6536\u8d77",
  more: "\u7b49",
  item: "\u9879"
};

const REVIEW_SEVERITIES = ["HIGH", "MEDIUM", "LOW"] as const;

export function ReviewPanel({
  findings,
  decision,
  status,
  workflowTransitions,
  maxReviewReworkAttempts,
  onRerunTarget,
  onLocateFinding,
  disabled,
  collapsed,
  onToggle
}: ReviewPanelProps) {
  const groupedFindings = groupFindings(findings);
  const decisionAction = decision?.action || "PASS";
  const targetAgent = decision?.targetAgent;
  const quality = qualityProfile(groupedFindings);
  const reworkLimit = reworkLimitProfile(status, decision, workflowTransitions, maxReviewReworkAttempts);
  const [collapsedSeverityGroups, setCollapsedSeverityGroups] = useState<Record<ReviewFinding["severity"], boolean>>({
    HIGH: false,
    MEDIUM: false,
    LOW: false
  });

  function toggleSeverityGroup(severity: ReviewFinding["severity"]) {
    setCollapsedSeverityGroups((current) => ({
      ...current,
      [severity]: !current[severity]
    }));
  }

  return (
    <CollapsiblePanel
      eyebrow={TEXT.eyebrow}
      title={TEXT.title}
      icon={findings.length ? <AlertTriangle size={18} /> : <ShieldCheck size={18} />}
      summary={`${findings.length} ${TEXT.issue} \u00b7 ${decisionActionLabel(decisionAction)}`}
      collapsed={collapsed}
      onToggle={onToggle}
    >
      <div className={`credibility-card ${quality.tone}`}>
        <div>
          <span>{TEXT.credibility}</span>
          <strong>{quality.label}</strong>
        </div>
        <p>{quality.description}</p>
        <div className="credibility-counts" aria-label="\u8d28\u68c0\u5206\u5c42\u7edf\u8ba1">
          <span>{groupedFindings.HIGH.length} {TEXT.blocked}</span>
          <span>{groupedFindings.MEDIUM.length} {TEXT.suggestion}</span>
          <span>{groupedFindings.LOW.length} {TEXT.manualReview}</span>
        </div>
      </div>
      {decision ? (
        <div className={`decision-box ${decisionClass(decisionAction, Boolean(reworkLimit))}`}>
          <div className="decision-header">
            <span>{reworkLimit ? "已封版需人工处理" : decisionActionLabel(decisionAction)}</span>
            {targetAgent ? <strong>{AGENT_LABELS[targetAgent] ?? targetAgent}</strong> : <strong>{TEXT.noTarget}</strong>}
          </div>
          <small className="decision-action-code">{decisionAction}</small>
          {reworkLimit ? <p className="decision-warning">{reworkLimit}</p> : null}
          <p>{decision.reason || TEXT.waitingReason}</p>
          <div className="decision-meta-grid">
            <DecisionMeta label={TEXT.affectedClaim} values={decision.affectedClaimIds} empty={TEXT.noClaim} />
            <DecisionMeta label={TEXT.requiredEvidence} values={decision.requiredEvidenceTypes} empty={TEXT.noEvidenceType} />
            <DecisionMeta label={TEXT.decisionTime} values={decision.decidedAt ? [formatTime(decision.decidedAt)] : []} empty={TEXT.waitingRecord} />
          </div>
          {targetAgent ? (
            <button type="button" onClick={() => onRerunTarget(targetAgent)} disabled={disabled}>
              <RefreshCw size={13} /> {rerunLabel(targetAgent)}
            </button>
          ) : null}
        </div>
      ) : null}
      <div className="finding-list">
        {findings.length ? (
          REVIEW_SEVERITIES.map((severity) => {
            const severityFindings = groupedFindings[severity];
            const severityCollapsed = collapsedSeverityGroups[severity];
            return severityFindings.length ? (
              <div className={`finding-group ${severityCollapsed ? "collapsed" : ""}`} key={severity}>
                <div className="finding-group-title">
                  <span className={`severity-dot ${severity.toLowerCase()}`} />
                  <strong>{severity_LABELS[severity]}</strong>
                  <small>{severityFindings.length}</small>
                  <button
                    className="severity-toggle"
                    type="button"
                    aria-expanded={!severityCollapsed}
                    aria-label={`${severityCollapsed ? TEXT.expand : TEXT.collapse}${severity_LABELS[severity]}`}
                    onClick={() => toggleSeverityGroup(severity)}
                  >
                    <ChevronDown size={14} />
                  </button>
                </div>
                {severityCollapsed ? null : (
                  <>
                    <p className="finding-group-note">{severity_DESCRIPTIONS[severity]}</p>
                    {severityFindings.map((finding) => (
                      <FindingItem finding={finding} onLocateFinding={onLocateFinding} key={finding.id} />
                    ))}
                  </>
                )}
              </div>
            ) : null;
          })
        ) : (
          <div className="pass-box">
            <CheckCircle2 size={18} />
            <span>{TEXT.noHighRisk}</span>
          </div>
        )}
      </div>
    </CollapsiblePanel>
  );
}

function DecisionMeta({ label, values, empty }: { label: string; values?: string[]; empty: string }) {
  const normalized = values?.filter(Boolean) ?? [];
  const visibleValues = normalized.slice(0, 3);
  const hiddenCount = Math.max(normalized.length - visibleValues.length, 0);
  const displayValue = normalized.length
    ? `${visibleValues.join("\u3001")}${hiddenCount ? ` ${TEXT.more} ${hiddenCount} ${TEXT.item}` : ""}`
    : empty;
  return (
    <div className="decision-meta">
      <span>{label}</span>
      <p title={normalized.join("\u3001")}>{displayValue}</p>
    </div>
  );
}

function FindingItem({ finding, onLocateFinding }: { finding: ReviewFinding; onLocateFinding?: (finding: ReviewFinding) => void }) {
  return (
    <div className={`finding-item ${finding.severity.toLowerCase()}`}>
      <div className="finding-heading">
        <span>{finding.severity}</span>
        <strong>{findingCategoryLabel(finding.category)}</strong>
      </div>
      <small className="finding-category">{finding.category}</small>
      <p>{finding.message}</p>
      <small>{finding.recommendation}</small>
      <div className="finding-meta">
        {finding.claimId ? <span>Claim {finding.claimId}</span> : null}
        {finding.citationKey ? <span>[{finding.citationKey}]</span> : null}
        {finding.paragraphIndex !== undefined ? <span>{TEXT.paragraph} {finding.paragraphIndex}</span> : null}
      </div>
      {finding.excerpt ? <small className="finding-excerpt">{TEXT.excerpt}: {finding.excerpt}</small> : null}
      {finding.claimId || finding.citationKey || finding.artifactId ? (
        <button className="finding-locate" type="button" onClick={() => onLocateFinding?.(finding)}>
          <LocateFixed size={13} /> {locateLabel(finding)}
        </button>
      ) : null}
    </div>
  );
}

const severity_LABELS: Record<ReviewFinding["severity"], string> = {
  HIGH: "\u963b\u65ad\u95ee\u9898",
  MEDIUM: "\u8d28\u91cf\u63d0\u9192",
  LOW: "\u4eba\u5de5\u590d\u6838"
};

const severity_DESCRIPTIONS: Record<ReviewFinding["severity"], string> = {
  HIGH: "\u4f1a\u5f71\u54cd\u62a5\u544a\u662f\u5426\u53ef\u4ee5\u5bf9\u5916\u53d1\u5e03\uff0c\u901a\u5e38\u9700\u8981\u8865\u8bc1\u3001\u91cd\u505a\u5206\u6790\u6216\u4fee\u8ba2\u62a5\u544a\u3002",
  MEDIUM: "\u4e0d\u963b\u65ad\u6f14\u793a\uff0c\u4f46\u5efa\u8bae\u5728\u6b63\u5f0f\u4f7f\u7528\u524d\u8865\u5f3a\u8bc1\u636e\u3001\u964d\u4f4e\u63aa\u8f9e\u6216\u66ff\u6362\u6765\u6e90\u3002",
  LOW: "\u7cfb\u7edf\u65e0\u6cd5\u81ea\u52a8\u5b9a\u8bba\uff0c\u4fdd\u7559\u7ed9\u4eba\u5de5\u68c0\u67e5\u3001\u8bbf\u8c08\u3001\u5b9e\u6d4b\u6216\u6700\u65b0\u4ef7\u683c\u786e\u8ba4\u3002"
};

function groupFindings(findings: ReviewFinding[]) {
  return findings.reduce<Record<ReviewFinding["severity"], ReviewFinding[]>>(
    (groups, finding) => {
      groups[finding.severity].push(finding);
      return groups;
    },
    { HIGH: [], MEDIUM: [], LOW: [] }
  );
}

function decisionClass(action: string, reworkLimitReached = false) {
  if (reworkLimitReached) return "limited";
  return action === "PASS" ? "pass" : "blocked";
}

function reworkLimitProfile(
  status?: AnalysisStatus,
  decision?: ReviewDecision,
  workflowTransitions: WorkflowTransition[] = [],
  maxReviewReworkAttempts?: number
) {
  if (status !== "SUCCEEDED" || !decision?.action || decision.action === "PASS") {
    return "";
  }
  const latestTransition = workflowTransitions.at(-1);
  if (latestTransition?.route !== "finish" || latestTransition.targetNode !== "FINALIZER" || latestTransition.trigger !== "auto-review-gate") {
    return "";
  }
  const reworkCount = workflowTransitions.filter((transition) => transition.route && transition.route !== "finish").length;
  const limitText = maxReviewReworkAttempts === undefined ? "本次设置的" : `${maxReviewReworkAttempts} 次`;
  return `自动返工已达到${limitText}上限，流程已封版；但 ReviewDecision 仍是 ${decisionActionLabel(decision.action)}，说明仍有未完全解决的复核项。请人工复核，或手动从目标 Agent 继续重跑下游链路。已自动返工 ${reworkCount} 次。`;
}

function decisionActionLabel(action: string) {
  const labels: Record<string, string> = {
    PASS: "\u53ef\u7ee7\u7eed",
    RECOLLECT_EVIDENCE: "\u9700\u8865\u8bc1",
    REWORK_ANALYSIS: "\u9700\u91cd\u6790",
    REVISE_REPORT: "\u9700\u4fee\u8ba2"
  };
  return labels[action] ?? action;
}

function qualityProfile(groupedFindings: Record<ReviewFinding["severity"], ReviewFinding[]>) {
  if (groupedFindings.HIGH.length > 0) {
    return {
      tone: "blocked",
      label: "\u4e0d\u5efa\u8bae\u5bf9\u5916\u53d1\u5e03",
      description: "\u5b58\u5728\u963b\u65ad\u95ee\u9898\uff0c\u76f8\u5173\u7ed3\u8bba\u9700\u8981\u5148\u8865\u8bc1\u3001\u964d\u7ea7\u6216\u91cd\u65b0\u4fee\u8ba2\u3002"
    };
  }
  if (groupedFindings.MEDIUM.length + groupedFindings.LOW.length > 0) {
    return {
      tone: "review",
      label: "\u53ef\u6f14\u793a\uff0c\u9700\u4eba\u5de5\u786e\u8ba4",
      description: "\u672a\u53d1\u73b0\u963b\u65ad\u9879\uff0c\u4f46\u8fd8\u6709\u8d28\u91cf\u63d0\u9192\u6216\u4eba\u5de5\u590d\u6838\u9879\uff0c\u6b63\u5f0f\u4f7f\u7528\u524d\u5efa\u8bae\u9010\u6761\u786e\u8ba4\u3002"
    };
  }
  return {
    tone: "pass",
    label: "\u5df2\u901a\u8fc7\u9ad8\u98ce\u9669\u68c0\u67e5",
    description: "\u5f53\u524d\u672a\u53d1\u73b0\u963b\u65ad\u3001\u8d28\u91cf\u63d0\u9192\u6216\u4eba\u5de5\u590d\u6838\u9879\uff0c\u53ef\u8fdb\u5165\u4eba\u5de5\u62bd\u67e5\u3002"
  };
}

function rerunLabel(agent: AgentName) {
  if (agent === "RESEARCHER") return "\u8865\u91c7\u8bc1\u636e";
  if (agent === "ANALYST") return "\u91cd\u505a\u7ed3\u6784\u5316\u5206\u6790";
  if (agent === "WRITER") return "\u4fee\u8ba2\u62a5\u544a\u8349\u7a3f";
  return `\u91cd\u8dd1 ${AGENT_LABELS[agent] ?? agent}`;
}

function locateLabel(finding: ReviewFinding) {
  if (finding.claimId) return "\u5b9a\u4f4d Claim";
  if (finding.citationKey) return "\u5b9a\u4f4d\u8bc1\u636e";
  return "\u5b9a\u4f4d\u62a5\u544a";
}

function findingCategoryLabel(category: string) {
  const labels: Record<string, string> = {
    citation_missing: "\u7f3a\u5c11\u5f15\u7528",
    citation_unknown: "\u672a\u77e5\u5f15\u7528",
    citation_weak_support: "\u5f15\u7528\u5f31\u652f\u6491",
    citation_snippet_only: "\u641c\u7d22\u6458\u8981\u6765\u6e90",
    citation_blocked_source: "\u6765\u6e90\u53d7\u9650",
    citation_thin_source: "\u6765\u6e90\u8fc7\u8584",
    claim_missing_evidence: "Claim \u7f3a\u8bc1\u636e",
    claim_unknown_evidence: "Claim \u5f15\u7528\u672a\u77e5\u8bc1\u636e",
    claim_weak_support: "Claim \u5f31\u652f\u6491",
    claim_high_confidence_low_quality_source: "\u9ad8\u7f6e\u4fe1\u4f4e\u8d28\u91cf\u6765\u6e90",
    claim_confidence_mismatch: "\u7f6e\u4fe1\u5ea6\u4e0d\u4e00\u81f4",
    llm_overclaim: "\u8bed\u4e49\u8fc7\u5ea6\u63a8\u65ad",
    llm_semantic_review: "\u8bed\u4e49\u8d28\u68c0"
  };
  return labels[category] ?? category;
}

function formatTime(value: string) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }
  return date.toLocaleString();
}
