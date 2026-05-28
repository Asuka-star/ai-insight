package com.aiinsight.dto;

import com.aiinsight.model.enums.AnalysisStatus;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Getter
@AllArgsConstructor
public class AnalysisRunSummary {

    private UUID id;
    private AnalysisStatus status;
    private String industry;
    private List<String> competitors;
    private String outputGoal;
    private String originalPrompt;
    private int evidenceCount;
    private int artifactCount;
    private int findingCount;
    private int stepCount;
    private Instant createdAt;
    private Instant updatedAt;
}
