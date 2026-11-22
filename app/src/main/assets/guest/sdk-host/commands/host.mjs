/** 宿主自身：握手、探活、退出。 */
import { PROTOCOL_VERSION } from "../protocol.mjs";

/** 能力表：App 据此决定哪些入口亮起，而不是硬编码「有没有」。 */
export const HOST_CAPABILITIES = [
  "ui.dialogs", "ui.widgetFactory", "ui.footer", "ui.header", "ui.custom",
  "ui.keybindings", "ui.overlayHandle", "ui.autocomplete", "ui.mouse",
  "ui.palette", "ui.cancelNotice", "renderers", "services.packages",
  "session.replace", "project.trust", "http.dispatcher",
  "builtin.mcp", "builtin.codemode", "builtin.toolSearch",
];

export function buildHelloPayload(registry) {
  return {
    protocol: PROTOCOL_VERSION,
    // 引擎标识：预留给双引擎（docs/sdk-host/dual-engine.md）。本宿主恒为 coding-agent；
    // Durable 将是另一个入口（durable-host），握手里报 "durable"，App 据此选适配器。
    engine: "coding-agent",
    pid: process.pid,
    node: process.version,
    commands: registry.names(),
    capabilities: HOST_CAPABILITIES,
  };
}

export function register(reg, { controller, hub, reg: registry }) {
  reg.add("host.hello", () => buildHelloPayload(registry));
  reg.add("host.ping", () => ({ now: Date.now(), epoch: hub.epoch, seq: hub.seq }));
  reg.add("host.shutdown", () => {
    setImmediate(() => controller.shutdown(0));
  });
}
