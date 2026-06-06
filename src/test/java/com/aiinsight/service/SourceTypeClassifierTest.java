package com.aiinsight.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SourceTypeClassifierTest {

    private final SourceTypeClassifier classifier = new SourceTypeClassifier();

    @Test
    void doesNotTreatPublisherArticlesAsOfficialSites() {
        assertThat(classifier.classify("https://www.forbes.com/sites/example/vendor-analysis", "Vendor analysis"))
                .isEqualTo("article");
        assertThat(classifier.qualityFor("article", "FETCHED", "LIVE_FETCHED"))
                .isEqualTo("MEDIUM");
    }

    @Test
    void keepsProductRootPagesAsOfficialSites() {
        assertThat(classifier.classify("https://www.notion.so/product", "Notion product page"))
                .isEqualTo("official_site");
    }

    @Test
    void doesNotTreatVideoPricingContentAsPricingPage() {
        String sourceType = classifier.classify(
                "https://www.youtube.com/watch?v=abc123",
                "Cursor pricing and Pro plan explained"
        );

        assertThat(sourceType).isEqualTo("video");
        assertThat(classifier.qualityFor(sourceType, "FETCHED", "LIVE_FETCHED"))
                .isEqualTo("LOW");
    }

    @Test
    void downgradesThirdPartyDocumentationSites() {
        assertThat(classifier.classify("https://learn-cursor.com/docs/features", "Cursor feature docs"))
                .isEqualTo("third_party_docs");
        assertThat(classifier.classify("https://claudelog.com/docs/claude-code", "Claude Code docs"))
                .isEqualTo("third_party_docs");
        assertThat(classifier.classify(
                "https://www.verdent.ai/guides/claude-code-agent-skills",
                "Claude Code Agent Skills - Verdent Guides"
        )).isEqualTo("third_party_docs");
        assertThat(classifier.qualityFor("third_party_docs", "FETCHED", "LIVE_FETCHED"))
                .isEqualTo("MEDIUM");
    }

    @Test
    void doesNotTreatMirrorDomainsAsOfficialProductSites() {
        String sourceType = classifier.classify(
                "https://cursor.ac.cn/enterprise",
                "Cursor 企业版"
        );

        assertThat(sourceType).isEqualTo("third_party_article");
        assertThat(classifier.authorityFor("https://cursor.ac.cn/enterprise", sourceType))
                .isEqualTo("THIRD_PARTY_GENERAL");
    }

    @Test
    void keepsOfficialDocumentationAndPricingHighQuality() {
        assertThat(classifier.classify("https://docs.cursor.com/context/rules", "Cursor docs"))
                .isEqualTo("docs");
        assertThat(classifier.qualityFor("docs", "FETCHED", "LIVE_FETCHED"))
                .isEqualTo("HIGH");

        assertThat(classifier.classify("https://www.cursor.com/pricing", "Cursor pricing"))
                .isEqualTo("pricing_page");
        assertThat(classifier.qualityFor("pricing_page", "FETCHED", "LIVE_FETCHED"))
                .isEqualTo("HIGH");

        assertThat(classifier.classify("https://cursor.com/cn/pricing", "Cursor 定价"))
                .isEqualTo("pricing_page");

        assertThat(classifier.classify("https://cursor.com/integrations", "Cursor integrations"))
                .isEqualTo("integration_docs");
        assertThat(classifier.qualityFor("integration_docs", "FETCHED", "LIVE_FETCHED"))
                .isEqualTo("HIGH");
        assertThat(classifier.authorityFor("https://www.cursor.com/pricing", "pricing_page"))
                .isEqualTo("FIRST_PARTY_OFFICIAL");
    }

    @Test
    void downgradesThirdPartyPricingReferences() {
        String sourceType = classifier.classify("https://learn-cursor.com/docs/pricing", "Cursor pricing guide");

        assertThat(sourceType).isEqualTo("third_party_pricing_reference");
        assertThat(classifier.qualityFor(sourceType, "FETCHED", "LIVE_FETCHED"))
                .isEqualTo("MEDIUM");
        assertThat(classifier.authorityFor("https://learn-cursor.com/docs/pricing", sourceType))
                .isEqualTo("THIRD_PARTY_GENERAL");
    }

    @Test
    void doesNotPromoteThirdPartyPricingUrlToOfficialPricingAuthority() {
        String sourceType = classifier.classify(
                "https://example-blog.com/notion-pricing-comparison",
                "Notion pricing comparison"
        );

        assertThat(sourceType).isEqualTo("third_party_pricing_reference");
        assertThat(classifier.authorityFor("https://example-blog.com/notion-pricing-comparison", sourceType))
                .isEqualTo("THIRD_PARTY_GENERAL");
    }
}
