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
    private String sourceType;
    private String collectionStatus;
    private String freshness;
    private String snippet;
    private String rawText;
    private String complianceNote;
    private Instant retrievedAt = Instant.now();

    public EvidenceSource(String citationKey, String title, String url, String snippet) {
        this.citationKey = citationKey;
        this.title = title;
        this.url = url;
        this.snippet = snippet;
    }

    public EvidenceSource(String citationKey,
                          String title,
                          String url,
                          String sourceType,
                          String snippet,
                          String rawText,
                          String complianceNote) {
        this.citationKey = citationKey;
        this.title = title;
        this.url = url;
        this.sourceType = sourceType;
        this.collectionStatus = "UNKNOWN";
        this.freshness = "UNKNOWN";
        this.snippet = snippet;
        this.rawText = rawText;
        this.complianceNote = complianceNote;
    }

    public EvidenceSource(String citationKey,
                          String title,
                          String url,
                          String sourceType,
                          String collectionStatus,
                          String freshness,
                          String snippet,
                          String rawText,
                          String complianceNote) {
        this.citationKey = citationKey;
        this.title = title;
        this.url = url;
        this.sourceType = sourceType;
        this.collectionStatus = collectionStatus;
        this.freshness = freshness;
        this.snippet = snippet;
        this.rawText = rawText;
        this.complianceNote = complianceNote;
    }
}
