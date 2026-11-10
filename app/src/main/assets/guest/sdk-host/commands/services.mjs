/**
 * 进程内服务：以前要另起 `pi` 命令行或 `pi-resources.mjs` 才能做的事，现在直接调 SDK。
 *
 * **这里只是骨架**：packages.list / packages.updates 已接通，作为「新增一项服务该怎么写」的样板。
 * 其余（装 / 卸 / 更新、资源开关、MCP 检测与登录、设置读写、OAuth）按 docs/sdk-host/migration.md
 * 的阶段 2 逐项迁入，每项一个 `services.<域>.<动作>` 命令。
 */

import { ERR, HostError, str } from "../protocol.mjs";
import { toWire } from "./generic.mjs";

/** 一个 cwd 绑定的 PackageManager。设置读的是会话当前的 SettingsManager，所以和 pi 本身一致。 */
function packageManager(controller) {
  const s = controller.requireSession();
  const services = controller.runtime.services;
  return new controller.sdk.DefaultPackageManager({
    cwd: s.sessionManager.getCwd(),
    agentDir: controller.cfg.agentDir,
    settingsManager: services.settingsManager,
  });
}

export function register(reg, { controller, bus }) {
  reg.add("packages.list", () => ({ packages: packageManager(controller).listConfiguredPackages() }));

  // 以前：`pi` 子进程 + 解析输出；现在：一次函数调用。
  reg.add("packages.updates", async () => ({ updates: await packageManager(controller).checkForAvailableUpdates() }));

  // 进程内子代理 RPC：pi-subagents 在共享事件总线上监听 `subagents:rpc:v1:*`。
  reg.add("subagent.rpc", async (args) => {
    const method = str(args, "method");
    const timeoutMs = Number(args.timeoutMs) > 0 ? Number(args.timeoutMs) : 30000;
    const eventBus = bus();
    if (!eventBus) throw new HostError(ERR.NOT_OPEN, "先 session.open");
    return new Promise((resolve, reject) => {
      const requestId = `r${Math.random().toString(36).slice(2, 10)}`;
      const off = eventBus.on(`subagents:rpc:v1:reply:${requestId}`, (reply) => {
        off();
        clearTimeout(timer);
        if (reply?.success) resolve(reply.data ?? {});
        else reject(new HostError(ERR.REJECTED, String(reply?.error?.message ?? "subagent rpc failed")));
      });
      const timer = setTimeout(() => {
        off();
        reject(new HostError(ERR.BUSY, "subagent rpc 超时（pi-subagents 没装或没响应）"));
      }, timeoutMs);
      eventBus.emit("subagents:rpc:v1:request", { version: 1, requestId, method, params: args.params ?? {} });
    });
  });

  // ---- 扩展事件总线桥 --------------------------------------------------------
  // pi 的扩展之间靠一条进程内事件总线（`pi.events`）通信，pi-subagents 的任务变化、MCP 的状态变化
  // 都走它。宿主不替任何扩展写适配：App 订阅它想要的事件名，宿主原样转成 `bus` 事件；
  // 想发事件 / 发请求，用 `bus.emit`。新扩展出新事件，App 直接订阅，宿主不用改。
  const subscriptions = new Map(); // name → off()
  reg.add("bus.subscribe", (args) => {
    const eventBus = bus();
    if (!eventBus) throw new HostError(ERR.NOT_OPEN, "先 session.open");
    if (!Array.isArray(args.names)) throw new HostError(ERR.BAD_ARGS, "names 必须是字符串数组");
    for (const name of args.names.map(String)) {
      if (subscriptions.has(name)) continue;
      const off = eventBus.on(name, (data) => controller.hub.emit("bus", { name, data: toWire(data) ?? null }));
      subscriptions.set(name, off);
    }
    return { subscribed: [...subscriptions.keys()] };
  });
  reg.add("bus.unsubscribe", (args) => {
    for (const name of (Array.isArray(args.names) ? args.names : [...subscriptions.keys()]).map(String)) {
      subscriptions.get(name)?.();
      subscriptions.delete(name);
    }
    return { subscribed: [...subscriptions.keys()] };
  });
  reg.add("bus.emit", (args) => {
    const eventBus = bus();
    if (!eventBus) throw new HostError(ERR.NOT_OPEN, "先 session.open");
    eventBus.emit(str(args, "name"), args.data);
  });
}
