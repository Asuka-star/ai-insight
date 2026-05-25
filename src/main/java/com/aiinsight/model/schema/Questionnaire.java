package com.aiinsight.model.schema;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class Questionnaire {

    private String title;
    private String targetRespondents;
    private String recommendedSampleSize;
    private List<SurveyQuestion> questions = new ArrayList<>();
}
