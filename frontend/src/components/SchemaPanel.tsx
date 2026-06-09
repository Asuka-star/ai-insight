import { useEffect, useRef, useState, type ReactNode } from "react";
import { Boxes, ChevronDown, ListChecks } from "lucide-react";
import type { AnalysisClaim, CompetitorProfile, FeatureNode, ResearchPackage, WorkflowTransition } from "../types";

interface SchemaPanelProps {
  researchPackage?: ResearchPackage;
  profiles: CompetitorProfile[];
  claims: AnalysisClaim[];
  transitions: WorkflowTransition[];
  selectedClaimId?: string;
  selectedClaimRequestId?: number;
  embedded?: boolean;
  onSelectCitation?: (citationKey: string) => void;
}

type SectionKey = "research" | "claims" | "profiles";

export function SchemaPanel({
  researchPackage,
  profiles,
  claims,
  transitions,
  selectedClaimId,
  selectedClaimRequestId,
  embedded,
  onSelectCitation
}: SchemaPanelProps) {
  const sourceCount = researchPackage?.sources?.length ?? 0;
  const missingCount = researchPackage?.missingEvidenceTypes?.length ?? 0;
  const claimRefs = useRef<Record<string, HTMLElement | null>>({});
  const [collapsedSections, setCollapsedSections] = useState<Record<SectionKey, boolean>>({
    research: false,
    claims: false,
    profiles: false
  });

  useEffect(() => {
    if (!selectedClaimId) return;
    if (collapsedSections.claims) {
      setCollapsedSections((current) => {
        if (!current.claims) return current;
        return {
          ...current,
          claims: false
        };
      });
      return;
    }
    const selectedClaim = claimRefs.current[selectedClaimId];
    if (!selectedClaim) return;
    selectedClaim.scrollIntoView({ block: "center", behavior: "smooth" });
  }, [collapsedSections.claims, selectedClaimId, selectedClaimRequestId]);

  const toggleSection = (key: SectionKey) => {
    setCollapsedSections((current) => ({
      ...current,
      [key]: !current[key]
    }));
  };

  return (
    <section className={embedded ? "schema-panel embedded" : "panel schema-panel"}>
      <div className="section-title">
        <div>
          <p className="eyebrow">结构化信息</p>
          <h2>结构化状态</h2>
        </div>
        <Boxes size={18} />
      </div>

      <div className="schema-stats">
        <SchemaStat label="来源" value={sourceCount} />
        <SchemaStat label="画像" value={profiles.length} />
        <SchemaStat label="结论" value={claims.length} />
        <SchemaStat label="路由" value={transitions.length} />
      </div>

      {missingCount ? (
        <div className="schema-alert">
          <strong>{missingCount} 个证据缺口</strong>
          <span>{researchPackage?.missingEvidenceTypes.join(", ")}</span>
        </div>
      ) : null}

      <SchemaSection
        collapsed={collapsedSections.research}
        icon={<ListChecks size={15} />}
        title="采集资料包"
        onToggle={() => toggleSection("research")}
      >
        <div className="schema-card">
          <span>ResearchPackage</span>
          <p>{sourceCount ? `已采集 ${sourceCount} 条来源` : "暂无资料来源"}</p>
          <small>采集时间：{formatDateTime(researchPackage?.collectedAt)}</small>

          {researchPackage?.sources?.length ? (
            <div className="schema-chip-list">
              {researchPackage.sources.map((source) => (
                <button
                  className="schema-chip evidence-chip"
                  type="button"
                  title={`${source.title}\n${source.url}\n${source.snippet}`}
                  key={source.id ?? source.citationKey}
                  onClick={() => onSelectCitation?.(source.citationKey)}
                >
                  <span className={`schema-source-key ${sourceQualityClass(source.sourceQuality)}`}>[{source.citationKey}]</span>
                  <span className="schema-source-title">{source.title}</span>
                </button>
              ))}
            </div>
          ) : null}

          {researchPackage?.interviewInsights?.length ? (
            <div className="schema-detail-grid research-plan-grid">
              <section className="schema-detail wide">
                <strong>访谈洞察</strong>
                <div className="schema-list">
                  {researchPackage.interviewInsights.map((insight, index) => (
                    <div className="schema-list-item" key={insight.id ?? `${insight.evidenceId}-${index}`}>
                      <strong>{insight.intervieweeRole || "受访用户"} / {insight.confidence || "LOW"}</strong>
                      <p>{insight.scenario || "场景待补充"}</p>
                      <dl className="schema-kv compact">
                        <div>
                          <dt>痛点</dt>
                          <dd><InsightValueList values={insight.painPoints} /></dd>
                        </div>
                        <div>
                          <dt>顾虑</dt>
                          <dd><InsightValueList values={insight.buyingConcerns} compact /></dd>
                        </div>
                        <div>
                          <dt>竞品</dt>
                          <dd><InsightValueList values={insight.competitorMentions} compact /></dd>
                        </div>
                        <div>
                          <dt>维度</dt>
                          <dd><InsightValueList values={insight.relatedDimensions} compact /></dd>
                        </div>
                        <div>
                          <dt>引用</dt>
                          <dd>
                            <EvidenceChips
                              values={insight.evidenceId ? [insight.evidenceId] : []}
                              onSelectCitation={onSelectCitation}
                            />
                          </dd>
                        </div>
                      </dl>
                      {insight.directQuotes?.length ? (
                        <div className="schema-quote-list">
                          {normalizeInsightValues(insight.directQuotes).map((quote, quoteIndex) => (
                            <small key={`${quote}-${quoteIndex}`}>{quote}</small>
                          ))}
                        </div>
                      ) : null}
                    </div>
                  ))}
                </div>
              </section>
            </div>
          ) : null}

          {researchPackage?.surveyInsights?.length ? (
            <div className="schema-detail-grid research-plan-grid">
              <section className="schema-detail wide">
                <strong>问卷洞察</strong>
                <div className="schema-list">
                  {researchPackage.surveyInsights.map((insight, index) => (
                    <div className="schema-list-item" key={insight.id ?? `${insight.evidenceId}-${index}`}>
                      <strong>{insight.title || "问卷结果"} / {insight.sampleSize || "unknown sample"}</strong>
                      <p>{joinOrEmpty(insight.respondentSegments)}</p>
                      <dl className="schema-kv compact">
                        <div>
                          <dt>竞品</dt>
                          <dd>{joinOrEmpty(insight.competitorMentions)}</dd>
                        </div>
                        <div>
                          <dt>维度</dt>
                          <dd>{joinOrEmpty(insight.relatedDimensions)}</dd>
                        </div>
                        <div>
                          <dt>证据</dt>
                          <dd><EvidenceChips values={insight.evidenceIds} onSelectCitation={onSelectCitation} /></dd>
                        </div>
                      </dl>
                      {insight.findings?.length ? (
                        <div className="schema-list">
                          {insight.findings.slice(0, 3).map((finding, findingIndex) => (
                            <div className="schema-list-item" key={`${finding.question}-${findingIndex}`}>
                              <strong>{finding.question || "Survey finding"}</strong>
                              <p>{finding.finding || finding.interpretation}</p>
                              <small>{finding.distribution}</small>
                            </div>
                          ))}
                        </div>
                      ) : null}
                    </div>
                  ))}
                </div>
              </section>
            </div>
          ) : null}

          {researchPackage?.researchPlan ? (
            <div className="schema-detail-grid research-plan-grid">
              <section className="schema-detail wide">
                <strong>调研目标</strong>
                <p>{researchPackage.researchPlan.objective || "暂无调研目标。"}</p>

                {researchPackage.researchPlan.evidenceGaps?.length ? (
                  <div className="schema-chip-list">
                    {researchPackage.researchPlan.evidenceGaps.map((gap) => (
                      <span className="schema-chip" key={gap}>{gap}</span>
                    ))}
                  </div>
                ) : null}

                {researchPackage.researchPlan.searchQueries?.length ? (
                  <div className="schema-list">
                    {researchPackage.researchPlan.searchQueries.map((query) => (
                      <div className="schema-list-item" key={query}>
                        <small>Search Query</small>
                        <p>{query}</p>
                      </div>
                    ))}
                  </div>
                ) : null}

                {researchPackage.actualSearchQueries?.length ? (
                  <div className="schema-list">
                    {researchPackage.actualSearchQueries.map((query) => (
                      <div className="schema-list-item" key={query}>
                        <small>实际执行 Query</small>
                        <p>{query}</p>
                      </div>
                    ))}
                  </div>
                ) : null}
              </section>

              <section className="schema-detail wide">
                <strong>公开资料任务</strong>
                {researchPackage.researchPlan.publicSourceTasks?.length ? (
                  <div className="schema-list">
                    {researchPackage.researchPlan.publicSourceTasks.slice(0, 6).map((task, index) => (
                      <div className="schema-list-item" key={`${task.type}-${task.target}-${index}`}>
                        <strong>{task.target || "待确认对象"}</strong>
                        <p>{task.rationale || "暂无说明"}</p>
                        <small>{task.type || "research"} / {task.status || "prepared"}</small>
                      </div>
                    ))}
                  </div>
                ) : (
                  <p className="muted-text">暂无公开资料任务。</p>
                )}
              </section>
            </div>
          ) : null}
        </div>
      </SchemaSection>

      <SchemaSection
        collapsed={collapsedSections.claims}
        icon={<ListChecks size={15} />}
        title="分析结论"
        onToggle={() => toggleSection("claims")}
      >
        {claims.length ? (
          claims.map((claim) => (
            <div
              className={`schema-card ${selectedClaimId === claim.id ? "active" : ""}`}
              key={claim.id}
              ref={(element) => {
                claimRefs.current[claim.id] = element;
              }}
            >
              <span>{claim.type ?? "CLAIM"} / {claim.confidence ?? "MEDIUM"}</span>
              <p>{claim.content}</p>
              <dl className="schema-kv">
                <div>
                  <dt>涉及竞品</dt>
                  <dd>{joinOrEmpty(claim.competitorNames)}</dd>
                </div>
                <div>
                  <dt>生成 Agent</dt>
                  <dd>{claim.generatedBy || "未知"}</dd>
                </div>
                <div>
                  <dt>证据</dt>
                  <dd><EvidenceChips values={claim.evidenceIds} onSelectCitation={onSelectCitation} /></dd>
                </div>
              </dl>
            </div>
          ))
        ) : (
          <p className="muted-text">暂无结构化结论。</p>
        )}
      </SchemaSection>

      <SchemaSection
        collapsed={collapsedSections.profiles}
        icon={<Boxes size={15} />}
        title="竞品画像"
        onToggle={() => toggleSection("profiles")}
      >
        {profiles.length ? (
          profiles.map((profile) => (
            <div className="schema-card" key={profile.productName ?? profile.companyName}>
              <span>{profile.productName ?? "未知产品"}</span>
              <p>{profile.positioning}</p>
              <dl className="schema-kv">
                <div>
                  <dt>公司</dt>
                  <dd>{profile.companyName || "待验证"}</dd>
                </div>
                <div>
                  <dt>目标用户</dt>
                  <dd>{joinOrEmpty(profile.targetUsers)}</dd>
                </div>
                <div>
                  <dt>优势</dt>
                  <dd>{joinOrEmpty(profile.strengths)}</dd>
                </div>
                <div>
                  <dt>弱势</dt>
                  <dd>{joinOrEmpty(profile.weaknesses)}</dd>
                </div>
                <div>
                  <dt>证据</dt>
                  <dd><EvidenceChips values={profile.evidenceIds} onSelectCitation={onSelectCitation} /></dd>
                </div>
              </dl>

              <div className="schema-detail-grid">
                <section className="schema-detail">
                  <strong>功能树</strong>
                  {profile.featureTree?.roots?.length ? (
                    <div className="feature-tree">
                      {profile.featureTree.roots.map((node, index) => (
                        <FeatureNodeView node={node} onSelectCitation={onSelectCitation} key={`${node.name}-${index}`} />
                      ))}
                    </div>
                  ) : (
                    <p className="muted-text">暂无功能树。</p>
                  )}
                </section>

                <section className="schema-detail">
                  <strong>定价模型</strong>
                  <p>{profile.pricingModel?.strategySummary || "暂无定价摘要。"}</p>
                  <small>
                    {profile.pricingModel?.hasFreePlan ? "包含免费版线索" : "免费版待验证"} /{" "}
                    <EvidenceChips values={profile.pricingModel?.evidenceIds} onSelectCitation={onSelectCitation} inline />
                  </small>
                  {profile.pricingModel?.plans?.length ? (
                    <div className="schema-list">
                      {profile.pricingModel.plans.map((plan, index) => (
                        <div className="schema-list-item" key={`${plan.name}-${index}`}>
                          <strong>{plan.name || "未命名套餐"}</strong>
                          <p>{plan.priceText || "价格待验证"} · {plan.billingCycle || "周期待验证"}</p>
                          <small>
                            {plan.targetSegment || "目标客群待验证"} /{" "}
                            <EvidenceChips values={plan.evidenceIds} onSelectCitation={onSelectCitation} inline />
                          </small>
                          <div className="schema-chip-list">
                            {(plan.includedFeatures ?? []).map((feature) => (
                              <span className="schema-chip" key={feature}>{feature}</span>
                            ))}
                          </div>
                        </div>
                      ))}
                    </div>
                  ) : null}
                </section>

                <section className="schema-detail wide">
                  <strong>用户画像</strong>
                  {profile.personas?.length ? (
                    <div className="schema-list">
                      {profile.personas.map((persona, index) => (
                        <div className="schema-list-item" key={`${persona.name}-${index}`}>
                          <strong>{persona.name || "未命名画像"}</strong>
                          <p>{persona.segment || "细分场景待验证"} · {persona.companySize || "规模待验证"}</p>
                          <dl className="schema-kv compact">
                            <div>
                              <dt>任务</dt>
                              <dd>{joinOrEmpty(persona.jobsToBeDone)}</dd>
                            </div>
                            <div>
                              <dt>痛点</dt>
                              <dd>{joinOrEmpty(persona.painPoints)}</dd>
                            </div>
                            <div>
                              <dt>顾虑</dt>
                              <dd>{joinOrEmpty(persona.buyingConcerns)}</dd>
                            </div>
                            <div>
                              <dt>证据</dt>
                              <dd><EvidenceChips values={persona.evidenceIds} onSelectCitation={onSelectCitation} /></dd>
                            </div>
                          </dl>
                        </div>
                      ))}
                    </div>
                  ) : (
                    <p className="muted-text">暂无用户画像。</p>
                  )}
                </section>
              </div>
            </div>
          ))
        ) : (
          <p className="muted-text">暂无竞品画像。</p>
        )}
      </SchemaSection>
    </section>
  );
}

