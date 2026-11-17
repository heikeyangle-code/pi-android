/**
 * 会话库命令：对「任意一个会话」做事，不要求它是当前会话。
 *
 * 为什么有这个文件：旧 App 没有这些能力，靠 Kotlin 自己扫目录、解析 JSONL（约 2600 行）。
 * SDK 的 `SessionManager` 本来就有 list / listAll / open / getTree / getBranch / appendSessionInfo /
 * appendLabelChange …，宿主只做适配：路径必须在 sessionDir 之内（宿主不是通用文件读写口），
 * 读用 `SessionManager.open`（只读，不追加），写只做 SDK 提供的追加类写入或删文件。
 *
 *   session.search   全文搜索（SessionInfo.allMessagesText）
 *   session.read     只读预览任意会话：摘要 / 当前分支 / 条目分页
 *   session.rename   改名（当前会话走 setSessionName，其他会话追加 session_info）
 *   session.delete   删文件（不许删当前会话）
 *   session.label    给条目打 / 清标签（会话树书签）
 *   session.path     当前分支（叶到根）的条目
 *   session.contextEdit  追加「上下文编辑」：隐藏或替换更早的条目对模型的可见内容
 */

import { join, resolve, sep } from "node:path";
import { unlink } from "node:fs/promises";
import { ERR, HostError, bad, bool, optStr, str } from "../protocol.mjs";
import { withState } from "../state.mjs";

const SNIPPET = 80;

/**
 * 列会话。`session.open` 总是把一个显式目录交给 SessionManager（App 传 --session-dir，否则 <agentDir>/sessions），
 * 所以会话文件平铺在 `controller.sessionDir` 里：用 `listAll(dir)`。`all=false` 只要当前 cwd 的。
 */
export function listInfos(sdk, controller, all) {
  const dir = controller.sessionDir ?? controller.cfg.sessionDir;
  return all ? sdk.SessionManager.listAll(dir) : sdk.SessionManager.list(controller.cwd, dir);
}

/** 去掉大字段，列表行不需要整段对话文本。 */
function slim(info) {
  const { allMessagesText, ...rest } = info;
  return rest;
}

function snippetOf(text, needle) {
  const i = text.toLowerCase().indexOf(needle);
  if (i < 0) return "";
  const from = Math.max(0, i - SNIPPET / 2);
  return (from > 0 ? "…" : "") + text.slice(from, from + SNIPPET + needle.length).replace(/\s+/g, " ");
}

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

  // 覆盖 session.list 的返回：默认不带 allMessagesText（手机上列几百个会话，每个带全文是浪费）
  // —— session.list 仍在 session.mjs 里，这里只加 search。
  reg.add("session.search", async (args) => {
    const query = str(args, "query").trim().toLowerCase();
    if (!query) throw bad("query 不能为空");
    const limit = Math.min(Math.max(Number(args.limit) || 50, 1), 500);
    const s = sdk();
    const infos = await listInfos(s, controller, bool(args, "all", false));
    const matches = [];
    for (const info of infos) {
      const hay = `${info.name ?? ""}\n${info.firstMessage ?? ""}\n${info.allMessagesText ?? ""}`;
      if (!hay.toLowerCase().includes(query)) continue;
      matches.push({ session: slim(info), snippet: snippetOf(hay, query) });
      if (matches.length >= limit) break;
    }
    return { query, matches, total: infos.length };
  });

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
    const name = str(args, "name").trim();
    if (!name) throw bad("会话名不能为空");
    const path = inside(str(args, "path"));
    if (path === currentFile()) {
      controller.requireSession().setSessionName(name);
      return withState(controller, { path });
    }
    const sm = sdk().SessionManager.open(path, sessionDir());
    sm.appendSessionInfo(name);
    return { path };
  });

  reg.add("session.delete", async (args) => {
    const path = inside(str(args, "path"));
    if (path === currentFile()) throw new HostError(ERR.BUSY, "不能删除当前会话");
    try {
      await unlink(path);
    } catch (error) {
      if (error?.code === "ENOENT") throw new HostError(ERR.NOT_FOUND, `会话文件不存在：${path}`);
      throw error;
    }
    return { path };
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
