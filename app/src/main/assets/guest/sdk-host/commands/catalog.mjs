/** 可调用的东西：斜杠命令（扩展 / 提示模板 / 技能）和工具。 */

import { ERR, HostError, bad, optStr, str } from "../protocol.mjs";
import { withState } from "../state.mjs";

import { pathToFileURL } from "node:url";
import { join } from "node:path";

/**
 * pi 自己的内建斜杠命令表（`BUILTIN_SLASH_COMMANDS`：名字、说明、参数提示）。
 *
 * 它不在包的公开出口里（`index.d.ts` 没导出），所以和 resources.mjs 一样从 dist 里按文件路径读——
 * 这是第二处伸进 pi 非公开路径的地方。读不到（pi 挪了文件）就给空表并带上原因：App 据此退回自己
 * 那张映射表的顺序，而不是整个命令面板空掉。**为什么要从宿主要这张表**：此前 App 里手抄了一份
 * （再加一张「没做的」说明表和一条合约测试盯着它们跟 pi 同步），pi 每加一个命令 App 就缺一个；
 * 现在名字与说明以 pi 为准，App 只决定「这个命令在手机上怎么做」。
 */
let builtinsCache = null;
async function loadBuiltins(controller) {
  if (builtinsCache) return builtinsCache;
  try {
    const mod = await import(pathToFileURL(join(controller.cfg.piDir, "dist", "core", "slash-commands.js")).href);
    builtinsCache = {
      builtins: [...mod.BUILTIN_SLASH_COMMANDS].map((c) => ({
        name: c.name,
        description: c.description ?? null,
        argumentHint: c.argumentHint ?? null,
      })),
    };
  } catch (e) {
    // 不缓存失败：下次打开面板再试（升级/修复之后不必重启宿主）。
    return { builtins: [], builtinsError: String(e?.message ?? e) };
  }
  return builtinsCache;
}

