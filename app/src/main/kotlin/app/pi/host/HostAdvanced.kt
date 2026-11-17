package app.pi.host

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 宿主上「AgentSession / 扩展 / models.json」这一批高级命令的**类型化**入口（`commands/agent.mjs`、
 * `catalog.mjs`、`modelconfig.mjs`）。界面只用这里的方法和数据类，不碰 JSON。
 *
 * 失败一律是 [app.pi.host.HostException]（宿主的 `code` + 原话）；这里不吞、不改写 ——
 * 尤其 [setProvider]：校验错误是 SDK 的原话（精确到字段），界面应当原样显示。
 */
class HostAdvanced(private val commands: HostCommands) {

    // ---- 作用域模型（循环切换只在这几个里转） ----
    data class ModelRef(val provider: String, val id: String, val name: String? = null, val thinkingLevel: String? = null)

    suspend fun scopedModels(): List<ModelRef> =
        rows(commands.call("models.scoped").data["models"]).map(::modelRef)

    /** 空列表 = 取消作用域。 */
    suspend fun setScopedModels(models: List<ModelRef>): Int {
        val arr = JsonArray(models.map {
            JsonObject(buildMap {
                put("provider", JsonPrimitive(it.provider)); put("id", JsonPrimitive(it.id))
                it.thinkingLevel?.let { l -> put("thinkingLevel", JsonPrimitive(l)) }
            })
        })
        return int(commands.call("models.setScoped", JsonObject(mapOf("models" to arr))).data["count"]) ?: 0
    }

    // ---- 缓存预热 ----
    data class CacheStatus(val mode: String, val state: String?, val reason: String?, val nextWarmAt: Long?)

    suspend fun cacheStatus(): CacheStatus = cacheOf(commands.call("cache.status").data)
    suspend fun setCacheMode(mode: String): CacheStatus = cacheOf(commands.call("cache.setMode", JsonObject(mapOf("mode" to JsonPrimitive(mode)))).data)

    // ---- 中止 / 查看 / 导出 / 报告 ----
    suspend fun abortCompaction(): Boolean = bool(commands.call("session.abortCompaction").data["wasCompacting"])
    suspend fun abortBranchSummary() { commands.call("session.abortBranchSummary") }
    suspend fun systemPrompt(): String = str(commands.call("session.systemPrompt").data["text"]).orEmpty()
    suspend fun exportJsonl(path: String? = null): String =
        str(commands.call("session.exportJsonl", JsonObject(path?.let { mapOf("path" to JsonPrimitive(it)) } ?: emptyMap())).data["path"].orEmptyElement()).orEmpty()
    suspend fun bugReport(hint: String? = null): String =
        str(commands.call("session.bugReport", JsonObject(hint?.let { mapOf("hint" to JsonPrimitive(it)) } ?: emptyMap()), LONG_MS).data["text"]).orEmpty()
    suspend fun cancelBugReport(): Boolean = bool(commands.call("session.bugReportCancel").data["wasRunning"])

    // ---- 待发队列内容 ----
    data class QueueMessages(val steering: List<String>, val followUp: List<String>)
    suspend fun queueMessages(): QueueMessages {
        val d = commands.call("queue.messages").data
        return QueueMessages(strings(d["steering"]), strings(d["followUp"]))
    }

    // ---- 工具 ----
    data class ToolDetail(
        val name: String,
        val description: String,
        val active: Boolean,
        val callable: Boolean,
        val exposure: String?,
        val source: String?,
        /** JSON Schema 原样（界面「工具详情」展示用）。 */
        val parameters: JsonElement?,
    )

    suspend fun tools(): List<ToolDetail> {
        val d = commands.call("catalog.tools").data
        val active = strings(d["active"]).toSet()
        val callable = strings(d["callable"]).toSet()
        return rows(d["all"]).map { t ->
            val name = str(t["name"]).orEmpty()
            ToolDetail(
                name = name,
                description = str(t["description"]).orEmpty(),
                active = name in active,
                callable = name in callable,
                exposure = str(t["exposure"]),
                source = ((t["sourceInfo"] as? JsonObject)?.get("path") ?: (t["sourceInfo"] as? JsonObject)?.get("source"))?.let(::str),
                parameters = t["parameters"]?.takeIf { it !is JsonNull },
            )
        }
    }

    suspend fun setActiveTools(names: List<String>) {
        commands.call("catalog.setTools", JsonObject(mapOf("names" to JsonArray(names.map(::JsonPrimitive)))))
    }

    // ---- 扩展 flag ----
    data class ExtensionFlag(val name: String, val description: String?, val isBoolean: Boolean, val value: JsonElement?, val extensionPath: String)

    suspend fun flags(): List<ExtensionFlag> = rows(commands.call("catalog.flags").data["flags"]).map {
        ExtensionFlag(str(it["name"]).orEmpty(), str(it["description"]), str(it["type"]) == "boolean", it["value"]?.takeIf { v -> v !is JsonNull }, str(it["extensionPath"]).orEmpty())
    }

    suspend fun setFlag(name: String, value: Boolean) { commands.call("catalog.setFlag", JsonObject(mapOf("name" to JsonPrimitive(name), "value" to JsonPrimitive(value)))) }
    suspend fun setFlag(name: String, value: String) { commands.call("catalog.setFlag", JsonObject(mapOf("name" to JsonPrimitive(name), "value" to JsonPrimitive(value)))) }

    // ---- 自定义供应商（models.json） ----
    data class ModelsConfig(val path: String, val exists: Boolean, val providers: Map<String, JsonObject>, val error: String?)

    suspend fun modelsConfig(): ModelsConfig {
        val d = commands.call("models.config.get").data
        val providers = ((d["config"] as? JsonObject)?.get("providers") as? JsonObject)?.mapNotNull { (k, v) -> (v as? JsonObject)?.let { k to it } }?.toMap().orEmpty()
        return ModelsConfig(str(d["path"]).orEmpty(), bool(d["exists"]), providers, str(d["error"]))
    }

    /** 保存一个自定义供应商。校验失败 → [HostException]，`message` 是 SDK 的原话；磁盘上仍是上一份配置。 */
    suspend fun setProvider(id: String, provider: JsonObject) {
        commands.call("models.config.setProvider", JsonObject(mapOf("id" to JsonPrimitive(id), "provider" to provider)))
    }

    suspend fun removeProvider(id: String) { commands.call("models.config.removeProvider", JsonObject(mapOf("id" to JsonPrimitive(id)))) }

    // ---- 内部 ----
    private fun cacheOf(d: JsonObject): CacheStatus {
        val st = d["status"] as? JsonObject
        return CacheStatus(str(d["mode"]).orEmpty(), str(st?.get("state")), str(st?.get("reason")), (st?.get("nextWarmAt") as? JsonPrimitive)?.content?.toLongOrNull())
    }

    private fun modelRef(o: JsonObject) = ModelRef(str(o["provider"]).orEmpty(), str(o["id"]).orEmpty(), str(o["name"]), str(o["thinkingLevel"]))
    private fun rows(e: JsonElement?): List<JsonObject> = (e as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
    private fun strings(e: JsonElement?): List<String> = (e as? JsonArray)?.mapNotNull { str(it) }.orEmpty()
    private fun str(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun bool(e: JsonElement?): Boolean = (e as? JsonPrimitive)?.content == "true"
    private fun int(e: JsonElement?): Int? = (e as? JsonPrimitive)?.content?.toIntOrNull()
    private fun JsonElement?.orEmptyElement(): JsonElement = this ?: JsonNull

    private companion object { const val LONG_MS = 180_000L }
}
