package com.aiinsight.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class AddUserEvidenceRequest {

    private String title;
    private String sourceType = "note";
    private String content;
    private String url;
    private boolean sensitive;
}
