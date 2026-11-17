/**
 * Hub：宿主里「向 App 说话」的唯一出口，负责序号、会话代号、向 App 提问并等回答。
 *
 *  - `seq`：进程内单调递增，App 看到跳号就知道丢帧，应当 `session.snapshot` 重新对账；
 *  - `epoch`：会话每被替换一次（new / switch / fork / clone / 扩展触发）加 1。
 *    事件里带着 epoch，App 丢弃「旧 epoch 的迟到事件」，不会把上一个会话的尾巴画进新会话；
 *  - 向 App 提问（对话框）是 `req` 帧；超时 / 被 abort / 宿主收回时额外发 `ui.cancel`
 *    通知 App 把对话框收掉（pi 的 rpc 模式不发，App 只能一直挂着一个已经没人等的对话框）。
 */

import { randomUUID } from "node:crypto";

export class Hub {
  constructor(transport) {
    this.transport = transport;
    this.seq = 0;
    this.epoch = 0;
    /** @type {Map<string, {resolve: Function, settle: Function}>} */
    this.pending = new Map();
  }

  res(id, ok, payload) {
    this.transport.send(
      ok ? { t: "res", id: id ?? null, ok: true, data: payload ?? {} } : { t: "res", id: id ?? null, ok: false, error: payload },
    );
  }

  emit(name, data = {}) {
    this.transport.send({ t: "evt", seq: ++this.seq, epoch: this.epoch, name, data });
  }

  bumpEpoch() {
    this.epoch += 1;
    return this.epoch;
  }

  /**
   * 向 App 提问。`fallback` 是超时 / 取消时的返回值。
   * @param {{timeout?: number, signal?: AbortSignal}} [opts]
   */
  ask(method, params, fallback, opts = {}) {
    if (opts.signal?.aborted) return Promise.resolve(fallback);
    const id = randomUUID();
    return new Promise((resolve) => {
      let timer;
      const settle = (value, notifyApp) => {
        if (!this.pending.delete(id)) return;
        if (timer) clearTimeout(timer);
        opts.signal?.removeEventListener("abort", onAbort);
        if (notifyApp) this.emit("ui.cancel", { id });
        resolve(value);
      };
      const onAbort = () => settle(fallback, true);
      opts.signal?.addEventListener("abort", onAbort, { once: true });
      if (opts.timeout > 0) timer = setTimeout(() => settle(fallback, true), opts.timeout);
      this.pending.set(id, { resolve, settle });
      this.transport.send({ t: "req", id, method, params });
    });
  }

  /** App 的回答（命令 `ui.respond`）。返回是否命中了一个还在等的提问。 */
  answer(id, value, cancelled) {
    const entry = this.pending.get(id);
    if (!entry) return false;
    entry.settle(cancelled ? undefined : value, false);
    return true;
  }

  /** 会话被替换 / 宿主退出：所有还在等的提问按取消处理。 */
  cancelAllAsks() {
    for (const entry of [...this.pending.values()]) entry.settle(undefined, true);
  }
}
