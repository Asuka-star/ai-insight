package com.aiinsight.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.UUID;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class RunNotFoundException extends RuntimeException {

    public RunNotFoundException(UUID runId) {
        super("Analysis run not found: " + runId);
    }
}
