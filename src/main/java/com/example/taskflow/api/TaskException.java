package com.example.taskflow.api;

import org.springframework.http.HttpStatus;

public class TaskException extends RuntimeException {
    private final HttpStatus status;

    public TaskException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() { return status; }
}
