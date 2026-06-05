package com.aiinsight.model.schema;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class SurveyFinding {

    private String question;
    private String finding;
    private String distribution;
    private String interpretation;
    private List<String> relatedCompetitors = new ArrayList<>();
    private List<String> relatedDimensions = new ArrayList<>();
    private List<String> evidenceIds = new ArrayList<>();
}
