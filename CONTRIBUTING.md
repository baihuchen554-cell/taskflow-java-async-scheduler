# 开发与贡献

建议使用 JDK 21。修改前阅读 README 和 `docs/learning-guide.md`，理解单实例、至少一次执行的设计边界。

1. 从 `main` 新建功能分支，一次修改解决一个问题。
2. 在业务边界补充中文注释，解释设计原因，不必逐行翻译代码。
3. 修改状态流转、数据库更新或重试规则时，补充能复现问题的测试。
4. 运行 `./mvnw verify`（Windows 使用 `scripts/test.ps1`）和 `node --check src/main/resources/static/app.js`。
5. 涉及 SQL 的修改还应在 MySQL 8.4 下运行 `python scripts/smoke_test.py --require-mysql`。此脚本会创建演示任务，请连接开发数据库。
6. 提交前检查 diff，禁止上传 `.env`、`.local`、真实凭据、数据库数据文件和构建产物。

提交说明写清楚问题、解决方式及验证结果。API 字段或运行方式变化时同步更新说明文档。
