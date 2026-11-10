#!/usr/bin/env node
/**
 * sdk-host 行为测试。起一个真宿主 + 真 pi SDK + 探针扩展，按 App 的方式驱动一遍。
 *
 *   node tools/sdk-host/test.mjs [--pi <pi-coding-agent 目录>] [--scandir-fix] [--no-bundle]
 *
 * `--scandir-fix`：用 NODE_OPTIONS 预加载 `guest/scandir-fix.mjs`，模拟 proroot 运行时
 * （ProrootCommand 在 guest 里就是这么注入的）。proot 运行时不需要它，所以默认关。
 * `--no-bundle`：强制 SDK 走包名入口，验证回退路径。
 */
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { rmSync } from "node:fs";
import { startHost } from "./client.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const repo = resolve(here, "../..");
const hostDir = join(repo, "app/src/main/assets/guest/sdk-host");
const arg = (n) => {
  const i = process.argv.indexOf(`--${n}`);
  return i > 0 ? process.argv[i + 1] : undefined;
};
const flag = (n) => process.argv.includes(`--${n}`);

const pi = resolve(arg("pi") ?? join(repo, "build/pi-contract/node_modules/@earendil-works/pi-coding-agent"));

const results = [];
function expect(name, ok, detail = "") {
  results.push({ name, ok });
  console.log(`${ok ? "PASS" : "FAIL"} ${name}${!ok && detail ? ` — ${detail}` : ""}`);
}

const nodeArgs = [];
if (flag("scandir-fix")) nodeArgs.push("--import", join(repo, "app/src/main/assets/guest/scandir-fix.mjs"));
const env = flag("no-bundle") ? { PI_HOST_NO_BUNDLE: "1" } : {};

const h = startHost({
  hostEntry: join(hostDir, "main.mjs"),
  extensions: [join(here, "fixtures/probe-extension.mjs")],
  nodeArgs,
  env,
  extraArgs: ["--pi-dir", pi],
});

// App 的最小行为：select 自动回第一个，其余对话框不回答。
h.onRequest((req) => {
  if (req.method === "ui.select") h.send({ id: `r-${req.id}`, cmd: "ui.respond", args: { id: req.id, value: req.params.options[0] } });
});

