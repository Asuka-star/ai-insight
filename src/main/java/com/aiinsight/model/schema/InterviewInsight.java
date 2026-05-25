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
public class InterviewInsight {

    private String id = UUID.randomUUID().toString();
    private String evidenceId;
    private String sourceTitle;
    private String intervieweeRole;
    private String scenario;
    private List<String> painPoints = new ArrayList<>();
    private List<String> positiveSignals = new ArrayList<>();
    private List<String> negativeSignals = new ArrayList<>();
    private List<String> buyingConcerns = new ArrayList<>();
    private List<String> competitorMentions = new ArrayList<>();
    private List<String> relatedDimensions = new ArrayList<>();
    private List<String> directQuotes = new ArrayList<>();
    private String confidence = "LOW";
}
