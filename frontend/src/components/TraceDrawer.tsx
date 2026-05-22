import { Clock, FileInput, FileOutput, Gauge, MessageSquareText, X } from "lucide-react";
import type { ReactNode } from "react";
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
              <div className="trace-card-header">
                <strong>{step.status}</strong>
                <small>{formatTime(step.startedAt)} - {formatTime(step.completedAt)}</small>
              </div>
              <TraceField icon={<FileInput size={14} />} label="输入摘要" value={step.inputSummary} />
              <TraceField icon={<FileOutput size={14} />} label="输出摘要" value={step.outputSummary} />
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
              <div className="trace-card-header">
                <strong>{trace.modelName || "未知模型"}</strong>
                <span className={trace.fallbackUsed ? "trace-pill fallback" : "trace-pill"}>
                  {trace.fallbackUsed ? "fallback" : trace.status ?? "trace"}
                </span>
              </div>
              <div className="trace-metrics">
                <TraceMetric icon={<MessageSquareText size={13} />} label="Prompt" value={trace.promptTokens ?? 0} />
                <TraceMetric icon={<FileOutput size={13} />} label="输出" value={trace.completionTokens ?? 0} />
                <TraceMetric icon={<Gauge size={13} />} label="总量" value={trace.totalTokens ?? ((trace.promptTokens ?? 0) + (trace.completionTokens ?? 0))} />
                <TraceMetric icon={<Clock size={13} />} label="耗时" value={`${trace.latencyMs ?? 0}ms`} />
              </div>
              <TraceField icon={<Gauge size={14} />} label="决策说明" value={trace.decisionSummary} />
              <TraceDisclosure title="Prompt" value={trace.prompt} />
              <TraceDisclosure title="输入快照" value={trace.inputSnapshot} />
              <TraceDisclosure title="输出摘要" value={trace.outputSnapshot} />
              <TraceDisclosure title="原始模型输出" value={trace.rawModelOutput} />
              {trace.errorMessage ? <TraceField label="异常" value={trace.errorMessage} danger /> : null}
              <small>
                {formatTime(trace.startedAt ?? trace.createdAt)} - {formatTime(trace.completedAt)}
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

function TraceMetric({ icon, label, value }: { icon: ReactNode; label: string; value: string | number }) {
  return (
    <div className="trace-metric">
      {icon}
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  );
}

function TraceField({
  icon,
  label,
  value,
  danger
}: {
  icon?: ReactNode;
  label: string;
  value?: string;
  danger?: boolean;
}) {
  return (
    <div className={danger ? "trace-field danger" : "trace-field"}>
      <span>{icon}{label}</span>
      <p>{value || "暂无记录"}</p>
    </div>
  );
}

function TraceDisclosure({ title, value }: { title: string; value?: string }) {
  return (
    <details className="trace-disclosure">
      <summary>{title}</summary>
      <pre>{value || "暂无记录"}</pre>
    </details>
  );
}
