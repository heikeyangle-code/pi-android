package app.pi.host

import app.pi.rpc.PiEvent
import app.pi.rpc.PiMessage
import app.pi.rpc.PiResponses
import app.pi.rpc.QueueMode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 命令失败。[command] 是宿主命令名，[reason] 是给人看的原因，[code] 是协议错误码（可能为 null）。 */
class HostCommandException(
    val command: String?,
    val reason: String,
    val code: String? = null,
) : Exception("${command ?: "host"}: $reason")

/**
 * 聊天界面用的「引擎 API」：一条宿主命令一个具名 suspend 函数，返回**已有的**结果类型
 * （`PiResponses.*`），所以 ViewModel 只换类型、不重写调用点。
 *
 * 数据形状不是靠猜的：宿主命令的返回与旧读取器期望的 `data` 形状逐条对过，
 * 见 `HostApiCheck`（回放真宿主录下的应答，让 `PiResponses` 的读取器去读）。
 * 对不上的两处在这里适配，且都在注释里写明：
 *  - `model.cycle` 的 `result` 对应旧 `data`；
 *  - `thinking.cycle` 的 `level` 对应旧 `data.level`（为 null = 只有一个等级）。
 *
 * 状态：`getState` 从宿主的 `state` 快照取，**不是**轮询 —— 宿主已经主动推（[HostCommands.stateStore]）。
 */
class HostEngineApi(private val commands: HostCommands) {

    // ---- 读 ----

    /** 现在的会话状态。其实是一次对账（`session.snapshot`），UI 平时应直接吃 `stateStore`。 */
    suspend fun getState(): PiResponses.SessionState {
        val snap = guarded("get_state") { commands.snapshot() }
        // 宿主快照用 messageCounts.total，旧读取器读 messageCount。
        val total = (snap["messageCounts"] as? JsonObject)?.get("total")
        val shaped = JsonObject(snap + ("messageCount" to (total ?: JsonPrimitive(0))))
        return required("get_state", PiResponses.sessionState(response("get_state", shaped)))
    }

    suspend fun getMessages(): List<PiMessage> = PiResponses.messages(read("session.messages"))

    suspend fun getEntries(since: String? = null): PiResponses.EntriesPage =
        required("get_entries", PiResponses.sessionEntries(read("session.entries", obj { since?.let { put("since", JsonPrimitive(it)) } })))

