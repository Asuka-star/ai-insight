package com.aiinsight.dto;

import com.aiinsight.model.enums.AgentName;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.Instant;
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
    private LatestImprovementMetrics latestImprovement;

    @Getter
    @AllArgsConstructor
    public static class LatestImprovementMetrics {
        private AgentName agentName;
        private boolean changed;
        private Instant recordedAt;
        private int evidenceBefore;
        private int evidenceAfter;
        private int evidenceDelta;
        private int coverageGapsBefore;
        private int coverageGapsAfter;
        private int coverageGapDelta;
        private int findingsBefore;
        private int findingsAfter;
        private int findingDelta;
        private int highFindingsBefore;
        private int highFindingsAfter;
        private int highFindingDelta;
        private int claimCoverageBefore;
        private int claimCoverageAfter;
        private int claimCoverageDelta;
    }
}
