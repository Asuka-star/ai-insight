package com.aiinsight.model.schema;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class UnknownFact {

    private String competitorName;
    private String field;
    private String reason;
    private List<String> neededEvidenceTypes = new ArrayList<>();
}
