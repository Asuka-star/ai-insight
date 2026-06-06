package com.aiinsight.util;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TermExtractorTest {

    @Test
    void extractsEnglishTermsAndChineseBigrams() {
        Set<String> terms = TermExtractor.extract(
                "AI search supports 企业权限治理",
                TermExtractor.TermOptions.support(3, Set.of("supports"))
        );

        assertThat(terms).contains("search", "企业", "权限", "治理");
        assertThat(terms).doesNotContain("supports");
    }

    @Test
    void honorsMinimumTermLength() {
        Set<String> terms = TermExtractor.extract(
                "AI IDE SOC2",
                TermExtractor.TermOptions.basic(2)
        );

        assertThat(terms).contains("ai", "ide", "soc2");
    }

    @Test
    void canAddCrossLingualAliases() {
        Set<String> terms = TermExtractor.extract(
                "支持企业权限治理",
                TermExtractor.TermOptions.supportWithAliases(3, Set.of())
        );

        assertThat(terms).contains("enterprise", "permission", "permissions", "admin", "governance");
    }
}
