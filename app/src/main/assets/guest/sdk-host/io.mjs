/**
 * 传输层：stdin/stdout 上的 JSONL。三件事必须做对，否则线上会出「偶发乱码 / 偶发卡死」。
 *
 *  1. **接管 stdout。** 协议用的是 fd 1，而扩展和 SDK 内部的 `console.log` 也写 fd 1，
 *     一行日志就能把协议流毁掉。启动第一件事：把 `process.stdout.write` 改道到 stderr，
 *     协议自己持有原始的 write。（pi 自己的 rpc 模式 `core/output-guard.js` 做的是同一件事。）
 *  2. **带背压的写。** 管道满了会返回 EAGAIN/ENOBUFS，必须重试而不是丢；写入队列超过
 *     高水位后，`drained()` 让生产者（agent 事件订阅）停下来等，避免手机上把内存吃光。
 *  3. **只按 LF 分行。** `readline` 会把 `\r`、U+2028 当行尾，JSON 字符串里合法出现的这些字符
 *     会把一行劈成两半。pi 的 `rpc/jsonl.js` 同样自己实现了 LF-only 读取。
 *
 * 原型版用 `writeSync(1)`：管道满时会抛 EAGAIN（同步写不会重试），并且会阻塞整个事件循环。
 */

import { StringDecoder } from "node:string_decoder";

const RETRY_DELAY_MS = 10;
const HIGH_WATER_BYTES = 256 * 1024;

let stdoutHook = null;

/** 注册 stdout 旁路钩子（用于捕获扩展直写 stdout 的 BEL `\x07` / OSC 通知 / 标题等终端控制序列）。 */
export function setStdoutHook(fn) {
  stdoutHook = typeof fn === "function" ? fn : null;
}

/** 必须在加载任何别的模块之前调用。返回原始 stdout 的写函数。 */
export function takeOverStdout() {
  const rawWrite = process.stdout.write.bind(process.stdout);
  const errWrite = process.stderr.write.bind(process.stderr);
  process.stdout.write = (chunk, encodingOrCb, cb) => {
    const text = typeof chunk === "string" ? chunk : Buffer.isBuffer(chunk) ? chunk.toString("utf8") : String(chunk);
    if (stdoutHook && (text.includes("\x07") || text.includes("\x1b]"))) {
      try {
        stdoutHook(text);
      } catch {
        // 钩子异常不能破坏 stdout 重定向
      }
    }
    return errWrite(text, typeof encodingOrCb === "function" ? encodingOrCb : cb);
  };
  return rawWrite;
}

export class Transport {
  /** @param {(text: string, cb: (err?: Error | null) => void) => boolean} rawWrite */
  constructor(rawWrite) {
    this.rawWrite = rawWrite;
    this.queue = [];
    this.flushing = false;
    this.flushWaiters = [];
    this.pendingBytes = 0;
    this.waiters = [];
    this.closed = false;
  }

  /** True when buffered output has reached backpressure high-water mark. */
  isBackpressured() {
    return this.pendingBytes >= HIGH_WATER_BYTES;
  }

  /** 排队写一帧；不等待。帧顺序 = 调用顺序；连续帧自动合并为单次 write(2)。 */
  send(frame) {
    if (this.closed) return;
    const text = `${JSON.stringify(frame)}\n`;
    const bytes = Buffer.byteLength(text);
    this.pendingBytes += bytes;
    this.queue.push({ text, bytes });
    if (!this.flushing) {
      this.flushing = true;
      void this.#drain();
    }
  }

  /** 生产者在高水位时 await 它。 */
  drained() {
    if (this.pendingBytes < HIGH_WATER_BYTES || this.closed) return Promise.resolve();
    return new Promise((resolve) => this.waiters.push(resolve));
  }

  /** 关机前等所有已排队的帧写完。 */
  flush() {
    if (!this.flushing && this.queue.length === 0) return Promise.resolve();
    return new Promise((resolve) => this.flushWaiters.push(resolve));
  }

  #wake() {
    if (this.waiters.length === 0) return;
    const waiters = this.waiters;
    this.waiters = [];
    for (const resolve of waiters) resolve();
  }

  #wakeFlush() {
    if (this.flushWaiters.length === 0) return;
    const waiters = this.flushWaiters;
    this.flushWaiters = [];
    for (const resolve of waiters) resolve();
  }

  async #drain() {
    try {
      while (!this.closed && this.queue.length > 0) {
        // Coalesce all currently queued frames into one write syscall (huge win under proot/proroot).
        let text;
        let bytes;
        if (this.queue.length === 1) {
          const item = this.queue.pop();
          text = item.text;
          bytes = item.bytes;
        } else {
          const batch = this.queue;
          this.queue = [];
          bytes = 0;
          const parts = new Array(batch.length);
          for (let i = 0; i < batch.length; i++) {
            parts[i] = batch[i].text;
            bytes += batch[i].bytes;
          }
          text = parts.join("");
        }
        try {
          await this.#writeChunk(text);
        } catch {
          // 对端关了管道：之后的写全部丢弃，进程由 stdin 的 end 事件收尾。
          this.closed = true;
          this.queue.length = 0;
          this.pendingBytes = 0;
          this.#wake();
          break;
        }
        this.pendingBytes = Math.max(0, this.pendingBytes - bytes);
        if (this.pendingBytes < HIGH_WATER_BYTES) this.#wake();
      }
    } finally {
      this.flushing = false;
      this.#wake();
      this.#wakeFlush();
    }
  }

  async #writeChunk(text) {
    for (;;) {
      if (this.closed) return;
      try {
        await new Promise((resolve, reject) => {
          try {
            this.rawWrite(text, (error) => (error ? reject(error) : resolve()));
          } catch (error) {
            reject(error);
          }
        });
        return;
      } catch (error) {
        const code = error?.code;
        if (code !== "EAGAIN" && code !== "ENOBUFS" && code !== "EWOULDBLOCK") throw error;
        await new Promise((resolve) => setTimeout(resolve, RETRY_DELAY_MS));
      }
    }
  }
}

/** LF-only 行读取。返回取消函数。 */
export function readLines(stream, onLine, onEnd) {
  const decoder = new StringDecoder("utf8");
  let buffer = "";
  const onData = (chunk) => {
    buffer += typeof chunk === "string" ? chunk : decoder.write(chunk);
    let start = 0;
    for (;;) {
      const nl = buffer.indexOf("\n", start);
      if (nl < 0) break;
      const end = nl > start && buffer.charCodeAt(nl - 1) === 13 ? nl - 1 : nl;
      const line = buffer.slice(start, end);
      start = nl + 1;
      if (line.trim()) onLine(line);
    }
    if (start > 0) buffer = start >= buffer.length ? "" : buffer.slice(start);
  };
  const end = () => {
    buffer += decoder.end();
    if (buffer.trim()) onLine(buffer);
    buffer = "";
    onEnd?.();
  };
  stream.on("data", onData);
  stream.on("end", end);
  return () => {
    stream.off("data", onData);
    stream.off("end", end);
  };
}
