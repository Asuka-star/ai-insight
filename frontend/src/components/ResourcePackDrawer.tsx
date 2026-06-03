import { useEffect, useMemo, useState } from "react";
import { FileText, RefreshCw, Trash2, UploadCloud, X } from "lucide-react";
import type { EvidenceSource } from "../types";

interface ResourcePackDrawerProps {
  open: boolean;
  sources: EvidenceSource[];
  disabled: boolean;
  uploading: boolean;
  deletingCitationKey?: string;
  file: File | null;
  inputKey: number;
  title: string;
  sourceType: string;
  sensitive: boolean;
  notes: string;
  onClose: () => void;
  onFileChange: (file: File | null) => void;
  onTitleChange: (value: string) => void;
  onSourceTypeChange: (value: string) => void;
  onSensitiveChange: (value: boolean) => void;
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
  deletingCitationKey,
  file,
  inputKey,
  title,
  sourceType,
  sensitive,
  notes,
  onClose,
  onFileChange,
  onTitleChange,
  onSourceTypeChange,
  onSensitiveChange,
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
            <small>支持 TXT、Markdown、PDF、DOCX</small>
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
            <label className="check-row evidence-sensitive">
              <input type="checkbox" checked={sensitive} onChange={(event) => onSensitiveChange(event.target.checked)} />
              内部资料
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
            <UploadCloud size={15} /> {uploading ? "上传中..." : "加入资源包"}
          </button>
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
                      <small>{source.citationKey} · {sourceTypeLabel(source.sourceType)} · {resourceVisibility(source)}</small>
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
                        <dd>{resourceVisibility(activeSource)}</dd>
                      </div>
                      <div>
                        <dt>质量</dt>
                        <dd>{sourceQualityLabel(activeSource.sourceQuality)}</dd>
                      </div>
                    </dl>
                    <p className="resource-snippet">{activeSource.snippet || "暂无可预览摘要"}</p>
                    {activeSource.complianceNote ? <p className="resource-note">{activeSource.complianceNote}</p> : null}
                  </>
                ) : null}
              </div>
            </div>
          ) : (
            <div className="empty-state resource-empty">
              <strong>当前会话还没有文件</strong>
              <span>上传后，它会作为可引用证据进入后续信息采集和分析。</span>
            </div>
          )}
        </section>
      </aside>
    </div>
  );
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

function resourceVisibility(source: EvidenceSource) {
  if (source.sourceAuthority === "INTERNAL_ONLY" || source.sourceQuality === "INTERNAL_ONLY" || source.freshness === "INTERNAL_ONLY") {
    return "内部资料";
  }
  return "用户提供";
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
