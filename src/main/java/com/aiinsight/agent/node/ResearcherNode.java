package com.aiinsight.agent.node;

import com.aiinsight.agent.AgentNode;
import com.aiinsight.llm.ChatMessage;
import com.aiinsight.llm.ChatOptions;
import com.aiinsight.llm.ChatRequest;
import com.aiinsight.llm.LlmClient;
import com.aiinsight.model.enums.AgentName;
import com.aiinsight.model.enums.ArtifactType;
import com.aiinsight.model.enums.ReviewAction;
import com.aiinsight.model.run.AnalysisArtifact;
import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.model.run.EvidenceSource;
import com.aiinsight.model.schema.InterviewGuide;
import com.aiinsight.model.schema.InterviewInsight;
import com.aiinsight.model.schema.Questionnaire;
import com.aiinsight.model.schema.ResearchPlan;
import com.aiinsight.model.schema.SurveyQuestion;
import com.aiinsight.service.EvidenceChunkService;
import com.aiinsight.service.FallbackResearchPlanFactory;
import com.aiinsight.service.InterviewInsightExtractor;
import com.aiinsight.service.SourceCollectionService;
import com.aiinsight.observability.AgentTraceContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
// Researcher 只负责产出“可引用证据”，不直接写分析结论。
// 优先采集用户提供的公开 URL 和一手调研材料；搜索服务可用时再主动补充公开网页。
public class ResearcherNode implements AgentNode {

    private final SourceCollectionService sourceCollectionService;
    private final EvidenceChunkService evidenceChunkService;
    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;
    private final FallbackResearchPlanFactory fallbackResearchPlanFactory;
    private final InterviewInsightExtractor interviewInsightExtractor;

    @Override
    public AgentName name() {
        return AgentName.RESEARCHER;
    }

    @Override
    public String title() {
        return "采集资料与证据";
    }

    @Override
    public AnalysisRun execute(AnalysisRun run) {
        boolean recollecting = run.getReviewDecision().getAction() == ReviewAction.RECOLLECT_EVIDENCE
                && run.getReviewDecision().getTargetAgent() == name();
        // 采集重跑时先清空旧证据，避免同一 citationKey 指向多个来源。
        run.getEvidenceSources().clear();
        run.getEvidenceChunks().clear();
        run.getEvidenceSources().addAll(sourceCollectionService.collect(run, recollecting));
        run.getEvidenceChunks().addAll(evidenceChunkService.chunk(run.getEvidenceSources()));
        run.getResearchPackage().setSources(new ArrayList<>(run.getEvidenceSources()));
        run.getResearchPackage().setMissingEvidenceTypes(recollecting
                ? List.of()
                : missingEvidenceTypes(run));
        run.getResearchPackage().setResearchPlan(buildResearchPlan(run));
        run.getResearchPackage().setInterviewInsights(interviewInsightExtractor.extract(run));
        run.getResearchPackage().setCollectedAt(Instant.now());

        run.addArtifact(new AnalysisArtifact(
                ArtifactType.SOURCE_LIST,
                "资料采集清单",
                sourceListMarkdown(run),
                run.getEvidenceSources().stream().map(EvidenceSource::getCitationKey).toList()
        ));
        run.addArtifact(new AnalysisArtifact(
                ArtifactType.RESEARCH_PLAN,
                "调研计划与一手资料设计",
                researchPlanMarkdown(
                        run.getResearchPackage().getResearchPlan(),
                        run.getResearchPackage().getInterviewInsights()
                ),
                run.getEvidenceSources().stream().map(EvidenceSource::getCitationKey).toList()
        ));
        return run;
    }

    private String sourceListMarkdown(AnalysisRun run) {
        return run.getEvidenceSources().stream()
                .map(source -> "- [%s] %s: %s".formatted(source.getCitationKey(), source.getTitle(), source.getSnippet()))
                .collect(Collectors.joining("\n"));
    }

    private List<String> missingEvidenceTypes(AnalysisRun run) {
        List<String> missing = new ArrayList<>();
        if (!hasEvidenceType(run, "pricing")) {
            missing.add("pricing_page");
        }
        if (!hasEvidenceType(run, "feedback") && !hasEvidenceType(run, "review")) {
            missing.add("user_review");
        }
        if (needsFieldResearch(run) && !hasEvidenceType(run, "survey")) {
            missing.add("survey_result");
        }
        if (needsFieldResearch(run) && !hasEvidenceType(run, "interview")) {
            missing.add("interview_note");
        }
        return missing;
    }

    private boolean needsFieldResearch(AnalysisRun run) {
        return mentionsAny(run.getRequirement().getDimensions(), "用户", "评价", "痛点", "画像", "满意度", "调研", "访谈", "问卷")
                || mentionsAny(run.getRequirement().getSourcePreferences(), "public_reviews", "review", "survey", "interview", "访谈", "问卷");
    }

