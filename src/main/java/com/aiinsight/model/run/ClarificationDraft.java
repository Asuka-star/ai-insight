package com.aiinsight.model.run;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class ClarificationDraft {

    private String industry;
    private List<String> competitors = new ArrayList<>();
    private List<String> dimensions = new ArrayList<>();
    private List<String> sourcePreferences = new ArrayList<>();
    private List<String> sourceUrls = new ArrayList<>();
    private String outputGoal;
    private List<String> clarificationQuestions = new ArrayList<>();
    private List<ClarificationItem> clarificationItems = new ArrayList<>();
    private boolean confirmed;
    private Instant createdAt = Instant.now();
    private Instant confirmedAt;

    public ClarificationDraft(AnalysisRequirement requirement) {
        this.industry = requirement.getIndustry();
        this.competitors = new ArrayList<>(requirement.getCompetitors());
        this.dimensions = new ArrayList<>(requirement.getDimensions());
        this.sourcePreferences = new ArrayList<>(requirement.getSourcePreferences());
        this.sourceUrls = new ArrayList<>(requirement.getSourceUrls());
        this.outputGoal = requirement.getOutputGoal();
    }
}
