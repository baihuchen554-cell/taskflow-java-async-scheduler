package com.example.taskflow.service;

import com.example.taskflow.model.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class TaskWorker {
    private static final Logger log = LoggerFactory.getLogger(TaskWorker.class);
    private final TaskHandler handler;
    private final TaskService service;

    public TaskWorker(TaskHandler handler, TaskService service) {
        this.handler = handler;
        this.service = service;
    }

    public void execute(Task task) {
        final String result;
        try {
            // 慢操作在事务外执行，避免整个执行期间占用数据库连接和行锁。
            result = handler.execute(task);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            service.fail(task, "执行线程被中断");
            return;
        } catch (Exception exception) {
            service.fail(task, exception.getMessage());
            return;
        }
        // 持久化失败与业务失败分开：数据库异常不能伪装成业务重试。
        // 留下 RUNNING 记录并报告错误，恢复数据库后重启应用可重新排队。
        try {
            service.succeed(task, result);
        } catch (RuntimeException exception) {
            log.error("任务 {} 已执行，但结果写入失败；恢复数据库后重启应用以恢复任务", task.id(), exception);
        }
    }
}
