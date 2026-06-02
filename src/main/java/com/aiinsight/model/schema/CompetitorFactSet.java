package com.aiinsight.model.schema;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class CompetitorFactSet {

    private String competitorName;
    private List<ExtractedFact> facts = new ArrayList<>();
    private List<UnknownFact> unknowns = new ArrayList<>();
    private List<String> sourceCoverageNotes = new ArrayList<>();
}
