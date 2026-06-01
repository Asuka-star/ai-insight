package com.aiinsight.service;

import com.aiinsight.dto.CreateAnalysisRunRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisRequestNormalizerTest {

    private final AnalysisRequestNormalizer normalizer = new AnalysisRequestNormalizer();

    @Test
    void infersDocumentIndustryAndCompetitorsFromPrompt() {
        CreateAnalysisRunRequest request = new CreateAnalysisRunRequest();
        request.setPrompt("分析 AlphaDocs 和 BetaBase 在 AI 协作文档方向的竞品机会");

        var requirement = normalizer.normalize(request);

        assertThat(requirement.getIndustry()).isEqualTo("AI 协作文档");
        assertThat(requirement.getCompetitors()).containsExactly("AlphaDocs", "BetaBase");
        assertThat(requirement.getDimensions()).contains("产品定位", "机会点", "风险提示");
    }

    @Test
    void extractsSourceUrlsFromPromptAndRequestBody() {
        CreateAnalysisRunRequest request = new CreateAnalysisRunRequest();
        request.setPrompt("分析 Notion，参考 https://www.notion.so/product");
        request.setSourceUrls(java.util.List.of("https://www.feishu.cn/product/docs"));

        var requirement = normalizer.normalize(request);

        assertThat(requirement.getSourceUrls())
                .containsExactly("https://www.feishu.cn/product/docs", "https://www.notion.so/product");
    }

    @Test
    void extractsCompetitorHintsWithoutTreatingSourceTypesAsCompetitors() {
        var competitors = normalizer.extractMentionedCompetitors("再加入 GammaDocs，重点看权限，也补充价格页和公开评价。");

        assertThat(competitors).containsExactly("GammaDocs");
    }

    @Test
    void separatesPromptUrlsJoinedByChineseEnumerationComma() {
        CreateAnalysisRunRequest request = new CreateAnalysisRunRequest();
        request.setPrompt("公开来源：https://cursor.com、https://github.com/features/copilot、https://www.jetbrains.com/ai/");

        var requirement = normalizer.normalize(request);

        assertThat(requirement.getSourceUrls())
                .containsExactly(
                        "https://cursor.com",
                        "https://github.com/features/copilot",
                        "https://www.jetbrains.com/ai/"
                );
    }
}
