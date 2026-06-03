package com.aiinsight.model.schema;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
public class CandidateUrl {

    private UUID id = UUID.randomUUID();
    private UUID runId;
    private UUID subtaskId;
    private String candidateId;
    private String url;
    private String normalizedUrl;
    private String title;
    private String snippet;
    private String sourceProvider;
    private String sourceTypeHint;
    private double searchScore;
    private boolean duplicate;
    private UUID duplicateOf;
    private String rejectionReason;
}
