package com.aiinsight.model.schema;

import com.aiinsight.model.run.EvidenceSource;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
// ResearchPackage 是 Researcher Agent 交给后续 Agent 的结构化资料包。
public class ResearchPackage {

    private List<EvidenceSource> sources = new ArrayList<>();
    private List<String> missingEvidenceTypes = new ArrayList<>();
    private List<String> actualSearchQueries = new ArrayList<>();
    private ResearchPlan researchPlan = new ResearchPlan();
    private List<InterviewInsight> interviewInsights = new ArrayList<>();
    private Instant collectedAt = Instant.now();
}
