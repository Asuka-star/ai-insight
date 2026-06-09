import { useEffect, useMemo, useRef } from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import rehypeRaw from "rehype-raw";
import rehypeSanitize, { defaultSchema } from "rehype-sanitize";
import { FileText } from "lucide-react";
import type { AnalysisArtifact, ArtifactLocateRequest, EvidenceSource } from "../types";
import { ARTIFACT_LABELS } from "../constants";

interface ArtifactViewerProps {
  artifact?: AnalysisArtifact;
  sources?: EvidenceSource[];
  onSelectCitation: (citationKey: string) => void;
  locateRequest?: ArtifactLocateRequest;
}

const artifactSanitizeSchema = {
  ...defaultSchema,
  tagNames: [...(defaultSchema.tagNames ?? []), "br"],
  attributes: {
    ...defaultSchema.attributes,
    a: [...(defaultSchema.attributes?.a ?? []), ["href"]],
    code: [...(defaultSchema.attributes?.code ?? []), ["className"]],
    span: [...(defaultSchema.attributes?.span ?? []), ["className"]]
  }
};

export function ArtifactViewer({ artifact, sources = [], onSelectCitation, locateRequest }: ArtifactViewerProps) {
  const readerRef = useRef<HTMLElement>(null);
  const handledLocateKeyRef = useRef<string>();
  const sourcesByKey = useMemo(() => new Map(sources.map((source) => [source.citationKey, source])), [sources]);
  const artifactLabel = artifact ? ARTIFACT_LABELS[artifact.type] ?? artifact.type : "";
  const citationCount = artifact?.citationKeys?.length ?? 0;

  useEffect(() => {
    if (!artifact || !locateRequest || !readerRef.current) return;
    if (locateRequest.artifactId && locateRequest.artifactId !== artifact.id) return;
    const requestKey = `${locateRequest.requestId}:${artifact.id}`;
    if (handledLocateKeyRef.current === requestKey) return;
    const target = findLocateTarget(readerRef.current, artifact.content ?? "", locateRequest);
    if (!target) return;
    handledLocateKeyRef.current = requestKey;

    // 定位结果用滚动加短暂闪烁表达，避免用户只看到视图跳转却找不到具体句子。
    target.scrollIntoView({ block: "center", behavior: "smooth" });
    target.classList.remove("artifact-locate-flash");
    window.requestAnimationFrame(() => {
      target.classList.add("artifact-locate-flash");
    });
    const timer = window.setTimeout(() => target.classList.remove("artifact-locate-flash"), 1400);
    return () => window.clearTimeout(timer);
  }, [artifact?.content, artifact?.id, locateRequest]);

  if (!artifact) {
    return (
      <div className="empty-state">
        <FileText size={22} />
        <strong>暂无产物</strong>
        <p>创建任务后会显示报告、矩阵、复核结果和结构化信息。</p>
      </div>
    );
  }

  return (
    <article className="artifact-reader" ref={readerRef}>
      <header className="artifact-reader-header">
        <div>
          <p className="eyebrow">{artifactLabel}</p>
          <h2>{artifact.title || artifactLabel}</h2>
        </div>
        <div className="artifact-meta" aria-label="产物元信息">
          <span>v{artifact.version || 1}</span>
          <span>{citationCount} 条引用</span>
          <span>{sourceCoverageLabel(citationCount, sources.length)}</span>
        </div>
      </header>
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        rehypePlugins={[rehypeRaw, [rehypeSanitize, artifactSanitizeSchema]]}
        components={{
          a({ href, children }) {
            const citationKey = citationKeyFromHref(href);
            if (!citationKey) {
              return <a href={href} target="_blank" rel="noreferrer">{children}</a>;
            }
            const source = sourcesByKey.get(citationKey);
            return (
              <button
                className={`citation-chip ${citationQualityClass(source)}`}
                type="button"
                title={citationTitle(source)}
                onClick={() => onSelectCitation(citationKey)}
              >
                {children}
              </button>
            );
          },
          table({ children }) {
            return (
              <div className="artifact-table-wrap">
                <table>{children}</table>
              </div>
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
  // 把段落内的单个换行折叠为空格，避免 ReactMarkdown 把 LLM 输出中的软换行渲染成 <br>。
  // 双换行（段落分隔）和 Markdown 结构元素（标题、列表、表格、代码块等）不受影响。
  const joined = markdown
    .split(/\n{2,}/)
    .map((block) => {
      const trimmed = block.trimStart();
      if (/^[#`>|\-*+]|\d+\.\s/.test(trimmed)) {
        return block;
      }
      return block.replace(/\n/g, " ");
    })
    .join("\n\n");
  return joined.replace(/\[((?:S\d+\s*(?:[,，、]\s*)?)+)]/g, (_, citationGroup: string) => {
    const citationKeys = citationGroup.match(/S\d+/g) ?? [];
    return citationKeys.map((citationKey) => `[\\[${citationKey}\\]](#citation-${citationKey})`).join("");
  });
}

function citationKeyFromHref(href?: string) {
  const match = href?.match(/^#citation-(S\d+)$/);
  return match?.[1];
}

function citationTitle(source?: EvidenceSource) {
  if (!source) return "未找到对应证据来源";
  return `${source.title}\n${source.url}\n${source.snippet}`;
}

function citationQualityClass(source?: EvidenceSource) {
  const quality = source?.sourceQuality?.toLowerCase();
  if (!quality) return "";
  return `quality-${quality}`;
}

function sourceCoverageLabel(citationCount: number, sourceCount: number) {
  if (!sourceCount) return "暂无来源";
  if (!citationCount) return `${sourceCount} 个来源`;
  return `${sourceCount} 个来源可查`;
}

function findLocateTarget(reader: HTMLElement, markdown: string, request: ArtifactLocateRequest) {
  const blockElements = Array.from(reader.querySelectorAll<HTMLElement>(
    "p, li, blockquote, td, th, h1, h2, h3, h4, h5, h6"
  ));
  const markdownBlocks = splitMarkdownParagraphs(markdown);

  // 后端 paragraphIndex 来自原始 Markdown 段落数组；这里先按同一口径匹配，再逐级退到摘录、claim、citation。
  if (request.paragraphIndex !== undefined) {
    const paragraphBlock = markdownBlocks[request.paragraphIndex];
    const match = paragraphBlock ? findElementByText(blockElements, paragraphBlock, true) : null;
    if (match) return match;
  }

  if (request.excerpt) {
    const match = findElementByText(blockElements, request.excerpt, true);
    if (match) return match;
  }

  if (request.claimText) {
    const match = findElementByText(blockElements, request.claimText, false);
    if (match) return match;
  }

  if (request.citationKey) {
    const citation = Array.from(reader.querySelectorAll<HTMLElement>(".citation-chip"))
      .find((element) => normalizeSearchText(element.textContent).includes(normalizeSearchText(request.citationKey)));
    return citation?.closest<HTMLElement>("p, li, blockquote, td, th") ?? citation ?? null;
  }

  if (request.claimId) {
    return findElementByText(blockElements, request.claimId, false);
  }

  return null;
}

function splitMarkdownParagraphs(markdown: string) {
  // 保留空段对应的索引位置，避免 paragraphIndex 被前端过滤后错位。
  return markdown
    .split(/\r?\n\s*\r?\n/)
    .map((block) => block.trim());
}

function findElementByText(elements: HTMLElement[], text: string, strict: boolean) {
  const normalizedNeedle = normalizeSearchText(text);
  if (!normalizedNeedle) return null;

  const exact = elements.find((element) => {
    const haystack = normalizeSearchText(element.textContent);
    if (!haystack) return false;
    // 反向包含只允许较长块，防止一个短词命中表格/列表里的错误位置。
    return haystack.includes(normalizedNeedle) || (haystack.length >= 18 && normalizedNeedle.includes(haystack));
  });
  if (exact) return exact;

  const snippets = textSnippets(text);
  const snippetMatch = elements.find((element) => {
    const haystack = normalizeSearchText(element.textContent);
    if (!haystack) return false;
    return snippets.some((snippet) => haystack.includes(snippet));
  });
  if (snippetMatch) return snippetMatch;

  if (strict) return null;

  return elements
    .map((element) => ({
      element,
      score: overlapScore(normalizeSearchText(element.textContent), normalizedNeedle)
    }))
    .filter((item) => item.score >= 0.42)
    .sort((left, right) => right.score - left.score)[0]?.element ?? null;
}

function textSnippets(text: string) {
  return text
    .split(/[。！？!?；;：:\n\r]/)
    .map(normalizeSearchText)
    .filter((snippet) => snippet.length >= 12)
    .sort((left, right) => right.length - left.length)
    .slice(0, 4);
}

function normalizeSearchText(value?: string | null) {
  return (value ?? "")
    .toLowerCase()
    .replace(/[`*_#[\](){}<>|>~-]/g, " ")
    .replace(/\s+/g, " ")
    .trim();
}

function overlapScore(haystack: string, needle: string) {
  const needleTerms = searchTerms(needle);
  if (!needleTerms.length) return 0;
  const haystackTerms = new Set(searchTerms(haystack));
  const matched = needleTerms.filter((term) => haystackTerms.has(term)).length;
  return matched / needleTerms.length;
}

function searchTerms(value: string) {
  return value
    .split(/[^a-z0-9\u4e00-\u9fa5]+/i)
    .map((term) => term.trim())
    .filter((term) => term.length >= 2);
}
