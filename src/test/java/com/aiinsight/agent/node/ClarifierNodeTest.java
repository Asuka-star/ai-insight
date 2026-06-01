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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ClarifierNodeTest {

    @Test
    void clarifierNodeOwnsLlmCallAndMergesResult() {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<ChatRequest> capturedRequest = new AtomicReference<>();
        ClarifierNode node = new ClarifierNode(new LlmClient() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String complete(ChatRequest request) {
                capturedRequest.set(request);
                calls.incrementAndGet();
                return """
                        {
                          "industry": "企业服务 CRM",
                          "competitors": ["Salesforce", "HubSpot"],
                          "dimensions": ["销售自动化", "权限治理"],
                          "sourcePreferences": ["official_site", "pricing_page"],
                          "sourceUrls": [],
                          "outputGoal": "支持销售产品规划",
                          "clarificationQuestions": ["是否需要加入 Zoho CRM？"],
                          "clarificationItems": [
                            {
                              "field": "competitors",
                              "question": "是否需要加入更多 CRM 标杆？",
                              "reason": "补充标杆可以提升矩阵对比价值",
                              "required": false,
                              "options": [
                                {
                                  "label": "加入 Zoho CRM",
                                  "description": "覆盖中小企业 CRM 参照",
                                  "values": ["Salesforce", "HubSpot", "Zoho CRM"],
                                  "recommended": true
                                }
                              ]
                            }
                          ]
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
        assertThat(capturedRequest.get().getMessages().get(1).getContent())
                .contains("错别字", "不要静默改写", "values 放修正后的完整竞品列表")
                .doesNotContain("ocdex 可能是 Codex");
        assertThat(run.getClarificationDraft().getClarificationQuestions())
                .containsExactly("是否需要加入 Zoho CRM？");
        assertThat(run.getClarificationDraft().getClarificationItems())
                .filteredOn(item -> "competitors".equals(item.getField())
                        && "是否需要加入更多 CRM 标杆？".equals(item.getQuestion()))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.getField()).isEqualTo("competitors");
                    assertThat(item.getOptions()).singleElement().satisfies(option -> {
                        assertThat(option.getLabel()).isEqualTo("加入 Zoho CRM");
                        assertThat(option.getValues()).containsExactly("Salesforce", "HubSpot", "Zoho CRM");
                        assertThat(option.isRecommended()).isTrue();
                    });
                });
    }

    @Test
    void clarifierKeepsUserCompetitorUntilUserChoosesCorrectionOption() {
        ClarifierNode node = new ClarifierNode(new LlmClient() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String complete(ChatRequest request) {
                return """
                        {
                          "industry": "AI 编程助手",
                          "competitors": ["Codex", "Cursor"],
                          "dimensions": ["代码生成"],
                          "sourcePreferences": ["official_site"],
                          "sourceUrls": [],
                          "outputGoal": "产品规划",
                          "clarificationQuestions": ["你输入的 ocdex 是否指 Codex？"],
                          "clarificationItems": [
                            {
                              "field": "competitors",
                              "question": "是否将 ocdex 修正为 Codex，并补充 Cursor 作为对照？",
                              "reason": "修正错别字并补充标杆后，后续采集会命中正确产品。",
                              "required": false,
                              "options": [
                                {
                                  "label": "修正并补充",
                                  "description": "后续分析使用 Codex 和 Cursor。",
                                  "values": ["Codex", "Cursor"],
                                  "recommended": true
                                }
                              ]
                            }
                          ]
                        }
                        """;
            }
        }, new ObjectMapper(), new FallbackClarificationDraftFactory());
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "分析 ocdex 这个 AI 编程助手",
                "AI 编程助手",
                List.of("ocdex"),
                List.of(),
                List.of(),
                List.of(),
                null
        ));

        node.execute(run);

        assertThat(run.getRequirement().getCompetitors()).containsExactly("ocdex");
        assertThat(run.getClarificationDraft().getClarificationItems())
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.getField()).isEqualTo("competitors");
                    assertThat(item.getQuestion()).contains("ocdex", "Codex");
                    assertThat(item.getOptions()).singleElement()
                            .satisfies(option -> assertThat(option.getValues()).containsExactly("Codex", "Cursor"));
                });
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
                .noneMatch(question -> question.contains("Confluence、Airtable") || question.contains("标杆产品作为对照"));
        assertThat(run.getClarificationDraft().getClarificationItems())
                .anySatisfy(item -> {
                    assertThat(item.getField()).isEqualTo("competitors");
                    assertThat(item.getOptions()).isNotEmpty();
                });
    }

}
