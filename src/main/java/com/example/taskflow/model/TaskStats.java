package com.example.taskflow.model;

public record TaskStats(long total, long pending, long running, long retryWait,
                        long success, long failed, long cancelled) {
}
