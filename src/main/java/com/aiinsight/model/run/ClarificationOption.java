package com.aiinsight.model.run;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class ClarificationOption {

    private String label;
    private String description;
    private List<String> values = new ArrayList<>();
    private boolean recommended;

    public ClarificationOption(String label, String description, List<String> values, boolean recommended) {
        this.label = label;
        this.description = description;
        this.values = values == null ? new ArrayList<>() : new ArrayList<>(values);
        this.recommended = recommended;
    }
}
