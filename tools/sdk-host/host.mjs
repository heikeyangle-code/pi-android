#!/usr/bin/env node
/**
 * Pi SDK Host —— App 与 pi 引擎之间的进程内宿主。
 *
 * 为什么存在：`pi --mode rpc` 的宿主上下文是 pi 写得残缺的实现（`rpc-mode.js` 里 17 处
 * "not supported in RPC mode"），扩展的 `ctx.ui.custom()`、`setWidget(工厂)`、消息渲染器
 * 全部够不着。这个宿主改用官方 SDK 自己建 session、自己实现 `ExtensionUIContext`，
 * 于是同样的扩展代码在手机上能画界面、能收到按键。
 *
 * 协议（一行一个 JSON，stdin/stdout）：
 *   入：{id, type:"hello"|"open"|"prompt"|"abort"|"state"|"subagent"|"ui.response"|"ui.input"|"ui.resize"}
 *   出：{type:"response", id, ok, data|error} / {type:"event", event} / {type:"ui", ...}
 *
 * 用法（在 guest 里）：
 *   node sdk-host.mjs --cwd <工作区> --agent-dir <agentDir> [--session-dir <dir>]
 *                     [--extension <path>]... [--trust-project]
 *
 * 设计要点：
 *  - 一进程一工作区（与 App 的 EngineHost 对齐）；
 *  - UI 组件只走 `render(width)` + `handleInput(data)` 两个方法，宿主按 App 给的字符宽度
 *    反复重渲染 —— 这是 pi-tui 组件在手机上可交互的唯一通路；
 *  - 子代理走 pi-subagents 自己的进程内 RPC（`subagents:rpc:v1:*`），不用键盘注入；
 *  - 项目信任默认 **关**（SDK 的 SettingsManager 默认 true，直接照搬会把项目级扩展当作
 *    已信任加载）。
 */

import { createInterface } from "node:readline";
import { randomUUID } from "node:crypto";
import { existsSync } from "node:fs";
import { resolve } from "node:path";

import {
  createAgentSession,
  DefaultResourceLoader,
  ModelRuntime,
  SessionManager,
  SettingsManager,
  createEventBus,
} from "@earendil-works/pi-coding-agent";

// --------------------------------------------------------------------------- args

function arg(name, fallback) {
  const i = process.argv.indexOf(`--${name}`);
  return i > 0 && process.argv[i + 1] ? process.argv[i + 1] : fallback;
}

function multiArg(name) {
  const out = [];
  process.argv.forEach((value, i) => {
    if (value === `--${name}` && process.argv[i + 1]) out.push(process.argv[i + 1]);
  });
  return out;
}

const cwd = resolve(arg("cwd", process.cwd()));
const agentDir = resolve(arg("agent-dir", `${process.env.HOME}/.pi/agent`));
const sessionDir = arg("session-dir", undefined);
const extraExtensions = multiArg("extension");
const trustProject = process.argv.includes("--trust-project");

// --------------------------------------------------------------------------- io

const out = (obj) => process.stdout.write(`${JSON.stringify(obj)}\n`);

const pendingResponses = new Map();
const pendingUi = new Map();

function respond(id, ok, data, error) {
  // 没有 id 的命令也要回（App 可能 fire-and-forget），用 null 占位以便断言。
  out({ type: "response", id: id ?? null, ok, ...(ok ? { data } : { error: String(error) }) });
}

// --------------------------------------------------------------------------- ui

/**
 * 渲染一个组件并把行发给 App。
 *
 * `width` 是**字符宽度**（App 按等宽字号算出来的列数），因为 pi-tui 组件只认这个。
 * App 旋转屏幕或改字号时发 `ui.resize`，我们按新宽度重渲染同一份组件。
 */
