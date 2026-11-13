/** 可调用的东西：斜杠命令（扩展 / 提示模板 / 技能）和工具。 */

import { ERR, HostError, bad, str } from "../protocol.mjs";
import { withState } from "../state.mjs";

export function register(reg, { controller }) {
  // `get_commands` 的同款拼装；多带 sourceInfo 以外的 invocationName，App 不必再猜前缀。
  reg.add("catalog.commands", () => {
    const s = controller.requireSession();
    const commands = [];
    for (const c of s.extensionRunner.getRegisteredCommands()) {
      commands.push({ name: c.invocationName, description: c.description, source: "extension", sourceInfo: c.sourceInfo });
    }
    for (const t of s.promptTemplates) {
      commands.push({ name: t.name, description: t.description, source: "prompt", sourceInfo: t.sourceInfo });
    }
    for (const k of s.resourceLoader.getSkills().skills) {
      commands.push({ name: `skill:${k.name}`, description: k.description, source: "skill", sourceInfo: k.sourceInfo });
    }
    return { commands };
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

  // 扩展注册的消息渲染器（TUI 才会调）：宿主取出来调，把 `render(width)` 的行发回 App。
  reg.add("render.message", (args, _call) => {
    const s = controller.requireSession();
    const renderer = s.extensionRunner.getMessageRenderer(str(args, "customType"));
    if (!renderer) return { lines: null, reason: "no_renderer" };
    const component = renderer(args.message, { expanded: Boolean(args.expanded), outputPad: 0 }, controller.theme);
    return { lines: component ? component.render(Number(args.width) || controller.surfaces.width) : null };
  });

  // 扩展注册的**工具渲染器**（`renderCall` / `renderResult`，TUI 才会调）：按行渲染，App 把行当作自己的
  // 「带 ANSI 的行」画在工具卡里。没有渲染器的工具（含内置工具）返回 null，App 用它自己的原生卡。
  //
  // 渲染器要的 `context.state` 是「同一次调用的 call / result 共享的一份可变状态」（TUI 的工具行持有它），
  // 所以按 toolCallId 存一份，封顶，免得长会话里无限长。
  const toolStates = new Map();
  const stateFor = (id) => {
    let st = toolStates.get(id);
    if (!st) {
      st = {};
      toolStates.set(id, st);
      if (toolStates.size > TOOL_STATE_LIMIT) toolStates.delete(toolStates.keys().next().value);
    }
    return st;
  };
  const hints = { keyText: () => "", keyHint: (_id, description) => description };

  reg.add("render.tool", (args) => {
    const s = controller.requireSession();
    const name = str(args, "toolName");
    const def = s.getToolDefinition?.(name);
    if (!def || (!def.renderCall && !def.renderResult)) return { call: null, result: null, shell: null, reason: "no_renderer" };
    const width = Number(args.width) || controller.surfaces.width;
    const id = typeof args.toolCallId === "string" && args.toolCallId ? args.toolCallId : name;
    const callArgs = args.args ?? {};
    const result = args.result ?? null;
    const context = {
      ...hints,
      args: callArgs,
      toolCallId: id,
      invalidate() {},
      lastComponent: undefined,
      state: stateFor(id),
      cwd: s.sessionManager?.getCwd?.() ?? controller.cfg?.cwd ?? process.cwd(),
      executionStarted: args.executionStarted !== false,
      argsComplete: args.argsComplete !== false,
      isPartial: Boolean(args.isPartial),
      expanded: Boolean(args.expanded),
      showImages: false,
      isError: Boolean(result?.isError),
      durationMs: Number.isFinite(args.durationMs) ? Number(args.durationMs) : undefined,
      outputPad: 0,
    };
    const draw = (make) => {
      try {
        const component = make();
        const lines = component ? component.render(width) : null;
        component?.dispose?.();
        return Array.isArray(lines) ? lines.map(String) : null;
      } catch (error) {
        return [`[render failed] ${error?.message ?? error}`];
      }
    };
    return {
      call: def.renderCall ? draw(() => def.renderCall(callArgs, controller.theme, context)) : null,
      result:
        def.renderResult && result
          ? draw(() => def.renderResult(result, { expanded: context.expanded, isPartial: context.isPartial }, controller.theme, context))
          : null,
      shell: def.renderShell ?? "default",
    };
  });
}

const TOOL_STATE_LIMIT = 300;
