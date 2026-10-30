package com.example.taskflow.service;

import com.example.taskflow.api.CreateTaskRequest;
import com.example.taskflow.api.TaskException;
import com.example.taskflow.config.SchedulerProperties;
import com.example.taskflow.model.*;
import com.example.taskflow.repository.TaskRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 业务状态机和事务边界。
 * 一个公开方法对应一段需要原子提交的状态变化；慢任务放在 TaskWorker 的事务外执行。
 */
@Service
public class TaskService {
    private final TaskRepository repository;
    private final SchedulerProperties properties;
    private final Clock clock;

    public TaskService(TaskRepository repository, SchedulerProperties properties, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public Task create(CreateTaskRequest request) {
        // MySQL DATETIME 的合法年份是 1000..9999；统一限制，避免 H2 和 MySQL 行为不同。
        if (request.scheduledAt() != null && (request.scheduledAt().isBefore(Instant.parse("1000-01-01T00:00:00Z"))
                || !request.scheduledAt().isBefore(Instant.parse("9999-12-31T23:59:59Z")))) {
            throw new TaskException(HttpStatus.BAD_REQUEST, "计划时间必须在公元 1000 到 9999 年之间");
        }
        return get(repository.insert(request, clock.instant()));
    }

    public Task get(long id) {
        return repository.find(id).orElseThrow(() -> new TaskException(HttpStatus.NOT_FOUND, "任务不存在"));
    }

    public TaskPage list(TaskStatus status, int page, int size) {
        return repository.findPage(status, page, size);
    }

    public TaskStats stats() { return repository.stats(); }

    public List<TaskLog> logs(long id) {
        get(id);
        return repository.logs(id);
    }

    @Transactional
    public Task retry(long id) {
        // 累计 attempts 和历史日志保留，只重置当前轮次的自动重试预算。
        get(id);
        if (!repository.retry(id, clock.instant())) {
            throw new TaskException(HttpStatus.CONFLICT, "只有失败的任务可以手动重试");
        }
        return get(id);
    }

    @Transactional
    public Task cancel(long id) {
        get(id);
        if (!repository.cancel(id, clock.instant())) {
            throw new TaskException(HttpStatus.CONFLICT, "只有待执行或等待重试的任务可以取消");
        }
        return get(id);
    }

    public List<Long> dueIds(int limit) {
        return repository.findDueIds(clock.instant(), limit);
    }

    @Transactional
    public Optional<Task> claim(long id) {
        // 更新状态与插入 RUNNING 日志必须处于同一个事务，任何一步失败都会整体回滚。
        if (!repository.claim(id, clock.instant())) return Optional.empty();
        Task task = get(id);
        repository.startLog(task);
        return Optional.of(task);
    }

    @Transactional
    public void succeed(Task task, String result) {
        Instant now = clock.instant();
        // SQL 同时检查 RUNNING 和 attempt 编号，防止旧执行结果覆盖恢复后的新一次执行。
        if (repository.completeSuccess(task, result, now)) {
            repository.finishLog(task, "SUCCESS", "执行成功", now);
        }
    }

    @Transactional
    public void fail(Task task, String error) {
        Instant now = clock.instant();
        String message = error == null ? "任务执行失败" : error.substring(0, Math.min(error.length(), 2000));
        // maxRetries 是“初次执行之外”的重试次数；用完预算后进入 FAILED，等待人工操作。
        Instant next = task.retryCount() < task.maxRetries() ? now.plusSeconds(properties.getRetryDelaySeconds()) : null;
        if (repository.completeFailure(task, message, now, next)) {
            repository.finishLog(task, "FAILED", message, now);
        }
    }

    @Transactional
    public void releaseRejected(Task task) {
        repository.releaseRejected(task, clock.instant());
    }

    @Transactional
    public int recoverInterrupted() {
        // 单实例启动恢复不消耗自动重试预算；旧日志关闭，下一次抢占使用新的累计 attempt。
        return repository.recoverInterrupted(clock.instant());
    }
}
