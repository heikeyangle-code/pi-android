/** 模型、推理等级、队列模式。 */

import { ERR, HostError, str } from "../protocol.mjs";

export function register(reg, { controller }) {
  reg.add("model.list", () => ({ models: controller.requireSession().modelRuntime.getAvailableSnapshot() }));
  reg.add("model.set", async (args) => {
    const s = controller.requireSession();
    const provider = str(args, "provider");
    const modelId = str(args, "modelId");
    const model = s.modelRuntime.getAvailableSnapshot().find((m) => m.provider === provider && m.id === modelId);
    if (!model) throw new HostError(ERR.NOT_FOUND, `模型不存在：${provider}/${modelId}`);
    await s.setModel(model);
    return model;
  });
  reg.add("model.cycle", async () => (await controller.requireSession().cycleModel()) ?? null);

  reg.add("thinking.levels", () => ({ levels: controller.requireSession().getAvailableThinkingLevels() }));
  reg.add("thinking.set", (args) => {
    controller.requireSession().setThinkingLevel(str(args, "level"));
  });
  reg.add("thinking.cycle", () => {
    const level = controller.requireSession().cycleThinkingLevel();
    return level ? { level } : null;
  });

  reg.add("queue.steeringMode", (args) => {
    controller.requireSession().setSteeringMode(str(args, "mode"));
  });
  reg.add("queue.followUpMode", (args) => {
    controller.requireSession().setFollowUpMode(str(args, "mode"));
  });
}
