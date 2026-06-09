import { useEffect, useMemo, useState } from "react";
import { AlertTriangle, CheckCircle2, Clock3, FileText, RefreshCw, Trash2, UploadCloud, X } from "lucide-react";
import type { EvidenceSource } from "../types";

interface ResourcePackDrawerProps {
  open: boolean;
  sources: EvidenceSource[];
  disabled: boolean;
  uploading: boolean;
  processingCount: number;
  deletingCitationKey?: string;
  file: File | null;
  inputKey: number;
  title: string;
  sourceType: string;
  notes: string;
  onClose: () => void;
  onFileChange: (file: File | null) => void;
  onTitleChange: (value: string) => void;
  onSourceTypeChange: (value: string) => void;
  onNotesChange: (value: string) => void;
  onUpload: () => void;
  onDelete: (citationKey: string) => void;
  onSelectCitation: (citationKey: string) => void;
}

export function ResourcePackDrawer({
  open,
  sources,
  disabled,
  uploading,
  processingCount,
  deletingCitationKey,
  file,
  inputKey,
  title,
  sourceType,
  notes,
  onClose,
  onFileChange,
  onTitleChange,
  onSourceTypeChange,
  onNotesChange,
  onUpload,
  onDelete,
  onSelectCitation
}: ResourcePackDrawerProps) {
  const [activeCitationKey, setActiveCitationKey] = useState<string>();
  const activeSource = useMemo(
    () => sources.find((source) => source.citationKey === activeCitationKey) ?? sources[0],
    [activeCitationKey, sources]
  );

  useEffect(() => {
    if (!open) return;
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        onClose();
      }
    };
    window.addEventListener("keydown", handleKeyDown);
    return () => window.removeEventListener("keydown", handleKeyDown);
  }, [onClose, open]);

  useEffect(() => {
    if (!open) return;
    if (!sources.some((source) => source.citationKey === activeCitationKey)) {
      setActiveCitationKey(sources[0]?.citationKey);
    }
  }, [activeCitationKey, open, sources]);

  if (!open) return null;

  return (
    <div className="history-overlay" role="presentation" onMouseDown={onClose}>
      <aside
        className="history-drawer resource-pack-drawer"
        role="dialog"
        aria-label="用户资源包"
        aria-modal="true"
        onMouseDown={(event) => event.stopPropagation()}
      >
        <div className="history-header">
          <div>
            <p className="eyebrow">资源</p>
            <h2>用户资源包</h2>
          </div>
          <button className="icon-button" type="button" onClick={onClose} aria-label="关闭用户资源包">
            <X size={17} />
          </button>
        </div>

        <section className="resource-upload-panel">
          <div className="subsection-title">
            <strong>放入新文件</strong>
            <small>仅加入当前分析任务</small>
          </div>
          <label>
            文件
            <input
              key={inputKey}
              type="file"
              accept=".txt,.md,.markdown,.pdf,.docx,text/plain,text/markdown,application/pdf,application/vnd.openxmlformats-officedocument.wordprocessingml.document"
              onChange={(event) => onFileChange(event.target.files?.[0] ?? null)}
            />
          </label>
          <label>
            标题
            <input
              value={title}
              onChange={(event) => onTitleChange(event.target.value)}
              placeholder={file?.name ?? "可选，默认使用文件名"}
            />
          </label>
          <div className="evidence-input-grid">
            <label>
              类型
              <select value={sourceType} onChange={(event) => onSourceTypeChange(event.target.value)}>
                <option value="document">通用文档</option>
                <option value="interview">访谈记录</option>
                <option value="survey">问卷摘要</option>
                <option value="product_brief">产品资料</option>
                <option value="pricing_document">价格资料</option>
              </select>
            </label>
          </div>
          <label>
            备注
            <textarea
              value={notes}
              onChange={(event) => onNotesChange(event.target.value)}
              placeholder="可选，说明这份文件的使用边界或背景"
              rows={3}
            />
          </label>
          <button className="primary-button" type="button" onClick={onUpload} disabled={disabled || uploading || !file}>
            <UploadCloud size={15} /> {uploading ? "提交中..." : "加入资源包"}
          </button>
          {processingCount ? (
            // 上传请求返回只代表文件已进入后台队列；真正可被 Agent 使用，要等解析/切片/向量化完成。
            <p className="resource-processing-note">
              <Clock3 size={14} /> {processingCount} 个文件正在解析、切片或向量化，完成后才能重跑 Agent。
            </p>
          ) : null}
          <p className="resource-global-note">用户资源包中的文件只会加入当前分析任务，作为本任务可引用的用户提供证据。</p>
        </section>

        <section className="resource-library">
          <div className="subsection-title">
            <strong>已放入的文件</strong>
            <small>{sources.length} 份</small>
          </div>
          {sources.length ? (
            <div className="resource-grid">
              <div className="resource-list">
                {sources.map((source) => (
                  <button
                    key={source.citationKey}
                    className={`resource-item ${source.citationKey === activeSource?.citationKey ? "selected" : ""}`}
                    type="button"
                    onClick={() => {
                      setActiveCitationKey(source.citationKey);
                      onSelectCitation(source.citationKey);
                    }}
                  >
                    <FileText size={16} />
                    <span>
                      <strong>{source.title || source.citationKey}</strong>
                      <small>{source.citationKey} · {sourceTypeLabel(source.sourceType)} · 当前任务</small>
                      <ResourceStatus source={source} />
                    </span>
                  </button>
                ))}
              </div>
              <div className="resource-preview">
                {activeSource ? (
                  <>
                    <div className="resource-preview-header">
                      <div>
                        <p className="eyebrow">{activeSource.citationKey}</p>
                        <h3>{activeSource.title || "未命名文件"}</h3>
                      </div>
                      <button
                        className="history-delete"
                        type="button"
                        title="删除文件"
                        aria-label={`删除${activeSource.title || activeSource.citationKey}`}
                        onClick={() => onDelete(activeSource.citationKey)}
                        disabled={disabled || Boolean(deletingCitationKey)}
                      >
                        {deletingCitationKey === activeSource.citationKey ? <RefreshCw size={14} /> : <Trash2 size={14} />}
                      </button>
                    </div>
                    <dl className="resource-meta">
                      <div>
                        <dt>类型</dt>
                        <dd>{sourceTypeLabel(activeSource.sourceType)}</dd>
                      </div>
                      <div>
                        <dt>权限</dt>
                        <dd>当前任务资源包</dd>
                      </div>
                      <div>
                        <dt>质量</dt>
                        <dd>{sourceQualityLabel(activeSource.sourceQuality)}</dd>
                      </div>
                      <div>
                        <dt>状态</dt>
                        <dd>{resourceStatusLabel(activeSource)}</dd>
                      </div>
                    </dl>
                    <div className="resource-content-viewer">
                      <div className="resource-content-title">
                        <strong>文件内容</strong>
                        <small>{previewSourceLabel(activeSource)}</small>
                      </div>
                      <pre>{resourcePreviewText(activeSource)}</pre>
                    </div>
                    {activeSource.ingestionMessage ? <p className="resource-note">{activeSource.ingestionMessage}</p> : null}
                    {activeSource.complianceNote ? <p className="resource-note">{activeSource.complianceNote}</p> : null}
                  </>
                ) : null}
              </div>
            </div>
          ) : (
            <div className="empty-state resource-empty">
              <strong>当前会话还没有文件</strong>
              <span>上传后，它会加入当前分析任务，并作为可引用证据参与后续信息采集和分析。</span>
            </div>
          )}
        </section>
      </aside>
    </div>
  );
}

