package com.aiinsight.model.schema;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class EvidenceBudget {

    private String competitorName;
    private String dimension;
    private int minOfficialSources;
    private int minThirdPartySources;
    private int minRagChunks;
    private int maxAcceptedSources;
}
