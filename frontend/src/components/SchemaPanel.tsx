import { Boxes, GitPullRequestArrow, ListChecks } from "lucide-react";
import type { AnalysisClaim, CompetitorProfile, ResearchPackage, WorkflowTransition } from "../types";

interface SchemaPanelProps {
  researchPackage?: ResearchPackage;
  profiles: CompetitorProfile[];
  claims: AnalysisClaim[];
  transitions: WorkflowTransition[];
  embedded?: boolean;
}

export function SchemaPanel({ researchPackage, profiles, claims, transitions, embedded }: SchemaPanelProps) {
  const sourceCount = researchPackage?.sources?.length ?? 0;
  const missingCount = researchPackage?.missingEvidenceTypes?.length ?? 0;

  return (
    <section className={embedded ? "schema-panel embedded" : "panel schema-panel"}>
      <div className="section-title">
        <div>
          <p className="eyebrow">Schema</p>
          <h2>结构化状态</h2>
        </div>
        <Boxes size={18} />
      </div>

      <div className="schema-stats">
        <SchemaStat label="来源" value={sourceCount} />
        <SchemaStat label="画像" value={profiles.length} />
        <SchemaStat label="结论" value={claims.length} />
        <SchemaStat label="路由" value={transitions.length} />
      </div>

      {missingCount ? (
        <div className="schema-alert">
          <strong>{missingCount} 个证据缺口</strong>
          <span>{researchPackage?.missingEvidenceTypes.join(", ")}</span>
        </div>
      ) : null}

      <div className="schema-section">
        <div className="schema-heading">
          <ListChecks size={15} />
          <strong>分析结论</strong>
        </div>
        {claims.length ? (
          claims.map((claim) => (
            <div className="schema-card" key={claim.id}>
              <span>{claim.type ?? "CLAIM"} / {claim.confidence ?? "MEDIUM"}</span>
              <p>{claim.content}</p>
              <small>{(claim.evidenceIds ?? []).join(", ") || "未绑定证据"}</small>
            </div>
          ))
        ) : (
          <p className="muted-text">暂无结构化结论。</p>
        )}
      </div>

      <div className="schema-section">
        <div className="schema-heading">
          <Boxes size={15} />
          <strong>竞品画像</strong>
        </div>
        {profiles.length ? (
          profiles.map((profile) => (
            <div className="schema-card" key={profile.productName ?? profile.companyName}>
              <span>{profile.productName ?? "未知产品"}</span>
              <p>{profile.positioning}</p>
              <small>
                {(profile.featureTree?.roots?.length ?? 0)} 个功能根节点 / {(profile.pricingModel?.plans?.length ?? 0)} 个定价方案
              </small>
            </div>
          ))
        ) : (
          <p className="muted-text">暂无竞品画像。</p>
        )}
      </div>

      <div className="schema-section">
        <div className="schema-heading">
          <GitPullRequestArrow size={15} />
          <strong>复核路由</strong>
        </div>
        {transitions.length ? (
          transitions.map((transition) => (
            <div className="schema-route" key={transition.id}>
              <span>{transition.route}</span>
              <p>{transition.sourceNode} {"->"} {transition.targetNode}</p>
              <small>{transition.reviewAction ?? "PASS"} / 第 {transition.attempt + 1} 次判断</small>
            </div>
          ))
        ) : (
          <p className="muted-text">暂无 REVIEW_GATE 决策。</p>
        )}
      </div>
    </section>
  );
}

function SchemaStat({ label, value }: { label: string; value: number }) {
  return (
    <div className="schema-stat">
      <strong>{value}</strong>
      <span>{label}</span>
    </div>
  );
}
