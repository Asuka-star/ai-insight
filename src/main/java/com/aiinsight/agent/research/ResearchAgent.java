package com.aiinsight.agent.research;

import com.aiinsight.model.enums.AgentName;
import com.aiinsight.model.enums.ReviewAction;
import com.aiinsight.model.review.ReviewDecision;
import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.model.run.EvidenceChunk;
import com.aiinsight.model.run.EvidenceSource;
import com.aiinsight.service.EvidenceChunkService;
import com.aiinsight.service.EvidenceEmbeddingService;
import com.aiinsight.service.EvidenceSourceLifecycleService;
import com.aiinsight.service.EvidenceSourceLifecycleService.EvidenceReplacementResult;
import com.aiinsight.service.LlmSearchCandidateSelector;
import com.aiinsight.service.LlmSearchQueryPlanner;
import com.aiinsight.service.SearchQueryPlanner;
import com.aiinsight.service.SourceCollectionService;
import com.aiinsight.service.SourceCollectionService.SearchCandidateCollection;
import com.aiinsight.repository.AnalysisRunRepository;
import com.aiinsight.util.AgentUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import static com.aiinsight.util.AgentUtils.containsIgnoreCase;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class ResearchAgent {

    private static final int MAX_GLOBAL_RAG_CHUNKS_FOR_RESEARCHER = 80;

    private final SourceCollectionService sourceCollectionService;
    private final EvidenceChunkService evidenceChunkService;
    private final EvidenceEmbeddingService evidenceEmbeddingService;
    private final LlmSearchQueryPlanner llmSearchQueryPlanner;
    private final LlmSearchCandidateSelector llmSearchCandidateSelector;
    private final EvidenceSourceLifecycleService evidenceSourceLifecycleService;
    private AnalysisRunRepository repository;

    @Autowired(required = false)
    public void setRepository(AnalysisRunRepository repository) {
        this.repository = repository;
    }

    public ResearchAgentResult run(AnalysisRun run) {
        boolean recollecting = isRecollectionMode(run);
        List<EvidenceSource> beforeSources = new ArrayList<>(run.getEvidenceSources());
        // 全局资源要在用户指定 URL、手动证据和公开搜索之前挂载，
        // 这样 query planner/候选选择器都能把用户资源当作已知背景，而不是盲搜。
        attachGlobalEvidence(run);
        // First observe user-controlled inputs. The LLM query planner sees these fetched sources
        // before deciding what to search next, so search becomes evidence-aware instead of blind.
        List<EvidenceSource> userObservedSources = sourceCollectionService.collectUserDirectedSources(run);
        replaceRunSources(run, userObservedSources);
        ResearchAgentPlan plan = plan(run, recollecting);
        SearchCandidateCollection searchCandidates = sourceCollectionService.searchCandidates(
                run,
                recollecting,
                plan.searchQueryBatches()
        );
        plan = withEffectiveSearchAction(plan, searchCandidates, recollecting);
        LlmSearchCandidateSelector.Selection candidateSelection = llmSearchCandidateSelector.select(run, searchCandidates);
        List<EvidenceSource> collectedSources = collectWithCandidateSelection(
                run,
                recollecting,
                plan,
                searchCandidates,
                candidateSelection
        );
        EvidenceReplacementResult replacementResult = evidenceSourceLifecycleService.reconcileAfterCollection(
                run,
                beforeSources,
                collectedSources
        );
        if (replacementResult.replacedBindings() > 0 || replacementResult.prunedBindings() > 0
                || replacementResult.prunedSources() > 0) {
            run.getRecommendedActions().add(
                    "已执行证据池整理：替换绑定=%d，移除弱绑定=%d，裁剪历史未引用来源=%d。被替换且需要审计的旧来源会保留，但不再支撑对应结论。"
                            .formatted(
                                    replacementResult.replacedBindings(),
                                    replacementResult.prunedBindings(),
                                    replacementResult.prunedSources()));
        }
        replaceRunSources(run, collectedSources);
        List<EvidenceChunk> chunks = evidenceEmbeddingService.embedChunks(evidenceChunkService.chunk(collectedSources));
        List<String> missingEvidenceTypes = missingEvidenceTypes(run, collectedSources);
        List<ResearchObservation> observations = observe(
                run,
                beforeSources,
                userObservedSources,
                searchCandidates,
                candidateSelection,
                collectedSources,
                chunks,
                missingEvidenceTypes,
                plan
        );
        ResearchAgentDecision decision = decide(run, recollecting, missingEvidenceTypes, collectedSources);
        String traceMarkdown = traceMarkdown(plan, observations, decision);
        return new ResearchAgentResult(
                plan,
                collectedSources,
                chunks,
                missingEvidenceTypes,
                observations,
                decision,
                traceMarkdown
        );
    }

    private ResearchAgentPlan plan(AnalysisRun run, boolean recollecting) {
        List<SearchQueryPlanner.SearchQueryBatch> searchQueryBatches = llmSearchQueryPlanner.plan(run, recollecting);
        List<ResearchAction> actions = new ArrayList<>();
        if (run.getRequirement() != null && run.getRequirement().getSourceUrls() != null
                && !run.getRequirement().getSourceUrls().isEmpty()) {
            actions.add(new ResearchAction(
                    "WebFetchTool",
                    "Fetch user-provided public URLs and promote usable pages to citation sources.",
                    "sourceUrls",
                    new ArrayList<>(run.getRequirement().getSourceUrls())
            ));
        }
        if (run.getUserProvidedEvidence() != null && !run.getUserProvidedEvidence().isEmpty()) {
            actions.add(new ResearchAction(
                    "UserEvidenceTool",
                    "Promote user-provided notes, surveys, interviews, and uploaded resources into evidence.",
                    "userProvidedEvidence",
                    run.getUserProvidedEvidence().stream()
                            .map(evidence -> AgentUtils.textOrDefault(evidence.getTitle(), evidence.getSourceType()))
                            .toList()
            ));
        }
        if (!searchQueryBatches.isEmpty()) {
            actions.add(new ResearchAction(
                    "SearchTool",
                    recollecting
                            ? "Run targeted public search for Reviewer repair tasks."
                            : "Run public search for competitor and dimension coverage.",
                    "publicWeb",
                    searchQueryBatches.stream()
                            .flatMap(batch -> batch.queries().stream())
                            .toList()
            ));
        }
        actions.add(new ResearchAction(
                "EvidenceChunkTool",
                "Chunk all promoted evidence sources and attach embeddings when configured.",
                "evidenceSources",
                List.of("chunk", "embed")
        ));
        return new ResearchAgentPlan(objective(run, recollecting), recollecting, searchQueryBatches, actions);
    }

    private ResearchAgentPlan withEffectiveSearchAction(ResearchAgentPlan plan,
                                                        SearchCandidateCollection searchCandidates,
                                                        boolean recollecting) {
        if (searchCandidates == null || searchCandidates.queries().isEmpty() || hasAction(plan, "SearchTool")) {
            return plan;
        }
        List<ResearchAction> actions = new ArrayList<>(plan.actions());
        actions.add(Math.max(0, actions.size() - 1), new ResearchAction(
                "SearchTool",
                recollecting
                        ? "Run targeted public search from rule fallback for Reviewer repair tasks."
                        : "Run public search from rule fallback for competitor and dimension coverage.",
                "publicWeb",
                searchCandidates.queries()
        ));
        return new ResearchAgentPlan(
                plan.objective(),
                plan.recollectionMode(),
                searchCandidates.batches(),
                actions
        );
    }

    private boolean hasAction(ResearchAgentPlan plan, String toolName) {
        return plan.actions().stream().anyMatch(action -> toolName.equals(action.toolName()));
    }

    private void attachGlobalEvidence(AnalysisRun run) {
        if (repository == null) {
            return;
        }
        // Researcher 主流程需要看到全局用户资源；这里先把全局 chunk 汇总成当前 run 的
        // EvidenceSource，后续 collect/replaceRunSources 会统一切片并生成本 run 的 S 编号。
        List<EvidenceChunk> chunks = repository.findGlobalEvidenceChunks(MAX_GLOBAL_RAG_CHUNKS_FOR_RESEARCHER);
        if (chunks.isEmpty()) {
            return;
        }
        Set<String> existingUrls = run.getEvidenceSources().stream()
                .map(EvidenceSource::getUrl)
                .filter(StringUtils::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> existingTextHashes = run.getEvidenceChunks().stream()
                .map(EvidenceChunk::getTextHash)
                .filter(StringUtils::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, List<EvidenceChunk>> chunksByUrl = chunks.stream()
                .filter(this::isGlobalChunk)
                .filter(chunk -> !isUrlExcluded(chunk.getUrl(), run))
                // 同一份文档可能已在当前 run 中作为局部资源存在；用 textHash 避免重复挂载。
                .filter(chunk -> !StringUtils.hasText(chunk.getTextHash()) || !existingTextHashes.contains(chunk.getTextHash()))
                .collect(Collectors.groupingBy(
                        EvidenceChunk::getUrl,
                        LinkedHashMap::new,
                        Collectors.toList()
                ));
        int next = maxCitationNumber(run.getEvidenceSources()) + 1;
        for (Map.Entry<String, List<EvidenceChunk>> entry : chunksByUrl.entrySet()) {
            if (!existingUrls.add(entry.getKey())) {
                continue;
            }
            EvidenceSource source = globalSource("S" + next, entry.getKey(), entry.getValue());
            run.getEvidenceSources().add(source);
            next++;
        }
        if (!chunksByUrl.isEmpty()) {
            run.getResearchPackage().setSources(new ArrayList<>(run.getEvidenceSources()));
        }
    }

    private boolean isUrlExcluded(String url, AnalysisRun run) {
        if (run == null || !StringUtils.hasText(url) || run.getExcludedSourceUrls() == null) {
            return false;
        }
        String normalized = normalizeUrl(url);
        return run.getExcludedSourceUrls().stream()
                .filter(StringUtils::hasText)
                .map(this::normalizeUrl)
                .anyMatch(normalized::equals);
    }

    private String normalizeUrl(String url) {
        if (!StringUtils.hasText(url)) {
            return "";
        }
        String normalized = url.trim().toLowerCase();
        return normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
    }

    private EvidenceSource globalSource(String citationKey, String url, List<EvidenceChunk> chunks) {
        EvidenceChunk first = chunks.get(0);
        String rawText = chunks.stream()
                .map(EvidenceChunk::getText)
                .filter(StringUtils::hasText)
                .collect(Collectors.joining("\n\n"));
        EvidenceSource source = new EvidenceSource(
                citationKey,
                StringUtils.hasText(first.getTitle()) ? first.getTitle() : "全局用户资源",
                url,
                StringUtils.hasText(first.getSourceType()) ? first.getSourceType() : "global_user_document",
                "USER_PROVIDED",
                "USER_PROVIDED",
                StringUtils.hasText(first.getSourceQuality()) ? first.getSourceQuality() : "USER_PROVIDED",
                "NONE",
                abbreviate(rawText, 220),
                rawText,
                "来自用户资源包/用户上传文档，已挂载到当前分析任务用于证据引用；不可描述为公开采集来源。"
        );
        source.setSourceAuthority(StringUtils.hasText(first.getSourceAuthority()) ? first.getSourceAuthority() : "USER_PROVIDED");
        source.setCanonicalHost("global-document");
        source.setPublisherName("用户资源包");
        source.setGlobalResource(true);
        return source;
    }

    private boolean isGlobalChunk(EvidenceChunk chunk) {
        return chunk != null && chunk.getUrl() != null && chunk.getUrl().startsWith("global-document://");
    }

    private int maxCitationNumber(List<EvidenceSource> sources) {
        return sources.stream()
                .map(EvidenceSource::getCitationKey)
                .filter(StringUtils::hasText)
                .mapToInt(this::citationNumber)
                .max()
                .orElse(0);
    }

    private int citationNumber(String citationKey) {
        if (citationKey == null || !citationKey.startsWith("S")) {
            return 0;
        }
        try {
            return Integer.parseInt(citationKey.substring(1));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private String abbreviate(String value, int maxLength) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxLength - 3)).trim() + "...";
    }

    private List<EvidenceSource> collectWithCandidateSelection(AnalysisRun run,
                                                               boolean recollecting,
                                                               ResearchAgentPlan plan,
                                                               SearchCandidateCollection searchCandidates,
                                                               LlmSearchCandidateSelector.Selection candidateSelection) {
        if (searchCandidates == null || !searchCandidates.searchAvailable() || searchCandidates.candidates().isEmpty()) {
            return sourceCollectionService.collect(run, recollecting, plan.searchQueryBatches());
        }
        return sourceCollectionService.collectSelectedSearchCandidates(
                run,
                recollecting,
                searchCandidates,
                candidateSelection.selectedCandidateIds()
        );
    }

    private List<ResearchObservation> observe(AnalysisRun run,
                                              List<EvidenceSource> beforeSources,
                                              List<EvidenceSource> userObservedSources,
                                              SearchCandidateCollection searchCandidates,
                                              LlmSearchCandidateSelector.Selection candidateSelection,
                                              List<EvidenceSource> collectedSources,
                                              List<EvidenceChunk> chunks,
                                              List<String> missingEvidenceTypes,
                                              ResearchAgentPlan plan) {
        Set<String> beforeKeys = beforeSources.stream()
                .map(EvidenceSource::getCitationKey)
                .filter(StringUtils::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<EvidenceSource> newSources = collectedSources.stream()
                .filter(source -> !beforeKeys.contains(source.getCitationKey()))
                .toList();
        Set<String> userObservedKeys = userObservedSources.stream()
                .map(EvidenceSource::getCitationKey)
                .filter(StringUtils::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<EvidenceSource> userNewSources = userObservedSources.stream()
                .filter(source -> !beforeKeys.contains(source.getCitationKey()))
                .toList();
        List<EvidenceSource> searchNewSources = collectedSources.stream()
                .filter(source -> !userObservedKeys.contains(source.getCitationKey()))
                .toList();
        List<ResearchObservation> observations = new ArrayList<>();
        observations.add(new ResearchObservation(
                "UserInputObservation",
                "Observed %d user-directed sources before planning search; %d were new.".formatted(
                        userObservedSources.size(),
                        userNewSources.size()
                ),
                userObservedSources.size(),
                userObservationNotes(run, userObservedSources)
        ));
        observations.add(new ResearchObservation(
                "Plan",
                "Prepared %d actions and %d search queries.".formatted(
                        plan.actions().size(),
                        plan.searchQueryBatches().stream().mapToInt(batch -> batch.queries().size()).sum()
                ),
                plan.actions().size(),
                actionNotes(plan.actions())
        ));
        observations.add(new ResearchObservation(
                "SearchCandidateSelector",
                candidateSelectionSummary(searchCandidates, candidateSelection),
                candidateSelection.selectedCandidateIds().size(),
                candidateSelectionNotes(searchCandidates, candidateSelection)
        ));
        observations.add(new ResearchObservation(
                "SourceCollectionService",
                "Promoted %d citation sources; %d were new in this run and %d came from search after user-source observation."
                        .formatted(collectedSources.size(), newSources.size(), searchNewSources.size()),
                collectedSources.size(),
                sourceNotes(collectedSources)
        ));
        observations.add(new ResearchObservation(
                "EvidenceChunkService",
                "Created %d retrievable evidence chunks.".formatted(chunks.size()),
                chunks.size(),
                chunks.stream()
                        .limit(6)
                        .map(chunk -> "%s <- %s".formatted(chunk.getChunkKey(), chunk.getSourceCitationKey()))
                        .toList()
        ));
        if (!missingEvidenceTypes.isEmpty()) {
            observations.add(new ResearchObservation(
                    "GapEvaluator",
                    "Remaining evidence gaps: " + String.join("、", missingEvidenceTypes),
                    missingEvidenceTypes.size(),
                    missingEvidenceTypes
            ));
        }
        if (!run.getRecommendedActions().isEmpty()) {
            observations.add(new ResearchObservation(
                    "RecommendedActions",
                    "Workflow already has operator-facing follow-up suggestions.",
                    run.getRecommendedActions().size(),
                    run.getRecommendedActions().stream().skip(Math.max(0, run.getRecommendedActions().size() - 6)).toList()
            ));
        }
        return observations;
    }

    private void replaceRunSources(AnalysisRun run, List<EvidenceSource> sources) {
        run.getEvidenceSources().clear();
        run.getEvidenceSources().addAll(sources);
        run.getResearchPackage().setSources(new ArrayList<>(sources));
    }

    private ResearchAgentDecision decide(AnalysisRun run,
                                         boolean recollecting,
                                         List<String> missingEvidenceTypes,
                                         List<EvidenceSource> collectedSources) {
        if (collectedSources.isEmpty()) {
            return new ResearchAgentDecision(
                    "ASK_USER",
                    "No usable source was collected; downstream agents need user-provided URLs, documents, interviews, or surveys.",
                    List.of("public_web")
            );
        }
        if (!missingEvidenceTypes.isEmpty()) {
            String action = recollecting ? "ASK_USER_OR_CONTINUE_WITH_LIMITATIONS" : "CONTINUE_WITH_GAPS";
            return new ResearchAgentDecision(
                    action,
                    "Evidence collection finished but some evidence types still require public sources or user-provided material.",
                    missingEvidenceTypes
            );
        }
        return new ResearchAgentDecision(
                "STOP_AND_HAND_OFF",
                "Enough citation-bearing evidence is available for Extractor to build structured profiles.",
                List.of()
        );
    }

    private String traceMarkdown(ResearchAgentPlan plan,
                                 List<ResearchObservation> observations,
                                 ResearchAgentDecision decision) {
        String actions = plan.actions().stream()
                .map(action -> "- %s: %s target=%s inputs=%d".formatted(
                        action.toolName(),
                        action.intent(),
                        action.target(),
                        action.inputs() == null ? 0 : action.inputs().size()
                ))
                .collect(Collectors.joining("\n"));
        String observationText = observations.stream()
                .map(observation -> """
                        ### %s
                        %s
                        Produced: %d
                        Notes:
                        %s
                        """.formatted(
                        observation.toolName(),
                        observation.summary(),
                        observation.producedCount(),
                        bulletList(observation.notes())
                ))
                .collect(Collectors.joining("\n"));
        return """
                ## Research Agent Plan

                Objective: %s
                Recollection mode: %s

                ## Actions

                %s

                ## Observations

                %s

                ## Decision

                Action: %s
                Reason: %s
                Unresolved evidence types: %s
                """.formatted(
                plan.objective(),
                plan.recollectionMode(),
                actions.isBlank() ? "- No action planned." : actions,
                observationText.isBlank() ? "- No observations recorded." : observationText,
                decision.action(),
                decision.reason(),
                decision.unresolvedEvidenceTypes().isEmpty()
                        ? "none"
                        : String.join("、", decision.unresolvedEvidenceTypes())
        );
    }

    private List<String> missingEvidenceTypes(AnalysisRun run, List<EvidenceSource> collectedSources) {
        Set<String> missing = new LinkedHashSet<>();
        List<String> competitors = competitors(run);
        if (!hasEvidenceTypeForAllRequiredCompetitors(collectedSources, competitors, "pricing")) {
            missing.add("pricing_page");
        }
        if (!hasEvidenceTypeForAllRequiredCompetitors(collectedSources, competitors, "feedback")
                && !hasEvidenceTypeForAllRequiredCompetitors(collectedSources, competitors, "review")) {
            missing.add("user_review");
        }
        if (needsSurveyResearch(run) && !hasEvidenceType(collectedSources, "survey")) {
            missing.add("survey_result");
        }
        if (needsInterviewResearch(run) && !hasEvidenceType(collectedSources, "interview")) {
            missing.add("interview_note");
        }
        return new ArrayList<>(missing);
    }

    private boolean needsSurveyResearch(AnalysisRun run) {
        if (run.getRequirement() == null) {
            return false;
        }
        return mentionsAny(run.getRequirement().getDimensions(), "调研", "问卷", "survey")
                || mentionsAny(run.getRequirement().getSourcePreferences(), "survey", "问卷", "调研");
    }

    private boolean needsInterviewResearch(AnalysisRun run) {
        if (run.getRequirement() == null) {
            return false;
        }
        return mentionsAny(run.getRequirement().getDimensions(), "访谈", "interview")
                || mentionsAny(run.getRequirement().getSourcePreferences(), "interview", "访谈");
    }

    private List<String> competitors(AnalysisRun run) {
        if (run.getRequirement() == null || run.getRequirement().getCompetitors() == null) {
            return List.of();
        }
        return run.getRequirement().getCompetitors().stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .toList();
    }

    private boolean hasEvidenceTypeForAllRequiredCompetitors(List<EvidenceSource> sources,
                                                             List<String> competitors,
                                                             String keyword) {
        if (competitors == null || competitors.isEmpty()) {
            return hasEvidenceType(sources, keyword);
        }
        if (competitors.size() == 1) {
            return hasEvidenceType(sources, keyword);
        }
        return competitors.stream()
                .allMatch(competitor -> hasEvidenceTypeForCompetitor(sources, keyword, competitor));
    }

    private boolean hasEvidenceTypeForCompetitor(List<EvidenceSource> sources, String keyword, String competitor) {
        return sources.stream().anyMatch(source ->
                hasEvidenceType(source, keyword) && evidenceMentionsCompetitor(source, competitor));
    }

    private boolean hasEvidenceType(List<EvidenceSource> sources, String keyword) {
        return sources.stream().anyMatch(source -> hasEvidenceType(source, keyword));
    }

    private boolean hasEvidenceType(EvidenceSource source, String keyword) {
        return containsIgnoreCase(source.getSourceType(), keyword)
                || containsIgnoreCase(source.getTitle(), keyword)
                || containsIgnoreCase(source.getUrl(), keyword)
                || containsIgnoreCase(source.getSnippet(), keyword)
                || containsIgnoreCase(source.getComplianceNote(), keyword);
    }

    private boolean evidenceMentionsCompetitor(EvidenceSource source, String competitor) {
        if (!StringUtils.hasText(competitor)) {
            return true;
        }
        return containsIgnoreCase(source.getTitle(), competitor)
                || containsIgnoreCase(source.getUrl(), competitor)
                || containsIgnoreCase(source.getSnippet(), competitor)
                || containsIgnoreCase(source.getRawText(), competitor);
    }

    private boolean mentionsAny(List<String> values, String... patterns) {
        if (values == null || values.isEmpty()) {
            return false;
        }
        return values.stream().anyMatch(value -> {
            for (String pattern : patterns) {
                if (containsIgnoreCase(value, pattern)) {
                    return true;
                }
            }
            return false;
        });
    }

    private boolean isRecollectionMode(AnalysisRun run) {
        ReviewDecision decision = run.getRepairDecisionFor(AgentName.RESEARCHER);
        return decision != null
                && decision.getAction() == ReviewAction.RECOLLECT_EVIDENCE
                && decision.getTargetAgent() == AgentName.RESEARCHER;
    }

    private String objective(AnalysisRun run, boolean recollecting) {
        String competitors = run.getRequirement() == null
                ? "unspecified competitors"
                : run.getRequirement().getCompetitors().stream().filter(StringUtils::hasText).collect(Collectors.joining("、"));
        String dimensions = run.getRequirement() == null
                ? "unspecified dimensions"
                : run.getRequirement().getDimensions().stream().filter(StringUtils::hasText).collect(Collectors.joining("、"));
        ReviewDecision decision = run.getRepairDecisionFor(AgentName.RESEARCHER);
        if (recollecting && decision != null && !decision.getRequiredEvidenceTypes().isEmpty()) {
            return "补采 Reviewer 要求的 %s 证据，覆盖竞品：%s".formatted(
                    String.join("、", decision.getRequiredEvidenceTypes()),
                    competitors
            );
        }
        return "采集 %s 在 %s 维度上的可引用证据".formatted(
                competitors.isBlank() ? "目标竞品" : competitors,
                dimensions.isBlank() ? "核心分析" : dimensions
        );
    }

    private List<String> actionNotes(List<ResearchAction> actions) {
        return actions.stream()
                .map(action -> "%s -> %s".formatted(action.toolName(), action.intent()))
                .toList();
    }

    private List<String> sourceNotes(List<EvidenceSource> sources) {
        return sources.stream()
                .limit(8)
                .map(source -> "[%s] %s | type=%s | authority=%s | quality=%s | status=%s".formatted(
                        source.getCitationKey(),
                        AgentUtils.abbreviate(source.getTitle(), 80),
                        source.getSourceType(),
                        source.getSourceAuthority(),
                        source.getSourceQuality(),
                        source.getCollectionStatus()
                ))
                .toList();
    }

    private List<String> userObservationNotes(AnalysisRun run, List<EvidenceSource> userObservedSources) {
        List<String> notes = new ArrayList<>();
        int sourceUrlCount = run.getRequirement() == null || run.getRequirement().getSourceUrls() == null
                ? 0
                : run.getRequirement().getSourceUrls().size();
        int userEvidenceCount = run.getUserProvidedEvidence() == null ? 0 : run.getUserProvidedEvidence().size();
        notes.add("Input sourceUrls=%d, userProvidedEvidence=%d".formatted(sourceUrlCount, userEvidenceCount));
        notes.addAll(sourceNotes(userObservedSources));
        if (userObservedSources.isEmpty()) {
            notes.add("No user-directed evidence was available; search planning used requirement scope only.");
        }
        return notes;
    }

    private String candidateSelectionSummary(SearchCandidateCollection searchCandidates,
                                             LlmSearchCandidateSelector.Selection candidateSelection) {
        int candidateCount = searchCandidates == null ? 0 : searchCandidates.candidates().size();
        if (searchCandidates == null || !searchCandidates.searchAvailable()) {
            return "Search provider was unavailable; source collection used the original rule fallback path.";
        }
        if (candidateCount == 0) {
            return "Search produced no selectable candidates; source collection used the original rule fallback path.";
        }
        if (candidateSelection.llmUsed()) {
            return "LLM selected %d of %d search candidates for fetching; rule-ranked candidates remain available for fill-in."
                    .formatted(candidateSelection.selectedCandidateIds().size(), candidateCount);
        }
        return "Candidate selector used rule fallback (%s) over %d search candidates."
                .formatted(candidateSelection.fallbackReason(), candidateCount);
    }

    private List<String> candidateSelectionNotes(SearchCandidateCollection searchCandidates,
                                                 LlmSearchCandidateSelector.Selection candidateSelection) {
        List<String> notes = new ArrayList<>();
        if (searchCandidates == null) {
            notes.add("No search candidate collection was produced.");
            return notes;
        }
        notes.add("queries=%d, candidates=%d, maxSelectable=%d, searchAvailable=%s".formatted(
                searchCandidates.queries().size(),
                searchCandidates.candidates().size(),
                searchCandidates.maxSelectable(),
                searchCandidates.searchAvailable()
        ));
        if (candidateSelection.llmUsed() && StringUtils.hasText(candidateSelection.strategy())) {
            notes.add("strategy=" + candidateSelection.strategy());
        }
        if (!candidateSelection.llmUsed() && StringUtils.hasText(candidateSelection.fallbackReason())) {
            notes.add("fallbackReason=" + candidateSelection.fallbackReason());
        }
        if (!candidateSelection.selectedCandidateIds().isEmpty()) {
            notes.add("selectedIds=" + String.join(", ", candidateSelection.selectedCandidateIds()));
        }
        notes.addAll(candidateSelection.reasons());
        searchCandidates.candidates().stream()
                .limit(6)
                .map(candidate -> "%s %s | type=%s | rulePriority=%d | query=%s".formatted(
                        candidate.id(),
                        AgentUtils.abbreviate(candidate.url(), 90),
                        candidate.sourceType(),
                        candidate.rulePriority(),
                        AgentUtils.abbreviate(candidate.query(), 80)
                ))
                .forEach(notes::add);
        return notes;
    }

    private String bulletList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "- none";
        }
        return values.stream()
                .filter(StringUtils::hasText)
                .limit(10)
                .map(value -> "- " + value)
                .collect(Collectors.joining("\n"));
    }
}
