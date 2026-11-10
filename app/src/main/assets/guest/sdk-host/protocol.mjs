/**
 * 线协议 v1：App(Kotlin) ⇄ 宿主，一行一个 JSON（LF 分帧，UTF-8）。
 *
 * 三种帧，用 `t` 区分：
 *
 *   App → 宿主   {"id":"c1","cmd":"session.prompt","args":{...}}
 *   宿主 → App   {"t":"res","id":"c1","ok":true,"data":{...}}
 *                {"t":"res","id":"c1","ok":false,"error":{"code":"...","message":"..."}}
 *   宿主 → App   {"t":"evt","seq":12,"epoch":2,"name":"agent","data":{...}}
 *   宿主 → App   {"t":"req","id":"r7","method":"ui.select","params":{...}}   // 宿主向 App 提问
 *   App → 宿主   {"id":"u1","cmd":"ui.respond","args":{"id":"r7","value":"..."}}
 *
 * 为什么不沿用 pi 的 RPC 形状：那是 pi 为「无头脚本」设计的，没有序号（丢帧看不出来）、
 * 没有会话代号（换会话后旧事件和新事件混在一起）、对话框没有「宿主收回」通知。
 * 完整说明见 docs/sdk-host/protocol.md。
 */

export const PROTOCOL_VERSION = 1;

/** 错误码是协议的一部分：App 按码分支，不要按 message 文案。 */
export const ERR = Object.freeze({
  BAD_FRAME: "bad_frame", //            这一行不是合法 JSON / 缺 cmd
  UNKNOWN_CMD: "unknown_cmd",
  BAD_ARGS: "bad_args", //              参数缺失或类型不对
  NOT_OPEN: "not_open", //              还没 session.open
  BUSY: "busy", //                      当前状态不允许（如压缩中提交）
  NOT_FOUND: "not_found", //            模型 / 条目 / 会话文件不存在
  REJECTED: "rejected", //              pi 自己拒绝（例如 prompt 预检失败）
  UNSUPPORTED: "unsupported", //        本宿主 / 本 pi 版本没有这项能力
  INTERNAL: "internal",
});

export class HostError extends Error {
  constructor(code, message) {
    super(message);
    this.code = code;
  }
}

export const bad = (message) => new HostError(ERR.BAD_ARGS, message);

/** 取必填字符串参数。 */
export function str(args, key) {
  const value = args?.[key];
  if (typeof value !== "string" || value === "") throw bad(`缺少字符串参数 ${key}`);
  return value;
}

export function optStr(args, key) {
  const value = args?.[key];
  if (value === undefined || value === null) return undefined;
  if (typeof value !== "string") throw bad(`参数 ${key} 必须是字符串`);
  return value;
}

export function bool(args, key, fallback) {
  const value = args?.[key];
  if (value === undefined) return fallback;
  if (typeof value !== "boolean") throw bad(`参数 ${key} 必须是布尔值`);
  return value;
}
