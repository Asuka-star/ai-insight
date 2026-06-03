import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { ChevronDown, Clock, FileOutput, Gauge, MessageSquareText, X } from "lucide-react";
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
  processSnapshot: "\u8fc7\u7a0b\u6458\u8981",
  inputSnapshot: "\u8f93\u5165\u6458\u8981",
  outputSnapshot: "\u8f93\u51fa\u6458\u8981",
  fullPrompt: "Prompt",
  rawOutput: "\u6a21\u578b\u8f93\u51fa",
  error: "\u5f02\u5e38",
  expandTrace: "\u5c55\u5f00 Trace",
  collapseTrace: "\u6536\u8d77 Trace",
  expand: "\u5c55\u5f00\u5b8c\u6574\u5185\u5bb9",
  collapse: "\u6536\u8d77"
};

const DISCLOSURE_PREVIEW_CHARS = 180;

export function TraceDrawer({ agent, traces, onClose }: TraceDrawerProps) {
  const closeStartedOnOverlayRef = useRef(false);
  const latestTraceId = useMemo(() => latestTraceIdFrom(traces), [traces]);
  const [expandedTraceIds, setExpandedTraceIds] = useState<Set<string>>(() => (
    latestTraceId ? new Set([latestTraceId]) : new Set()
  ));

  useEffect(() => {
    setExpandedTraceIds(latestTraceId ? new Set([latestTraceId]) : new Set());
  }, [agent, latestTraceId]);

  const toggleTrace = useCallback((traceId: string) => {
    setExpandedTraceIds((current) => {
      const next = new Set(current);
      if (next.has(traceId)) {
        next.delete(traceId);
      } else {
        next.add(traceId);
      }
      return next;
    });
  }, []);

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
        <div className="drawer-section trace-drawer-section">
          <h3>{TEXT.traceTitle}</h3>
          {traces.length ? (
            traces.map((trace) => (
              <TraceCard
                trace={trace}
                expanded={expandedTraceIds.has(trace.id)}
                onToggle={() => toggleTrace(trace.id)}
                key={trace.id}
              />
            ))
          ) : (
            <p className="muted-text">{TEXT.noTrace}</p>
          )}
        </div>
      </aside>
    </div>
  );
}

function TraceCard({ trace, expanded, onToggle }: { trace: AgentTrace; expanded: boolean; onToggle: () => void }) {
  const status = trace.status ?? "trace";
  const startedAt = trace.startedAt ?? trace.createdAt;
  const completedAt = trace.completedAt;
  const totalTokens = trace.totalTokens ?? ((trace.promptTokens ?? 0) + (trace.completionTokens ?? 0));

  return (
    <div className="trace-card">
      <button
        className="trace-card-toggle"
        type="button"
        onClick={onToggle}
        aria-expanded={expanded}
        aria-label={expanded ? TEXT.collapseTrace : TEXT.expandTrace}
      >
        <ChevronDown className={expanded ? "expanded" : ""} size={16} />
        <span className="trace-card-title">
          <strong>{trace.modelName || TEXT.unknownModel}</strong>
          <small>
            {formatTime(startedAt)} - {formatTime(completedAt)} · {totalTokens} tokens · {formatLatencySeconds(trace.latencyMs)}
          </small>
        </span>
        <span className={trace.fallbackUsed ? "trace-pill fallback" : "trace-pill"}>
          {trace.fallbackUsed ? "fallback" : status}
        </span>
      </button>
      {expanded ? (
        <div className="trace-card-body">
          <div className="trace-metrics">
            <TraceMetric icon={<MessageSquareText size={13} />} label="Prompt" value={trace.promptTokens ?? 0} />
            <TraceMetric icon={<FileOutput size={13} />} label={TEXT.output} value={trace.completionTokens ?? 0} />
            <TraceMetric icon={<Gauge size={13} />} label={TEXT.total} value={totalTokens} />
            <TraceMetric icon={<Clock size={13} />} label={TEXT.latency} value={formatLatencySeconds(trace.latencyMs)} />
          </div>
          <TraceField icon={<Gauge size={14} />} label={TEXT.decision} value={trace.decisionSummary} />
          {trace.processSnapshot ? <TraceDisclosure title={TEXT.processSnapshot} value={trace.processSnapshot} /> : null}
          <TraceDisclosure title={TEXT.inputSnapshot} value={trace.inputSnapshot} />
          <TraceDisclosure title={TEXT.outputSnapshot} value={trace.outputSnapshot} />
          <TraceDisclosure title={TEXT.fullPrompt} value={trace.prompt} />
          <TraceDisclosure title={TEXT.rawOutput} value={trace.rawModelOutput} />
          {trace.errorMessage ? <TraceField label={TEXT.error} value={trace.errorMessage} danger /> : null}
        </div>
      ) : null}
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

function formatLatencySeconds(latencyMs?: number) {
  if (!latencyMs) return "0s";
  return `${(latencyMs / 1000).toFixed(2)}s`;
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
  const [expanded, setExpanded] = useState(false);
  const completeValue = value || TEXT.noRecord;
  const previewValue = truncatePreview(completeValue, DISCLOSURE_PREVIEW_CHARS);
  const hasTruncatedPreview = previewValue !== completeValue;
  const displayValue = expanded ? completeValue : previewValue;

  return (
    <details className="trace-disclosure">
      <summary>{title}</summary>
      <pre>{displayValue}</pre>
      {hasTruncatedPreview ? (
        <button className="trace-expand-button" type="button" onClick={() => setExpanded((current) => !current)}>
          {expanded ? TEXT.collapse : TEXT.expand}
        </button>
      ) : null}
    </details>
  );
}

function truncatePreview(value: string, maxChars: number) {
  if (value.length <= maxChars) {
    return value;
  }
  return `${value.slice(0, Math.max(0, maxChars - 1))}\u2026`;
}

function latestTraceIdFrom(traces: AgentTrace[]) {
  return traces.reduce<{ id: string; time: number } | null>((latest, trace) => {
    const time = Date.parse(trace.completedAt ?? trace.startedAt ?? trace.createdAt ?? "") || 0;
    if (!latest || time >= latest.time) {
      return { id: trace.id, time };
    }
    return latest;
  }, null)?.id;
}
