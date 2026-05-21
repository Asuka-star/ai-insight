import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { FileText } from "lucide-react";
import type { AnalysisArtifact } from "../types";
import { ARTIFACT_LABELS } from "../constants";

interface ArtifactViewerProps {
  artifact?: AnalysisArtifact;
  onSelectCitation: (citationKey: string) => void;
}

export function ArtifactViewer({ artifact, onSelectCitation }: ArtifactViewerProps) {
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
        <span>{artifact.citationKeys?.length ?? 0} citations</span>
      </div>
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        components={{
          text({ children }) {
            return <>{renderCitationText(String(children), onSelectCitation)}</>;
          }
        }}
      >
        {artifact.content || "该产物暂无内容"}
      </ReactMarkdown>
    </article>
  );
}

function renderCitationText(text: string, onSelectCitation: (citationKey: string) => void) {
  const parts = text.split(/(\[S\d+])/g);
  return parts.map((part, index) => {
    const match = part.match(/^\[(S\d+)]$/);
    if (!match) return part;
    return (
      <button key={`${part}-${index}`} className="citation-chip" type="button" onClick={() => onSelectCitation(match[1])}>
        {part}
      </button>
    );
  });
}
