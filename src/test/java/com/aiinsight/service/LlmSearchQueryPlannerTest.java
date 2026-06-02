package com.aiinsight.service;

import com.aiinsight.llm.LlmClient;
import com.aiinsight.model.enums.AgentName;
import com.aiinsight.model.enums.ReviewAction;
import com.aiinsight.model.review.ReviewRepairTask;
import com.aiinsight.model.run.AnalysisRequirement;
import com.aiinsight.model.run.AnalysisRun;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LlmSearchQueryPlannerTest {

    @Test
    void usesLlmJsonAndSanitizesQueryBatches() {
        StringBuilder promptCapture = new StringBuilder();
        LlmClient llmClient = new LlmClient() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String complete(com.aiinsight.llm.ChatRequest request) {
                promptCapture.append(request.getMessages().get(1).getContent());
                return """
                        {
                          "batches": [
                            {
                              "competitor": "GitHub Copilot",
                              "queries": [
                                {
                                  "query": "model selection official documentation",
                                  "evidenceType": "docs",
                                  "purpose": "验证多模型支持",
                                  "priority": "HIGH"
                                },
                                {
                                  "query": "GitHub Copilot imaginary private benchmark",
                                  "evidenceType": "private_note",
                                  "purpose": "非法来源类型",
                                  "priority": "LOW"
                                }
                              ]
                            },
                            {
                              "competitor": "Unknown",
                              "queries": [
                                {"query": "Unknown docs", "evidenceType": "docs", "priority": "HIGH"}
                              ]
                            }
                          ]
                        }
                        """;
            }
        };
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "分析 AI 编程助手的模型策略。",
                "AI 编程助手",
                List.of("GitHub Copilot"),
                List.of("多模型支持"),
                List.of("official_site"),
                List.of()
        ));
        run.getReviewDecision().setAction(ReviewAction.RECOLLECT_EVIDENCE);
        run.getReviewDecision().setTargetAgent(AgentName.RESEARCHER);
        run.getReviewDecision().setRequiredEvidenceTypes(List.of("docs"));
        ReviewRepairTask repairTask = new ReviewRepairTask();
        repairTask.setTargetAgent(AgentName.RESEARCHER);
        repairTask.setCategory("claim_weak_support");
        repairTask.setExcerpt("Copilot 支持多模型切换");
        repairTask.setInstruction("补充 GitHub Copilot 多模型支持的官方文档。");
        repairTask.setExpectedFix("找到可引用的官方 docs 证明多模型支持。");
        repairTask.setRequiredEvidenceTypes(List.of("docs"));
        run.getReviewDecision().getRepairTasks().add(repairTask);

        var batches = new LlmSearchQueryPlanner(llmClient, new ObjectMapper()).plan(run, true);

        assertThat(promptCapture.toString()).contains("Reviewer 补采要求", "多模型支持", "官方 docs");
        assertThat(batches).hasSize(1);
        assertThat(batches.get(0).competitor()).isEqualTo("GitHub Copilot");
        assertThat(batches.get(0).queries())
                .containsExactly("GitHub Copilot model selection official documentation");
    }

    @Test
    void mergesDuplicateCompetitorBatchesAndSkipsLlmWhenNoCompetitor() {
        LlmClient llmClient = new LlmClient() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String complete(com.aiinsight.llm.ChatRequest request) {
                assertThat(request.getOptions().getMaxTokens()).isEqualTo(900);
                return """
                        {
                          "batches": [
                            {
                              "competitor": "Cursor",
                              "queries": [
                                {"query": "official docs", "evidenceType": "docs", "priority": "HIGH"}
                              ]
                            },
                            {
                              "competitor": "Cursor",
                              "queries": [
                                {"query": "official docs", "evidenceType": "docs", "priority": "HIGH"},
                                {"query": "pricing plans", "evidenceType": "pricing_page", "priority": "HIGH"}
                              ]
                            }
                          ]
                        }
                        """;
            }
        };
        LlmSearchQueryPlanner planner = new LlmSearchQueryPlanner(llmClient, new ObjectMapper());
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "分析 Cursor",
                "AI 编程助手",
                List.of("Cursor"),
                List.of("定价"),
                List.of("official_site"),
                List.of()
        ));

        var batches = planner.plan(run, false);

        assertThat(batches).hasSize(1);
        assertThat(batches.get(0).queries())
                .containsExactly("Cursor official docs", "Cursor pricing plans");

        AnalysisRun noCompetitorRun = new AnalysisRun(new AnalysisRequirement(
                "分析市场",
                "AI 编程助手",
                List.of(),
                List.of("定价"),
                List.of("official_site"),
                List.of()
        ));
        assertThat(planner.plan(noCompetitorRun, false)).isEmpty();
    }
}
