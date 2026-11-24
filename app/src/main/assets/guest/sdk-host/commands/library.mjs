/**
 * 会话库命令：对「任意一个会话」做事，不要求它是当前会话。
 *
 * 为什么有这个文件：旧 App 没有这些能力，靠 Kotlin 自己扫目录、解析 JSONL（约 2600 行）。
 * SDK 的 `SessionManager` 本来就有 list / listAll / open / getTree / getBranch / appendSessionInfo /
 * appendLabelChange …，宿主只做适配：路径必须在 sessionDir 之内（宿主不是通用文件读写口），
 * 读用 `SessionManager.open`（只读，不追加），写只做 SDK 提供的追加类写入或删文件。
 *
 *   session.read     只读预览任意会话：摘要 / 当前分支 / 条目分页
 *   session.rename   改名（当前会话走 setSessionName，其他会话追加 session_info）
 *   session.delete   删会话（官方 deleteSessionFile：先 trash，再 unlink；不许删当前会话）
 *
 * 列表 / 搜索 / 排序在 sessionlist.mjs（`session.list`）。
 *   session.label    给条目打 / 清标签（会话树书签）
 *   session.path     当前分支（叶到根）的条目
 *   session.contextEdit  追加「上下文编辑」：隐藏或替换更早的条目对模型的可见内容
 */

import { join, resolve, sep } from "node:path";
import { existsSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { unlink } from "node:fs/promises";
import { ERR, HostError, bad, bool, optStr, str } from "../protocol.mjs";
import { withState } from "../state.mjs";

export function register(reg, { controller }) {
  const sdk = () => {
    if (!controller.sdk) throw new HostError(ERR.NOT_OPEN, "先 session.open");
    return controller.sdk;
  };
  // 会话根：session.open 时定下的平铺目录。
  const sessionDir = () => resolve(controller.sessionDir ?? join(controller.cfg.agentDir, "sessions"));
  const inside = (path) => {
    const root = sessionDir();
    const abs = resolve(path);
    if (abs !== root && !abs.startsWith(root + sep)) throw bad(`路径不在会话目录内：${path}`);
    return abs;
  };
  const currentFile = () => {
    const f = controller.session?.sessionManager?.getSessionFile?.();
    return f ? resolve(f) : undefined;
  };

  reg.add("session.read", (args) => {
    const path = inside(str(args, "path"));
    const mode = optStr(args, "mode") ?? "summary";
    const s = sdk();
    const sm = s.SessionManager.open(path, sessionDir());
    const base = { path, id: sm.getSessionId(), name: sm.getSessionName() ?? null, header: sm.getHeader(), entryCount: sm.getEntryCount(), leafId: sm.getLeafId() };
    if (mode === "summary") return base;
    if (mode === "branch") return { ...base, entries: sm.getBranch(optStr(args, "fromId")) };
    if (mode === "entries") {
      const all = sm.getEntries();
      const offset = Math.max(Number(args.offset) || 0, 0);
      const limit = Math.min(Math.max(Number(args.limit) || 200, 1), 1000);
      return { ...base, offset, entries: all.slice(offset, offset + limit), hasMore: offset + limit < all.length };
    }
    throw bad(`mode 只能是 summary / branch / entries：${mode}`);
  });

  reg.add("session.rename", (args) => {
    if (typeof args?.name !== "string") throw bad("缺少字符串参数 name");
    const name = args.name.trim();
    const path = inside(str(args, "path"));
    if (path === currentFile()) {
      controller.requireSession().setSessionName(name);
      controller.invalidateSessionList?.();
      return withState(controller, { path });
    }
    const sm = sdk().SessionManager.open(path, sessionDir());
    sm.appendSessionInfo(name);
    controller.invalidateSessionList?.();
    return { path };
  });

  // 官方 `session-selector.js` 的 `deleteSessionFile`：先试 `trash` 命令；它成功、或文件已不在，算成功；
  // 否则 unlink。返回 method 让 App 说「已移到回收站」还是「已删除」（官方的两句提示）。
  reg.add("session.delete", async (args) => {
    const path = inside(str(args, "path"));
    if (path === currentFile()) throw new HostError(ERR.BUSY, "不能删除当前会话");
    if (!existsSync(path)) throw new HostError(ERR.NOT_FOUND, `会话文件不存在：${path}`);
    const trash = spawnSync("trash", path.startsWith("-") ? ["--", path] : [path], { encoding: "utf-8" });
    let method;
    if (trash.status === 0 || !existsSync(path)) {
      method = "trash";
    } else {
      try {
        await unlink(path);
        method = "unlink";
      } catch (error) {
        const hint = [trash.error?.message, trash.stderr?.trim().split("\n")[0]].filter(Boolean).join(" · ");
        throw new HostError(ERR.REJECTED, `${error?.message ?? error}${hint ? ` (trash: ${hint.slice(0, 200)})` : ""}`);
      }
    }
    controller.invalidateSessionList?.();
    return { path, method };
  });

  reg.add("session.label", (args) => {
    const sm = controller.requireSession().sessionManager;
    const entryId = str(args, "entryId");
    if (!sm.getEntry(entryId)) throw new HostError(ERR.NOT_FOUND, `条目不存在：${entryId}`);
    const label = optStr(args, "label");
    sm.appendLabelChange(entryId, label && label.trim() ? label.trim() : undefined);
    return withState(controller, { entryId, label: sm.getLabel(entryId) ?? null });
  });

  reg.add("session.path", (args) => {
    const sm = controller.requireSession().sessionManager;
    return { entries: sm.getBranch(optStr(args, "fromId")), leafId: sm.getLeafId() };
  });

  reg.add("session.contextEdit", (args) => {
    const s = controller.requireSession();
    if (s.isStreaming) throw new HostError(ERR.BUSY, "正在生成，先停止再编辑上下文");
    const targetId = str(args, "targetId");
    const sm = s.sessionManager;
    if (!sm.getEntry(targetId)) throw new HostError(ERR.NOT_FOUND, `条目不存在：${targetId}`);
    // replacement 为 null = 从模型上下文里隐藏；{content} = 只替换内容。
    const replacement = args.replacement === null ? null : args.replacement;
    if (replacement !== null && !(replacement && typeof replacement === "object" && "content" in replacement)) throw bad("replacement 必须是 null 或 {content}");
    const id = sm.appendContextEdit(targetId, replacement);
    return withState(controller, { id });
  });
}