function SchemaSection({
  collapsed,
  icon,
  title,
  onToggle,
  children
}: {
  collapsed: boolean;
  icon: ReactNode;
  title: string;
  onToggle: () => void;
  children: ReactNode;
}) {
  return (
    <div className={`schema-section ${collapsed ? "collapsed" : ""}`}>
      <button className="schema-heading-toggle" type="button" aria-expanded={!collapsed} onClick={onToggle}>
        <div className="schema-heading">
          {icon}
          <strong>{title}</strong>
        </div>
        <ChevronDown size={14} />
      </button>
      {collapsed ? null : children}
    </div>
  );
}

function FeatureNodeView({ node, onSelectCitation }: { node: FeatureNode; onSelectCitation?: (citationKey: string) => void }) {
  return (
    <div className="feature-node">
      <strong>{node.name || "未命名功能"}</strong>
      <p>{node.description || "暂无描述"}</p>
      <small><EvidenceChips values={node.evidenceIds} onSelectCitation={onSelectCitation} /></small>
      {node.children?.length ? (
        <div className="feature-children">
          {node.children.map((child, index) => (
            <FeatureNodeView node={child} onSelectCitation={onSelectCitation} key={`${child.name}-${index}`} />
          ))}
        </div>
      ) : null}
    </div>
  );
}

