package com.aiinsight.model.schema;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class ResearchPlan {

    private String objective;
    private List<String> evidenceGaps = new ArrayList<>();
    private List<String> searchQueries = new ArrayList<>();
    private List<ResearchTask> publicSourceTasks = new ArrayList<>();
    private Questionnaire questionnaire = new Questionnaire();
    private InterviewGuide interviewGuide = new InterviewGuide();
}
