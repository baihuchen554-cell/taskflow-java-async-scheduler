package com.example.taskflow.api;

import com.example.taskflow.config.SchedulerProperties;
import com.example.taskflow.repository.TaskRepository;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SystemController {
    private final TaskRepository repository;
    private final SchedulerProperties properties;

    public SystemController(TaskRepository repository, SchedulerProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    @GetMapping("/api/system")
    public Map<String, Object> system() {
        return Map.of("database", repository.databaseProduct(), "workers", properties.getWorkers(),
                "pollIntervalMs", properties.getPollIntervalMs(), "retryDelaySeconds", properties.getRetryDelaySeconds());
    }
}
