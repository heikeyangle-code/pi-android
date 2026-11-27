#!/usr/bin/env node
/**
 * Pi SDK Host —— App 与官方 pi SDK 之间的宿主进程。入口只做「装配」，逻辑在各模块里。
 *
 *   node main.mjs --cwd <工作区> --agent-dir <agentDir> --pi-dir <引擎包目录> [--session-dir <dir>]
 *                 [--extension <path>]... [--android-extensions]
 *
 * 启动顺序（改之前先读 docs/sdk-host/architecture.md §启动）：
 *   1. 接管 stdout            —— 之后任何 console.log 都去 stderr，协议流不会被污染
 *   2. chdir 到工作区          —— 三个运行时（含无 -w 的 tawcroot）可直接 exec node，免起 /bin/bash
 *   3. 装配命令表、发 host.ready（内嵌 hello 能力表，省去一次串行握手 RTT）
 *   4. 立刻后台预热官方 SDK，同时开始读 stdin —— SDK 加载与 App 侧 session.open 投递完全并行
 */
import { existsSync } from "node:fs";
import { resolve } from "node:path";
import { Transport, readLines, setStdoutHook, takeOverStdout } from "./io.mjs";
import { Hub } from "./hub.mjs";
import { ERR } from "./protocol.mjs";
import { Registry } from "./commands/registry.mjs";
import { Surfaces } from "./ui/surfaces.mjs";
import { createTheme } from "./ui/theme.mjs";
import { createUiContext } from "./ui/context.mjs";
import { Controller } from "./controller.mjs";
import { loadSdk } from "./sdk.mjs";
import * as hostCmds from "./commands/host.mjs";
import * as sessionCmds from "./commands/session.mjs";
import * as libraryCmds from "./commands/library.mjs";
import * as sessionListCmds from "./commands/sessionlist.mjs";
import * as agentCmds from "./commands/agent.mjs";
import * as modelConfigCmds from "./commands/modelconfig.mjs";
import * as modelCmds from "./commands/model.mjs";
import * as catalogCmds from "./commands/catalog.mjs";
import * as uiCmds from "./commands/ui.mjs";
import * as serviceCmds from "./commands/services.mjs";
import * as authCmds from "./commands/auth.mjs";
import * as resourceCmds from "./commands/resources.mjs";
import * as resourceFileCmds from "./commands/resourcefiles.mjs";
import * as mcpCmds from "./commands/mcp.mjs";
import * as settingsCmds from "./commands/settings.mjs";
import * as genericCmds from "./commands/generic.mjs";

const rawWrite = takeOverStdout();
// 扩展靠 PI_CODING_AGENT 判断「在 pi 里」，AI_AGENT 给子进程（git 等）识别，
// PI_SDK_HOST 标识运行在 Android SDK 宿主内，同时去掉 Node 的 ExperimentalWarning 刷屏。
process.title = "pi-sdk-host";
process.env.PI_CODING_AGENT = "true";
process.env.PI_SDK_HOST = "1";
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
  androidExtensions: process.argv.includes("--android-extensions"),
};

if (cfg.agentDir && !process.env.PI_CODING_AGENT_DIR) {
  process.env.PI_CODING_AGENT_DIR = cfg.agentDir;
}
if (cfg.piDir && !process.env.PI_PACKAGE_DIR) {
  process.env.PI_PACKAGE_DIR = cfg.piDir;
}

const transport = new Transport(rawWrite);
const hub = new Hub(transport);

if (!existsSync(cfg.cwd)) {
  hub.emit("host.fatal", { code: "cwd_missing", message: `cwd 不存在：${cfg.cwd}` });
  await transport.flush();
  process.exit(2);
}

// 直接切到工作区目录：让 tawcroot / proot / proroot 都可直接 execve("/opt/node/bin/node", ...)，
// 彻底省掉外层 /bin/bash -c "cd ... && exec node ..." 的二次进程启动开销。
try {
  if (process.cwd() !== cfg.cwd) process.chdir(cfg.cwd);
  process.env.PWD = cfg.cwd;
} catch (error) {
  hub.emit("host.fatal", { code: "cwd_chdir_failed", message: `无法切换到工作区 ${cfg.cwd}：${error?.message ?? error}` });
  await transport.flush();
  process.exit(2);
}

const themeHandle = createTheme();
const surfaces = new Surfaces(hub, themeHandle.theme, themeHandle);
setStdoutHook((text) => surfaces.handleTerminalOutput(text, "stdout"));
const ui = createUiContext({ hub, surfaces, theme: themeHandle.theme, themeHandle, flags: {} });
const controller = new Controller({ hub, transport, ui, surfaces, cfg, themeHandle });
controller.theme = themeHandle.theme;

const reg = new Registry();
const deps = { controller, hub, surfaces, ui, themeHandle, reg, bus: () => controller.bus };
for (const mod of [hostCmds, sessionCmds, libraryCmds, sessionListCmds, agentCmds, modelConfigCmds, modelCmds, catalogCmds, uiCmds, serviceCmds, authCmds, resourceCmds, resourceFileCmds, mcpCmds, settingsCmds, genericCmds]) mod.register(reg, deps);

hub.emit("host.ready", {
  ...hostCmds.buildHelloPayload(reg),
  cwd: cfg.cwd,
});

// host.ready 帧已发出后立刻在下一轮事件循环开始加载官方 SDK，
// 与 App 侧收帧、校验协议和发送 session.open 彻底重叠，不阻塞 host.ready 响应时间。
if (cfg.piDir && process.env.PI_HOST_NO_PRELOAD !== "1") {
  setImmediate(() => {
    void loadSdk(cfg.piDir).catch(() => {});
  });
}

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
process.on("uncaughtException", (error) => {
  hub.emit("host.fatal", { code: "uncaught", message: String(error?.stack ?? error) });
  // Flush the fatal event (bounded) and exit instead of leaving Node in a corrupted zombie state.
  Promise.race([transport.flush(), new Promise((r) => setTimeout(r, 500))]).finally(() => process.exit(1));
});
process.on("unhandledRejection", (error) => hub.emit("host.log", { level: "error", message: `unhandledRejection: ${error?.stack ?? error}` }));
