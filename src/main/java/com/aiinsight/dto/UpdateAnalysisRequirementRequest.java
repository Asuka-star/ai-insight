package com.aiinsight.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Getter
@NoArgsConstructor
public class UpdateAnalysisRequirementRequest {

    private String industry;
    private List<String> competitors = new ArrayList<>();
    private List<String> dimensions = new ArrayList<>();
    private List<String> sourceUrls = new ArrayList<>();
    private String outputGoal;
    private Integer maxReviewReworkAttempts;
    private boolean industryProvided;
    private boolean competitorsProvided;
    private boolean dimensionsProvided;
    private boolean sourceUrlsProvided;
    private boolean outputGoalProvided;
    private boolean maxReviewReworkAttemptsProvided;

    public void setIndustry(String industry) {
        this.industry = industry;
        this.industryProvided = true;
    }

    public void setCompetitors(List<String> competitors) {
        this.competitors = competitors == null ? new ArrayList<>() : new ArrayList<>(competitors);
        this.competitorsProvided = true;
    }

    public void setDimensions(List<String> dimensions) {
        this.dimensions = dimensions == null ? new ArrayList<>() : new ArrayList<>(dimensions);
        this.dimensionsProvided = true;
    }

    public void setSourceUrls(List<String> sourceUrls) {
        this.sourceUrls = sourceUrls == null ? new ArrayList<>() : new ArrayList<>(sourceUrls);
        this.sourceUrlsProvided = true;
    }

    public void setOutputGoal(String outputGoal) {
        this.outputGoal = outputGoal;
        this.outputGoalProvided = true;
    }

    public void setMaxReviewReworkAttempts(Integer maxReviewReworkAttempts) {
        this.maxReviewReworkAttempts = maxReviewReworkAttempts;
        this.maxReviewReworkAttemptsProvided = true;
    }

    public boolean industryProvided() {
        return industryProvided;
    }

    public boolean competitorsProvided() {
        return competitorsProvided;
    }

    public boolean dimensionsProvided() {
        return dimensionsProvided;
    }

    public boolean sourceUrlsProvided() {
        return sourceUrlsProvided;
    }

    public boolean outputGoalProvided() {
        return outputGoalProvided;
    }

    public boolean maxReviewReworkAttemptsProvided() {
        return maxReviewReworkAttemptsProvided;
    }
}
