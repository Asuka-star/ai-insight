package com.aiinsight.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.UUID;

@Getter
@AllArgsConstructor
public class AnalysisRunMetrics {

    private UUID runId;
    private int agentStepCount;
    private int evidenceCount;
    private int reviewFindingCount;
    private int citationMentionCount;
    private int claimCoverage;
    private int schemaCompleteness;
    private int reworkCount;
    private double evidencePerClaim;
    private int totalTokens;
    private long totalLatencyMs;
    private int highFindingCount;
    private int mediumFindingCount;
    private int lowFindingCount;
}
