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
public class SurveyInsight {

    private UUID id = UUID.randomUUID();
    private String evidenceId;
    private String title;
    private String sampleSize;
    private List<String> respondentSegments = new ArrayList<>();
    private List<SurveyFinding> findings = new ArrayList<>();
    private List<String> competitorMentions = new ArrayList<>();
    private List<String> relatedDimensions = new ArrayList<>();
    private List<String> evidenceIds = new ArrayList<>();
}
