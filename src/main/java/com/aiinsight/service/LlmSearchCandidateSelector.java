package com.aiinsight.service;

import com.aiinsight.llm.ChatMessage;
import com.aiinsight.llm.ChatOptions;
import com.aiinsight.llm.ChatRequest;
import com.aiinsight.llm.LlmClient;
import com.aiinsight.model.enums.AgentName;
import com.aiinsight.model.review.ReviewDecision;
import com.aiinsight.model.review.ReviewRepairTask;
import com.aiinsight.model.run.AnalysisRequirement;
import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.model.run.EvidenceSource;
import com.aiinsight.service.SourceCollectionService.SearchCandidate;
import com.aiinsight.service.SourceCollectionService.SearchCandidateCollection;
import com.aiinsight.util.JsonResponseExtractor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class LlmSearchCandidateSelector {

    private static final int MAX_CANDIDATES_IN_PROMPT = 30;

    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;

    public Selection select(AnalysisRun run, SearchCandidateCollection candidateCollection) {
        if (candidateCollection == null || candidateCollection.candidates().isEmpty()) {
            return Selection.ruleFallback("no_search_candidates");
        }
        if (!llmClient.isAvailable()) {
            return Selection.ruleFallback("llm_unavailable");
        }
        try {
            String response = llmClient.complete(new ChatRequest(
                    List.of(
                            ChatMessage.system("You are a cautious research web-page selection agent. Output strict JSON only."),
                            ChatMessage.user(prompt(run, candidateCollection))
                    ),
                    ChatOptions.researcher()
            ));
            Selection selection = parse(response, candidateCollection);
            if (selection.selectedCandidateIds().isEmpty()) {
                return Selection.ruleFallback("llm_selected_no_valid_candidates");
            }
            return selection;
        } catch (RuntimeException ex) {
            log.warn("LLM search candidate selection failed: runId={}, exceptionType={}, message={}",
                    run.getId(),
                    ex.getClass().getName(),
                    ex.getMessage());
            run.getRecommendedActions().add("LLM search candidate selection failed; rule-ranked search candidates were used: " + ex.getMessage());
            return Selection.ruleFallback("llm_selection_failed");
        }
    }

    private String prompt(AnalysisRun run, SearchCandidateCollection candidateCollection) {
        AnalysisRequirement requirement = run.getRequirement();
        int maxSelectable = Math.max(1, candidateCollection.maxSelectable());
        return """
                Select which search-result pages the Researcher should fetch next.
                Output JSON only:
                {
                  "strategy": "short reason for the selection",
                  "selected": [
                    {"id": "C1", "reason": "why this page should be fetched"}
                  ]
                }

                Rules:
                1. Only choose ids from the candidate list. Do not invent URLs.
                2. Choose at most %d candidates.
                3. Prefer official docs, pricing, security/trust, changelog, release notes, primary vendor pages, and credible public reviews.
                4. Avoid duplicates of already observed evidence.
                5. Keep competitor coverage balanced unless reviewer repair context requires focus.
                6. If a candidate looks weak, irrelevant, anti-bot, unavailable, or SEO-only, do not select it.

                Topic: %s
                Industry: %s
                Competitors: %s
                Dimensions: %s
                Source preferences: %s
                Reviewer repair context:
                %s
                Existing evidence:
                %s

                Search candidates:
                %s
                """.formatted(
                maxSelectable,
                text(requirement == null ? null : requirement.getOriginalPrompt()),
                text(requirement == null ? null : requirement.getIndustry()),
                join(requirement == null ? null : requirement.getCompetitors()),
                join(requirement == null ? null : requirement.getDimensions()),
                join(requirement == null ? null : requirement.getSourcePreferences()),
                repairContext(run),
                evidenceContext(run),
                candidateContext(candidateCollection)
        );
    }

    private Selection parse(String response, SearchCandidateCollection candidateCollection) {
        try {
            JsonNode root = objectMapper.readTree(JsonResponseExtractor.extractJsonObject(response));
            String strategy = root.has("strategy") ? root.get("strategy").asText("") : "";
            JsonNode selectedNode = root.has("selected") ? root.get("selected") : root.get("selectedCandidateIds");
            Set<String> validIds = candidateCollection.candidates().stream()
                    .map(SearchCandidate::id)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            List<String> selectedIds = new ArrayList<>();
            List<String> reasons = new ArrayList<>();
            if (selectedNode != null && selectedNode.isArray()) {
                for (JsonNode node : selectedNode) {
                    String id = node.isTextual() ? node.asText("") : node.path("id").asText("");
                    if (!validIds.contains(id) || selectedIds.contains(id)) {
                        continue;
                    }
                    selectedIds.add(id);
                    String reason = node.isObject() ? node.path("reason").asText("") : "";
                    if (StringUtils.hasText(reason)) {
                        reasons.add(id + ": " + reason);
                    }
                    if (selectedIds.size() >= candidateCollection.maxSelectable()) {
                        break;
                    }
                }
            }
            return new Selection(selectedIds, reasons, strategy, "", true);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to parse LLM search candidate selection JSON", ex);
        }
    }

    private String evidenceContext(AnalysisRun run) {
        if (run.getEvidenceSources().isEmpty()) {
            return "- none";
        }
        return run.getEvidenceSources().stream()
                .limit(8)
                .map(this::evidenceLine)
                .collect(Collectors.joining("\n"));
    }

    private String repairContext(AnalysisRun run) {
        List<String> lines = new ArrayList<>();
        if (run.getResearchPackage() != null && !run.getResearchPackage().getMissingEvidenceTypes().isEmpty()) {
            lines.add("- missingEvidenceTypes=" + String.join(", ", run.getResearchPackage().getMissingEvidenceTypes()));
        }
        ReviewDecision decision = run.getReviewDecision();
        if (decision != null) {
            if (!decision.getRequiredEvidenceTypes().isEmpty()) {
                lines.add("- requiredEvidenceTypes=" + String.join(", ", decision.getRequiredEvidenceTypes()));
            }
            if (StringUtils.hasText(decision.getRepairScopeSummary())) {
                lines.add("- repairScope=" + abbreviate(decision.getRepairScopeSummary(), 180));
            }
            decision.getRepairInstructions().stream()
                    .filter(StringUtils::hasText)
                    .limit(4)
                    .map(instruction -> "- instruction=" + abbreviate(instruction, 180))
                    .forEach(lines::add);
            decision.getRepairTasks().stream()
                    .filter(task -> task.getTargetAgent() == null || task.getTargetAgent() == AgentName.RESEARCHER)
                    .limit(5)
                    .map(this::repairTaskLine)
                    .forEach(lines::add);
        }
        return lines.isEmpty() ? "- none" : String.join("\n", lines);
    }

    private String repairTaskLine(ReviewRepairTask task) {
        return "- task category=%s; claimId=%s; citation=%s; requiredTypes=%s; instruction=%s; acceptance=%s".formatted(
                text(task.getCategory()),
                text(task.getClaimId()),
                text(task.getCitationKey()),
                task.getRequiredEvidenceTypes().isEmpty() ? "unspecified" : String.join("/", task.getRequiredEvidenceTypes()),
                abbreviate(task.getInstruction(), 160),
                abbreviate(task.getAcceptanceCriteria(), 160)
        );
    }

    private String evidenceLine(EvidenceSource source) {
        return "- [%s] %s | type=%s | quality=%s | %s".formatted(
                source.getCitationKey(),
                abbreviate(source.getTitle(), 80),
                source.getSourceType(),
                source.getSourceQuality(),
                abbreviate(source.getSnippet(), 140)
        );
    }

    private String candidateContext(SearchCandidateCollection candidateCollection) {
        return candidateCollection.candidates().stream()
                .limit(MAX_CANDIDATES_IN_PROMPT)
                .map(candidate -> "- %s | competitor=%s | type=%s | rulePriority=%d | sourceBudget=%d | rank=%d | title=%s | url=%s | query=%s | snippet=%s".formatted(
                        candidate.id(),
                        text(candidate.competitor()),
                        text(candidate.sourceType()),
                        candidate.rulePriority(),
                        candidate.sourceBudget(),
                        candidate.rank(),
                        abbreviate(candidate.title(), 90),
                        candidate.url(),
                        abbreviate(candidate.query(), 100),
                        abbreviate(candidate.snippet(), 160)
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

    private String text(String value) {
        return StringUtils.hasText(value) ? value : "unspecified";
    }

    private String join(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "unspecified";
        }
        return values.stream()
                .filter(StringUtils::hasText)
                .collect(Collectors.joining(", "));
    }

    public record Selection(List<String> selectedCandidateIds,
                            List<String> reasons,
                            String strategy,
                            String fallbackReason,
                            boolean llmUsed) {

        public Selection {
            selectedCandidateIds = selectedCandidateIds == null ? List.of() : List.copyOf(selectedCandidateIds);
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
            strategy = strategy == null ? "" : strategy.trim();
            fallbackReason = fallbackReason == null ? "" : fallbackReason.toLowerCase(Locale.ROOT);
        }

        public static Selection ruleFallback(String reason) {
            return new Selection(List.of(), List.of(), "", reason, false);
        }
    }
}
