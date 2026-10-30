"use strict";

// 原生 JavaScript 页面：没有构建工具，Spring Boot 会直接提供这三个静态文件。
const $ = (selector) => document.querySelector(selector);
const statusNames = {
  PENDING: "等待中",
  RUNNING: "执行中",
  RETRY_WAIT: "重试等待",
  SUCCESS: "已完成",
  FAILED: "失败",
  CANCELLED: "已取消",
  INTERRUPTED: "执行中断",
};
const state = {
  status: "",
  page: 0,
  size: 5,
  total: 0,
  request: 0,
  controller: null,
  detailId: null,
  detailRequest: 0,
  detailController: null,
  submitting: false,
};
const pendingActions = new Set();
let toastTimer;
// 接口返回的数据不能直接当 HTML 使用，统一转义后再渲染，防止脚本注入。
const escapeHtml = (value) =>
  String(value ?? "").replace(
    /[&<>"']/g,
    (character) =>
      ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[
        character
      ],
  );
const formatDate = (value) => {
  if (!value) return "—";
  const date = new Date(value);
  return Number.isNaN(date.getTime())
    ? "—"
    : date
        .toLocaleString("zh-CN", {
          year: "numeric",
          month: "2-digit",
          day: "2-digit",
          hour: "2-digit",
          minute: "2-digit",
          second: "2-digit",
          hour12: false,
        })
        .replaceAll("/", "-");
};
const statusHtml = (status) =>
  `<span class="status status-${escapeHtml(String(status).toLowerCase())}">${escapeHtml(statusNames[status] || status)}</span>`;
const typeHtml = (type) =>
  `<span class="type-label${type === "DEMO_TASK" ? " demo" : ""}">${escapeHtml(type)}</span>`;

// 所有接口请求经过这里：超时、取消和服务端错误以统一方式处理。
async function api(path, options = {}) {
  const controller = new AbortController();
  const abort = () => controller.abort();
  let timedOut = false;
  if (options.signal?.aborted) abort();
  options.signal?.addEventListener("abort", abort, { once: true });
  const timeout = setTimeout(() => {
    timedOut = true;
    controller.abort();
  }, 15000);
  try {
    const response = await fetch(`/api${path}`, {
      headers: { "Content-Type": "application/json" },
      ...options,
      signal: controller.signal,
    });
    const data = await response.json();
    if (!response.ok)
      throw new Error(
        data?.message || `请求失败（HTTP ${response.status}），请稍后重试。`,
      );
    if (data === null) throw new Error("服务返回了空数据，请刷新后重试。");
    return data;
  } catch (error) {
    if (timedOut)
      throw new Error(
        options.method === "POST"
          ? "请求超时，操作可能已完成。请刷新列表确认后再操作。"
          : "请求超时，请检查服务后重试。",
      );
    if (error.name === "AbortError") throw error;
    if (error instanceof TypeError)
      throw new Error("无法连接服务，请检查应用是否已启动。");
    if (error instanceof SyntaxError)
      throw new Error("服务返回的数据格式异常，请检查应用日志。");
    throw error;
  } finally {
    clearTimeout(timeout);
    options.signal?.removeEventListener("abort", abort);
  }
}

function showError(selector, message) {
  const element = $(selector);
  element.textContent = message || "";
  element.hidden = !message;
}

function toast(message) {
  clearTimeout(toastTimer);
  $("#toast").textContent = message;
  $("#toast").hidden = false;
  toastTimer = setTimeout(() => {
    $("#toast").hidden = true;
  }, 4000);
}

// 操作按钮取决于服务端状态；最终能否取消/重试仍由后端原子更新决定。
function rowActions(task, includeView = true) {
  const id = escapeHtml(task.id);
  const disabled = pendingActions.has(String(task.id)) ? " disabled" : "";
  const buttons = includeView
    ? [
        `<button class="row-action" type="button" data-action="view" data-id="${id}" aria-label="查看任务 ${id} 详情">查看</button>`,
      ]
    : [];
  if (["PENDING", "RETRY_WAIT"].includes(task.status))
    buttons.push(
      `<button class="row-action cancel" type="button" data-action="cancel" data-id="${id}" aria-label="取消任务 ${id}"${disabled}>取消</button>`,
    );
  if (task.status === "FAILED")
    buttons.push(
      `<button class="row-action" type="button" data-action="retry" data-id="${id}" aria-label="重试任务 ${id}"${disabled}>重试</button>`,
    );
  return buttons.join("");
}

// 自动刷新时保留按钮焦点；内容未改变时不重新生成 DOM。
function updateContent(element, html) {
  if (element._lastHtml === html) return;
  const active = element.contains(document.activeElement)
    ? document.activeElement
    : null;
  const focus = active?.dataset.action
    ? { action: active.dataset.action, id: active.dataset.id }
    : null;
  element.innerHTML = html;
  element._lastHtml = html;
  if (focus) {
    const replacement = Array.from(
      element.querySelectorAll("[data-action]"),
    ).find(
      (button) =>
        button.dataset.action === focus.action &&
        button.dataset.id === focus.id,
    );
    replacement?.focus({ preventScroll: true });
  }
}

function renderTasks(data) {
  state.total = data.total;
  updateContent(
    $("#task-rows"),
    data.items.length
      ? data.items
          .map(
            (task) => `<tr>
    <td><span class="task-name" title="${escapeHtml(task.name)}">${escapeHtml(task.name)}</span><span class="task-id">#${escapeHtml(task.id)}</span></td>
    <td>${typeHtml(task.type)}</td><td>${statusHtml(task.status)}</td><td>${escapeHtml(task.attempts)}</td>
    <td class="row-date">${escapeHtml(formatDate(task.createdAt))}</td><td><div class="actions">${rowActions(task)}</div></td>
  </tr>`,
          )
          .join("")
      : `<tr><td colspan="6"><div class="empty-state"><p>${state.status ? "没有这个状态的任务" : "还没有任务"}</p><span>${state.status ? "试试其他筛选条件，或创建一个新任务。" : "创建第一个任务，看看异步调度如何运行。"}</span><button class="text-button" type="button" data-action="new">新建任务</button></div></td></tr>`,
  );
  $("#total-label").textContent = `共 ${data.total} 个任务`;
  const pages = Math.max(1, Math.ceil(data.total / state.size));
  $("#page-number").textContent = String(state.page + 1);
  $("#page-count").textContent = `/ ${pages}`;
  $("#previous-page").disabled = state.page === 0;
  $("#next-page").disabled = state.page + 1 >= pages;
}

// 切换筛选/页码时中止旧请求，并用请求序号避免旧响应覆盖新页面。
async function refresh(force = false) {
  if (state.controller && !force) return;
  state.controller?.abort();
  const controller = new AbortController();
  state.controller = controller;
  const request = ++state.request;
  const query = new URLSearchParams({ page: state.page, size: state.size });
  if (state.status) query.set("status", state.status);
  $("#refresh").setAttribute("aria-busy", "true");
  try {
    const [data, stats, system] = await Promise.all([
      api(`/tasks?${query}`, { signal: controller.signal }),
      api("/tasks/stats", { signal: controller.signal }),
      api("/system", { signal: controller.signal }),
    ]);
    if (request !== state.request) return;
    const lastPage = Math.max(0, Math.ceil(data.total / state.size) - 1);
    if (state.page > lastPage) {
      state.page = lastPage;
      refresh(true);
      return;
    }
    renderTasks(data);
    for (const name of ["total", "running", "success", "failed"])
      $(`#stat-${name}`).textContent = stats[name];
    $("#connection-dot").className = "connection-dot connected";
    $("#connection-label").textContent = "数据库连接正常";
    $("#system-meta").textContent =
      `${system.database} · ${system.workers} 个工作线程`;
    $("#last-updated").textContent =
      `更新于 ${new Date().toLocaleTimeString("zh-CN", { hour12: false })}`;
    showError("#page-error", "");
  } catch (error) {
    if (error.name === "AbortError" || request !== state.request) return;
    showError("#page-error", `${error.message} 页面数据可能不是最新状态。`);
    $("#connection-dot").className = "connection-dot disconnected";
    $("#connection-label").textContent = "服务连接异常";
    $("#system-meta").textContent = "请检查应用与数据库";
    if (!$("#task-rows .task-name"))
      updateContent(
        $("#task-rows"),
        '<tr><td colspan="6"><div class="empty-state"><p>暂时无法读取任务</p><span>恢复服务后，点击右上方刷新按钮重试。</span></div></td></tr>',
      );
  } finally {
    if (request === state.request) {
      state.controller = null;
      $("#refresh").removeAttribute("aria-busy");
    }
  }
}

function setFilter(status) {
  state.status = status;
  state.page = 0;
  document.querySelectorAll(".filter").forEach((button) => {
    const active = button.dataset.status === status;
    button.classList.toggle("active", active);
    button.setAttribute("aria-pressed", String(active));
  });
  $("#list-title").textContent = status
    ? `${statusNames[status]}任务`
    : "全部任务";
  refresh(true);
}

// 隐藏的字段也要 disabled，否则浏览器仍可能阻止表单提交。
function syncTypeFields() {
  const demo = $("#task-type").value === "DEMO_TASK";
  $("#demo-fields").hidden = !demo;
  $("#task-delay").disabled = !demo || state.submitting;
  $("#task-fail").disabled = !demo || state.submitting;
  $("#task-payload").required = !demo;
  $("#payload-required").hidden = demo;
  $("#type-help").textContent = demo
    ? "模拟耗时与失败，观察后台执行、自动重试和状态流转。"
    : "统计字符数、按空白分隔的词数与行数；不做中文分词。";
  $("#task-payload").placeholder = demo
    ? "可选：添加一句演示说明…"
    : "输入需要统计的文本…";
}

function openCreate() {
  if (state.submitting) return;
  $("#create-form").reset();
  showError("#form-error", "");
  syncTypeFields();
  $("#create-dialog").showModal();
  $("#task-name").focus();
}

function fillDemo() {
  $("#task-name").value = "失败后自动重试演示";
  $("#task-type").value = "DEMO_TASK";
  $("#task-payload").value = "第一次模拟失败，随后由调度器自动重试。";
  $("#task-retries").value = "2";
  $("#task-delay").value = "2";
  $("#task-fail").value = "1";
  $("#task-schedule").value = "";
  syncTypeFields();
  showError("#form-error", "");
}

// datetime-local 没有时区；先按用户设备时间解析，再转成后端使用的 UTC。
async function createTask(event) {
  event.preventDefault();
  if (state.submitting) return;
  const schedule = $("#task-schedule").value;
  const name = $("#task-name").value.trim();
  const demo = $("#task-type").value === "DEMO_TASK";
  if (!name) {
    showError("#form-error", "请填写任务名称，不能只包含空格。");
    return;
  }
  if (!demo && !$("#task-payload").value.trim()) {
    showError("#form-error", "文本统计需要填写非空文本。");
    return;
  }
  const body = {
    name,
    type: $("#task-type").value,
    payload: $("#task-payload").value,
    maxRetries: Number($("#task-retries").value),
    delaySeconds: demo ? Number($("#task-delay").value) : 0,
    failUntilAttempt: demo ? Number($("#task-fail").value) : 0,
  };
  if (schedule) {
    const scheduledDate = new Date(schedule);
    if (Number.isNaN(scheduledDate.getTime())) {
      showError("#form-error", "请输入有效的执行时间。");
      return;
    }
    body.scheduledAt = scheduledDate.toISOString();
  }
  state.submitting = true;
  $("#create-dialog")
    .querySelectorAll("input, textarea, select, button")
    .forEach((element) => {
      element.disabled = true;
    });
  $("#submit-task").textContent = "正在提交…";
  showError("#form-error", "");
  try {
    const task = await api("/tasks", {
      method: "POST",
      body: JSON.stringify(body),
    });
    $("#create-dialog").close();
    toast(`任务 #${task.id} 已提交`);
    setFilter("");
  } catch (error) {
    showError("#form-error", error.message);
  } finally {
    state.submitting = false;
    $("#create-dialog")
      .querySelectorAll("input, textarea, select, button")
      .forEach((element) => {
        element.disabled = false;
      });
    syncTypeFields();
    $("#submit-task").textContent = "提交任务";
  }
}

function prettyResult(value) {
  if (!value) return "暂无执行结果";
  if (typeof value === "object") return JSON.stringify(value, null, 2);
  try {
    return JSON.stringify(JSON.parse(value), null, 2);
  } catch {
    return value;
  }
}

// 详情展示累计执行次数及每次独立日志，便于观察失败 -> 重试 -> 成功。
function renderDetail(task, logs) {
  $("#detail-title").textContent = task.name;
  $("#detail-subtitle").textContent = `任务 #${task.id} · ${task.type}`;
  const fields = [
    ["累计执行次数", task.attempts],
    ["每轮最大自动重试", `${task.maxRetries} 次`],
    ["创建时间", formatDate(task.createdAt)],
    ["计划执行时间", formatDate(task.scheduledAt)],
    [
      "下次执行时间",
      ["PENDING", "RETRY_WAIT"].includes(task.status)
        ? formatDate(task.nextRunAt)
        : "—",
    ],
    ["最近开始时间", formatDate(task.startedAt)],
    ["最近结束时间", formatDate(task.finishedAt)],
  ];
  if (task.type === "DEMO_TASK")
    fields.push([
      "演示参数",
      `耗时 ${task.delaySeconds} 秒 / 前 ${task.failUntilAttempt} 次失败`,
    ]);
  updateContent(
    $("#detail-content"),
    `
    <div class="detail-summary">${statusHtml(task.status)}<div class="actions">${rowActions(task, false)}</div></div>
    <dl class="detail-grid">${fields.map(([label, value]) => `<div><dt>${escapeHtml(label)}</dt><dd>${escapeHtml(value)}</dd></div>`).join("")}</dl>
    <section class="detail-section"><h3>任务内容</h3><pre class="code-block">${escapeHtml(task.payload || "无内容")}</pre></section>
    <section class="detail-section"><h3>执行结果</h3><pre class="code-block">${escapeHtml(prettyResult(task.result))}</pre></section>
    ${task.lastError ? `<section class="detail-section"><h3>最近错误</h3><div class="notice error">${escapeHtml(task.lastError)}</div></section>` : ""}
    <section class="detail-section"><h3>执行记录 <span class="field-help">${logs.length} 条</span></h3>${logs.length ? `<ol class="log-list">${logs.map((log) => `<li class="log-item"><div class="log-header"><strong>第 ${escapeHtml(log.attempt)} 次执行</strong>${statusHtml(log.status)}</div><p class="log-time">${escapeHtml(formatDate(log.startedAt))} → ${escapeHtml(log.finishedAt ? formatDate(log.finishedAt) : "执行中")}</p><p class="log-message">${escapeHtml(log.message || "正在处理任务…")}</p></li>`).join("")}</ol>` : '<p class="no-logs">任务尚未开始执行。</p>'}</section>`,
  );
}

async function refreshDetail(force = false) {
  if (state.detailId === null || (state.detailController && !force)) return;
  state.detailController?.abort();
  const controller = new AbortController();
  state.detailController = controller;
  const request = ++state.detailRequest;
  const id = state.detailId;
  try {
    const [task, logs] = await Promise.all([
      api(`/tasks/${id}`, { signal: controller.signal }),
      api(`/tasks/${id}/logs`, { signal: controller.signal }),
    ]);
    if (request !== state.detailRequest || id !== state.detailId) return;
    renderDetail(task, logs);
    showError("#detail-error", "");
  } catch (error) {
    if (error.name !== "AbortError" && request === state.detailRequest)
      showError("#detail-error", error.message);
  } finally {
    if (request === state.detailRequest) state.detailController = null;
  }
}

function openDetail(id) {
  state.detailId = id;
  $("#detail-title").textContent = "任务详情";
  $("#detail-subtitle").textContent = `任务 #${id}`;
  updateContent(
    $("#detail-content"),
    '<p class="loading-text">正在读取任务…</p>',
  );
  showError("#detail-error", "");
  $("#detail-dialog").showModal();
  refreshDetail(true);
}

async function actOnTask(button) {
  const { action, id } = button.dataset;
  if (action === "new") {
    openCreate();
    return;
  }
  if (action === "view") {
    openDetail(id);
    return;
  }
  if (
    !["cancel", "retry"].includes(action) ||
    button.disabled ||
    pendingActions.has(id)
  )
    return;
  pendingActions.add(id);
  button.disabled = true;
  try {
    await api(`/tasks/${id}/${action}`, { method: "POST" });
    pendingActions.delete(id);
    toast(
      action === "cancel" ? `任务 #${id} 已取消` : `任务 #${id} 已重新排队`,
    );
    await refresh(true);
    if (state.detailId === id) await refreshDetail(true);
  } catch (error) {
    if ($("#detail-dialog").open) showError("#detail-error", error.message);
    else showError("#page-error", error.message);
    button.disabled = false;
  } finally {
    pendingActions.delete(id);
  }
}

// 事件委托让后续刷新生成的新行仍能响应按钮，无需逐行重新绑定事件。
$("#new-task").addEventListener("click", openCreate);
$("#refresh").addEventListener("click", () => refresh(true));
$("#nav-tasks").addEventListener("click", () => setFilter(""));
$(".filters").addEventListener("click", (event) => {
  const button = event.target.closest("[data-status]");
  if (button) setFilter(button.dataset.status);
});
$("#previous-page").addEventListener("click", () => {
  if (state.page > 0) {
    state.page--;
    refresh(true);
  }
});
$("#next-page").addEventListener("click", () => {
  if ((state.page + 1) * state.size < state.total) {
    state.page++;
    refresh(true);
  }
});
$("#task-type").addEventListener("change", syncTypeFields);
$("#fill-demo").addEventListener("click", fillDemo);
$("#create-form").addEventListener("submit", createTask);
document.querySelectorAll(".close-dialog").forEach((button) =>
  button.addEventListener("click", () => {
    const dialog = button.closest("dialog");
    if (dialog.id === "create-dialog" && state.submitting) return;
    dialog.close();
  }),
);
document.querySelectorAll("#task-rows, #detail-content").forEach((element) =>
  element.addEventListener("click", (event) => {
    const button = event.target.closest("[data-action]");
    if (button) actOnTask(button);
  }),
);
$("#detail-dialog").addEventListener("close", () => {
  state.detailId = null;
  state.detailController?.abort();
  state.detailController = null;
  state.detailRequest++;
});
// 请求提交时保持窗口打开，避免重复创建或丢失错误提示。
$("#create-dialog").addEventListener("cancel", (event) => {
  if (state.submitting) event.preventDefault();
});
document.addEventListener("visibilitychange", () => {
  if (!document.hidden) {
    refresh(true);
    refreshDetail(true);
  }
});
setInterval(() => {
  if (!document.hidden) {
    refresh();
    refreshDetail();
  }
}, 3000);
refresh();
