/** 模型、推理等级、队列模式。 */

import { ERR, HostError, bad, bool, optStr, str } from "../protocol.mjs";
import { withState } from "../state.mjs";
import { pathToFileURL } from "node:url";
import { join } from "node:path";

const THINKING_ORDER = ["minimal", "low", "medium", "high", "xhigh", "max"];
let cachedGetSupportedThinkingLevels = undefined;

async function resolveSupportedThinkingLevelsFn(piDir) {
  if (cachedGetSupportedThinkingLevels !== undefined) return cachedGetSupportedThinkingLevels;
  const candidates = [
    join(piDir, "..", "pi-ai", "dist", "models.js"),
    join(piDir, "node_modules", "@earendil-works", "pi-ai", "dist", "models.js"),
  ];
  for (const p of candidates) {
    try {
      const mod = await import(pathToFileURL(p).href);
      if (typeof mod.getSupportedThinkingLevels === "function") {
        cachedGetSupportedThinkingLevels = mod.getSupportedThinkingLevels;
        return cachedGetSupportedThinkingLevels;
      }
    } catch {
      // try next
    }
  }
  cachedGetSupportedThinkingLevels = null;
  return null;
}

function supportedThinkingLevelsOf(model, sdkFn) {
  if (!model || !model.reasoning) return [];
  if (typeof sdkFn === "function") {
    try {
      const levels = sdkFn(model);
      if (Array.isArray(levels)) return levels;
    } catch {
      // fallback below
    }
  }
  const map = model.thinkingLevelMap;
  if (map && typeof map === "object") {
    const mapped = THINKING_ORDER.filter((lvl) => lvl in map);
    if (mapped.length > 0) return mapped;
  }
  return ["minimal", "low", "medium", "high"];
}

export function register(reg, { controller }) {
  /**
   * 模型目录。默认 = 此刻**可用**的（`getAvailableSnapshot`：已配置凭据的供应商的模型，登录/退出后
   * SDK 已等它刷新完才应答，所以读到的是新的）。
   *
   * `all: true` = 目录里**全部**（`getModels`，含还没配置凭据的供应商），每条多一个 `available`
   * （`hasConfiguredAuth`）。供应商页要在登录**之前**就让人看到它有哪些模型；`provider` 只取一家。
   * 每条模型同时附带官方 `supportedThinkingLevels`（与 `pi-ai/dist/models.js` 一致），供「逐模型思考等级」
   * 选择器直接展示该模型真实支持的档位。
   */
  reg.add("model.list", async (args) => {
    const rt = controller.requireSession().modelRuntime;
    const sdkFn = await resolveSupportedThinkingLevelsFn(controller.cfg.piDir);
    if (!bool(args, "all", false)) {
      return {
        models: rt.getAvailableSnapshot().map((m) => ({
          ...m,
          supportedThinkingLevels: supportedThinkingLevelsOf(m, sdkFn),
        })),
      };
    }
    const provider = optStr(args, "provider");
    return {
      models: rt.getModels(provider).map((m) => ({
        ...m,
        available: rt.hasConfiguredAuth(m.provider),
        supportedThinkingLevels: supportedThinkingLevelsOf(m, sdkFn),
      })),
    };
  });

  /**
   * `/model <引用>` 的第一步（interactive-mode.js `handleModelCommand` / `findExactModelMatch`）：
   * 在「作用域模型（有就只看它们）或可用模型」里按**精确引用**找（`provider/id` 或唯一的 id），找到由 App
   * 接着 `model.set`，找不到才打开选择器并预填搜索词。`findExactModelReferenceMatch` 不在包的公开出口里，
   * 按文件路径读（同 catalog.mjs 读 slash-commands）。终端版在「没有作用域模型且缓存里没有」时还会先联网
   * 刷新一次目录（15 秒超时）；这里不做，没命中就进选择器，那里有刷新按钮。
   */
  reg.add("model.resolve", async (args) => {
    const s = controller.requireSession();
    const query = str(args, "query");
    const mod = await import(pathToFileURL(join(controller.cfg.piDir, "dist", "core", "model-resolver.js")).href);
    const pool = s.scopedModels.length > 0 ? s.scopedModels.map((x) => x.model) : [...s.modelRuntime.getAvailableSnapshot()];
    const m = mod.findExactModelReferenceMatch(query, pool);
    return { model: m ? { provider: m.provider, id: m.id, name: m.name } : null };
  });

  // `persist` 是 SDK 的 ModelMutationOptions：**默认只改本会话**，true 才写进全局默认
  // （settings.json 的 defaultProvider/defaultModel/defaultThinkingLevel）。宿主原样透传，
  // 不替 App 选。
  const mutation = (args) => ({ persist: bool(args, "persist", false) });

  reg.add("model.set", async (args) => {
    const s = controller.requireSession();
    const provider = str(args, "provider");
    const modelId = str(args, "modelId");
    const model = s.modelRuntime.getAvailableSnapshot().find((m) => m.provider === provider && m.id === modelId);
    if (!model) throw new HostError(ERR.NOT_FOUND, `模型不存在或没有配置凭据：${provider}/${modelId}`);
    await s.setModel(model, mutation(args));
    return withState(controller, { model });
  });
  reg.add("model.cycle", async (args) => {
    const direction = optStr(args, "direction") ?? "forward";
    if (direction !== "forward" && direction !== "backward") throw bad("direction 只能是 forward 或 backward");
    return withState(controller, { result: (await controller.requireSession().cycleModel(direction, mutation(args))) ?? null });
  });

  reg.add("thinking.levels", () => {
    const s = controller.requireSession();
    return { levels: s.getAvailableThinkingLevels(), supportsThinking: s.supportsThinking() };
  });
  reg.add("thinking.set", (args) => {
    controller.requireSession().setThinkingLevel(str(args, "level"), mutation(args));
    return withState(controller);
  });
  reg.add("thinking.cycle", (args) => {
    const level = controller.requireSession().cycleThinkingLevel(mutation(args));
    return withState(controller, { level: level ?? null });
  });

  reg.add("queue.steeringMode", (args) => {
    controller.requireSession().setSteeringMode(str(args, "mode"));
    return withState(controller);
  });
  reg.add("queue.followUpMode", (args) => {
    controller.requireSession().setFollowUpMode(str(args, "mode"));
    return withState(controller);
  });
}
