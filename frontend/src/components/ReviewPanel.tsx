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
  affectedClaim: "\u5f71\u54cd\u7ed3\u8bba",
  noClaim: "\u65e0\u6307\u5b9a\u7ed3\u8bba",
  requiredEvidence: "\u9700\u8865\u8bc1\u636e",
  noEvidenceType: "\u65e0\u6307\u5b9a\u8bc1\u636e\u7c7b\u578b",
  decisionTime: "\u51b3\u7b56\u65f6\u95f4",
  waitingRecord: "\u7b49\u5f85\u8bb0\u5f55",
  noHighRisk: "\u6682\u65e0\u9ad8\u98ce\u9669\u8d28\u68c0\u95ee\u9898",
  paragraph: "\u6bb5\u843d",
  claim: "\u7ed3\u8bba",
  evidence: "\u8bc1\u636e",
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
          <small className="decision-action-code">状态：{decisionActionLabel(decisionAction)}</small>
          {reworkLimit ? <p className="decision-warning">{reworkLimit}</p> : null}
          <p>{localizeReviewText(decision.reason) || TEXT.waitingReason}</p>
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
  const citationKey = citationKeyFromFinding(finding);
  const locationType = locationTypeFromFinding(finding, citationKey);
  const canLocate = canLocateFinding(finding, citationKey, locationType);
  return (
    <div className={`finding-item ${finding.severity.toLowerCase()}`}>
      <div className="finding-heading">
        <span>{severity_LABELS[finding.severity]}</span>
        <strong>{findingCategoryLabel(finding.category)}</strong>
      </div>
      <p>{localizeReviewText(finding.message)}</p>
      <small>{localizeReviewText(finding.recommendation)}</small>
      <div className="finding-meta">
        {finding.claimId ? <span>{TEXT.claim} {finding.claimId}</span> : null}
        {citationKey ? <span>{TEXT.evidence} [{citationKey}]</span> : null}
        {hasParagraphIndex(finding) ? <span>{TEXT.paragraph} {finding.paragraphIndex}</span> : null}
        {finding.targetAgent ? <span>建议处理 {AGENT_LABELS[finding.targetAgent] ?? finding.targetAgent}</span> : null}
      </div>
      {finding.excerpt ? <small className="finding-excerpt">{TEXT.excerpt}: {localizeReviewText(finding.excerpt)}</small> : null}
      {canLocate ? (
        <button
          className="finding-locate"
          type="button"
          onClick={() => onLocateFinding?.({ ...finding, citationKey: finding.citationKey ?? citationKey })}
        >
          <LocateFixed size={13} /> {locateLabel(finding, citationKey, locationType)}
        </button>
      ) : null}
    </div>
  );
}

function hasParagraphIndex(finding: ReviewFinding) {
  return typeof finding.paragraphIndex === "number" && Number.isFinite(finding.paragraphIndex);
}

function citationKeyFromFinding(finding: ReviewFinding) {
  if (finding.citationKey) return finding.citationKey;
  return /\[(S\d+)]/.exec(`${finding.message} ${finding.excerpt ?? ""}`)?.[1];
}

function locationTypeFromFinding(finding: ReviewFinding, citationKey?: string) {
  if (finding.locationType) return finding.locationType;
  if (hasParagraphIndex(finding)) return "REPORT_PARAGRAPH";
  if (finding.claimId) return "CLAIM";
  if (citationKey) return "EVIDENCE_SOURCE";
  if (finding.artifactId) return "GLOBAL_REPORT";
  if (finding.excerpt) return "REPORT_PARAGRAPH";
  return undefined;
}

