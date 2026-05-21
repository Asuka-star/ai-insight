package com.aiinsight.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class RunEvent {

    private UUID runId;
    private String type;
    private String message;
    private Instant occurredAt;

    public static RunEvent of(UUID runId, String type, String message) {
        return new RunEvent(runId, type, message, Instant.now());
    }
}