try {
  // ---- 1. 握手：不等 SDK ------------------------------------------------
  const t0 = Date.now();
  const ready = await h.waitFor(h.evt("host.ready"), 5000);
  expect("host.ready 先于 SDK 加载", ready.data.pid > 0 && Date.now() - t0 < 2000, `${Date.now() - t0}ms`);

  const hello = await h.call("host.hello");
  expect("host.hello 协议版本与命令表", hello.ok && hello.data.protocol === 1 && hello.data.commands.includes("session.prompt"));

  const unknown = await h.call("nope.nope");
  expect("未知命令返回 unknown_cmd", !unknown.ok && unknown.error.code === "unknown_cmd");
  const early = await h.call("session.snapshot");
  expect("open 之前返回 not_open", !early.ok && early.error.code === "not_open");

  // ---- 2. 打开会话 ------------------------------------------------------
  const opened = await h.call("session.open", {}, 120000);
  expect("session.open", opened.ok && typeof opened.data.sessionId === "string", JSON.stringify(opened.error ?? opened.data));
  expect(`SDK 入口：${opened.data?.sdkVia}`, flag("no-bundle") ? opened.data?.sdkVia === "package" : ["bundle", "package"].includes(opened.data?.sdkVia));

  // ---- 3. UI 面 ---------------------------------------------------------
  const status = await h.waitFor(h.evt("ui.status", (d) => d.key === "probe"));
  expect("setStatus", status.data.text.startsWith("ready:"));
  const lines = await h.waitFor(h.evt("ui.widget", (d) => Array.isArray(d.lines)));
  expect("setWidget(行)", lines.data.lines.length === 2);

  const widgetFrame = await h.waitFor(h.evt("ui.widget", (d) => d.surfaceId));
  const surf = await h.waitFor(h.evt("ui.surface", (d) => d.surfaceId === widgetFrame.data.surfaceId));
  expect("setWidget(工厂) 出表面", surf.data.lines[0].startsWith("factory width="));
  expect("theme.fg 产出 ANSI 颜色（原型版是空串）", surf.data.lines[1].includes("\x1b[38;2;") && surf.data.lines[1].includes("colored"));

  await h.waitFor(h.evt("ui.surface", (d) => d.surfaceId === surf.data.surfaceId && d.rev >= 4), 5000).catch(() => {});
  const revs = h.frames.filter(h.evt("ui.surface", (d) => d.surfaceId === surf.data.surfaceId)).map((f) => f.data.rev);
  expect("requestRender 触发重绘", Math.max(...revs) >= 4, `revs=${revs.join(",")}`);

  const inputRes = await h.call("ui.input", { surfaceId: surf.data.surfaceId, data: "x" });
  expect("ui.input 命中组件", inputRes.ok && inputRes.data.handled === true);
  await h.waitFor(h.evt("ui.notify", (d) => d.message.includes('factory key="x"')));
  expect("按键进 handleInput", true);

  await h.waitFor(h.evt("ui.notify", (d) => d.message === "picked=a"));
  expect("select 拿到 App 回值", true);

  await h.waitFor(h.evt("ui.cancel"), 5000);
  expect("对话框超时后宿主通知 App 收回（ui.cancel）", true);
  await h.waitFor(h.evt("ui.notify", (d) => d.message === "confirm=false"));
  expect("超时的 confirm 回落为 false", true);

  const custom = await h.waitFor(h.evt("ui.surface", (d) => d.mode === "custom"));
  expect("custom() 出表面", custom.data.lines[0].startsWith("custom width="));
  const resized = await h.call("ui.resize", { width: 60 });
  const wider = await h.waitFor(h.evt("ui.surface", (d, f) => d.surfaceId === custom.data.surfaceId && d.rev > custom.data.rev && d.lines[0].includes("width=60")), 5000);
  expect("ui.resize 按新宽度重绘", resized.ok && !!wider);
  await h.call("ui.input", { surfaceId: custom.data.surfaceId, data: "\r" });
  await h.waitFor(h.evt("ui.notify", (d) => d.message === "custom=entered"));
  await h.waitFor(h.evt("ui.close", (d) => d.surfaceId === custom.data.surfaceId));
  expect("custom() 由组件 done() 结束并关闭表面", true);
  // bindExtensions 要等所有 session_start 处理器（上面那串对话框）跑完，所以 ext.bound 只能在这之后。
  const bound = await h.waitFor(h.evt("ext.bound"), 30000);
  expect("扩展绑定完成（open 的应答没有被它拖住）", bound.data.ok === true, bound.data.error);

  // ---- 4. 协议完整性 ----------------------------------------------------
  expect("扩展的 console.log / stdout.write 没有污染协议流", h.garbage.length === 0, JSON.stringify(h.garbage.slice(0, 2)));
  expect("噪声被改道到了 stderr", h.stderrLines.join("").includes("NOISE-from-extension"));
  const seqs = h.frames.filter((f) => f.t === "evt").map((f) => f.seq);
  expect("evt.seq 严格递增无跳号", seqs.every((s, i) => i === 0 || s === seqs[i - 1] + 1));

  h.send({ id: "u2028", cmd: "host.ping", args: { text: "a b\r\nc" } });
  const ping = await h.waitFor((f) => f.t === "res" && f.id === "u2028");
  expect("参数里的 U+2028 / CR 不会把一帧劈成两半", ping.ok);

  // ---- 5. 会话读写 ------------------------------------------------------
  const snap = await h.call("session.snapshot", { messages: true });
  expect("session.snapshot", snap.ok && snap.data.epoch >= 1 && Array.isArray(snap.data.messages));
  const epoch0 = snap.data.epoch;

  const cmds = await h.call("catalog.commands");
  expect("catalog.commands 含扩展命令", cmds.ok && cmds.data.commands.some((c) => c.name === "probe-ping"));
  const tools = await h.call("catalog.tools");
  expect("catalog.tools", tools.ok && tools.data.active.length > 0);
  const toolNames = tools.data.all.map((t) => t.name);
  expect("官方默认工具都在", ["read", "bash", "edit", "write"].every((n) => tools.data.active.includes(n)), tools.data.active.join(","));
  expect("官方工具全集（grep/find/ls）可启用", ["grep", "find", "ls"].every((n) => toolNames.includes(n)), toolNames.join(","));
  expect("Code Mode / Tool Search 已注册（默认未激活）", toolNames.includes("codemode") && toolNames.includes("tool_search"), toolNames.join(","));
  expect("MCP 内置扩展的 /mcp 命令在", cmds.data.commands.some((c) => c.name === "mcp"), cmds.data.commands.map((c) => c.name).join(","));

  // 通用调用口：不经具名命令就能调 SDK 新 API。
  const viaCall = await h.call("sdk.call", { target: "session", method: "getActiveToolNames" });
  expect("sdk.call 调 session 方法", viaCall.ok && Array.isArray(viaCall.data.result) && viaCall.data.result.includes("read"));
  const viaGet = await h.call("sdk.get", { target: "session", prop: "thinkingLevel" });
  expect("sdk.get 读 session 属性", viaGet.ok && "value" in viaGet.data);
  const viaPm = await h.call("sdk.call", { target: "packageManager", method: "listConfiguredPackages" });
  expect("sdk.call 可达 packageManager（安装/更新等无需新命令）", viaPm.ok && Array.isArray(viaPm.data.result));
  const denied = await h.call("sdk.call", { target: "session", method: "_buildRuntime" });
  expect("sdk.call 拒绝私有方法", !denied.ok && denied.error.code === "bad_args");
  const missing = await h.call("sdk.call", { target: "session", method: "noSuchApi" });
  expect("sdk.call 缺方法 → unsupported（版本差异可识别）", !missing.ok && missing.error.code === "unsupported");

  const rendered = await h.call("render.message", { customType: "probe", message: { content: "hi" }, width: 50 });
  expect("render.message 调到了扩展的渲染器", rendered.ok && rendered.data.lines?.[0]?.includes("width=50"), JSON.stringify(rendered));

  // 宿主持状态：open 之后主动推一份完整快照，变更类命令的应答里带最新状态（App 不用再 get_xxx）。
  const pushed = await h.waitFor(h.evt("state", (d) => d.state?.sessionId), 5000);
  expect("宿主主动推 state 快照", pushed.data.state.tools.active.includes("read") && "tokens" in pushed.data.state && "cost" in pushed.data.state);
  const th = await h.call("thinking.set", { level: "off" });
  expect("变更类命令应答带最新 state", th.ok && th.data.state?.thinkingLevel === "off", JSON.stringify(th).slice(0, 200));
  const stateFrames = h.frames.filter(h.evt("state")).length;
  await h.call("session.name", { name: "推送测试" });
  await h.waitFor(h.evt("state", (d) => d.state?.sessionName === "推送测试"), 3000).catch(() => {});
  expect("改名后推送 state（session_info_changed）", h.frames.filter(h.evt("state")).length > stateFrames || h.frames.some(h.evt("state", (d) => d.state?.sessionName === "推送测试")));

  // 无凭据时 pi 的 prompt 预检**通过**（disposition=started），失败发生在之后，以 agent 事件报告。
  // 宿主必须：预检通过就应答（不等整轮），并把后续事件原样推出来。
  const mark = h.frames.length;
  const started = await h.call("session.prompt", { message: "hello" });
  expect("prompt 预检通过即应答（不等整轮结束）", started.ok && started.data.disposition === "started", JSON.stringify(started));
  const agentStart = await h.waitFor((f) => h.frames.indexOf(f) >= mark && h.evt("agent", (d) => d.type === "agent_start")(f), 10000);
  expect("官方事件流原样透传（agent_start）", !!agentStart);
  await h.waitFor((f) => h.frames.indexOf(f) >= mark && h.evt("agent", (d) => d.type === "agent_settled")(f), 30000).catch(() => {});
  const noPartial = h.frames.filter(h.evt("agent", (d) => d.type === "message_update")).every((f) => !("partial" in f.data.assistantMessageEvent));
  expect("message_update 不带整条 partial（体积）", noPartial);
  const chat = await h.call("chat.stop", { clearQueue: true });
  expect("chat.stop 返回 state", chat.ok && chat.data.state && typeof chat.data.state.isStreaming === "boolean");
  const badPrompt = await h.call("session.prompt", {});
  expect("prompt 缺 message → bad_args", !badPrompt.ok && badPrompt.error.code === "bad_args");

  const named = await h.call("session.name", { name: "测试会话" });
  expect("session.name", named.ok);
  const badArg = await h.call("session.name", {});
  expect("缺参数返回 bad_args", !badArg.ok && badArg.error.code === "bad_args");

  // ---- 6. 会话替换：epoch 只 +1，旧表面被收 --------------------------------
  const boundBefore = h.frames.filter(h.evt("session.bound")).length;
  // 新会话会让探针扩展的 session_start 再跑一遍；`session.new` 的应答要等它（与 pi 一致），
  // 所以这里要像 App 一样在它等待期间回答它的对话框。
  const mark2 = h.frames.length;
  const pending = h.call("session.new", {}, 60000);
  const custom2 = await h.waitFor((f) => h.frames.indexOf(f) >= mark2 && h.evt("ui.surface", (d) => d.mode === "custom")(f), 20000);
  await h.call("ui.input", { surfaceId: custom2.data.surfaceId, data: "\r" });
  const created = await pending;
  expect("session.new", created.ok && created.data.cancelled === false, JSON.stringify(created));
  const after = await h.call("session.snapshot");
  expect("epoch 恰好 +1（不重复绑定）", after.data.epoch === epoch0 + 1, `${epoch0} → ${after.data.epoch}`);
  expect("session.bound 恰好多一条", h.frames.filter(h.evt("session.bound")).length === boundBefore + 1);
  await h.waitFor(h.evt("ui.close", (d) => d.surfaceId === surf.data.surfaceId), 5000);
  expect("旧会话的工厂 widget 被收（dispose 已调用）", h.stderrLines.join("").includes("PROBE-FACTORY-DISPOSED"));

  // ---- 7. 进程内服务 ----------------------------------------------------
  const pk = await h.call("packages.list");
  expect("packages.list（进程内，不起子进程）", pk.ok && Array.isArray(pk.data.packages), JSON.stringify(pk.error));

  // 扩展事件总线桥：订阅 → 发事件 → App 收到原样转发的 bus 事件。
  const sub = await h.call("bus.subscribe", { names: ["probe:ping"] });
  expect("bus.subscribe", sub.ok && sub.data.subscribed.includes("probe:ping"), JSON.stringify(sub));
  await h.call("bus.emit", { name: "probe:ping", data: { n: 1 } });
  const busEvt = await h.waitFor(h.evt("bus", (d) => d.name === "probe:ping"), 5000).catch(() => null);
  expect("bus 事件原样转发给 App", busEvt?.data.data.n === 1, JSON.stringify(busEvt));
  const sr = await h.call("subagent.rpc", { method: "ping", timeoutMs: 300 });
  expect("subagent.rpc 在 pi-subagents 未装时返回可识别的错误而不是挂死", !sr.ok && sr.error.code === "busy", JSON.stringify(sr));

  // ---- 8. 退出 ----------------------------------------------------------
  await h.call("host.shutdown");
  const code = await new Promise((r) => (h.child.exitCode !== null ? r(h.child.exitCode) : h.child.once("exit", r)));
  expect("host.shutdown 干净退出", code === 0, `exit=${code}`);
} catch (error) {
  expect("测试过程没有异常", false, error?.stack ?? String(error));
  console.log("--- stderr tail ---\n" + h.stderrLines.join("").split("\n").slice(-15).join("\n"));
} finally {
  h.stop();
  rmSync(h.root, { recursive: true, force: true });
}

const failed = results.filter((r) => !r.ok);
console.log(failed.length ? `\n${failed.length} FAILED` : `\nharness: OK (sdk-host, ${results.length} checks)`);
process.exit(failed.length ? 1 : 0);
