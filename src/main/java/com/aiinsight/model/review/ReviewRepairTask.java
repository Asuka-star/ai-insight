package com.aiinsight.model.review;

import com.aiinsight.model.enums.AgentName;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
public class ReviewRepairTask {

    private UUID id = UUID.randomUUID();
    private AgentName targetAgent;
    private String findingId;
    private UUID artifactId;
    private String claimId;
    private String citationKey;
    private Integer paragraphIndex;
    private String excerpt;
    private String currentText;
    private String category;
    private String action;
    private String instruction;
    private String expectedFix;
    private String acceptanceCriteria;
    private List<String> requiredEvidenceTypes = new ArrayList<>();
}
