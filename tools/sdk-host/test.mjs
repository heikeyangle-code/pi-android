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
import { createServer } from "node:http";
import { fileURLToPath } from "node:url";
import { existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
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
  if (req.method === "auth.prompt") h.send({ id: `r-${req.id}`, cmd: "ui.respond", args: { id: req.id, value: globalThis.__authAnswer ?? "CODE1" } });
  if (req.method === "ui.select") h.send({ id: `r-${req.id}`, cmd: "ui.respond", args: { id: req.id, value: req.params.options[0] } });
});

// **CI 里没有凭据，pi 1.5 的 prompt 预检会直接拒绝**（No API key found for …），
// 而这一组要验的是"预检通过后事件原样流出"。所以给一个只回一条流式文本的
// OpenAI-compatible 端点，再把它写进 agentDir 的 models.json（带 apiKey，模型才算
// available）与 settings.json（默认模型指向它）。真实上游的失败模式不在这里验。
const probeServer = createServer((req, res) => {
  req.on("data", () => {});
  req.on("end", () => {
    res.writeHead(200, { "content-type": "text/event-stream", "cache-control": "no-cache", connection: "keep-alive" });
    const chunk = (delta, finish = null) =>
      `data: ${JSON.stringify({ id: "probe-1", object: "chat.completion.chunk", created: 1, model: "probe-model", choices: [{ index: 0, delta, finish_reason: finish }] })}\n\n`;
    res.write(chunk({ role: "assistant", content: "hi" }));
    res.write(chunk({}, "stop"));
    res.write("data: [DONE]\n\n");
    res.end();
  });
});
await new Promise((done) => probeServer.listen(0, "127.0.0.1", done));
const probePort = probeServer.address().port;
writeFileSync(join(h.agentDir, "settings.json"), JSON.stringify({
  defaultProvider: "probe-local",
  defaultModel: "probe-model",
  retry: { enabled: false },
}));
writeFileSync(join(h.agentDir, "models.json"), JSON.stringify({
  providers: {
    "probe-local": {
      baseUrl: `http://127.0.0.1:${probePort}/v1`,
      api: "openai-completions",
      apiKey: "probe-key",
      models: [{ id: "probe-model", contextWindow: 8000 }, { id: "probe-model-2", contextWindow: 8000 }],
    },
  },
}));

