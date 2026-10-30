package com.example.taskflow.model;

import java.util.List;

public record TaskPage(List<Task> items, long total, int page, int size) {
}
