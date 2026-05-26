import { CheckCircle2, ClipboardCheck, PlayCircle } from "lucide-react";
import type { AnalysisRun } from "../types";
import { SOURCE_OPTIONS } from "../constants";
import { displayRunPhase, resolveRunPhase } from "../utils";

interface ScopeConfirmationPanelProps {
  run: AnalysisRun | null;
  localConfirmed: boolean;
  industry: string;
  competitors: string;
  dimensions: string;
  outputGoal: string;
  sources: string[];
  sourceUrls: string;
  onIndustryChange: (value: string) => void;
  onCompetitorsChange: (value: string) => void;
  onDimensionsChange: (value: string) => void;
  onOutputGoalChange: (value: string) => void;
  onSourcesChange: (value: string[]) => void;
  onSourceUrlsChange: (value: string) => void;
  onCreate: () => void;
  onConfirm: () => void;
  onStart: () => void;
  creating: boolean;
  busy: boolean;
}

export function ScopeConfirmationPanel({
  run,
  localConfirmed,
  industry,
  competitors,
  dimensions,
  outputGoal,
  sources,
  sourceUrls,
  onIndustryChange,
  onCompetitorsChange,
  onDimensionsChange,
  onOutputGoalChange,
  onSourcesChange,
  onSourceUrlsChange,
  onCreate,
  onConfirm,
  onStart,
  creating,
  busy
}: ScopeConfirmationPanelProps) {
  const phase = resolveRunPhase(run);
  const draft = run?.clarificationDraft;
  const questions = draft?.clarificationQuestions ?? [];
  const isConfirmed = Boolean(draft?.confirmed || localConfirmed);
  const phaseText = String(phase);
  const hasScopeInput = [industry, competitors, dimensions, outputGoal, sourceUrls].some((value) => value.trim());
  const canCreate = !run && !busy && !creating && hasScopeInput;
  const canConfirm = Boolean(run) && !busy && ["DRAFT", "AWAITING_CONFIRMATION", "PENDING"].includes(phaseText);
  const canStart = Boolean(run) && !busy && isConfirmed && ["PENDING", "NEEDS_USER_INPUT"].includes(phaseText);

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

      <div className="source-preference-block">
        <div className="source-preference-header">
          <span>重点覆盖来源</span>
          <small>默认优先官方和权威资料</small>
        </div>
        <div className="source-options">
          {SOURCE_OPTIONS.map((source) => (
            <label key={source.value} className="check-row">
              <input
                type="checkbox"
                checked={sources.includes(source.value)}
                onChange={(event) => {
                  onSourcesChange(
                    event.target.checked
                      ? [...sources, source.value]
                      : sources.filter((value) => value !== source.value)
                  );
                }}
              />
              {source.label}
            </label>
          ))}
        </div>
      </div>

      <label>
        公开来源 URL
        <textarea
          value={sourceUrls}
          onChange={(event) => onSourceUrlsChange(event.target.value)}
          placeholder="每行一个公开网页 URL，例如官网、价格页、产品文档"
          rows={3}
        />
      </label>

      {questions.length ? (
        <div className="question-box">
          <strong>范围确认建议</strong>
          {questions.map((question) => (
            <p key={question}>{question}</p>
          ))}
        </div>
      ) : (
        <div className="question-box quiet">
          <strong>等待范围确认内容</strong>
          <p>填写范围信息后，这里会展示待确认的问题和结构化范围。</p>
        </div>
      )}

      <div className="scope-actions">
        {!run ? (
          <button className="primary-button" type="button" onClick={onCreate} disabled={!canCreate}>
            <PlayCircle size={15} /> 生成范围确认
          </button>
        ) : (
          <>
            <button type="button" onClick={onConfirm} disabled={!canConfirm}>
              <CheckCircle2 size={15} /> {isConfirmed ? "已确认范围" : "确认范围"}
            </button>
            <button className="primary-button" type="button" onClick={onStart} disabled={!canStart}>
              <PlayCircle size={15} /> 开始 Agent 分析
            </button>
          </>
        )}
      </div>

      {!run ? (
        <p className="scope-hint"><ClipboardCheck size={14} /> 直接填写范围信息，生成确认内容后再启动分析。</p>
      ) : null}
    </section>
  );
}
