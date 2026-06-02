package com.aiinsight.model.schema;

import com.aiinsight.model.enums.FactType;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class ExtractedFact {

    private String id;
    private String competitorName;
    private FactType factType = FactType.UNKNOWN;
    private String attribute;
    private String value;
    private List<String> evidenceIds = new ArrayList<>();
    private List<String> chunkKeys = new ArrayList<>();
    private String sourceAuthority;
    private String sourceQuality;
    private String extractionConfidence;
}
