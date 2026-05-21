package com.aiinsight.model.run;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
public class EvidenceSource {

    private UUID id = UUID.randomUUID();
    private String citationKey;
    private String title;
    private String url;
    private String snippet;
    private Instant retrievedAt = Instant.now();

    public EvidenceSource(String citationKey, String title, String url, String snippet) {
        this.citationKey = citationKey;
        this.title = title;
        this.url = url;
        this.snippet = snippet;
    }
}
