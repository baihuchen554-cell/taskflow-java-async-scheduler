package com.example.taskflow;

import com.example.taskflow.api.CreateTaskRequest;
import com.example.taskflow.model.Task;
import com.example.taskflow.model.TaskStatus;
import com.example.taskflow.model.TaskType;
import com.example.taskflow.service.TaskService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.datasource.url=jdbc:h2:mem:taskflow_persistence;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=", "scheduler.enabled=false"
})
class SchedulerPersistenceTest {
    @Autowired TaskService service;

    // 固定纳秒尾数为 789：数据库仅存微秒，立即 claim 不能因四舍五入而失败。
    @TestConfiguration
    static class PreciseClock {
        @Bean @Primary
        Clock regressionClock() { return Clock.fixed(Instant.parse("2026-01-01T00:00:00.123456789Z"), ZoneOffset.UTC); }
    }

    @Test
    void conditionalClaimAllowsOnlyOneConcurrentWinner() {
        Task task = create(null);
        var contenders = IntStream.range(0, 16).mapToObj(i ->
                CompletableFuture.supplyAsync(() -> service.claim(task.id()).isPresent())).toList();
        assertThat(contenders.stream().map(CompletableFuture::join).filter(Boolean::booleanValue).count()).isEqualTo(1);
        assertThat(service.get(task.id()).attempts()).isEqualTo(1);
        assertThat(service.logs(task.id())).hasSize(1);
        service.succeed(service.get(task.id()), "{}");
    }

    @Test
    void crashRecoveryClosesOldAttemptAndFencesItsLateResult() {
        Task task = create(null);
        Task oldAttempt = service.claim(task.id()).orElseThrow();
        assertThat(service.recoverInterrupted()).isEqualTo(1);
        assertThat(service.get(task.id()).status()).isEqualTo(TaskStatus.RETRY_WAIT);
        assertThat(service.logs(task.id()).get(0).status()).isEqualTo("INTERRUPTED");
        Task newAttempt = service.claim(task.id()).orElseThrow();
        service.succeed(oldAttempt, "stale result");
        assertThat(service.get(task.id()).status()).isEqualTo(TaskStatus.RUNNING);
        service.succeed(newAttempt, "new result");
        assertThat(service.get(task.id()).result()).isEqualTo("new result");
        assertThat(service.get(task.id()).attempts()).isEqualTo(2);
        assertThat(service.logs(task.id())).hasSize(2);
        assertThat(service.recoverInterrupted()).isZero();
    }

    @Test
    void rejectedSubmissionDoesNotConsumeAnExecutionAttempt() {
        Task task = create(null);
        Task rejected = service.claim(task.id()).orElseThrow();
        service.releaseRejected(rejected);
        assertThat(service.get(task.id()).attempts()).isZero();
        assertThat(service.logs(task.id())).isEmpty();
        Task accepted = service.claim(task.id()).orElseThrow();
        assertThat(accepted.attempts()).isEqualTo(1);
        service.succeed(accepted, "{}");
    }

    @Test
    void futureAndCancelledTasksCannotBeClaimed() {
        Task future = create(Instant.now().plusSeconds(300));
        assertThat(service.claim(future.id())).isEmpty();
        Task cancelled = create(null);
        service.cancel(cancelled.id());
        assertThat(service.claim(cancelled.id())).isEmpty();
        assertThat(service.logs(cancelled.id())).isEmpty();
    }

    private Task create(Instant scheduled) {
        return service.create(new CreateTaskRequest("持久化测试", TaskType.TEXT_STATS, "test", scheduled, 2, 0, 0));
    }
}
