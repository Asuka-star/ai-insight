import { useEffect, useMemo, useState, type Dispatch, type SetStateAction } from "react";
import { ChevronDown, ClipboardList, DownloadCloud, FileSpreadsheet, MessageSquareText, Pencil, Plus, RefreshCw, Save, Trash2, X } from "lucide-react";
import type { InterviewGuide, InterviewInsight, Questionnaire, SurveyInsight, SurveyQuestion, SurveyResultImport } from "../types";

interface ResearchDesignPanelProps {
  questionnaire?: Questionnaire;
  interviewGuide?: InterviewGuide;
  interviewInsights?: InterviewInsight[];
  surveyResultImports?: SurveyResultImport[];
  surveyInsights?: SurveyInsight[];
  disabled?: boolean;
  busy?: boolean;
  pendingRevision?: boolean;
  pendingRevisionReason?: string;
  onDownloadTemplate?: () => void;
  onSaveQuestionnaire?: (questionnaire: Questionnaire) => void;
  onApplyResearchInputs?: () => void;
  onDeleteInsight?: (insightType: "survey" | "interview", insightId: string) => void;
  deletingInsightKey?: string;
}

export function ResearchDesignPanel({
  questionnaire,
  interviewGuide,
  interviewInsights = [],
  surveyResultImports = [],
  surveyInsights = [],
  disabled,
  busy,
  pendingRevision,
  pendingRevisionReason,
  onDownloadTemplate,
  onSaveQuestionnaire,
  onApplyResearchInputs,
  onDeleteInsight,
  deletingInsightKey
}: ResearchDesignPanelProps) {
  const [editingQuestionnaire, setEditingQuestionnaire] = useState(false);
  const [expandedInsightKeys, setExpandedInsightKeys] = useState<Record<string, boolean>>({});
  const [draftQuestionnaire, setDraftQuestionnaire] = useState<Questionnaire>(() => editableQuestionnaire(questionnaire));
  const questions = questionnaire?.questions ?? [];
  const draftQuestions = draftQuestionnaire.questions ?? [];
  const interviewQuestions = interviewGuide?.questions ?? [];
  const draftIsSavable = useMemo(() => {
    return Boolean(draftQuestionnaire.title?.trim())
      && draftQuestions.some((question) => question.question?.trim() && question.options.filter((option) => option.trim()).length >= 2);
  }, [draftQuestionnaire.title, draftQuestions]);

  useEffect(() => {
    if (!editingQuestionnaire) {
      setDraftQuestionnaire(editableQuestionnaire(questionnaire));
    }
  }, [editingQuestionnaire, questionnaire]);

  const toggleInsight = (key: string) => {
    setExpandedInsightKeys((current) => ({
      ...current,
      [key]: !current[key]
    }));
  };

  return (
    <div className="research-design-panel">
      <section className="research-hero">
        <div>
          <p className="eyebrow">Research</p>
          <h2>问卷与访谈</h2>
          <p>{questionnaire?.title || interviewGuide?.title || "等待 资料采集节点 生成调研材料"}</p>
        </div>
        <div className="research-actions">
          <button type="button" className="secondary-button" onClick={onDownloadTemplate} disabled={disabled || busy || !questions.length}>
            <DownloadCloud size={15} /> 导出调研问卷
          </button>
          <p className="research-format-note">
            导出 TXT：腾讯问卷内容 DSL，可粘贴到腾讯问卷建题
          </p>
        </div>
      </section>

      {pendingRevision ? (
        <section className="research-pending-banner">
          <div>
            <strong>新调研数据待应用</strong>
            <p>{pendingRevisionReason || "已导入或新增问卷/访谈资料，当前报告还没有基于这批资料刷新。"}</p>
          </div>
          <button type="button" className="primary-button" onClick={onApplyResearchInputs} disabled={disabled || busy}>
            <RefreshCw size={15} /> 应用并重跑 Extractor
          </button>
        </section>
      ) : null}

      <div className="research-design-grid">
        <section className="research-card wide">
          <div className="research-card-title">
            <ClipboardList size={16} />
            <strong>问卷草案</strong>
            <div className="research-card-actions">
              {editingQuestionnaire ? (
                <>
                  <button type="button" className="icon-button" title="取消编辑" onClick={() => setEditingQuestionnaire(false)} disabled={busy}>
                    <X size={15} />
                  </button>
                  <button
                    type="button"
                    className="icon-button"
                    title="保存问卷草案"
                    onClick={() => {
                      onSaveQuestionnaire?.(normalizeQuestionnaire(draftQuestionnaire));
                      setEditingQuestionnaire(false);
                    }}
                    disabled={disabled || busy || !draftIsSavable}
                  >
                    <Save size={15} />
                  </button>
                </>
              ) : (
                <button
                  type="button"
                  className="icon-button"
                  title="编辑问卷草案"
                  onClick={() => {
                    setDraftQuestionnaire(editableQuestionnaire(questionnaire));
                    setEditingQuestionnaire(true);
                  }}
                  disabled={disabled || busy}
                >
                  <Pencil size={15} />
                </button>
              )}
            </div>
          </div>
          {editingQuestionnaire ? (
            <div className="research-edit-form">
              <div className="research-edit-grid">
                <label>
                  标题
                  <input
                    value={draftQuestionnaire.title ?? ""}
                    onChange={(event) => setDraftQuestionnaire((current) => ({ ...current, title: event.target.value }))}
                    placeholder="例如：AI 编码工具用户调研"
                  />
                </label>
              </div>
              <label>
                目标受访者
                <input
                  value={draftQuestionnaire.targetRespondents ?? ""}
                  onChange={(event) => setDraftQuestionnaire((current) => ({ ...current, targetRespondents: event.target.value }))}
                  placeholder="例如：使用过或评估过竞品的产品/研发/采购角色"
                />
              </label>
              <div className="research-question-list">
                {draftQuestions.map((question, index) => (
                  <article className="research-question editable" key={index}>
                    <div className="research-question-toolbar">
                      <strong>题目 {index + 1}</strong>
                      <button
                        type="button"
                        className="icon-button"
                        title="删除题目"
                        onClick={() => setDraftQuestionnaire((current) => ({
                          ...current,
                          questions: current.questions.filter((_, questionIndex) => questionIndex !== index)
                        }))}
                        disabled={draftQuestions.length <= 1}
                      >
                        <Trash2 size={14} />
                      </button>
                    </div>
                    <div className="research-edit-grid">
                      <label>
                        维度
                        <input
                          value={question.dimension ?? ""}
                          onChange={(event) => updateDraftQuestion(setDraftQuestionnaire, index, { dimension: event.target.value })}
                          placeholder="例如：价格 / 权限 / AI 搜索"
                        />
                      </label>
                      <label>
                        选项
                        <input
                          value={(question.options ?? []).join("；")}
                          onChange={(event) => updateDraftQuestion(setDraftQuestionnaire, index, { options: splitOptions(event.target.value) })}
                          placeholder="用逗号或分号分隔"
                        />
                      </label>
                    </div>
                    <label>
                      问题
                      <input
                        value={question.question ?? ""}
                        onChange={(event) => updateDraftQuestion(setDraftQuestionnaire, index, { question: event.target.value })}
                        placeholder="填写题目文本"
                      />
                    </label>
                  </article>
                ))}
              </div>
              <button
                type="button"
                className="secondary-button"
                onClick={() => setDraftQuestionnaire((current) => ({
                  ...current,
                  questions: [...current.questions, emptyQuestion()]
                }))}
              >
                <Plus size={15} /> 新增题目
              </button>
            </div>
          ) : questions.length ? (
            <div className="research-question-list">
              {questions.map((question, index) => (
                <article className="research-question" key={`${question.question}-${index}`}>
                  <small>{question.dimension || `Q${index + 1}`}</small>
                  <strong>{question.question || "未命名题目"}</strong>
                  {question.options?.length ? <ChipList values={question.options} /> : null}
                </article>
              ))}
            </div>
          ) : (
            <p className="muted-text">暂无问卷题目。先运行 资料采集节点 生成调研计划。</p>
          )}
          {!editingQuestionnaire && questionnaire?.targetRespondents ? <p className="muted-text">{questionnaire.targetRespondents}</p> : null}
        </section>

        <section className="research-card">
          <div className="research-card-title">
            <FileSpreadsheet size={16} />
            <strong>结果回收</strong>
            {surveyInsights.length ? <small>{surveyInsights.length} 洞察</small> : null}
          </div>
          {surveyResultImports.length ? (
            <div className="research-import-list">
              {surveyResultImports.slice(-4).reverse().map((resultImport) => (
                <article className="research-import" key={resultImport.id}>
                  <strong>{resultImport.title || "问卷结果"}</strong>
                  <small>{resultImport.fileName || "手动导入"} / {resultImport.status || "IMPORTED"} / {resultImport.resultCount ?? 0} responses</small>
                  {resultImport.evidenceIds?.length ? <ChipList values={resultImport.evidenceIds} compact /> : null}
                </article>
              ))}
            </div>
          ) : (
            <p className="muted-text">导入 CSV/XLSX 后会在这里显示回收批次与证据编号。</p>
          )}
        </section>

        <section className="research-card">
          <div className="research-card-title">
            <MessageSquareText size={16} />
            <strong>访谈提纲</strong>
            {interviewGuide?.targetRoles?.length ? <small>{interviewGuide.targetRoles.join(" / ")}</small> : null}
          </div>
          {interviewQuestions.length ? (
            <ol className="research-ordered-list">
              {interviewQuestions.map((question) => (
                <li key={question}>{question}</li>
              ))}
            </ol>
          ) : (
            <p className="muted-text">暂无访谈提纲。</p>
          )}
          {interviewGuide?.probingQuestions?.length ? (
            <div className="research-probes">
              <strong>追问</strong>
              <ChipList values={interviewGuide.probingQuestions.slice(0, 8)} />
            </div>
          ) : null}
        </section>
      </div>

      <section className="research-card wide">
        <div className="research-card-title">
          <strong>已结构化洞察</strong>
          <small>{surveyInsights.length} 问卷 / {interviewInsights.length} 访谈</small>
        </div>
        <div className="research-insight-grid">
          {surveyInsights.map((insight) => {
            const id = insightIdentity("survey", insight.id, insight.evidenceId, insight.evidenceIds?.[0]);
            const expanded = Boolean(expandedInsightKeys[id.key]);
            const deleting = deletingInsightKey === id.key;
            return (
              <article className="research-insight" key={id.key}>
                <div className="research-insight-toolbar">
                  <small>{insight.evidenceId || insight.evidenceIds?.[0] || "survey"}</small>
                  <div className="research-card-actions">
                    <button
                      type="button"
                      className={`icon-button ${expanded ? "active" : ""}`}
                      title={expanded ? "收起详情" : "查看详细"}
                      aria-expanded={expanded}
                      onClick={() => toggleInsight(id.key)}
                    >
                      <ChevronDown size={15} />
                    </button>
                    <button
                      type="button"
                      className="icon-button danger"
                      title="删除洞察"
                      onClick={() => onDeleteInsight?.("survey", id.value)}
                      disabled={disabled || busy || deleting || !id.value}
                    >
                      <Trash2 size={14} />
                    </button>
                  </div>
                </div>
                <strong>{insight.title || "问卷洞察"} / {insight.sampleSize || "unknown sample"}</strong>
                <p>{insight.findings?.[0]?.finding || insight.findings?.[0]?.interpretation || "暂无摘要"}</p>
                {expanded ? (
                  <div className="research-insight-detail">
                    <DetailRow label="受访分组" values={insight.respondentSegments} compact />
                    <DetailRow label="竞品" values={insight.competitorMentions} compact />
                    <DetailRow label="维度" values={insight.relatedDimensions} compact />
                    <DetailRow label="证据" values={insight.evidenceIds} compact />
                    {insight.findings?.length ? (
                      <div className="research-finding-list">
                        {insight.findings.map((finding, index) => (
                          <div className="research-finding" key={`${finding.question}-${index}`}>
                            <strong>{finding.question || `发现 ${index + 1}`}</strong>
                            <p>{finding.finding || finding.interpretation || "暂无发现"}</p>
                            {finding.distribution ? <small>{finding.distribution}</small> : null}
                          </div>
                        ))}
                      </div>
                    ) : null}
                  </div>
                ) : null}
              </article>
            );
          })}
          {interviewInsights.map((insight) => {
            const id = insightIdentity("interview", insight.id, insight.evidenceId);
            const expanded = Boolean(expandedInsightKeys[id.key]);
            const deleting = deletingInsightKey === id.key;
            return (
              <article className="research-insight" key={id.key}>
                <div className="research-insight-toolbar">
                  <small>{insight.evidenceId || "interview"}</small>
                  <div className="research-card-actions">
                    <button
                      type="button"
                      className={`icon-button ${expanded ? "active" : ""}`}
                      title={expanded ? "收起详情" : "查看详细"}
                      aria-expanded={expanded}
                      onClick={() => toggleInsight(id.key)}
                    >
                      <ChevronDown size={15} />
                    </button>
                    <button
                      type="button"
                      className="icon-button danger"
                      title="删除洞察"
                      onClick={() => onDeleteInsight?.("interview", id.value)}
                      disabled={disabled || busy || deleting || !id.value}
                    >
                      <Trash2 size={14} />
                    </button>
                  </div>
                </div>
                <strong>{insight.intervieweeRole || "访谈对象"} / {insight.confidence || "LOW"}</strong>
                <p>{insight.scenario || insight.painPoints?.[0] || "暂无摘要"}</p>
                {expanded ? (
                  <div className="research-insight-detail">
                    {insight.sourceTitle ? <DetailRow label="来源" values={[insight.sourceTitle]} /> : null}
                    <DetailRow label="痛点" values={insight.painPoints} />
                    <DetailRow label="正向信号" values={insight.positiveSignals} />
                    <DetailRow label="负向信号" values={insight.negativeSignals} />
                    <DetailRow label="顾虑" values={insight.buyingConcerns} compact />
                    <DetailRow label="竞品" values={insight.competitorMentions} compact />
                    <DetailRow label="维度" values={insight.relatedDimensions} compact />
                    <DetailRow label="引用摘录" values={insight.directQuotes} />
                  </div>
                ) : null}
              </article>
            );
          })}
          {!surveyInsights.length && !interviewInsights.length ? <p className="muted-text">导入问卷结果或添加访谈证据后，这里会展示结构化洞察。</p> : null}
        </div>
      </section>
    </div>
  );
}

