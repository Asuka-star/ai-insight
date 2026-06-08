import { Background, Controls, Handle, MarkerType, Position, ReactFlow, type Edge, type Node, type NodeProps } from "@xyflow/react";
import { MessageCircle, Search, Layers, BarChart3, FileText, ShieldCheck, Check, Clock, AlertCircle, Loader2, type LucideIcon } from "lucide-react";
import { useMemo } from "react";
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
  accent: string;
  Icon: LucideIcon;
  onSelectAgent: (agent: AgentName) => void;
};

/** Route → 中文标签 */
const ROUTE_LABELS: Record<string, string> = {
  recollect: "补采",
  reextract: "重新抽取",
  reanalyze: "重分析",
  revise: "修订报告"
};

/** Per-agent accent color and icon */
const AGENT_VISUAL: Record<AgentName, { accent: string; Icon: LucideIcon }> = {
  CLARIFIER:  { accent: "#7c5cbf", Icon: MessageCircle },
  RESEARCHER: { accent: "#08777b", Icon: Search },
  EXTRACTOR:  { accent: "#2b6ca3", Icon: Layers },
  ANALYST:    { accent: "#1a7a52", Icon: BarChart3 },
  WRITER:     { accent: "#8b6914", Icon: FileText },
  REVIEWER:   { accent: "#b04a28", Icon: ShieldCheck }
};

export function WorkflowGraph({ run, onSelectAgent }: WorkflowGraphProps) {
  const stepsByAgent = latestStepByAgent(run ?? undefined);
  const nodes: Node<AgentNodeData>[] = AGENTS.map((agent, index) => {
    const steps = stepsByAgent.get(agent) ?? [];
    const latest = steps.at(-1);
    const visual = AGENT_VISUAL[agent];
    const layout = graphLayout(agent, index);
    return {
      id: agent,
      type: "agent",
      position: layout.position,
      sourcePosition: layout.sourcePosition,
      targetPosition: layout.targetPosition,
      data: {
        agent,
        label: AGENT_LABELS[agent],
        stage: AGENT_STAGE[agent],
        status: latest?.status ?? "PENDING",
        count: steps.length,
        accent: visual.accent,
        Icon: visual.Icon,
        onSelectAgent
      }
    };
  });

  // Compute actual feedback edges from workflow transitions
  const feedbackEdges = useMemo(() => {
    const transitions = run?.workflowTransitions ?? [];
    const rejections = transitions.filter(
      (t) => t.sourceNode === "REVIEWER" && t.targetNode && t.route && t.route !== "finish"
    );
    const grouped = new Map<string, { target: string; count: number; route: string }>();
    for (const t of rejections) {
      const key = t.targetNode!;
      const existing = grouped.get(key);
      if (existing) {
        existing.count += 1;
      } else {
        grouped.set(key, { target: key, count: 1, route: t.route! });
      }
    }
    const edges: Edge[] = [];
    for (const { target, count, route } of grouped.values()) {
      const label = ROUTE_LABELS[route] ?? route;
      edges.push(
        feedbackEdge("REVIEWER", target as AgentName, count > 1 ? `${label} ×${count}` : label)
      );
    }
    return edges;
  }, [run?.workflowTransitions]);

  const edges: Edge[] = [
    edge("CLARIFIER", "RESEARCHER", "预检", "preflight"),
    edge("RESEARCHER", "EXTRACTOR"),
    edge("EXTRACTOR", "ANALYST"),
    edge("ANALYST", "WRITER"),
    edge("WRITER", "REVIEWER"),
    ...feedbackEdges
  ];

  return (
    <div className="flow-wrap">
      <ReactFlow
        nodes={nodes}
        edges={edges}
        nodeTypes={{ agent: AgentNode }}
        fitView
        minZoom={0.4}
        maxZoom={1.3}
        fitViewOptions={{ padding: 0.18 }}
        proOptions={{ hideAttribution: true }}
      >
        <Background gap={20} size={1} color="#d4ddd8" />
        <Controls showInteractive={false} />
      </ReactFlow>
    </div>
  );
}

