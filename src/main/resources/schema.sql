-- 使用 MySQL 与 H2 MySQL 模式均可运行的 DDL。
-- DATETIME(6) 避免 TIMESTAMP 在部分 MySQL 版本中的 2038 年限制。
CREATE TABLE IF NOT EXISTS tasks (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    type VARCHAR(32) NOT NULL,
    payload LONGTEXT NOT NULL,
    status VARCHAR(20) NOT NULL,
    max_retries INT NOT NULL DEFAULT 2,
    attempts INT NOT NULL DEFAULT 0,
    retry_count INT NOT NULL DEFAULT 0,
    delay_seconds INT NOT NULL DEFAULT 2,
    fail_until_attempt INT NOT NULL DEFAULT 0,
    result LONGTEXT,
    last_error VARCHAR(2000),
    created_at DATETIME(6) NOT NULL,
    scheduled_at DATETIME(6) NOT NULL,
    next_run_at DATETIME(6) NOT NULL,
    started_at DATETIME(6),
    finished_at DATETIME(6),
    INDEX idx_tasks_due (status, next_run_at, id),
    INDEX idx_tasks_created (created_at, id)
);

CREATE TABLE IF NOT EXISTS task_logs (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    attempt INT NOT NULL,
    status VARCHAR(20) NOT NULL,
    started_at DATETIME(6) NOT NULL,
    finished_at DATETIME(6),
    message VARCHAR(2000),
    CONSTRAINT fk_task_logs_task FOREIGN KEY (task_id) REFERENCES tasks(id),
    CONSTRAINT uk_task_log_attempt UNIQUE (task_id, attempt)
);
