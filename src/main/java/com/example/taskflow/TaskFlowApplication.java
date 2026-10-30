package com.example.taskflow;

import com.example.taskflow.config.SchedulerProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(SchedulerProperties.class)
public class TaskFlowApplication {
    public static void main(String[] args) {
        SpringApplication.run(TaskFlowApplication.class, args);
    }
}
