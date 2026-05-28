import { useEffect } from "react";
import { FileText, Plus, RefreshCw, X } from "lucide-react";
import type { AnalysisRunSummary } from "../types";
import { displayRunPhase } from "../utils";

interface HistoryDrawerProps {
  open: boolean;
  summaries: AnalysisRunSummary[];
  currentRunId?: string;
  loading: boolean;
  onClose: () => void;
  onNewRun: () => void;
  onRefresh: () => void;
  onSelectRun: (runId: string) => void;
}

export function HistoryDrawer({
  open,
  summaries,
  currentRunId,
  loading,
  onClose,
  onNewRun,
  onRefresh,
  onSelectRun
}: HistoryDrawerProps) {
  useEffect(() => {
    if (!open) return;
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        onClose();
      }
    };
    window.addEventListener("keydown", handleKeyDown);
    return () => window.removeEventListener("keydown", handleKeyDown);
  }, [onClose, open]);

  if (!open) return null;

  return (
    <div className="history-overlay" role="presentation" onMouseDown={onClose}>
      <aside
        className="history-drawer"
        role="dialog"
        aria-label="历史会话"
        aria-modal="true"
        onMouseDown={(event) => event.stopPropagation()}
      >
        <div className="history-header">
          <div>
            <p className="eyebrow">历史</p>
            <h2>历史会话</h2>
          </div>
          <button className="icon-button" type="button" onClick={onClose} aria-label="关闭历史会话">
            <X size={17} />
          </button>
        </div>
        <div className="history-actions">
          <button className="primary-button" type="button" onClick={onNewRun}>
            <Plus size={15} /> 新建分析
          </button>
          <button className="ghost-button history-refresh" type="button" onClick={onRefresh}>
            <RefreshCw size={14} /> 刷新
          </button>
        </div>
        <div className="history-list">
          {summaries.length ? summaries.map((summary) => (
            <button
              key={summary.id}
              type="button"
              className={`history-item ${summary.id === currentRunId ? "selected" : ""}`}
              onClick={() => onSelectRun(summary.id)}
              disabled={loading && summary.id !== currentRunId}
            >
              <FileText size={17} />
              <span>
                <strong>{runTitle(summary)}</strong>
                <small>{runMeta(summary)}</small>
              </span>
              <em>{displayRunPhase(summary.status)}</em>
            </button>
          )) : (
            <div className="empty-state">
              <strong>暂无历史会话</strong>
            </div>
          )}
        </div>
      </aside>
    </div>
  );
}

function runTitle(summary: AnalysisRunSummary) {
  if (summary.industry?.trim()) return summary.industry.trim();
  if (summary.competitors?.length) return summary.competitors.slice(0, 3).join("、");
  if (summary.originalPrompt?.trim()) return truncate(summary.originalPrompt.trim(), 24);
  return "未命名分析";
}

function runMeta(summary: AnalysisRunSummary) {
  const updatedAt = formatLocalTime(summary.updatedAt ?? summary.createdAt);
  return [
    updatedAt,
    summary.competitors.length ? `${summary.competitors.length} 个竞品` : "",
    summary.evidenceCount ? `${summary.evidenceCount} 条证据` : "",
    summary.findingCount ? `${summary.findingCount} 个质检项` : ""
  ].filter(Boolean).join(" · ");
}

function formatLocalTime(value?: string) {
  if (!value) return "";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "";
  return date.toLocaleString("zh-CN", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit"
  });
}

function truncate(value: string, maxLength: number) {
  return value.length > maxLength ? `${value.slice(0, maxLength)}...` : value;
}
