/**
 * 自定义供应商 / 模型（`models.json`）。
 *
 * 旧 App 在 Kotlin 里手写 `models.json` 的读写与校验（`ModelProviderScreen` 近 3000 行里的一大块）。
 * SDK 已经有真正的校验：`ModelRuntime` 加载 `models.json` 时按 schema 检查，错误文案精确到字段
 * （实测：`Invalid models.json schema:\n  - providers.mine.baseUrl: must be string`），`refresh()` 会重读。
 * 所以宿主不复制 schema，只做**事务**：先备份、原子写、`refresh()`、问 SDK 有没有错 ——
 * 有错就**回滚到原文件**并把 SDK 的原话抛回去。这样 UI 保存失败时，磁盘上永远是上一份能用的配置。
 *
 *   models.config.get            读出当前文件（解析后的对象）+ SDK 当前的加载错误
 *   models.config.set            整份替换
 *   models.config.setProvider    增 / 改一个供应商
 *   models.config.removeProvider 删一个供应商
 *
 * 只动 `providers` 下的条目，文件里其他顶层字段原样保留（SDK 以后加字段不会被我们抹掉）。
 */

import { existsSync } from "node:fs";
import { readFile, rename, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { ERR, HostError, bad, str } from "../protocol.mjs";

export function register(reg, { controller, hub }) {
  const path = () => join(controller.cfg.agentDir, "models.json");
  const runtime = () => controller.requireSession().modelRuntime;

  async function readFileConfig() {
    if (!existsSync(path())) return { text: null, config: { providers: {} } };
    const text = await readFile(path(), "utf8");
    try {
      const config = JSON.parse(text);
      return { text, config: config && typeof config === "object" ? config : { providers: {} } };
    } catch {
      // 文件已经坏了：不替用户「修」它，也不在上面叠加 —— 让调用方看到错误，再决定整份替换。
      return { text, config: null };
    }
  }

  /** 事务式写入：成功返回，失败回滚并抛 SDK 的原话。 */
  async function commit(next) {
    const before = await readFileConfig();
    const tmp = `${path()}.tmp-${process.pid}`;
    await writeFile(tmp, JSON.stringify(next, null, 2) + "\n", { mode: 0o600 });
    await rename(tmp, path());
    const rt = runtime();
    await rt.refresh();
    const error = rt.getError();
    if (error) {
      if (before.text === null) await rename(path(), `${path()}.rejected`).catch(() => {});
      else await writeFile(path(), before.text, { mode: 0o600 });
      await rt.refresh();
      throw new HostError(ERR.REJECTED, error);
    }
    hub.emit("models.changed", { providers: Object.keys(next.providers ?? {}) });
    return { providers: Object.keys(next.providers ?? {}) };
  }

  reg.add("models.config.get", async () => {
    const { text, config } = await readFileConfig();
    // 文件被外部改坏时，SDK 的加载错误还是上一次的：让它重读一次，才能给出带行列号的原话。
    if (config === null) await runtime().refresh();
    return { path: path(), exists: text !== null, config, error: runtime().getError() ?? null };
  });

  reg.add("models.config.set", async (args) => {
    if (!args.config || typeof args.config !== "object" || Array.isArray(args.config)) throw bad("config 必须是对象");
    return commit(args.config);
  });

  reg.add("models.config.setProvider", async (args) => {
    const id = str(args, "id");
    if (!id.trim()) throw bad("供应商 id 不能为空");
    if (!args.provider || typeof args.provider !== "object" || Array.isArray(args.provider)) throw bad("provider 必须是对象");
    const { config } = await readFileConfig();
    if (config === null) throw new HostError(ERR.REJECTED, "models.json 现在不是合法的 JSON，先用 models.config.set 整份替换");
    return commit({ ...config, providers: { ...(config.providers ?? {}), [id]: args.provider } });
  });

  reg.add("models.config.removeProvider", async (args) => {
    const id = str(args, "id");
    const { config } = await readFileConfig();
    if (config === null) throw new HostError(ERR.REJECTED, "models.json 现在不是合法的 JSON，先用 models.config.set 整份替换");
    if (!config.providers || !(id in config.providers)) throw new HostError(ERR.NOT_FOUND, `没有这个自定义供应商：${id}`);
    const { [id]: _gone, ...rest } = config.providers;
    return commit({ ...config, providers: rest });
  });
}
