package com.aiinsight.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.BAD_REQUEST)
public class DocumentIngestionException extends RuntimeException {

    public DocumentIngestionException(String message) {
        super(message);
    }

    public DocumentIngestionException(String message, Throwable cause) {
        super(message, cause);
    }
}
