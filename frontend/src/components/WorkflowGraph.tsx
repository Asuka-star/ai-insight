import { Background, Controls, Handle, MarkerType, Position, ReactFlow, type Edge, type Node, type NodeProps } from "@xyflow/react";
import { GitBranch } from "lucide-react";
import type { AgentName, AnalysisRun, StepStatus } from "../types";
import { AGENTS, AGENT_LABELS, AGENT_STAGE } from "../constants";
import { latestStepByAgent, statusClass } from "../utils";

interface WorkflowGraphProps {
  run: AnalysisRun | null;
  onSelectAgent: (agent: AgentName) => void;
}

type AgentNodeData = {
  agent: AgentName;
  label: string;
  stage: string;
  status: StepStatus | "PENDING";
  count: number;
  onSelectAgent: (agent: AgentName) => void;
};

export function WorkflowGraph({ run, onSelectAgent }: WorkflowGraphProps) {
  const stepsByAgent = latestStepByAgent(run ?? undefined);
  const nodes: Node<AgentNodeData>[] = AGENTS.map((agent, index) => {
    const steps = stepsByAgent.get(agent) ?? [];
    const latest = steps.at(-1);
    return {
      id: agent,
      type: "agent",
      position: graphPosition(index),
      data: {
        agent,
        label: AGENT_LABELS[agent],
        stage: AGENT_STAGE[agent],
        status: latest?.status ?? "PENDING",
        count: steps.length,
        onSelectAgent
      }
    };
  });

  const edges: Edge[] = [
    edge("CLARIFIER", "RESEARCHER", "预检", "preflight"),
    edge("RESEARCHER", "EXTRACTOR"),
    edge("EXTRACTOR", "ANALYST"),
    edge("ANALYST", "WRITER"),
    edge("WRITER", "REVIEWER"),
    edge("REVIEWER", "FINALIZER"),
    edge("REVIEWER", "RESEARCHER", "recollect", "feedback"),
    edge("REVIEWER", "ANALYST", "reanalyze", "feedback"),
    edge("REVIEWER", "WRITER", "revise", "feedback")
  ];

  return (
    <div className="flow-wrap">
      <ReactFlow
        nodes={nodes}
        edges={edges}
        nodeTypes={{ agent: AgentNode }}
        fitView
        minZoom={0.45}
        maxZoom={1.25}
        proOptions={{ hideAttribution: true }}
      >
        <Background gap={18} size={1} />
        <Controls showInteractive={false} />
      </ReactFlow>
    </div>
  );
}

function AgentNode({ data }: NodeProps<Node<AgentNodeData>>) {
  return (
    <button className={`flow-node ${statusClass(data.status)}`} type="button" onClick={() => data.onSelectAgent(data.agent)}>
      <Handle type="target" position={Position.Left} />
      <span className="flow-icon"><GitBranch size={14} /></span>
      <strong>{data.label}</strong>
      <small>{data.agent}</small>
      <em>{data.count > 1 ? `${data.count} runs` : data.status}</em>
      <Handle type="source" position={Position.Right} />
    </button>
  );
}

function graphPosition(index: number) {
  const row = index % 2;
  return {
    x: Math.floor(index / 2) * 230,
    y: row * 145
  };
}

function edge(source: AgentName, target: AgentName, label?: string, kind?: "feedback" | "preflight"): Edge {
  return {
    id: `${source}-${target}-${label ?? "next"}`,
    source,
    target,
    label,
    animated: kind === "feedback",
    type: "smoothstep",
    markerEnd: { type: MarkerType.ArrowClosed },
    className: kind === "feedback" ? "feedback-edge" : kind === "preflight" ? "preflight-edge" : ""
  };
}