    private boolean hasEvidenceType(AnalysisRun run, String keyword) {
        return run.getEvidenceSources().stream().anyMatch(source ->
                containsIgnoreCase(source.getSourceType(), keyword)
                        || containsIgnoreCase(source.getTitle(), keyword)
                        || containsIgnoreCase(source.getComplianceNote(), keyword));
    }

    private boolean mentionsAny(List<String> values, String... patterns) {
        return values.stream().anyMatch(value -> {
            for (String pattern : patterns) {
                if (containsIgnoreCase(value, pattern)) {
                    return true;
                }
            }
            return false;
        });
    }

    private boolean containsIgnoreCase(String text, String pattern) {
        return text != null && pattern != null && text.toLowerCase(Locale.ROOT).contains(pattern.toLowerCase(Locale.ROOT));
    }

    private ResearchPlan buildResearchPlan(AnalysisRun run) {
        ResearchPlan fallback = fallbackResearchPlanFactory.build(run);
        if (!llmClient.isAvailable()) {
            AgentTraceContext.recordFallback("deterministic-research-plan-fallback", researchPlanMarkdown(fallback, List.of()));
            return fallback;
        }
        try {
            return mergeResearchPlan(generateResearchPlanWithLlm(run, fallback), fallback);
        } catch (RuntimeException ex) {
            run.getRecommendedActions().add("LLM 调研计划生成失败，已使用规则模板兜底：" + ex.getMessage());
            AgentTraceContext.recordFallback("deterministic-research-plan-fallback", researchPlanMarkdown(fallback, List.of()));
            return fallback;
        }
    }

    private ResearchPlan generateResearchPlanWithLlm(AnalysisRun run, ResearchPlan fallback) {
        String prompt = """
                你是竞品分析工作流中的信息采集 Agent，负责设计调研计划、问卷和用户访谈提纲。
                请基于用户课题、行业、竞品、分析维度和已有证据缺口，生成一份能真实服务竞品分析的信息采集方案。

                输出必须是一个 JSON 对象，不要 Markdown，不要解释。字段如下：
                {
                  "objective": "调研目标",
                  "evidenceGaps": ["缺口"],
                  "searchQueries": ["搜索 query"],
                  "publicSourceTasks": [
                    {"type": "public_web|pricing_page|public_review|survey|interview", "target": "采集对象", "rationale": "为什么采集", "status": "prepared|covered|needs_collection"}
                  ],
                  "questionnaire": {
                    "title": "问卷标题",
                    "targetRespondents": "目标样本",
                    "recommendedSampleSize": "建议样本量",
                    "questions": [
                      {"dimension": "调研维度", "question": "题目", "options": ["选项1", "选项2", "选项3"]}
                    ]
                  },
                  "interviewGuide": {
                    "title": "访谈提纲标题",
                    "targetRoles": ["目标角色"],
                    "questions": ["主问题"],
                    "probingQuestions": ["追问"]
                  }
                }

                约束：
                1. 问卷必须围绕用户给出的竞品和分析维度生成，不要套用固定行业模板。
                2. 每个分析维度至少对应 1 道问卷题；题目要能形成可比较的数据。
                3. 访谈问题要追问真实使用场景、切换阻力、采购顾虑、满意/不满意原因和竞品差异。
                4. 搜索 query 要贴合行业和竞品，不要默认写 AI collaboration，除非用户课题确实需要。
                5. 不要编造已经发生的调研结果，只设计采集方案。

                用户课题：%s
                行业：%s
                竞品：%s
                分析维度：%s
                偏好来源：%s
                证据缺口：%s
                已有来源：%s

                规则兜底草稿，仅供参考，不要机械照抄：
                %s
                """.formatted(
                run.getRequirement().getOriginalPrompt(),
                researchDomain(run),
                String.join("、", run.getRequirement().getCompetitors()),
                String.join("、", run.getRequirement().getDimensions()),
                String.join("、", run.getRequirement().getSourcePreferences()),
                String.join("、", run.getResearchPackage().getMissingEvidenceTypes()),
                run.getEvidenceSources().stream()
                        .map(source -> "[%s] %s: %s".formatted(source.getCitationKey(), source.getTitle(), source.getSnippet()))
                        .collect(Collectors.joining("\n")),
                researchPlanMarkdown(fallback, List.of())
        );
        String response = llmClient.complete(new ChatRequest(
                List.of(
                        ChatMessage.system("你是严谨的信息采集 Agent。只输出可解析 JSON，所有问题都要服务竞品分析。"),
                        ChatMessage.user(prompt)
                ),
                ChatOptions.deterministic()
        ));
        return parseResearchPlanJson(response);
    }

    private ResearchPlan parseResearchPlanJson(String response) {
        try {
            return objectMapper.readValue(extractJsonObject(response), ResearchPlan.class);
        } catch (Exception ex) {
            throw new IllegalStateException("无法解析调研计划 JSON", ex);
        }
    }

