package com.example.taskflow.api;

import com.example.taskflow.model.*;
import com.example.taskflow.service.TaskService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** HTTP 入口只负责参数校验和返回结果，状态变化交给 TaskService。 */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {
    private final TaskService service;

    public TaskController(TaskService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<Task> create(@Valid @RequestBody CreateTaskRequest request) {
        Task task = service.create(request);
        return ResponseEntity.created(URI.create("/api/tasks/" + task.id())).body(task);
    }

    @GetMapping
    public TaskPage list(@RequestParam(required = false) TaskStatus status,
                         @RequestParam(defaultValue = "0") @Min(0) @Max(1000000) int page,
                         @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size) {
        return service.list(status, page, size);
    }

    @GetMapping("/stats")
    public TaskStats stats() { return service.stats(); }

    @GetMapping("/{id}")
    public Task get(@PathVariable long id) { return service.get(id); }

    @GetMapping("/{id}/logs")
    public List<TaskLog> logs(@PathVariable long id) { return service.logs(id); }

    @PostMapping("/{id}/retry")
    public Task retry(@PathVariable long id) { return service.retry(id); }

    @PostMapping("/{id}/cancel")
    public Task cancel(@PathVariable long id) { return service.cancel(id); }
}
