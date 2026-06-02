package com.aiinsight.model.schema;

import com.aiinsight.model.enums.ClaimType;
import com.aiinsight.model.enums.ConfidenceLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
// AnalysisClaim 是报告中的“结论原子”：每条结论都必须带类型、置信度和证据引用。
public class AnalysisClaim {

    private String id = "C-" + UUID.randomUUID();
    private ClaimType type;
    private String content;
    private ConfidenceLevel confidence = ConfidenceLevel.MEDIUM;
    private String generatedBy;
    private List<String> competitorNames = new ArrayList<>();
    private List<String> factIds = new ArrayList<>();
    private List<String> evidenceIds = new ArrayList<>();
    private List<String> chunkKeys = new ArrayList<>();
}
