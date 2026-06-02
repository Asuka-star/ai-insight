import { useEffect, useRef, useState } from "react";
import type { MutableRefObject } from "react";
import { ChevronDown, Database, ExternalLink, Search, ShieldAlert } from "lucide-react";
import type { EvidenceSource } from "../types";
import { CollapsiblePanel } from "./CollapsiblePanel";

interface EvidencePanelProps {
  sources: EvidenceSource[];
  selectedCitationKey?: string;
  selectedCitationRequestId: number;
  onSelectCitation: (citationKey: string) => void;
  collapsed: boolean;
  onToggle: () => void;
}

const TEXT = {
  eyebrow: "\u6eaf\u6e90",
  title: "\u8bc1\u636e\u6765\u6e90",
  empty: "\u6682\u65e0\u8bc1\u636e\u6765\u6e90",
  sourceCount: "\u6761\u6765\u6e90",
  selected: "\u5df2\u9009",
  expand: "\u5c55\u5f00",
  collapse: "\u6536\u8d77"
};

const SOURCE_QUALITY_GROUPS = ["HIGH", "MEDIUM", "LOW"] as const;
type SourceQualityGroup = typeof SOURCE_QUALITY_GROUPS[number];

export function EvidencePanel({
  sources,
  selectedCitationKey,
  selectedCitationRequestId,
  onSelectCitation,
  collapsed,
  onToggle
}: EvidencePanelProps) {
  const itemRefs = useRef<Record<string, HTMLElement | null>>({});
  const prevCitationKeyRef = useRef<string | undefined>(undefined);
  const prevCitationRequestIdRef = useRef(0);
  const prevCollapsedRef = useRef(collapsed);
  const [collapsedQualityGroups, setCollapsedQualityGroups] = useState<Record<SourceQualityGroup, boolean>>({
    HIGH: false,
    MEDIUM: false,
    LOW: false
  });
  const groupedSources = groupSources(sources);

  useEffect(() => {
    if (!selectedCitationKey || collapsed) return;
    const selectedSource = sources.find((source) => source.citationKey === selectedCitationKey);
    if (!selectedSource) return;
    const qualityGroup = sourceQualityGroup(selectedSource.sourceQuality);
    setCollapsedQualityGroups((current) => {
      if (!current[qualityGroup]) return current;
      return {
        ...current,
        [qualityGroup]: false
      };
    });
  }, [collapsed, selectedCitationKey, selectedCitationRequestId, sources]);

  useEffect(() => {
    const wasCollapsed = prevCollapsedRef.current;
    prevCollapsedRef.current = collapsed;
    if (!selectedCitationKey || collapsed) return;
    const selectedSource = sources.find((source) => source.citationKey === selectedCitationKey);
    if (!selectedSource) return;
    const qualityGroup = sourceQualityGroup(selectedSource.sourceQuality);
    if (collapsedQualityGroups[qualityGroup]) return;
    const shouldScroll = selectedCitationKey !== prevCitationKeyRef.current
      || selectedCitationRequestId !== prevCitationRequestIdRef.current
      || wasCollapsed;
    if (!shouldScroll) return;
    const selectedItem = itemRefs.current[selectedCitationKey];
    if (!selectedItem) return;
    // citation 变化或面板重新展开时滚动；轮询刷新 sources 不会反复抢滚动位置。
    prevCitationKeyRef.current = selectedCitationKey;
    prevCitationKeyRef.current = selectedCitationKey;
    prevCitationRequestIdRef.current = selectedCitationRequestId;
    selectedItem.scrollIntoView({
      block: "center",
      behavior: "smooth"
    });
  }, [collapsed, collapsedQualityGroups, selectedCitationKey, selectedCitationRequestId, sources]);

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
          SOURCE_QUALITY_GROUPS.map((quality) => {
            const groupSources = groupedSources[quality];
            const qualityCollapsed = collapsedQualityGroups[quality];
            return groupSources.length ? (
              <div className={`evidence-group ${qualityCollapsed ? "collapsed" : ""}`} key={quality}>
                <div className="evidence-group-title">
                  <span className={`severity-dot ${quality.toLowerCase()}`} />
                  <strong>{SOURCE_QUALITY_LABELS[quality]}</strong>
                  <small>{groupSources.length}</small>
                  <button
                    className="severity-toggle"
                    type="button"
                    aria-expanded={!qualityCollapsed}
                    aria-label={`${qualityCollapsed ? TEXT.expand : TEXT.collapse}${SOURCE_QUALITY_LABELS[quality]}`}
                    onClick={() => toggleQualityGroup(quality)}
                  >
                    <ChevronDown size={14} />
                  </button>
                </div>
                {qualityCollapsed ? null : (
                  <>
                    <p className="evidence-group-note">{SOURCE_QUALITY_DESCRIPTIONS[quality]}</p>
                    {groupSources.map((source) => (
                      <EvidenceItem
                        key={source.id ?? source.citationKey}
                        source={source}
                        selected={selectedCitationKey === source.citationKey}
                        onSelectCitation={onSelectCitation}
                        itemRefs={itemRefs}
                      />
                    ))}
                  </>
                )}
              </div>
            ) : null;
          })
        ) : (
          <p className="muted-text">{TEXT.empty}</p>
        )}
      </div>
    </CollapsiblePanel>
  );

  function toggleQualityGroup(quality: SourceQualityGroup) {
    setCollapsedQualityGroups((current) => ({
      ...current,
      [quality]: !current[quality]
    }));
  }
}

