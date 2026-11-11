#!/usr/bin/env node
/**
 * Pi SDK Host —— App 与 pi 引擎之间的宿主进程。入口只做「装配」，逻辑在各模块里。
 *
 *   node main.mjs --cwd <工作区> --agent-dir <agentDir> --pi-dir <引擎包目录> [--session-dir <dir>]
 *                 [--extension <path>]... [--mode tui|rpc]
 *
 * 启动顺序是有讲究的（改之前先读 docs/sdk-host/architecture.md §启动）：
 *   1. 接管 stdout            —— 之后任何 console.log 都去 stderr，协议流不会被污染
 *   2. 发 `host.ready` 帧     —— 不等 SDK：SDK 加载是秒级，App 要立刻知道进程活着
 *   3. 装配命令表、开始读 stdin
 *   4. SDK 在 `session.open` 时才 dynamic import（见 sdk.mjs）
 */

import { existsSync } from "node:fs";
import { resolve } from "node:path";
import { Transport, readLines, takeOverStdout } from "./io.mjs";
import { Hub } from "./hub.mjs";
import { ERR } from "./protocol.mjs";
import { Registry } from "./commands/registry.mjs";
import { Surfaces } from "./ui/surfaces.mjs";
import { createTheme } from "./ui/theme.mjs";
import { createUiContext } from "./ui/context.mjs";
import { Controller } from "./controller.mjs";
import * as hostCmds from "./commands/host.mjs";
import * as sessionCmds from "./commands/session.mjs";
import * as libraryCmds from "./commands/library.mjs";
import * as agentCmds from "./commands/agent.mjs";
import * as modelConfigCmds from "./commands/modelconfig.mjs";
import * as modelCmds from "./commands/model.mjs";
import * as catalogCmds from "./commands/catalog.mjs";
import * as uiCmds from "./commands/ui.mjs";
import * as serviceCmds from "./commands/services.mjs";
import * as authCmds from "./commands/auth.mjs";
import * as resourceCmds from "./commands/resources.mjs";
import * as mcpCmds from "./commands/mcp.mjs";
import * as settingsCmds from "./commands/settings.mjs";
import * as genericCmds from "./commands/generic.mjs";

const rawWrite = takeOverStdout();

// pi 的打包入口（`dist/bundle/rpc-entry.js`）在 `main()` 之前做的三件事，宿主必须照做：
// 扩展靠 PI_CODING_AGENT 判断「在 pi 里」，AI_AGENT 给子进程（git 等）识别，
// 去掉 Node 的 ExperimentalWarning 刷屏。`configureHttpDispatcher()`（HTTP 代理 / 空闲超时）不在 SDK
// 公共导出里，宿主做不到，记在 docs/sdk-host/capabilities.md 的「已知缺口」。
process.title = "pi-sdk-host";
process.env.PI_CODING_AGENT = "true";
process.env.AI_AGENT ??= "pi";
process.emitWarning = () => {};

function arg(name, fallback) {
  const i = process.argv.indexOf(`--${name}`);
  return i > 0 && process.argv[i + 1] ? process.argv[i + 1] : fallback;
}
const multi = (name) => process.argv.flatMap((v, i) => (v === `--${name}` && process.argv[i + 1] ? [process.argv[i + 1]] : []));

const cfg = {
  cwd: resolve(arg("cwd", process.cwd())),
  agentDir: resolve(arg("agent-dir", `${process.env.HOME}/.pi/agent`)),
  sessionDir: arg("session-dir", undefined),
  // 引擎包目录（含 dist/）。必须由启动方给，见 sdk.mjs。
  piDir: arg("pi-dir", undefined),
  extensions: multi("extension"),
  // "tui"：扩展以为自己在终端里，会走组件路径（我们能接住）；"rpc"：扩展走纯文本降级。
  // 默认 tui，因为宿主存在的意义就是让组件能画出来。
  mode: arg("mode", "tui"),
};

const transport = new Transport(rawWrite);
const hub = new Hub(transport);

if (!existsSync(cfg.cwd)) {
  hub.emit("host.fatal", { code: "cwd_missing", message: `cwd 不存在：${cfg.cwd}` });
  await transport.flush();
  process.exit(2);
}

const themeHandle = createTheme();
const surfaces = new Surfaces(hub, themeHandle.theme);
const ui = createUiContext({ hub, surfaces, theme: themeHandle.theme, flags: {} });
const controller = new Controller({ hub, transport, ui, surfaces, cfg });
controller.theme = themeHandle.theme;

const reg = new Registry();
const deps = { controller, hub, surfaces, ui, themeHandle, reg, bus: () => controller.bus };
for (const mod of [hostCmds, sessionCmds, libraryCmds, agentCmds, modelConfigCmds, modelCmds, catalogCmds, uiCmds, serviceCmds, authCmds, resourceCmds, mcpCmds, settingsCmds, genericCmds]) mod.register(reg, deps);

hub.emit("host.ready", { pid: process.pid, node: process.version, cwd: cfg.cwd });

readLines(
  process.stdin,
  (line) => {
    let frame;
    try {
      frame = JSON.parse(line);
    } catch (error) {
      hub.res(null, false, { code: ERR.BAD_FRAME, message: `不是合法 JSON：${error.message}` });
      return;
    }
    void reg.dispatch(frame, hub);
  },
  () => void controller.shutdown(0),
);

for (const signal of ["SIGTERM", "SIGHUP"]) {
  process.on(signal, () => void controller.shutdown(signal === "SIGHUP" ? 129 : 143));
}
process.on("uncaughtException", (error) => hub.emit("host.fatal", { code: "uncaught", message: String(error?.stack ?? error) }));
process.on("unhandledRejection", (error) => hub.emit("host.log", { level: "error", message: `unhandledRejection: ${error?.stack ?? error}` }));
