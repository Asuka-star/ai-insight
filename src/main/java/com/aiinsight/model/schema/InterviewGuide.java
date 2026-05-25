package com.aiinsight.model.schema;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class InterviewGuide {

    private String title;
    private List<String> targetRoles = new ArrayList<>();
    private List<String> questions = new ArrayList<>();
    private List<String> probingQuestions = new ArrayList<>();
}
