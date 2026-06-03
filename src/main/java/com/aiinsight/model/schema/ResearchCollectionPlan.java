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
public class ResearchCollectionPlan {

    private UUID id = UUID.randomUUID();
    private UUID runId;
    private String goal;
    private List<String> competitors = new ArrayList<>();
    private List<String> dimensions = new ArrayList<>();
    private List<ResearchSubtask> subtasks = new ArrayList<>();
    private List<CandidateUrl> candidateUrls = new ArrayList<>();
    private List<EvidenceBudget> evidenceBudgets = new ArrayList<>();
    private List<ResearchCoverageGap> coverageGaps = new ArrayList<>();
    private List<ResearchRepairTarget> repairTargets = new ArrayList<>();
    private LeadResearchPlan leadResearchPlan = new LeadResearchPlan();
    private String planSource = "RULE_BASED";
    private Instant createdAt = Instant.now();
}
