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
import com.aiinsight.service.InterviewInsightExtractor;
import com.aiinsight.service.SourceCollectionService;
import com.aiinsight.service.fallback.FallbackResearchPlanFactory;
import com.aiinsight.observability.AgentTraceContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
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
        // SourceCollectionService 会保留既有 citation 并追加新来源；这里再整体替换列表，
        // 避免旧 artifact 中的 [S1] 在重跑后指向不同来源。
        List<EvidenceSource> collectedSources = sourceCollectionService.collect(run, recollecting);
        run.getEvidenceSources().clear();
        run.getEvidenceChunks().clear();
        run.getEvidenceSources().addAll(collectedSources);
        run.getEvidenceChunks().addAll(evidenceChunkService.chunk(run.getEvidenceSources()));
        run.getResearchPackage().setSources(new ArrayList<>(run.getEvidenceSources()));
        // missingEvidenceTypes 是 Reviewer 是否打回采集的主要依据。
        // 每次采集后都重新计算，避免补采成功后仍保留旧缺口。
        run.getResearchPackage().setMissingEvidenceTypes(missingEvidenceTypes(run));
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
            log.warn("Research plan fallback activated: runId={}, reason=llm_unavailable, competitors={}, missingEvidenceTypes={}, evidenceSources={}",
                    run.getId(),
                    run.getRequirement().getCompetitors(),
                    run.getResearchPackage().getMissingEvidenceTypes(),
                    run.getEvidenceSources().size());
            AgentTraceContext.recordFallback("deterministic-research-plan-fallback", researchPlanMarkdown(fallback, List.of()));
            return fallback;
        }
        try {
            return mergeResearchPlan(generateResearchPlanWithLlm(run, fallback), fallback);
        } catch (RuntimeException ex) {
            log.warn("Research plan fallback activated: runId={}, reason=llm_exception, exceptionType={}, message={}, competitors={}, missingEvidenceTypes={}, evidenceSources={}",
                    run.getId(),
                    ex.getClass().getName(),
                    ex.getMessage(),
                    run.getRequirement().getCompetitors(),
                    run.getResearchPackage().getMissingEvidenceTypes(),
                    run.getEvidenceSources().size());
            run.getRecommendedActions().add("LLM 调研计划生成失败，已使用规则模板兜底：" + ex.getMessage());
            AgentTraceContext.recordFallback("deterministic-research-plan-fallback", researchPlanMarkdown(fallback, List.of()));
            return fallback;
        }
    }

    private ResearchPlan generateResearchPlanWithLlm(AnalysisRun run, ResearchPlan fallback) {
        CompletableFuture<LlmSubtaskResult<ResearchPlan>> strategyTask = CompletableFuture.supplyAsync(
                () -> runResearchPlanSubtask("research-strategy", () -> generateResearchStrategyWithLlm(run))
        );
        CompletableFuture<LlmSubtaskResult<Questionnaire>> questionnaireTask = CompletableFuture.supplyAsync(
                () -> runResearchPlanSubtask("questionnaire", () -> generateQuestionnaireWithLlm(run))
        );
        CompletableFuture<LlmSubtaskResult<InterviewGuide>> interviewTask = CompletableFuture.supplyAsync(
                () -> runResearchPlanSubtask("interview-guide", () -> generateInterviewGuideWithLlm(run))
        );
        CompletableFuture.allOf(strategyTask, questionnaireTask, interviewTask).join();

        LlmSubtaskResult<ResearchPlan> strategyResult = strategyTask.join();
        LlmSubtaskResult<Questionnaire> questionnaireResult = questionnaireTask.join();
        LlmSubtaskResult<InterviewGuide> interviewResult = interviewTask.join();

        ResearchPlan plan = strategyResult.value() == null ? new ResearchPlan() : strategyResult.value();
        plan.setQuestionnaire(questionnaireResult.value());
        plan.setInterviewGuide(interviewResult.value());

        List<LlmSubtaskResult<?>> results = List.of(strategyResult, questionnaireResult, interviewResult);
        recordParallelResearchTrace(results);
        results.stream()
                .filter(result -> !result.succeeded())
                .forEach(result -> run.getRecommendedActions().add(
                        "LLM 调研子任务失败，已对该字段使用规则兜底：" + result.name() + " - " + result.errorMessage()));
        if (results.stream().noneMatch(LlmSubtaskResult::succeeded)) {
            throw new IllegalStateException("调研计划 LLM 子任务全部失败");
        }
        return plan;
    }

    private ResearchPlan generateResearchStrategyWithLlm(AnalysisRun run) {
        String prompt = """
                你是竞品分析工作流中的信息采集 Agent。请只生成“采集策略增量”，不要输出完整问卷或访谈提纲。
                问卷和访谈会由另外两个并行 LLM 子任务生成；你只负责公开资料和采集策略。

                输出必须是一个 JSON 对象，不要 Markdown，不要解释。字段如下：
                {
                  "objective": "不超过80字的调研目标",
                  "evidenceGaps": ["最多4个缺口"],
                  "searchQueries": ["最多6个搜索 query"],
                  "publicSourceTasks": [
                    {"type": "public_web|pricing_page|public_review|survey|interview", "target": "采集对象", "rationale": "不超过40字", "status": "prepared|covered|needs_collection"}
                  ]
                }

                约束：
                1. 不要输出 questionnaire、interviewGuide 或任何长题目列表。
                2. searchQueries 必须贴合竞品、行业和分析维度。
                3. 默认优先官网、官方文档、更新日志、定价页、官方技术博客、权威媒体或行业报告。
                4. 来源偏好只表示本次重点覆盖的来源类型，不代表可以降低来源权威性。
                5. publicSourceTasks 最多 6 项，关键结论不要只依赖营销软文、SEO 聚合页或二手摘要。
                6. 不要编造已经发生的调研结果，只设计采集动作。
                7. 输出要短，确保 JSON 完整闭合。

                用户课题：%s
                行业：%s
                竞品：%s
                分析维度：%s
                偏好来源：%s
                证据缺口：%s
                已有来源：%s
                """.formatted(
                run.getRequirement().getOriginalPrompt(),
                researchDomain(run),
                String.join("、", run.getRequirement().getCompetitors()),
                String.join("、", run.getRequirement().getDimensions()),
                String.join("、", run.getRequirement().getSourcePreferences()),
                String.join("、", run.getResearchPackage().getMissingEvidenceTypes()),
                compactEvidenceSources(run, 6)
        );
        String response = llmClient.complete(new ChatRequest(
                List.of(
                        ChatMessage.system("你是严谨的信息采集 Agent。只输出可解析 JSON，所有问题都要服务竞品分析。"),
                        ChatMessage.user(prompt)
                ),
                ChatOptions.researcher()
        ));
        return parseResearchPlanJson(response);
    }

    private Questionnaire generateQuestionnaireWithLlm(AnalysisRun run) throws Exception {
        String prompt = """
                你是竞品分析工作流中的用户调研 Agent。请为本次竞品分析单独生成问卷草案。
                输出必须是 JSON 对象，不要 Markdown，不要解释。

                JSON 结构：
                {
                  "title": "问卷标题",
                  "targetRespondents": "目标答题人",
                  "recommendedSampleSize": "建议样本量",
                  "questions": [
                    {"dimension": "维度", "question": "题目", "options": ["选项1", "选项2"]}
                  ]
                }

                约束：
                1. questions 生成 5-7 题，每题必须服务竞品比较。
                2. 至少覆盖使用场景、核心维度、满意度、购买顾虑、替换意愿。
                3. options 每题 3-6 个，避免开放题过多。
                4. 不要编造调研结果，只设计问题。

                用户课题：%s
                行业：%s
                竞品：%s
                分析维度：%s
                证据缺口：%s
                """.formatted(
                run.getRequirement().getOriginalPrompt(),
                researchDomain(run),
                String.join("、", run.getRequirement().getCompetitors()),
                String.join("、", run.getRequirement().getDimensions()),
                String.join("、", run.getResearchPackage().getMissingEvidenceTypes())
        );
        String response = llmClient.complete(new ChatRequest(
                List.of(
                        ChatMessage.system("你是严谨的用户调研问卷设计 Agent。只输出可解析 JSON。"),
                        ChatMessage.user(prompt)
                ),
                ChatOptions.researcher()
        ));
        return readNestedOrRoot(response, "questionnaire", Questionnaire.class);
    }

    private InterviewGuide generateInterviewGuideWithLlm(AnalysisRun run) throws Exception {
        String prompt = """
                你是竞品分析工作流中的访谈研究 Agent。请为本次竞品分析单独生成访谈提纲。
                输出必须是 JSON 对象，不要 Markdown，不要解释。

                JSON 结构：
                {
                  "title": "访谈提纲标题",
                  "targetRoles": ["目标角色"],
                  "questions": ["主问题"],
                  "probingQuestions": ["追问问题"]
                }

                约束：
                1. questions 生成 6-8 个，围绕真实使用、决策、痛点、竞品差异和替换阻力。
                2. probingQuestions 生成 3-5 个，用于追问证据、频率、影响和付费意愿。
                3. 不要编造访谈结论，只设计访谈问题。

                用户课题：%s
                行业：%s
                竞品：%s
                分析维度：%s
                证据缺口：%s
                """.formatted(
                run.getRequirement().getOriginalPrompt(),
                researchDomain(run),
                String.join("、", run.getRequirement().getCompetitors()),
                String.join("、", run.getRequirement().getDimensions()),
                String.join("、", run.getResearchPackage().getMissingEvidenceTypes())
        );
        String response = llmClient.complete(new ChatRequest(
                List.of(
                        ChatMessage.system("你是严谨的用户访谈研究 Agent。只输出可解析 JSON。"),
                        ChatMessage.user(prompt)
                ),
                ChatOptions.researcher()
        ));
        return readNestedOrRoot(response, "interviewGuide", InterviewGuide.class);
    }

    private <T> T readNestedOrRoot(String response, String fieldName, Class<T> type) throws Exception {
        var root = objectMapper.readTree(extractJsonObject(response));
        var target = root.has(fieldName) ? root.get(fieldName) : root;
        return objectMapper.treeToValue(target, type);
    }

    private <T> LlmSubtaskResult<T> runResearchPlanSubtask(String name, LlmSubtask<T> subtask) {
        try {
            return new LlmSubtaskResult<>(name, subtask.run(), null);
        } catch (Exception ex) {
            log.warn("Researcher LLM subtask failed: name={}, exceptionType={}, message={}",
                    name, ex.getClass().getName(), ex.getMessage());
            return new LlmSubtaskResult<>(name, null, ex.getMessage());
        }
    }

    private void recordParallelResearchTrace(List<LlmSubtaskResult<?>> results) {
        String summary = results.stream()
                .map(result -> "%s=%s%s".formatted(
                        result.name(),
                        result.succeeded() ? "succeeded" : "failed",
                        result.succeeded() ? "" : " (" + result.errorMessage() + ")"
                ))
                .collect(Collectors.joining("\n"));
        AgentTraceContext.recordModelResponse("Parallel Researcher LLM subtasks:\n" + summary, null, null);
    }

    private String compactEvidenceSources(AnalysisRun run, int limit) {
        return run.getEvidenceSources().stream()
                .limit(limit)
                .map(source -> "[%s] %s | type=%s | status=%s | %s".formatted(
                        source.getCitationKey(),
                        abbreviate(source.getTitle(), 80),
                        source.getSourceType(),
                        source.getCollectionStatus(),
                        abbreviate(source.getSnippet(), 160)
                ))
                .collect(Collectors.joining("\n"));
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength) + "...";
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
        // LLM 负责让调研计划更贴近题目；fallback 负责保证问卷、访谈、搜索任务这些
        // 演示必需字段永远可用。这里逐字段合并，防止模型漏字段导致前端空面板。
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

    private interface LlmSubtask<T> {
        T run() throws Exception;
    }

    private record LlmSubtaskResult<T>(String name, T value, String errorMessage) {
        boolean succeeded() {
            return value != null && errorMessage == null;
        }
    }
}
