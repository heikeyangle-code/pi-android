package app.pi.packages

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * **models.dev** 的一张索引：pi 自己的模型目录就是用它生成的
 * （`packages/ai/scripts/generate-models.ts` 里到处是 models.dev 与
 * `models-dev-reasoning-options.ts`），所以它不是"第三方的猜测"，是 pi 数据的上游。
 *
 * 为什么需要它：厂商的 `/models` 只回 id（或少数几家回上下文/价格），**没有一家回
 * 思考档位**（`thinkingLevelMap` 是按 `minimal/low/medium/high/xhigh/max` 这套 pi 的档位
 * 建的映射，属于 pi 的建模）。`models.dev` 里有 `reasoning_options`，覆盖 232 家、几千个
 * 模型，正是"所有厂商都能自动拿到思考档位"的那张表。
 *
 * 这里只做**解析**：怎么取回来、缓存多久，在 `PiCredentialService` 里。
 */
internal object PiModelsDev {

    /** 一个模型在 models.dev 里的那几个字段，单位与 pi 一致（价格是每百万美元）。 */
    data class Entry(
        val provider: String,
        val id: String,
        val contextWindow: Long? = null,
        val maxTokens: Long? = null,
        val acceptsImages: Boolean? = null,
        val reasoning: Boolean? = null,
        val costInput: Double? = null,
        val costOutput: Double? = null,
        val costCacheRead: Double? = null,
        val costCacheWrite: Double? = null,
        /** 由 `reasoning_options` 推出的 pi 档位表；没有 effort 选项时是 null。 */
        val thinkingLevelMap: JsonObject? = null,
    )

    /**
     * 整份 `api.json` → 两张表：`厂商/模型` 精确键，和 `模型 id` 的兜底键。
     *
     * 兜底键是给中转站用的：它们转的 `claude-opus-4-6` 这种 id 与上游厂商同名，但自己的
     * 厂商名不在 models.dev 里。**先精确、再兜底**，与目录那条路同一个顺序。
     */
    fun parse(text: String): Pair<Map<String, Entry>, Map<String, Entry>> {
        val root = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(text) as? JsonObject
        }.getOrNull() ?: return emptyMap<String, Entry>() to emptyMap()
        val exact = HashMap<String, Entry>()
        val bare = HashMap<String, Entry>()
        root.forEach { (providerId, providerElement) ->
            val provider = providerElement as? JsonObject ?: return@forEach
            val models = provider["models"] as? JsonObject ?: return@forEach
            models.forEach { (key, modelElement) ->
                val model = modelElement as? JsonObject ?: return@forEach
                val id = (model["id"] as? JsonPrimitive)?.content
                    ?.takeIf { it.isNotBlank() } ?: key
                val entry = entryOf(providerId, id, model)
                exact.putIfAbsent("$providerId/$id", entry)
                bare.putIfAbsent(id, entry)
            }
        }
        return exact to bare
    }

    /** 先按 `厂商/模型` 查，再按裸模型 id 查。 */
    fun lookup(
        index: Pair<Map<String, Entry>, Map<String, Entry>>,
        providerId: String,
        modelId: String,
    ): Entry? = index.first["$providerId/$modelId"] ?: index.second[modelId]

    private fun entryOf(providerId: String, id: String, model: JsonObject): Entry {
        val limit = model["limit"] as? JsonObject
        val cost = model["cost"] as? JsonObject
        val modalities = (model["modalities"] as? JsonObject)?.get("input") as? JsonArray
        return Entry(
            provider = providerId,
            id = id,
            contextWindow = longOf(limit?.get("context")),
            maxTokens = longOf(limit?.get("output")),
            acceptsImages = modalities?.any {
                (it as? JsonPrimitive)?.content.equals("image", ignoreCase = true)
            },
            reasoning = (model["reasoning"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull(),
            costInput = doubleOf(cost?.get("input")),
            costOutput = doubleOf(cost?.get("output")),
            costCacheRead = doubleOf(cost?.get("cache_read")),
            costCacheWrite = doubleOf(cost?.get("cache_write")),
            thinkingLevelMap = thinkingLevelMap(model["reasoning_options"]),
        )
    }

    /**
     * models.dev 的 `reasoning_options` → pi 的 `thinkingLevelMap`，逐条照着 pi 自己的生成器
     * （`packages/ai/scripts/models-dev-reasoning-options.ts` 的
     * `getEffortThinkingLevelMap`）：
     *
     *  - 只认 `{type:"effort", values:[…]}`；
     *  - pi 的档位是 `off/minimal/low/medium/high/xhigh/max`；
     *  - `off` 有 `"none"` 才是 `"none"`，否则写 `null`（= 这个档位不映射）；
     *  - 其余档位：厂商给了同名值就写同名值，没给写 `null`；
     *  - 一个档位都不支持、也没有 `none` → 返回 null（不要写一张全 null 的表）。
     */
    fun thinkingLevelMap(options: JsonElement?): JsonObject? {
        val array = options as? JsonArray ?: return null
        val values = array.flatMap { option ->
            val obj = option as? JsonObject ?: return@flatMap emptyList()
            if ((obj["type"] as? JsonPrimitive)?.content != "effort") return@flatMap emptyList()
            val list = obj["values"] as? JsonArray ?: return@flatMap emptyList()
            list.mapNotNull { (it as? JsonPrimitive)?.content }
        }.toSet()
        if (values.isEmpty()) return null
        val levels = listOf("minimal", "low", "medium", "high", "xhigh", "max")
        if (levels.none { it in values } && "none" !in values) return null
        return buildMap<String, JsonElement> {
            put("off", if ("none" in values) JsonPrimitive("none") else JsonNull)
            levels.forEach { level -> put(level, if (level in values) JsonPrimitive(level) else JsonNull) }
        }.let(::JsonObject)
    }

    private fun longOf(element: JsonElement?): Long? =
        (element as? JsonPrimitive)?.content?.trim()?.toLongOrNull()

    private fun doubleOf(element: JsonElement?): Double? =
        (element as? JsonPrimitive)?.content?.trim()?.toDoubleOrNull()
}