function paint(surface) {
  let lines;
  try {
    lines = surface.component.render(surface.width) ?? [];
  } catch (error) {
    lines = [`[render failed] ${error?.message ?? error}`];
  }
  out({
    type: "ui.surface",
    surfaceId: surface.id,
    mode: surface.mode,
    rev: ++surface.rev,
    interactive: typeof surface.component.handleInput === "function",
    lines,
  });
}

function surfaceFor(mode, component, width = 40) {
  const surface = { id: randomUUID(), mode, component, width, rev: 0 };
  paint(surface);
  return surface;
}

const surfaces = new Map();

function closeSurface(id) {
  surfaces.delete(id);
  out({ type: "ui.close", surfaceId: id });
}

/** 等待 App 发回来的 `ui.response`。 */
function ask(request) {
  const id = randomUUID();
  return new Promise((resolvePromise, reject) => {
    pendingUi.set(id, { resolve: resolvePromise, reject });
    out({ type: "ui.request", id, ...request });
  });
}

/** 完整的 `ExtensionUIContext`：每一条都真的接到 App，而不是 no-op。 */
const uiContext = {
  async select(title, options, opts) {
    const value = await ask({ kind: "select", title, options, timeout: opts?.timeout });
    return typeof value === "string" ? value : undefined;
  },
  async confirm(title, message, opts) {
    const value = await ask({ kind: "confirm", title, message, timeout: opts?.timeout });
    return value === true;
  },
  async input(title, placeholder, opts) {
    const value = await ask({ kind: "input", title, placeholder, timeout: opts?.timeout });
    return typeof value === "string" ? value : undefined;
  },
  async editor(title, prefill) {
    const value = await ask({ kind: "editor", title, prefill });
    return typeof value === "string" ? value : undefined;
  },
  notify(message, type) {
    out({ type: "ui.notify", message: String(message), tone: type ?? "info" });
  },
  setStatus(key, text) {
    out({ type: "ui.status", key: String(key), text: text === undefined ? null : String(text) });
  },
  setTitle(title) {
    out({ type: "ui.title", title: String(title) });
  },
  setEditorText(text) {
    out({ type: "ui.editorText", text: String(text) });
  },
  // 输入框内容由 App 推送（见 `ui.editorState` 入站消息），这里返回最近一次快照。
  getEditorText() {
    return editorText;
  },
  pasteToEditor(text) {
    out({ type: "ui.paste", text: String(text) });
  },
  setWorkingMessage(message) {
    out({ type: "ui.working", message: message === undefined ? null : String(message) });
  },
  setWorkingVisible(visible) {
    out({ type: "ui.workingVisible", visible: Boolean(visible) });
  },
  setWorkingIndicator(options) {
    out({ type: "ui.workingIndicator", frames: options?.frames ?? null });
  },
  setHiddenThinkingLabel(label) {
    out({ type: "ui.thinkingLabel", label: label ?? null });
  },
  /** 字符串行直接转发；组件工厂渲染成表面。 */
  setWidget(key, content, options) {
    if (content === undefined) {
      out({ type: "ui.widget", key: String(key), lines: null, placement: options?.placement ?? null });
      return;
    }
    if (Array.isArray(content)) {
      out({ type: "ui.widget", key: String(key), lines: content.map(String), placement: options?.placement ?? null });
      return;
    }
    if (typeof content === "function") {
      const surface = surfaceFor(`widget:${key}`, content({ requestRender() {} }, themeStub));
      surfaces.set(surface.id, surface);
      out({ type: "ui.widgetSurface", key: String(key), surfaceId: surface.id });
    }
  },
  setFooter(factory) {
    out({ type: "ui.chrome", slot: "footer", attached: Boolean(factory) });
    if (factory) {
      const surface = surfaceFor("footer", factory({ requestRender() {} }, themeStub, footerDataStub));
      surfaces.set(surface.id, surface);
    }
  },
  setHeader(factory) {
    out({ type: "ui.chrome", slot: "header", attached: Boolean(factory) });
    if (factory) {
      const surface = surfaceFor("header", factory({ requestRender() {} }, themeStub));
      surfaces.set(surface.id, surface);
    }
  },
  /** 整屏/弹层接管：拿到组件、持续渲染，`done()` 结束并返回值。 */
  custom(factory) {
    return new Promise((resolvePromise) => {
      const component = factory({ requestRender() {} }, themeStub, keybindingsStub, (value) => {
        const surface = surfaces.get(component.__surfaceId);
        if (surface) closeSurface(surface.id);
        resolvePromise(value);
      });
      const surface = surfaceFor("custom", component);
      component.__surfaceId = surface.id;
      surfaces.set(surface.id, surface);
    });
  },
  getEditorComponent() {
    return undefined;
  },
  setEditorComponent() {
    out({ type: "ui.chrome", slot: "editor", attached: false });
  },
  addAutocompleteProvider(factory) {
    out({ type: "ui.autocomplete", registered: true });
    autocompleteProviders.push(factory);
  },
  onTerminalInput(handler) {
    terminalInputHandlers.add(handler);
    return () => terminalInputHandlers.delete(handler);
  },
  get theme() {
    return themeStub;
  },
  getAllThemes() {
    return [];
  },
  getTheme() {
    return undefined;
  },
  setTheme() {
    return { success: false, error: "主题由 App 管理" };
  },
  getToolsExpanded() {
    return false;
  },
  setToolsExpanded(expanded) {
    out({ type: "ui.toolsExpanded", expanded: Boolean(expanded) });
  },
};

