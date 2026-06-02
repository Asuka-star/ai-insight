import { GitCompareArrows } from "lucide-react";
import type { AnalysisArtifact } from "../types";
import { ARTIFACT_LABELS } from "../constants";

interface ArtifactVersionsPanelProps {
  artifacts: AnalysisArtifact[];
  selectedArtifactId?: string;
  onSelectArtifact: (artifactId: string) => void;
}

export function ArtifactVersionsPanel({ artifacts, selectedArtifactId, onSelectArtifact }: ArtifactVersionsPanelProps) {
  const grouped = artifacts.reduce<Record<string, AnalysisArtifact[]>>((acc, artifact) => {
    const key = artifact.type;
    acc[key] = [...(acc[key] ?? []), artifact];
    return acc;
  }, {});

  if (!artifacts.length) {
    return (
      <div className="empty-state">
        <GitCompareArrows size={22} />
        <strong>暂无产物</strong>
        <p>Agent 产物生成后，这里会按类型展示全部产物和版本记录。</p>
      </div>
    );
  }

  return (
    <div className="version-panel">
      {Object.entries(grouped).map(([type, items]) => (
        <section className="version-group" key={type}>
          <div className="version-heading">
            <strong>{ARTIFACT_LABELS[type as keyof typeof ARTIFACT_LABELS] ?? type}</strong>
            <span>{items.length} 个版本</span>
          </div>
          {items.map((artifact) => (
            <button
              key={artifact.id}
              type="button"
              className={`version-item ${artifact.id === selectedArtifactId ? "selected" : ""}`}
              onClick={() => onSelectArtifact(artifact.id)}
            >
              <span>{artifact.title || type}</span>
              <strong>v{artifact.version || 1}</strong>
              <small>{artifact.citationKeys?.length ?? 0} 条引用</small>
            </button>
          ))}
        </section>
      ))}
    </div>
  );
}
