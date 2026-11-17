/**
 * 进程内服务：以前要另起 `pi` 命令行或 `pi-resources.mjs` 才能做的事，现在直接调 SDK。
 *
 * 包：列出 / 检查更新 / 装 / 卸 / 更新（带进度）。资源开关在 resources.mjs，认证在 auth.mjs，
 * MCP 在 mcp.mjs，设置在 settings.mjs。
 */

import { ERR, HostError, optStr, str } from "../protocol.mjs";
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

/**
 * 一个包来源「是什么」，交给官方的 `parseSource` 判断（npm / git / 本地；npm 的版本与是否固定，git 的 ref）。
 * App 不自己猜：比如没有 `npm:` 前缀的 `foo` 在 pi 里是**本地路径**，只有官方解析器说了算。
 * `parseSource` 在类型声明里是 private，但运行时就在原型上；契约测试盯着它，升级挪了位置会红。
 * 固定（exact 版本 / git ref）的包 `pi update` 会跳过，所以 `pinned` 要让用户看见。
 */
export function describeSource(pm, source) {
  try {
    const p = pm.parseSource(source);
    if (p.type === "npm") return { kind: "npm", name: p.name, version: p.version ?? null, pinned: p.pinned === true };
    if (p.type === "git") return { kind: "git", name: `${p.host}/${p.path}`, version: p.ref ?? null, pinned: p.pinned === true };
    return { kind: "local", name: p.path, version: null, pinned: false };
  } catch {
    return { kind: "unknown", name: source, version: null, pinned: false };
  }
}

const withInfo = (pm, list) => list.map((p) => ({ ...p, info: describeSource(pm, p.source) }));

export function register(reg, { controller, bus }) {
  reg.add("packages.list", () => {
    const pm = packageManager(controller);
    return { packages: withInfo(pm, pm.listConfiguredPackages()) };
  });

  reg.add("packages.inspect", (args) => ({ info: describeSource(packageManager(controller), str(args, "source").trim()) }));

  // 以前：`pi` 子进程 + 解析输出；现在：一次函数调用。
  reg.add("packages.updates", async () => ({
    updates: (await packageManager(controller).checkForAvailableUpdates()).map((u) => ({ ...u, name: u.displayName ?? u.source })),
  }));

  // ---- 装 / 卸 / 更新 ----------------------------------------------------------
  // 官方 `PackageManager` 的 `installAndPersist / removeAndPersist / update`，进度来自
  // `setProgressCallback`，原样转成 `packages.progress` 事件（start / progress / complete / error）。
  // 进度回调是**每个 PackageManager 一个**，所以这三条命令各用一个新实例，互不串线；同一时刻只允许
  // 一个装卸操作（npm / git 并发写同一个目录只会互相踩）。
  // 装完**不会**自动重载：App 在用户确认后调 `session.reload`，新扩展才进当前会话。
  let packageOpBusy = false;
  async function packageOp(args, run) {
    if (packageOpBusy) throw new HostError(ERR.BUSY, "另一个包操作还在进行");
    packageOpBusy = true;
    const opId = optStr(args, "opId") ?? `p${Math.random().toString(36).slice(2, 8)}`;
    const pm = packageManager(controller);
    pm.setProgressCallback((event) => controller.hub.emit("packages.progress", { opId, ...event }));
    try {
      const result = await run(pm, { local: optStr(args, "scope") === "project" });
      return { opId, ...(result ?? {}), packages: withInfo(pm, pm.listConfiguredPackages()) };
    } catch (error) {
      if (error instanceof HostError) throw error;
      throw new HostError(ERR.INTERNAL, String(error?.message ?? error));
    } finally {
      pm.setProgressCallback(undefined);
      packageOpBusy = false;
    }
  }

  reg.add("packages.install", (args) => packageOp(args, async (pm, opts) => {
    await pm.installAndPersist(str(args, "source"), opts);
  }));
  reg.add("packages.remove", (args) => packageOp(args, async (pm, opts) => ({ removed: await pm.removeAndPersist(str(args, "source"), opts) })));
  reg.add("packages.update", (args) => packageOp(args, async (pm) => {
    await pm.update(optStr(args, "source"));
  }));

  // ---- 扩展事件总线桥 --------------------------------------------------------
  // pi 的扩展之间靠一条进程内事件总线（`pi.events`）通信，任何扩展的状态变化都可以走它。宿主不替任何扩展写适配：App 订阅它想要的事件名，宿主原样转成 `bus` 事件；
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
