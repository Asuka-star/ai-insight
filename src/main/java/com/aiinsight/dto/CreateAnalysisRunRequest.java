package com.aiinsight.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class CreateAnalysisRunRequest {

    @NotBlank
    private String prompt;
    private String industry;
    private List<String> competitors = new ArrayList<>();
    private List<String> dimensions = new ArrayList<>();
    private List<String> sourceUrls = new ArrayList<>();
    private String outputGoal;
    private Integer maxReviewReworkAttempts;

    public void setCompetitors(List<String> competitors) {
        this.competitors = competitors == null ? new ArrayList<>() : new ArrayList<>(competitors);
    }

    public void setDimensions(List<String> dimensions) {
        this.dimensions = dimensions == null ? new ArrayList<>() : new ArrayList<>(dimensions);
    }

    public void setSourceUrls(List<String> sourceUrls) {
        this.sourceUrls = sourceUrls == null ? new ArrayList<>() : new ArrayList<>(sourceUrls);
    }
}
