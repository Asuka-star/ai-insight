import { Boxes, GitPullRequestArrow, ListChecks } from "lucide-react";
import type { AnalysisClaim, CompetitorProfile, ResearchPackage, WorkflowTransition } from "../types";

interface SchemaPanelProps {
  researchPackage?: ResearchPackage;
  profiles: CompetitorProfile[];
  claims: AnalysisClaim[];
  transitions: WorkflowTransition[];
}

export function SchemaPanel({ researchPackage, profiles, claims, transitions }: SchemaPanelProps) {
  const sourceCount = researchPackage?.sources?.length ?? 0;
  const missingCount = researchPackage?.missingEvidenceTypes?.length ?? 0;

  return (
    <section className="panel schema-panel">
      <div className="section-title">
        <div>
          <p className="eyebrow">Schema</p>
          <h2>Structured State</h2>
        </div>
        <Boxes size={18} />
      </div>

      <div className="schema-stats">
        <SchemaStat label="Sources" value={sourceCount} />
        <SchemaStat label="Profiles" value={profiles.length} />
        <SchemaStat label="Claims" value={claims.length} />
        <SchemaStat label="Routes" value={transitions.length} />
      </div>

      {missingCount ? (
        <div className="schema-alert">
          <strong>{missingCount} evidence gaps</strong>
          <span>{researchPackage?.missingEvidenceTypes.join(", ")}</span>
        </div>
      ) : null}

      <div className="schema-section">
        <div className="schema-heading">
          <ListChecks size={15} />
          <strong>Claims</strong>
        </div>
        {claims.length ? (
          claims.map((claim) => (
            <div className="schema-card" key={claim.id}>
              <span>{claim.type ?? "CLAIM"} / {claim.confidence ?? "MEDIUM"}</span>
              <p>{claim.content}</p>
              <small>{(claim.evidenceIds ?? []).join(", ") || "No evidence bound"}</small>
            </div>
          ))
        ) : (
          <p className="muted-text">No structured claims yet.</p>
        )}
      </div>

      <div className="schema-section">
        <div className="schema-heading">
          <Boxes size={15} />
          <strong>Competitors</strong>
        </div>
        {profiles.length ? (
          profiles.map((profile) => (
            <div className="schema-card" key={profile.productName ?? profile.companyName}>
              <span>{profile.productName ?? "Unknown product"}</span>
              <p>{profile.positioning}</p>
              <small>
                {(profile.featureTree?.roots?.length ?? 0)} feature roots / {(profile.pricingModel?.plans?.length ?? 0)} plans
              </small>
            </div>
          ))
        ) : (
          <p className="muted-text">No competitor profiles yet.</p>
        )}
      </div>

      <div className="schema-section">
        <div className="schema-heading">
          <GitPullRequestArrow size={15} />
          <strong>Review Routes</strong>
        </div>
        {transitions.length ? (
          transitions.map((transition) => (
            <div className="schema-route" key={transition.id}>
              <span>{transition.route}</span>
              <p>{transition.sourceNode} {"->"} {transition.targetNode}</p>
              <small>{transition.reviewAction ?? "PASS"} / attempt {transition.attempt}</small>
            </div>
          ))
        ) : (
          <p className="muted-text">No REVIEW_GATE decisions yet.</p>
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
