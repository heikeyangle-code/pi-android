/** 宿主自身：握手、探活、退出。 */

import { PROTOCOL_VERSION } from "../protocol.mjs";

export function register(reg, { controller, hub, reg: registry }) {
  reg.add("host.hello", () => ({
    protocol: PROTOCOL_VERSION,
    pid: process.pid,
    node: process.version,
    commands: registry.names(),
    // 能力表：App 据此决定哪些入口亮起，而不是硬编码「有没有」。
    capabilities: [
      "ui.dialogs", "ui.widgetFactory", "ui.footer", "ui.header", "ui.custom",
      "ui.palette", "ui.cancelNotice", "renderers", "subagents.rpc", "services.packages",
      "session.replace", "project.trust", "builtin.mcp", "builtin.codemode", "builtin.toolSearch",
    ],
  }));
  reg.add("host.ping", () => ({ now: Date.now(), epoch: hub.epoch, seq: hub.seq }));
  reg.add("host.shutdown", () => {
    setImmediate(() => controller.shutdown(0));
  });
}
