package com.example.taskflow.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** 集中管理调度参数；非法配置会在启动时报告，而不是运行中悄悄失效。 */
@Validated
@ConfigurationProperties(prefix = "scheduler")
public class SchedulerProperties {
    private boolean enabled = true;
    @Min(100) private long pollIntervalMs = 1000;
    @Min(1) @Max(3600) private int retryDelaySeconds = 5;
    @Min(1) @Max(32) private int workers = 4;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public long getPollIntervalMs() { return pollIntervalMs; }
    public void setPollIntervalMs(long pollIntervalMs) { this.pollIntervalMs = pollIntervalMs; }
    public int getRetryDelaySeconds() { return retryDelaySeconds; }
    public void setRetryDelaySeconds(int retryDelaySeconds) { this.retryDelaySeconds = retryDelaySeconds; }
    public int getWorkers() { return workers; }
    public void setWorkers(int workers) { this.workers = workers; }
}
