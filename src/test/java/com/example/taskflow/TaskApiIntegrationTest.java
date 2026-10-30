package com.example.taskflow;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Tests use a real HTTP server, database, scanner and worker pool, without requiring MySQL. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:taskflow_api;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "scheduler.poll-interval-ms=100", "scheduler.retry-delay-seconds=1", "scheduler.workers=2"
})
class TaskApiIntegrationTest {
    @Autowired TestRestTemplate http;

    @Test
    void textTaskFinishesAndWritesAnExecutionLog() {
        long id = create(Map.of("name", "文本统计", "type", "TEXT_STATS", "payload", "hello world\nJava 异步"));
        JsonNode task = waitFor(id, "SUCCESS");
        assertThat(task.path("attempts").asInt()).isEqualTo(1);
        assertThat(task.path("result").asText()).contains("4");
        JsonNode logs = http.getForObject("/api/tasks/" + id + "/logs", JsonNode.class);
        assertThat(logs.size()).isEqualTo(1);
        assertThat(logs.get(0).path("status").asText()).isEqualTo("SUCCESS");
        assertThat(logs.get(0).path("finishedAt").isNull()).isFalse();
    }

    @Test
    void failureAutomaticallyRetriesThenSucceeds() {
        long id = create(Map.of("name", "重试后成功", "type", "DEMO_TASK", "payload", "demo",
                "delaySeconds", 0, "failUntilAttempt", 1, "maxRetries", 2));
        JsonNode task = waitFor(id, "SUCCESS");
        assertThat(task.path("attempts").asInt()).isEqualTo(2);
        JsonNode logs = http.getForObject("/api/tasks/" + id + "/logs", JsonNode.class);
        assertThat(logs.size()).isEqualTo(2);
        assertThat(logs.toString()).contains("FAILED", "SUCCESS");
    }

