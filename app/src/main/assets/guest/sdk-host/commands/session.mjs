/**
 * 会话命令：对话控制、会话树、会话替换、维护操作。
 * 语义逐条对照 pi 的 `modes/rpc/rpc-mode.js`（1.5.0），差异处有注释。
 */

import { existsSync, statSync, unlinkSync } from "node:fs";
import { randomUUID } from "node:crypto";
import { join, resolve } from "node:path";
import { DEFERRED } from "./registry.mjs";
import { ERR, HostError, bad, bool, optStr, str } from "../protocol.mjs";
import { snapshotOf, withState } from "../state.mjs";
import { listInfos, makeListProgress } from "./library.mjs";

function rethrowSessionCwdError(error) {
  if (error?.name === "MissingSessionCwdError") {
    throw new HostError(ERR.CWD_MISSING, String(error.message ?? error), { issue: error.issue ?? null });
  }
  throw error;
}

function checkedCwd(cwd) {
  const abs = resolve(cwd);
  if (!existsSync(abs) || !statSync(abs).isDirectory()) throw bad(`工作目录不存在：${cwd}`);
  return abs;
}

/**
 * 在会话目录里给一个**还不存在**的新会话文件挑个路径（平铺目录，文件名规则同 SDK：`<ISO>_<id>.jsonl`）。
 * 不写任何东西：`SessionManager.open(不存在的路径, dir, cwdOverride)` 会按 `--session <path>` 的语义开一个
 * 新会话（头里的 cwd = cwdOverride），**直到第一条 assistant 消息才落盘**（`_persist` 的 `flushed` 规则）——
 * 和 pi 自己的新会话一样，用户没发过话就不会留下文件。
 */
function plannedSessionFile(controller) {
  const dir = controller.sessionDir;
  const timestamp = new Date().toISOString().replace(/[:.]/g, "-");
  return join(dir, `${timestamp}_${randomUUID()}.jsonl`);
}