    private String extractJsonObject(String response) {
        if (response == null || response.isBlank()) {
            throw new IllegalStateException("模型输出为空");
        }
        String trimmed = response.trim();
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "").trim();
        }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalStateException("模型输出不包含 JSON 对象");
        }
        return trimmed.substring(start, end + 1);
    }

    private ResearchPlan mergeResearchPlan(ResearchPlan generated, ResearchPlan fallback) {
        ResearchPlan plan = generated == null ? new ResearchPlan() : generated;
        if (!hasText(plan.getObjective())) {
            plan.setObjective(fallback.getObjective());
        }
        if (plan.getEvidenceGaps() == null || plan.getEvidenceGaps().isEmpty()) {
            plan.setEvidenceGaps(new ArrayList<>(fallback.getEvidenceGaps()));
        }
        if (plan.getSearchQueries() == null || plan.getSearchQueries().isEmpty()) {
            plan.setSearchQueries(new ArrayList<>(fallback.getSearchQueries()));
        }
        if (plan.getPublicSourceTasks() == null || plan.getPublicSourceTasks().isEmpty()) {
            plan.setPublicSourceTasks(new ArrayList<>(fallback.getPublicSourceTasks()));
        }
        if (!isUsableQuestionnaire(plan.getQuestionnaire())) {
            plan.setQuestionnaire(fallback.getQuestionnaire());
        }
        if (!isUsableInterviewGuide(plan.getInterviewGuide())) {
            plan.setInterviewGuide(fallback.getInterviewGuide());
        }
        return plan;
    }

    private boolean isUsableQuestionnaire(Questionnaire questionnaire) {
        return questionnaire != null
                && hasText(questionnaire.getTitle())
                && hasText(questionnaire.getTargetRespondents())
                && questionnaire.getQuestions() != null
                && questionnaire.getQuestions().stream().filter(this::isUsableSurveyQuestion).count() >= 3;
    }

    private boolean isUsableSurveyQuestion(SurveyQuestion question) {
        return question != null
                && hasText(question.getDimension())
                && hasText(question.getQuestion())
                && question.getOptions() != null
                && question.getOptions().size() >= 2;
    }

    private boolean isUsableInterviewGuide(InterviewGuide guide) {
        return guide != null
                && hasText(guide.getTitle())
                && guide.getTargetRoles() != null
                && !guide.getTargetRoles().isEmpty()
                && guide.getQuestions() != null
                && guide.getQuestions().size() >= 3;
    }

    private String researchDomain(AnalysisRun run) {
        String industry = run.getRequirement().getIndustry();
        if (industry == null || industry.isBlank() || "待澄清行业".equals(industry)) {
            return "目标领域";
        }
        return industry;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String researchPlanMarkdown(ResearchPlan plan, List<InterviewInsight> interviewInsights) {
        String tasks = plan.getPublicSourceTasks().stream()
                .map(task -> "- %s：%s（%s，状态：%s）".formatted(
                        task.getType(),
                        task.getTarget(),
                        task.getRationale(),
                        task.getStatus()
                ))
                .collect(Collectors.joining("\n"));
        String questions = plan.getQuestionnaire().getQuestions().stream()
                .map(question -> "- [%s] %s 选项：%s".formatted(
                        question.getDimension(),
                        question.getQuestion(),
                        String.join(" / ", question.getOptions())
                ))
                .collect(Collectors.joining("\n"));
        String interviewQuestions = plan.getInterviewGuide().getQuestions().stream()
                .map(question -> "- " + question)
                .collect(Collectors.joining("\n"));
        String insights = interviewInsights.stream()
                .map(insight -> "- [%s] %s：痛点=%s；顾虑=%s；竞品=%s".formatted(
                        insight.getEvidenceId(),
                        insight.getIntervieweeRole(),
                        insight.getPainPoints().isEmpty() ? "待补充" : String.join(" / ", insight.getPainPoints()),
                        String.join(" / ", insight.getBuyingConcerns()),
                        insight.getCompetitorMentions().isEmpty() ? "未显式提及" : String.join(" / ", insight.getCompetitorMentions())
                ))
                .collect(Collectors.joining("\n"));
        return """
                ## 调研目标

                %s

                ## 证据缺口

                %s

                ## 搜索 Query

                %s

                ## 公开资料采集任务

                %s

                ## 问卷草案

                目标样本：%s

                %s

                ## 访谈提纲

                目标角色：%s

                %s

                ## 访谈洞察

                %s
                """.formatted(
                plan.getObjective(),
                plan.getEvidenceGaps().isEmpty() ? "暂无关键缺口。" : String.join("、", plan.getEvidenceGaps()),
                plan.getSearchQueries().isEmpty() ? "暂无搜索 query。" : plan.getSearchQueries().stream()
                        .map(query -> "- " + query)
                        .collect(Collectors.joining("\n")),
                tasks,
                plan.getQuestionnaire().getTargetRespondents(),
                questions,
                String.join("、", plan.getInterviewGuide().getTargetRoles()),
                interviewQuestions,
                interviewInsights.isEmpty() ? "暂无已结构化访谈洞察。" : insights
        );
    }
}
