package com.aiinsight.service;

import com.aiinsight.llm.ChatMessage;
import com.aiinsight.llm.ChatOptions;
import com.aiinsight.llm.ChatRequest;
import com.aiinsight.llm.LlmClient;
import com.aiinsight.model.enums.AgentName;
import com.aiinsight.model.enums.ReviewAction;
import com.aiinsight.model.review.ReviewRepairTask;
import com.aiinsight.model.run.AnalysisRequirement;
import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.model.run.EvidenceSource;
import com.aiinsight.observability.AgentTraceContext;
import com.aiinsight.util.JsonResponseExtractor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
// Query 规划只决定“搜什么”，不决定“相信什么”。后续 SourceCollectionService 仍会按
// robots、抓取质量和去重规则筛选证据，避免把 LLM 生成的搜索词误当成事实来源。
public class LlmSearchQueryPlanner {

    private static final int MAX_QUERIES_PER_COMPETITOR = 6;
    private static final int MAX_QUERY_LENGTH = 180;
    private static final Set<String> ALLOWED_EVIDENCE_TYPES = Set.of(
            "official_site",
            "docs",
            "pricing_page",
            "release_notes",
            "public_review",
            "security",
            "article"
    );

    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;

    public List<SearchQueryPlanner.SearchQueryBatch> plan(AnalysisRun run, boolean recollecting) {
        if (!llmClient.isAvailable() || run.getRequirement().getCompetitors().stream().noneMatch(StringUtils::hasText)) {
            return List.of();
        }
        try {
            String response = llmClient.complete(new ChatRequest(
                    List.of(
                            ChatMessage.system("你是竞品分析系统的信息采集 Query 规划 Agent。只输出可解析 JSON。"),
                            ChatMessage.user(prompt(run, recollecting))
                    ),
                    ChatOptions.searchQueryPlanner()
            ));
            List<SearchQueryPlanner.SearchQueryBatch> batches = parse(response, run);
            if (!batches.isEmpty()) {
                AgentTraceContext.recordProcessSummary("LLM search query batches:\n" + batches.stream()
                        .map(batch -> "%s: %s".formatted(batch.competitor(), String.join(" | ", batch.queries())))
                        .collect(Collectors.joining("\n")));
            }
            return batches;
        } catch (RuntimeException ex) {
            log.warn("LLM search query planning failed: runId={}, exceptionType={}, message={}",
                    run.getId(),
                    ex.getClass().getName(),
                    ex.getMessage());
            run.getRecommendedActions().add("LLM 搜索 Query 生成失败，已使用规则 Query 兜底：" + ex.getMessage());
            return List.of();
        }
    }

    private String prompt(AnalysisRun run, boolean recollecting) {
        AnalysisRequirement requirement = run.getRequirement();
        return """
                请为 Researcher 生成本轮真正用于搜索的 query batch。

                输出必须是 JSON 对象，不要 Markdown，不要解释：
                {
                  "batches": [
                    {
                      "competitor": "竞品原名",
                      "queries": [
                        {"query": "搜索 query", "evidenceType": "official_site|docs|pricing_page|release_notes|public_review|security|article", "purpose": "为何搜索", "priority": "HIGH|MEDIUM|LOW"}
                      ]
                    }
                  ]
                }

                约束：
                1. 每个 query 必须包含对应竞品名。
                2. 每个竞品最多 %d 条 query，优先 HIGH。
                3. 首轮优先官网、官方文档、定价页、更新日志、技术博客、权威资料。
                4. 首轮采集必须尽量覆盖每个竞品；复核重跑时优先围绕 repairTasks 和 requiredEvidenceTypes 补证据，不要泛泛重搜。
                5. query 要短而精准，不要超过 %d 个字符。
                6. 不要编造 URL，不要输出已经采集到的证据结论。

                是否复核补采：%s
                用户课题：%s
                行业：%s
                竞品：%s
                分析维度：%s
                来源偏好：%s
                Reviewer 补采要求：%s
                已有来源摘要：%s
                """.formatted(
                MAX_QUERIES_PER_COMPETITOR,
                MAX_QUERY_LENGTH,
                recollecting,
                requirement.getOriginalPrompt(),
                researchDomain(requirement),
                String.join("、", requirement.getCompetitors()),
                String.join("、", requirement.getDimensions()),
                String.join("、", requirement.getSourcePreferences()),
                repairContext(run, recollecting),
                evidenceContext(run)
        );
    }

