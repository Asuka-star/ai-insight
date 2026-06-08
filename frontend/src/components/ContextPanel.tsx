import { ChevronDown, RotateCcw } from "lucide-react";
import type { AgentName } from "../types";
import { AGENTS, AGENT_LABELS } from "../constants";

const rerunnableAgents = AGENTS.filter((agent) => agent !== "CLARIFIER");

interface ContextPanelProps {
  runReady?: boolean;
  targetAgent?: AgentName;
  disabled?: boolean;
  onTargetAgentChange: (agent?: AgentName) => void;
  onSubmit: () => void;
  collapsed?: boolean;
  onToggle?: () => void;
}

export function ContextPanel({
  runReady,
  targetAgent,
  disabled,
  onTargetAgentChange,
  onSubmit,
  collapsed = false,
  onToggle
}: ContextPanelProps) {
  const effectiveTargetAgent = targetAgent === "CLARIFIER" ? undefined : targetAgent;
  const submitDisabled = !effectiveTargetAgent;
  const summary = effectiveTargetAgent ? `已选 ${AGENT_LABELS[effectiveTargetAgent]}` : (runReady ? "待选择 Agent" : "等待创建任务");

  return (
    <section className={`panel context-panel collapsible-panel ${collapsed ? "collapsed" : ""}`}>
      <div className="section-title collapsible-title">
        <div>
          <p className="eyebrow">控制</p>
          <h2>指定重跑</h2>
          {collapsed ? <small className="collapse-summary">{summary}</small> : null}
        </div>
        <div className="collapse-actions">
          <RotateCcw size={18} />
          {onToggle ? (
            <button
              className="collapse-toggle"
              type="button"
              aria-expanded={!collapsed}
              aria-label={collapsed ? "展开指定重跑" : "折叠指定重跑"}
              onClick={onToggle}
            >
              <ChevronDown size={16} />
            </button>
          ) : null}
        </div>
      </div>

      {collapsed ? null : (
        <>
          <div className="question-box quiet">
            <strong>从指定节点重放主流程</strong>
            <p>选择一个 Agent 后，系统会从该节点重新执行，并顺序刷新下游报告与复核结果。</p>
          </div>

          <label className="context-target">
            目标 Agent
            <select value={effectiveTargetAgent ?? ""} onChange={(event) => onTargetAgentChange(event.target.value as AgentName || undefined)}>
              <option value="">请选择要重跑的 Agent</option>
              {rerunnableAgents.map((agent) => (
                <option key={agent} value={agent}>
                  {AGENT_LABELS[agent]}
                </option>
              ))}
            </select>
          </label>

          <button className="primary-button" type="button" onClick={onSubmit} disabled={disabled || submitDisabled}>
            <RotateCcw size={15} /> 开始重跑
          </button>

          <p className="muted-text">
            范围调整请在上方“范围确认”中完成，资料补充请使用下方“补充资料”。
          </p>
        </>
      )}
    </section>
  );
}
