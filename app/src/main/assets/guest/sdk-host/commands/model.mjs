/** 模型、推理等级、队列模式。 */

import { ERR, HostError, bad, bool, optStr, str } from "../protocol.mjs";
import { withState } from "../state.mjs";

export function register(reg, { controller }) {
  /**
   * 模型目录。默认 = 此刻**可用**的（`getAvailableSnapshot`：已配置凭据的供应商的模型，登录/退出后
   * SDK 已等它刷新完才应答，所以读到的是新的）。
   *
   * `all: true` = 目录里**全部**（`getModels`，含还没配置凭据的供应商），每条多一个 `available`
   * （`hasConfiguredAuth`）。供应商页要在登录**之前**就让人看到它有哪些模型；`provider` 只取一家。
   */
  reg.add("model.list", (args) => {
    const rt = controller.requireSession().modelRuntime;
    if (!bool(args, "all", false)) return { models: rt.getAvailableSnapshot() };
    const provider = optStr(args, "provider");
    return {
      models: rt.getModels(provider).map((m) => ({ ...m, available: rt.hasConfiguredAuth(m.provider) })),
    };
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
