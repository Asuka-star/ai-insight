package com.aiinsight.service;

import com.aiinsight.agent.node.AnalystNode;
import com.aiinsight.agent.node.ClarifierNode;
import com.aiinsight.agent.node.ExtractorNode;
import com.aiinsight.agent.node.ResearcherNode;
import com.aiinsight.agent.node.ReviewerNode;
import com.aiinsight.agent.node.RevisionNode;
import com.aiinsight.agent.node.WriterNode;
import com.aiinsight.dto.AddAnalysisContextRequest;
import com.aiinsight.dto.AddUserEvidenceRequest;
import com.aiinsight.dto.CreateAnalysisRunRequest;
import com.aiinsight.dto.UpdateAnalysisRequirementRequest;
import com.aiinsight.exception.InvalidRunStateException;
import com.aiinsight.llm.LlmClient;
import com.aiinsight.model.enums.AgentName;
import com.aiinsight.model.enums.AnalysisStatus;
import com.aiinsight.model.enums.ArtifactType;
import com.aiinsight.model.enums.ClaimType;
import com.aiinsight.model.enums.ContextIntent;
import com.aiinsight.model.enums.ReviewAction;
import com.aiinsight.model.enums.StepStatus;
import com.aiinsight.model.run.AnalysisArtifact;
import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.model.schema.AnalysisClaim;
import com.aiinsight.repository.AnalysisRunRepository;
import com.aiinsight.workflow.AnalysisLangGraphWorkflow;
import com.aiinsight.workflow.WorkflowNodeExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.support.TaskExecutorAdapter;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnalysisWorkflowServiceTest {

    @Test
    void createsDraftThenExecutesAfterRequirementConfirmation() {
        AnalysisWorkflowService service = newService();
        CreateAnalysisRunRequest request = new CreateAnalysisRunRequest();
        request.setPrompt("Analyze Notion and Confluence for AI document collaboration.");

        var draft = service.createDraft(request);

        assertThat(draft.getStatus()).isEqualTo(AnalysisStatus.AWAITING_CONFIRMATION);
        assertThat(draft.getSteps()).isEmpty();
        assertThat(draft.getClarificationDraft()).isNotNull();
        assertThat(draft.getClarificationDraft().isConfirmed()).isFalse();
        assertThat(draft.getClarificationDraft().getClarificationQuestions()).isNotEmpty();

        UpdateAnalysisRequirementRequest update = new UpdateAnalysisRequirementRequest();
        update.setCompetitors(List.of("Notion", "Confluence"));
        update.setOutputGoal("产品规划");
        var confirmed = service.updateRequirement(draft.getId(), update);

        assertThat(confirmed.getStatus()).isEqualTo(AnalysisStatus.PENDING);
        assertThat(confirmed.getRequirement().getOutputGoal()).isEqualTo("产品规划");
        assertThat(confirmed.getClarificationDraft().isConfirmed()).isTrue();

        AddAnalysisContextRequest context = new AddAnalysisContextRequest();
        context.setIntent(ContextIntent.ADJUST_SCOPE);
        context.setContent("再加入 Confluence，重点看企业权限和 AI 搜索能力，也补充价格页和公开评价。");
        var withContext = service.addContext(draft.getId(), context);

        assertThat(withContext.getStatus()).isEqualTo(AnalysisStatus.AWAITING_CONFIRMATION);
        assertThat(withContext.getContextMessages()).hasSize(1);
        assertThat(withContext.getRequirement().getCompetitors()).contains("Confluence");
        assertThat(withContext.getRequirement().getDimensions()).contains("权限协作", "AI 搜索", "价格策略", "用户评价");
        assertThat(withContext.getRequirement().getSourcePreferences()).contains("pricing_page", "public_reviews");

        var finished = service.startExecution(draft.getId());

        assertThat(finished.getStatus()).isEqualTo(AnalysisStatus.SUCCEEDED);
        assertThat(finished.getSteps()).isNotEmpty();
    }

    @Test
    void addsUserProvidedEvidenceAsCitableSourceAndChunk() {
        AnalysisWorkflowService service = newService();
        CreateAnalysisRunRequest request = new CreateAnalysisRunRequest();
        request.setPrompt("Analyze Notion and Confluence for AI document collaboration.");
        var run = service.createDraft(request);

        AddUserEvidenceRequest evidenceRequest = new AddUserEvidenceRequest();
        evidenceRequest.setTitle("Internal interview notes");
        evidenceRequest.setSourceType("interview");
        evidenceRequest.setContent("Users praised AI summaries, but asked for stronger enterprise permission governance.");
        evidenceRequest.setSensitive(true);

        var updated = service.addEvidence(run.getId(), evidenceRequest);

        assertThat(updated.getUserProvidedEvidence()).hasSize(1);
        assertThat(updated.getEvidenceSources()).hasSize(1);
        assertThat(updated.getEvidenceSources().get(0).getCitationKey()).isEqualTo("S1");
        assertThat(updated.getEvidenceSources().get(0).getSourceType()).isEqualTo("user_interview");
        assertThat(updated.getEvidenceSources().get(0).getComplianceNote()).contains("internal-only");
        assertThat(updated.getEvidenceChunks()).hasSize(1);
        assertThat(updated.getResearchPackage().getSources()).hasSize(1);
        assertThat(updated.getRecommendedActions()).anyMatch(action -> action.contains("用户证据 S1 已加入"));
    }

    @Test
    void addEvidenceContextCreatesCitableSource() {
        AnalysisWorkflowService service = newService();
        CreateAnalysisRunRequest request = new CreateAnalysisRunRequest();
        request.setPrompt("Analyze Notion and Confluence for AI document collaboration.");
        var run = service.createDraft(request);

        AddAnalysisContextRequest context = new AddAnalysisContextRequest();
        context.setIntent(ContextIntent.ADD_EVIDENCE);
        context.setContent("Interview notes: users want stronger audit trails for AI-generated document changes.");

        var updated = service.addContext(run.getId(), context);

        assertThat(updated.getContextMessages()).hasSize(1);
        assertThat(updated.getUserProvidedEvidence()).hasSize(1);
        assertThat(updated.getEvidenceSources()).hasSize(1);
        assertThat(updated.getEvidenceSources().get(0).getCitationKey()).isEqualTo("S1");
        assertThat(updated.getEvidenceChunks()).hasSize(1);
    }

    @Test
    void cancelsDraftAndBlocksRestart() {
        AnalysisWorkflowService service = newService();
        CreateAnalysisRunRequest request = new CreateAnalysisRunRequest();
        request.setPrompt("Analyze Notion and Confluence for AI document collaboration.");
        var run = service.createDraft(request);

        var cancelled = service.cancel(run.getId());

        assertThat(cancelled.getStatus()).isEqualTo(AnalysisStatus.CANCELLED);
        assertThat(cancelled.getRecommendedActions()).contains("任务已由用户取消。");
        assertThatThrownBy(() -> service.startExecution(run.getId()))
                .isInstanceOf(InvalidRunStateException.class)
                .hasMessageContaining("CANCELLED");
    }

    @Test
    void blocksRequirementChangesAfterRunSucceeded() {
        AnalysisWorkflowService service = newService();
        CreateAnalysisRunRequest request = new CreateAnalysisRunRequest();
        request.setPrompt("Analyze Notion and Confluence for AI document collaboration.");
        var finished = service.start(request);

        UpdateAnalysisRequirementRequest update = new UpdateAnalysisRequirementRequest();
        update.setOutputGoal("Change after completion");

        assertThat(finished.getStatus()).isEqualTo(AnalysisStatus.SUCCEEDED);
        assertThatThrownBy(() -> service.updateRequirement(finished.getId(), update))
                .isInstanceOf(InvalidRunStateException.class)
                .hasMessageContaining("SUCCEEDED");
    }

    @Test
    void executesFullWorkflowAndProducesFinalReport() {
        AnalysisWorkflowService service = newService();
        CreateAnalysisRunRequest request = new CreateAnalysisRunRequest();
        request.setPrompt("Analyze Notion and Confluence for AI document collaboration.");

        var run = service.start(request);
        var finished = service.get(run.getId());

        assertThat(finished.getStatus()).isEqualTo(AnalysisStatus.SUCCEEDED);
        assertThat(finished.getSteps()).hasSize(12);
        assertThat(finished.getTraces()).hasSize(12);
        assertThat(finished.getTraces()).allSatisfy(trace -> {
            assertThat(trace.getStepId()).isNotNull();
            assertThat(trace.getStatus()).isEqualTo(StepStatus.SUCCEEDED);
            assertThat(trace.getStartedAt()).isNotNull();
            assertThat(trace.getCompletedAt()).isNotNull();
            assertThat(trace.getLatencyMs()).isNotNull();
            assertThat(trace.getInputSnapshot()).isNotBlank();
            assertThat(trace.getOutputSnapshot()).isNotBlank();
        });
        assertThat(finished.getTraces())
                .filteredOn(trace -> trace.getAgentName() == AgentName.WRITER)
                .allSatisfy(trace -> {
                    assertThat(trace.getFallbackUsed()).isTrue();
                    assertThat(trace.getModelName()).isEqualTo("deterministic-writer-fallback");
                    assertThat(trace.getRawModelOutput()).isNotBlank();
                    assertThat(trace.getCompletionTokens()).isPositive();
                    assertThat(trace.getTotalTokens()).isEqualTo(trace.getCompletionTokens());
                });
        assertThat(finished.getTraces())
                .filteredOn(trace -> trace.getAgentName() == AgentName.REVIEWER)
                .allSatisfy(trace -> {
                    assertThat(trace.getFallbackUsed()).isTrue();
                    assertThat(trace.getModelName()).isEqualTo("deterministic-reviewer-fallback");
                    assertThat(trace.getRawModelOutput()).isNotBlank();
                    assertThat(trace.getCompletionTokens()).isNotNegative();
                    assertThat(trace.getTotalTokens()).isEqualTo(trace.getCompletionTokens());
                });
        assertThat(finished.getWorkflowTransitions()).hasSize(2);
        assertThat(finished.getWorkflowTransitions().get(0).getRoute()).isEqualTo("recollect");
        assertThat(finished.getWorkflowTransitions().get(0).getTargetNode()).isEqualTo(AgentName.RESEARCHER.name());
        assertThat(finished.getWorkflowTransitions().get(1).getRoute()).isEqualTo("finish");
        assertThat(finished.getWorkflowTransitions().get(1).getTargetNode()).isEqualTo(AgentName.REVISION.name());
        assertThat(finished.getSteps())
                .filteredOn(step -> step.getAgentName() == AgentName.RESEARCHER)
                .hasSize(2);
        assertThat(finished.getSteps())
                .filteredOn(step -> step.getAgentName() == AgentName.REVIEWER)
                .hasSize(2);
        assertThat(finished.getEvidenceSources()).hasSizeGreaterThanOrEqualTo(4);
        assertThat(finished.getEvidenceChunks()).hasSizeGreaterThanOrEqualTo(4);
        assertThat(service.retrieveEvidence(finished.getId(), "Notion", 3)).isNotEmpty();
        assertThat(finished.getResearchPackage().getSources()).hasSizeGreaterThanOrEqualTo(4);
        assertThat(finished.getResearchPackage().getMissingEvidenceTypes()).isEmpty();
        assertThat(finished.getResearchPackage().getResearchPlan().getQuestionnaire().getQuestions()).isNotEmpty();
        assertThat(finished.getResearchPackage().getResearchPlan().getInterviewGuide().getQuestions()).isNotEmpty();
        assertThat(finished.getResearchPackage().getResearchPlan().getPublicSourceTasks()).isNotEmpty();
        assertThat(finished.getResearchPackage().getResearchPlan().getSearchQueries()).isNotEmpty();
        assertThat(finished.getCompetitorProfiles()).hasSize(2);
        assertThat(finished.getCompetitorProfiles())
                .allSatisfy(profile -> {
                    assertThat(profile.getFeatureTree().getRoots()).isNotEmpty();
                    assertThat(profile.getPricingModel().getEvidenceIds()).isNotEmpty();
                    assertThat(profile.getPricingModel().getPlans()).isNotEmpty();
                    assertThat(profile.getPersonas()).isNotEmpty();
                    assertThat(profile.getEvidenceIds()).isNotEmpty();
                });
        assertThat(finished.getClaims()).hasSize(3);
        assertThat(finished.getClaims())
                .extracting(claim -> claim.getType())
                .containsExactlyInAnyOrder(ClaimType.COMPARISON, ClaimType.OPPORTUNITY, ClaimType.RISK);
        assertThat(finished.getClaims()).allSatisfy(claim -> assertThat(claim.getEvidenceIds()).isNotEmpty());
        assertThat(finished.getReviewDecision().getAction()).isEqualTo(ReviewAction.PASS);
        assertThat(finished.getArtifacts())
                .filteredOn(artifact -> artifact.getType() == ArtifactType.REVIEW_FINDINGS)
                .hasSize(2);
        assertThat(finished.getArtifacts())
                .filteredOn(artifact -> artifact.getType() == ArtifactType.REPORT_DRAFT)
                .extracting(artifact -> artifact.getVersion())
                .containsExactly(1, 2);
        assertThat(finished.getArtifacts())
                .filteredOn(artifact -> artifact.getType() == ArtifactType.SOURCE_LIST)
                .extracting(artifact -> artifact.getVersion())
                .containsExactly(1, 2);
        assertThat(finished.getArtifacts()).anyMatch(artifact -> artifact.getType() == ArtifactType.SWOT_ANALYSIS);
        assertThat(finished.getArtifacts()).anyMatch(artifact -> artifact.getType() == ArtifactType.RESEARCH_PLAN);
        assertThat(finished.getArtifacts()).anyMatch(artifact -> artifact.getType() == ArtifactType.FINAL_REPORT);
        assertThat(finished.getReviewFindings()).isEmpty();
    }

    @Test
    void researcherBuildsSurveyFromRequestedDomainAndDimensions() {
        AnalysisWorkflowService service = newService();
        CreateAnalysisRunRequest request = new CreateAnalysisRunRequest();
        request.setPrompt("分析 Salesforce 和 HubSpot 在 CRM 销售自动化方向的竞品机会。");
        request.setIndustry("企业服务 CRM");
        request.setCompetitors(List.of("Salesforce", "HubSpot"));
        request.setDimensions(List.of("线索管理", "价格策略", "集成能力"));

        var run = service.start(request);
        var questionnaire = service.get(run.getId()).getResearchPackage().getResearchPlan().getQuestionnaire();
        var interviewGuide = service.get(run.getId()).getResearchPackage().getResearchPlan().getInterviewGuide();

        assertThat(questionnaire.getTitle()).contains("企业服务 CRM");
        assertThat(questionnaire.getTargetRespondents()).contains("Salesforce", "HubSpot");
        assertThat(questionnaire.getQuestions())
                .extracting(question -> question.getDimension())
                .contains("线索管理", "价格策略", "集成能力");
        assertThat(questionnaire.getQuestions())
                .flatExtracting(question -> question.getOptions())
                .contains("线索管理", "客户跟进", "销售预测");
        assertThat(interviewGuide.getQuestions()).anyMatch(question -> question.contains("Salesforce、HubSpot"));
    }

    @Test
    void researcherUsesLlmGeneratedResearchPlanWhenAvailable() {
        LlmClient researchPlanLlm = new LlmClient() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String complete(com.aiinsight.llm.ChatRequest request) {
                return """
                        {
                          "objective": "验证 CRM 竞品在销售流程中的真实使用差异",
                          "evidenceGaps": ["sales_ops_interview"],
                          "searchQueries": ["Salesforce HubSpot sales workflow comparison", "HubSpot CRM onboarding pain points"],
                          "publicSourceTasks": [
                            {"type": "public_review", "target": "Salesforce 与 HubSpot 销售运营评价", "rationale": "补充真实用户反馈", "status": "needs_collection"}
                          ],
                          "questionnaire": {
                            "title": "CRM 销售运营竞品决策问卷",
                            "targetRespondents": "评估过 Salesforce 或 HubSpot 的销售运营、销售主管和 CRM 管理员",
                            "recommendedSampleSize": "20-40 份，覆盖销售运营、销售主管和管理员",
                            "questions": [
                              {"dimension": "线索管理效率", "question": "在 Salesforce 或 HubSpot 中完成线索分配和跟进提醒的效率如何？", "options": ["明显提升", "基本满足", "流程偏重", "需要人工补充"]},
                              {"dimension": "销售预测可信度", "question": "你是否信任系统给出的销售预测或阶段判断？", "options": ["非常信任", "部分信任", "不信任", "未使用"]},
                              {"dimension": "采购顾虑", "question": "采购或续费 CRM 时最主要的阻力是什么？", "options": ["价格", "迁移成本", "集成成本", "团队学习成本"]}
                            ]
                          },
                          "interviewGuide": {
                            "title": "CRM 销售运营用户访谈提纲",
                            "targetRoles": ["销售运营", "销售主管", "CRM 管理员"],
                            "questions": ["最近一次使用 Salesforce 或 HubSpot 跟进线索的流程是什么？", "哪个环节最影响销售团队效率？", "如果切换竞品，最大的阻力是什么？"],
                            "probingQuestions": ["能否举一个具体商机或客户跟进案例？", "这个问题每周出现几次？", "你愿意为哪些能力付费？"]
                          }
                        }
                        """;
            }
        };
        SourceCollectionService sourceCollectionService = new SourceCollectionService(fetchAlwaysFails(), fakeSearchProvider());
        ResearcherNode researcherNode = researcherNode(sourceCollectionService, researchPlanLlm);
        var run = new com.aiinsight.model.run.AnalysisRun(new com.aiinsight.model.run.AnalysisRequirement(
                "分析 Salesforce 和 HubSpot 在 CRM 销售自动化方向的竞品机会。",
                "企业服务 CRM",
                List.of("Salesforce", "HubSpot"),
                List.of("线索管理效率", "销售预测可信度", "采购顾虑"),
                List.of("public_reviews", "interview"),
                List.of()
        ));

        researcherNode.execute(run);

        var questionnaire = run.getResearchPackage().getResearchPlan().getQuestionnaire();
        assertThat(questionnaire.getTitle()).isEqualTo("CRM 销售运营竞品决策问卷");
        assertThat(questionnaire.getQuestions())
                .extracting(question -> question.getDimension())
                .containsExactly("线索管理效率", "销售预测可信度", "采购顾虑");
        assertThat(run.getResearchPackage().getResearchPlan().getInterviewGuide().getTargetRoles())
                .contains("销售运营", "销售主管", "CRM 管理员");
        assertThat(run.getResearchPackage().getResearchPlan().getSearchQueries())
                .contains("Salesforce HubSpot sales workflow comparison");
    }

    @Test
    void researcherTurnsInterviewEvidenceIntoPersonaSignals() {
        AnalysisWorkflowService service = newService();
        CreateAnalysisRunRequest request = new CreateAnalysisRunRequest();
        request.setPrompt("分析 Salesforce 和 HubSpot 在 CRM 销售自动化方向的竞品机会。");
        request.setIndustry("企业服务 CRM");
        request.setCompetitors(List.of("Salesforce", "HubSpot"));
        request.setDimensions(List.of("权限治理", "价格策略", "用户体验"));
        var run = service.createDraft(request);

        AddUserEvidenceRequest evidenceRequest = new AddUserEvidenceRequest();
        evidenceRequest.setTitle("Salesforce 用户访谈");
        evidenceRequest.setSourceType("interview");
        evidenceRequest.setContent("受访者是销售运营管理员。最近在 Salesforce 做线索管理和客户跟进，觉得权限配置复杂，学习成本高，也担心价格和 HubSpot 集成成本。她认可销售预测提效，但不满意审批流程慢。");

        service.addEvidence(run.getId(), evidenceRequest);
        var finished = service.startExecution(run.getId());

        assertThat(finished.getResearchPackage().getInterviewInsights()).hasSize(1);
        var insight = finished.getResearchPackage().getInterviewInsights().get(0);
        assertThat(insight.getEvidenceId()).isEqualTo("S1");
        assertThat(insight.getIntervieweeRole()).contains("销售");
        assertThat(insight.getCompetitorMentions()).contains("Salesforce", "HubSpot");
        assertThat(insight.getPainPoints()).anyMatch(point -> point.contains("权限配置复杂"));
        assertThat(insight.getBuyingConcerns()).contains("价格/预算", "学习成本", "集成成本");
        assertThat(finished.getCompetitorProfiles())
                .flatExtracting(profile -> profile.getPersonas())
                .allSatisfy(persona -> {
                    assertThat(persona.getPainPoints()).anyMatch(point -> point.contains("权限配置复杂"));
                    assertThat(persona.getBuyingConcerns()).contains("价格/预算");
                    assertThat(persona.getEvidenceIds()).contains("S1");
                });
    }

    @Test
    void rerunAgentAppendsNextArtifactVersion() {
        AnalysisWorkflowService service = newService();
        CreateAnalysisRunRequest request = new CreateAnalysisRunRequest();
        request.setPrompt("Analyze Notion and Confluence for AI document collaboration.");

        var run = service.start(request);
        var rerun = service.rerunAgent(run.getId(), AgentName.WRITER);

        assertThat(rerun.getArtifacts())
                .filteredOn(artifact -> artifact.getType() == ArtifactType.REPORT_DRAFT)
                .extracting(artifact -> artifact.getVersion())
                .containsExactly(1, 2, 3);
    }

    @Test
    void reviewerFindingsCarryArtifactAndClaimLocation() {
        LlmClient noopLlmClient = new LlmClient() {
            @Override
            public boolean isAvailable() {
                return false;
            }

            @Override
            public String complete(com.aiinsight.llm.ChatRequest request) {
                throw new IllegalStateException("LLM is not configured");
            }
        };
        AnalysisRun run = new AnalysisRun();
        AnalysisClaim claim = new AnalysisClaim();
        claim.setType(ClaimType.OPPORTUNITY);
        run.getClaims().add(claim);
        AnalysisArtifact draft = run.addArtifact(new AnalysisArtifact(
                ArtifactType.REPORT_DRAFT,
                "draft",
                "机会点是构建可复核的 Agent 工作流。",
                List.of()
        ));

        new ReviewerNode(new CitationCoverageEvaluator(), noopLlmClient).execute(run);

        assertThat(run.getReviewFindings()).hasSize(1);
        assertThat(run.getReviewFindings().get(0).getArtifactId()).isEqualTo(draft.getId());
        assertThat(run.getReviewFindings().get(0).getClaimId()).isEqualTo(claim.getId());
        assertThat(run.getReviewFindings().get(0).getExcerpt()).contains("机会点");
    }

    private AnalysisWorkflowService newService() {
        LlmClient noopLlmClient = new LlmClient() {
            @Override
            public boolean isAvailable() {
                return false;
            }

            @Override
            public String complete(com.aiinsight.llm.ChatRequest request) {
                throw new IllegalStateException("LLM is not configured");
            }
        };
        AnalysisRunRepository repository = new TestAnalysisRunRepository();
        AnalysisEventBroker eventBroker = new AnalysisEventBroker();
        WorkflowNodeExecutor nodeExecutor = new WorkflowNodeExecutor(repository, eventBroker);
        SourceCollectionService sourceCollectionService = new SourceCollectionService(fetchAlwaysFails(), fakeSearchProvider());
        AnalysisLangGraphWorkflow graphWorkflow = new AnalysisLangGraphWorkflow(
                List.of(
                        new RevisionNode(),
                        new WriterNode(noopLlmClient),
                        new ReviewerNode(new CitationCoverageEvaluator(), noopLlmClient),
                        new AnalystNode(noopLlmClient),
                        new ExtractorNode(noopLlmClient),
                        researcherNode(sourceCollectionService, noopLlmClient),
                        new ClarifierNode(noopLlmClient)
                ),
                nodeExecutor,
                repository,
                eventBroker
        );
        assertThat(graphWorkflow.mermaid()).contains("REVIEW_GATE");
        return new AnalysisWorkflowService(
                repository,
                new AnalysisRequestNormalizer(),
                eventBroker,
                new TaskExecutorAdapter(Runnable::run),
                graphWorkflow,
                new EvidenceRetrievalService(),
                sourceCollectionService,
                new EvidenceChunkService()
        );
    }

    private WebPageFetchService fetchAlwaysFails() {
        return new WebPageFetchService() {
            @Override
            public FetchedPage fetch(String url) {
                return FetchedPage.failed(url, "simulated fetch failure");
            }
        };
    }

    private SearchProvider fakeSearchProvider() {
        return new SearchProvider() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public List<SearchResult> search(String query, int count) {
                return List.of(new SearchResult(
                        "Search result for " + query,
                        "https://search.example.test/" + query.toLowerCase().replaceAll("[^a-z0-9]+", "-"),
                        "Snippet for " + query + " with pricing, reviews, AI collaboration and permission details.",
                        query,
                        1
                ));
            }
        };
    }

    private ResearcherNode researcherNode(SourceCollectionService sourceCollectionService, LlmClient llmClient) {
        return new ResearcherNode(
                sourceCollectionService,
                new EvidenceChunkService(),
                llmClient,
                new ObjectMapper(),
                new FallbackResearchPlanFactory(),
                new InterviewInsightExtractor()
        );
    }

    private static class TestAnalysisRunRepository implements AnalysisRunRepository {

        private final ConcurrentMap<UUID, AnalysisRun> runs = new ConcurrentHashMap<>();

        @Override
        public AnalysisRun save(AnalysisRun run) {
            run.touch();
            runs.put(run.getId(), run);
            return run;
        }

        @Override
        public Optional<AnalysisRun> findById(UUID id) {
            return Optional.ofNullable(runs.get(id));
        }

        @Override
        public Collection<AnalysisRun> findAll() {
            return runs.values();
        }
    }
}