function SchemaStat({ label, value }: { label: string; value: number }) {
  return (
    <div className="schema-stat">
      <strong>{value}</strong>
      <span>{label}</span>
    </div>
  );
}

function joinOrEmpty(values?: string[]) {
  return values?.length ? values.join("、") : "待验证";
}

function InsightValueList({ values, compact }: { values?: string[]; compact?: boolean }) {
  const normalized = normalizeInsightValues(values);
  if (!normalized.length) {
    return <span className="schema-empty-value">待验证</span>;
  }
  return (
    <span className={compact ? "schema-insight-values compact" : "schema-insight-values"}>
      {normalized.map((value, index) => (
        <span className="schema-insight-value" key={`${value}-${index}`}>
          {value}
        </span>
      ))}
    </span>
  );
}

function normalizeInsightValues(values?: string[]) {
  return (values ?? [])
    .flatMap((value) => splitInsightValue(value))
    .map(cleanInsightValue)
    .filter((value, index, all) => value.length > 0 && all.indexOf(value) === index);
}

function splitInsightValue(value?: string) {
  if (!value) return [];
  return value
    .replace(/[\u3001\uff0c,\uff1b;]\s*[-\u2022*]\s*/g, "\n")
    .split(/\n+|(?<=\u3002)\s+(?=[-\u2022*])|(?<=\.)\s+(?=[-\u2022*])/);
}

function cleanInsightValue(value: string) {
  return value
    .replace(/^\s*[-\u2022*]+\s*/g, "")
    .replace(/\s*[-\u2022*]+\s*$/g, "")
    .replace(/\s+/g, " ")
    .trim();
}

function formatDateTime(value?: string) {
  if (!value) return "暂无";
  return new Date(value).toLocaleString("zh-CN", { hour12: false });
}

function sourceQualityClass(sourceQuality?: string) {
  if (!sourceQuality) return "";
  return `quality-${sourceQuality.toLowerCase()}`;
}

function EvidenceChips({
  values,
  onSelectCitation,
  inline
}: {
  values?: string[];
  onSelectCitation?: (citationKey: string) => void;
  inline?: boolean;
}) {
  if (!values?.length) {
    return <span>未绑定证据</span>;
  }
  return (
    <span className={inline ? "schema-evidence-chips inline" : "schema-evidence-chips"}>
      {values.map((value) => (
        <button
          className="citation-chip schema-citation"
          type="button"
          key={value}
          onClick={() => onSelectCitation?.(value)}
        >
          [{value}]
        </button>
      ))}
    </span>
  );
}