function editableQuestionnaire(questionnaire?: Questionnaire): Questionnaire {
  return {
    title: questionnaire?.title ?? "",
    targetRespondents: questionnaire?.targetRespondents ?? "",
    questions: questionnaire?.questions?.length
      ? questionnaire.questions.map((question) => ({
        dimension: question.dimension ?? "",
        question: question.question ?? "",
        options: [...(question.options ?? [])]
      }))
      : [emptyQuestion()]
  };
}

function normalizeQuestionnaire(questionnaire: Questionnaire): Questionnaire {
  return {
    title: questionnaire.title?.trim() ?? "",
    targetRespondents: questionnaire.targetRespondents?.trim() ?? "",
    questions: questionnaire.questions
      .map((question) => ({
        dimension: question.dimension?.trim() ?? "",
        question: question.question?.trim() ?? "",
        options: question.options.map((option) => option.trim()).filter(Boolean)
      }))
      .filter((question) => question.question && question.options.length >= 2)
  };
}

function emptyQuestion(): SurveyQuestion {
  return {
    dimension: "",
    question: "",
    options: ["", ""]
  };
}

function splitOptions(value: string) {
  return value.split(/[;,，；]/).map((item) => item.trim()).filter(Boolean);
}

function updateDraftQuestion(
  setDraftQuestionnaire: Dispatch<SetStateAction<Questionnaire>>,
  index: number,
  patch: Partial<SurveyQuestion>
) {
  setDraftQuestionnaire((current) => ({
    ...current,
    questions: current.questions.map((question, questionIndex) => questionIndex === index ? { ...question, ...patch } : question)
  }));
}