export function register(reg, { controller }) {
  // `get_commands` 的同款拼装；多带 sourceInfo 以外的 invocationName，App 不必再猜前缀。
  reg.add("catalog.commands", async () => {
    const s = controller.requireSession();
    const commands = [];
    for (const c of s.extensionRunner.getRegisteredCommands()) {
      commands.push({
        name: c.invocationName,
        description: c.description,
        source: "extension",
        sourceInfo: c.sourceInfo,
        // 扩展自己提供参数补全（`getArgumentCompletions`）：App 据此在敲完命令名之后去问 `catalog.commandCompletions`。
        hasCompletions: typeof c.getArgumentCompletions === "function",
      });
    }
    for (const t of s.promptTemplates) {
      // `argumentHint` 来自模板 frontmatter 的 `argument-hint`；App 用它决定「点一下是执行还是先填名字等参数」。
      commands.push({
        name: t.name,
        description: t.description,
        source: "prompt",
        sourceInfo: t.sourceInfo,
        argumentHint: t.argumentHint ?? null,
      });
    }
    for (const k of s.resourceLoader.getSkills().skills) {
      commands.push({ name: `skill:${k.name}`, description: k.description, source: "skill", sourceInfo: k.sourceInfo });
    }
    return { commands, ...(await loadBuiltins(controller)) };
  });

  // 扩展命令的参数补全（pi TUI 敲 `/name ` 之后出的那份）。`name` 是调用名（带 `:1` 后缀的那种），
  // `prefix` 是已经敲了的参数前缀。补全函数是扩展自己的代码，可能慢或抛错：限时 2 秒，出错给空表
  // 并带原因，不让命令面板挂住。
  reg.add("catalog.commandCompletions", async (args) => {
    const name = str(args, "name");
    const prefix = optStr(args, "prefix") ?? "";
    const command = controller.requireSession().extensionRunner.getRegisteredCommands().find((c) => c.invocationName === name);
    if (typeof command?.getArgumentCompletions !== "function") return { items: [] };
    try {
      const items = await Promise.race([
        Promise.resolve(command.getArgumentCompletions(prefix)),
        new Promise((resolve) => setTimeout(() => resolve("timeout"), 2000)),
      ]);
      if (items === "timeout") return { items: [], error: "补全超时" };
      return {
        items: (Array.isArray(items) ? items : []).slice(0, 50).map((i) => ({
          value: String(i.value),
          label: String(i.label ?? i.value),
          description: i.description ?? null,
        })),
      };
    } catch (e) {
      return { items: [], error: String(e?.message ?? e) };
    }
  });

  reg.add("catalog.tools", () => {
    const s = controller.requireSession();
    return {
      active: s.getActiveToolNames(),
      callable: s.getCallableToolNames(),
      // `parameters` 是 JSON Schema，界面「工具详情」要用；其余字段是 SDK 的 ToolInfo 原样。
      all: s.getAllTools().map((tool) => ({
        name: tool.name,
        description: tool.description,
        exposure: tool.exposure,
        namespace: tool.namespace ?? null,
        annotations: tool.annotations ?? null,
        sourceInfo: tool.sourceInfo,
        parameters: tool.parameters ?? null,
        promptGuidelines: tool.promptGuidelines ?? null,
      })),
    };
  });

  // 扩展注册的命令行 flag（`pi.registerFlag`）：列出（名字/说明/类型/默认值/来自哪个扩展）和当前值。
  // 启动时的值走 `session.open {flags}`；运行中改值用 catalog.setFlag（扩展下次读取时生效）。
  reg.add("catalog.flags", () => {
    const runner = controller.requireSession().extensionRunner;
    const values = runner.getFlagValues();
    return {
      flags: [...runner.getFlags().values()].map((f) => ({
        name: f.name,
        description: f.description ?? null,
        type: f.type,
        default: f.default ?? null,
        extensionPath: f.extensionPath,
        value: values.get(f.name) ?? f.default ?? null,
      })),
    };
  });

  reg.add("catalog.setFlag", (args) => {
    const runner = controller.requireSession().extensionRunner;
    const name = str(args, "name");
    const flag = runner.getFlags().get(name);
    if (!flag) throw new HostError(ERR.NOT_FOUND, `没有这个扩展 flag：${name}`);
    const value = args.value;
    if (flag.type === "boolean" ? typeof value !== "boolean" : typeof value !== "string") {
      throw bad(`flag ${name} 的类型是 ${flag.type}`);
    }
    runner.setFlagValue(name, value);
    return { name, value };
  });

  // 扩展快捷键：手机没有物理键盘，但外接键盘时有意义；同时把冲突诊断一并给出。
  reg.add("catalog.diagnostics", () => {
    const s = controller.requireSession();
    const runner = s.extensionRunner;
    return {
      commands: runner.getCommandDiagnostics(),
      shortcuts: runner.getShortcutDiagnostics(),
      resources: s.resourceLoader.getExtensions?.().errors ?? [],
    };
  });
  reg.add("catalog.setTools", (args) => {
    const s = controller.requireSession();
    if (!Array.isArray(args.names)) throw new Error("names 必须是数组");
    s.setActiveToolsByName(args.names.map(String));
    return withState(controller, { active: s.getActiveToolNames() });
  });

  // 渲染一律交给 pi 官方导出的 `ToolExecutionComponent` / `CustomMessageComponent`：render context、
  // 状态、回退、`invalidate` 都是它们的实现，宿主不再自己拼（此前自拼漏了 details / 图片块 / executionStarted…）。
  // 官方组件读的是 pi 的**全局官方主题**，所以先 `initTheme`；字体色深度由 COLORTERM 决定。
  let themed = false;
  const officialTheme = () => {
    if (!themed) {
      process.env.COLORTERM ||= "truecolor";
      controller.sdk.initTheme("dark");
      themed = true;
    }
    return globalThis[Symbol.for("@earendil-works/pi-coding-agent:theme")];
  };
  // 组件自己 `ui.requestRender()`（动画、定时刷新）→ 通知 App 重取这一张卡。
  const tui = (key) => ({ requestRender: () => controller.hub.emit("render.invalidate", key) });

  reg.add("render.message", (args) => {
    const s = controller.requireSession();
    const customType = str(args, "customType");
    const renderer = s.extensionRunner.getMessageRenderer(customType);
    if (!renderer) return { lines: null, reason: "no_renderer" };
    officialTheme();
    const { CustomMessageComponent } = controller.sdk;
    const message = { ...args.message, customType };
    const component = new CustomMessageComponent(message, renderer, undefined, 0);
    component.setExpanded(Boolean(args.expanded));
    const lines = component.render(Number(args.width) || controller.surfaces.width);
    return { lines: lines.map(String) };
  });

  // 扩展用 `registerEntryRenderer` 给自己的 `custom` 会话条目（`appendEntry`）注册的渲染器：
  // TUI 用 `CustomEntryComponent`，它不在 SDK 的公开导出里，所以按文件路径取同一个官方类
  // （contract 守着这个路径）。没渲染器 / 渲染器没给内容 = 不画，和 pi 一样。
  let customEntryComponent;
  const loadCustomEntryComponent = async () => {
    if (customEntryComponent) return customEntryComponent;
    const file = join(controller.cfg.piDir ?? "", "dist", "modes", "interactive", "components", "custom-entry.js");
    customEntryComponent = (await import(pathToFileURL(file).href)).CustomEntryComponent;
    return customEntryComponent;
  };
  reg.add("render.entry", async (args) => {
    const s = controller.requireSession();
    const customType = str(args, "customType");
    const renderer = s.extensionRunner.getEntryRenderer(customType);
    if (!renderer) return { lines: null, reason: "no_renderer" };
    officialTheme();
    const CustomEntryComponent = await loadCustomEntryComponent();
    const entry = { type: "custom", id: str(args, "id") || "entry", parentId: null, timestamp: new Date().toISOString(), customType, data: args.data };
    const component = new CustomEntryComponent(entry, renderer, 0);
    if (!component.hasContent()) return { lines: null, reason: "empty" };
    component.setExpanded(Boolean(args.expanded));
    return { lines: component.render(Number(args.width) || controller.surfaces.width).map(String) };
  });

  // 扩展用 `registerMarkdownTransformer` 改写 markdown（TUI 在 Assistant/UserMessageComponent 里逐条套用）。
  // 与 TUI 同一条链：先 pi 自己的（mermaid，终端字符画，手机上不需要），再扩展的，按加载顺序。
  reg.add("render.markdown", (args) => {
    const s = controller.requireSession();
    const transformers = s.extensionRunner.getMarkdownTransformers();
    let text = str(args, "markdown");
    if (!transformers.length) return { markdown: text, changed: false, count: 0 };
    const context = {
      messageType: ["user", "assistant", "assistant-thinking", "custom", "summary", "changelog"].includes(args.messageType) ? args.messageType : "assistant",
      isStreaming: Boolean(args.isStreaming),
      availableWidth: Number(args.width) || controller.surfaces.width,
      ...(typeof args.messageId === "string" ? { messageId: args.messageId } : {}),
      ...(typeof args.timestamp === "string" ? { timestamp: args.timestamp } : {}),
    };
    for (const transform of transformers) {
      try {
        text = transform(text, context);
      } catch (error) {
        controller.hub.emit("host.log", { level: "warn", message: `markdown transformer failed: ${error?.message ?? error}` });
      }
    }
    return { markdown: text, changed: text !== args.markdown, count: transformers.length };
  });

  // 一个 toolCallId 对应一个官方组件（它持有 renderer state / lastComponent），封顶免得长会话无限长。
  const toolComponents = new Map();
  const componentFor = (id, name, def, callArgs, cwd) => {
    let c = toolComponents.get(id);
    if (!c) {
      c = new controller.sdk.ToolExecutionComponent(name, id, callArgs, { showImages: false, outputPad: 0 }, def, tui({ kind: "tool", toolCallId: id }), cwd);
      toolComponents.set(id, c);
      if (toolComponents.size > TOOL_STATE_LIMIT) toolComponents.delete(toolComponents.keys().next().value);
    }
    return c;
  };

  reg.add("render.tool", (args) => {
    const s = controller.requireSession();
    const name = str(args, "toolName");
    // 与 TUI 同一条解析链：扩展用 `registerToolRenderer` 注册的 resolver 先于工具自带的渲染器
    // （interactive-mode.js getRegisteredToolDefinition）——直接取 getToolDefinition 会漏掉被 resolver 接管的工具。
    const def = s.extensionRunner.resolveToolRenderers(name, () => s.getToolDefinition?.(name));
    if (!def || (!def.renderCall && !def.renderResult)) return { call: null, result: null, shell: null, reason: "no_renderer" };
    officialTheme();
    const width = Number(args.width) || controller.surfaces.width;
    const id = typeof args.toolCallId === "string" && args.toolCallId ? args.toolCallId : name;
    const callArgs = args.args ?? {};
    const cwd = s.sessionManager?.getCwd?.() ?? controller.cfg?.cwd ?? process.cwd();
    const comp = componentFor(id, name, def, callArgs, cwd);
    comp.updateArgs(callArgs);
    if (args.executionStarted !== false) comp.markExecutionStarted();
    if (args.argsComplete !== false) comp.setArgsComplete();
    if (args.result) {
      comp.updateResult(
        { content: args.result.content ?? [], details: args.result.details, isError: Boolean(args.result.isError), durationMs: Number.isFinite(args.durationMs) ? Number(args.durationMs) : undefined },
        Boolean(args.isPartial),
      );
    }
    comp.setExpanded(Boolean(args.expanded));
    const whole = comp.render(width).map(String); // 触发官方的 updateDisplay
    // 官方组件把 call / result 渲成一个整体；App 的卡片要分开画头和体，所以取它内部的两个子渲染结果。
    const part = (child) => {
      try {
        const lines = child?.render?.(width);
        return Array.isArray(lines) ? lines.map(String) : null;
      } catch (error) {
        return [`[render failed] ${error?.message ?? error}`];
      }
    };
    let call = part(comp.callRendererComponent);
    const result = part(comp.resultRendererComponent);
    if (!call && !result) call = whole; // 渲染器抛了、官方回退了：把官方的整体输出给 App，别让 App 当成「没有渲染器」
    return { call, result, shell: def.renderShell ?? "default" };
  });
}

const TOOL_STATE_LIMIT = 300;
