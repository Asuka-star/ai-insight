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
    private String claimId;
    private String citationKey;
    private String category;
    private String action;
    private String instruction;
    private String acceptanceCriteria;
    private List<String> requiredEvidenceTypes = new ArrayList<>();
}
