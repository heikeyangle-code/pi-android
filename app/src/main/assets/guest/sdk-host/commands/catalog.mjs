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
}
