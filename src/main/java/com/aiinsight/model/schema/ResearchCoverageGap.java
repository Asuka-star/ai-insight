package com.aiinsight.model.schema;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
public class ResearchCoverageGap {

    private UUID id = UUID.randomUUID();
    private UUID runId;
    private String competitorName;
    private String dimension;
    private List<String> missingSourceTypes = new ArrayList<>();
    private int existingEvidenceCount;
    private int requiredEvidenceCount;
    private String reason;
    private boolean repairRecommended;
}
