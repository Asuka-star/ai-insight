package com.aiinsight.model.run;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
public class EvidenceChunk {

    private UUID id = UUID.randomUUID();
    private String chunkKey;
    private String sourceCitationKey;
    private int chunkIndex;
    private String title;
    private String url;
    private List<String> headingPath = List.of();
    private String contentKind;
    private String sourceType;
    private String sourceAuthority;
    private String sourceQuality;
    private String textHash;
    private String embeddingModel;
    private Instant embeddedAt;
    private List<Double> embedding = List.of();
    private String text;
    private double score;
    private Instant createdAt = Instant.now();

    public EvidenceChunk(String chunkKey,
                         String sourceCitationKey,
                         int chunkIndex,
                         String title,
                         String url,
                         String text) {
        this.chunkKey = chunkKey;
        this.sourceCitationKey = sourceCitationKey;
        this.chunkIndex = chunkIndex;
        this.title = title;
        this.url = url;
        this.text = text;
    }
}
