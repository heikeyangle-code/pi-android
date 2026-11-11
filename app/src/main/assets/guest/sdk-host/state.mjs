/**
 * 宿主持有的状态快照，以及「变了就推」。
 *
 * 与 RPC 的分界线：RPC 让 App 反复 `get_xxx` 自己拼视图；这里宿主拼好**一份完整视图**，
 * 变了就推 `state` 事件，变更类命令的应答里也带最新 `state`，App 的 UI 直接吃它，
 * 不自己攒字段、不来回问。（这条思路来自分支上的另一次评审：「宿主只是把命令 1:1 换个信封转发，
 * 就是换了个名字的 RPC」——是对的。）
 *
 * 快照只放「会随会话变化的小数据」；消息历史不在其中（`session.messages` / `messages:true` 才给），
 * 因为它大、而且 App 靠 `agent` 事件流增量维护。
 */

/** 会改变快照的 agent 事件；其余事件只转发，不触发推送。 */
export const STATE_EVENTS = new Set([
  "agent_start", "agent_end", "agent_settled", "turn_end", "message_end", "tool_execution_end",
  "model_change", "thinking_level_change", "session_compact", "session_info_changed", "queue_update",
  "compaction_end", "auto_retry_end",
]);

const PUSH_DEBOUNCE_MS = 100;

export function snapshotOf(controller, { messages = false } = {}) {
  const s = controller.requireSession();
  const stats = s.getSessionStats();
  return {
    epoch: controller.hub.epoch,
    seq: controller.hub.seq,
    cwd: controller.cfg.cwd,
    agentDir: controller.cfg.agentDir,
    sessionId: s.sessionId,
    sessionFile: s.sessionFile ?? null,
    sessionName: s.sessionName ?? null,
    model: s.model ? { provider: s.model.provider, id: s.model.id, name: s.model.name ?? null, reasoning: s.model.reasoning ?? null } : null,
    thinkingLevel: s.thinkingLevel,
    // 虚拟模型下，最近一次成功响应实际落到的物理模型（没有虚拟选择时为 null）。
    routedModel: s.routedModel ? { provider: s.routedModel.model.provider, id: s.routedModel.model.id, thinkingLevel: s.routedModel.thinkingLevel ?? null } : null,
    scopedModelCount: s.scopedModels.length,
    isStreaming: s.isStreaming,
    isCompacting: s.isCompacting,
    steeringMode: s.steeringMode,
    followUpMode: s.followUpMode,
    autoCompactionEnabled: s.autoCompactionEnabled,
    pendingMessageCount: s.pendingMessageCount,
    tools: { active: s.getActiveToolNames() },
    contextUsage: s.getContextUsage?.() ?? null,
    tokens: stats.tokens,
    cost: stats.cost,
    messageCounts: { user: stats.userMessages, assistant: stats.assistantMessages, tools: stats.toolCalls, total: stats.totalMessages },
    extensions: { bound: controller.extensionsBound },
    ...(messages ? { messages: s.messages } : {}),
  };
}

/** 变更类命令的应答：原有返回值 + 最新状态，App 不必再追问一次。 */
export function withState(controller, data) {
  return { ...(data && typeof data === "object" ? data : {}), state: snapshotOf(controller) };
}

/** 合并一批事件，100 ms 推一次，避免每个 token 都推。 */
export function createStatePusher(controller) {
  let timer = null;
  return function pushState(reason) {
    if (timer || !controller.session) return;
    timer = setTimeout(() => {
      timer = null;
      if (!controller.session) return;
      try {
        controller.hub.emit("state", { reason, state: snapshotOf(controller) });
      } catch (error) {
        controller.hub.emit("host.log", { level: "warn", message: `state push failed: ${error?.message ?? error}` });
      }
    }, PUSH_DEBOUNCE_MS);
  };
}
