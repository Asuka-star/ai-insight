import { useEffect, useRef } from "react";
import { Database, ExternalLink, Search, ShieldAlert } from "lucide-react";
import type { EvidenceSource } from "../types";
import { CollapsiblePanel } from "./CollapsiblePanel";

interface EvidencePanelProps {
  sources: EvidenceSource[];
  selectedCitationKey?: string;
  onSelectCitation: (citationKey: string) => void;
  collapsed: boolean;
  onToggle: () => void;
}

const TEXT = {
  eyebrow: "\u6eaf\u6e90",
  title: "\u8bc1\u636e\u6765\u6e90",
  empty: "\u6682\u65e0\u8bc1\u636e\u6765\u6e90",
  sourceCount: "\u6761\u6765\u6e90",
  selected: "\u5df2\u9009"
};

export function EvidencePanel({
  sources,
  selectedCitationKey,
  onSelectCitation,
  collapsed,
  onToggle
}: EvidencePanelProps) {
  const itemRefs = useRef<Record<string, HTMLElement | null>>({});
  const prevCitationKeyRef = useRef<string | undefined>(undefined);
  const prevCollapsedRef = useRef(collapsed);

  useEffect(() => {
    const wasCollapsed = prevCollapsedRef.current;
    prevCollapsedRef.current = collapsed;
    if (!selectedCitationKey || collapsed) return;
    const shouldScroll = selectedCitationKey !== prevCitationKeyRef.current || wasCollapsed;
    if (!shouldScroll) return;
    const selectedItem = itemRefs.current[selectedCitationKey];
    if (!selectedItem) return;
    // citation 变化或面板重新展开时滚动；轮询刷新 sources 不会反复抢滚动位置。
    prevCitationKeyRef.current = selectedCitationKey;
    selectedItem.scrollIntoView({
      block: "center",
      behavior: "smooth"
    });
  }, [collapsed, selectedCitationKey]);

  return (
    <CollapsiblePanel
      eyebrow={TEXT.eyebrow}
      title={TEXT.title}
      icon={<Search size={18} />}
      summary={`${sources.length} ${TEXT.sourceCount}${selectedCitationKey ? ` · ${TEXT.selected} [${selectedCitationKey}]` : ""}`}
      collapsed={collapsed}
      onToggle={onToggle}
    >
      <div className="evidence-list">
        {sources.length ? (
          sources.map((source) => (
            <article
              key={source.id}
              ref={(element) => {
                itemRefs.current[source.citationKey] = element;
              }}
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
              <span className={`evidence-key ${sourceQualityClass(source.sourceQuality)}`}>[{source.citationKey}]</span>
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
          <p className="muted-text">{TEXT.empty}</p>
        )}
      </div>
    </CollapsiblePanel>
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

function sourceQualityClass(sourceQuality?: string) {
  if (!sourceQuality) return "";
  return `quality-${sourceQuality.toLowerCase()}`;
}
