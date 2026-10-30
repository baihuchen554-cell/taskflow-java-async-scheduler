package com.example.taskflow.api;

import com.example.taskflow.model.TaskType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/** 使用 record 保存请求数据，类似 Python 中不可变的 dataclass。 */
public record CreateTaskRequest(
        @NotBlank(message = "任务名称不能为空")
        @Size(max = 100, message = "任务名称最多 100 个字符") String name,
        @NotNull(message = "请选择任务类型") TaskType type,
        @Size(max = 10000, message = "任务内容最多 10000 个字符") String payload,
        Instant scheduledAt,
        @Min(value = 0, message = "重试次数不能小于 0")
        @Max(value = 5, message = "重试次数不能超过 5") Integer maxRetries,
        @Min(value = 0, message = "模拟耗时不能小于 0 秒")
        @Max(value = 10, message = "模拟耗时不能超过 10 秒") Integer delaySeconds,
        @Min(value = 0, message = "失败次数不能小于 0")
        @Max(value = 5, message = "失败次数不能超过 5") Integer failUntilAttempt) {
}
