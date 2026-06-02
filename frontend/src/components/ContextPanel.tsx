import { MessageSquareText, SendHorizontal } from "lucide-react";
import type { AgentName, AnalysisContextMessage, ContextIntent } from "../types";
import { AGENTS, AGENT_LABELS } from "../constants";
import { formatTime } from "../utils";

const intentOptions: Array<{ label: string; value: ContextIntent }> = [
  { label: "调整范围", value: "ADJUST_SCOPE" },
  { label: "补充资料", value: "ADD_EVIDENCE" },
  { label: "指定重跑", value: "REQUEST_RERUN" },
  { label: "修订报告", value: "REVISE_REPORT" },
  { label: "人工备注", value: "COMMENT" }
];

interface ContextPanelProps {
  messages: AnalysisContextMessage[];
  value: string;
  intent: ContextIntent;
  targetAgent?: AgentName;
  disabled?: boolean;
  onValueChange: (value: string) => void;
  onIntentChange: (intent: ContextIntent) => void;
  onTargetAgentChange: (agent?: AgentName) => void;
  onSubmit: () => void;
}

export function ContextPanel({
  messages,
  value,
  intent,
  targetAgent,
  disabled,
  onValueChange,
  onIntentChange,
  onTargetAgentChange,
  onSubmit
}: ContextPanelProps) {
  const submitDisabled = intent === "REQUEST_RERUN"
    ? !targetAgent
    : !value.trim();

  return (
    <section className="panel context-panel">
      <div className="section-title">
        <div>
          <p className="eyebrow">上下文</p>
          <h2>补充上下文</h2>
        </div>
        <MessageSquareText size={18} />
      </div>

      <div className="intent-tabs">
        {intentOptions.map((option) => (
          <button
            key={option.value}
            type="button"
            className={intent === option.value ? "selected" : ""}
            onClick={() => onIntentChange(option.value)}
          >
            {option.label}
          </button>
        ))}
      </div>

      <textarea
        value={value}
        onChange={(event) => onValueChange(event.target.value)}
        placeholder={intent === "REQUEST_RERUN" ? "可选：补充这次重跑需要注意的调整说明" : "请输入补充背景、范围调整、证据材料或报告修订要求"}
        rows={4}
      />

      {intent === "REQUEST_RERUN" ? (
        <label className="context-target">
          目标 Agent
          <select value={targetAgent ?? ""} onChange={(event) => onTargetAgentChange(event.target.value as AgentName || undefined)}>
            <option value="">请选择要重跑的 Agent</option>
            {AGENTS.map((agent) => (
              <option key={agent} value={agent}>
                {AGENT_LABELS[agent]}
              </option>
            ))}
          </select>
        </label>
      ) : null}

      <button className="primary-button" type="button" onClick={onSubmit} disabled={disabled || submitDisabled}>
        <SendHorizontal size={15} /> {intent === "REQUEST_RERUN" ? "重跑 Agent" : "提交补充"}
      </button>

      <div className="context-list">
        {messages.length ? (
          messages.map((message) => (
            <div className="context-item" key={message.id}>
              <span>{message.intent}</span>
              <p>{message.content}</p>
              <small>{message.role} · {formatTime(message.createdAt)}</small>
            </div>
          ))
        ) : (
          <p className="muted-text">暂无上下文补充。后端接口接入后，用户的调整范围、补资料和重跑指令会沉淀在这里。</p>
        )}
      </div>
    </section>
  );
}
