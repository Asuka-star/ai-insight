import { Boxes, GitPullRequestArrow, ListChecks } from "lucide-react";
import type { AnalysisClaim, CompetitorProfile, FeatureNode, ResearchPackage, WorkflowTransition } from "../types";

interface SchemaPanelProps {
  researchPackage?: ResearchPackage;
  profiles: CompetitorProfile[];
  claims: AnalysisClaim[];
  transitions: WorkflowTransition[];
  selectedClaimId?: string;
  embedded?: boolean;
  onSelectCitation?: (citationKey: string) => void;
}

export function SchemaPanel({
  researchPackage,
  profiles,
  claims,
  transitions,
  selectedClaimId,
  embedded,
  onSelectCitation
}: SchemaPanelProps) {
  const sourceCount = researchPackage?.sources?.length ?? 0;
  const missingCount = researchPackage?.missingEvidenceTypes?.length ?? 0;

  return (
    <section className={embedded ? "schema-panel embedded" : "panel schema-panel"}>
      <div className="section-title">
        <div>
          <p className="eyebrow">Schema</p>
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

      <div className="schema-section">
        <div className="schema-heading">
          <ListChecks size={15} />
          <strong>采集资料包</strong>
        </div>
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
                  [{source.citationKey}] {source.title}
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
                          <dd>{joinOrEmpty(insight.painPoints)}</dd>
                        </div>
                        <div>
                          <dt>顾虑</dt>
                          <dd>{joinOrEmpty(insight.buyingConcerns)}</dd>
                        </div>
                        <div>
                          <dt>竞品</dt>
                          <dd>{joinOrEmpty(insight.competitorMentions)}</dd>
                        </div>
                        <div>
                          <dt>维度</dt>
                          <dd>{joinOrEmpty(insight.relatedDimensions)}</dd>
                        </div>
                        <div>
                          <dt>引用</dt>
                          <dd><EvidenceChips values={insight.evidenceId ? [insight.evidenceId] : []} onSelectCitation={onSelectCitation} /></dd>
                        </div>
                      </dl>
                      {insight.directQuotes?.length ? (
                        <small>{insight.directQuotes.join(" / ")}</small>
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

              <section className="schema-detail">
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

              <section className="schema-detail">
                <strong>{researchPackage.researchPlan.questionnaire?.title || "问卷草案"}</strong>
                <p>{researchPackage.researchPlan.questionnaire?.targetRespondents || "目标样本待确认。"}</p>
                <small>{researchPackage.researchPlan.questionnaire?.recommendedSampleSize}</small>
                <div className="schema-list">
                  {(researchPackage.researchPlan.questionnaire?.questions ?? []).map((question, index) => (
                    <div className="schema-list-item" key={`${question.dimension}-${index}`}>
                      <strong>{question.dimension || "调研维度"}</strong>
                      <p>{question.question}</p>
                      <small>{question.options?.join(" / ")}</small>
                    </div>
                  ))}
                </div>
              </section>

              <section className="schema-detail wide">
                <strong>{researchPackage.researchPlan.interviewGuide?.title || "访谈提纲"}</strong>
                <p>目标角色：{joinOrEmpty(researchPackage.researchPlan.interviewGuide?.targetRoles)}</p>
                <div className="schema-list">
                  {(researchPackage.researchPlan.interviewGuide?.questions ?? []).map((question, index) => (
                    <div className="schema-list-item" key={`${question}-${index}`}>
                      <p>{question}</p>
                    </div>
                  ))}
                </div>
              </section>
            </div>
          ) : null}
        </div>
      </div>

      <div className="schema-section">
        <div className="schema-heading">
          <ListChecks size={15} />
          <strong>分析结论</strong>
        </div>
        {claims.length ? (
          claims.map((claim) => (
            <div className={`schema-card ${selectedClaimId === claim.id ? "active" : ""}`} key={claim.id}>
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
      </div>

      <div className="schema-section">
        <div className="schema-heading">
          <Boxes size={15} />
          <strong>竞品画像</strong>
        </div>
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
                  <small>{profile.pricingModel?.hasFreePlan ? "包含免费版线索" : "免费版待验证"} / <EvidenceChips values={profile.pricingModel?.evidenceIds} onSelectCitation={onSelectCitation} inline /></small>
                  {profile.pricingModel?.plans?.length ? (
                    <div className="schema-list">
                      {profile.pricingModel.plans.map((plan, index) => (
                        <div className="schema-list-item" key={`${plan.name}-${index}`}>
                          <strong>{plan.name || "未命名套餐"}</strong>
                          <p>{plan.priceText || "价格待验证"} · {plan.billingCycle || "周期待验证"}</p>
                          <small>{plan.targetSegment || "目标客群待验证"} / <EvidenceChips values={plan.evidenceIds} onSelectCitation={onSelectCitation} inline /></small>
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
      </div>

      <div className="schema-section">
        <div className="schema-heading">
          <GitPullRequestArrow size={15} />
          <strong>复核路由</strong>
        </div>
        {transitions.length ? (
          transitions.map((transition) => (
            <div className="schema-route" key={transition.id}>
              <span>{transition.route}</span>
              <p>{transition.sourceNode} {"->"} {transition.targetNode}</p>
              <small>{transition.reviewAction ?? "PASS"} / 第 {transition.attempt + 1} 次判断</small>
            </div>
          ))
        ) : (
          <p className="muted-text">暂无 REVIEW_GATE 决策。</p>
        )}
      </div>
    </section>
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

function formatDateTime(value?: string) {
  if (!value) return "暂无";
  return new Date(value).toLocaleString("zh-CN", { hour12: false });
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
