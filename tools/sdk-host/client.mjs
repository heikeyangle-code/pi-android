/**
 * 测试用的宿主客户端：就是「App 那一侧」的最小实现。
 * 也是写 Kotlin `PiHostClient` 时的行为参照（帧怎么配对、事件怎么按 epoch 过滤）。
 */
import { spawn } from "node:child_process";
import { mkdirSync, mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

export function startHost({ hostEntry, extensions = [], env = {}, nodeArgs = [], extraArgs = [] }) {
  const root = mkdtempSync(join(tmpdir(), "sdk-host-"));
  const agentDir = join(root, "agent");
  const work = join(root, "work");
  mkdirSync(agentDir, { recursive: true });
  mkdirSync(work, { recursive: true });
  const args = [...nodeArgs, hostEntry, "--cwd", work, "--agent-dir", agentDir, ...extraArgs];
  for (const ext of extensions) args.push("--extension", ext);
  const child = spawn(process.execPath, args, { stdio: ["pipe", "pipe", "pipe"], env: { ...process.env, ...env } });

  const frames = [];
  const garbage = []; // stdout 上出现的非 JSON 行：协议被污染的证据，必须为空
  const stderrLines = [];
  const waiters = new Set();
  const pending = new Map();
  let n = 0;
  let onReq = () => {};

  let buffer = "";
  child.stdout.on("data", (chunk) => {
    buffer += chunk.toString("utf8");
    for (;;) {
      const i = buffer.indexOf("\n");
      if (i < 0) break;
      const line = buffer.slice(0, i);
      buffer = buffer.slice(i + 1);
      if (!line) continue;
      let frame;
      try {
        frame = JSON.parse(line);
      } catch {
        garbage.push(line);
        continue;
      }
      frames.push(frame);
      if (frame.t === "res" && pending.has(frame.id)) {
        pending.get(frame.id)(frame);
        pending.delete(frame.id);
      }
      if (frame.t === "req") onReq(frame);
      for (const w of [...waiters]) w(frame);
    }
  });
  child.stderr.on("data", (chunk) => stderrLines.push(chunk.toString("utf8")));

  const api = {
    child, frames, garbage, stderrLines, root, work, agentDir,
    onRequest(fn) {
      onReq = fn;
    },
    send(frame) {
      child.stdin.write(`${JSON.stringify(frame)}\n`);
    },
    call(cmd, args = {}, timeoutMs = 60000) {
      const id = `t${++n}`;
      return new Promise((resolve, reject) => {
        const timer = setTimeout(() => reject(new Error(`timeout waiting for ${cmd}`)), timeoutMs);
        pending.set(id, (frame) => {
          clearTimeout(timer);
          resolve(frame);
        });
        api.send({ id, cmd, args });
      });
    },
    waitFor(predicate, timeoutMs = 30000) {
      return new Promise((resolve, reject) => {
        const hit = frames.find(predicate);
        if (hit) return resolve(hit);
        const timer = setTimeout(() => {
          waiters.delete(check);
          reject(new Error("waitFor timeout"));
        }, timeoutMs);
        const check = (frame) => {
          if (predicate(frame)) {
            clearTimeout(timer);
            waiters.delete(check);
            resolve(frame);
          }
        };
        waiters.add(check);
      });
    },
    evt: (name, extra = () => true) => (f) => f.t === "evt" && f.name === name && extra(f.data, f),
    stop() {
      child.kill();
    },
  };
  return api;
}
