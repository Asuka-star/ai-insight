package com.aiinsight.model.run;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class ClarificationItem {

    private String field;
    private String question;
    private String reason;
    private boolean required;
    private List<ClarificationOption> options = new ArrayList<>();
    private List<String> selectedValues = new ArrayList<>();

    public ClarificationItem(String field,
                             String question,
                             String reason,
                             boolean required,
                             List<ClarificationOption> options) {
        this.field = field;
        this.question = question;
        this.reason = reason;
        this.required = required;
        this.options = options == null ? new ArrayList<>() : new ArrayList<>(options);
    }
}
