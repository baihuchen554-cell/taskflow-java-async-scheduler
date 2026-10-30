# HTTP 接口说明

服务默认地址：`http://localhost:8080`。请求体使用 `Content-Type: application/json`，时间使用带时区的 ISO 8601 字符串，例如 `2026-10-29T08:30:00Z`。页面会将时间转换成本地显示。

## 接口一览

| 方法 | 路径 | 作用 |
| --- | --- | --- |
| `POST` | `/api/tasks` | 创建任务 |
| `GET` | `/api/tasks` | 分页查询，可按状态筛选 |
| `GET` | `/api/tasks/{id}` | 查询任务详情 |
| `GET` | `/api/tasks/{id}/logs` | 查询每次执行记录 |
| `GET` | `/api/tasks/stats` | 查询所有任务的状态统计 |
| `POST` | `/api/tasks/{id}/retry` | 手动重试失败任务 |
| `POST` | `/api/tasks/{id}/cancel` | 取消尚未运行的任务 |
| `GET` | `/api/system` | 查询数据库类型与调度配置 |

## 创建任务

```json
{
  "name": "统计一段文本",
  "type": "TEXT_STATS",
  "payload": "Hello Java\nHello async",
  "maxRetries": 2
}
```

| 参数 | 规则 |
| --- | --- |
| `name` | 必填，非空，最多 100 个字符 |
| `type` | 必填，`TEXT_STATS` 或 `DEMO_TASK` |
| `payload` | 可选字符串，最多 10000 个字符；省略或 `null` 视为空字符串 |
| `scheduledAt` | 可选，计划执行时间；省略时立即进入待执行状态；已过去的时间在下次扫描即可执行 |
| `maxRetries` | 可选，`0..5`，默认 `2`；不包含第一次执行 |
| `delaySeconds` | 可选，`0..10`，默认 `2`；演示任务等待秒数 |
| `failUntilAttempt` | 可选，`0..5`，默认 `0`；累计执行次数不大于该值时，演示任务故意失败 |

创建成功返回 HTTP `201` 和任务对象，其中 `id` 用于后续查询。任务可能需要等待下一个扫描周期才开始执行，因此“立即任务”不表示 HTTP 返回前已经执行完成。未知 JSON 字段、数字形式的枚举和用于整数参数的小数会被拒绝。

PowerShell 示例：

```powershell
$base = 'http://localhost:8080'
$body = @{
    name = '重试演示'
    type = 'DEMO_TASK'
    payload = '第 1 次失败，第 2 次成功'
    maxRetries = 2
    delaySeconds = 2
    failUntilAttempt = 1
} | ConvertTo-Json

$task = Invoke-RestMethod "$base/api/tasks" -Method Post `
    -ContentType 'application/json; charset=utf-8' `
    -Body ([System.Text.Encoding]::UTF8.GetBytes($body))

$task
Invoke-RestMethod "$base/api/tasks/$($task.id)"
Invoke-RestMethod "$base/api/tasks/$($task.id)/logs"
```

创建一分钟后执行的任务：

```powershell
$body = @{
    name = '一分钟后统计'
    type = 'TEXT_STATS'
    payload = 'Hello async'
    scheduledAt = [DateTime]::UtcNow.AddMinutes(1).ToString('o')
} | ConvertTo-Json

$task = Invoke-RestMethod "$base/api/tasks" -Method Post `
    -ContentType 'application/json; charset=utf-8' `
    -Body ([System.Text.Encoding]::UTF8.GetBytes($body))
```

## 任务对象

| 字段 | 含义 |
| --- | --- |
| `id` / `name` / `type` / `payload` | 编号、名称、类型和输入内容 |
| `status` | 当前任务状态 |
| `maxRetries` | 一轮执行允许的额外自动重试次数 |
| `attempts` | 累计实际执行次数，包括手动重试后的执行 |
| `delaySeconds` / `failUntilAttempt` | 演示任务配置 |
| `result` | 成功结果，以 JSON 字符串保存；未成功时可能为 `null` |
| `lastError` | 最近的错误说明；没有错误时为 `null` |
| `createdAt` | 创建时间 |
| `scheduledAt` | 原始计划执行时间 |
| `nextRunAt` | 下一次可被扫描领取的时间 |
| `startedAt` / `finishedAt` | 执行开始和结束时间；未发生时为 `null` |

## 状态和操作限制

| 状态 | 页面含义 | 可执行操作 |
| --- | --- | --- |
| `PENDING` | 等待执行 | 查询、取消 |
| `RUNNING` | 执行中 | 查询 |
| `RETRY_WAIT` | 等待重试 | 查询、取消 |
| `SUCCESS` | 已成功 | 查询 |
| `FAILED` | 已失败 | 查询、手动重试 |
| `CANCELLED` | 已取消 | 查询 |

```powershell
Invoke-RestMethod "$base/api/tasks/$($task.id)/cancel" -Method Post
# 仅 FAILED 任务允许以下操作：
Invoke-RestMethod "$base/api/tasks/$($task.id)/retry" -Method Post
```

手动重试保留原来的任务编号、累计尝试次数和历史记录，重新开放这一轮的自动重试预算。

取消与重试是两种独立操作：已取消任务不能直接重试；重试请求应使用另一个处于 `FAILED` 状态的任务编号。

## 列表、统计和执行记录

```text
GET /api/tasks?page=0&size=10
GET /api/tasks?status=FAILED&page=0&size=10
```

`page` 从 `0` 开始。列表返回 `{ "items": [...], "total": 0, "page": 0, "size": 10 }`；`total` 是当前筛选条件匹配的任务总数。

`page` 范围为 `0..1000000`，`size` 范围为 `1..100`，默认 `10`。列表按创建时间和编号倒序排列。

统计接口返回 `total`、`pending`、`running`、`retryWait`、`success`、`failed`、`cancelled`。统计针对整个任务库，不随列表筛选条件改变。

执行记录接口返回数组，最新尝试在前。每条记录包含 `id`、`taskId`、`attempt`、`status`、`startedAt`、`finishedAt` 和 `message`，用于分辨同一个任务的不同尝试。记录状态有 `RUNNING`、`SUCCESS`、`FAILED` 和 `INTERRUPTED`；其中 `INTERRUPTED` 表示应用重启时识别到上次未完成的尝试。

系统接口返回 `database`、`workers`、`pollIntervalMs` 和 `retryDelaySeconds`。用它可以确认当前运行的是 MySQL 还是 H2 演示模式。

文本统计的 `result` 示例为字符串 `"{\"characters\":11,\"words\":2,\"lines\":1}"`，对应输入 `Hello async`。演示任务成功结果包含 `message`、`attempt`、`payload` 和 `delaySeconds`。调用方需要时可将 `result` 再解析为 JSON 对象。

## 错误响应

错误体提供 `message` 字段，说明失败原因。

| HTTP 状态码 | 常见原因 |
| --- | --- |
| `400` | 参数非法、未知任务类型、状态或无法解析的请求体 |
| `404` | 任务编号不存在 |
| `405` | 请求方法错误，例如对仅支持 POST 的操作发送 GET |
| `409` | 状态不允许当前操作，例如取消运行中的任务或重试成功任务 |
| `415` | 请求体的 Content-Type 不是支持的 JSON 格式 |
| `500` | 服务端异常，需要查看应用日志 |

轮询任务状态时，请使用合理间隔。本项目页面约每 3 秒刷新一次，没有必要每几十毫秒发送请求。
