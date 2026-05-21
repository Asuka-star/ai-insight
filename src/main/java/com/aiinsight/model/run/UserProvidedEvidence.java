package com.aiinsight.model.run;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
public class UserProvidedEvidence {

    private UUID id = UUID.randomUUID();
    private String title;
    private String sourceType;
    private String content;
    private String url;
    private boolean sensitive;
    private Instant createdAt = Instant.now();

    public UserProvidedEvidence(String title, String sourceType, String content, String url, boolean sensitive) {
        this.title = title;
        this.sourceType = sourceType;
        this.content = content;
        this.url = url;
        this.sensitive = sensitive;
    }
}
