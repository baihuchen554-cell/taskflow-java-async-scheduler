package com.example.taskflow.service;

import com.example.taskflow.model.Task;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 任务执行逻辑。新增类型时，在枚举和这里增加一个分支即可。 */
@Component
public class TaskHandler {
    private final ObjectMapper objectMapper;

    public TaskHandler(ObjectMapper objectMapper) { this.objectMapper = objectMapper; }

    public String execute(Task task) throws InterruptedException, JsonProcessingException {
        return switch (task.type()) {
            case TEXT_STATS -> textStats(task.payload());
            case DEMO_TASK -> demoTask(task);
        };
    }

    private String textStats(String text) throws JsonProcessingException {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("characters", text.codePointCount(0, text.length()));
        // 这里的 words 是按空白分隔的片段数，不是中文自然语言分词。
        result.put("words", text.isBlank() ? 0 : text.strip().split("\\s+").length);
        result.put("lines", text.isEmpty() ? 0 : text.split("\\R", -1).length);
        return objectMapper.writeValueAsString(result);
    }

    private String demoTask(Task task) throws InterruptedException, JsonProcessingException {
        Thread.sleep(task.delaySeconds() * 1000L);
        if (task.attempts() <= task.failUntilAttempt()) {
            throw new IllegalStateException("模拟失败：第 " + task.attempts() + " 次执行，前 "
                    + task.failUntilAttempt() + " 次会失败");
        }
        return objectMapper.writeValueAsString(Map.of("message", "模拟任务执行完成", "attempt", task.attempts(),
                "payload", task.payload(), "delaySeconds", task.delaySeconds()));
    }
}
