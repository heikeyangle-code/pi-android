package app.pi.models

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 自定义供应商（`models.json` 的一个 provider）与表单之间的纯转换。
 *
 * 这里**不校验**：校验是 SDK 的事——`models.config.setProvider` 把整份配置交给 `ModelRuntime`
 * 当校验器，非法就回滚并带回字段精确的原话（`providers.mine.baseUrl: must be string`）。
 * 表单只负责三件事：
 *  1. 读出已有配置时**不丢字段**：认不得的键（`compat`、`cost`、`headers` 的别的写法…）原样放进 [extra]，
 *     保存时原样写回——我们没有做界面的字段，不应该被一次保存悄悄删掉；
 *  2. 空的就不写（不写 `""`，不写 `NaN`）；
 *  3. 数字字段写不成数字时，告诉用户是哪一个，而不是写出垃圾让 SDK 去报。
 */
data class ProviderForm(
    val id: String = "",
    val baseUrl: String = "",
    val api: String = "",
    val apiKey: String = "",
    /** 每行 `名称: 值`。 */
    val headersText: String = "",
    val models: List<ModelForm> = emptyList(),
    val extra: JsonObject = JsonObject(emptyMap()),
) {
    data class ModelForm(
        val id: String = "",
        val name: String = "",
        val reasoning: Boolean = false,
        val acceptsImages: Boolean = false,
        val contextWindow: String = "",
        val maxTokens: String = "",
        val extra: JsonObject = JsonObject(emptyMap()),
    )

    sealed interface Result {
        data class Ok(val json: JsonObject) : Result
        data class Invalid(val message: String) : Result
    }

    fun toJson(): Result {
        if (models.any { it.id.isBlank() }) return Result.Invalid("有模型没有填 ID。")
        val ids = models.map { it.id.trim() }
        ids.firstOrNull { id -> ids.count { it == id } > 1 }?.let { return Result.Invalid("模型 ID 重复：$it") }
        val headers = parseHeaders(headersText) ?: return Result.Invalid("请求头每行要写成「名称: 值」。")
        val out = LinkedHashMap<String, JsonElement>(extra)
        put(out, "baseUrl", baseUrl)
        put(out, "api", api)
        put(out, "apiKey", apiKey)
        if (headers.isEmpty()) out.remove("headers") else out["headers"] = JsonObject(headers.mapValues { JsonPrimitive(it.value) })
        val modelJson = ArrayList<JsonElement>()
        for (m in models) {
            val o = LinkedHashMap<String, JsonElement>(m.extra)
            o["id"] = JsonPrimitive(m.id.trim())
            put(o, "name", m.name)
            if (m.reasoning) o["reasoning"] = JsonPrimitive(true) else o.remove("reasoning")
            // extra 里有 input = 它有表单表达不了的取值：整个原样留着，开关不再碰它。
            if (!m.extra.containsKey("input")) {
                if (m.acceptsImages) o["input"] = JsonArray(listOf(JsonPrimitive("text"), JsonPrimitive("image"))) else o.remove("input")
            }
            for ((key, label, text) in listOf(Triple("contextWindow", "上下文窗口", m.contextWindow), Triple("maxTokens", "最大输出", m.maxTokens))) {
                val t = text.trim()
                if (t.isEmpty()) { o.remove(key); continue }
                val n = t.toLongOrNull()?.takeIf { it > 0 } ?: return Result.Invalid("「${m.id}」的$label 要是正整数。")
                o[key] = JsonPrimitive(n)
            }
            modelJson += JsonObject(o)
        }
        if (modelJson.isEmpty()) out.remove("models") else out["models"] = JsonArray(modelJson)
        return Result.Ok(JsonObject(out))
    }

    companion object {
        /** 已有 provider → 表单。认得的字段进表单，其余进 extra。 */
        fun from(id: String, provider: JsonObject): ProviderForm {
            val known = setOf("baseUrl", "api", "apiKey", "headers", "models")
            val headers = (provider["headers"] as? JsonObject)?.entries
                ?.joinToString("\n") { (k, v) -> "$k: ${(v as? JsonPrimitive)?.content ?: v}" }.orEmpty()
            val models = (provider["models"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map { m ->
                val knownModel = setOf("id", "name", "reasoning", "input", "contextWindow", "maxTokens")
                val input = (m["input"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
                // input 里除了 text/image 还有别的值时，原样留在 extra，不被「只认图片」吞掉。
                val inputIsPlain = input.all { it == "text" || it == "image" }
                ModelForm(
                    id = str(m["id"]),
                    name = str(m["name"]),
                    reasoning = (m["reasoning"] as? JsonPrimitive)?.content == "true",
                    acceptsImages = "image" in input,
                    contextWindow = str(m["contextWindow"]),
                    maxTokens = str(m["maxTokens"]),
                    extra = JsonObject(m.filterKeys { it !in knownModel || (it == "input" && !inputIsPlain) }),
                )
            }
            return ProviderForm(
                id = id,
                baseUrl = str(provider["baseUrl"]),
                api = str(provider["api"]),
                apiKey = str(provider["apiKey"]),
                headersText = headers,
                models = models,
                extra = JsonObject(provider.filterKeys { it !in known }),
            )
        }

        /** `名称: 值` 逐行；空行忽略；任何一行没有冒号或名称为空就整体不合法（null）。 */
        fun parseHeaders(text: String): Map<String, String>? {
            val out = LinkedHashMap<String, String>()
            for (raw in text.lineSequence()) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                val at = line.indexOf(':')
                if (at <= 0) return null
                out[line.substring(0, at).trim()] = line.substring(at + 1).trim()
            }
            return out
        }

        private fun put(into: MutableMap<String, JsonElement>, key: String, value: String) {
            val v = value.trim()
            if (v.isEmpty()) into.remove(key) else into[key] = JsonPrimitive(v)
        }

        private fun str(e: JsonElement?): String = (e as? JsonPrimitive)?.content.orEmpty()
    }
}
