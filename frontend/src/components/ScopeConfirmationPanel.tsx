import { memo, useCallback, useEffect, useState } from "react";
import { CheckCircle2, ClipboardCheck, PlayCircle, RefreshCw } from "lucide-react";
import type { AnalysisRun, ClarificationItem, ClarificationOption } from "../types";
import { displayRunPhase, resolveRunPhase } from "../utils";

interface ScopeConfirmationPanelProps {
  run: AnalysisRun | null;
  localConfirmed: boolean;
  industry: string;
  competitors: string;
  dimensions: string;
  outputGoal: string;
  sourceUrls: string;
  maxReviewReworkAttempts: number;
  onIndustryChange: (value: string) => void;
  onCompetitorsChange: (value: string) => void;
  onDimensionsChange: (value: string) => void;
  onOutputGoalChange: (value: string) => void;
  onSourceUrlsChange: (value: string) => void;
  onMaxReviewReworkAttemptsChange: (value: number) => void;
  onApplyClarificationOption: (field: string, values: string[]) => void;
  onCreate: () => void;
  onReclarify: () => void;
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
  sourceUrls,
  maxReviewReworkAttempts,
  onIndustryChange,
  onCompetitorsChange,
  onDimensionsChange,
  onOutputGoalChange,
  onSourceUrlsChange,
  onMaxReviewReworkAttemptsChange,
  onApplyClarificationOption,
  onCreate,
  onReclarify,
  onConfirm,
  onStart,
  creating,
  busy
}: ScopeConfirmationPanelProps) {
  const [formEditVersion, setFormEditVersion] = useState(0);
  const phase = resolveRunPhase(run);
  const draft = run?.clarificationDraft;
  const questions = draft?.clarificationQuestions ?? [];
  const clarificationItems = draft?.clarificationItems ?? [];
  const isConfirmed = Boolean(draft?.confirmed || localConfirmed);
  const waitingForClarification = creating && !isConfirmed;
  const pendingQuestions = isConfirmed || waitingForClarification ? [] : questions;
  const pendingClarificationItems = isConfirmed || waitingForClarification
    ? []
    : clarificationItems.filter((item) => item.field !== "sourcePreferences");
  const phaseText = String(phase);
  const hasDraft = Boolean(run && draft);
  const hasClarificationRequests = pendingQuestions.length > 0 || pendingClarificationItems.length > 0;
  const agentRunning = Boolean(run?.steps?.some((step) => step.status === "RUNNING"));
  const scopeEditable = !busy && !creating && !agentRunning && (!run || ["DRAFT", "AWAITING_CONFIRMATION", "PENDING"].includes(phaseText));
  // Clarifier 没产出待确认项时，用户无需再点一次“确认范围”，启动前会直接保存当前结构化范围。
  const canStartWithoutConfirm = hasDraft && !hasClarificationRequests;
  const mainAnalysisStarted = Boolean(run?.steps?.some((step) => step.agentName !== "CLARIFIER"));
  const hasScopeInput = [industry, competitors, dimensions, outputGoal, sourceUrls].some((value) => value.trim());
  const canCreate = !run && !busy && !creating && hasScopeInput;
  const canConfirm = Boolean(run) && hasClarificationRequests && !busy && !agentRunning && ["DRAFT", "AWAITING_CONFIRMATION", "PENDING"].includes(phaseText);
  const canReclarify = Boolean(run) && !busy && !agentRunning && ["DRAFT", "AWAITING_CONFIRMATION", "PENDING"].includes(phaseText);
  const canStart = Boolean(run)
    && !busy
    && !agentRunning
    && !mainAnalysisStarted
    && (isConfirmed || canStartWithoutConfirm)
    && ["AWAITING_CONFIRMATION", "PENDING", "NEEDS_USER_INPUT"].includes(phaseText);

  const handleManualEdit = useCallback((update: () => void) => {
    setFormEditVersion((version) => version + 1);
    update();
  }, []);

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
          <input value={industry} onChange={(event) => handleManualEdit(() => onIndustryChange(event.target.value))} placeholder="请输入行业或业务方向" disabled={!scopeEditable} />
        </label>
        <label>
          报告用途
          <input value={outputGoal} onChange={(event) => handleManualEdit(() => onOutputGoalChange(event.target.value))} placeholder="请输入报告使用场景" disabled={!scopeEditable} />
        </label>
        <label>
          竞品列表
          <input value={competitors} onChange={(event) => handleManualEdit(() => onCompetitorsChange(event.target.value))} placeholder="请输入竞品名称，多个用逗号分隔" disabled={!scopeEditable} />
        </label>
        <label>
          分析维度
          <input value={dimensions} onChange={(event) => handleManualEdit(() => onDimensionsChange(event.target.value))} placeholder="请输入关注维度，多个用逗号分隔" disabled={!scopeEditable} />
        </label>
      </div>

      <label>
        公开来源 URL
        <textarea
          value={sourceUrls}
          onChange={(event) => handleManualEdit(() => onSourceUrlsChange(event.target.value))}
          placeholder="每行一个公开网页 URL，例如官网、价格页、产品文档"
          rows={3}
          disabled={!scopeEditable}
        />
      </label>

      <label>
        质检自动返工
        {/* 这是单次 run 的执行选项，由 Review Gate 读取；默认一轮返工，让可自动修复的问题进入闭环。 */}
        <select
          value={maxReviewReworkAttempts}
          onChange={(event) => onMaxReviewReworkAttemptsChange(Number(event.target.value))}
          disabled={!scopeEditable}
        >
          <option value={0}>不自动返工</option>
          <option value={1}>最多 1 轮</option>
          <option value={2}>最多 2 轮</option>
        </select>
      </label>

      {waitingForClarification ? (
        <div className="question-box quiet">
          <strong>正在生成范围确认</strong>
          <p>Clarifier 正在整理结构化范围，完成后会展示可确认的选项。</p>
        </div>
      ) : !run ? (
        <div className="question-box quiet">
          <strong>等待范围确认内容</strong>
          <p>填写范围信息后，这里会展示待确认的问题和结构化范围。</p>
        </div>
      ) : isConfirmed ? (
        <div className="question-box done">
          <strong>范围已确认</strong>
          <p>当前结构化范围已保存，可以开始或继续 Agent 分析。</p>
        </div>
      ) : pendingQuestions.length ? (
        <div className="question-box">
          <strong>范围确认建议</strong>
          {pendingQuestions.map((question) => (
            <p key={question}>{question}</p>
          ))}
        </div>
      ) : pendingClarificationItems.length ? (
        <div className="question-box">
          <strong>请确认下方澄清项</strong>
          <p>Clarifier 已生成可选澄清内容，请选择或调整后再确认范围。</p>
        </div>
      ) : (
        <div className="question-box done">
          <strong>无需额外澄清</strong>
          <p>当前范围已足够明确，可以直接开始 Agent 分析。</p>
        </div>
      )}

      {pendingClarificationItems.length ? (
        <div className="clarification-items">
          {pendingClarificationItems.map((item) => (
            <ClarificationItemCard
              item={item}
              key={`${item.field}-${item.question}`}
              selectedValues={selectedValuesForField(item.field, {
                industry,
                competitors,
                dimensions,
                outputGoal,
                sourceUrls
              }, item.selectedValues)}
              selectionSyncVersion={formEditVersion}
              onApply={onApplyClarificationOption}
              disabled={!scopeEditable}
            />
          ))}
        </div>
      ) : null}

      <div className="scope-actions">
        {!run ? (
          <button className="primary-button" type="button" onClick={onCreate} disabled={!canCreate}>
            <PlayCircle size={15} /> 生成范围确认
          </button>
        ) : (
          <>
            <button type="button" onClick={onReclarify} disabled={!canReclarify}>
              <RefreshCw size={15} /> 重新澄清
            </button>
            {hasClarificationRequests ? (
              <button type="button" onClick={onConfirm} disabled={!canConfirm}>
                <CheckCircle2 size={15} /> {isConfirmed ? "已确认范围" : "确认范围"}
              </button>
            ) : null}
            <button className="primary-button" type="button" onClick={onStart} disabled={!canStart}>
              <PlayCircle size={15} /> 开始 Agent 分析
            </button>
          </>
        )}
      </div>

      {!run ? (
        <p className="scope-hint"><ClipboardCheck size={14} /> 直接填写范围信息，生成确认内容后再启动分析。</p>
      ) : !scopeEditable ? (
        <p className="scope-hint"><ClipboardCheck size={14} /> 分析开始后范围已锁定，避免执行产物与分析范围不一致。</p>
      ) : null}
    </section>
  );
}

