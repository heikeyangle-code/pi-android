package app.pi.host

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
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

    /** (epoch, seq) 的先后；用来在多个来源（事件 / 应答 / 对账）里取最新。 */
    internal fun isNewerOrEqual(other: HostState): Boolean =
        epoch > other.epoch || (epoch == other.epoch && seq >= other.seq)

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.bool(k: String): Boolean? = (this[k] as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.long(k: String): Long? = (this[k] as? JsonPrimitive)?.longOrNull
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
