"""通过真实 HTTP 请求验证系统，零第三方依赖；会在目标库创建演示任务。"""
import argparse
import concurrent.futures
import datetime as dt
import json
import time
import urllib.error
import urllib.request

parser = argparse.ArgumentParser()
parser.add_argument("--base-url", default="http://127.0.0.1:8080")
parser.add_argument("--require-mysql", action="store_true")
args = parser.parse_args()
# 本地请求直接连接，避免开发机的全局代理干扰。
client = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def request(path, body=None):
    data = None if body is None else json.dumps(body).encode("utf-8")
    req = urllib.request.Request(args.base_url + "/api" + path, data=data,
                                 headers={"Content-Type": "application/json"})
    with client.open(req, timeout=10) as response:
        return json.load(response)


def wait_status(task_id, status, timeout=35):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        task = request(f"/tasks/{task_id}")
        if task["status"] == status:
            return task
        time.sleep(0.1)
    raise AssertionError(f"Task {task_id}: expected {status}, got {task['status']}")


for attempt in range(60):
    try:
        system = request("/system")
        break
    except (urllib.error.URLError, TimeoutError):
        time.sleep(1)
else:
    raise RuntimeError("Application did not become ready in 60 seconds")
if args.require_mysql:
    assert system["database"] == "MySQL", system

text = request("/tasks", {"name": "文本统计示例", "type": "TEXT_STATS", "payload": "hello world\nJava 异步"})
done = wait_status(text["id"], "SUCCESS")
assert json.loads(done["result"]) == {"characters": 19, "words": 4, "lines": 2}, done

retry = request("/tasks", {"name": "自动重试示例", "type": "DEMO_TASK", "delaySeconds": 0,
                           "failUntilAttempt": 1, "maxRetries": 2})
assert wait_status(retry["id"], "SUCCESS")["attempts"] == 2
assert len(request(f"/tasks/{retry['id']}/logs")) == 2

failed = request("/tasks", {"name": "手动重试示例", "type": "DEMO_TASK", "delaySeconds": 0,
                            "failUntilAttempt": 1, "maxRetries": 0})
assert wait_status(failed["id"], "FAILED")["attempts"] == 1
request(f"/tasks/{failed['id']}/retry", {})
assert wait_status(failed["id"], "SUCCESS")["attempts"] == 2

later = (dt.datetime.now(dt.timezone.utc) + dt.timedelta(minutes=5)).isoformat()
cancelled = request("/tasks", {"name": "延迟取消示例", "type": "TEXT_STATS", "scheduledAt": later})
request(f"/tasks/{cancelled['id']}/cancel", {})
assert request(f"/tasks/{cancelled['id']}")["status"] == "CANCELLED"
assert request(f"/tasks/{cancelled['id']}/logs") == []

def concurrent_task(number):
    item = request("/tasks", {"name": f"并发任务 {number}", "type": "TEXT_STATS", "payload": "concurrent test"})
    assert wait_status(item["id"], "SUCCESS")["attempts"] == 1
    assert len(request(f"/tasks/{item['id']}/logs")) == 1

with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
    list(pool.map(concurrent_task, range(8)))
print(json.dumps({"database": system["database"], "checks": "text, automatic/manual retry, delayed cancellation, concurrent execution", "result": "PASS"}))