const ClarificationItemCard = memo(function ClarificationItemCard({
  item,
  selectedValues,
  selectionSyncVersion,
  onApply,
  disabled
}: {
  item: ClarificationItem;
  selectedValues: string[];
  selectionSyncVersion: number;
  onApply: (field: string, values: string[]) => void;
  disabled?: boolean;
}) {
  const computedSelectedIndex = selectedIndexForOptions(item.options, selectedValues);
  const itemSelectionKey = clarificationItemSelectionKey(item);
  const [selectedOptionIndex, setSelectedOptionIndex] = useState(computedSelectedIndex);

  useEffect(() => {
    setSelectedOptionIndex(computedSelectedIndex);
  }, [itemSelectionKey, selectionSyncVersion]);

  const handleSelectOption = useCallback((index: number, values: string[]) => {
    setSelectedOptionIndex(index);
    onApply(item.field, values);
  }, [item.field, onApply]);

  return (
    <div className="clarification-item">
      <div className="clarification-item-header">
        <strong>{item.question}</strong>
        {item.required ? <span>必选</span> : null}
      </div>
      {item.reason ? <p>{item.reason}</p> : null}
      <div className="clarification-options">
        {item.options.map((option, index) => (
          <ClarificationOptionButton
            option={option}
            key={`${option.label}-${option.values.join("|")}`}
            index={index}
            selected={index === selectedOptionIndex}
            onSelect={handleSelectOption}
            disabled={disabled}
          />
        ))}
      </div>
    </div>
  );
}, areClarificationItemsEqual);

