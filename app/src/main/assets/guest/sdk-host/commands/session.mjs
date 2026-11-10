/**
 * 会话命令：对话控制、会话树、会话替换、维护操作。
 * 语义逐条对照 pi 的 `modes/rpc/rpc-mode.js`（1.5.0），差异处有注释。
 */

import { DEFERRED } from "./registry.mjs";
import { ERR, HostError, bad, bool, optStr, str } from "../protocol.mjs";
import { snapshotOf, withState } from "../state.mjs";

export function register(reg, { controller }) {
  reg.add("session.open", (args) => controller.open(args));
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
      source: "rpc",
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
    withState(controller, { disposition: await controller.requireSession().steer(str(args, "message"), args.images, { source: "rpc" }) }),
  );
  reg.add("session.followUp", async (args) =>
    withState(controller, { disposition: await controller.requireSession().followUp(str(args, "message"), args.images, { source: "rpc" }) }),
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
      source: "rpc",
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
    return withState(controller, await controller.runtime.newSession(options));
  });
  reg.add("session.switch", async (args) => withState(controller, await controller.runtime.switchSession(str(args, "sessionPath"))));
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
    const all = bool(args, "all", false);
    const infos = all
      ? await sdk.SessionManager.listAll(controller.cfg.sessionDir)
      : await sdk.SessionManager.list(controller.cfg.cwd, controller.cfg.sessionDir);
    return { sessions: infos };
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
    return withState(controller, { cancelled: result.cancelled === true, ...(result.editorText !== undefined ? { editorText: result.editorText } : {}) });
  });
  reg.add("session.import", async (args) => controller.runtime.importFromJsonl(str(args, "path"), optStr(args, "cwd")));

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
  reg.add("project.trust", (args) => controller.setProjectTrust(bool(args, "trusted")));
}
