#!/usr/bin/env node
/**
 * 录一份真宿主的帧序列，给 Kotlin harness（app/src/test/kotlin/app/pi/host/HostClientCheck.kt）回放。
 *
 *   node tools/sdk-host/record.mjs [--out app/src/test/resources/sdk-host/session.jsonl] [--pi <目录>]
 *
 * 文件每行是 `{"dir":"app"|"host","line":"<原始线协议行>"}`：
 *   app  = App 发给宿主的命令（id 已改写成 c1,c2…，与 Kotlin 客户端的编号一致）
 *   host = 宿主回的一行（res 的 id 同步改写；evt/req 原样）
 * 回放时按序：遇到 app 行就让客户端发同一条命令，遇到 host 行就喂给 `onLine`。
 */
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { mkdirSync, writeFileSync, rmSync } from "node:fs";
import { startHost } from "./client.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const repo = resolve(here, "../..");
const arg = (n) => {
  const i = process.argv.indexOf(`--${n}`);
  return i > 0 ? process.argv[i + 1] : undefined;
};
const out = resolve(arg("out") ?? join(repo, "app/src/test/resources/sdk-host/session.jsonl"));
const pi = resolve(arg("pi") ?? join(repo, "build/pi-contract/node_modules/@earendil-works/pi-coding-agent"));

const h = startHost({ hostEntry: join(repo, "app/src/main/assets/guest/sdk-host/main.mjs"), extraArgs: ["--pi-dir", pi] });
const log = [];
const idMap = new Map();
let n = 0;
const rec = (dir, frame) => log.push({ dir, line: JSON.stringify(frame) });

const origSend = h.send;
const call = async (cmd, args = {}) => {
  const cid = `c${++n}`;
  // client.mjs 的 call 自己编号 t*，这里手工发，以便使用 c* 编号
  const waiter = h.waitFor((f) => f.t === "res" && f.id === cid, 90000);
  const frame = { id: cid, cmd, args };
  rec("app", frame);
  origSend(frame);
  return waiter;
};

try {
  await h.waitFor(h.evt("host.ready"), 5000);
  await call("host.hello");
  await call("session.open", { trust: false });
  await h.waitFor(h.evt("ext.bound"), 60000);
  await call("session.snapshot");
  await call("model.list");
  const r = await call("chat.send", { message: "hello" });
  // 没有凭据：预检通过后整轮会失败，宿主应发 agent 事件与/或 agent.error，等到空闲即可
  await h.waitFor((f) => f.t === "evt" && (f.name === "agent.error" || (f.name === "agent" && f.data?.type === "agent_settled")), 30000).catch(() => {});
  await call("session.new");
  await call("session.snapshot");
  await call("no.such.command"); // 错误应答的真样本
  for (const f of h.frames) {
    if (f.t === "res") {
      rec("host", f);
    } else rec("host", f);
  }
  // 重排：把 app 行插到对应 res 之前，保证回放时「先发命令、再喂应答」。
  const hostLines = log.filter((x) => x.dir === "host");
  const appLines = log.filter((x) => x.dir === "app");
  const ordered = [];
  const used = new Set();
  for (const host of hostLines) {
    const f = JSON.parse(host.line);
    if (f.t === "res") {
      const a = appLines.find((x) => JSON.parse(x.line).id === f.id && !used.has(x));
      if (a) { used.add(a); ordered.push(a); }
    }
    ordered.push(host);
  }
  mkdirSync(dirname(out), { recursive: true });
  writeFileSync(out, ordered.map((x) => JSON.stringify(x)).join("\n") + "\n");
  console.log(`recorded ${ordered.length} lines (${hostLines.length} host, ${appLines.length} app) → ${out}`);
} finally {
  h.stop();
  try { rmSync(h.root, { recursive: true, force: true }); } catch {}
}
process.exit(0);