function insightIdentity(type: "survey" | "interview", ...candidates: Array<string | undefined>) {
  const value = candidates.find((candidate) => candidate?.trim())?.trim() ?? "";
  return {
    value,
    key: `${type}:${value || "unknown"}`
  };
}

function DetailRow({ label, values, compact }: { label: string; values?: string[]; compact?: boolean }) {
  const normalized = normalizeValues(values);
  if (!normalized.length) {
    return null;
  }
  return (
    <div className="research-detail-row">
      <small>{label}</small>
      {compact ? <ChipList values={normalized} compact /> : <DetailList values={normalized} />}
    </div>
  );
}

function DetailList({ values }: { values: string[] }) {
  return (
    <div className="research-detail-list">
      {values.map((value, index) => (
        <span key={`${value}-${index}`}>{value}</span>
      ))}
    </div>
  );
}

function normalizeValues(values?: string[]) {
  return (values ?? []).map((value) => value.trim()).filter((value, index, all) => value && all.indexOf(value) === index);
}

function ChipList({ values, compact }: { values?: string[]; compact?: boolean }) {
  if (!values?.length) {
    return null;
  }
  return (
    <div className={compact ? "mini-chip-list compact" : "mini-chip-list"}>
      {values.map((value) => (
        <span key={value} className="mini-chip">{value}</span>
      ))}
    </div>
  );
}
