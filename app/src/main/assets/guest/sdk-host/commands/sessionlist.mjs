/**
 * 会话列表（pi 的 `/resume` 选择器）的数据面。
 *
 * 对照的官方位置（SDK `dist/modes/interactive/components/`）：
 *   session-selector.js         `SessionSelectorComponent`：current / all 两个范围、各自一份已加载的列表、
 *                               切范围 / 排序 / 命名过滤只重算视图不重读磁盘、变更（改名 / 删除）之后重读、
 *                               加载进度 `(loaded, total, partial)`、可中止的加载（`cancelLoads`）
 *   session-selector.js         `buildSessionTree` / `flattenSessionTree`：threaded 排序下按
 *                               `parentSessionPath` 成树、按子树最近活动降序；行带 depth / isLast / ancestorContinues
 *   session-selector-search.js  `filterAndSortSessions`：搜索语法（`re:` 正则、`"短语"`、模糊）、named 过滤、
 *                               recent / relevance 排序 —— **直接 import 官方文件**（仅在有查询词时延迟加载，
 *                               避免无搜索时把未打包的 `@earendil-works/pi-tui` 整棵模块树拖进冷启动）
 *   interactive-mode.js         `showSessionSelector`：current = `SessionManager.list(cwd, dir)`，
 *                               all = `listAll(dir)`（自定义会话目录时）
 *
 * 性能设计（保持 100% 官方语义）：
 *  1. 延迟加载 `session-selector-search.js`：无查询词时完全不 import `@earendil-works/pi-tui`；
 *  2. 路径规范化缓存：每次渲染把 `realpathSync` 记忆化，消除 O(N²) proot syscall；
 *  3. 持久化增量元数据索引（`<agentDir>/.session-list-index.json`）：按 `(size, mtimeMs)` 复用
 *     未改动文件的 SDK 解析结果，仅对变更的少数文件增量解析；全文搜索时自动通过官方
 *     `SessionManager.list`/`listAll` 回填完整 `allMessagesText`。
 *
 * 命令：
 *   session.list         {scope, sortMode, nameFilter, query, refresh, groupByCwd}  → {scope, rows, total, loading:false}
 *   session.list.cancel  中止在途的加载（对应 `cancelLoads`）
 */

import { createReadStream, existsSync } from "node:fs";
import { readdir, readFile, rename, stat, writeFile } from "node:fs/promises";
import { createInterface } from "node:readline";
import { pathToFileURL } from "node:url";
import { basename, join } from "node:path";
import { homedir } from "node:os";
import { ERR, HostError, bad, bool, optStr } from "../protocol.mjs";

const SORT_MODES = new Set(["threaded", "recent", "relevance"]);
const NAME_FILTERS = new Set(["all", "named"]);
const SCOPES = new Set(["current", "all"]);
const INDEX_VERSION = 1;
const MAX_INCREMENTAL_DIRTY = 16;
const STAT_CONCURRENCY = 32;

const hasSessionNameLocal = (session) => Boolean(session.name?.trim());

let pathsCache = null;
let searchCache = null;

/** 官方路径工具（不依赖 `@earendil-works/pi-tui`，体积小）。 */
async function officialPaths(controller) {
  if (pathsCache) return pathsCache;
  const base = join(controller.cfg.piDir, "dist");
  const paths = await import(pathToFileURL(join(base, "utils", "paths.js")).href);
  pathsCache = { hasSessionName: hasSessionNameLocal, ...paths };
  return pathsCache;
}

/** 官方搜索实现（依赖 `@earendil-works/pi-tui`）：仅在真正有搜索词时延迟加载。 */
async function officialSearch(controller) {
  if (searchCache) return searchCache;
  const base = join(controller.cfg.piDir, "dist");
  const search = await import(pathToFileURL(join(base, "modes", "interactive", "components", "session-selector-search.js")).href);
  searchCache = {
    filterAndSortSessions: search.filterAndSortSessions,
    hasSessionName: search.hasSessionName,
    parseSearchQuery: search.parseSearchQuery,
  };
  return searchCache;
}

