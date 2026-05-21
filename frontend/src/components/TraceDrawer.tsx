import { X } from "lucide-react";
import type { AgentName, AgentStep, AgentTrace } from "../types";
import { AGENT_LABELS } from "../constants";
import { formatTime } from "../utils";

interface TraceDrawerProps {
  agent: AgentName | null;
  steps: AgentStep[];
  traces: AgentTrace[];
  onClose: () => void;
}

export function TraceDrawer({ agent, steps, traces, onClose }: TraceDrawerProps) {
  if (!agent) return null;

  return (
    <aside className="trace-drawer" aria-label="Agent 执行轨迹">
      <div className="drawer-header">
        <div>
          <p className="eyebrow">轨迹</p>
          <h2>{AGENT_LABELS[agent]}</h2>
        </div>
        <button className="icon-button" type="button" onClick={onClose} aria-label="关闭 Trace">
          <X size={17} />
        </button>
      </div>
      <div className="drawer-section">
        <h3>执行步骤</h3>
        {steps.length ? (
          steps.map((step) => (
            <div className="trace-card" key={step.id}>
              <strong>{step.status}</strong>
              <p>{step.inputSummary || "无输入摘要"}</p>
              <p>{step.outputSummary || "无输出摘要"}</p>
              <small>{formatTime(step.startedAt)} - {formatTime(step.completedAt)}</small>
            </div>
          ))
        ) : (
          <p className="muted-text">暂无执行步骤</p>
        )}
      </div>
      <div className="drawer-section">
        <h3>模型 Trace</h3>
        {traces.length ? (
          traces.map((trace) => (
            <div className="trace-card" key={trace.id}>
              <strong>{trace.modelName || "未知模型"}</strong>
              <p>{trace.decisionSummary || trace.outputSnapshot || "暂无决策说明"}</p>
              <small>
                提示词 {trace.promptTokens ?? 0} / 输出 {trace.completionTokens ?? 0} / {trace.latencyMs ?? 0}ms
              </small>
            </div>
          ))
        ) : (
          <p className="muted-text">后端尚未写入 AgentTrace</p>
        )}
      </div>
    </aside>
  );
}
