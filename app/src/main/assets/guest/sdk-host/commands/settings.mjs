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

  reg.add("settings.set", async (args) => {
    const sm = manager();
    const method = str(args, "method");
    if (!/^set[A-Z]\w*$/.test(method) || typeof sm[method] !== "function") throw bad(`SettingsManager 没有这个设置方法：${method}`);
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
    hub.emit("settings.changed", { method });
    return view(sm);
  });

  // 外部改了 settings.json（App 的旧路径 / 用户手改）之后让会话重读。
  reg.add("settings.reload", async () => {
    const sm = manager();
    await sm.reload();
    hub.emit("settings.changed", { method: "reload" });
    return view(sm);
  });
}
