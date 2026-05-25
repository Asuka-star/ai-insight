package com.aiinsight.model.schema;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class ResearchTask {

    private String type;
    private String target;
    private String rationale;
    private String status;

    public ResearchTask(String type, String target, String rationale, String status) {
        this.type = type;
        this.target = target;
        this.rationale = rationale;
        this.status = status;
    }
}
