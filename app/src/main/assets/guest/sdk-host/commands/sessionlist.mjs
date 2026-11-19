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
 *                               recent / relevance 排序 —— **直接 import 官方文件**，不重写
 *   interactive-mode.js         `showSessionSelector`：current = `SessionManager.list(cwd, dir)`，
 *                               all = `listAll(dir)`（自定义会话目录时）
 *
 * `buildSessionTree` / `flattenSessionTree` 在官方文件里没有导出（模块私有），所以按源码移植到这里，
 * 并由 `tools/sdk-host/test.mjs` 用**官方 `SessionSelectorComponent` 真渲染出的行序与前缀**逐行比对
 * （「会话列表对照官方组件」一节）；SDK 升级让规则变了，那一节先红。
 *
 * 命令：
 *   session.list         {scope, sortMode, nameFilter, query, refresh}  → {scope, rows, total, loading:false}
 *                        没有缓存或 `refresh:true` 时读磁盘，读的过程中发 `sessions.progress` 事件（带部分行）；
 *                        否则只用缓存重算视图（切排序 / 过滤 / 搜索即时）。
 *   session.list.cancel  中止在途的加载（对应 `cancelLoads`）
 *
 * 状态：缓存与在途加载挂在 controller 上，按会话目录 + cwd 区分，`session.open` 换了目录就作废。
 */

import { pathToFileURL } from "node:url";
import { join } from "node:path";
import { homedir } from "node:os";
import { ERR, HostError, bad, bool, optStr } from "../protocol.mjs";

const SORT_MODES = new Set(["threaded", "recent", "relevance"]);
const NAME_FILTERS = new Set(["all", "named"]);
const SCOPES = new Set(["current", "all"]);

