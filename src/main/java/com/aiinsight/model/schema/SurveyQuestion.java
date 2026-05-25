package com.aiinsight.model.schema;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class SurveyQuestion {

    private String dimension;
    private String question;
    private List<String> options = new ArrayList<>();

    public SurveyQuestion(String dimension, String question, List<String> options) {
        this.dimension = dimension;
        this.question = question;
        this.options = new ArrayList<>(options);
    }
}