    @Test
    void retryBudgetIsFiniteAndManualRetryKeepsHistory() {
        long id = create(Map.of("name", "预算耗尽", "type", "DEMO_TASK", "payload", "demo",
                "delaySeconds", 0, "failUntilAttempt", 2, "maxRetries", 1));
        assertThat(waitFor(id, "FAILED").path("attempts").asInt()).isEqualTo(2);
        ResponseEntity<JsonNode> retry = http.postForEntity("/api/tasks/" + id + "/retry", null, JsonNode.class);
        assertThat(retry.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(waitFor(id, "SUCCESS").path("attempts").asInt()).isEqualTo(3);
        assertThat(http.getForObject("/api/tasks/" + id + "/logs", JsonNode.class).size()).isEqualTo(3);
        assertThat(http.postForEntity("/api/tasks/" + id + "/retry", null, JsonNode.class).getStatusCode().value()).isEqualTo(409);
    }

    @Test
    void delayedTaskCanBeCancelledBeforeAnyExecution() {
        long id = create(Map.of("name", "未来任务", "type", "TEXT_STATS", "payload", "later",
                "scheduledAt", Instant.now().plusSeconds(300).toString()));
        assertThat(get(id).path("status").asText()).isEqualTo("PENDING");
        assertThat(http.postForEntity("/api/tasks/" + id + "/cancel", null, JsonNode.class).getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(get(id).path("status").asText()).isEqualTo("CANCELLED");
        assertThat(get(id).path("attempts").asInt()).isZero();
        assertThat(http.getForObject("/api/tasks/" + id + "/logs", JsonNode.class).isEmpty()).isTrue();
        assertThat(http.postForEntity("/api/tasks/" + id + "/cancel", null, JsonNode.class).getStatusCode().value()).isEqualTo(409);
    }

    @Test
    void runningTaskCannotBeCancelledOrManuallyRetried() {
        long id = create(Map.of("name", "正在执行", "type", "DEMO_TASK", "payload", "busy", "delaySeconds", 2));
        waitFor(id, "RUNNING");
        assertThat(http.postForEntity("/api/tasks/" + id + "/cancel", null, JsonNode.class).getStatusCode().value()).isEqualTo(409);
        assertThat(http.postForEntity("/api/tasks/" + id + "/retry", null, JsonNode.class).getStatusCode().value()).isEqualTo(409);
        waitFor(id, "SUCCESS");
    }

    @Test
    void concurrentSubmissionsAreEachExecutedExactlyOnceDuringNormalOperation() {
        var requests = IntStream.range(0, 12).mapToObj(i -> CompletableFuture.supplyAsync(() ->
                create(Map.of("name", "并发-" + i, "type", "TEXT_STATS", "payload", "one two")))).toList();
        for (var request : requests) {
            long id = request.join();
            assertThat(waitFor(id, "SUCCESS").path("attempts").asInt()).isEqualTo(1);
            assertThat(http.getForObject("/api/tasks/" + id + "/logs", JsonNode.class).size()).isEqualTo(1);
        }
    }

    @Test
    void validationRejectsInvalidInputsWithReadableErrors() {
        for (Map<String, Object> input : java.util.List.<Map<String, Object>>of(
                Map.of("name", "", "type", "TEXT_STATS"),
                Map.of("name", "invalid type", "type", "SHELL"),
                Map.of("name", "invalid retries", "type", "DEMO_TASK", "maxRetries", 6),
                Map.of("name", "invalid delay", "type", "DEMO_TASK", "delaySeconds", -1),
                Map.of("name", "invalid payload", "type", "TEXT_STATS", "payload", "x".repeat(10001)),
                Map.of("name", "invalid date", "type", "DEMO_TASK", "scheduledAt", "yesterday"))) {
            var response = http.postForEntity("/api/tasks", input, JsonNode.class);
            assertThat(response.getStatusCode().value()).isEqualTo(400);
            assertThat(response.getBody().path("message").asText()).isNotBlank();
        }
        assertThat(http.getForEntity("/api/tasks?size=0", JsonNode.class).getStatusCode().value()).isEqualTo(400);
        assertThat(http.getForEntity("/api/tasks?status=UNKNOWN", JsonNode.class).getStatusCode().value()).isEqualTo(400);
        assertThat(http.getForEntity("/api/tasks/-1", JsonNode.class).getStatusCode().value()).isIn(400, 404);
        assertThat(http.getForEntity("/api/tasks/999999999", JsonNode.class).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void paginationFilterAndStatisticsUsePersistedRows() {
        long id = create(Map.of("name", "列表任务", "type", "TEXT_STATS", "payload", "list"));
        waitFor(id, "SUCCESS");
        JsonNode list = http.getForObject("/api/tasks?status=SUCCESS&page=0&size=2", JsonNode.class);
        assertThat(list.path("total").asLong()).isPositive();
        assertThat(list.path("items").size()).isBetween(1, 2);
        list.path("items").forEach(task -> assertThat(task.path("status").asText()).isEqualTo("SUCCESS"));
        JsonNode stats = http.getForObject("/api/tasks/stats", JsonNode.class);
        assertThat(stats.path("success").asLong()).isPositive();
        assertThat(stats.path("total").asLong()).isEqualTo(stats.path("pending").asLong() + stats.path("running").asLong()
                + stats.path("retryWait").asLong() + stats.path("success").asLong()
                + stats.path("failed").asLong() + stats.path("cancelled").asLong());
    }

    private long create(Map<String, ?> body) {
        ResponseEntity<JsonNode> response = http.postForEntity("/api/tasks", body, JsonNode.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).as("create: %s", response.getBody()).isTrue();
        return response.getBody().path("id").asLong();
    }

    private JsonNode get(long id) { return http.getForObject("/api/tasks/" + id, JsonNode.class); }

    private JsonNode waitFor(long id, String status) {
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(50))
                .untilAsserted(() -> assertThat(get(id).path("status").asText()).isEqualTo(status));
        return get(id);
    }
}
