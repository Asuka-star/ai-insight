import { AlertTriangle, CheckCircle2, ShieldCheck } from "lucide-react";
import type { AgentName, ReviewDecision, ReviewFinding } from "../types";
import { AGENT_LABELS } from "../constants";

interface ReviewPanelProps {
  findings: ReviewFinding[];
  decision?: ReviewDecision;
  onRerunTarget: (agent: AgentName) => void;
  disabled?: boolean;
}

export function ReviewPanel({ findings, decision, onRerunTarget, disabled }: ReviewPanelProps) {
  return (
    <section className="panel">
      <div className="section-title">
        <div>
          <p className="eyebrow">Reviewer</p>
          <h2>质检与打回</h2>
        </div>
        {findings.length ? <AlertTriangle size={18} /> : <ShieldCheck size={18} />}
      </div>
      {decision ? (
        <div className="decision-box">
          <span>{decision.action || "PASS"}</span>
          <p>{decision.reason || "等待 Reviewer 给出结构化决策"}</p>
          {decision.targetAgent ? (
            <button type="button" onClick={() => onRerunTarget(decision.targetAgent as AgentName)} disabled={disabled}>
              重跑 {AGENT_LABELS[decision.targetAgent as AgentName] ?? decision.targetAgent}
            </button>
          ) : null}
        </div>
      ) : null}
      <div className="finding-list">
        {findings.length ? (
          findings.map((finding) => (
            <div className={`finding-item ${finding.severity.toLowerCase()}`} key={finding.id}>
              <span>{finding.severity}</span>
              <strong>{finding.category}</strong>
              <p>{finding.message}</p>
              <small>{finding.recommendation}</small>
            </div>
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