export function register(reg, { controller }) {
  // 应答附 state：打开后状态仓立刻有值，不用等下一条 state 事件。
  reg.add("session.open", async (args) => withState(controller, await controller.open(args)));
  reg.add("session.snapshot", (args) => snapshotOf(controller, { messages: bool(args, "messages", false) }));

  // ---- 对话 ---------------------------------------------------------------
  // prompt 的两阶段应答：预检通过（已开始 / 已排队 / 已被扩展消费）就应答；
  // 预检失败走 reject；整轮跑完之后的失败不再有「应答」可发，作为 `agent.error` 事件报告。
  reg.add("session.prompt", (args, call) => {
    const s = controller.requireSession();
    const message = str(args, "message");
    const reply = call.defer();
    let preflighted = false;
    s.prompt(message, {
      images: args.images,
      streamingBehavior: args.streamingBehavior,
      source: "interactive",
      preflightResult: (disposition) => {
        preflighted = true;
        reply.ok(withState(controller, { disposition }));
      },
    }).catch((error) => {
      if (!preflighted) reply.fail(new HostError(ERR.REJECTED, String(error?.message ?? error)));
      else controller.hub.emit("agent.error", { message: String(error?.message ?? error) });
    });
    return DEFERRED;
  });
  reg.add("session.steer", async (args) =>
    withState(controller, { disposition: await controller.requireSession().steer(str(args, "message"), args.images, { source: "interactive" }) }),
  );
  reg.add("session.followUp", async (args) =>
    withState(controller, { disposition: await controller.requireSession().followUp(str(args, "message"), args.images, { source: "interactive" }) }),
  );
  reg.add("session.abort", async () => {
    await controller.requireSession().abort();
    return withState(controller);
  });
  reg.add("session.clearQueue", () => withState(controller, controller.requireSession().clearQueue()));

  // ---- 粗粒度：App 说「发一条 / 停下」，宿主决定是新开一轮还是排队 ------------------
  // 流式中：按 `behavior`（steer | followUp，默认 steer）排队；空闲：开新一轮。
  // 应答规则与 session.prompt 相同（预检通过即回），并带最新状态。App 的发送按钮只需要这一条。
  reg.add("chat.send", (args, call) => {
    const s = controller.requireSession();
    const message = str(args, "message");
    const behavior = optStr(args, "behavior") ?? "steer";
    if (behavior !== "steer" && behavior !== "followUp") throw bad("behavior 必须是 steer 或 followUp");
    const reply = call.defer();
    let preflighted = false;
    s.prompt(message, {
      images: args.images,
      streamingBehavior: behavior,
      source: "interactive",
      preflightResult: (disposition) => {
        preflighted = true;
        reply.ok(withState(controller, { disposition }));
      },
    }).catch((error) => {
      if (!preflighted) reply.fail(new HostError(ERR.REJECTED, String(error?.message ?? error)));
      else controller.hub.emit("agent.error", { message: String(error?.message ?? error) });
    });
    return DEFERRED;
  });
  reg.add("chat.stop", async (args) => {
    const s = controller.requireSession();
    if (bool(args, "clearQueue", false)) s.clearQueue();
    await s.abort();
    return withState(controller);
  });

  // ---- 会话替换（运行时会回调 rebind，epoch 自动 +1）---------------------
  reg.add("session.new", async (args) => {
    const options = optStr(args, "parentSession") ? { parentSession: args.parentSession } : undefined;
    const cwd = optStr(args, "cwd");
    if (cwd && resolve(cwd) !== resolve(controller.cwd)) {
      // SDK 的 `runtime.newSession` 只在当前 cwd 里开。别的项目里开新会话用它公开的另一条路：
      // `switchSession(路径, { cwdOverride })` —— 路径指向一个还不存在的文件，SessionManager 就按
      // `--session <path>` 的语义开新会话，cwd 记成 cwdOverride，且**不预先写文件**（见 plannedSessionFile）。
      if (options) throw bad("跨项目新建会话不支持 parentSession（SDK 的 switchSession 没有这个参数）");
      const target = checkedCwd(cwd);
      return withState(controller, await controller.runtime.switchSession(plannedSessionFile(controller), { cwdOverride: target }));
    }
    return withState(controller, await controller.runtime.newSession(options));
  });
  reg.add("session.switch", async (args) => {
    const cwd = optStr(args, "cwd");
    const options = cwd ? { cwdOverride: checkedCwd(cwd) } : undefined;
    try {
      return withState(controller, await controller.runtime.switchSession(str(args, "sessionPath"), options));
    } catch (error) {
      rethrowSessionCwdError(error);
    }
  });
  reg.add("session.forkFrom", async (args) => {
    const sdk = controller.sdk;
    if (!sdk) throw new HostError(ERR.NOT_OPEN, "先 session.open");
    const sourcePath = str(args, "sourcePath");
    const targetCwd = checkedCwd(optStr(args, "cwd") ?? controller.cwd);
    const forked = sdk.SessionManager.forkFrom(sourcePath, targetCwd, controller.sessionDir);
    const forkFile = forked.getSessionFile();
    if (!forkFile) throw new HostError(ERR.INTERNAL, "未能生成分支会话文件");
    const result = await controller.runtime.switchSession(forkFile);
    if (result.cancelled) {
      try {
        unlinkSync(forkFile);
      } catch {
        // ignore cleanup error if already absent
      }
    }
    return withState(controller, {
      cancelled: result.cancelled === true,
      sessionFile: result.cancelled ? null : forkFile,
    });
  });
  reg.add("session.fork", async (args) => {
    const result = await controller.runtime.fork(str(args, "entryId"));
    return withState(controller, { text: result.selectedText, cancelled: result.cancelled });
  });
  reg.add("session.clone", async () => {
    const leafId = controller.requireSession().sessionManager.getLeafId();
    if (!leafId) throw new HostError(ERR.BUSY, "没有当前条目，无法克隆");
    const result = await controller.runtime.fork(leafId, { position: "at" });
    return withState(controller, { cancelled: result.cancelled });
  });

  // ---- 读 -----------------------------------------------------------------
  reg.add("session.messages", () => ({ messages: controller.requireSession().messages }));
  reg.add("session.entries", (args) => {
    const sm = controller.requireSession().sessionManager;
    let entries = sm.getEntries();
    const since = optStr(args, "since");
    if (since !== undefined) {
      const index = entries.findIndex((e) => e.id === since);
      if (index === -1) throw new HostError(ERR.NOT_FOUND, `条目不存在：${since}`);
      entries = entries.slice(index + 1);
    }
    return { entries, leafId: sm.getLeafId() };
  });
  reg.add("session.tree", () => {
    const sm = controller.requireSession().sessionManager;
    return { tree: sm.getTree(), leafId: sm.getLeafId() };
  });
  reg.add("session.stats", () => controller.requireSession().getSessionStats());
  reg.add("session.lastText", () => ({ text: controller.requireSession().getLastAssistantText() ?? null }));
  reg.add("session.forkMessages", () => ({ messages: controller.requireSession().getUserMessagesForForking() }));
  reg.add("session.list", async (args) => {
    // 列会话：直接用 SessionManager，不再让 App 去读目录。
    const sdk = controller.sdk;
    if (!sdk) throw new HostError(ERR.NOT_OPEN, "先 session.open");
    const opId = optStr(args, "opId");
    const infos = await listInfos(sdk, controller, bool(args, "all", false), makeListProgress(controller, opId));
    // 默认不带 allMessagesText（整段对话文本，列表行用不上；全文搜索走 session.search）。
    return { sessions: bool(args, "text", false) ? infos : infos.map(({ allMessagesText, ...rest }) => rest) };
  });

  // ---- 写 -----------------------------------------------------------------
  // 会话树导航（`/tree`）：RPC 没有这条，旧 App 靠 pi-android-bridge 扩展里的命令绕；SDK 里是直接方法。
  reg.add("session.navigate", async (args) => {
    const targetId = str(args, "targetId");
    const result = await controller.requireSession().navigateTree(targetId, {
      summarize: args.summarize,
      customInstructions: args.customInstructions,
      replaceInstructions: args.replaceInstructions,
      label: args.label,
    });
    // 与 SDK 的结果字段一一对应：aborted = 摘要被用户中止（TUI 此时回到树、保持选中）；
    // cancelled 但没 aborted = 扩展的 session_before_tree 取消了。
    return withState(controller, {
      cancelled: result.cancelled === true,
      ...(result.aborted === true ? { aborted: true } : {}),
      ...(result.editorText !== undefined ? { editorText: result.editorText } : {}),
    });
  });
  reg.add("session.import", async (args) => {
    try {
      return withState(controller, await controller.runtime.importFromJsonl(str(args, "path"), optStr(args, "cwd")));
    } catch (error) {
      rethrowSessionCwdError(error);
    }
  });

  reg.add("session.name", (args) => {
    const name = str(args, "name").trim();
    if (!name) throw bad("会话名不能为空");
    controller.requireSession().setSessionName(name);
    return withState(controller);
  });
  reg.add("session.compact", async (args) => withState(controller, await controller.requireSession().compact(optStr(args, "customInstructions"))));
  reg.add("session.export", async (args) => ({ path: await controller.requireSession().exportToHtml(optStr(args, "outputPath")) }));
  reg.add("session.reload", async () => {
    await controller.requireSession().reload();
    return withState(controller);
  });

  // ---- 重试 / 压缩开关 -----------------------------------------------------
  reg.add("session.retry", (args) => {
    const s = controller.requireSession();
    if (args.enabled !== undefined) s.setAutoRetryEnabled(bool(args, "enabled"));
    if (args.abort === true) s.abortRetry();
    return withState(controller);
  });
  reg.add("session.autoCompaction", (args) => {
    controller.requireSession().setAutoCompactionEnabled(bool(args, "enabled"));
    return withState(controller);
  });

  // ---- 用户 bash（`!cmd`）--------------------------------------------------
  reg.add("session.bash", async (args, call) => {
    const s = controller.requireSession();
    const command = str(args, "command");
    const excludeFromContext = bool(args, "excludeFromContext", false);
    const event = await s.extensionRunner.emitUserBash({
      type: "user_bash",
      command,
      excludeFromContext,
      cwd: s.sessionManager.getCwd(),
    });
    if (event?.result) {
      s.recordBashResult(command, event.result, { excludeFromContext });
      return event.result;
    }
    return s.executeBash(command, undefined, { excludeFromContext, id: call.id, operations: event?.operations });
  });
  reg.add("session.abortBash", () => {
    controller.requireSession().abortBash();
  });

  // ---- 项目信任 -------------------------------------------------------------
  // `remember`：folder（默认）/ parent / session / reset，含义见 `controller.setProjectTrust`。
  reg.add("project.trust", (args) => controller.setProjectTrust(bool(args, "trusted"), optStr(args, "remember") ?? "folder"));
  reg.add("project.trustState", () => controller.trustState());
  reg.add("project.trustOf", (args) => ({ trust: controller.trustOf(args?.cwds) }));
}
