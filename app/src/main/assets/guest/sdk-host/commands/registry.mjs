/**
 * 命令表。一条命令 = 一个名字 + 一个 `(args, call) => data` 函数。
 *
 * 命名规则：`<领域>.<动作>`，领域对应 App 里的一块功能（session / model / catalog / ui / packages …），
 * **不**对应 pi 的 RPC 命令名。旧 RPC 命令与新命令的对照表在 docs/sdk-host/rpc-to-host.md。
 *
 * 处理函数的契约：
 *  - 正常返回值 → `res.ok=true, data=返回值`（undefined 当成 {}）；
 *  - 抛 `HostError(code, message)` → `res.ok=false`；其他异常 → `internal`，带 message；
 *  - 需要「先应答、后做事」时（`session.prompt`：预检通过就应答，整轮跑完才 resolve），
 *    调 `call.defer()` 拿到 `{ok, fail}`，然后返回 `DEFERRED`。
 */

import { ERR, HostError } from "../protocol.mjs";

export const DEFERRED = Symbol("deferred");

export class Registry {
  constructor() {
    /** @type {Map<string, Function>} */
    this.handlers = new Map();
  }

  add(name, handler) {
    if (this.handlers.has(name)) throw new Error(`duplicate command ${name}`);
    this.handlers.set(name, handler);
  }

  names() {
    return [...this.handlers.keys()].sort();
  }

  /** 处理一帧入站命令（已解析成对象）。永远不抛。 */
  async dispatch(frame, hub) {
    const id = frame?.id ?? null;
    const name = frame?.cmd;
    if (typeof name !== "string") {
      hub.res(id, false, { code: ERR.BAD_FRAME, message: "缺少 cmd" });
      return;
    }
    const handler = this.handlers.get(name);
    if (!handler) {
      hub.res(id, false, { code: ERR.UNKNOWN_CMD, message: `未知命令 ${name}` });
      return;
    }
    let deferred = false;
    const call = {
      id,
      defer() {
        deferred = true;
        return {
          ok: (data) => hub.res(id, true, data),
          fail: (error) => hub.res(id, false, toWire(error)),
        };
      },
    };
    try {
      const data = await handler(frame.args ?? {}, call);
      if (data === DEFERRED || deferred) return;
      hub.res(id, true, data ?? {});
    } catch (error) {
      hub.res(id, false, toWire(error));
    }
  }
}

export function toWire(error) {
  if (error instanceof HostError) return { code: error.code, message: error.message };
  return { code: ERR.INTERNAL, message: String(error?.message ?? error), detail: String(error?.stack ?? "") };
}
