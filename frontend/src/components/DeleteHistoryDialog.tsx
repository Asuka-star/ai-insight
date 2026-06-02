import { useEffect, useRef } from "react";
import { AlertTriangle, RefreshCw, Trash2 } from "lucide-react";
import type { AnalysisRunSummary } from "../types";
import { displayRunPhase } from "../utils";

interface DeleteHistoryDialogProps {
  summary?: AnalysisRunSummary;
  deleting: boolean;
  onCancel: () => void;
  onConfirm: () => void;
}

export function DeleteHistoryDialog({
  summary,
  deleting,
  onCancel,
  onConfirm
}: DeleteHistoryDialogProps) {
  const dialogRef = useRef<HTMLElement>(null);

  useEffect(() => {
    if (!summary) return;
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape" && !deleting) {
        event.stopPropagation();
        onCancel();
      }
    };
    window.addEventListener("keydown", handleKeyDown, true);
    return () => window.removeEventListener("keydown", handleKeyDown, true);
  }, [deleting, onCancel, summary]);

  useEffect(() => {
    if (summary) {
      dialogRef.current?.focus();
    }
  }, [summary]);

  if (!summary) return null;

  const title = historyTitle(summary);
  const counts = [
    summary.competitors.length ? `${summary.competitors.length} 个竞品` : "",
    summary.evidenceCount ? `${summary.evidenceCount} 条证据` : "",
    summary.findingCount ? `${summary.findingCount} 个质检项` : "",
    summary.stepCount ? `${summary.stepCount} 个步骤` : ""
  ].filter(Boolean);

  return (
    <div
      className="confirm-overlay"
      role="presentation"
      onMouseDown={(event) => {
        if (event.currentTarget === event.target && !deleting) {
          onCancel();
        }
      }}
    >
      <section
        ref={dialogRef}
        className="confirm-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="delete-history-title"
        aria-describedby="delete-history-description"
        tabIndex={-1}
        onMouseDown={(event) => event.stopPropagation()}
      >
        <div className="confirm-head">
          <div className="confirm-icon danger">
            <AlertTriangle size={20} />
          </div>
          <div>
            <p className="eyebrow">删除历史会话</p>
            <h2 id="delete-history-title">确认删除这次分析？</h2>
          </div>
        </div>
        <p id="delete-history-description" className="confirm-copy">
          删除后，后端保存的运行数据、执行步骤、Trace、证据与产物记录都会一并移除。
        </p>
        <div className="confirm-target">
          <strong>{title}</strong>
          <span>{displayRunPhase(summary.status)}</span>
          {counts.length ? <small>{counts.join(" · ")}</small> : null}
        </div>
        <div className="confirm-actions">
          <button className="ghost-button" type="button" onClick={onCancel} disabled={deleting}>
            取消
          </button>
          <button className="danger-button" type="button" onClick={onConfirm} disabled={deleting}>
            {deleting ? <RefreshCw size={14} /> : <Trash2 size={14} />}
            {deleting ? "正在删除" : "删除会话"}
          </button>
        </div>
      </section>
    </div>
  );
}

function historyTitle(summary: AnalysisRunSummary) {
  if (summary.industry?.trim()) return summary.industry.trim();
  if (summary.competitors?.length) return summary.competitors.slice(0, 3).join(", ");
  if (summary.originalPrompt?.trim()) return summary.originalPrompt.trim().slice(0, 28);
  return summary.id;
}