/* ── Node component ── */

function AgentNode({ data, sourcePosition = Position.Right, targetPosition = Position.Left }: NodeProps<Node<AgentNodeData>>) {
  const { status, count, accent, Icon, onSelectAgent, agent, label, stage } = data;
  const cls = statusClass(status);

  return (
    <button
      className={`flow-node flow-node--${cls}`}
      type="button"
      style={{ "--accent": accent } as React.CSSProperties}
      onClick={() => onSelectAgent(agent)}
    >
      <Handle type="target" position={targetPosition} className="flow-handle" />
      <div className="flow-node__icon">
        <Icon size={22} />
      </div>
      <div className="flow-node__body">
        <span className="flow-node__title">{label}</span>
        <span className="flow-node__stage">{stage}</span>
      </div>
      <StatusBadge status={status} count={count} />
      <Handle type="source" position={sourcePosition} className="flow-handle" />
    </button>
  );
}

function StatusBadge({ status, count }: { status: StepStatus | "PENDING"; count: number }) {
  if (status === "RUNNING") {
    return <span className="flow-badge flow-badge--running"><Loader2 size={13} className="spin" /> 运行中</span>;
  }
  if (status === "SUCCEEDED") {
    return <span className="flow-badge flow-badge--ok">{count > 1 ? `${count} 次` : <><Check size={13} /> 完成</>}</span>;
  }
  if (status === "FAILED") {
    return <span className="flow-badge flow-badge--fail"><AlertCircle size={13} /> 失败</span>;
  }
  if (status === "CANCELLED") {
    return <span className="flow-badge flow-badge--cancel">已取消</span>;
  }
  return <span className="flow-badge flow-badge--pending"><Clock size={13} /> 待执行</span>;
}

/* ── Layout ── */

function graphLayout(agent: AgentName, index: number) {
  const compactLayout: Record<AgentName, {
    position: { x: number; y: number };
    sourcePosition: Position;
    targetPosition: Position;
  }> = {
    CLARIFIER: {
      position: { x: 56, y: 34 },
      sourcePosition: Position.Right,
      targetPosition: Position.Left
    },
    RESEARCHER: {
      position: { x: 328, y: 34 },
      sourcePosition: Position.Right,
      targetPosition: Position.Left
    },
    EXTRACTOR: {
      position: { x: 600, y: 34 },
      sourcePosition: Position.Bottom,
      targetPosition: Position.Left
    },
    ANALYST: {
      position: { x: 600, y: 198 },
      sourcePosition: Position.Left,
      targetPosition: Position.Top
    },
    WRITER: {
      position: { x: 328, y: 198 },
      sourcePosition: Position.Left,
      targetPosition: Position.Right
    },
    REVIEWER: {
      position: { x: 56, y: 198 },
      sourcePosition: Position.Top,
      targetPosition: Position.Right
    }
  };

  return compactLayout[agent] ?? {
    position: {
      x: 56 + (index % 3) * 272,
      y: 34 + Math.floor(index / 3) * 164
    },
    sourcePosition: Position.Right,
    targetPosition: Position.Left
  };
}

/* ── Edge factories ── */

function edge(source: AgentName, target: AgentName, label?: string, kind?: "preflight"): Edge {
  return {
    id: `${source}-${target}-${label ?? "next"}`,
    source,
    target,
    label,
    type: "smoothstep",
    markerEnd: { type: MarkerType.ArrowClosed, width: 16, height: 16 },
    style: { strokeWidth: kind === "preflight" ? 1.5 : 2 },
    className: kind === "preflight" ? "preflight-edge" : "main-edge"
  };
}

function feedbackEdge(source: AgentName, target: AgentName, label: string): Edge {
  return {
    id: `${source}-${target}-feedback-${label}`,
    source,
    target,
    label,
    animated: true,
    type: "smoothstep",
    markerEnd: { type: MarkerType.ArrowClosed, width: 16, height: 16 },
    style: { strokeWidth: 2 },
    className: "feedback-edge"
  };
}
