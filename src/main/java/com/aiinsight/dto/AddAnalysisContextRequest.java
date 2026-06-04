package com.aiinsight.dto;

import com.aiinsight.model.enums.AgentName;
import com.aiinsight.model.enums.ContextIntent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class AddAnalysisContextRequest {

    @NotBlank
    private String content;
    @NotNull
    private ContextIntent intent;
    private AgentName targetAgent;
    private boolean startAfterUpdate;
}
