import { CheckCircle2, ClipboardCheck, PlayCircle } from "lucide-react";
import type { AnalysisRun } from "../types";
import { displayRunPhase, resolveRunPhase } from "../utils";

interface ScopeConfirmationPanelProps {
  run: AnalysisRun | null;
  localConfirmed: boolean;
  industry: string;
  competitors: string;
  dimensions: string;
  outputGoal: string;
  onIndustryChange: (value: string) => void;
  onCompetitorsChange: (value: string) => void;
  onDimensionsChange: (value: string) => void;
  onOutputGoalChange: (value: string) => void;
  onConfirm: () => void;
  onStart: () => void;
  busy: boolean;
}

export function ScopeConfirmationPanel({
  run,
  localConfirmed,
  industry,
  competitors,
  dimensions,
  outputGoal,
  onIndustryChange,
  onCompetitorsChange,
  onDimensionsChange,
  onOutputGoalChange,
  onConfirm,
  onStart,
  busy
}: ScopeConfirmationPanelProps) {
  const phase = resolveRunPhase(run);
  const draft = run?.clarificationDraft;
  const questions = draft?.clarificationQuestions ?? [];
  const isConfirmed = Boolean(draft?.confirmed || localConfirmed);
  const phaseText = String(phase);
  const canConfirm = Boolean(run) && !busy && ["DRAFT", "CLARIFYING", "AWAITING_CONFIRMATION", "PENDING"].includes(phaseText);
  const canStart = Boolean(run) && !busy && ["AWAITING_CONFIRMATION", "PENDING", "NEEDS_USER_INPUT"].includes(phaseText);

  return (
    <section className="panel scope-panel">
      <div className="section-title">
        <div>
          <p className="eyebrow">范围</p>
          <h2>范围确认</h2>
        </div>
        <span className={`phase-pill ${String(phase).toLowerCase()}`}>{displayRunPhase(String(phase))}</span>
      </div>

      <div className="scope-grid">
        <label>
          行业方向
          <input value={industry} onChange={(event) => onIndustryChange(event.target.value)} placeholder="请输入行业或业务方向" />
        </label>
        <label>
          报告用途
          <input value={outputGoal} onChange={(event) => onOutputGoalChange(event.target.value)} placeholder="请输入报告使用场景" />
        </label>
        <label>
          竞品列表
          <input value={competitors} onChange={(event) => onCompetitorsChange(event.target.value)} placeholder="请输入竞品名称，多个用逗号分隔" />
        </label>
        <label>
          分析维度
          <input value={dimensions} onChange={(event) => onDimensionsChange(event.target.value)} placeholder="请输入关注维度，多个用逗号分隔" />
        </label>
      </div>

      {questions.length ? (
        <div className="question-box">
          <strong>澄清 Agent 建议确认</strong>
          {questions.map((question) => (
            <p key={question}>{question}</p>
          ))}
        </div>
      ) : (
        <div className="question-box quiet">
          <strong>等待澄清草稿</strong>
          <p>后端接入 clarificationDraft 后，这里会展示待确认的问题和结构化范围。</p>
        </div>
      )}

      <div className="scope-actions">
        <button type="button" onClick={onConfirm} disabled={!canConfirm}>
          <CheckCircle2 size={15} /> {isConfirmed ? "已确认范围" : "确认范围"}
        </button>
        <button className="primary-button" type="button" onClick={onStart} disabled={!canStart}>
          <PlayCircle size={15} /> 开始 Agent 分析
        </button>
      </div>

      {!run ? (
        <p className="scope-hint"><ClipboardCheck size={14} /> 先创建任务草稿，再确认范围并启动分析。</p>
      ) : null}
    </section>
  );
}
