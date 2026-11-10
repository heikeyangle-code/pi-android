/** 可调用的东西：斜杠命令（扩展 / 提示模板 / 技能）和工具。 */

import { str } from "../protocol.mjs";

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
      all: s.getAllTools().map((tool) => ({ name: tool.name, description: tool.description })),
    };
  });
  reg.add("catalog.setTools", (args) => {
    const s = controller.requireSession();
    if (!Array.isArray(args.names)) throw new Error("names 必须是数组");
    s.setActiveToolsByName(args.names.map(String));
    return { active: s.getActiveToolNames() };
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