function EvidenceItem({
  source,
  selected,
  onSelectCitation,
  itemRefs
}: {
  source: EvidenceSource;
  selected: boolean;
  onSelectCitation: (citationKey: string) => void;
  itemRefs: MutableRefObject<Record<string, HTMLElement | null>>;
}) {
  return (
    <article
      ref={(element) => {
        itemRefs.current[source.citationKey] = element;
      }}
      role="button"
      tabIndex={0}
      className={`evidence-item ${selected ? "active" : ""}`}
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
  );
}

const SOURCE_QUALITY_LABELS: Record<SourceQualityGroup, string> = {
  HIGH: "High",
  MEDIUM: "Medium",
  LOW: "Low"
};

const SOURCE_QUALITY_DESCRIPTIONS: Record<SourceQualityGroup, string> = {
  HIGH: "\u5b98\u65b9\u3001\u6587\u6863\u3001\u5b9a\u4ef7\u9875\u6216\u5185\u90e8\u53ef\u7528\u8d44\u6599\uff0c\u4f18\u5148\u7528\u4e8e\u652f\u6491\u5173\u952e\u7ed3\u8bba\u3002",
  MEDIUM: "\u4fe1\u606f\u53ef\u7528\u4f46\u6743\u5a01\u6027\u6216\u5b8c\u6574\u5ea6\u5c45\u4e2d\uff0c\u9002\u5408\u4f5c\u4e3a\u8865\u5145\u8bc1\u636e\u3002",
  LOW: "\u5f31\u8bc1\u636e\u3001\u6458\u8981\u3001\u793e\u533a\u53cd\u9988\u6216\u6293\u53d6\u4e0d\u5b8c\u6574\u6765\u6e90\uff0c\u6b63\u5f0f\u7ed3\u8bba\u524d\u5efa\u8bae\u518d\u786e\u8ba4\u3002"
};

function groupSources(sources: EvidenceSource[]) {
  const groups: Record<SourceQualityGroup, EvidenceSource[]> = {
    HIGH: [],
    MEDIUM: [],
    LOW: []
  };
  for (const source of sources) {
    groups[sourceQualityGroup(source.sourceQuality)].push(source);
  }
  for (const quality of SOURCE_QUALITY_GROUPS) {
    groups[quality] = [...groups[quality]].sort(compareCitationOrder);
  }
  return groups;
}

function sourceQualityGroup(sourceQuality?: string): SourceQualityGroup {
  const normalized = sourceQuality?.trim().toUpperCase();
  if (normalized === "HIGH" || normalized === "INTERNAL_ONLY") return "HIGH";
  if (normalized === "LOW" || normalized === "UNUSABLE") return "LOW";
  return "MEDIUM";
}

function compareCitationOrder(left: EvidenceSource, right: EvidenceSource) {
  const leftNumber = citationNumber(left.citationKey);
  const rightNumber = citationNumber(right.citationKey);
  if (leftNumber !== rightNumber) {
    return leftNumber - rightNumber;
  }
  return left.citationKey.localeCompare(right.citationKey);
}

function citationNumber(citationKey: string) {
  const match = citationKey.match(/^S(\d+)$/i);
  return match ? Number(match[1]) : Number.MAX_SAFE_INTEGER;
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