function ResourceStatus({ source }: { source: EvidenceSource }) {
  const status = source.ingestionStatus;
  // ingestionStatus 是后端异步文档管线的权威状态；不要用 collectionStatus 推断资源包进度。
  if (status === "PROCESSING") {
    return (
      <span className="resource-status processing">
        <Clock3 size={12} /> {stageLabel(source.ingestionStage)}
      </span>
    );
  }
  if (status === "FAILED") {
    return (
      <span className="resource-status failed">
        <AlertTriangle size={12} /> 处理失败
      </span>
    );
  }
  if (status === "READY") {
    return (
      <span className="resource-status ready">
        <CheckCircle2 size={12} /> 已就绪
      </span>
    );
  }
  return null;
}

function resourceStatusLabel(source: EvidenceSource) {
  if (source.ingestionStatus === "PROCESSING") return stageLabel(source.ingestionStage);
  if (source.ingestionStatus === "FAILED") return "处理失败";
  if (source.ingestionStatus === "READY") return "已就绪";
  return "已加入";
}

function stageLabel(stage?: string) {
  switch (stage) {
    case "QUEUED":
      return "排队中";
    case "PARSING":
      return "解析中";
    case "PII_DESENSITIZED":
      return "PII 脱敏中";
    case "CHUNKING":
      return "切片中";
    case "EMBEDDING":
      return "向量化中";
    case "READY":
      return "已就绪";
    case "FAILED":
      return "处理失败";
    default:
      return "处理中";
  }
}

function sourceTypeLabel(value?: string) {
  const normalized = value?.replace(/^user_/, "") ?? "";
  switch (normalized) {
    case "interview":
      return "访谈记录";
    case "survey":
      return "问卷摘要";
    case "product_brief":
      return "产品资料";
    case "pricing_document":
      return "价格资料";
    case "document":
      return "通用文档";
    default:
      return value || "未分类";
  }
}

function sourceQualityLabel(value?: string) {
  switch (value?.toUpperCase()) {
    case "HIGH":
      return "高质量";
    case "MEDIUM":
      return "中等质量";
    case "LOW":
      return "低质量";
    case "UNUSABLE":
      return "不可用";
    case "INTERNAL_ONLY":
      return "内部可用";
    default:
      return value || "未知";
  }
}

function previewSourceLabel(source: EvidenceSource) {
  if (source.rawText?.trim()) {
    return "解析原文";
  }
  if (source.snippet?.trim()) {
    return "摘要预览";
  }
  return "等待解析";
}

function resourcePreviewText(source: EvidenceSource) {
  const text = source.rawText?.trim() || source.snippet?.trim();
  if (text) {
    return text;
  }
  if (source.ingestionStatus === "PROCESSING") {
    return "文件正在解析中，完成后会在这里显示具体内容。";
  }
  if (source.ingestionStatus === "FAILED") {
    return source.ingestionMessage || "文件处理失败，暂无可查看内容。";
  }
  return "暂无可查看内容。";
}
