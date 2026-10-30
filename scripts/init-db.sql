-- Run with your existing MySQL administrator account, after changing the example password.
CREATE DATABASE IF NOT EXISTS taskflow CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS 'taskflow'@'localhost' IDENTIFIED BY 'local_taskflow_2026';
GRANT ALL PRIVILEGES ON taskflow.* TO 'taskflow'@'localhost';
