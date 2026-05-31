import { useRef } from "react";
import { Clock, FileOutput, Gauge, MessageSquareText, X } from "lucide-react";
import type { ReactNode } from "react";
import type { AgentName, AgentTrace } from "../types";
import { AGENT_LABELS } from "../constants";
import { formatTime } from "../utils";

interface TraceDrawerProps {
  agent: AgentName | null;
  traces: AgentTrace[];
  onClose: () => void;
}

const TEXT = {
  aria: "\u0041\u0067\u0065\u006e\u0074 \u6267\u884c\u8f68\u8ff9",
  eyebrow: "\u8f68\u8ff9",
  close: "\u5173\u95ed Trace",
  traceTitle: "\u6a21\u578b Trace",
  unknownModel: "\u672a\u77e5\u6a21\u578b",
  noRecord: "\u6682\u65e0\u8bb0\u5f55",
  noTrace: "\u540e\u7aef\u5c1a\u672a\u5199\u5165 AgentTrace",
  decision: "\u51b3\u7b56\u8bf4\u660e",
  output: "\u8f93\u51fa",
  total: "\u603b\u91cf",
  latency: "\u8017\u65f6",
  inputSnapshot: "\u8f93\u5165\u6458\u8981",
  outputSnapshot: "\u8f93\u51fa\u6458\u8981",
  rawOutput: "\u539f\u59cb\u6a21\u578b\u8f93\u51fa",
  error: "\u5f02\u5e38"
};

export function TraceDrawer({ agent, traces, onClose }: TraceDrawerProps) {
  const closeStartedOnOverlayRef = useRef(false);

  if (!agent) return null;

  return (
    <div
      className="trace-overlay"
      role="presentation"
      onPointerDown={(event) => {
        closeStartedOnOverlayRef.current = event.target === event.currentTarget;
      }}
      onClick={(event) => {
        if (closeStartedOnOverlayRef.current && event.target === event.currentTarget) {
          onClose();
        }
        closeStartedOnOverlayRef.current = false;
      }}
    >
      <aside className="trace-drawer" aria-label={TEXT.aria} onClick={(event) => event.stopPropagation()}>
        <div className="drawer-header">
          <div>
            <p className="eyebrow">{TEXT.eyebrow}</p>
            <h2>{AGENT_LABELS[agent]}</h2>
          </div>
          <button className="icon-button" type="button" onClick={onClose} aria-label={TEXT.close}>
            <X size={17} />
          </button>
        </div>
        <div className="drawer-section">
          <h3>{TEXT.traceTitle}</h3>
          {traces.length ? (
            traces.map((trace) => (
              <TraceCard trace={trace} key={trace.id} />
            ))
          ) : (
            <p className="muted-text">{TEXT.noTrace}</p>
          )}
        </div>
      </aside>
    </div>
  );
}

function TraceCard({ trace }: { trace: AgentTrace }) {
  const status = trace.status ?? "trace";
  const startedAt = trace.startedAt ?? trace.createdAt;
  const completedAt = trace.completedAt;

  return (
    <div className="trace-card">
      <div className="trace-card-header">
        <strong>{trace.modelName || TEXT.unknownModel}</strong>
        <span className={trace.fallbackUsed ? "trace-pill fallback" : "trace-pill"}>
          {trace.fallbackUsed ? "fallback" : status}
        </span>
      </div>
      <div className="trace-metrics">
        <TraceMetric icon={<MessageSquareText size={13} />} label="Prompt" value={trace.promptTokens ?? 0} />
        <TraceMetric icon={<FileOutput size={13} />} label={TEXT.output} value={trace.completionTokens ?? 0} />
        <TraceMetric icon={<Gauge size={13} />} label={TEXT.total} value={trace.totalTokens ?? ((trace.promptTokens ?? 0) + (trace.completionTokens ?? 0))} />
        <TraceMetric icon={<Clock size={13} />} label={TEXT.latency} value={`${trace.latencyMs ?? 0}ms`} />
      </div>
      <TraceField icon={<Gauge size={14} />} label={TEXT.decision} value={trace.decisionSummary} />
      <TraceDisclosure title="Prompt" value={trace.prompt} />
      <TraceDisclosure title={TEXT.inputSnapshot} value={trace.inputSnapshot} />
      <TraceDisclosure title={TEXT.outputSnapshot} value={trace.outputSnapshot} />
      <TraceDisclosure title={TEXT.rawOutput} value={trace.rawModelOutput} />
      {trace.errorMessage ? <TraceField label={TEXT.error} value={trace.errorMessage} danger /> : null}
      <small>
        {formatTime(startedAt)} - {formatTime(completedAt)}
      </small>
    </div>
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
      <p>{value || TEXT.noRecord}</p>
    </div>
  );
}

function TraceDisclosure({ title, value }: { title: string; value?: string }) {
  return (
    <details className="trace-disclosure">
      <summary>{title}</summary>
      <pre>{value || TEXT.noRecord}</pre>
    </details>
  );
}
