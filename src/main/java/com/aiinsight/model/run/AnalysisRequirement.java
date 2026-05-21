package com.aiinsight.model.run;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class AnalysisRequirement {

    private String originalPrompt;
    private String industry;
    private List<String> competitors = new ArrayList<>();
    private List<String> dimensions = new ArrayList<>();
    private List<String> sourcePreferences = new ArrayList<>();
    private List<String> sourceUrls = new ArrayList<>();

    public AnalysisRequirement(String originalPrompt, String industry, List<String> competitors,
                               List<String> dimensions, List<String> sourcePreferences, List<String> sourceUrls) {
        this.originalPrompt = originalPrompt;
        this.industry = industry;
        this.competitors = new ArrayList<>(competitors);
        this.dimensions = new ArrayList<>(dimensions);
        this.sourcePreferences = new ArrayList<>(sourcePreferences);
        this.sourceUrls = new ArrayList<>(sourceUrls);
    }
}