try {
  // ---- 1. 握手：不等 SDK ------------------------------------------------
  const t0 = Date.now();
  const ready = await h.waitFor(h.evt("host.ready"), 5000);
  expect("host.ready 先于 SDK 加载", ready.data.pid > 0 && Date.now() - t0 < 2000, `${Date.now() - t0}ms`);

  const hello = await h.call("host.hello");
  expect("host.hello 协议版本与命令表", hello.ok && hello.data.protocol === 1 && hello.data.engine === "coding-agent" && hello.data.commands.includes("session.prompt"));

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
  // 扩展工具的渲染器：renderCall / renderResult 渲成行；同一次调用的 state 共享；没有渲染器的工具返回 null。
  const rt = await h.call("render.tool", { toolName: "probe_tool", toolCallId: "t1", args: { q: "hi" }, width: 50, result: { content: [{ type: "text", text: "ok" }], isError: true }, expanded: true });
  expect("render.tool 渲染 call", rt.ok && rt.data.call?.[0]?.includes("q=hi w=50"), JSON.stringify(rt));
  expect("render.tool 渲染 result（同一次调用共享 state，带 isError / expanded）", rt.ok && /^result expanded=true err=true n=\d+$/.test(rt.data.result?.[0]) && rt.data.result?.[1] === "ok", JSON.stringify(rt));
  const rtd = await h.call("render.tool", { toolName: "probe_tool", toolCallId: "t2", args: { q: "d" }, width: 50, result: { content: [{ type: "text", text: "x" }], details: { successfulQueries: 2, totalResults: 7 }, isError: false } });
  expect("render.tool 把 result.details 交给 renderResult（扩展渲染器读的主要是它）", rtd.ok && rtd.data.result?.[2] === 'details={"successfulQueries":2,"totalResults":7}', JSON.stringify(rtd));
  const rt2 = await h.call("render.tool", { toolName: "probe_tool", toolCallId: "t1", args: { q: "hi" }, width: 50 });
  expect("同一 toolCallId 再渲染，state 延续（官方组件持有它）", rt2.ok && Number(/n=(\d+)/.exec(rt2.data.call?.[0] ?? "")?.[1]) > Number(/n=(\d+)/.exec(rt.data.call?.[0] ?? "")?.[1]), JSON.stringify(rt2));
  const rt3 = await h.call("render.tool", { toolName: "no_such_tool", args: {}, width: 50 });
  expect("没注册的工具 → null（App 用自己的原生卡）", rt3.ok && rt3.data.call === null && rt3.data.result === null && rt3.data.reason === "no_renderer", JSON.stringify(rt3));
  const rt4 = await h.call("render.tool", { toolName: "read", args: { path: "x" }, width: 50 });
  expect("内置工具 SDK 自带渲染器也能出行（App 对内置工具仍用原生卡，这是对照）", rt4.ok && Array.isArray(rt4.data.call), JSON.stringify(rt4));

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
  expect("扩展命令标出 hasCompletions", cmds.data.commands.find((c) => c.name === "probe-ping")?.hasCompletions === true);
  const comp = await h.call("catalog.commandCompletions", { name: "probe-ping", prefix: "b" });
  expect("catalog.commandCompletions 按前缀过滤并带说明", comp.ok && comp.data.items.length === 1 && comp.data.items[0].value === "beta" && comp.data.items[0].description === "arg beta");
  const none = await h.call("catalog.commandCompletions", { name: "no-such-command", prefix: "" });
  expect("catalog.commandCompletions 对没有补全的命令给空表", none.ok && none.data.items.length === 0);
  // 内建命令表来自 pi 自己（BUILTIN_SLASH_COMMANDS）；测试用的 pi 若读不到就必须带原因，不能静默给空表。
  expect(
    "catalog.commands 带 pi 的内建命令表（或读不到的原因）",
    cmds.ok && Array.isArray(cmds.data.builtins) &&
      (cmds.data.builtins.some((c) => c.name === "settings" && typeof c.description === "string") ||
        typeof cmds.data.builtinsError === "string"),
  );
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
  expect("render.message 调到了扩展的渲染器", rendered.ok && rendered.data.lines?.some((l) => l.includes("width=50")), JSON.stringify(rendered));
  const re1 = await h.call("render.entry", { customType: "probe-entry", id: "e1", data: { n: 1 }, width: 50, expanded: true });
  expect("render.entry 调到了扩展的 entry 渲染器（官方 CustomEntryComponent）", re1.ok && re1.data.lines?.some((l) => l.includes('entry<{"n":1}> expanded=true key=function')), JSON.stringify(re1));
  const re2 = await h.call("render.entry", { customType: "nobody", id: "e2", data: {}, width: 50 });
  expect("没有渲染器的 custom 条目 → no_renderer（和 pi 一样什么也不画）", re2.ok && re2.data.lines === null && re2.data.reason === "no_renderer", JSON.stringify(re2));
  const rmd = await h.call("render.markdown", { markdown: "a PROBE b", messageType: "assistant", isStreaming: false, width: 50 });
  expect("render.markdown 走扩展的 markdown transformer，带 messageType / isStreaming 上下文", rmd.ok && rmd.data.markdown === "a [assistant:false] b" && rmd.data.count >= 1 && rmd.data.changed === true, JSON.stringify(rmd));

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
  // 这次不让组件自己 done()，而是 App 侧关掉表面（用户点 ✕）：扩展的 await ctx.ui.custom() 必须以 undefined 返回，
  // 否则 session.new 会永远等下去。
  const closedByApp = await h.call("ui.close", { surfaceId: custom2.data.surfaceId });
  const created = await pending;
  expect("App 侧关闭 custom 表面 → 扩展的 await 以 undefined 返回", closedByApp.ok && closedByApp.data.closed === true && created.ok, JSON.stringify(created));
  expect("session.new", created.ok && created.data.cancelled === false, JSON.stringify(created));
  const after = await h.call("session.snapshot");
  expect("epoch 恰好 +1（不重复绑定）", after.data.epoch === epoch0 + 1, `${epoch0} → ${after.data.epoch}`);
  expect("session.bound 恰好多一条", h.frames.filter(h.evt("session.bound")).length === boundBefore + 1);
  await h.waitFor(h.evt("ui.close", (d) => d.surfaceId === surf.data.surfaceId), 5000);
  expect("旧会话的工厂 widget 被收（dispose 已调用）", h.stderrLines.join("").includes("PROBE-FACTORY-DISPOSED"));

  // ---- 6b. 会话库：搜索 / 只读预览 / 改名 / 标签 / 删除 -----------------------
  // 现成的会话文件：header + 一问一答 + 一条分支。直接写进当前会话所在目录（宿主只认这个目录内的路径）。
  const curFile = after.data.sessionFile;
  const dir = dirname(curFile);
  const mkEntry = (id, parentId, role, text) => ({ type: "message", id, parentId, timestamp: new Date().toISOString(), message: { role, content: [{ type: "text", text }], timestamp: Date.now(), ...(role === "assistant" ? { api: "x", provider: "x", model: "x", usage: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, totalTokens: 0, cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 } }, stopReason: "stop" } : {}) } });
  const libFile = join(dir, "2026-01-01T00-00-00-000Z_lib-fixture-1.jsonl");
  writeFileSync(libFile, [
    { type: "session", version: 3, id: "lib-fixture-1", timestamp: new Date().toISOString(), cwd: after.data.cwd },
    mkEntry("e1", null, "user", "独一无二的搜索词 zebra-quartz 怎么用"),
    mkEntry("e2", "e1", "assistant", "这样用"),
    mkEntry("e3", "e2", "user", "再来一个分支问题"),
  ].map((x) => JSON.stringify(x)).join("\n") + "\n");
  const listed = await h.call("session.list", { all: true });
  const row = listed.data.sessions?.find((x) => x.id === "lib-fixture-1");
  expect("session.list 能列出（宿主用 SessionManager，不靠 App 扫盘）且默认不带全文", !!row && !("allMessagesText" in row), JSON.stringify(listed.error ?? listed.data).slice(0, 200));
  const found = await h.call("session.search", { query: "ZEBRA-quartz", all: true });
  expect("session.search 全文命中（大小写不敏感）并带片段", found.ok && found.data.matches.some((m) => m.session.id === "lib-fixture-1" && m.snippet.includes("zebra-quartz")), JSON.stringify(found.error ?? found.data).slice(0, 200));
  expect("session.search 空词 → bad_args", (await h.call("session.search", { query: " " })).error?.code === "bad_args");
  const sum = await h.call("session.read", { path: libFile });
  expect("session.read 摘要（不切换会话）", sum.ok && sum.data.entryCount === 3 && sum.data.leafId === "e3", JSON.stringify(sum.error ?? sum.data).slice(0, 200));
  const br = await h.call("session.read", { path: libFile, mode: "branch", fromId: "e2" });
  expect("session.read 分支（叶到根）", br.ok && br.data.entries.map((e) => e.id).join() === "e1,e2" || br.data.entries.map((e) => e.id).join() === "e2,e1", JSON.stringify(br.data?.entries?.map((e) => e.id)));
  const pg = await h.call("session.read", { path: libFile, mode: "entries", offset: 1, limit: 1 });
  expect("session.read 条目分页", pg.ok && pg.data.entries.length === 1 && pg.data.hasMore === true);
  expect("session.read 拒绝会话目录外的路径", (await h.call("session.read", { path: "/etc/passwd" })).error?.code === "bad_args");
  expect("session.read 拒绝 .. 逃逸", (await h.call("session.read", { path: join(dir, "..", "..", "auth.json") })).error?.code === "bad_args");
  const rn = await h.call("session.rename", { path: libFile, name: "库里改的名" });
  const row2 = (await h.call("session.list", { all: true })).data.sessions.find((x) => x.id === "lib-fixture-1");
  expect("session.rename 非当前会话", rn.ok && row2?.name === "库里改的名", JSON.stringify(row2));
  expect("session.delete 不许删当前会话", (await h.call("session.delete", { path: curFile })).error?.code === "busy");
  const del = await h.call("session.delete", { path: libFile });
  expect("session.delete", del.ok && !existsSync(libFile));
  expect("session.delete 不存在 → not_found", (await h.call("session.delete", { path: libFile })).error?.code === "not_found");
  expect("session.label 条目不存在 → not_found", (await h.call("session.label", { entryId: "nope", label: "x" })).error?.code === "not_found");
  const pathNow = await h.call("session.path");
  expect("session.path 空会话也可调", pathNow.ok && Array.isArray(pathNow.data.entries));
  expect("session.contextEdit 条目不存在 → not_found", (await h.call("session.contextEdit", { targetId: "nope", replacement: null })).error?.code === "not_found");

  // ---- 6c. AgentSession 补齐项：作用域模型 / 缓存预热 / 中止 / 系统提示词 / 导出 / 队列 ----
  const ml = await h.call("model.list");
  const mods = ml.data?.models ?? ml.data?.available ?? [];
  expect("model.list 有可选模型（假凭据下也应有内置目录）", Array.isArray(mods) && mods.length >= 2, JSON.stringify(ml.error ?? Object.keys(ml.data ?? {})));
  if (mods.length >= 2) {
    const two = mods.slice(0, 2).map((m) => ({ provider: m.provider, id: m.id }));
    const set = await h.call("models.setScoped", { models: two });
    expect("models.setScoped 设两个", set.ok && set.data.count === 2 && set.data.state.scopedModelCount === 2, JSON.stringify(set.error ?? set.data?.count));
    const got = await h.call("models.scoped");
    expect("models.scoped 读回同两个", got.ok && got.data.models.map((m) => m.id).join() === two.map((m) => m.id).join(), JSON.stringify(got.data));
    expect("models.setScoped 不存在的模型 → not_found", (await h.call("models.setScoped", { models: [{ provider: "nope", id: "nope" }] })).error?.code === "not_found");
    expect("models.setScoped 缺字段 → bad_args", (await h.call("models.setScoped", { models: [{ id: "x" }] })).error?.code === "bad_args");
    expect("models.setScoped 非数组 → bad_args", (await h.call("models.setScoped", { models: "x" })).error?.code === "bad_args");
    const clr = await h.call("models.setScoped", { models: [] });
    expect("models.setScoped 空数组 = 取消作用域", clr.ok && clr.data.state.scopedModelCount === 0);
  }
  // ---- 模型目录与 persist（SDK ModelMutationOptions：默认只改本会话）----
  const allList = await h.call("model.list", { all: true });
  const allModels = allList.data?.models ?? [];
  expect("model.list all:true 含还没配置凭据的供应商，且每条带 available",
    allList.ok && allModels.length > mods.length && allModels.every((m) => typeof m.available === "boolean") && allModels.some((m) => m.available === false),
    JSON.stringify(allList.error ?? { all: allModels.length, avail: mods.length }));
  const oneProv = mods[0]?.provider;
  const oneList = await h.call("model.list", { all: true, provider: oneProv });
  expect("model.list all:true provider 只取那一家", oneList.ok && oneList.data.models.length > 0 && oneList.data.models.every((m) => m.provider === oneProv));
  expect("model.list all 非布尔 → bad_args", (await h.call("model.list", { all: "yes" })).error?.code === "bad_args");
  if (mods.length >= 2) {
    const before = (await h.call("settings.get")).data.global ?? {};
    const target = mods.find((m) => !(m.provider === before.defaultProvider && m.id === before.defaultModel)) ?? mods[1];
    const sess = await h.call("model.set", { provider: target.provider, modelId: target.id });
    const afterSess = (await h.call("settings.get")).data.global ?? {};
    expect("model.set 默认只改本会话，不写 defaultModel（会话里确实换了，全局默认没动）",
      sess.ok && sess.data.state?.model?.id === target.id && afterSess.defaultModel !== target.id && afterSess.defaultModel === before.defaultModel,
      JSON.stringify({ was: before.defaultModel, now: afterSess.defaultModel, target: target.id }));
    const other = mods.find((m) => m.id !== target.id);
    const pers = await h.call("model.set", { provider: other.provider, modelId: other.id, persist: true });
    const afterPers = (await h.call("settings.get")).data.global ?? {};
    expect("model.set persist:true 写进全局默认", pers.ok && afterPers.defaultModel === other.id && afterPers.defaultProvider === other.provider,
      JSON.stringify(pers.error ?? afterPers));
    expect("model.set persist 非布尔 → bad_args", (await h.call("model.set", { provider: other.provider, modelId: other.id, persist: 1 })).error?.code === "bad_args");
  }
  expect("model.cycle 非法 direction → bad_args", (await h.call("model.cycle", { direction: "sideways" })).error?.code === "bad_args");
  const lv = await h.call("thinking.levels");
  expect("thinking.levels 带 supportsThinking", lv.ok && Array.isArray(lv.data.levels) && typeof lv.data.supportsThinking === "boolean", JSON.stringify(lv.error ?? lv.data));
  // ---- 目录刷新：SDK 的 errors 是 Map，不能 JSON.stringify（会变 {}）----
  const rf = await h.call("auth.refreshModels");
  expect("auth.refreshModels 返回 { aborted:boolean, errors:{providerId:string} }",
    rf.ok && typeof rf.data.result?.aborted === "boolean" && rf.data.result?.errors && typeof rf.data.result.errors === "object" &&
      Object.values(rf.data.result.errors).every((m) => typeof m === "string"),
    JSON.stringify(rf.error ?? rf.data));
  const { wireRefresh } = await import("../../app/src/main/assets/guest/sdk-host/commands/auth.mjs");
  const wired = wireRefresh({ aborted: false, errors: new Map([["openrouter", new Error("fetch failed")], ["x", "plain"]]) });
  expect("wireRefresh：Map<id, Error> 摊成 { id: message }（修前是 {}）",
    wired.errors.openrouter === "fetch failed" && wired.errors.x === "plain" && wired.aborted === false, JSON.stringify(wired));
  expect("wireRefresh：缺字段不炸", JSON.stringify(wireRefresh(undefined)) === '{"aborted":false,"errors":{}}');
  const cs = await h.call("cache.status");
  expect("cache.status 有 mode 与 status 字段", cs.ok && typeof cs.data.mode === "string" && "status" in cs.data, JSON.stringify(cs.error ?? cs.data));
  const cm = await h.call("cache.setMode", { mode: cs.data?.mode ?? "off" });
  expect("cache.setMode 设回原值", cm.ok && cm.data.mode === cs.data.mode, JSON.stringify(cm.error ?? cm.data));
  expect("cache.setMode 非法值被拒绝（SDK 自己不校验）", (await h.call("cache.setMode", { mode: "definitely-not-a-mode" })).error?.code === "bad_args");
  expect("非法值没有落进设置", (await h.call("cache.status")).data.mode === cs.data.mode);
  const ac = await h.call("session.abortCompaction");
  expect("session.abortCompaction 没在压缩时也不报错", ac.ok && ac.data.wasCompacting === false);
  expect("session.abortBranchSummary 无事可做不报错", (await h.call("session.abortBranchSummary")).ok);
  const sp = await h.call("session.systemPrompt");
  expect("session.systemPrompt 返回非空文本", sp.ok && typeof sp.data.text === "string" && sp.data.text.length > 50);
  const ct = await h.call("tools.callable");
  expect("tools.callable 是名字数组", ct.ok && Array.isArray(ct.data.names));
  const qm = await h.call("queue.messages");
  expect("queue.messages 空队列", qm.ok && qm.data.steering.length === 0 && qm.data.followUp.length === 0);
  const ej = await h.call("session.exportJsonl", {});
  expect("session.exportJsonl 默认路径（工作目录）", ej.ok && existsSync(ej.data.path), JSON.stringify(ej.error ?? ej.data));
  expect("session.exportJsonl 拒绝工作目录之外", (await h.call("session.exportJsonl", { path: "/tmp/escape.jsonl" })).error?.code === "bad_args");
  expect("session.bugReportCancel 没有在跑时返回 wasRunning:false", (await h.call("session.bugReportCancel")).data?.wasRunning === false);
  const snap2 = await h.call("session.snapshot");
  expect("快照带 routedModel（无虚拟选择为 null）与 scopedModelCount", snap2.ok && snap2.data.routedModel === null && snap2.data.scopedModelCount === 0, JSON.stringify(snap2.data).slice(0, 160));

  // ---- 6d. models.json 事务：增改删 / 非法回滚（SDK 自己当校验器） ----
  const mc0 = await h.call("models.config.get");
  // 文件不是"初始没有"了：上面为了绕过 prompt 预检写了一份（probe-local）。要验的是
  // 「读回来的就是磁盘上那份」，以及后面的增改删都写在它之上。
  expect("models.config.get 读回启动时写的那份", mc0.ok && mc0.data.exists === true && mc0.data.error === null && mc0.data.config?.providers?.["probe-local"], JSON.stringify(mc0.error ?? mc0.data).slice(0, 200));
  const good = { baseUrl: "http://127.0.0.1:1/v1", apiKey: "k", api: "openai-completions", models: [{ id: "m1", name: "M1" }] };
  const mcSet = await h.call("models.config.setProvider", { id: "mine", provider: good });
  expect("models.config.setProvider 新增", mcSet.ok && mcSet.data.providers.includes("mine"), JSON.stringify(mcSet.error ?? mcSet.data));
  const ml2 = await h.call("model.list");
  expect("新增的自定义模型立刻出现在 model.list", JSON.stringify(ml2.data).includes('"m1"'), JSON.stringify(ml2.error ?? ml2.data).slice(0, 120));
  const fileOnDisk = () => readFileSync(join(h.agentDir, "models.json"), "utf8");
  const okText = fileOnDisk();
  const badSet = await h.call("models.config.setProvider", { id: "mine", provider: { baseUrl: 5 } });
  expect("非法配置被拒绝，且原话来自 SDK 校验（精确到字段）", badSet.error?.code === "rejected" && /providers\.mine\.baseUrl.*must be string/.test(badSet.error.message), JSON.stringify(badSet.error));
  expect("非法保存后磁盘上仍是上一份能用的配置", fileOnDisk() === okText);
  expect("回滚之后自定义模型仍可用", JSON.stringify((await h.call("model.list")).data).includes('"m1"'));
  const keep = JSON.parse(okText);
  writeFileSync(join(h.agentDir, "models.json"), JSON.stringify({ ...keep, futureField: { x: 1 } }));
  await h.call("models.config.setProvider", { id: "other", provider: { ...good, models: [{ id: "m2" }] } });
  expect("文件里其他顶层字段原样保留", JSON.parse(fileOnDisk()).futureField?.x === 1);
  expect("models.config.removeProvider", (await h.call("models.config.removeProvider", { id: "other" })).ok && !("other" in JSON.parse(fileOnDisk()).providers));
  expect("删不存在的 → not_found", (await h.call("models.config.removeProvider", { id: "ghost" })).error?.code === "not_found");
  writeFileSync(join(h.agentDir, "models.json"), "{ broken");
  expect("文件损坏时 setProvider 拒绝叠加", (await h.call("models.config.setProvider", { id: "z", provider: good })).error?.code === "rejected");
  expect("models.config.get 报出 SDK 的解析错误", /Failed to parse models.json/.test((await h.call("models.config.get")).data.error ?? ""));
  expect("整份 set 可以修好损坏的文件", (await h.call("models.config.set", { config: { providers: {} } })).ok);
  expect("models.config.setProvider 缺 id → bad_args", (await h.call("models.config.setProvider", { provider: good })).error?.code === "bad_args");

  // ---- 6d. 已加载资源与项目信任（对照 SDK：resource-loader / trust-manager / project-trust） ----
  mkdirSync(join(h.agentDir, "skills", "user-skill"), { recursive: true });
  writeFileSync(join(h.agentDir, "skills", "user-skill", "SKILL.md"), "---\nname: user-skill\ndescription: 用户级技能\n---\n正文\n");
  mkdirSync(join(h.agentDir, "prompts"), { recursive: true });
  writeFileSync(join(h.agentDir, "prompts", "user-prompt.md"), "---\ndescription: 用户级提示\nargument-hint: <x>\n---\n你好 $1\n");
  await h.call("session.reload", {}, 60000);
  const ld = await h.call("resources.loaded");
  const lx = ld.data?.extensions?.find((e) => (e.commands ?? []).some((c) => c.name === "probe-ping"));
  expect("resources.loaded：扩展带出它注册的命令 / 工具 / flag", ld.ok && lx && lx.tools.length > 0 && lx.flags.some((f) => f.name === "probe-mode" && f.type === "string"), JSON.stringify(ld.error ?? lx));
  const lsk = ld.data?.skills?.find((k) => k.name === "user-skill");
  expect("resources.loaded：技能带描述 / 路径 / 来源 / 是否禁止模型自动调用", lsk && lsk.description === "用户级技能" && lsk.filePath.endsWith("SKILL.md") && lsk.source?.scope === "user" && lsk.disableModelInvocation === false, JSON.stringify(lsk));
  const lpr = ld.data?.prompts?.find((p) => p.name === "user-prompt");
  expect("resources.loaded：提示模板带参数提示与正文预览", lpr && lpr.argumentHint === "<x>" && lpr.body.text.includes("你好") && lpr.body.truncated === false, JSON.stringify(lpr));
  expect("resources.loaded：诊断三类都是数组", ["skillDiagnostics", "promptDiagnostics", "themeDiagnostics", "extensionErrors", "extensionWarnings", "contextFiles", "appendSystemPrompt"].every((k) => Array.isArray(ld.data?.[k])));

  const ts0 = await h.call("project.trustState");
  expect("project.trustState：没有需要信任的项目资源 → 信任，来源 nothing-to-trust", ts0.ok && ts0.data.trusted === true && ts0.data.needsTrust === false && ts0.data.source === "nothing-to-trust" && ts0.data.entry === null, JSON.stringify(ts0.error ?? ts0.data));
  mkdirSync(join(h.work, ".pi", "skills", "proj-skill"), { recursive: true });
  writeFileSync(join(h.work, ".pi", "skills", "proj-skill", "SKILL.md"), "---\nname: proj-skill\ndescription: 项目级技能\n---\n正文\n");
  const ts1 = await h.call("project.trustState");
  expect("项目里出现 .pi/skills 且没表态 → 未信任并待询问", ts1.data?.needsTrust === true && ts1.data.undecided === true && ts1.data.trusted === false && ts1.data.source === "ask", JSON.stringify(ts1.data));
  const skillNames = async () => (await h.call("resources.loaded")).data.skills.map((k) => k.name);
  const trustYes = await h.call("project.trust", { trusted: true, remember: "folder" }, 60000);
  expect("project.trust folder：写进信任库，结论 true", trustYes.ok && trustYes.data.trusted === true && trustYes.data.entry?.decision === true && trustYes.data.entry?.inherited === false && trustYes.data.source === "store", JSON.stringify(trustYes.error ?? trustYes.data));
  expect("信任后**已在跑的会话**立刻加载项目技能（SettingsManager 信任态已更新）", (await skillNames()).includes("proj-skill"));
  const trustNo = await h.call("project.trust", { trusted: false, remember: "folder" }, 60000);
  expect("改为不信任 → 项目技能立刻卸掉", trustNo.ok && trustNo.data.trusted === false && !(await skillNames()).includes("proj-skill"));
  const trustSess = await h.call("project.trust", { trusted: true, remember: "session" }, 60000);
  expect("project.trust session：只管这一次，信任库里仍是上次的「不信任」", trustSess.data?.trusted === true && trustSess.data.source === "session" && trustSess.data.entry?.decision === false && (await skillNames()).includes("proj-skill"), JSON.stringify(trustSess.data));
  const trustReset = await h.call("project.trust", { trusted: false, remember: "reset" }, 60000);
  expect("project.trust reset：清掉记录，回到待询问", trustReset.data?.entry === null && trustReset.data.undecided === true && trustReset.data.trusted === false, JSON.stringify(trustReset.error ?? trustReset.data));
  const trustParent = await h.call("project.trust", { trusted: true, remember: "parent" }, 60000);
  expect("project.trust parent：记在父目录，本目录是「继承」", trustParent.ok && trustParent.data.trusted === true && trustParent.data.entry?.inherited === true && trustParent.data.entry.path === trustParent.data.parent, JSON.stringify(trustParent.error ?? trustParent.data));
  expect("「信任父目录」不能用于不信任 → bad_args", (await h.call("project.trust", { trusted: false, remember: "parent" })).error?.code === "bad_args");
  expect("未知 remember → bad_args", (await h.call("project.trust", { trusted: true, remember: "forever" })).error?.code === "bad_args");
  const tof = await h.call("project.trustOf", { cwds: [h.work, join(h.work, "nowhere"), "relative/x", 7] });
  expect("project.trustOf：每个绝对路径各给结论，相对路径与非字符串被忽略", tof.ok && Object.keys(tof.data.trust).length === 2 && tof.data.trust[h.work]?.trusted === true && tof.data.trust[h.work].source === "store" && typeof tof.data.trust[join(h.work, "nowhere")]?.undecided === "boolean", JSON.stringify(tof.error ?? tof.data));
  expect("project.trustOf：没有 cwds 也不报错", (await h.call("project.trustOf", {})).data?.trust && Object.keys((await h.call("project.trustOf", {})).data.trust).length === 0);
  const clearInh = await h.call("project.trust", { trusted: false, remember: "reset-inherited" }, 60000);
  expect("project.trust reset-inherited：清掉父目录那条记录", clearInh.ok && clearInh.data.entry === null && clearInh.data.undecided === true, JSON.stringify(clearInh.error ?? clearInh.data));
  expect("没有记录时 reset-inherited → bad_args", (await h.call("project.trust", { trusted: false, remember: "reset-inherited" })).error?.code === "bad_args");
  const settingsPath = join(h.agentDir, "settings.json");
  const settingsNow = JSON.parse(readFileSync(settingsPath, "utf8"));
  writeFileSync(settingsPath, JSON.stringify({ ...settingsNow, defaultProjectTrust: "always" }));
  const dfAlways = await h.call("project.trustState");
  expect("defaultProjectTrust=always：没表态也信任，来源 default", dfAlways.data?.trusted === true && dfAlways.data.undecided === false && dfAlways.data.source === "default" && dfAlways.data.defaultProjectTrust === "always", JSON.stringify(dfAlways.data));
  writeFileSync(settingsPath, JSON.stringify({ ...settingsNow, defaultProjectTrust: "never" }));
  const dfNever = await h.call("project.trustState");
  expect("defaultProjectTrust=never：没表态直接不信任，不再询问", dfNever.data?.trusted === false && dfNever.data.undecided === false && dfNever.data.source === "default", JSON.stringify(dfNever.data));
  writeFileSync(settingsPath, JSON.stringify(settingsNow));
  await h.call("project.trust", { trusted: false, remember: "reset" }, 60000);

  // ---- 6d2. 资源文件：读 / 改 / 建 / 删（位置表照 SDK resource-loader） ----
  {
    // 前面信任测试最后一步是「信任父目录」，项目层此刻可写。
    const skillPath = join(h.agentDir, "skills", "user-skill", "SKILL.md");
    const rd = await h.call("resources.file.read", { path: skillPath });
    expect("resources.file.read：技能文件带内容、指纹、类型、可写", rd.ok && rd.data.text.includes("用户级技能") && rd.data.kind === "skill" && rd.data.scope === "user" && rd.data.writable === true && typeof rd.data.mtimeMs === "number" && rd.data.rel === "user-skill/SKILL.md", JSON.stringify(rd.error ?? rd.data));
    const wr = await h.call("resources.file.write", { path: skillPath, text: rd.data.text.replace("正文", "改过的正文"), expectSize: rd.data.size, expectMtimeMs: rd.data.mtimeMs });
    expect("resources.file.write：指纹对得上就写，返回新指纹", wr.ok && readFileSync(skillPath, "utf8").includes("改过的正文"), JSON.stringify(wr.error ?? wr.data));
    const stale = await h.call("resources.file.write", { path: skillPath, text: "x", expectSize: rd.data.size, expectMtimeMs: rd.data.mtimeMs });
    expect("resources.file.write：文件被别处改过（指纹不符）→ busy，不盖", stale.error?.code === "busy" && readFileSync(skillPath, "utf8").includes("改过的正文"), JSON.stringify(stale.error ?? stale.data));
    for (const bad of [join(h.agentDir, "settings.json"), join(h.agentDir, "models.json"), join(h.agentDir, "auth.json"), join(h.agentDir, "sessions", "x.jsonl"), join(h.agentDir, "prompts", "sub", "a.md"), join(h.agentDir, "prompts", "a.txt"), join(h.agentDir, "themes", "a.md"), "/etc/passwd", join(h.agentDir, "skills", "..", "settings.json")]) {
      const w = await h.call("resources.file.write", { path: bad, text: "{}" });
      expect(`resources.file.write 拒绝白名单之外：${bad.replace(h.agentDir, "<agent>")}`, w.error?.code === "rejected", JSON.stringify(w.error ?? w.data));
    }
    const rdBad = await h.call("resources.file.read", { path: join(h.agentDir, "auth.json") });
    expect("resources.file.read 也不给读凭据", rdBad.error?.code === "rejected", JSON.stringify(rdBad.error ?? rdBad.data));

    const mk = async (args) => h.call("resources.file.create", { scope: "user", ...args });
    for (const [n, label] of [["Bad", "大写"], ["a--b", "连续连字符"], ["-a", "开头连字符"], ["a b", "空格"], ["", "空"]]) {
      expect(`新建技能：非法名（${label}）→ bad_args`, (await mk({ kind: "skill", name: n })).error?.code === "bad_args");
    }
    const sk = await mk({ kind: "skill", name: "made-skill" });
    expect("新建技能：写出带 name/description 的 SKILL.md", sk.ok && readFileSync(sk.data.path, "utf8").startsWith("---\nname: made-skill\ndescription:"), JSON.stringify(sk.error ?? sk.data));
    expect("新建同名 → rejected", (await mk({ kind: "skill", name: "made-skill" })).error?.code === "rejected");
    const pr = await mk({ kind: "prompt", name: "made-prompt" });
    const ex = await mk({ kind: "extension", name: "made-ext" });
    const cx = await mk({ kind: "context", name: "AGENTS.md" });
    const sy = await mk({ kind: "context", name: "SYSTEM.md" });
    expect("新建提示 / 扩展 / 上下文 / 系统提示：路径按 SDK 位置", pr.data?.path === join(h.agentDir, "prompts", "made-prompt.md") && ex.data?.path === join(h.agentDir, "extensions", "made-ext.ts") && cx.data?.path === join(h.agentDir, "AGENTS.md") && sy.data?.path === join(h.agentDir, "SYSTEM.md"), JSON.stringify([pr, ex, cx, sy].map((r) => r.error ?? r.data?.path)));
    expect("新建上下文：只认 AGENTS/CLAUDE/SYSTEM/APPEND_SYSTEM", (await mk({ kind: "context", name: "settings.json" })).error?.code === "bad_args");
    await h.call("session.reload", {}, 60000);
    const after = (await h.call("resources.loaded")).data;
    expect("新建的技能 / 提示 / 扩展 / 上下文 / 系统提示，pi 重载后真的加载了", after.skills.some((k) => k.name === "made-skill") && after.prompts.some((k) => k.name === "made-prompt") && after.extensions.some((e) => e.path.endsWith("made-ext.ts")) && after.contextFiles.some((f) => f.path === join(h.agentDir, "AGENTS.md")) && typeof after.systemPrompt === "string", JSON.stringify({ s: after.skills.map((k) => k.name), x: after.extensionErrors }));
    const dl = await h.call("resources.file.delete", { path: sk.data.path });
    expect("删技能：删掉整个技能目录", dl.ok && dl.data.removed === "directory" && !existsSync(dirname(sk.data.path)), JSON.stringify(dl.error ?? dl.data));
    const dp = await h.call("resources.file.delete", { path: pr.data.path });
    expect("删提示：只删文件", dp.ok && dp.data.removed === "file" && !existsSync(pr.data.path));
    for (const p of [ex.data.path, cx.data.path, sy.data.path]) await h.call("resources.file.delete", { path: p });
    expect("删不存在的 → not_found", (await h.call("resources.file.delete", { path: pr.data.path })).error?.code === "not_found");
    expect("删白名单之外 → rejected", (await h.call("resources.file.delete", { path: join(h.agentDir, "settings.json") })).error?.code === "rejected");

    // 项目层：未信任时不让动
    const projSkill = join(h.work, ".pi", "skills", "proj-skill", "SKILL.md");
    await h.call("project.trust", { trusted: false, remember: "session" }, 60000);
    const pw = await h.call("resources.file.write", { path: projSkill, text: "---\nname: proj-skill\ndescription: x\n---\n" });
    expect("项目未信任：改项目资源 → rejected", pw.error?.code === "rejected", JSON.stringify(pw.error ?? pw.data));
    expect("项目未信任：新建项目资源 → rejected", (await h.call("resources.file.create", { kind: "skill", scope: "project", name: "p2" })).error?.code === "rejected");
    await h.call("project.trust", { trusted: true, remember: "session" }, 60000);
    const pw2 = await h.call("resources.file.write", { path: projSkill, text: "---\nname: proj-skill\ndescription: 改了\n---\n" });
    expect("信任后可改项目资源", pw2.ok && readFileSync(projSkill, "utf8").includes("改了"), JSON.stringify(pw2.error ?? pw2.data));
    const pk = await h.call("resources.file.create", { kind: "context", scope: "project", name: "AGENTS.md" });
    expect("项目 AGENTS.md 放项目根（pi 从 cwd 往上找）", pk.ok && pk.data.path === join(h.work, "AGENTS.md"), JSON.stringify(pk.error ?? pk.data));
    const ps = await h.call("resources.file.create", { kind: "context", scope: "project", name: "SYSTEM.md" });
    expect("项目 SYSTEM.md 放 .pi 里", ps.ok && ps.data.path === join(h.work, ".pi", "SYSTEM.md"), JSON.stringify(ps.error ?? ps.data));
    for (const p of [pk.data.path, ps.data.path]) await h.call("resources.file.delete", { path: p });
  }

  // ---- 6e. 扩展 flag / 诊断 / 工具详情 ----
  const fl = await h.call("catalog.flags");
  const fm = fl.data?.flags?.find((f) => f.name === "probe-mode");
  expect("catalog.flags 列出扩展注册的 flag（类型/默认值/当前值）", fl.ok && fm?.type === "string" && fm.default === "fast" && fm.value === "fast", JSON.stringify(fl.error ?? fl.data));
  expect("catalog.setFlag 改字符串 flag", (await h.call("catalog.setFlag", { name: "probe-mode", value: "slow" })).ok && (await h.call("catalog.flags")).data.flags.find((f) => f.name === "probe-mode").value === "slow");
  expect("catalog.setFlag 类型不符 → bad_args", (await h.call("catalog.setFlag", { name: "probe-loud", value: "yes" })).error?.code === "bad_args");
  expect("catalog.setFlag 不存在 → not_found", (await h.call("catalog.setFlag", { name: "ghost", value: true })).error?.code === "not_found");
  const dg = await h.call("catalog.diagnostics");
  expect("catalog.diagnostics 三个数组", dg.ok && Array.isArray(dg.data.commands) && Array.isArray(dg.data.shortcuts) && Array.isArray(dg.data.resources), JSON.stringify(dg.error ?? Object.keys(dg.data ?? {})));
  const ctl = await h.call("catalog.tools");
  const tool0 = ctl.data?.all?.[0];
  expect("catalog.tools 带 exposure / sourceInfo / parameters", ctl.ok && tool0 && "exposure" in tool0 && tool0.sourceInfo && "parameters" in tool0 && Array.isArray(ctl.data.callable), JSON.stringify(tool0)?.slice(0, 160));

  // ---- 7. 进程内服务 ----------------------------------------------------
  const insp = async (source) => (await h.call("packages.inspect", { source })).data?.info;
  const iNpm = await insp("npm:@scope/pkg@1.2.3"), iRange = await insp("npm:pkg@^1.0.0"), iBare = await insp("npm:pkg");
  expect("packages.inspect：npm 的名字 / 版本 / 是否固定由官方解析器说了算", iNpm?.kind === "npm" && iNpm.name === "@scope/pkg" && iNpm.version === "1.2.3" && iNpm.pinned === true && iRange?.pinned === false && iBare?.version === null, JSON.stringify([iNpm, iRange, iBare]));
  const iGit = await insp("git:github.com/user/repo@v1"), iHttps = await insp("https://github.com/user/repo");
  expect("packages.inspect：git 与 ref（带 ref 即固定）", iGit?.kind === "git" && iGit.name === "github.com/user/repo" && iGit.version === "v1" && iGit.pinned === true && iHttps?.kind === "git" && iHttps.pinned === false, JSON.stringify([iGit, iHttps]));
  const iLocal = await insp("./local-pkg"), iNaked = await insp("some-name");
  expect("packages.inspect：没有 npm:/git 前缀的名字是本地路径", iLocal?.kind === "local" && iNaked?.kind === "local" && iNaked.name === "some-name", JSON.stringify([iLocal, iNaked]));
  const pk = await h.call("packages.list");
  expect("packages.list（进程内，不起子进程）", pk.ok && Array.isArray(pk.data.packages), JSON.stringify(pk.error));

  // 扩展事件总线桥：订阅 → 发事件 → App 收到原样转发的 bus 事件。
  const sub = await h.call("bus.subscribe", { names: ["probe:ping"] });
  expect("bus.subscribe", sub.ok && sub.data.subscribed.includes("probe:ping"), JSON.stringify(sub));
  await h.call("bus.emit", { name: "probe:ping", data: { n: 1 } });
  const busEvt = await h.waitFor(h.evt("bus", (d) => d.name === "probe:ping"), 5000).catch(() => null);
  expect("bus 事件原样转发给 App", busEvt?.data.data.n === 1, JSON.stringify(busEvt));

  // ---- 7b. 认证：供应商 / API Key / OAuth 登录 ----------------------------
  const provs = await h.call("auth.providers");
  const sso = provs.data?.providers?.find((p) => p.id === "probe-sso");
  expect("auth.providers 列出内置与扩展供应商（含认证方式）", provs.ok && provs.data.providers.length > 20 && sso?.methods.some((m) => m.type === "oauth" && m.name === "Probe SSO"), JSON.stringify(provs.error ?? sso));
  const anth = provs.data.providers.find((p) => p.id === "anthropic");
  expect("内置供应商带 api_key 方式", anth?.methods.some((m) => m.type === "api_key"));
  expect("auth.providers 带 usingSubscription（订阅 ≠ 任意 OAuth）", provs.data.providers.every((p) => typeof p.usingSubscription === "boolean"));

  const setKey = await h.call("auth.setApiKey", { providerId: "openai", apiKey: "sk-test-123" });
  expect("auth.setApiKey", setKey.ok && setKey.data.provider.configured === true, JSON.stringify(setKey.error ?? setKey.data));
  const creds = await h.call("auth.credentials");
  expect("凭据列表里有它", creds.ok && creds.data.credentials.some((c) => c.providerId === "openai" && c.type === "api_key"));
  const authFile = join(h.root, "agent", "auth.json");
  expect("API Key 落盘到 auth.json（不是仅本次运行）", existsSync(authFile) && readFileSync(authFile, "utf8").includes("sk-test-123"));
  const rmKey = await h.call("auth.removeApiKey", { providerId: "openai" });
  expect("auth.removeApiKey", rmKey.ok && !(await h.call("auth.credentials")).data.credentials.some((c) => c.providerId === "openai"));
  const badKey = await h.call("auth.setApiKey", { providerId: "no-such", apiKey: "x" });
  expect("未知供应商 → not_found", !badKey.ok && badKey.error.code === "not_found");
  const noOauth = await h.call("auth.login", { providerId: "deepseek", type: "oauth" });
  expect("供应商不支持的登录方式 → unsupported", !noOauth.ok && noOauth.error.code === "unsupported", JSON.stringify(noOauth));

  // OAuth 全流程：事件（链接/进度）→ 向 App 提问 → 完成
  const loginP = h.call("auth.login", { providerId: "probe-sso", loginId: "L1" }, 20000);
  const authEvt = await h.waitFor(h.evt("auth.event", (d) => d.event.type === "auth_url"), 10000);
  expect("auth.event 带授权链接", authEvt.data.loginId === "L1" && authEvt.data.event.url === "https://probe.invalid/authorize");
  const login = await loginP;
  expect("auth.login 完成并返回新状态", login.ok && login.data.credentialType === "oauth" && login.data.provider.configured === true, JSON.stringify(login.error ?? login.data));
  expect("OAuth 凭据在凭据列表里", (await h.call("auth.credentials")).data.credentials.some((c) => c.providerId === "probe-sso" && c.type === "oauth"));
  await h.waitFor(h.evt("auth.changed", (d) => d.action === "login"), 3000);
  expect("登录后发 auth.changed", true);

  // 取消：登录卡在 prompt 之后的等待里，auth.cancel 能中止
  globalThis.__authAnswer = "never";
  await h.call("auth.logout", { providerId: "probe-sso" });
  const loginC = h.call("auth.login", { providerId: "probe-sso", loginId: "L2" }, 20000);
  await h.waitFor(h.evt("auth.event", (d) => d.loginId === "L2"), 10000);
  await new Promise((r) => setTimeout(r, 200));
  const dup = await h.call("auth.login", { providerId: "probe-sso", loginId: "L3" });
  expect("同一供应商同时只允许一个登录 → busy", !dup.ok && dup.error.code === "busy", JSON.stringify(dup));
  const cancel = await h.call("auth.cancel", { loginId: "L2" });
  const cancelled = await loginC;
  expect("auth.cancel 中止进行中的登录", cancel.data.cancelled === true && !cancelled.ok && cancelled.error.message.includes("取消"), JSON.stringify(cancelled));
  const out = await h.call("auth.logout", { providerId: "probe-sso" });
  expect("auth.logout", out.ok && !(await h.call("auth.credentials")).data.credentials.some((c) => c.providerId === "probe-sso"));
  globalThis.__authAnswer = undefined;

  // ---- 7c. 包：装 / 资源清单 / 开关 / 卸 --------------------------------------
  const pkgDir = join(h.root, "probe-pkg");
  mkdirSync(pkgDir, { recursive: true });
  writeFileSync(join(pkgDir, "package.json"), JSON.stringify({ name: "probe-pkg", version: "1.0.0", pi: { extensions: ["./ext.mjs"] } }));
  writeFileSync(join(pkgDir, "ext.mjs"), "export default function () {}\n");
  const inst = await h.call("packages.install", { source: pkgDir, opId: "P1" }, 120000);
  expect("packages.install（本地目录包）", inst.ok && inst.data.packages.some((p) => p.installedPath === pkgDir), JSON.stringify(inst.error ?? inst.data));
  // 同一拍发出多个，至少有一个得到 busy（单发第二个会和第一个的完成赛跑，本地包的 update 几乎瞬间结束）。
  const ops = await Promise.all(["P2", "P3", "P4", "P5"].map((opId) => h.call("packages.update", { opId }, 60000)));
  const busyOp2 = ops.find((r) => !r.ok) ?? ops[ops.length - 1];
  expect("包操作互斥：并发的后来者 → busy", !busyOp2.ok && busyOp2.error.code === "busy", JSON.stringify(ops));

  const res = await h.call("resources.list", {}, 120000);
  const extItems = (res.data?.global ?? []).flatMap((g) => g.subgroups).filter((s) => s.type === "extensions").flatMap((s) => s.items);
  const pkgExt = extItems.find((i) => i.path.endsWith("probe-pkg/ext.mjs"));
  expect("resources.list 列出包里的扩展且默认启用", res.ok && pkgExt?.enabled === true, JSON.stringify(res.error ?? extItems.map((i) => i.path)));
  expect("resources.list 带内置扩展（mcp 等）", extItems.some((i) => /builtin:mcp|mcp/.test(i.path)));
  const tog = await h.call("resources.toggle", { scope: "global", type: "extensions", path: pkgExt.path }, 120000);
  const afterTog = tog.data?.global?.flatMap((g) => g.subgroups).flatMap((s) => s.items).find((i) => i.path === pkgExt.path);
  expect("resources.toggle 关掉，并返回写完之后的清单", tog.ok && tog.data.enabled === false && afterTog?.enabled === false, JSON.stringify(tog.error ?? tog.data?.enabled));
  expect("开关写进了 settings.json（pi config 同款规则）", readFileSync(join(h.agentDir, "settings.json"), "utf8").includes("-"));
  const togBad = await h.call("resources.toggle", { scope: "global", type: "extensions", path: "/no/such" }, 60000);
  expect("toggle 不存在的资源 → not_found", !togBad.ok && togBad.error.code === "not_found");

  const rm = await h.call("packages.remove", { source: inst.data.packages.find((p) => p.installedPath === pkgDir).source }, 120000);
  expect("packages.remove", rm.ok && !rm.data.packages.some((p) => p.installedPath === pkgDir), JSON.stringify(rm.error ?? rm.data));

  // ---- 7d. MCP：增 / 检测 / 启停 / 删 / 登录的拒绝路径 ----------------------
  const mcpAdd = await h.call("mcp.add", { name: "echo", command: "node", args: [join(here, "fixtures/mcp-echo.mjs")] }, 60000);
  expect("mcp.add（stdio）写进 mcp.json", mcpAdd.ok && readFileSync(join(h.agentDir, "mcp.json"), "utf8").includes('"echo"'), JSON.stringify(mcpAdd.error ?? mcpAdd.data));
  const addBoth = await h.call("mcp.add", { name: "x", command: "a", url: "http://b" });
  expect("mcp.add 同时给 url 和 command → bad_args", !addBoth.ok && addBoth.error.code === "bad_args");
  const cfg = await h.call("mcp.servers");
  const cfgEcho = cfg.data?.servers?.find((x) => x.name === "echo");
  expect("mcp.servers 只读配置、不连接（带来源 / 暴露方式 / 启停）", cfg.ok && cfgEcho?.enabled === true && typeof cfgEcho.exposure === "string" && cfgEcho.scope, JSON.stringify(cfg.error ?? cfg.data));
  const mcpList = await h.call("mcp.list", {}, 120000);
  const echo = mcpList.data?.servers?.find((s) => s.name === "echo");
  expect("mcp.list 真连上了服务器并列出工具", mcpList.ok && echo?.state === "connected" && echo.tools.includes("echo"), JSON.stringify(mcpList.error ?? mcpList.data));
  const mcpOff = await h.call("mcp.update", { name: "echo", enabled: false });
  expect("mcp.update 停用", mcpOff.ok);
  expect("停用后 mcp.list 不再连它", (await h.call("mcp.list", {}, 60000)).data.servers.find((s) => s.name === "echo")?.state === "disabled");
  const mcpExp = await h.call("mcp.update", { name: "echo", enabled: true, exposure: "direct" });
  expect("mcp.update 改暴露方式", mcpExp.ok && readFileSync(join(h.agentDir, "mcp.json"), "utf8").includes("direct"));
  const mcpBadExp = await h.call("mcp.update", { name: "echo", exposure: "nope" });
  expect("非法暴露方式 → bad_args", !mcpBadExp.ok && mcpBadExp.error.code === "bad_args");
  const mcpLogin = await h.call("mcp.login", { name: "echo" }, 30000);
  expect("stdio 服务器没有 OAuth → unsupported", !mcpLogin.ok && mcpLogin.error.code === "unsupported", JSON.stringify(mcpLogin));
  const mcpMissing = await h.call("mcp.update", { name: "ghost", enabled: false });
  expect("未知服务器 → not_found", !mcpMissing.ok && mcpMissing.error.code === "not_found");
  const mcpRm = await h.call("mcp.remove", { name: "echo" }, 60000);
  expect("mcp.remove", mcpRm.ok && !readFileSync(join(h.agentDir, "mcp.json"), "utf8").includes('"echo"'));

  // ---- 7e. 设置 ---------------------------------------------------------------
  const st0 = await h.call("settings.get");
  expect("settings.get 给有效 / 全局 / 项目三份", st0.ok && typeof st0.data.effective === "object" && "global" in st0.data && "project" in st0.data, JSON.stringify(st0.error));
  const st1 = await h.call("settings.set", { method: "setDefaultThinkingLevel", params: ["high"] });
  expect("settings.set 调的是 SDK 的 setter，立刻反映在有效设置里", st1.ok && st1.data.effective.defaultThinkingLevel === "high", JSON.stringify(st1.error ?? st1.data?.effective));
  expect("并落盘到 settings.json", readFileSync(join(h.agentDir, "settings.json"), "utf8").includes('"high"'));
  const st2 = await h.call("settings.set", { method: "setCompactionEnabled", params: [false] });
  expect("嵌套设置（compaction.enabled）也能写", st2.ok && st2.data.effective.compaction?.enabled === false, JSON.stringify(st2.data?.effective?.compaction));
  const stBad = await h.call("settings.set", { method: "flush", params: [] });
  expect("非 set 开头的方法被拒（不是任意调用口）", !stBad.ok && stBad.error.code === "bad_args");
  const stGhost = await h.call("settings.set", { method: "setNoSuchThing", params: [] });
  expect("不存在的设置方法 → bad_args", !stGhost.ok && stGhost.error.code === "bad_args");

  // ---- 7. cwd 跟着会话走：换项目不重启宿主 ------------------------------------
  const other = join(h.root, "other-project");
  mkdirSync(other, { recursive: true });
  const bounce = async (cmd, args) => {
    const m = h.frames.length;
    const call = h.call(cmd, args, 60000);
    const surf = await h.waitFor((f) => h.frames.indexOf(f) >= m && h.evt("ui.surface", (d) => d.mode === "custom")(f), 20000);
    await h.call("ui.close", { surfaceId: surf.data.surfaceId });
    return call;
  };
  const here0 = (await h.call("session.snapshot")).data;
  const moved = await bounce("session.new", { cwd: other });
  const there = (await h.call("session.snapshot")).data;
  expect("session.new{cwd} 在另一个项目里开新会话", moved.ok && there.cwd === other && there.sessionFile !== here0.sessionFile, JSON.stringify(moved.error ?? there.cwd));
  const hdr = JSON.parse(readFileSync(there.sessionFile, "utf8").split("\n")[0]);
  expect("新会话头里记的就是那个 cwd", hdr.type === "session" && hdr.cwd === other, JSON.stringify(hdr));
  const listedHere = await h.call("session.list", {});
  expect("session.list（非 all）跟着当前 cwd 走", listedHere.ok && listedHere.data.sessions.every((x) => x.cwd === other), JSON.stringify(listedHere.data?.sessions?.map((x) => x.cwd)));
  const nocwd = await h.call("session.new", { cwd: join(h.root, "nope") });
  expect("cwd 不存在 → bad_args，当前会话不动", nocwd.error?.code === "bad_args" && (await h.call("session.snapshot")).data.cwd === other, JSON.stringify(nocwd.error));
  const homeFile = join(dir, "2026-02-01T00-00-00-000Z_home-fixture.jsonl");
  writeFileSync(homeFile, JSON.stringify({ type: "session", version: 3, id: "home-fixture", timestamp: new Date().toISOString(), cwd: here0.cwd }) + "\n");
  const back = await bounce("session.switch", { sessionPath: homeFile });
  expect("session.switch 回到原项目，cwd 随之回来", back.ok && (await h.call("session.snapshot")).data.cwd === here0.cwd, JSON.stringify([back.error, back.data?.cancelled, (await h.call("session.snapshot")).data.cwd, here0.cwd]));

  // ---- 8. 退出 ----------------------------------------------------------
  await h.call("host.shutdown");
  const code = await new Promise((r) => (h.child.exitCode !== null ? r(h.child.exitCode) : h.child.once("exit", r)));
  expect("host.shutdown 干净退出", code === 0, `exit=${code}`);
} catch (error) {
  expect("测试过程没有异常", false, error?.stack ?? String(error));
  console.log("--- stderr tail ---\n" + h.stderrLines.join("").split("\n").slice(-15).join("\n"));
} finally {
  probeServer.close();
  h.stop();
  rmSync(h.root, { recursive: true, force: true });
}

const failed = results.filter((r) => !r.ok);
console.log(failed.length ? `\n${failed.length} FAILED` : `\nharness: OK (sdk-host, ${results.length} checks)`);
process.exit(failed.length ? 1 : 0);
