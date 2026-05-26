import { AlertTriangle, CheckCircle2, LocateFixed, RefreshCw, ShieldCheck } from "lucide-react";
import type { AgentName, ReviewDecision, ReviewFinding } from "../types";
import { AGENT_LABELS } from "../constants";

interface ReviewPanelProps {
  findings: ReviewFinding[];
  decision?: ReviewDecision;
  onRerunTarget: (agent: AgentName) => void;
  onLocateFinding?: (finding: ReviewFinding) => void;
  disabled?: boolean;
}

export function ReviewPanel({ findings, decision, onRerunTarget, onLocateFinding, disabled }: ReviewPanelProps) {
  const groupedFindings = groupFindings(findings);
  const decisionAction = decision?.action || "PASS";
  const targetAgent = decision?.targetAgent;

  return (
    <section className="panel">
      <div className="section-title">
        <div>
          <p className="eyebrow">复核</p>
          <h2>质检与打回</h2>
        </div>
        {findings.length ? <AlertTriangle size={18} /> : <ShieldCheck size={18} />}
      </div>
      {decision ? (
        <div className={`decision-box ${decisionClass(decisionAction)}`}>
          <div className="decision-header">
            <span>{decisionAction}</span>
            {targetAgent ? <strong>{AGENT_LABELS[targetAgent] ?? targetAgent}</strong> : <strong>无需打回</strong>}
          </div>
          <p>{decision.reason || "等待复核 Agent 给出结构化决策"}</p>
          <div className="decision-meta-grid">
            <DecisionMeta label="影响 Claim" values={decision.affectedClaimIds} empty="无指定 Claim" />
            <DecisionMeta label="需补证据" values={decision.requiredEvidenceTypes} empty="无指定证据类型" />
            <DecisionMeta label="决策时间" values={decision.decidedAt ? [formatTime(decision.decidedAt)] : []} empty="等待记录" />
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
          (["HIGH", "MEDIUM", "LOW"] as const).map((severity) => (
            groupedFindings[severity].length ? (
              <div className="finding-group" key={severity}>
                <div className="finding-group-title">
                  <span className={`severity-dot ${severity.toLowerCase()}`} />
                  <strong>{severity_LABELS[severity]}</strong>
                  <small>{groupedFindings[severity].length}</small>
                </div>
                {groupedFindings[severity].map((finding) => (
                  <FindingItem finding={finding} onLocateFinding={onLocateFinding} key={finding.id} />
                ))}
              </div>
            ) : null
          ))
        ) : (
          <div className="pass-box">
            <CheckCircle2 size={18} />
            <span>暂无高风险质检问题</span>
          </div>
        )}
      </div>
    </section>
  );
}

function DecisionMeta({ label, values, empty }: { label: string; values?: string[]; empty: string }) {
  const normalized = values?.filter(Boolean) ?? [];
  return (
    <div className="decision-meta">
      <span>{label}</span>
      <p>{normalized.length ? normalized.join("、") : empty}</p>
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
        {finding.paragraphIndex !== undefined ? <span>段落 {finding.paragraphIndex}</span> : null}
      </div>
      {finding.excerpt ? <small className="finding-excerpt">摘录：{finding.excerpt}</small> : null}
      {finding.claimId || finding.citationKey || finding.artifactId ? (
        <button className="finding-locate" type="button" onClick={() => onLocateFinding?.(finding)}>
          <LocateFixed size={13} /> {locateLabel(finding)}
        </button>
      ) : null}
    </div>
  );
}

const severity_LABELS: Record<ReviewFinding["severity"], string> = {
  HIGH: "阻断问题",
  MEDIUM: "质量提醒",
  LOW: "人工复核"
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

function decisionClass(action: string) {
  return action === "PASS" ? "pass" : "blocked";
}

function rerunLabel(agent: AgentName) {
  if (agent === "RESEARCHER") return "补采证据";
  if (agent === "ANALYST") return "重做结构化分析";
  if (agent === "WRITER") return "修订报告草稿";
  return `重跑 ${AGENT_LABELS[agent] ?? agent}`;
}

function locateLabel(finding: ReviewFinding) {
  if (finding.claimId) return "定位 Claim";
  if (finding.citationKey) return "定位证据";
  return "定位报告";
}

function findingCategoryLabel(category: string) {
  const labels: Record<string, string> = {
    citation_missing: "缺少引用",
    citation_unknown: "未知引用",
    citation_weak_support: "引用弱支撑",
    citation_snippet_only: "搜索摘要来源",
    citation_blocked_source: "来源受限",
    citation_thin_source: "来源过薄",
    claim_missing_evidence: "Claim 缺证据",
    claim_unknown_evidence: "Claim 引用未知证据",
    claim_weak_support: "Claim 弱支撑",
    claim_high_confidence_low_quality_source: "高置信低质量来源",
    claim_confidence_mismatch: "置信度不一致",
    llm_overclaim: "语义过度推断",
    llm_semantic_review: "语义质检"
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
