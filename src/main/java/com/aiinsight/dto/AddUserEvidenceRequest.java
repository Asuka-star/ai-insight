package com.aiinsight.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class AddUserEvidenceRequest {

    @NotBlank
    private String title;
    private String sourceType = "note";
    @NotBlank
    private String content;
    private String url;
    private boolean sensitive;
}