async function mapWithConcurrency(items, limit, fn, signal) {
  const results = new Array(items.length);
  let next = 0;
  const worker = async () => {
    while (next < items.length) {
      signal?.throwIfAborted();
      const idx = next++;
      results[idx] = await fn(items[idx], idx);
    }
  };
  await Promise.all(Array.from({ length: Math.min(limit, items.length) }, () => worker()));
  return results;
}

function extractTextContent(message) {
  const content = message?.content;
  if (typeof content === "string") return content;
  if (!Array.isArray(content)) return "";
  return content
    .filter((block) => block && block.type === "text" && typeof block.text === "string")
    .map((block) => block.text)
    .join(" ");
}

function getMessageActivityTime(entry) {
  const message = entry?.message;
  if (!message || typeof message.role !== "string" || !("content" in message)) return undefined;
  if (message.role !== "user" && message.role !== "assistant") return undefined;
  if (typeof message.timestamp === "number") return message.timestamp;
  const t = new Date(entry.timestamp).getTime();
  return Number.isNaN(t) ? undefined : t;
}

/**
 * 单文件流式解析，字段与边界条件严格对齐 SDK `dist/core/session-manager.js#buildSessionInfo`。
 * 仅用于持久化索引命中绝大多数文件时增量更新少量变更文件；大批量仍走官方 `SessionManager.list`/`listAll`。
 */
async function parseSessionMetaFile(filePath, fileStats, signal) {
  try {
    const stats = fileStats ?? (await stat(filePath));
    let header = null;
    let messageCount = 0;
    let firstMessage = "";
    let name;
    let lastActivityTime;
    // 对齐官方 session-manager.js (#11956)：不把 signal 直接交给 createReadStream，
    // 防止提前跳出循环后 abort 触发未监听的 stream error；循环内显式检查并在 finally 销毁流。
    const stream = createReadStream(filePath, { encoding: "utf8" });
    try {
      const rl = createInterface({
        input: stream,
        crlfDelay: Infinity,
      });
      for await (const line of rl) {
        signal?.throwIfAborted();
        const trimmed = line.trim();
      if (!trimmed) continue;
      let entry;
      try {
        entry = JSON.parse(trimmed);
      } catch {
        continue;
      }
      if (!entry || typeof entry !== "object") continue;
      if (!header) {
        if (entry.type !== "session") return null;
        header = entry;
        continue;
      }
      if (entry.type === "session_info") {
        name = typeof entry.name === "string" ? entry.name.trim() || undefined : undefined;
      }
      if (entry.type !== "message") continue;
      messageCount++;
      const activityTime = getMessageActivityTime(entry);
      if (typeof activityTime === "number") {
        lastActivityTime = Math.max(lastActivityTime ?? 0, activityTime);
      }
      const message = entry.message;
      if (!message || typeof message.role !== "string" || !("content" in message)) continue;
      if (message.role !== "user" && message.role !== "assistant") continue;
      if (!firstMessage && message.role === "user") {
        const textContent = extractTextContent(message);
        if (textContent) firstMessage = textContent;
      }
      }
    } finally {
      stream.destroy();
    }
    signal?.throwIfAborted();
    if (!header) return null;
    const cwd = typeof header.cwd === "string" ? header.cwd : "";
    const parentSessionPath = header.parentSession;
    const headerTime = typeof header.timestamp === "string" ? new Date(header.timestamp).getTime() : NaN;
    const modified =
      typeof lastActivityTime === "number" && lastActivityTime > 0
        ? new Date(lastActivityTime)
        : !Number.isNaN(headerTime)
          ? new Date(headerTime)
          : stats.mtime;
    return {
      path: filePath,
      id: header.id,
      cwd,
      name,
      parentSessionPath,
      created: new Date(header.timestamp),
      modified,
      messageCount,
      firstMessage: firstMessage || "(no messages)",
      // 显式保留 undefined：需要全文搜索时据其判断是否回源官方 `SessionManager.list`/`listAll`。
      allMessagesText: undefined,
    };
  } catch {
    signal?.throwIfAborted();
    return null;
  }
}

