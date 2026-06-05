package com.aiinsight.model.schema;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
public class SurveyResultImport {

    private UUID id = UUID.randomUUID();
    private UUID runId;
    private String batchId;
    private String title;
    private Questionnaire questionnaire = new Questionnaire();
    private String fileName;
    private String status = "IMPORTED";
    private int resultCount;
    private List<String> evidenceIds = new ArrayList<>();
    private Instant importedAt = Instant.now();
}
