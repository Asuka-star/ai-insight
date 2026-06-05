import { useEffect, useMemo, useRef, useState, type Dispatch, type SetStateAction } from "react";
import { ClipboardList, DownloadCloud, FileSpreadsheet, MessageSquareText, Pencil, Plus, Save, Trash2, UploadCloud, X } from "lucide-react";
import type { InterviewGuide, InterviewInsight, Questionnaire, SurveyInsight, SurveyQuestion, SurveyResultImport } from "../types";

interface ResearchDesignPanelProps {
  questionnaire?: Questionnaire;
  interviewGuide?: InterviewGuide;
  interviewInsights?: InterviewInsight[];
  surveyResultImports?: SurveyResultImport[];
  surveyInsights?: SurveyInsight[];
  disabled?: boolean;
  busy?: boolean;
  onDownloadTemplate?: () => void;
  onSaveQuestionnaire?: (questionnaire: Questionnaire) => void;
  onImportSurveyResults?: (file: File) => void;
}

export function ResearchDesignPanel({
  questionnaire,
  interviewGuide,
  interviewInsights = [],
  surveyResultImports = [],
  surveyInsights = [],
  disabled,
  busy,
  onDownloadTemplate,
  onSaveQuestionnaire,
  onImportSurveyResults
}: ResearchDesignPanelProps) {
  const importInputRef = useRef<HTMLInputElement>(null);
  const [editingQuestionnaire, setEditingQuestionnaire] = useState(false);
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

  return (
    <div className="research-design-panel">
      <section className="research-hero">
        <div>
          <p className="eyebrow">Research</p>
          <h2>问卷与访谈</h2>
          <p>{questionnaire?.title || interviewGuide?.title || "等待 Researcher 生成调研材料"}</p>
        </div>
        <div className="research-actions">
          <button type="button" className="secondary-button" onClick={onDownloadTemplate} disabled={disabled || busy || !questions.length}>
            <DownloadCloud size={15} /> 下载结果模板
          </button>
          <button type="button" className="primary-button" onClick={() => importInputRef.current?.click()} disabled={disabled || busy}>
            <UploadCloud size={15} /> 导入结果表格
          </button>
          <input
            ref={importInputRef}
            type="file"
            accept=".csv,.xlsx,text/csv,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            hidden
            onChange={(event) => {
              const file = event.currentTarget.files?.[0];
              event.currentTarget.value = "";
              if (file) {
                onImportSurveyResults?.(file);
              }
            }}
          />
        </div>
      </section>

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
            <p className="muted-text">暂无问卷题目。先运行 Researcher 生成调研计划。</p>
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
          {surveyInsights.slice(0, 4).map((insight) => (
            <article className="research-insight" key={insight.id ?? insight.evidenceId}>
              <small>{insight.evidenceId || "survey"}</small>
              <strong>{insight.title || "问卷洞察"} / {insight.sampleSize || "unknown sample"}</strong>
              <p>{insight.findings?.[0]?.finding || insight.findings?.[0]?.interpretation || "暂无摘要"}</p>
            </article>
          ))}
          {interviewInsights.slice(0, 4).map((insight) => (
            <article className="research-insight" key={insight.id ?? insight.evidenceId}>
              <small>{insight.evidenceId || "interview"}</small>
              <strong>{insight.intervieweeRole || "访谈对象"} / {insight.confidence || "LOW"}</strong>
              <p>{insight.scenario || insight.painPoints?.[0] || "暂无摘要"}</p>
            </article>
          ))}
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