const autocompleteProviders = [];
const terminalInputHandlers = new Set();
let editorText = "";

const themeStub = new Proxy({}, {
  get: (_target, prop) => (prop === "name" ? "app" : () => ""),
});

const keybindingsStub = { matches: () => false };

const footerDataStub = {
  getGitBranch: () => undefined,
  getExtensionStatuses: () => [],
};

// --------------------------------------------------------------------------- session

let session;
let events;

async function open() {
  const settingsManager = SettingsManager.create(cwd, agentDir, { projectTrusted: trustProject });
  const resourceLoader = new DefaultResourceLoader({
    cwd,
    agentDir,
    settingsManager,
    eventBus: events,
    additionalExtensionPaths: extraExtensions,
  });
  await resourceLoader.reload();

  const modelRuntime = await ModelRuntime.create({
    authPath: `${agentDir}/auth.json`,
    modelsPath: `${agentDir}/models.json`,
  });

  const created = await createAgentSession({
    cwd,
    agentDir,
    resourceLoader,
    settingsManager,
    modelRuntime,
    sessionManager: SessionManager.create(cwd, sessionDir ?? `${agentDir}/sessions`),
  });

  session = created.session;
  session.subscribe((event) => out({ type: "event", event }));

  // `bindExtensions` 会**等待**每个扩展的 `session_start` 处理器；而处理器里常见的正是
  // `await ctx.ui.select(...)` 这种会阻塞到用户作答的调用。把它放在 `open` 的应答之前，
  // 一个扩展就能把整个会话建立拖死（冒烟测试踩到的就是这个）。所以这里先回 `open`，
  // 再后台绑定，绑完单独发一条事件。
  void session
    .bindExtensions({ uiContext, mode: "tui" })
    .then(() => out({ type: "extensions", bound: true }))
    .catch((error) => out({ type: "extensions", bound: false, error: String(error) }));

  return {
    sessionId: session.getSessionStats?.().sessionId ?? null,
    cwd,
    agentDir,
    modelFallbackMessage: created.modelFallbackMessage ?? null,
    extensions: created.extensionsResult?.extensions?.map((e) => e.path ?? e.name) ?? [],
    warnings: created.extensionsResult?.errors ?? [],
  };
}

