/**
 * 通用调用口：`sdk.call` / `sdk.get`。
 *
 * 这是「随引擎升级」的杠杆：pi 新增一个 `AgentSession` / `SettingsManager` / `SessionManager` /
 * `ModelRuntime` / `PackageManager` 的方法，App 不用等宿主改代码就能调。
 * 具名命令（session.prompt 等）仍然是主路径 —— 它们带参数校验和两阶段应答这类语义；
 * 通用口只做「方法名 + 参数数组 → 可序列化的结果」，没有任何额外逻辑，也就没有额外的维护面。
 *
 * 边界（写在这里，因为这是安全面）：
 *  - 只开放下面这张表里的对象；方法名必须是该对象上的函数，且不以 `_` 或 `#` 开头；
 *  - 不开放 `constructor`、`dispose`（会话生命周期归运行时管）；
 *  - 结果必须能 JSON 序列化，循环引用 / 函数字段会被剔除并在 `dropped` 里说明。
 *  - 宿主只接受本机 App 的 stdin，没有网络入口；这里的限制是防「写错」而不是防「攻击」。
 */

import { ERR, HostError, str } from "../protocol.mjs";

const DENY = new Set(["constructor", "dispose", "then"]);

/** JSON 安全化：循环引用、函数、bigint、Symbol 处理成可序列化的形状，并记下丢了什么。 */
export function toWire(value, dropped = []) {
  const seen = new WeakSet();
  const walk = (v, path) => {
    if (v === null || ["string", "number", "boolean"].includes(typeof v)) return v;
    if (v === undefined) return undefined;
    if (typeof v === "bigint") return v.toString();
    if (typeof v === "function" || typeof v === "symbol") {
      dropped.push(path);
      return undefined;
    }
    if (v instanceof Map) return walk(Object.fromEntries(v), path);
    if (v instanceof Set) return walk([...v], path);
    if (v instanceof Date) return v.toISOString();
    if (v instanceof Error) return { name: v.name, message: v.message };
    if (seen.has(v)) {
      dropped.push(`${path} (circular)`);
      return undefined;
    }
    seen.add(v);
    if (Array.isArray(v)) return v.map((x, i) => walk(x, `${path}[${i}]`) ?? null);
    const out = {};
    for (const key of Object.keys(v)) {
      const next = walk(v[key], `${path}.${key}`);
      if (next !== undefined) out[key] = next;
    }
    seen.delete(v);
    return out;
  };
  return walk(value, "$");
}

export function register(reg, { controller }) {
  const targets = () => {
    const session = controller.requireSession();
    const services = controller.runtime.services;
    return {
      session,
      runtime: controller.runtime,
      sessionManager: session.sessionManager,
      settings: services?.settingsManager,
      modelRuntime: session.modelRuntime,
      resourceLoader: session.resourceLoader,
      extensionRunner: session.extensionRunner,
      // 每次现建：它只是设置 + 目录的视图，没有自己的状态。
      packageManager: new controller.sdk.DefaultPackageManager({
        cwd: session.sessionManager.getCwd(),
        agentDir: controller.cfg.agentDir,
        settingsManager: services?.settingsManager,
      }),
    };
  };

  const resolve = (name) => {
    const table = targets();
    const target = table[name];
    if (!target) throw new HostError(ERR.BAD_ARGS, `未知 target：${name}。可用：${Object.keys(table).join(", ")}`);
    return target;
  };

  reg.add("sdk.call", async (args) => {
    const target = resolve(str(args, "target"));
    const method = str(args, "method");
    if (method.startsWith("_") || method.startsWith("#") || DENY.has(method)) {
      throw new HostError(ERR.BAD_ARGS, `不开放的方法：${method}`);
    }
    if (typeof target[method] !== "function") {
      throw new HostError(ERR.UNSUPPORTED, `${args.target} 上没有方法 ${method}（这个 pi 版本没有该 API？）`);
    }
    const params = Array.isArray(args.params) ? args.params : [];
    const result = await target[method](...params);
    const dropped = [];
    return { result: toWire(result, dropped) ?? null, ...(dropped.length ? { dropped } : {}) };
  });

  // 读属性（getter 也算）：`session.model`、`session.isStreaming` 这类。
  reg.add("sdk.get", (args) => {
    const target = resolve(str(args, "target"));
    const prop = str(args, "prop");
    if (prop.startsWith("_") || prop.startsWith("#")) throw new HostError(ERR.BAD_ARGS, `不开放的属性：${prop}`);
    const dropped = [];
    return { value: toWire(target[prop], dropped) ?? null, ...(dropped.length ? { dropped } : {}) };
  });
}