    /**
     * `get_entries` 的**原始**条目，给历史回放和分支过滤用。
     *
     * 那两处要的是 pi 自己那一层 JSON：`TranscriptReducer.seedFromHistory` 直接折
     * `JsonObject`，`activeBranch` 按 `id` / `parentId` 走树。类型化之后的
     * [PiResponses.EntriesPage] 只剩 `SessionEntry`，这一层字段就丢了 —— 所以同一份应答
     * 这里再取一次原始形状，而不是从类型化条目反序列化回去（那等于写第二个解析器）。
     */
    suspend fun getEntriesRaw(since: String? = null): RawEntriesPage {
        val data = guarded("get_entries") {
            commands.client.callObject("session.entries", obj { since?.let { put("since", JsonPrimitive(it)) } })
        }
        val entries = (data["entries"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
        val leafId = (data["leafId"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        return RawEntriesPage(entries, leafId)
    }

    suspend fun getTree(): PiResponses.TreePage = required("get_tree", PiResponses.tree(read("session.tree")))

    suspend fun getForkMessages(): List<PiResponses.ForkMessage> = PiResponses.forkMessages(read("session.forkMessages"))

    suspend fun getLastAssistantText(): String? = PiResponses.lastAssistantText(read("session.lastText"))

    suspend fun getSessionStats(): PiResponses.SessionStats =
        required("get_session_stats", PiResponses.sessionStats(read("session.stats")))

    suspend fun getAvailableModels(): List<PiResponses.ModelInfo> = PiResponses.availableModels(read("model.list"))

    suspend fun getAvailableThinkingLevels(): List<String> = PiResponses.thinkingLevels(read("thinking.levels"))

    suspend fun getCommands(): List<PiResponses.SlashCommand> = PiResponses.slashCommands(read("catalog.commands"))

    /** 命令面板的全部原料：扩展/模板/技能命令，加 pi 自己的内建命令表（一次请求）。 */
    suspend fun getCommandCatalog(): PiResponses.CommandCatalog = PiResponses.commandCatalog(read("catalog.commands"))

    // ---- 模型 / 思考 / 队列 ----

    suspend fun setModel(provider: String, modelId: String): PiResponses.ModelInfo {
        val r = write("model.set", obj { put("provider", JsonPrimitive(provider)); put("modelId", JsonPrimitive(modelId)) })
        // 宿主应答 {model, state}；旧读取器直接读 model 对象。
        return required("set_model", PiResponses.setModel(response("set_model", r.data["model"])))
    }

    /** 只有一个可选模型时为 null。 */
    suspend fun cycleModel(): PiResponses.CycleModelResult? {
        val r = write("model.cycle")
        val result = r.data["result"]
        if (result == null || result is JsonNull) return null
        return required("cycle_model", PiResponses.cycleModel(response("cycle_model", result)))
    }

    suspend fun setThinkingLevel(level: String) { write("thinking.set", obj { put("level", JsonPrimitive(level)) }) }

    suspend fun cycleThinkingLevel(): PiResponses.CycleThinkingResult? {
        val level = write("thinking.cycle").data["level"]
        if (level == null || level is JsonNull) return null
        return required("cycle_thinking_level", PiResponses.cycleThinkingLevel(response("cycle_thinking_level", obj { put("level", level) })))
    }

    suspend fun setSteeringMode(mode: QueueMode) { write("queue.steeringMode", obj { put("mode", JsonPrimitive(mode.wire)) }) }
    suspend fun setFollowUpMode(mode: QueueMode) { write("queue.followUpMode", obj { put("mode", JsonPrimitive(mode.wire)) }) }

    suspend fun clearQueue(): PiResponses.ClearQueueResult =
        required("clear_queue", PiResponses.clearQueueResult(write("session.clearQueue").asResponse("clear_queue")))

    // ---- 压缩 / 重试 ----

    suspend fun compact(customInstructions: String? = null): PiResponses.CompactionResult =
        required(
            "compact",
            PiResponses.compactionResult(
                write("session.compact", obj { customInstructions?.let { put("customInstructions", JsonPrimitive(it)) } }, HostCommands.LONG_TIMEOUT_MS)
                    .asResponse("compact"),
            ),
        )

    suspend fun setAutoCompaction(enabled: Boolean) { write("session.autoCompaction", obj { put("enabled", JsonPrimitive(enabled)) }) }
    suspend fun setAutoRetry(enabled: Boolean) { write("session.retry", obj { put("enabled", JsonPrimitive(enabled)) }) }
    suspend fun abortRetry() { write("session.retry", obj { put("abort", JsonPrimitive(true)) }) }

    // ---- bash / 中止 / 导出 ----

    suspend fun bash(command: String, excludeFromContext: Boolean? = null): PiResponses.BashResult =
        required(
            "bash",
            PiResponses.bashResult(
                write(
                    "session.bash",
                    obj {
                        put("command", JsonPrimitive(command))
                        excludeFromContext?.let { put("excludeFromContext", JsonPrimitive(it)) }
                    },
                    HostCommands.LONG_TIMEOUT_MS,
                ).asResponse("bash"),
            ),
        )

    suspend fun abortBash() { write("session.abortBash") }

    /** 中止当前这一轮。 */
    suspend fun abort() { write("session.abort") }

    /** @return 导出文件的路径（宿主按 `outputPath` 或默认命名）。 */
    suspend fun exportHtml(outputPath: String? = null): String =
        PiResponses.exportResult(read("session.export", obj { outputPath?.let { put("outputPath", JsonPrimitive(it)) } }, HostCommands.LONG_TIMEOUT_MS))
            ?.path ?: throw HostCommandException("export_html", "宿主没有返回导出路径")

    // ---- 会话 ----

    /** [cwd]（guest 路径）非空 = 在那个项目里开新会话，宿主不重启（SDK 的 cwd 是会话的属性）。 */
    suspend fun newSession(parentSession: String? = null, cwd: String? = null): PiResponses.CancelledResult =
        required("new_session", PiResponses.cancelledResult(write("session.new", obj {
            parentSession?.let { put("parentSession", JsonPrimitive(it)) }
            cwd?.let { put("cwd", JsonPrimitive(it)) }
        }, HostCommands.LONG_TIMEOUT_MS).asResponse("new_session")))

    suspend fun switchSession(sessionPath: String, cwd: String? = null): PiResponses.CancelledResult =
        required("switch_session", PiResponses.cancelledResult(write("session.switch", obj {
            put("sessionPath", JsonPrimitive(sessionPath))
            cwd?.let { put("cwd", JsonPrimitive(it)) }
        }, HostCommands.LONG_TIMEOUT_MS).asResponse("switch_session")))

    suspend fun fork(entryId: String): PiResponses.ForkResult =
        required("fork", PiResponses.forkResult(write("session.fork", obj { put("entryId", JsonPrimitive(entryId)) }, HostCommands.LONG_TIMEOUT_MS).asResponse("fork")))

    suspend fun cloneSession(): PiResponses.CancelledResult =
        required("clone", PiResponses.cancelledResult(write("session.clone", timeoutMs = HostCommands.LONG_TIMEOUT_MS).asResponse("clone")))

    suspend fun setSessionName(name: String) { write("session.name", obj { put("name", JsonPrimitive(name)) }) }

    // ---- 内部 ----

    private suspend fun read(cmd: String, args: JsonObject = EMPTY, timeoutMs: Long? = null): PiEvent.Response {
        val data = guarded(cmd) { commands.client.callObject(cmd, args, timeoutMs) }
        return response(cmd, data)
    }

    private suspend fun write(cmd: String, args: JsonObject = EMPTY, timeoutMs: Long? = null): HostReply =
        guarded(cmd) { commands.call(cmd, args, timeoutMs) }

    private fun HostReply.asResponse(cmd: String): PiEvent.Response = response(cmd, data)

    /** 旧读取器认的是 `PiEvent.Response`；宿主的 `res.data` 装进去即可。 */
    private fun response(cmd: String, data: JsonElement?): PiEvent.Response =
        PiEvent.Response(id = null, command = cmd, success = true, error = null, data = data)

    private fun <T : Any> required(cmd: String, value: T?): T =
        value ?: throw HostCommandException(cmd, "宿主的应答里没有可读的数据")

    private suspend fun <T> guarded(cmd: String, block: suspend () -> T): T = try {
        block()
    } catch (e: HostException) {
        throw HostCommandException(cmd, e.message ?: e.code, e.code)
    }

    companion object {
        private val EMPTY = JsonObject(emptyMap())

        /** 普通命令的时限。 */
        const val DEFAULT_TIMEOUT_MS = 120_000L

        /** 会跑 LLM 摘要、或可能卡在扩展对话框等用户回答的命令。 */
        const val SLOW_TIMEOUT_MS = 600_000L
    }
}

/** [HostEngineApi.getEntriesRaw] 的答案：pi 的原始条目加它的 `leafId`。 */
data class RawEntriesPage(val entries: List<JsonObject>, val leafId: String?)

private inline fun obj(build: MutableMap<String, JsonElement>.() -> Unit): JsonObject =
    JsonObject(LinkedHashMap<String, JsonElement>().apply(build))

