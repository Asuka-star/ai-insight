import { Clock, CheckCircle2, CircleDashed, XCircle } from "lucide-react";
import type { AgentName, AnalysisRun, StepStatus } from "../types";
import { AGENTS, AGENT_LABELS } from "../constants";
import { formatTime, latestStepByAgent, statusClass } from "../utils";

interface AgentTimelineProps {
  run: AnalysisRun | null;
  selectedAgent: AgentName | null;
  onSelectAgent: (agent: AgentName) => void;
}

export function AgentTimeline({ run, selectedAgent, onSelectAgent }: AgentTimelineProps) {
  const stepsByAgent = latestStepByAgent(run ?? undefined);

  return (
    <div className="timeline">
      {AGENTS.map((agent) => {
        const steps = stepsByAgent.get(agent) ?? [];
        const latest = steps.at(-1);
        const status = latest?.status ?? "PENDING";
        const Icon = statusIcon(status);
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
              <small>{latest?.outputSummary || latest?.inputSummary || "等待执行"}</small>
            </span>
            <em>{steps.length > 1 ? `${steps.length} 次` : formatTime(latest?.completedAt || latest?.startedAt)}</em>
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
