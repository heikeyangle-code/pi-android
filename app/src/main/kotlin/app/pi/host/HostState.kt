package app.pi.host

import app.pi.rpc.PiResponses
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * 宿主持有的会话状态快照（`state` 事件 / 变更命令应答里的 `state` / `session.snapshot`）的类型化视图。
 *
 * UI 的模型、思考等级、工具、用量、成本、队列这些字段**只从这里派生**，不自己攒、不来回问
 * （docs/sdk-host/README.md 原则 4）。字段没有就是 null / 默认值：宿主给快照加字段不升版本，
 * 所以这里只取认识的，其余留在 [raw]。
 */
class HostState(val raw: JsonObject) {
    val epoch: Long get() = raw.long("epoch") ?: 0L
    val seq: Long get() = raw.long("seq") ?: 0L
    val cwd: String? get() = raw.str("cwd")
    val sessionId: String? get() = raw.str("sessionId")
    val sessionFile: String? get() = raw.str("sessionFile")
    val sessionName: String? get() = raw.str("sessionName")
    val thinkingLevel: String? get() = raw.str("thinkingLevel")
    val isStreaming: Boolean get() = raw.bool("isStreaming") ?: false
    val isCompacting: Boolean get() = raw.bool("isCompacting") ?: false
    val steeringMode: String? get() = raw.str("steeringMode")
    val followUpMode: String? get() = raw.str("followUpMode")
    val autoCompactionEnabled: Boolean get() = raw.bool("autoCompactionEnabled") ?: false
    val pendingMessageCount: Int get() = raw.long("pendingMessageCount")?.toInt() ?: 0
    val extensionsBound: Boolean get() = (raw["extensions"] as? JsonObject)?.bool("bound") ?: false

    val modelProvider: String? get() = (raw["model"] as? JsonObject)?.str("provider")
    val modelId: String? get() = (raw["model"] as? JsonObject)?.str("id")
    val modelName: String? get() = (raw["model"] as? JsonObject)?.str("name")
    val modelReasoning: Boolean? get() = (raw["model"] as? JsonObject)?.bool("reasoning")

    val activeTools: List<String>
        get() = ((raw["tools"] as? JsonObject)?.get("active") as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.orEmpty()

    /** 上下文占用；宿主没给（模型未知等）为 null。 */
    val contextUsage: JsonObject? get() = raw["contextUsage"] as? JsonObject
    val tokens: JsonObject? get() = raw["tokens"] as? JsonObject
    val cost: JsonElement? get() = raw["cost"]
    val messageCounts: JsonObject? get() = raw["messageCounts"] as? JsonObject

    /**
     * 把宿主主动推来的状态快照直接投影成 [PiResponses.SessionStats]：
     * 上下文用量、累计 token、费用、消息数都已随每次 debounced `state` 事件推送，
     * 不再需要 UI 自己 `delay(700)` 或反复发 `session.stats` 重扫整份会话。
     */
    fun toSessionStats(previousToolResults: Int = 0): PiResponses.SessionStats {
        val tok = tokens?.let {
            PiResponses.TokenTotals(
                input = it.long("input") ?: 0L,
                output = it.long("output") ?: 0L,
                cacheRead = it.long("cacheRead") ?: 0L,
                cacheWrite = it.long("cacheWrite") ?: 0L,
                total = it.long("total") ?: 0L,
            )
        }
        val usage = contextUsage?.let {
            PiResponses.ContextUsage(
                tokens = it.long("tokens"),
                contextWindow = it.long("contextWindow"),
                percent = it.dbl("percent"),
            )
        }
        val counts = messageCounts
        val costNum = (cost as? JsonPrimitive)?.doubleOrNull
        return PiResponses.SessionStats(
            sessionFile = sessionFile,
            sessionId = sessionId,
            userMessages = counts?.long("user")?.toInt() ?: 0,
            assistantMessages = counts?.long("assistant")?.toInt() ?: 0,
            toolCalls = counts?.long("tools")?.toInt() ?: 0,
            toolResults = counts?.long("toolResults")?.toInt() ?: previousToolResults,
            totalMessages = counts?.long("total")?.toInt() ?: 0,
            tokens = tok,
            cost = costNum,
            contextUsage = usage,
        )
    }

    /** (epoch, seq) 的先后；用来在多个来源（事件 / 应答 / 对账）里取最新。 */
    internal fun isNewerOrEqual(other: HostState): Boolean =
        epoch > other.epoch || (epoch == other.epoch && seq >= other.seq)

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.bool(k: String): Boolean? = (this[k] as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.long(k: String): Long? = (this[k] as? JsonPrimitive)?.longOrNull
    private fun JsonObject.dbl(k: String): Double? = (this[k] as? JsonPrimitive)?.doubleOrNull
}

/**
 * 状态的唯一入口：三个来源（`state` 事件、命令应答里的 `state`、`session.snapshot`）都喂 [offer]，
 * 按 `(epoch, seq)` 取最新，旧的丢掉。这样一条晚到的应答不会把刚推来的新状态盖回去。
 */
class HostStateStore {
    private val _state = MutableStateFlow<HostState?>(null)
    val state: StateFlow<HostState?> = _state.asStateFlow()

    /** @return 是否采纳。 */
    fun offer(raw: JsonObject?): Boolean {
        if (raw == null) return false
        val incoming = HostState(raw)
        while (true) {
            val current = _state.value
            if (current != null && !incoming.isNewerOrEqual(current)) return false
            if (_state.compareAndSet(current, incoming)) return true
        }
    }

    /** `state` 事件的 data 是 `{reason, state}`。 */
    fun offerEvent(data: JsonElement?): Boolean = offer((data as? JsonObject)?.get("state") as? JsonObject)

    /** 变更命令的应答是 `{…, state}`。 */
    fun offerReply(reply: JsonObject): Boolean = offer(reply["state"] as? JsonObject)

    fun reset() { _state.value = null }
}