    private List<SearchQueryPlanner.SearchQueryBatch> parse(String response, AnalysisRun run) {
        try {
            JsonNode root = objectMapper.readTree(extractJsonObject(response));
            JsonNode batchesNode = root.has("batches") ? root.get("batches") : root;
            List<QueryBatchDraft> drafts = objectMapper.convertValue(batchesNode, new TypeReference<>() {
            });
            // 只接受用户范围中已经确认的竞品名，避免模型额外扩展竞品导致下游矩阵口径漂移。
            Map<String, String> competitorByNormalizedName = run.getRequirement().getCompetitors().stream()
                    .filter(StringUtils::hasText)
                    .collect(Collectors.toMap(
                            this::normalize,
                            String::trim,
                            (first, ignored) -> first,
                            LinkedHashMap::new
                    ));
            Map<String, LinkedHashSet<String>> queriesByCompetitor = new LinkedHashMap<>();
            for (QueryBatchDraft draft : drafts == null ? List.<QueryBatchDraft>of() : drafts) {
                String competitor = competitorByNormalizedName.get(normalize(draft.competitor));
                if (!StringUtils.hasText(competitor)) {
                    continue;
                }
                List<String> queries = sanitizeQueries(competitor, draft.queries);
                if (!queries.isEmpty()) {
                    queriesByCompetitor
                            .computeIfAbsent(competitor, ignored -> new LinkedHashSet<>())
                            .addAll(queries);
                }
            }
            return queriesByCompetitor.entrySet().stream()
                    .map(entry -> new SearchQueryPlanner.SearchQueryBatch(
                            entry.getKey(),
                            entry.getValue().stream().limit(MAX_QUERIES_PER_COMPETITOR).toList()
                    ))
                    .toList();
        } catch (Exception ex) {
            throw new IllegalStateException("无法解析 LLM 搜索 Query JSON", ex);
        }
    }

    private List<String> sanitizeQueries(String competitor, List<QueryDraft> drafts) {
        return (drafts == null ? List.<QueryDraft>of() : drafts).stream()
                .filter(draft -> draft != null && StringUtils.hasText(draft.query))
                .filter(draft -> !StringUtils.hasText(draft.evidenceType)
                        || ALLOWED_EVIDENCE_TYPES.contains(normalize(draft.evidenceType)))
                // 搜索词必须带竞品名，避免“pricing”“reviews”这类泛词召回到无关产品。
                .map(draft -> ensureCompetitorInQuery(competitor, draft.query))
                .map(query -> query.replaceAll("\\s+", " ").trim())
                .filter(query -> query.length() <= MAX_QUERY_LENGTH)
                .distinct()
                .limit(MAX_QUERIES_PER_COMPETITOR)
                .toList();
    }

    private String ensureCompetitorInQuery(String competitor, String query) {
        if (containsIgnoreCase(query, competitor)) {
            return query;
        }
        return competitor + " " + query;
    }

    private String repairContext(AnalysisRun run, boolean recollecting) {
        if (!recollecting || run.getReviewDecision() == null
                || run.getReviewDecision().getAction() != ReviewAction.RECOLLECT_EVIDENCE
                || run.getReviewDecision().getTargetAgent() != AgentName.RESEARCHER) {
            return "无";
        }
        // 复核补采时把 ReviewDecision 的结构化 repairTasks 原样喂给 Query Planner，
        // 让新一轮搜索围绕缺口收敛，而不是重新做一次宽泛行业调研。
        String requiredTypes = String.join("、", run.getReviewDecision().getRequiredEvidenceTypes());
        String tasks = run.getReviewDecision().getRepairTasks().stream()
                .filter(task -> task.getTargetAgent() == AgentName.RESEARCHER)
                .limit(6)
                .map(this::repairTaskLine)
                .collect(Collectors.joining("\n"));
        String instructions = run.getReviewDecision().getRepairInstructions().stream()
                .limit(6)
                .collect(Collectors.joining("\n"));
        return """
                requiredEvidenceTypes=%s
                repairTasks:
                %s
                repairInstructions:
                %s
                """.formatted(requiredTypes, tasks.isBlank() ? "无" : tasks, instructions.isBlank() ? "无" : instructions);
    }

    private String repairTaskLine(ReviewRepairTask task) {
        return "- category=%s; claimId=%s; citation=%s; requiredTypes=%s; instruction=%s; acceptance=%s"
                .formatted(
                        text(task.getCategory()),
                        text(task.getClaimId()),
                        text(task.getCitationKey()),
                        String.join("/", task.getRequiredEvidenceTypes()),
                        text(task.getInstruction()),
                        text(task.getAcceptanceCriteria())
                );
    }

    private String evidenceContext(AnalysisRun run) {
        return run.getEvidenceSources().stream()
                .limit(10)
                .map(this::evidenceLine)
                .collect(Collectors.joining("\n"));
    }

    private String evidenceLine(EvidenceSource source) {
        return "[%s] %s | type=%s | quality=%s | %s"
                .formatted(
                        source.getCitationKey(),
                        abbreviate(source.getTitle(), 80),
                        source.getSourceType(),
                        source.getSourceQuality(),
                        abbreviate(source.getSnippet(), 160)
                );
    }

    private String researchDomain(AnalysisRequirement requirement) {
        if (StringUtils.hasText(requirement.getIndustry()) && !"待澄清行业".equals(requirement.getIndustry())) {
            return requirement.getIndustry();
        }
        return "目标领域";
    }

    private String extractJsonObject(String response) {
        return JsonResponseExtractor.extractJsonObject(response);
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
        return StringUtils.hasText(value) ? value : "无";
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private boolean containsIgnoreCase(String text, String pattern) {
        return text != null && pattern != null && text.toLowerCase(Locale.ROOT).contains(pattern.toLowerCase(Locale.ROOT));
    }

    private static class QueryBatchDraft {
        public String competitor;
        public List<QueryDraft> queries = List.of();
    }

    private static class QueryDraft {
        public String query;
        public String evidenceType;
        public String purpose;
        public String priority;
    }
}