function serializeIndexEntry(session, st) {
  return {
    size: st.size,
    mtimeMs: st.mtimeMs,
    id: session.id,
    cwd: session.cwd,
    name: session.name ?? null,
    parentSessionPath: session.parentSessionPath ?? null,
    created: session.created instanceof Date ? session.created.toISOString() : String(session.created),
    modified: session.modified instanceof Date ? session.modified.toISOString() : String(session.modified),
    messageCount: session.messageCount,
    firstMessage: session.firstMessage,
  };
}

function hydrateIndexEntry(filePath, raw) {
  return {
    path: filePath,
    id: raw.id,
    cwd: typeof raw.cwd === "string" ? raw.cwd : "",
    name: raw.name ?? undefined,
    parentSessionPath: raw.parentSessionPath ?? undefined,
    created: new Date(raw.created),
    modified: new Date(raw.modified),
    messageCount: Number(raw.messageCount) || 0,
    firstMessage: typeof raw.firstMessage === "string" ? raw.firstMessage : "(no messages)",
    allMessagesText: undefined,
  };
}

/** `session-selector.js` 的 `buildSessionTree`：按 parentSessionPath 成树，子树按最近活动降序。 */
export function buildSessionTree(sessions, pathKey) {
  const byPath = new Map();
  for (const session of sessions) {
    byPath.set(pathKey(session.path), { session, children: [], latestActivity: session.modified.getTime() });
  }
  const roots = [];
  for (const session of sessions) {
    const node = byPath.get(pathKey(session.path));
    const parentPath = session.parentSessionPath ? pathKey(session.parentSessionPath) : undefined;
    if (parentPath && byPath.has(parentPath)) byPath.get(parentPath).children.push(node);
    else roots.push(node);
  }
  // 官方用递归；会话数是几百级、谱系深度很浅，递归够用，但这里写成显式栈，与 AGENTS.md「长链用显式栈」一致。
  const order = [];
  const stack = roots.map((r) => ({ node: r, done: false }));
  while (stack.length) {
    const top = stack[stack.length - 1];
    if (!top.done) {
      top.done = true;
      for (const child of top.node.children) stack.push({ node: child, done: false });
    } else {
      stack.pop();
      let latest = top.node.session.modified.getTime();
      for (const child of top.node.children) latest = Math.max(latest, child.latestActivity);
      top.node.latestActivity = latest;
      order.push(top.node);
    }
  }
  for (const node of byPath.values()) node.children.sort((a, b) => b.latestActivity - a.latestActivity);
  roots.sort((a, b) => b.latestActivity - a.latestActivity);
  return roots;
}

/** `flattenSessionTree`：前序展开，并带上画连线要用的 depth / isLast / ancestorContinues。 */
export function flattenSessionTree(roots) {
  const result = [];
  const stack = [];
  for (let i = roots.length - 1; i >= 0; i--) stack.push({ node: roots[i], depth: 0, ancestorContinues: [], isLast: i === roots.length - 1 });
  while (stack.length) {
    const { node, depth, ancestorContinues, isLast } = stack.pop();
    result.push({ session: node.session, depth, isLast, ancestorContinues });
    // 只给非根的祖先画延续线（官方：`depth > 0 ? !isLast : false`）
    const continues = depth > 0 ? !isLast : false;
    for (let i = node.children.length - 1; i >= 0; i--) {
      stack.push({
        node: node.children[i],
        depth: depth + 1,
        ancestorContinues: [...ancestorContinues, continues],
        isLast: i === node.children.length - 1,
      });
    }
  }
  return result;
}

