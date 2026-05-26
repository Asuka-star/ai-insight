package com.aiinsight.service;

import com.aiinsight.llm.ChatRequest;
import com.aiinsight.llm.LlmClient;
import com.aiinsight.model.run.AnalysisRequirement;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ClarificationDraftServiceTest {

    @Test
    void clarifyScopeUsesLlmDraftToFillPlaceholderScopeAndQuestions() {
        ClarificationDraftService service = new ClarificationDraftService(new LlmClient() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String complete(ChatRequest request) {
                return """
                        {
                          "industry": "企业服务 CRM",
                          "competitors": ["Salesforce", "HubSpot"],
                          "dimensions": ["销售自动化", "权限治理"],
                          "sourcePreferences": ["official_site", "pricing_page"],
                          "sourceUrls": ["https://invented.example.test"],
                          "outputGoal": "支持销售产品规划",
                          "clarificationQuestions": ["是否需要加入 Zoho CRM 作为价格对照？"]
                        }
                        """;
            }
        }, new ObjectMapper());
        AnalysisRequirement requirement = new AnalysisRequirement(
                "分析 CRM 里的销售自动化机会",
                "待澄清行业",
                List.of("竞品 A", "竞品 B"),
                List.of(),
                List.of(),
                List.of(),
                null
        );

        var result = service.clarifyScope(requirement);

        assertThat(result.fallbackUsed()).isFalse();
        assertThat(result.draft().getIndustry()).isEqualTo("企业服务 CRM");
        assertThat(result.draft().getCompetitors()).containsExactly("Salesforce", "HubSpot");
        assertThat(result.draft().getDimensions()).containsExactly("销售自动化", "权限治理");
        assertThat(result.draft().getSourcePreferences()).containsExactly("official_site", "pricing_page");
        assertThat(result.draft().getSourceUrls()).isEmpty();
        assertThat(result.draft().getOutputGoal()).isEqualTo("支持销售产品规划");
        assertThat(result.draft().getClarificationQuestions())
                .contains("是否需要加入 Zoho CRM 作为价格对照？", "是否有官网、价格页、产品文档、公开评价或访谈记录可以作为资料来源？");
    }

    @Test
    void clarifyScopePreservesExplicitRequirementFieldsWhenLlmSuggestsDifferentScope() {
        ClarificationDraftService service = new ClarificationDraftService(new LlmClient() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String complete(ChatRequest request) {
                return """
                        {
                          "industry": "企业知识库",
                          "competitors": ["Confluence", "Airtable"],
                          "dimensions": ["知识沉淀"],
                          "sourcePreferences": ["public_reviews"],
                          "sourceUrls": ["https://example.test/other"],
                          "outputGoal": "市场汇报",
                          "clarificationQuestions": ["是否需要扩展到知识库产品？"]
                        }
                        """;
            }
        }, new ObjectMapper());
        AnalysisRequirement requirement = new AnalysisRequirement(
                "分析 Notion 和飞书文档",
                "AI 协作文档",
                List.of("Notion", "飞书文档"),
                List.of("AI 搜索", "权限协作"),
                List.of("official_site", "pricing_page"),
                List.of("https://www.notion.com/product"),
                "产品规划"
        );

        var result = service.clarifyScope(requirement);

        assertThat(result.fallbackUsed()).isFalse();
        assertThat(result.draft().getIndustry()).isEqualTo("AI 协作文档");
        assertThat(result.draft().getCompetitors()).containsExactly("Notion", "飞书文档");
        assertThat(result.draft().getDimensions()).containsExactly("AI 搜索", "权限协作");
        assertThat(result.draft().getSourcePreferences()).containsExactly("official_site", "pricing_page");
        assertThat(result.draft().getSourceUrls()).containsExactly("https://www.notion.com/product");
        assertThat(result.draft().getOutputGoal()).isEqualTo("产品规划");
        assertThat(result.draft().getClarificationQuestions()).contains("是否需要扩展到知识库产品？");
    }

    @Test
    void clarifyScopeFallsBackToRulesWhenLlmFails() {
        ClarificationDraftService service = new ClarificationDraftService(new LlmClient() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String complete(ChatRequest request) {
                throw new IllegalStateException("simulated clarifier timeout");
            }
        }, new ObjectMapper());
        AnalysisRequirement requirement = new AnalysisRequirement(
                "分析 Notion",
                "AI 协作文档",
                List.of("Notion"),
                List.of("AI 搜索"),
                List.of("official_site"),
                List.of(),
                null
        );

        var result = service.clarifyScope(requirement);

        assertThat(result.fallbackUsed()).isTrue();
        assertThat(result.fallbackReason()).contains("LLM 范围澄清失败");
        assertThat(result.draft().getCompetitors()).containsExactly("Notion");
        assertThat(result.draft().getClarificationQuestions())
                .contains("是否需要加入 Confluence、Airtable 等标杆产品作为对照？",
                        "是否有官网、价格页、产品文档、公开评价或访谈记录可以作为资料来源？",
                        "这份报告主要用于支持什么决策：产品评审、规划立项，还是向上汇报？");
    }

    @Test
    void createDraftUsesRulesWithoutLlmRecommendedAction() {
        ClarificationDraftService service = new ClarificationDraftService(new LlmClient() {
            @Override
            public boolean isAvailable() {
                return false;
            }

            @Override
            public String complete(ChatRequest request) {
                throw new IllegalStateException("LLM is not configured");
            }
        }, new ObjectMapper());

        var result = service.createDraft(new AnalysisRequirement(
                "分析 Notion",
                "AI 协作文档",
                List.of("Notion"),
                List.of("AI 搜索"),
                List.of("official_site"),
                List.of(),
                null
        ));

        assertThat(result.fallbackUsed()).isFalse();
        assertThat(result.fallbackReason()).isNull();
        assertThat(result.draft().getClarificationQuestions()).isNotEmpty();
    }

    @Test
    void createDraftDoesNotCallLlmEvenWhenClientIsAvailable() {
        ClarificationDraftService service = new ClarificationDraftService(new LlmClient() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String complete(ChatRequest request) {
                throw new AssertionError("createDraft should not call LLM");
            }
        }, new ObjectMapper());

        var result = service.createDraft(new AnalysisRequirement(
                "分析 Notion 和飞书文档在 AI 协作文档方向的竞品机会",
                "AI 协作文档",
                List.of("Notion", "飞书文档"),
                List.of("AI 搜索"),
                List.of("official_site"),
                List.of(),
                "产品规划"
        ));

        assertThat(result.fallbackUsed()).isFalse();
        assertThat(result.draft().getCompetitors()).containsExactly("Notion", "飞书文档");
        assertThat(result.draft().getClarificationQuestions())
                .contains("是否需要加入 Confluence、Airtable 等标杆产品作为对照？");
    }
}
