package com.example.taskflow.service;

import com.example.taskflow.config.SchedulerProperties;
import com.example.taskflow.model.Task;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

/** 调度器只负责“找任务并交给线程池”，具体执行逻辑在 TaskHandler。 */
@Component
public class TaskDispatcher {
    private static final Logger log = LoggerFactory.getLogger(TaskDispatcher.class);
    private final TaskService service;
    private final TaskWorker worker;
    private final ThreadPoolTaskExecutor executor;
    private final SchedulerProperties properties;
    // 信号量表示“可用执行席位”，不是任务队列；待执行内容始终留在数据库中。
    private final Semaphore slots;
    private final AtomicBoolean ready = new AtomicBoolean(false);

    public TaskDispatcher(TaskService service, TaskWorker worker, ThreadPoolTaskExecutor executor,
                          SchedulerProperties properties) {
        this.service = service;
        this.worker = worker;
        this.executor = executor;
        this.properties = properties;
        this.slots = new Semaphore(properties.getWorkers());
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        // 必须先处理上次进程留下的 RUNNING，再开放扫描，避免把本次执行误当成遗留任务。
        if (properties.isEnabled()) {
            int recovered = service.recoverInterrupted();
            if (recovered > 0) log.warn("已恢复 {} 个上次运行中断的任务", recovered);
        }
        ready.set(true);
    }

    @Scheduled(fixedDelayString = "${scheduler.poll-interval-ms:1000}")
    public void dispatch() {
        if (!properties.isEnabled() || !ready.get()) return;
        int available = slots.availablePermits();
        if (available == 0) return;
        for (long id : service.dueIds(available)) {
            if (!slots.tryAcquire()) break;
            Optional<Task> claimed;
            try {
                claimed = service.claim(id);
            } catch (RuntimeException exception) {
                slots.release();
                throw exception;
            }
            if (claimed.isEmpty()) {
                slots.release();
                continue;
            }
            Task task = claimed.get();
            try {
                executor.execute(() -> {
                    try {
                        worker.execute(task);
                    } catch (RuntimeException exception) {
                        log.error("任务 {} 状态保存失败；数据库恢复后重启可重新排队", task.id(), exception);
                    } finally {
                        slots.release();
                    }
                });
            } catch (TaskRejectedException exception) {
                // 线程池正关闭或短暂繁忙时，归还数据库任务，不消耗执行次数。
                // 即使有信号量也必须处理拒绝：工作线程归还席位和线程池恢复空闲并非原子操作。
                try {
                    service.releaseRejected(task);
                } finally {
                    slots.release();
                }
            }
        }
    }
}