/** 创建带记忆化的路径比较键函数，避免渲染 N 条会话时重复调用 realpathSync（proot 下每条 syscall 很贵）。 */
function makePathKeyMemo(o, backingCache) {
  const cache = backingCache ?? new Map();
  return (p) => {
    let k = cache.get(p);
    if (k === undefined) {
      k = o.getPathComparisonKey(o.canonicalizePath(p));
      cache.set(p, k);
    }
    return k;
  };
}

/**
 * `SessionList.filterSessions`：threaded 且无查询 → 树；其余 → `filterAndSortSessions` 的平铺。
 * name 过滤先于一切（官方先 `hasSessionName` 过滤，再把 "all" 传给 filterAndSortSessions）。
 */
export function viewOf(o, sessions, { sortMode, nameFilter, query, groupByCwd = false }, pathKeyMemo) {
  const hasName = o.hasSessionName ?? hasSessionNameLocal;
  const nameFiltered = nameFilter === "all" ? sessions : sessions.filter((s) => hasName(s));
  const trimmedQuery = query.trim();
  if (sortMode === "threaded" && !trimmedQuery) {
    const key = pathKeyMemo ?? makePathKeyMemo(o);
    if (!groupByCwd) return flattenSessionTree(buildSessionTree(nameFiltered, key));
    // App 层的呈现（不是 pi 的概念）：手机上「全部」范围按工作目录分组展示，每组各自成树 ——
    // 父会话在别的目录时，子会话在本组里就是根（跨组的连线没法画）。组内规则仍是官方的 build/flatten。
    const groups = new Map();
    for (const s of nameFiltered) {
      const g = groups.get(s.cwd ?? "");
      if (g) g.push(s);
      else groups.set(s.cwd ?? "", [s]);
    }
    return [...groups.values()].flatMap((g) => flattenSessionTree(buildSessionTree(g, key)));
  }
  if (!trimmedQuery) {
    // 无搜索词时 recent / relevance 都不改变顺序（对照 session-selector-search.js:189 `if (!trimmed) return nameFiltered`），
    // 无需加载 `@earendil-works/pi-tui`。
    return nameFiltered.map((session) => ({ session, depth: 0, isLast: true, ancestorContinues: [] }));
  }
  return o.filterAndSortSessions(nameFiltered, query, sortMode, "all").map((session) => ({ session, depth: 0, isLast: true, ancestorContinues: [] }));
}

/** 列表行上线：去掉整段对话文本；多带「~ 缩写」后的 cwd / path（官方 `shortenHomePath`，家目录取宿主进程的）。 */
function rowOf(o, node, currentKey, pathKey, home) {
  const s = node.session;
  const key = pathKey(s.path);
  return {
    path: s.path,
    id: s.id,
    cwd: s.cwd,
    cwdShort: s.cwd ? o.shortenHomePath(s.cwd, home) : "",
    pathShort: o.shortenHomePath(s.path, home),
    name: s.name ?? null,
    created: s.created.toISOString(),
    modified: s.modified.toISOString(),
    messageCount: s.messageCount,
    firstMessage: s.firstMessage,
    parentSessionPath: s.parentSessionPath ?? null,
    depth: node.depth,
    isLast: node.isLast,
    ancestorContinues: node.ancestorContinues,
    isCurrent: currentKey !== undefined && key === currentKey,
  };
}

/** `parseSearchQuery` 的错误（比如坏正则）要让 App 说得出来：官方此时返回空表，这里额外带上原因。 */
function queryError(o, query) {
  return query.trim() ? (o.parseSearchQuery(query).error ?? null) : null;
}

