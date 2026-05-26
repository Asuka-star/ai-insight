package com.aiinsight.service;

import com.aiinsight.model.schema.AnalysisClaim;

import java.util.List;
import java.util.stream.Collectors;

public record AnalysisDraft(List<AnalysisClaim> claims, String matrixMarkdown, String swotMarkdown) {

    public String traceOutput() {
        return """
                claims=%s

                matrix:
                %s

                swot:
                %s
                """.formatted(
                claims.stream()
                        .map(claim -> "%s/%s %s evidence=%s".formatted(
                                claim.getType(),
                                claim.getConfidence(),
                                claim.getContent(),
                                claim.getEvidenceIds()))
                        .collect(Collectors.joining("\n")),
                matrixMarkdown,
                swotMarkdown);
    }
}
