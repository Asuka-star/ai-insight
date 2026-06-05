package com.aiinsight.model.review;

import com.aiinsight.model.enums.ReviewSeverity;
import com.aiinsight.model.enums.AgentName;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
public class ReviewFinding {

    private UUID id = UUID.randomUUID();
    private ReviewSeverity severity;
    private AgentName targetAgent;
    private String category;
    private String message;
    private String recommendation;
    private UUID artifactId;
    private String claimId;
    private String factId;
    private String chunkKey;
    private String citationKey;
    private Integer paragraphIndex;
    private String excerpt;

    public ReviewFinding(ReviewSeverity severity, String category, String message, String recommendation) {
        this.severity = severity;
        this.category = category;
        this.message = message;
        this.recommendation = recommendation;
    }
}
