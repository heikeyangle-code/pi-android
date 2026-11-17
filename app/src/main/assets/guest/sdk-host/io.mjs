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

/** 必须在加载任何别的模块之前调用。返回原始 stdout 的写函数。 */
export function takeOverStdout() {
  const rawWrite = process.stdout.write.bind(process.stdout);
  const errWrite = process.stderr.write.bind(process.stderr);
  process.stdout.write = (chunk, encodingOrCb, cb) =>
    errWrite(String(chunk), typeof encodingOrCb === "function" ? encodingOrCb : cb);
  return rawWrite;
}

export class Transport {
  /** @param {(text: string, cb: (err?: Error | null) => void) => boolean} rawWrite */
  constructor(rawWrite) {
    this.rawWrite = rawWrite;
    this.tail = Promise.resolve();
    this.pendingBytes = 0;
    this.waiters = [];
    this.closed = false;
  }

  /** 排队写一帧；不等待。帧顺序 = 调用顺序。 */
  send(frame) {
    if (this.closed) return;
    const text = `${JSON.stringify(frame)}\n`;
    const bytes = Buffer.byteLength(text);
    this.pendingBytes += bytes;
    this.tail = this.tail
      .then(() => this.#writeChunk(text))
      .catch(() => {
        // 对端关了管道：之后的写全部丢弃，进程由 stdin 的 end 事件收尾。
        this.closed = true;
      })
      .finally(() => {
        this.pendingBytes -= bytes;
        if (this.pendingBytes < HIGH_WATER_BYTES) this.#wake();
      });
  }

  /** 生产者在高水位时 await 它。 */
  drained() {
    if (this.pendingBytes < HIGH_WATER_BYTES) return Promise.resolve();
    return new Promise((resolve) => this.waiters.push(resolve));
  }

  /** 关机前等所有已排队的帧写完。 */
  flush() {
    return this.tail;
  }

  #wake() {
    const waiters = this.waiters;
    this.waiters = [];
    for (const resolve of waiters) resolve();
  }

  async #writeChunk(text) {
    for (;;) {
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
    for (;;) {
      const nl = buffer.indexOf("\n");
      if (nl < 0) break;
      let line = buffer.slice(0, nl);
      buffer = buffer.slice(nl + 1);
      if (line.endsWith("\r")) line = line.slice(0, -1);
      if (line.trim()) onLine(line);
    }
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
