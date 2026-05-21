import { ExternalLink, Search } from "lucide-react";
import type { EvidenceSource } from "../types";

interface EvidencePanelProps {
  sources: EvidenceSource[];
  selectedCitationKey?: string;
  onSelectCitation: (citationKey: string) => void;
}

export function EvidencePanel({ sources, selectedCitationKey, onSelectCitation }: EvidencePanelProps) {
  return (
    <section className="panel">
      <div className="section-title">
        <div>
          <p className="eyebrow">Traceability</p>
          <h2>证据来源</h2>
        </div>
        <Search size={18} />
      </div>
      <div className="evidence-list">
        {sources.length ? (
          sources.map((source) => (
            <button
              key={source.id}
              type="button"
              className={`evidence-item ${selectedCitationKey === source.citationKey ? "active" : ""}`}
              onClick={() => onSelectCitation(source.citationKey)}
            >
              <span className="evidence-key">[{source.citationKey}]</span>
              <strong>{source.title}</strong>
              <p>{source.snippet}</p>
              <small><ExternalLink size={12} /> {source.url}</small>
            </button>
          ))
        ) : (
          <p className="muted-text">暂无证据来源</p>
        )}
      </div>
    </section>
  );
}
