package com.aiinsight.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.UUID;

@ResponseStatus(HttpStatus.CONFLICT)
public class InvalidRunStateException extends RuntimeException {

    public InvalidRunStateException(UUID runId, String message) {
        super("Analysis run " + runId + " is not in a valid state: " + message);
    }
}
