package com.aiinsight.model.schema;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
public class LeadResearchPlan {

    private UUID id = UUID.randomUUID();
    private UUID runId;
    private String planner = "RULE_BASED_LEAD_RESEARCH_PLANNER";
    private String objective;
    private List<String> focusAreas = new ArrayList<>();
    private List<String> rationale = new ArrayList<>();
    private List<String> recommendedSourceTypes = new ArrayList<>();
    private List<String> repairPriorities = new ArrayList<>();
    private Instant createdAt = Instant.now();
}