export function register(reg, { controller }) {
  /** @type {{key:string, current?:any[], all?:any[], allHasText?:boolean, currentHasText?:boolean, load?:{scope:string, fullText:boolean, abort:AbortController, superseded:boolean, promise:Promise<any[]>}}} */
  const state = { key: "" };
  controller.sessionListState = state;

  const sdk = () => {
    if (!controller.sdk) throw new HostError(ERR.NOT_OPEN, "先 session.open");
    return controller.sdk;
  };
  const dirOf = () => controller.sessionDir ?? controller.cfg.sessionDir;
  const indexFileOf = () => (controller.cfg.agentDir ? join(controller.cfg.agentDir, ".session-list-index.json") : null);
  /** 缓存的有效范围：会话目录 + cwd（cwd 变了「当前文件夹」就是另一批）。 */
  const keyOf = () => `${dirOf()}\n${controller.cwd}`;
  const syncKey = () => {
    const k = keyOf();
    if (state.key !== k) {
      abortLoad();
      state.key = k;
      state.current = undefined;
      state.all = undefined;
      state.currentHasText = false;
      state.allHasText = false;
      state.load = undefined;
    }
  };

  /** 终止在途的加载，并标明「是被换掉的」：等着它的视图请求据此改去等新的那个，而不是报错。 */
  const abortLoad = () => {
    if (state.load) {
      state.load.superseded = true;
      state.load.abort.abort();
      state.load = undefined;
    }
  };

  /** 变更（改名 / 删除 / 新会话落盘）之后：缓存作废，下一次 session.list 重读。官方 `refreshSessionsAfterMutation`。 */
  controller.invalidateSessionList = () => {
    abortLoad();
    state.current = undefined;
    state.all = undefined;
    state.currentHasText = false;
    state.allHasText = false;
  };

  let diskIndexMem = null;
  async function readDiskIndex(dir) {
    const file = indexFileOf();
    if (!file) return null;
    const piVersion = controller.sdk?.VERSION ?? "";
    if (diskIndexMem && diskIndexMem.dir === dir && diskIndexMem.piVersion === piVersion) {
      return diskIndexMem.entries;
    }
    try {
      const raw = JSON.parse(await readFile(file, "utf8"));
      if (raw?.v === INDEX_VERSION && raw.dir === dir && raw.piVersion === piVersion && raw.entries && typeof raw.entries === "object") {
        diskIndexMem = { dir, piVersion, entries: raw.entries };
        return raw.entries;
      }
    } catch {
      // 索引不存在或损坏：静默回落全量扫描。
    }
    return null;
  }

  async function writeDiskIndex(dir, entries) {
    const file = indexFileOf();
    if (!file) return;
    const piVersion = controller.sdk?.VERSION ?? "";
    diskIndexMem = { dir, piVersion, entries };
    try {
      const tmp = `${file}.tmp`;
      await writeFile(tmp, JSON.stringify({ v: INDEX_VERSION, dir, piVersion, entries }), "utf8");
      await rename(tmp, file);
    } catch {
      // 写索引失败不影响列表正确性。
    }
  }

  /**
   * 尝试走增量索引快速加载平铺会话目录（仅当不需要全文搜索 `allMessagesText` 时）：
   *  - 0 文件变更 → 直接返回缓存元数据（~5 ms，0 次 JSONL 读取）；
   *  - 1..16 文件变更 → 仅流式解析变更文件，其余复用索引；
   *  - 无索引或 >16 文件变更 → 返回 null，回落官方 `SessionManager.list`/`listAll` 并在完成后保存索引。
   */
  async function tryIncrementalLoad(scope, dir, o, signal) {
    if (!dir || !existsSync(dir)) return null;
    const existing = await readDiskIndex(dir);
    if (!existing) return null;
    const dirEntries = await readdir(dir, { withFileTypes: true });
    // 若存在子目录（例如内置终端运行官方 pi CLI 生成的 --cwd--/ 目录），回落官方 SessionManager.listAll 确保 100% 不漏会话。
    if (dirEntries.some((d) => d.isDirectory())) return null;
    const jsonlNames = dirEntries.filter((d) => d.isFile() && d.name.endsWith(".jsonl")).map((d) => d.name);
    const statList = await mapWithConcurrency(
      jsonlNames,
      STAT_CONCURRENCY,
      async (name) => {
        const path = join(dir, name);
        try {
          return { name, path, stats: await stat(path) };
        } catch {
          return null;
        }
      },
      signal,
    );
    const files = statList.filter(Boolean);
    const hits = [];
    const dirty = [];
    const nextEntries = {};
    for (const f of files) {
      const prev = existing[f.name];
      if (prev && prev.size === f.stats.size && prev.mtimeMs === f.stats.mtimeMs) {
        hits.push(hydrateIndexEntry(f.path, prev));
        nextEntries[f.name] = prev;
      } else {
        dirty.push(f);
      }
    }
    if (dirty.length > MAX_INCREMENTAL_DIRTY) return null;
    if (dirty.length > 0) {
      const parsed = await mapWithConcurrency(
        dirty,
        10,
        async (f) => ({ f, info: await parseSessionMetaFile(f.path, f.stats, signal) }),
        signal,
      );
      for (const { f, info } of parsed) {
        if (info) {
          hits.push(info);
          nextEntries[f.name] = serializeIndexEntry(info, f.stats);
        }
      }
    }
    if (dirty.length > 0 || Object.keys(existing).length !== Object.keys(nextEntries).length) {
      void writeDiskIndex(dir, nextEntries);
    }
    hits.sort((a, b) => b.modified.getTime() - a.modified.getTime());
    if (scope === "current") {
      const resolvedCwd = o.resolvePath(controller.cwd);
      return hits.filter((s) => s.cwd && o.pathsEqual(o.resolvePath(s.cwd), resolvedCwd));
    }
    return hits;
  }

  /** 官方全量加载完成后把目录元数据刷入持久化索引（异步，不阻塞应答）。 */
  async function saveIndexFromSessions(dir, sessions) {
    if (!dir || !sessions) return;
    try {
      const nextEntries = {};
      await mapWithConcurrency(sessions, STAT_CONCURRENCY, async (s) => {
        if (!s?.path) return;
        try {
          const st = await stat(s.path);
          nextEntries[basename(s.path)] = serializeIndexEntry(s, st);
        } catch {
          // ignore disappeared file
        }
      });
      await writeDiskIndex(dir, nextEntries);
    } catch {
      // ignore
    }
  }

  const officialLoader = (scope, onProgress, signal) => {
    const s = sdk();
    return scope === "current"
      ? s.SessionManager.list(controller.cwd, dirOf(), onProgress, signal)
      : s.SessionManager.listAll(dirOf(), onProgress, signal);
  };

  reg.add("session.list.cancel", () => {
    const had = Boolean(state.load);
    abortLoad();
    return { cancelled: had };
  });

  /**
   * 开一次读盘。读的过程中发 `sessions.progress`；读完才写缓存（被换掉的加载不写）。
   * 加载是**共享**的：同一时刻只有一个，视图请求（没有缓存时）直接等它，不再各自重起一遍整盘读取。
   * `mine.render` 保持最新：新查询复用在途全量扫描时更新 `mine.render`，避免进度事件把旧搜索词的结果覆写回 UI。
   */
  const startLoad = (scope, fullText, o, render) => {
    abortLoad();
    const ctl = new AbortController();
    const mine = { scope, fullText, abort: ctl, superseded: false, render, promise: undefined };
    const onProgress = (loaded, total, partial) => {
      if (state.load !== mine) return;
      controller.hub.emit("sessions.progress", { scope, loaded, total, rows: partial ? mine.render(partial) : undefined });
    };
    mine.promise = (async () => {
      const dir = dirOf();
      let sessions = null;
      let hasText = false;
      if (!fullText && dir) {
        try {
          sessions = await tryIncrementalLoad(scope, dir, o, ctl.signal);
        } catch (err) {
          if (ctl.signal.aborted) throw err;
          sessions = null;
        }
      }
      if (!sessions) {
        sessions = await officialLoader(scope, onProgress, ctl.signal);
        hasText = true;
        if (scope === "all" && dir && state.load === mine) {
          void saveIndexFromSessions(dir, sessions);
        }
      }
      if (state.load !== mine) throw new HostError(ERR.BUSY, "加载已被新的请求取代");
      state[scope] = sessions;
      state[`${scope}HasText`] = hasText;
      state.load = undefined;
      if (hasText) scheduleFullTextSweep();
      return sessions;
    })();
    mine.promise.catch(() => {}); // 没人等的加载被换掉不算未处理的拒绝
    state.load = mine;
    return mine;
  };

  // Strip heavy `allMessagesText` from cached sessions after 60 s of search idle so V8 heap
  // does not permanently hold full text of every session on disk (50–200+ MB).
  let fullTextSweepTimer;
  const scheduleFullTextSweep = () => {
    if (fullTextSweepTimer) clearTimeout(fullTextSweepTimer);
    fullTextSweepTimer = setTimeout(() => {
      fullTextSweepTimer = undefined;
      for (const sc of ["current", "all"]) {
        if (!state[`${sc}HasText`] || !Array.isArray(state[sc])) continue;
        state[sc] = state[sc].map((s) => (s && s.allMessagesText ? { ...s, allMessagesText: "" } : s));
        state[`${sc}HasText`] = false;
      }
    }, 60_000);
    fullTextSweepTimer.unref?.();
  };

  reg.add("session.list", async (args) => {
    const scope = optStr(args, "scope") ?? "current";
    const sortMode = optStr(args, "sortMode") ?? "threaded";
    const nameFilter = optStr(args, "nameFilter") ?? "all";
    const query = optStr(args, "query") ?? "";
    if (!SCOPES.has(scope)) throw bad(`scope 只能是 current / all：${scope}`);
    if (!SORT_MODES.has(sortMode)) throw bad(`sortMode 只能是 threaded / recent / relevance：${sortMode}`);
    if (!NAME_FILTERS.has(nameFilter)) throw bad(`nameFilter 只能是 all / named：${nameFilter}`);
    const needsSearch = query.trim().length > 0;
    const pathsMod = await officialPaths(controller);
    const o = needsSearch ? { ...pathsMod, ...(await officialSearch(controller)) } : pathsMod;
    syncKey();
    const view = { sortMode, nameFilter, query, groupByCwd: bool(args, "groupByCwd", false) };
    const pathKeyCache = new Map();
    const pathKey = makePathKeyMemo(o, pathKeyCache);
    const home = homedir();
    const render = (sessions) => {
      const curFile = controller.session?.sessionManager?.getSessionFile?.();
      const currentKey = curFile ? pathKey(curFile) : undefined;
      return viewOf(o, sessions, view, pathKey).map((n) => rowOf(o, n, currentKey, pathKey, home));
    };
    const answer = (sessions) => ({ scope, rows: render(sessions), total: sessions.length, queryError: needsSearch ? queryError(o, query) : null });
    const refresh = bool(args, "refresh", false);
    const hasCachedText = Boolean(state[`${scope}HasText`]);

    // 有缓存且不要求重读（若本次要全文搜索，还要求缓存已含全文）：立刻答。
    if (!refresh && state[scope] && (!needsSearch || hasCachedText)) return answer(state[scope]);

    for (;;) {
      let load = state.load?.scope === scope && (!needsSearch || state.load.fullText) ? state.load : undefined;
      // 要求重读或需要全文搜索而当前无全文加载：重起一次；复用在途加载时把进度渲染更新为当前最新查询。
      if (refresh || !load) {
        load = startLoad(scope, needsSearch, o, render);
      } else {
        load.render = render;
      }
      try {
        return answer(await load.promise);
      } catch (e) {
        // 视图请求等的加载被换掉了（重读 / 变更）：改等新的那个。
        if (!refresh && load.superseded && state.load) continue;
        if (e instanceof HostError) throw e;
        if (load.abort.signal.aborted) throw new HostError(ERR.BUSY, "加载已中止");
        throw e;
      }
    }
  });
}
