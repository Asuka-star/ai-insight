package com.aiinsight.agent.node;

import com.aiinsight.llm.ChatRequest;
import com.aiinsight.llm.LlmClient;
import com.aiinsight.model.run.AnalysisRequirement;
import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.service.fallback.FallbackClarificationDraftFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ClarifierNodeTest {

    @Test
    void clarifierNodeOwnsLlmCallAndMergesResult() {
        AtomicInteger calls = new AtomicInteger();
        ClarifierNode node = new ClarifierNode(new LlmClient() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String complete(ChatRequest request) {
                calls.incrementAndGet();
                return """
                        {
                          "industry": "企业服务 CRM",
                          "competitors": ["Salesforce", "HubSpot"],
                          "dimensions": ["销售自动化", "权限治理"],
                          "sourcePreferences": ["official_site", "pricing_page"],
                          "sourceUrls": [],
                          "outputGoal": "支持销售产品规划",
                          "clarificationQuestions": ["是否需要加入 Zoho CRM？"]
                        }
                        """;
            }
        }, new ObjectMapper(), new FallbackClarificationDraftFactory());
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "分析 CRM 里的销售自动化机会",
                "待澄清行业",
                List.of("竞品 A", "竞品 B"),
                List.of(),
                List.of(),
                List.of(),
                null
        ));

        node.execute(run);

        assertThat(calls).hasValue(1);
        assertThat(run.getRequirement().getIndustry()).isEqualTo("企业服务 CRM");
        assertThat(run.getRequirement().getCompetitors()).containsExactly("Salesforce", "HubSpot");
        assertThat(run.getClarificationDraft().getClarificationQuestions()).contains("是否需要加入 Zoho CRM？");
    }

    @Test
    void clarifierNodeFallsBackThroughFallbackFactoryWhenLlmFails() {
        ClarifierNode node = new ClarifierNode(new LlmClient() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String complete(ChatRequest request) {
                throw new IllegalStateException("simulated clarifier timeout");
            }
        }, new ObjectMapper(), new FallbackClarificationDraftFactory());
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "分析 Notion",
                "AI 协作文档",
                List.of("Notion"),
                List.of("AI 搜索"),
                List.of("official_site"),
                List.of(),
                null
        ));

        node.execute(run);

        assertThat(run.getRecommendedActions()).anyMatch(action -> action.contains("LLM 范围澄清失败"));
        assertThat(run.getClarificationDraft().getCompetitors()).containsExactly("Notion");
        assertThat(run.getClarificationDraft().getClarificationQuestions())
                .contains("是否需要加入 Confluence、Airtable 等标杆产品作为对照？");
    }

}