/** 进程内子代理 RPC：pi-subagents 自己在事件总线上监听这个频道。 */
function subagentRpc(method, params, timeoutMs = 30000) {
  return new Promise((resolvePromise, reject) => {
    const requestId = randomUUID();
    const off = events.on(`subagents:rpc:v1:reply:${requestId}`, (reply) => {
      off();
      clearTimeout(timer);
      if (reply?.success) resolvePromise(reply.data);
      else reject(new Error(reply?.error?.message ?? "subagent rpc failed"));
    });
    const timer = setTimeout(() => {
      off();
      reject(new Error("subagent rpc timed out"));
    }, timeoutMs);
    events.emit("subagents:rpc:v1:request", { version: 1, requestId, method, params });
  });
}

// --------------------------------------------------------------------------- loop

events = createEventBus();

const rl = createInterface({ input: process.stdin });
rl.on("line", async (line) => {
  if (!line.trim()) return;
  let msg;
  try {
    msg = JSON.parse(line);
  } catch {
    out({ type: "error", error: "bad json line" });
    return;
  }
  const { id, type } = msg;
  try {
    switch (type) {
      case "hello":
        respond(id, true, {
          protocol: 1,
          capabilities: [
            "ui.select", "ui.confirm", "ui.input", "ui.editor",
            "ui.widget", "ui.widgetFactory", "ui.custom",
            "ui.footer", "ui.header", "ui.status", "ui.notify",
            "ui.editorText", "ui.terminalInput", "ui.autocomplete",
            "renderers", "subagents.rpc",
          ],
        });
        break;
      case "open":
        respond(id, true, await open());
        break;
      case "prompt":
        session.prompt(msg.text, { images: msg.images }).catch((error) => out({ type: "error", error: String(error) }));
        respond(id, true, { accepted: true });
        break;
      case "abort":
        await session.abort();
        respond(id, true, {});
        break;
      case "state":
        respond(id, true, {
          stats: session.getSessionStats?.(),
          usage: session.getContextUsage?.(),
          model: session.model ? { provider: session.model.provider, id: session.model.id } : null,
        });
        break;
      case "subagent":
        respond(id, true, await subagentRpc(msg.method, msg.params));
        break;
      case "ui.response": {
        const pending = pendingUi.get(msg.id);
        if (!pending) {
          respond(id, false, null, "unknown ui request");
          break;
        }
        pendingUi.delete(msg.id);
        pending.resolve(msg.cancelled ? undefined : msg.value);
        respond(id, true, {});
        break;
      }
      case "ui.input": {
        const surface = surfaces.get(msg.surfaceId);
        if (surface?.component?.handleInput) {
          surface.component.handleInput(String(msg.data));
          paint(surface);
        }
        respond(id, true, { handled: Boolean(surface?.component?.handleInput) });
        break;
      }
      case "ui.resize": {
        const surface = surfaces.get(msg.surfaceId);
        if (surface) {
          surface.width = Number(msg.width) || surface.width;
          paint(surface);
        }
        respond(id, true, {});
        break;
      }
      case "ui.editorState":
        editorText = String(msg.text ?? "");
        for (const handler of terminalInputHandlers) handler(String(msg.text ?? ""));
        respond(id, true, {});
        break;
      case "shutdown":
        respond(id, true, {});
        process.exit(0);
        break;
      default:
        respond(id, false, null, `unknown type: ${type}`);
    }
  } catch (error) {
    respond(id, false, null, error?.stack ?? String(error));
  }
});

rl.on("close", () => {
  // 输入结束后不要立刻退出：异步命令可能还在跑，且 stdout 还没刷完。
  setTimeout(() => process.exit(0), 100);
});

process.on("uncaughtException", (error) => out({ type: "fatal", error: String(error?.stack ?? error) }));
process.on("unhandledRejection", (error) => out({ type: "fatal", error: String(error?.stack ?? error) }));

if (!existsSync(cwd)) {
  out({ type: "fatal", error: `cwd does not exist: ${cwd}` });
  process.exit(2);
}

out({ type: "ready", pid: process.pid });