function areClarificationItemsEqual(
  previous: {
    item: ClarificationItem;
    selectedValues: string[];
    selectionSyncVersion: number;
    onApply: (field: string, values: string[]) => void;
    disabled?: boolean;
  },
  next: {
    item: ClarificationItem;
    selectedValues: string[];
    selectionSyncVersion: number;
    onApply: (field: string, values: string[]) => void;
    disabled?: boolean;
  }
) {
  return previous.item === next.item
    && previous.selectionSyncVersion === next.selectionSyncVersion
    && previous.disabled === next.disabled
    && previous.onApply === next.onApply;
}

function ClarificationOptionButton({
  option,
  index,
  selected,
  onSelect,
  disabled
}: {
  option: ClarificationOption;
  index: number;
  selected: boolean;
  onSelect: (index: number, values: string[]) => void;
  disabled?: boolean;
}) {
  return (
    <button
      className={[
        "clarification-option",
        option.recommended ? "recommended" : "",
        selected ? "selected" : ""
      ].filter(Boolean).join(" ")}
      type="button"
      aria-pressed={selected}
      onClick={() => onSelect(index, option.values ?? [])}
      disabled={disabled}
    >
      <span>{option.label}</span>
      {option.description ? <small>{option.description}</small> : null}
      {option.values?.length ? <em>{option.values.join("、")}</em> : null}
    </button>
  );
}

function selectedValuesForField(
  field: string,
  values: {
    industry: string;
    competitors: string;
    dimensions: string;
    outputGoal: string;
    sourceUrls: string;
  },
  fallbackValues?: string[]
) {
  const selected = (() => {
    if (field === "industry") return singleValue(values.industry);
    if (field === "competitors") return splitFieldValues(values.competitors);
    if (field === "dimensions") return splitFieldValues(values.dimensions);
    if (field === "sourceUrls") return splitFieldValues(values.sourceUrls);
    if (field === "outputGoal") return singleValue(values.outputGoal);
    return [];
  })();
  return selected.length ? selected : fallbackValues ?? [];
}

function selectedIndexForOptions(options: ClarificationOption[], selectedValues: string[]) {
  const normalizedSelected = normalizeValues(selectedValues);
  const exactMatchIndexes = options
    .map((option, index) => ({ index, values: normalizeValues(option.values ?? []), recommended: option.recommended }))
    .filter((candidate) => sameNormalizedValues(candidate.values, normalizedSelected));
  if (exactMatchIndexes.length) {
    return exactMatchIndexes.find((candidate) => candidate.recommended)?.index ?? exactMatchIndexes[0].index;
  }
  const recommendedIndex = options.findIndex((option) => option.recommended);
  if (recommendedIndex >= 0) return recommendedIndex;
  return options.length ? 0 : -1;
}

function clarificationItemSelectionKey(item: ClarificationItem) {
  return [
    item.field,
    item.question,
    item.options.map((option) => `${option.label}:${normalizeValues(option.values ?? []).join("|")}:${option.recommended}`).join(";")
  ].join("::");
}

function sameNormalizedValues(left: string[], right: string[]) {
  if (left.length !== right.length) return false;
  return left.every((value, index) => value === right[index]);
}

function singleValue(value: string) {
  const trimmed = value.trim();
  return trimmed ? [trimmed] : [];
}

function splitFieldValues(value: string) {
  return value
    .split(/[\r\n,，、]+/)
    .map((item) => item.trim())
    .filter(Boolean);
}

function normalizeValues(values: string[]) {
  return values
    .map((value) => value.trim().toLowerCase())
    .filter(Boolean)
    .sort();
}
