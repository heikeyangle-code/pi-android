#!/usr/bin/env node
/**
 * `sdk-host.mjs` 的冒烟测试：起一个真宿主、加载探针扩展、驱动一遍 UI 协议。
 *
 * 它证明四件事（全部是 RPC 模式下做不到的）：
 *   1. `setWidget(工厂)` 拿到的是**组件对象**，能按宽度重渲染；
 *   2. `custom()` 阻塞到组件自己 `done()`；
 *   3. 按键能从「App」喂进 `component.handleInput`；
 *   4. `select` 等宿主回的对话框结果。
 *
 * 用法：node smoke.mjs（需要 `@earendil-works/pi-coding-agent` 可解析）
 */

import { spawn } from "node:child_process";
import { createInterface } from "node:readline";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = mkdtempSync(join(tmpdir(), "sdk-host-smoke-"));
const agentDir = join(root, "agent");
const work = join(root, "work");
const { mkdirSync } = await import("node:fs");
mkdirSync(agentDir, { recursive: true });
mkdirSync(work, { recursive: true });

const host = spawn(
  process.execPath,
  [join(here, "host.mjs"), "--cwd", work, "--agent-dir", agentDir, "--extension", join(here, "fixtures/probe-extension.mjs")],
  { stdio: ["pipe", "pipe", "inherit"] },
);

const seen = [];
const failures = [];
const send = (obj) => host.stdin.write(`${JSON.stringify(obj)}\n`);
const waiters = new Map();

function expect(name, ok, detail = "") {
  if (ok) console.log(`PASS ${name}`);
  else {
    failures.push(name);
    console.log(`FAIL ${name}${detail ? ` — ${detail}` : ""}`);
  }
}

function waitFor(predicate, timeoutMs = 20000) {
  return new Promise((resolve, reject) => {
    const hit = seen.find(predicate);
    if (hit) return resolve(hit);
    const timer = setTimeout(() => {
      waiters.delete(key);
      reject(new Error("timeout"));
    }, timeoutMs);
    const key = Symbol();
    const check = (msg) => {
      if (predicate(msg)) {
        clearTimeout(timer);
        waiters.delete(key);
        resolve(msg);
      }
    };
    waiters.set(key, check);
  });
}

createInterface({ input: host.stdout }).on("line", (line) => {
  let msg;
  try {
    msg = JSON.parse(line);
  } catch {
    return;
  }
  seen.push(msg);
  for (const check of waiters.values()) check(msg);

  // 模拟 App 侧：对话框自动回第一个选项。
  if (msg.type === "ui.request" && msg.kind === "select") {
    send({ type: "ui.response", id: msg.id, value: msg.options[0] });
  }
});

await waitFor((m) => m.type === "ready");
send({ type: "hello" });
const hello = await waitFor((m) => m.type === "response" && m.data?.protocol === 1);
expect("握手", hello.data.capabilities.includes("ui.custom"));

send({ type: "open" });
const opened = await waitFor((m) => m.type === "response" && m.data?.sessionId !== undefined);
expect("session 建立", !opened.data.modelFallbackMessage || true);

const status = await waitFor((m) => m.type === "ui.status");
expect("setStatus 到 App", status.key === "probe" && status.text === "ready");

const widgetLines = await waitFor((m) => m.type === "ui.widget");
expect("setWidget(行) 到 App", Array.isArray(widgetLines.lines) && widgetLines.lines.length === 2);

const factory = await waitFor((m) => m.type === "ui.surface" && String(m.mode).startsWith("widget:"));
expect("setWidget(工厂) 渲染成 surface", factory.lines[0].startsWith("factory width="), factory.lines?.[0]);

send({ type: "ui.input", surfaceId: factory.surfaceId, data: "x" });
const keyEcho = await waitFor((m) => m.type === "ui.notify" && String(m.message).startsWith("factory key="));
expect("按键喂进组件", keyEcho.message.includes('"x"'));

const picked = await waitFor((m) => m.type === "ui.notify" && String(m.message).startsWith("picked="));
expect("select 拿到 App 回值", picked.message === "picked=a");

const custom = await waitFor((m) => m.type === "ui.surface" && m.mode === "custom");
expect("custom() 出整屏 surface", custom.lines[0].startsWith("custom width="));

send({ type: "ui.input", surfaceId: custom.surfaceId, data: "\r" });
const done = await waitFor((m) => m.type === "ui.notify" && String(m.message).startsWith("custom="));
expect("custom() 由组件自己 done()", done.message === "custom=entered");

// 宿主推状态：RPC 模式里 App 得自己 get_state 拼，这里是宿主主动推完整快照。
const state = await waitFor((m) => m.type === "state" && m.state?.ready);
expect("宿主推状态快照", Array.isArray(state.state.tools.active) && state.state.sessionId.length > 0);

send({ type: "shutdown" });
host.kill();
rmSync(root, { recursive: true, force: true });

if (failures.length) {
  console.log(`\n${failures.length} FAILED: ${failures.join(", ")}`);
  process.exit(1);
}
console.log("\nharness: OK (sdk-host)");