let officialCache = null;
/** 官方的搜索 / 路径工具：按文件 URL 加载（它们不在包的公开导出里；catalog.mjs 加载 slash-commands 同理）。 */
async function official(controller) {
  if (officialCache) return officialCache;
  const base = join(controller.cfg.piDir, "dist");
  const search = await import(pathToFileURL(join(base, "modes", "interactive", "components", "session-selector-search.js")).href);
  const paths = await import(pathToFileURL(join(base, "utils", "paths.js")).href);
  officialCache = { filterAndSortSessions: search.filterAndSortSessions, hasSessionName: search.hasSessionName, parseSearchQuery: search.parseSearchQuery, ...paths };
  return officialCache;
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

/**
 * `SessionList.filterSessions`：threaded 且无查询 → 树；其余 → `filterAndSortSessions` 的平铺。
 * name 过滤先于一切（官方先 `hasSessionName` 过滤，再把 "all" 传给 filterAndSortSessions）。
 */
export function viewOf(o, sessions, { sortMode, nameFilter, query }) {
  const nameFiltered = nameFilter === "all" ? sessions : sessions.filter((s) => o.hasSessionName(s));
  if (sortMode === "threaded" && !query.trim()) {
    const key = (p) => o.getPathComparisonKey(o.canonicalizePath(p));
    return flattenSessionTree(buildSessionTree(nameFiltered, key));
  }
  return o.filterAndSortSessions(nameFiltered, query, sortMode, "all").map((session) => ({ session, depth: 0, isLast: true, ancestorContinues: [] }));
}

/** 列表行上线：去掉整段对话文本；多带「~ 缩写」后的 cwd / path（官方 `shortenHomePath`，家目录取宿主进程的）。 */
function rowOf(o, node, currentKey) {
  const s = node.session;
  const key = o.getPathComparisonKey(o.canonicalizePath(s.path));
  return {
    path: s.path,
    id: s.id,
    cwd: s.cwd,
    cwdShort: s.cwd ? o.shortenHomePath(s.cwd, homedir()) : "",
    pathShort: o.shortenHomePath(s.path, homedir()),
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
  /** @type {{key:string, current?:any[], all?:any[], load?:{scope:string, abort:AbortController}}} */
  const state = { key: "" };
  controller.sessionListState = state;

  const sdk = () => {
    if (!controller.sdk) throw new HostError(ERR.NOT_OPEN, "先 session.open");
    return controller.sdk;
  };
  const dirOf = () => controller.sessionDir ?? controller.cfg.sessionDir;
  /** 缓存的有效范围：会话目录 + cwd（cwd 变了「当前文件夹」就是另一批）。 */
  const keyOf = () => `${dirOf()}\n${controller.cwd}`;
  const syncKey = () => {
    const k = keyOf();
    if (state.key !== k) {
      state.load?.abort.abort();
      state.key = k;
      state.current = undefined;
      state.all = undefined;
      state.load = undefined;
    }
  };

  /** 变更（改名 / 删除 / 新会话落盘）之后：缓存作废，下一次 session.list 重读。官方 `refreshSessionsAfterMutation`。 */
  controller.invalidateSessionList = () => {
    state.load?.abort.abort();
    state.load = undefined;
    state.current = undefined;
    state.all = undefined;
  };

  const loader = (scope, onProgress, signal) => {
    const s = sdk();
    return scope === "current" ? s.SessionManager.list(controller.cwd, dirOf(), onProgress, signal) : s.SessionManager.listAll(dirOf(), onProgress, signal);
  };

  const currentKeyOf = (o) => {
    const f = controller.session?.sessionManager?.getSessionFile?.();
    return f ? o.getPathComparisonKey(o.canonicalizePath(f)) : undefined;
  };

  reg.add("session.list.cancel", () => {
    const had = Boolean(state.load);
    state.load?.abort.abort();
    state.load = undefined;
    return { cancelled: had };
  });

  reg.add("session.list", async (args) => {
    const scope = optStr(args, "scope") ?? "current";
    const sortMode = optStr(args, "sortMode") ?? "threaded";
    const nameFilter = optStr(args, "nameFilter") ?? "all";
    const query = optStr(args, "query") ?? "";
    if (!SCOPES.has(scope)) throw bad(`scope 只能是 current / all：${scope}`);
    if (!SORT_MODES.has(sortMode)) throw bad(`sortMode 只能是 threaded / recent / relevance：${sortMode}`);
    if (!NAME_FILTERS.has(nameFilter)) throw bad(`nameFilter 只能是 all / named：${nameFilter}`);
    const o = await official(controller);
    syncKey();
    const view = { sortMode, nameFilter, query };
    const render = (sessions) => viewOf(o, sessions, view).map((n) => rowOf(o, n, currentKeyOf(o)));

    if (bool(args, "refresh", false)) {
      state[scope] = undefined;
    }
    if (state[scope]) {
      const rows = render(state[scope]);
      return { scope, rows, total: state[scope].length, queryError: queryError(o, query) };
    }

    // 只允许一个加载在途；换范围 / 重读就中止旧的（官方 `cancelLoads` 与 `loadScope` 的 isActive 守卫）。
    state.load?.abort.abort();
    const ctl = new AbortController();
    const mine = { scope, abort: ctl };
    state.load = mine;
    const onProgress = (loaded, total, partial) => {
      if (state.load !== mine) return;
      controller.hub.emit("sessions.progress", { scope, loaded, total, rows: partial ? render(partial) : undefined });
    };
    try {
      const sessions = await loader(scope, onProgress, ctl.signal);
      if (state.load !== mine) throw new HostError(ERR.BUSY, "加载已被新的请求取代");
      state[scope] = sessions;
      state.load = undefined;
      return { scope, rows: render(sessions), total: sessions.length, queryError: queryError(o, query) };
    } catch (e) {
      if (state.load === mine) state.load = undefined;
      if (e instanceof HostError) throw e;
      if (ctl.signal.aborted) throw new HostError(ERR.BUSY, "加载已中止");
      throw e;
    }
  });
}
