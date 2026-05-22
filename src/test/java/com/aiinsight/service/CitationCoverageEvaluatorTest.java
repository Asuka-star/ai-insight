package com.aiinsight.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CitationCoverageEvaluatorTest {

    private final CitationCoverageEvaluator evaluator = new CitationCoverageEvaluator();

    @Test
    void flagsClaimParagraphWithoutCitation() {
        String report = """
                # Report

                机会点是构建可复核的 Agent 工作流。

                风险在于资料源不足会导致推断过度 [S1]。
                """;

        var findings = evaluator.evaluate(report);

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).getCategory()).isEqualTo("citation_missing");
        assertThat(findings.get(0).getParagraphIndex()).isEqualTo(1);
        assertThat(findings.get(0).getExcerpt()).contains("机会点");
    }
}
