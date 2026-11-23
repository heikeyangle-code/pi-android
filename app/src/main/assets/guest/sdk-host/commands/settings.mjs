/**
 * 设置：读合并后的有效设置，写任意一项。
 *
 * 以前 App 直接读写 `settings.json`（自己转录每个键的位置、校验、项目层覆盖、`projectTrusted` 过滤）。
 * `SettingsManager` 有约 60 个 `setXxx`，键与方法并不一一对应（`compaction.enabled` ↔ `setCompactionEnabled`），
 * 给每个都写一条命令是在重写 SDK，所以只开一个口：`settings.set { method, params }`，调的是**会话正在用的**
 * 那个 `SettingsManager`（所以改完当前会话立刻生效，和 TUI 里改设置一样），然后 flush 落盘。
 * 方法名必须真实存在于 SettingsManager 且以 `set` 开头 —— 不是任意方法调用口（那是 `sdk.call`，另有管控）。
 * 校验、默认值、项目层 / 全局层的写入位置全是 SDK 的。
 */

import { ERR, HostError, bad, str } from "../protocol.mjs";
import { toWire } from "./generic.mjs";

function sessionModelOverride(session, sm) {
  const cur = session?.model;
  return cur ? sm.getModelThinkingLevel?.(cur.provider, cur.id) : undefined;
}

export function register(reg, { controller, hub }) {
  const manager = () => {
    if (!controller.opened) throw new HostError(ERR.NOT_OPEN, "先 session.open");
    return controller.runtime.services.settingsManager;
  };
  const view = (sm) => ({
    effective: toWire(sm.getSettings()),
    global: toWire(sm.getGlobalSettings()),
    project: toWire(sm.getProjectSettings()),
    trusted: controller.isProjectTrusted(),
  });

  reg.add("settings.get", () => view(manager()));

  const ALLOWED_SETTINGS_METHODS = new Set(["removeModelThinkingLevel"]);

  reg.add("settings.set", async (args) => {
    const sm = manager();
    const method = str(args, "method");
    if ((!/^set[A-Z]\w*$/.test(method) && !ALLOWED_SETTINGS_METHODS.has(method)) || typeof sm[method] !== "function") {
      throw bad(`SettingsManager 没有这个设置方法：${method}`);
    }
    const params = Array.isArray(args.params) ? args.params : [];
    sm.drainErrors(); // 丢掉之前的残留，只报这一次写入的错
    try {
      sm[method](...params);
    } catch (error) {
      throw new HostError(ERR.REJECTED, String(error?.message ?? error));
    }
    await sm.flush();
    const errors = sm.drainErrors();
    if (errors.length) throw new HostError(ERR.REJECTED, errors.map((e) => String(e?.error?.message ?? e?.message ?? e)).join("; "));
    if (method === "setHttpIdleTimeoutMs") await controller.applyHttpSettings?.();
    if (method === "setDefaultProjectTrust") await controller.reconcileProjectTrust?.();
    // 与 Pi 官方 `interactive-mode.js` / `settings-selector.js` 一致：同步运行中会话与 Agent 的实时状态。
    const session = controller.session;
    if (session) {
      try {
        if (method === "setSteeringMode" && typeof params[0] === "string") session.setSteeringMode?.(params[0]);
        else if (method === "setFollowUpMode" && typeof params[0] === "string") session.setFollowUpMode?.(params[0]);
        else if (method === "setCompactionEnabled" && typeof params[0] === "boolean") session.setAutoCompactionEnabled?.(params[0]);
        else if (method === "setRetryEnabled" && typeof params[0] === "boolean") session.setAutoRetryEnabled?.(params[0]);
        else if (method === "setCacheWarmingMode" && typeof params[0] === "string") session.setCacheWarmingMode?.(params[0]);
        else if (method === "setTransport" && typeof params[0] === "string" && session.agent) {
          session.agent.transport = params[0];
        } else if ((method === "setModelThinkingLevel" || method === "removeModelThinkingLevel") && typeof params[0] === "string") {
          const cur = session.model;
          const providerArg = params[0];
          const modelIdArg = typeof params[1] === "string" ? params[1] : null;
          const matchesCurrent = cur && (
            (modelIdArg !== null && cur.provider === providerArg && cur.id === modelIdArg) ||
            providerArg === `${cur.provider}/${cur.id}` ||
            (modelIdArg === null && providerArg === cur.id)
          );
          if (matchesCurrent) {
            const levelArg = method === "setModelThinkingLevel"
              ? (typeof params[2] === "string" ? params[2] : (typeof params[1] === "string" && !modelIdArg ? params[1] : null))
              : null;
            const nextLevel = levelArg ?? (sm.getDefaultThinkingLevel?.() ?? "medium");
            session.setThinkingLevel?.(nextLevel);
          }
        } else if (method === "setEnabledModels" && typeof session.setScopedModels === "function") {
          const patterns = Array.isArray(params[0]) ? params[0] : [];
          if (patterns.length === 0) {
            session.setScopedModels([]);
          }
        }
      } catch {
        // 会话尚未就绪或不支持该运行时方法时不阻塞设置落盘
      }
    }
    hub.emit("settings.changed", { method });
    return view(sm);
  });

  // 外部改了 settings.json（或无直接 setter 的嵌套键落盘后）让会话重读，并同步运行中会话/Agent/项目信任态。
  reg.add("settings.reload", async () => {
    const sm = manager();
    const prevWarming = sm.getCacheWarmingMode?.();
    const prevOverride = sessionModelOverride(controller.session, sm);
    await sm.reload();
    await controller.applyHttpSettings?.();
    await controller.reconcileProjectTrust?.();
    const session = controller.session;
    if (session) {
      try {
        const steering = sm.getSteeringMode?.();
        if (steering) session.setSteeringMode?.(steering);
        const followUp = sm.getFollowUpMode?.();
        if (followUp) session.setFollowUpMode?.(followUp);
        const compactionEnabled = sm.getCompactionEnabled?.();
        if (typeof compactionEnabled === "boolean") session.setAutoCompactionEnabled?.(compactionEnabled);
        const retryEnabled = sm.getRetryEnabled?.();
        if (typeof retryEnabled === "boolean") session.setAutoRetryEnabled?.(retryEnabled);
        const nextWarming = sm.getCacheWarmingMode?.();
        if (nextWarming && nextWarming !== prevWarming) {
          session.setCacheWarmingMode?.(nextWarming);
        }
        const cur = session.model;
        if (cur) {
          const override = sm.getModelThinkingLevel?.(cur.provider, cur.id);
          if (typeof override === "string") {
            session.setThinkingLevel?.(override);
          } else if (prevOverride !== undefined) {
            session.setThinkingLevel?.(sm.getDefaultThinkingLevel?.() ?? "medium");
          }
        }
      } catch {
        // ignore if session not ready
      }
      if (session.agent) {
        try {
          const transport = sm.getTransport?.();
          if (transport) session.agent.transport = transport;
          const budgets = sm.getThinkingBudgets?.();
          if (budgets !== undefined) session.agent.thinkingBudgets = budgets;
          const pr = sm.getProviderRetrySettings?.();
          if (pr && typeof pr.maxRetryDelayMs === "number") {
            session.agent.maxRetryDelayMs = pr.maxRetryDelayMs;
          }
        } catch {
          // ignore if agent shape differs
        }
      }
    }
    hub.emit("settings.changed", { method: "reload" });
    return view(sm);
  });
}
