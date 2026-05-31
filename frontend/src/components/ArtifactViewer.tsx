import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { FileText } from "lucide-react";
import type { AnalysisArtifact, EvidenceSource } from "../types";
import { ARTIFACT_LABELS } from "../constants";

interface ArtifactViewerProps {
  artifact?: AnalysisArtifact;
  sources?: EvidenceSource[];
  onSelectCitation: (citationKey: string) => void;
}

export function ArtifactViewer({ artifact, sources = [], onSelectCitation }: ArtifactViewerProps) {
  const sourcesByKey = new Map(sources.map((source) => [source.citationKey, source]));

  if (!artifact) {
    return (
      <div className="empty-state">
        <FileText size={22} />
        <strong>暂无产物</strong>
        <p>创建任务后会显示报告、矩阵、复核结果和结构化 Schema。</p>
      </div>
    );
  }

  return (
    <article className="artifact-reader">
      <div className="artifact-meta">
        <span>{ARTIFACT_LABELS[artifact.type] ?? artifact.type}</span>
        <span>v{artifact.version || 1}</span>
        <span>{artifact.citationKeys?.length ?? 0} 条引用</span>
      </div>
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        components={{
          a({ href, children }) {
            const citationKey = citationKeyFromHref(href);
            if (!citationKey) {
              return <a href={href}>{children}</a>;
            }
            const source = sourcesByKey.get(citationKey);
            return (
              <button
                className="citation-chip"
                type="button"
                title={citationTitle(source)}
                onClick={() => onSelectCitation(citationKey)}
              >
                {children}
              </button>
            );
          }
        }}
      >
        {linkifyCitations(artifact.content || "该产物暂无内容")}
      </ReactMarkdown>
    </article>
  );
}

function linkifyCitations(markdown: string) {
  return markdown.replace(/\[(S\d+)]/g, "[\\[$1\\]](#citation-$1)");
}

function citationKeyFromHref(href?: string) {
  const match = href?.match(/^#citation-(S\d+)$/);
  return match?.[1];
}

function citationTitle(source?: EvidenceSource) {
  if (!source) return "未找到对应证据来源";
  return `${source.title}\n${source.url}\n${source.snippet}`;
}
