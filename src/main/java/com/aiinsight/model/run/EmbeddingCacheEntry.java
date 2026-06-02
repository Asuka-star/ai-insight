package com.aiinsight.model.run;

import java.time.Instant;
import java.util.List;

public record EmbeddingCacheEntry(
        String inputHash,
        String textHash,
        String embeddingModel,
        int dimensions,
        List<Double> embedding,
        Instant createdAt,
        Instant lastUsedAt,
        int usageCount
) {
}
