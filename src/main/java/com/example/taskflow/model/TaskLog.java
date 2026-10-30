package com.example.taskflow.model;

import java.time.Instant;

public record TaskLog(long id, long taskId, int attempt, String status,
                      Instant startedAt, Instant finishedAt, String message) {
}
