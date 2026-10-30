package com.example.taskflow.repository;

import com.example.taskflow.api.CreateTaskRequest;
import com.example.taskflow.model.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Repository;

/** SQL 显式写出，便于看懂参数绑定、条件更新和索引如何配合。 */
@Repository
public class TaskRepository {
    private final JdbcTemplate jdbc;
    private final SimpleJdbcInsert insertTask;

    public TaskRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.insertTask = new SimpleJdbcInsert(jdbc).withTableName("tasks")
                .usingGeneratedKeyColumns("id")
                .usingColumns("name", "type", "payload", "status", "max_retries", "attempts",
                        "retry_count", "delay_seconds", "fail_until_attempt", "created_at",
                        "scheduled_at", "next_run_at");
    }

    public long insert(CreateTaskRequest request, Instant now) {
        Instant scheduled = request.scheduledAt() == null ? now : request.scheduledAt();
        Map<String, Object> values = new java.util.HashMap<>();
        values.put("name", request.name().strip());
        values.put("type", request.type().name());
        values.put("payload", request.payload() == null ? "" : request.payload());
        values.put("status", TaskStatus.PENDING.name());
        values.put("max_retries", request.maxRetries() == null ? 2 : request.maxRetries());
        values.put("attempts", 0);
        values.put("retry_count", 0);
        values.put("delay_seconds", request.delaySeconds() == null ? 2 : request.delaySeconds());
        values.put("fail_until_attempt", request.failUntilAttempt() == null ? 0 : request.failUntilAttempt());
        values.put("created_at", sqlTime(now));
        values.put("scheduled_at", sqlTime(scheduled));
        values.put("next_run_at", sqlTime(scheduled));
        return insertTask.executeAndReturnKey(values).longValue();
    }

    public Optional<Task> find(long id) {
        return jdbc.query("SELECT * FROM tasks WHERE id = ?", TASK_MAPPER, id).stream().findFirst();
    }

    public TaskPage findPage(TaskStatus status, int page, int size) {
        String filter = status == null ? "" : " WHERE status = ?";
        Long total = status == null
                ? jdbc.queryForObject("SELECT COUNT(*) FROM tasks", Long.class)
                : jdbc.queryForObject("SELECT COUNT(*) FROM tasks" + filter, Long.class, status.name());
        String sql = "SELECT * FROM tasks" + filter + " ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?";
        long offset = (long) page * size;
        List<Task> tasks = status == null ? jdbc.query(sql, TASK_MAPPER, size, offset)
                : jdbc.query(sql, TASK_MAPPER, status.name(), size, offset);
        return new TaskPage(tasks, total == null ? 0 : total, page, size);
    }

    public TaskStats stats() {
        Map<TaskStatus, Long> counts = new EnumMap<>(TaskStatus.class);
        jdbc.query("SELECT status, COUNT(*) AS total FROM tasks GROUP BY status",
                (org.springframework.jdbc.core.RowCallbackHandler) row ->
                        counts.put(TaskStatus.valueOf(row.getString("status")), row.getLong("total")));
        return new TaskStats(counts.values().stream().mapToLong(Long::longValue).sum(),
                counts.getOrDefault(TaskStatus.PENDING, 0L), counts.getOrDefault(TaskStatus.RUNNING, 0L),
                counts.getOrDefault(TaskStatus.RETRY_WAIT, 0L), counts.getOrDefault(TaskStatus.SUCCESS, 0L),
                counts.getOrDefault(TaskStatus.FAILED, 0L), counts.getOrDefault(TaskStatus.CANCELLED, 0L));
    }

    public List<Long> findDueIds(Instant now, int limit) {
        return jdbc.queryForList("""
                SELECT id FROM tasks
                WHERE status IN ('PENDING', 'RETRY_WAIT') AND next_run_at <= ?
                ORDER BY next_run_at, id LIMIT ?
                """, Long.class, sqlTime(now), limit);
    }

    /** 类似 compare-and-set：只有状态仍符合条件的任务才会被抢占。 */
    public boolean claim(long id, Instant now) {
        return jdbc.update("""
                UPDATE tasks SET status = 'RUNNING', attempts = attempts + 1,
                    started_at = ?, finished_at = NULL
                WHERE id = ? AND status IN ('PENDING', 'RETRY_WAIT') AND next_run_at <= ?
                """, sqlTime(now), id, sqlTime(now)) == 1;
    }

    public void startLog(Task task) {
        jdbc.update("""
                INSERT INTO task_logs (task_id, attempt, status, started_at, message)
                VALUES (?, ?, 'RUNNING', ?, '开始执行任务')
                """, task.id(), task.attempts(), sqlTime(task.startedAt()));
    }

    public boolean completeSuccess(Task task, String result, Instant now) {
        return jdbc.update("""
                UPDATE tasks SET status = 'SUCCESS', result = ?, last_error = NULL,
                    finished_at = ? WHERE id = ? AND status = 'RUNNING' AND attempts = ?
                """, result, sqlTime(now), task.id(), task.attempts()) == 1;
    }

    public boolean completeFailure(Task task, String error, Instant now, Instant nextRunAt) {
        boolean retry = nextRunAt != null;
        return jdbc.update("""
                UPDATE tasks SET status = ?, last_error = ?, finished_at = ?, next_run_at = ?,
                    retry_count = retry_count + ?
                WHERE id = ? AND status = 'RUNNING' AND attempts = ?
                """, retry ? "RETRY_WAIT" : "FAILED", error, retry ? null : sqlTime(now),
                sqlTime(retry ? nextRunAt : now), retry ? 1 : 0,
                task.id(), task.attempts()) == 1;
    }

    public void finishLog(Task task, String status, String message, Instant now) {
        jdbc.update("""
                UPDATE task_logs SET status = ?, message = ?, finished_at = ?
                WHERE task_id = ? AND attempt = ? AND status = 'RUNNING'
                """, status, message, sqlTime(now), task.id(), task.attempts());
    }

    public boolean retry(long id, Instant now) {
        return jdbc.update("""
                UPDATE tasks SET status = 'PENDING', retry_count = 0, next_run_at = ?,
                    started_at = NULL, finished_at = NULL, result = NULL, last_error = NULL
                WHERE id = ? AND status = 'FAILED'
                """, sqlTime(now), id) == 1;
    }

    public boolean cancel(long id, Instant now) {
        return jdbc.update("""
                UPDATE tasks SET status = 'CANCELLED', finished_at = ?
                WHERE id = ? AND status IN ('PENDING', 'RETRY_WAIT')
                """, sqlTime(now), id) == 1;
    }

    public List<TaskLog> logs(long taskId) {
        return jdbc.query("SELECT * FROM task_logs WHERE task_id = ? ORDER BY attempt DESC, id DESC",
                (row, index) -> new TaskLog(row.getLong("id"), row.getLong("task_id"),
                        row.getInt("attempt"), row.getString("status"), instant(row, "started_at"),
                        instant(row, "finished_at"), row.getString("message")), taskId);
    }

    public void releaseRejected(Task task, Instant now) {
        int changed = jdbc.update("""
                UPDATE tasks SET status = 'RETRY_WAIT', attempts = attempts - 1,
                    started_at = NULL, next_run_at = ?
                WHERE id = ? AND status = 'RUNNING' AND attempts = ?
                """, sqlTime(now), task.id(), task.attempts());
        if (changed == 1) {
            jdbc.update("DELETE FROM task_logs WHERE task_id = ? AND attempt = ? AND status = 'RUNNING'",
                    task.id(), task.attempts());
        }
    }

    /** 仅用于单应用实例启动：上一次进程遗留的 RUNNING 不代表现在仍在执行。 */
    public int recoverInterrupted(Instant now) {
        jdbc.update("""
                UPDATE task_logs SET status = 'INTERRUPTED', finished_at = ?,
                    message = '应用退出导致执行中断，启动后重新排队'
                WHERE status = 'RUNNING' AND task_id IN (SELECT id FROM tasks WHERE status = 'RUNNING')
                """, sqlTime(now));
        return jdbc.update("""
                UPDATE tasks SET status = 'RETRY_WAIT', next_run_at = ?, finished_at = NULL,
                    last_error = '上次执行被中断，已重新排队'
                WHERE status = 'RUNNING'
                """, sqlTime(now));
    }

    public String databaseProduct() {
        return jdbc.execute((java.sql.Connection connection) -> connection.getMetaData().getDatabaseProductName());
    }

    // DATETIME 不带时区。明确按 UTC 写入/读取，避免 JVM 与数据库时区不同时延迟任务偏移。
    // DATETIME(6) 只保留微秒：主动截断纳秒，防止数据库四舍五入把“现在”写成未来。
    // 查询条件也走同一转换，使立即提交、立即抢占在同一个时钟刻度内保持一致。
    private static LocalDateTime sqlTime(Instant instant) {
        return LocalDateTime.ofInstant(instant.truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet row, String name) throws SQLException {
        LocalDateTime value = row.getObject(name, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private static final RowMapper<Task> TASK_MAPPER = (row, index) -> new Task(
            row.getLong("id"), row.getString("name"), TaskType.valueOf(row.getString("type")),
            row.getString("payload"), TaskStatus.valueOf(row.getString("status")),
            row.getInt("max_retries"), row.getInt("attempts"), row.getInt("retry_count"),
            row.getInt("delay_seconds"), row.getInt("fail_until_attempt"), row.getString("result"),
            row.getString("last_error"), instant(row, "created_at"), instant(row, "scheduled_at"),
            instant(row, "next_run_at"), instant(row, "started_at"), instant(row, "finished_at"));
}
