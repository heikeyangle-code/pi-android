/**
 * AgentSession 上宿主此前**没接**的那批能力（对照 `agent-session.d.ts` 逐项审计的结果，见
 * docs/sdk-host/coverage-audit.md）。每条都是对同名 SDK 方法的薄适配，不重写语义：
 *
 *   models.scoped / models.setScoped   作用域模型（`--models`：循环切换只在这几个里转）
 *   cache.status / cache.setMode       提示缓存预热：模式、当前状态
 *   session.abortCompaction            中止正在进行的压缩
 *   session.abortBranchSummary         中止正在生成的分支摘要
 *   session.systemPrompt               当前系统提示词（只读查看）
 *   session.exportJsonl                把当前分支导出成 JSONL
 *   session.bugReport / .bugReportCancel  让当前模型描述「这个会话哪里不对」（用户不想交出转录时用）
 *   tools.callable                     模型此刻真能调用的工具名（与 active 不同：受扩展与权限影响）
 *   queue.messages                     待发的 steer / followUp 文本
 */

import { resolve, sep } from "node:path";
import { ERR, HostError, bad, optStr, str } from "../protocol.mjs";
import { withState } from "../state.mjs";

/** `settings-schema` 里 `cacheWarming` 的三个字面量。 */
const CACHE_MODES = ["off", "streaming", "idle"];

const wireModel = (m) => (m ? { provider: m.provider, id: m.id, name: m.name ?? null, reasoning: m.reasoning ?? null } : null);

export function register(reg, { controller }) {
  const session = () => controller.requireSession();
  const settings = () => controller.runtime.services.settingsManager;
  let bugReportAbort = null;

  reg.add("models.scoped", () => ({
    models: session().scopedModels.map((s) => ({ ...wireModel(s.model), thinkingLevel: s.thinkingLevel ?? null })),
  }));

  reg.add("models.setScoped", (args) => {
    const s = session();
    if (!Array.isArray(args.models)) throw bad("models 必须是数组（空数组 = 取消作用域，循环全部可用模型）");
    const runtime = s.modelRuntime;
    const resolved = args.models.map((m, i) => {
      const provider = typeof m?.provider === "string" ? m.provider : undefined;
      const id = typeof m?.id === "string" ? m.id : undefined;
      if (!provider || !id) throw bad(`models[${i}] 需要 provider 与 id`);
      const model = runtime.getModel(provider, id);
      if (!model) throw new HostError(ERR.NOT_FOUND, `没有这个模型：${provider}/${id}`);
      return { model, ...(typeof m.thinkingLevel === "string" ? { thinkingLevel: m.thinkingLevel } : {}) };
    });
    s.setScopedModels(resolved);
    return withState(controller, { count: resolved.length });
  });

  reg.add("cache.status", () => {
    const s = session();
    return { mode: settings().getCacheWarmingMode(), status: s.cacheWarmingStatus ?? null };
  });

  reg.add("cache.setMode", (args) => {
    const s = session();
    const mode = str(args, "mode");
    // SDK 不校验、直接写进 settings.json；非法值落盘后每次启动都会被 settings 校验报警，所以宿主先拦。
    if (!CACHE_MODES.includes(mode)) throw bad(`mode 只能是 ${CACHE_MODES.join(" / ")}：${mode}`);
    try {
      s.setCacheWarmingMode(mode);
    } catch (error) {
      throw new HostError(ERR.REJECTED, String(error?.message ?? error));
    }
    return withState(controller, { mode: settings().getCacheWarmingMode(), status: s.cacheWarmingStatus ?? null });
  });

  reg.add("session.abortCompaction", () => {
    const s = session();
    const was = s.isCompacting;
    s.abortCompaction();
    return { wasCompacting: was };
  });

  reg.add("session.abortBranchSummary", () => {
    session().abortBranchSummary();
    return {};
  });

  reg.add("session.systemPrompt", () => ({ text: session().systemPrompt }));

  reg.add("session.exportJsonl", (args) => {
    const s = session();
    const out = optStr(args, "path");
    if (out) {
      // 与 session.read 同一条规矩：宿主不是通用文件写口。只许写进工作目录或会话目录之内。
      const abs = resolve(out);
      const roots = [controller.cfg.cwd, controller.sessionDir].filter(Boolean).map((r) => resolve(r));
      if (!roots.some((r) => abs === r || abs.startsWith(r + sep))) throw bad(`导出路径必须在工作目录或会话目录之内：${out}`);
    }
    return { path: s.exportToJsonl(out) };
  });

  reg.add("session.bugReport", async (args) => {
    const s = session();
    if (bugReportAbort) throw new HostError(ERR.BUSY, "已经有一份问题报告在生成");
    bugReportAbort = new AbortController();
    try {
      return { text: await s.summarizeForBugReport({ hint: optStr(args, "hint"), signal: bugReportAbort.signal }) };
    } catch (error) {
      if (bugReportAbort?.signal.aborted) throw new HostError(ERR.CANCELLED ?? ERR.REJECTED, "已取消");
      throw new HostError(ERR.REJECTED, String(error?.message ?? error));
    } finally {
      bugReportAbort = null;
    }
  });

  reg.add("session.bugReportCancel", () => {
    const was = !!bugReportAbort;
    bugReportAbort?.abort();
    return { wasRunning: was };
  });

  reg.add("tools.callable", () => ({ names: session().getCallableToolNames() }));

  reg.add("queue.messages", () => {
    const s = session();
    return { steering: [...s.getSteeringMessages()], followUp: [...s.getFollowUpMessages()] };
  });
}
