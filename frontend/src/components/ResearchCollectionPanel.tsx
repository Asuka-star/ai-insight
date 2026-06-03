import { AlertTriangle, ChevronDown, ListChecks, Search, Target } from "lucide-react";
import type { ResearchCollectionPlan, ResearchRepairTarget, ResearchSubtask } from "../types";
import { CollapsiblePanel } from "./CollapsiblePanel";

interface ResearchCollectionPanelProps {
  plan?: ResearchCollectionPlan;
  collapsed: boolean;
  onToggle: () => void;
}

const STATUS_LABELS: Record<string, string> = {
  PENDING: "待执行",
  SEARCHING: "搜索中",
  SEARCHED: "已搜索",
  FETCHING: "抓取中",
  RETRIEVING_RAG: "检索中",
  RANKING: "排序中",
  SUCCEEDED: "完成",
  FAILED: "失败",
  CANCELLED: "取消",
  SKIPPED: "跳过"
};

export function ResearchCollectionPanel({ plan, collapsed, onToggle }: ResearchCollectionPanelProps) {
  const subtasks = plan?.subtasks ?? [];
  const candidateUrls = plan?.candidateUrls ?? [];
  const coverageGaps = plan?.coverageGaps ?? [];
  const repairTargets = plan?.repairTargets ?? [];
  const leadPlan = plan?.leadResearchPlan;
  const succeeded = subtasks.filter((task) => task.status === "SUCCEEDED").length;
  const failed = subtasks.filter((task) => task.status === "FAILED").length;
  const acceptedEvidence = subtasks.reduce((sum, task) => sum + (task.acceptedEvidenceCount ?? 0), 0);

  return (
    <CollapsiblePanel
      eyebrow="采集"
      title="采集计划"
      icon={<ListChecks size={18} />}
      summary={`${subtasks.length} 任务 · ${coverageGaps.length} 缺口 · ${repairTargets.length} 补采`}
      collapsed={collapsed}
      onToggle={onToggle}
      className="research-collection-panel"
    >
      {plan ? (
        <div className="collection-panel-body">
          <div className="collection-summary-grid">
            <SummaryItem label="任务" value={subtasks.length} />
            <SummaryItem label="候选 URL" value={candidateUrls.length} />
            <SummaryItem label="采纳证据" value={acceptedEvidence} />
            <SummaryItem label="完成/失败" value={`${succeeded}/${failed}`} />
          </div>

          {leadPlan?.objective || leadPlan?.focusAreas?.length || leadPlan?.rationale?.length ? (
            <section className="collection-block">
              <div className="collection-block-title">
                <Target size={14} />
                <strong>Lead 规划</strong>
                {plan.planSource ? <small>{plan.planSource}</small> : null}
              </div>
              {leadPlan.objective ? <p className="collection-objective">{leadPlan.objective}</p> : null}
              {leadPlan.focusAreas?.length ? <ChipList values={leadPlan.focusAreas.slice(0, 8)} /> : null}
              {leadPlan.rationale?.length ? (
                <ul className="collection-note-list">
                  {leadPlan.rationale.slice(0, 3).map((item) => (
                    <li key={item}>{item}</li>
                  ))}
                </ul>
              ) : null}
            </section>
          ) : null}

          <section className="collection-block">
            <div className="collection-block-title">
              <Search size={14} />
              <strong>子任务</strong>
            </div>
            {subtasks.length ? (
              <div className="collection-task-list">
                {subtasks.slice(0, 8).map((task) => (
                  <TaskRow key={task.id ?? `${task.competitorName}-${task.dimension}`} task={task} />
                ))}
              </div>
            ) : (
              <p className="muted-text">暂无采集子任务</p>
            )}
          </section>

          {coverageGaps.length ? (
            <section className="collection-block">
              <div className="collection-block-title warning">
                <AlertTriangle size={14} />
                <strong>覆盖缺口</strong>
              </div>
              <div className="collection-gap-list">
                {coverageGaps.slice(0, 6).map((gap) => (
                  <article className="collection-gap" key={gap.id ?? `${gap.competitorName}-${gap.dimension}`}>
                    <strong>{gap.competitorName || "未指定竞品"} / {gap.dimension || "未指定维度"}</strong>
                    <small>{gap.existingEvidenceCount ?? 0}/{gap.requiredEvidenceCount ?? 0} 证据</small>
                    {gap.missingSourceTypes?.length ? <ChipList values={gap.missingSourceTypes} compact /> : null}
                    {gap.reason ? <p>{gap.reason}</p> : null}
                  </article>
                ))}
              </div>
            </section>
          ) : null}

          {repairTargets.length ? (
            <section className="collection-block">
              <div className="collection-block-title">
                <ChevronDown size={14} />
                <strong>补采目标</strong>
              </div>
              <div className="collection-target-list">
                {repairTargets.slice(0, 6).map((target) => (
                  <RepairTargetRow key={target.id ?? `${target.competitorName}-${target.dimension}`} target={target} />
                ))}
              </div>
            </section>
          ) : null}
        </div>
      ) : (
        <p className="muted-text">采集计划会在 Researcher 启动后生成</p>
      )}
    </CollapsiblePanel>
  );
}

function SummaryItem({ label, value }: { label: string; value: number | string }) {
  return (
    <div className="collection-summary-item">
      <strong>{value}</strong>
      <span>{label}</span>
    </div>
  );
}

function TaskRow({ task }: { task: ResearchSubtask }) {
  return (
    <article className="collection-task-row">
      <div>
        <strong>{task.competitorName || "未指定竞品"}</strong>
        <small>{task.dimension || "public_search"}</small>
      </div>
      <span className={`collection-status ${statusClass(task.status)}`}>{STATUS_LABELS[task.status ?? ""] ?? task.status ?? "待执行"}</span>
      <small>{task.acceptedEvidenceCount ?? 0} 证据 · {task.candidateUrlCount ?? 0} 候选</small>
    </article>
  );
}

function RepairTargetRow({ target }: { target: ResearchRepairTarget }) {
  return (
    <article className="collection-target-row">
      <div>
        <strong>{target.competitorName || "未指定竞品"} / {target.dimension || "public_search"}</strong>
        <small>{target.priority || "BACKFILL"} · {target.status || "PENDING"}</small>
      </div>
      {target.sourcePreferences?.length ? <ChipList values={target.sourcePreferences} compact /> : null}
      {target.queries?.[0] ? <p>{target.queries[0]}</p> : null}
    </article>
  );
}

function ChipList({ values, compact = false }: { values: string[]; compact?: boolean }) {
  return (
    <div className={`collection-chip-list ${compact ? "compact" : ""}`}>
      {values.map((value) => (
        <span key={value}>{value}</span>
      ))}
    </div>
  );
}

function statusClass(status?: string) {
  if (status === "SUCCEEDED") return "success";
  if (status === "FAILED" || status === "CANCELLED") return "danger";
  if (status === "SEARCHING" || status === "FETCHING" || status === "RETRIEVING_RAG" || status === "RANKING") return "running";
  return "idle";
}
