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
public class ResearchRepairTarget {

    private UUID id = UUID.randomUUID();
    private UUID runId;
    private UUID coverageGapId;
    private String findingId;
    private String repairTaskId;
    private String competitorName;
    private String dimension;
    private List<String> sourcePreferences = new ArrayList<>();
    private List<String> queries = new ArrayList<>();
    private String reason;
    private String priority = "BACKFILL";
    private String status = "PENDING";
}
