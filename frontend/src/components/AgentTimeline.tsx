import { Clock, CheckCircle2, CircleDashed, XCircle } from "lucide-react";
import type { AgentName, AnalysisRun, StepStatus } from "../types";
import { AGENTS, AGENT_LABELS } from "../constants";
import { formatTime, latestStepByAgent, statusClass } from "../utils";

interface AgentTimelineProps {
  run: AnalysisRun | null;
  selectedAgent: AgentName | null;
  onSelectAgent: (agent: AgentName) => void;
  pendingClarification?: boolean;
}

export function AgentTimeline({ run, selectedAgent, onSelectAgent, pendingClarification = false }: AgentTimelineProps) {
  const stepsByAgent = latestStepByAgent(run ?? undefined);

  return (
    <div className="timeline">
      {AGENTS.map((agent) => {
        const steps = stepsByAgent.get(agent) ?? [];
        const latest = steps.at(-1);
        // 生成范围确认时后端还没返回 runId，SSE 无法订阅真实步骤；这里先用本地态补上 Clarifier 运行中状态。
        const isPendingClarifier = pendingClarification && !run && agent === "CLARIFIER";
        const status = isPendingClarifier ? "RUNNING" : latest?.status ?? "PENDING";
        const Icon = statusIcon(status);
        const summary = isPendingClarifier
          ? "正在执行澄清 Agent，生成范围确认内容"
          : latest?.outputSummary || latest?.inputSummary || "等待执行";
        const timeText = isPendingClarifier
          ? "进行中"
          : steps.length > 1 ? `${steps.length} 次` : formatTime(latest?.completedAt || latest?.startedAt);
        return (
          <button
            key={agent}
            type="button"
            className={`timeline-row ${statusClass(status)} ${selectedAgent === agent ? "selected" : ""}`}
            onClick={() => onSelectAgent(agent)}
          >
            <Icon size={17} />
            <span>
              <strong>{AGENT_LABELS[agent]}</strong>
              <small>{summary}</small>
            </span>
            <em>{timeText}</em>
          </button>
        );
      })}
    </div>
  );
}

function statusIcon(status: StepStatus | "PENDING") {
  if (status === "SUCCEEDED") return CheckCircle2;
  if (status === "FAILED") return XCircle;
  if (status === "RUNNING") return Clock;
  return CircleDashed;
}
