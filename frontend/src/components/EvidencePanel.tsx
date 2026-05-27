import { Database, ExternalLink, Search, ShieldAlert } from "lucide-react";
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
          <p className="eyebrow">溯源</p>
          <h2>证据来源</h2>
        </div>
        <Search size={18} />
      </div>
      <div className="evidence-list">
        {sources.length ? (
          sources.map((source) => (
            <article
              key={source.id}
              role="button"
              tabIndex={0}
              className={`evidence-item ${selectedCitationKey === source.citationKey ? "active" : ""}`}
              onClick={() => onSelectCitation(source.citationKey)}
              onKeyDown={(event) => {
                if (event.key === "Enter" || event.key === " ") {
                  event.preventDefault();
                  onSelectCitation(source.citationKey);
                }
              }}
            >
              <span className="evidence-key">[{source.citationKey}]</span>
              <strong>{source.title}</strong>
              <p>{source.snippet}</p>
              <div className="evidence-badges">
                <small className="evidence-source-type">{source.sourceType}</small>
                {source.sourceQuality ? <small className={`quality-${source.sourceQuality.toLowerCase()}`}>{source.sourceQuality}</small> : null}
                {source.collectionStatus ? <small>{source.collectionStatus}</small> : null}
                {source.freshness ? <small>{source.freshness}</small> : null}
                {source.cacheHit ? (
                  <small className="cache-hit">
                    <Database size={11} /> CACHE
                  </small>
                ) : null}
              </div>
              {source.failureReason && source.failureReason !== "NONE" ? (
                <small className="evidence-warning">
                  <ShieldAlert size={12} /> {source.failureReason}
                </small>
              ) : null}
              {source.contentHash ? <small className="evidence-hash">hash {shortHash(source.contentHash)}</small> : null}
              {displayComplianceNote(source.complianceNote) ? (
                <small className="evidence-note">{displayComplianceNote(source.complianceNote)}</small>
              ) : null}
              {isExternalUrl(source.url) ? (
                <a className="evidence-link" href={source.url} target="_blank" rel="noreferrer" onClick={(event) => event.stopPropagation()}>
                  <ExternalLink size={12} /> {source.url}
                </a>
              ) : (
                <small><ExternalLink size={12} /> {source.url}</small>
              )}
            </article>
          ))
        ) : (
          <p className="muted-text">暂无证据来源</p>
        )}
      </div>
    </section>
  );
}

function isExternalUrl(url: string) {
  return /^https?:\/\//i.test(url);
}

function shortHash(hash: string) {
  return hash.length > 12 ? hash.slice(0, 12) : hash;
}

function displayComplianceNote(note?: string) {
  if (!note) {
    return "";
  }
  return note
    .replace(/\s*statusCode=.*$/i, "")
    .replace(/\s*cacheHit=true;.*$/i, "")
    .trim();
}
