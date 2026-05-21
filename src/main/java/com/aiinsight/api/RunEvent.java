package com.aiinsight.api;

import java.time.Instant;
import java.util.UUID;

public record RunEvent(
        UUID runId,
        String type,
        String message,
        Instant occurredAt
) {
    public static RunEvent of(UUID runId, String type, String message) {
        return new RunEvent(runId, type, message, Instant.now());
    }
}
