package com.aiinsight.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
public class AnalysisArtifact {

    private UUID id = UUID.randomUUID();
    private ArtifactType type;
    private String title;
    private String content;
    private int version = 1;
    private List<String> citationKeys = new ArrayList<>();
    private Instant createdAt = Instant.now();

    public AnalysisArtifact(ArtifactType type, String title, String content, List<String> citationKeys) {
        this.type = type;
        this.title = title;
        this.content = content;
        this.citationKeys = new ArrayList<>(citationKeys);
    }
}
