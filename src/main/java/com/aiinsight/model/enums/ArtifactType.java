package com.aiinsight.model.enums;

public enum ArtifactType {
    CLARIFICATION_BRIEF,
    SOURCE_LIST,
    RESEARCH_PLAN,
    FACT_EXTRACTION,
    COMPETITOR_PROFILE,
    COMPETITIVE_MATRIX,
    SWOT_ANALYSIS,
    REPORT_DRAFT,
    REVIEW_FINDINGS,
    // Historical compatibility only. New runs stop after Reviewer and display REPORT_DRAFT.
    FINALIZATION_NOTE,
    FINAL_REPORT
}
