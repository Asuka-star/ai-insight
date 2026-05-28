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
  const quality = qualityProfile(groupedFindings);

  return (
    <section className="panel">
      <div className="section-title">
        <div>
          <p className="eyebrow">复核</p>
          <h2>质检与打回</h2>
        </div>
        {findings.length ? <AlertTriangle size={18} /> : <ShieldCheck size={18} />}
      </div>
      <div className={`credibility-card ${quality.tone}`}>
        <div>
          <span>可信度状态</span>
          <strong>{quality.label}</strong>
        </div>
        <p>{quality.description}</p>
        <div className="credibility-counts" aria-label="质检分层统计">
          <span>{groupedFindings.HIGH.length} 阻断</span>
          <span>{groupedFindings.MEDIUM.length} 建议</span>
          <span>{groupedFindings.LOW.length} 复核</span>
        </div>
      </div>
      {decision ? (
        <div className={`decision-box ${decisionClass(decisionAction)}`}>
          <div className="decision-header">
            <span>{decisionActionLabel(decisionAction)}</span>
            {targetAgent ? <strong>{AGENT_LABELS[targetAgent] ?? targetAgent}</strong> : <strong>无需打回</strong>}
          </div>
          <small className="decision-action-code">{decisionAction}</small>
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
                <p className="finding-group-note">{severity_DESCRIPTIONS[severity]}</p>
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
  const visibleValues = normalized.slice(0, 3);
  const hiddenCount = Math.max(normalized.length - visibleValues.length, 0);
  const displayValue = normalized.length
    ? `${visibleValues.join("、")}${hiddenCount ? ` 等 ${hiddenCount} 项` : ""}`
    : empty;
  return (
    <div className="decision-meta">
      <span>{label}</span>
      <p title={normalized.join("、")}>{displayValue}</p>
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

const severity_DESCRIPTIONS: Record<ReviewFinding["severity"], string> = {
  HIGH: "会影响报告是否可以对外发布，通常需要补证、重做分析或修订报告。",
  MEDIUM: "不阻断演示，但建议在正式使用前补强证据、降低措辞或替换来源。",
  LOW: "系统无法自动定论，保留给人工检查、访谈、实测或最新价格确认。"
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

function decisionActionLabel(action: string) {
  const labels: Record<string, string> = {
    PASS: "可继续",
    RECOLLECT_EVIDENCE: "需补证",
    REWORK_ANALYSIS: "需重析",
    REVISE_REPORT: "需修订"
  };
  return labels[action] ?? action;
}

function qualityProfile(groupedFindings: Record<ReviewFinding["severity"], ReviewFinding[]>) {
  if (groupedFindings.HIGH.length > 0) {
    return {
      tone: "blocked",
      label: "不建议对外发布",
      description: "存在阻断问题，相关结论需要先补证、降级或重新修订。"
    };
  }
  if (groupedFindings.MEDIUM.length + groupedFindings.LOW.length > 0) {
    return {
      tone: "review",
      label: "可演示，需人工确认",
      description: "未发现阻断项，但还有质量提醒或人工复核项，正式使用前建议逐条确认。"
    };
  }
  return {
    tone: "pass",
    label: "已通过高风险检查",
    description: "当前未发现阻断、质量提醒或人工复核项，可进入人工抽查。"
  };
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