function canLocateFinding(finding: ReviewFinding, citationKey: string | undefined, locationType?: ReviewFinding["locationType"]) {
  if (locationType === "REPORT_PARAGRAPH") return Boolean(hasParagraphIndex(finding) || finding.excerpt);
  if (locationType === "CLAIM") return Boolean(finding.claimId);
  if (locationType === "EVIDENCE_SOURCE") return Boolean(citationKey);
  if (locationType === "GLOBAL_REPORT") return Boolean(finding.artifactId);
  if (locationType === "SCHEMA") return true;
  return Boolean(finding.claimId || citationKey);
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
  if ((status !== "SUCCEEDED" && status !== "NEEDS_USER_INPUT") || !decision?.action || decision.action === "PASS") {
    return "";
  }
  const latestTransition = workflowTransitions.at(-1);
  if (latestTransition?.route !== "finish" || latestTransition.trigger !== "auto-review-gate") {
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

function locateLabel(finding: ReviewFinding, citationKey?: string, locationType?: ReviewFinding["locationType"]) {
  if (locationType === "REPORT_PARAGRAPH") return "\u5b9a\u4f4d\u62a5\u544a";
  if (locationType === "CLAIM" || finding.claimId) return "\u5b9a\u4f4d\u7ed3\u8bba";
  if (locationType === "EVIDENCE_SOURCE" || citationKey) return "\u5b9a\u4f4d\u8bc1\u636e";
  if (locationType === "SCHEMA") return "\u67e5\u770b\u7ed3\u6784\u5316";
  return "\u67e5\u770b\u62a5\u544a";
}

function findingCategoryLabel(category: string) {
  const normalized = normalizeCategoryKey(category);
  return REVIEW_CATEGORY_LABELS[normalized] ?? fallbackCategoryLabel(normalized);
}

const REVIEW_CATEGORY_LABELS: Record<string, string> = {
  citation_missing: "缺少引用",
  missing_citation: "缺少引用",
  citation_unknown: "未知引用",
  citation_weak_support: "引用支撑不足",
  citation_support_mismatch: "引用支撑不一致",
  citation_snippet_only: "搜索摘要来源",
  snippet_only_source: "搜索摘要来源",
  citation_blocked_source: "来源受限",
  blocked_source: "来源受限",
  fetch_failed_source: "来源抓取失败",
  citation_thin_source: "来源内容过薄",
  thin_source: "来源内容过薄",
  citation_internal_evidence_presented_as_public: "内部资料被表述为公开证据",
  citation_region_unavailable_source: "来源区域不可用",
  region_unavailable_source: "来源区域不可用",
  citation_marketing_only_source: "营销型来源",
  marketing_only_source: "营销型来源",
  low_quality_source: "低质量来源",
  claim_missing_evidence: "结论缺少证据",
  claim_unknown_evidence: "结论引用未知证据",
  claim_evidence_mismatch: "结论与证据不一致",
  claim_weak_support: "结论支撑不足",
  claim_high_confidence_low_quality_source: "高置信结论依赖低质量来源",
  claim_confidence_mismatch: "置信度不一致",
  claim_missing_fact_binding: "结论缺少事实绑定",
  claim_unknown_fact: "结论引用未知事实",
  claim_fact_mismatch: "结论与事实不一致",
  claim_internal_evidence_presented_as_public: "内部资料被表述为公开证据",
  claim_weak_pricing_source: "定价来源偏弱",
  claim_missing_pricing_source: "缺少定价来源",
  claim_weak_security_source: "安全/权限来源偏弱",
  claim_missing_sentiment_source: "缺少用户口碑来源",
  fact_missing_evidence: "事实缺少证据",
  fact_unknown_chunk: "事实引用未知切片",
  fact_unknown_evidence: "事实引用未知证据",
  fact_partial_evidence_binding_weak: "事实部分证据偏弱",
  fact_unsupported_by_evidence: "事实未被证据支撑",
  fact_extraction_mismatch: "事实抽取不一致",
  extracted_fact_mismatch: "抽取事实不一致",
  report_overclaim: "报告过度推断",
  llm_overclaim: "语义过度推断",
  report_quality_insufficient: "报告质量不足",
  report_missing_decision_summary: "报告缺少决策摘要",
  report_dimension_coverage_gap: "报告维度覆盖不足",
  report_actionability_gap: "报告行动建议不足",
  report_actionability_insufficient: "报告可执行性不足",
  unsupported_recommendation: "建议缺少支撑",
  schema_consistency: "结构化信息不一致",
  matrix_claim_conflict: "矩阵与结论冲突",
  swot_claim_conflict: "SWOT 与结论冲突",
  llm_semantic_review: "语义质检"
};

function normalizeCategoryKey(category?: string) {
  return (category ?? "").trim().toLowerCase().replace(/-/g, "_");
}

function fallbackCategoryLabel(category: string) {
  if (!category) return "未分类质检项";
  if (category.includes("citation") || category.includes("reference")) return "引用问题";
  if (category.includes("overclaim")) return "过度推断";
  if (category.includes("source")) return "来源质量问题";
  if (category.includes("fact")) return "事实抽取问题";
  if (category.includes("claim")) return "结论支撑问题";
  if (category.includes("schema") || category.includes("matrix") || category.includes("swot")) return "结构化一致性问题";
  if (category.includes("report")) return "报告质量问题";
  return "质检问题";
}

function localizeReviewText(value?: string) {
  if (!value) return "";
  const exact: Record<string, string> = {
    "Rerun Extractor to correct the fact value/evidence binding, or move the unsupported field to unknowns.":
      "请重跑 Extractor 修正事实值与证据绑定；无法被证据支撑的字段应移动到未知事实列表。",
    "Keep the supported evidence binding and remove weak extra evidenceIds/chunkKeys instead of rerunning the whole analysis.":
      "保留已能支撑该事实的证据绑定，移除额外的弱 evidenceIds/chunkKeys；无需重跑整条分析链路。",
    "Rerun Extractor and only emit facts that carry evidenceIds/chunkKeys, or move the item to unknowns.":
      "请重跑 Extractor；只保留带有 evidenceIds/chunkKeys 的事实，无法被证据支撑的字段应移动到未知事实列表。",
    "Rerun Extractor after chunking, or remove stale chunkKeys from the fact binding.":
      "请在证据切片刷新后重跑 Extractor，或从事实绑定中移除失效的 chunkKeys。",
    "Rerun Extractor and bind the fact to existing evidence, or drop the unsupported fact.":
      "请重跑 Extractor 并把事实绑定到现有证据；如果没有可支撑证据，请删除该事实。",
    "Bind the claim to relevant ExtractedFact ids, or keep the claim tentative if no extracted fact supports it.":
      "请把该 claim 绑定到相关 ExtractedFact；如果没有抽取事实可支撑，应保持为待验证结论。",
    "Remove the stale factId or rerun Analyst after Extractor regenerates the fact layer.":
      "请移除失效的 factId；或在 Extractor 重新生成事实层后重跑 Analyst。",
    "Rerun Analyst to rewrite the claim from bound facts, bind more relevant facts, or downgrade confidence.":
      "请重跑 Analyst，基于绑定事实改写该 claim；也可以绑定更相关的事实，或降低置信度。"
  };
  if (exact[value]) return localizeReviewDisplayTokens(localizeCategoryTokens(exact[value]));
  return localizeReviewDisplayTokens(localizeCategoryTokens(value
    .replace(
      /^Extracted fact has no evidenceIds: (.+)$/,
      "抽取事实缺少 evidenceIds: $1"
    )
    .replace(
      /^Extracted fact references an unknown evidence chunk key: (.+)$/,
      "抽取事实引用了不存在的证据切片: $1"
    )
    .replace(
      /^Extracted fact references an unknown evidence id: \[(.+)]$/,
      "抽取事实引用了不存在的证据编号: [$1]"
    )
    .replace(
      /^Extracted fact has supporting evidence, but some bound evidence ids look weak \[(.+)]: (.+)$/,
      "抽取事实已有支撑证据，但部分绑定证据较弱 [$1]: $2"
    )
    .replace(
      /^Extracted fact value is not supported by its bound evidence ids \[(.+)]: (.+)$/,
      "抽取事实值无法被其绑定证据支撑 [$1]: $2"
    )
    .replace(
      /^Structured claim is backed by evidenceIds but not by extracted factIds: (.+)$/,
      "结构化结论绑定了 evidenceIds，但没有绑定对应的 extracted factIds: $1"
    )
    .replace(
      /^Structured claim references an unknown extracted fact id: (.+)$/,
      "结构化结论引用了不存在的 extracted fact id: $1"
    )
    .replace(
      /^High-confidence claim over-interprets or does not align with its bound extracted facts: (.+)$/,
      "高置信 claim 与其绑定的抽取事实不一致，或存在过度解读: $1"
    )
    .replace(
      /^Review found high-risk extracted fact issues \((.+)\); rerun Extractor to repair fact values, evidenceIds, or chunk bindings\.$/,
      "质检发现高风险抽取事实问题（$1）；需要重跑 Extractor 修复事实值、evidenceIds 或 chunk 绑定。"
    )
    .replace(
      /^Previous Extractor repair did not reduce blocking findings and evidence did not change; rerun Researcher to refresh upstream evidence before extracting facts again\.$/,
      "上一轮 Extractor 返工没有减少阻断问题，且证据没有变化；需要先重跑 Researcher 刷新上游证据，再重新抽取事实。"
    )
    .replace(
      /^Previous Analyst repair changed claims but did not reduce blocking findings while evidence, profiles, and facts stayed unchanged; rerun Extractor to rebuild upstream fact\/evidence bindings before analyzing again\.$/,
      "上一轮 Analyst 返工虽然改动了 claims，但阻断问题没有减少，且证据、画像和事实层没有变化；需要先重跑 Extractor 重建上游事实/证据绑定，再继续分析。"
    )));
}

function localizeCategoryTokens(value: string) {
  return Object.entries(REVIEW_CATEGORY_LABELS)
    .sort(([left], [right]) => right.length - left.length)
    .reduce((text, [category, label]) => {
      const pattern = new RegExp(escapeRegExp(category).replace(/_/g, "[-_]"), "g");
      return text.replace(pattern, label);
    }, value);
}

function localizeReviewDisplayTokens(value: string) {
  return value
    .replace(/ParagraphIndex=(\d+)/g, "段落 $1")
    .replace(/\bHIGH\b/g, "阻断问题")
    .replace(/\bMEDIUM\b/g, "质量提醒")
    .replace(/\bLOW\b/g, "人工复核")
    .replace(/\bExtractor\b/g, "结构化抽取")
    .replace(/\bResearcher\b/g, "资料采集")
    .replace(/\bAnalyst\b/g, "竞品分析")
    .replace(/\bWriter\b/g, "报告撰写")
    .replace(/\bReviewer\b/g, "事实质检")
    .replace(/\bClaims\b/g, "结论")
    .replace(/\bclaims\b/g, "结论")
    .replace(/\bClaim\b/g, "结论")
    .replace(/\bclaim\b/g, "结论");
}

function escapeRegExp(value: string) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

function formatTime(value: string) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }
  return date.toLocaleString();
}
