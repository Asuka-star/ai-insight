import { useState } from "react";
import { ChevronDown, GitCompareArrows } from "lucide-react";
import type { AnalysisArtifact } from "../types";
import { ARTIFACT_LABELS } from "../constants";

interface ArtifactVersionsPanelProps {
  artifacts: AnalysisArtifact[];
  selectedArtifactId?: string;
  onSelectArtifact: (artifactId: string) => void;
}

export function ArtifactVersionsPanel({ artifacts, selectedArtifactId, onSelectArtifact }: ArtifactVersionsPanelProps) {
  const [collapsedGroups, setCollapsedGroups] = useState<Record<string, boolean>>({});
  const grouped = artifacts.reduce<Record<string, AnalysisArtifact[]>>((acc, artifact) => {
    const key = artifact.type;
    acc[key] = [...(acc[key] ?? []), artifact];
    return acc;
  }, {});

  const toggleGroup = (type: string) => {
    setCollapsedGroups((current) => ({
      ...current,
      [type]: !current[type]
    }));
  };

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
      {Object.entries(grouped).map(([type, items]) => {
        const collapsed = collapsedGroups[type] ?? true;
        const label = ARTIFACT_LABELS[type as keyof typeof ARTIFACT_LABELS] ?? type;
        return (
          <section className={`version-group ${collapsed ? "collapsed" : ""}`} key={type}>
            <button
              className="version-heading"
              type="button"
              aria-expanded={!collapsed}
              aria-label={collapsed ? `展开${label}` : `折叠${label}`}
              onClick={() => toggleGroup(type)}
            >
              <strong>{label}</strong>
              <span>{items.length} 个版本</span>
              <ChevronDown size={16} />
            </button>
            {collapsed ? null : items.map((artifact) => (
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
        );
      })}
    </div>
  );
}
