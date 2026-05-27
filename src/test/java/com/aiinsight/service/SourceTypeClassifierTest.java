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
}
