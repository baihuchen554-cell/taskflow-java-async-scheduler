package com.example.taskflow.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.Instant;

/**
 * 一条数据库任务的只读快照。
 * attempts 是累计实际执行次数；retryCount 是本轮已安排的自动重试次数，不对前端暴露。
 */
public record Task(
        long id, String name, TaskType type, String payload, TaskStatus status,
        int maxRetries, int attempts, @JsonIgnore int retryCount,
        int delaySeconds, int failUntilAttempt,
        String result, String lastError, Instant createdAt, Instant scheduledAt,
        Instant nextRunAt, Instant startedAt, Instant finishedAt) {
}
