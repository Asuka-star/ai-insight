package com.aiinsight.domain;

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
    private String category;
    private String message;
    private String recommendation;

    public ReviewFinding(ReviewSeverity severity, String category, String message, String recommendation) {
        this.severity = severity;
        this.category = category;
        this.message = message;
        this.recommendation = recommendation;
    }
}
