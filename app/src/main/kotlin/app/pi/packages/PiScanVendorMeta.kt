package app.pi.packages

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 厂商在自己的 `/models` 响应里**自愿给**的模型元数据。
 *
 * 为什么值得单独一个纯文件：这里的规则全是**静默出错**型 —— 价格单位换错一百万倍、图片
 * 能力读反，界面上都只是一个数字或一个标签，没人看得出来。`tools/run-app-pure-checks.sh`
 * 的 `scan-vendor-meta` 用真实形状（OpenRouter / vLLM / LM Studio / models.dev）把这些规则
 * 钉住。
 *
 * 用户裁定：「pi 官方配置文件有数据的，就用 pi 官方的数据；pi 暂时没有的，就用这个 API
 * 扫到的数据。」这里读的就是后半句那份数据：**厂商给了才带上，没给就是 null** ——
 * 调用方于是什么都不写，交给 pi 的默认值，绝不猜。
 *
 * ## 为什么字段名是一张表而不是一个名字
 *
 * OpenAI 的 `/v1/models` 只有 `id`，**没有任何统一的元数据口**：OpenRouter 用
 * `context_length` / `pricing` / `architecture.input_modalities`；vLLM 用 `max_model_len`；
 * LM Studio 用 `max_context_length`；Ollama 的 `/api/show` 用 `capabilities`；Google 的
 * `models.list` 用 `inputTokenLimit` / `outputTokenLimit`；models.dev 用
 * `limit.{context,output}` + `cost.{input,output}`（每百万美元）。同一件事有六种拼法，
 * 中转站干脆什么都不给。所以这里按**语义**分组收：上下文、最大输出、图片、价格，各自
 * 认几种写法，认不出就是 null。
 */
internal data class ScanVendorMeta(
    val contextWindow: Long? = null,
    val maxTokens: Long? = null,
    val acceptsImages: Boolean? = null,
    val costInputPerMillion: Double? = null,
    val costOutputPerMillion: Double? = null,
) {
    /** 厂商到底说没说话：一处都没有，就是没说。 */
    val any: Boolean
        get() = contextWindow != null || maxTokens != null || acceptsImages != null ||
            costInputPerMillion != null || costOutputPerMillion != null
}

/**
 * 读各家 `/models` 里**共同的那几件事**，每一个都可选：
 *
 *  - 上下文：`context_length`（OpenRouter）/ `context_window`（部分自建网关）/
 *    `max_context_length`（LM Studio）/ `max_model_len`（vLLM、SGLang）/
 *    `max_input_tokens` / `inputTokenLimit`（Google）/ `limit.context`（models.dev）
 *  - 最大输出：`top_provider.max_completion_tokens`（OpenRouter）/ `max_output_tokens` /
 *    `max_completion_tokens` / `outputTokenLimit`（Google）/ `limit.output`（models.dev）
 *  - 图片：`architecture.input_modalities` / `capabilities` 里有 `vision`（Ollama、LM Studio）/
 *    `modalities.input` 里有 `image`（models.dev）/ `supports_vision`
 *  - 价格：`pricing.prompt|completion`（OpenRouter，**USD per token**）、
 *    `cost.input|output`（models.dev，**USD per million**）、
 *    `input_cost_per_token|output_cost_per_token`（LiteLLM 形状，per token）
 */
internal fun scanVendorMeta(obj: JsonObject): ScanVendorMeta {
    val limit = obj["limit"] as? JsonObject
    return ScanVendorMeta(
        contextWindow = firstLong(
            obj,
            "context_length",
            "context_window",
            "max_context_length",
            "max_model_len",
            "max_input_tokens",
            "inputTokenLimit",
            "n_ctx",
            "max_sequence_length",
        ) ?: longOf(limit?.get("context")),
        maxTokens = longOf((obj["top_provider"] as? JsonObject)?.get("max_completion_tokens"))
            ?: firstLong(
                obj,
                "max_output_tokens",
                "max_completion_tokens",
                "outputTokenLimit",
                "max_output",
                "max_tokens",
            )
            ?: longOf(limit?.get("output")),
        acceptsImages = imagesOf(obj["architecture"] as? JsonObject)
            ?: capabilitiesOf(obj["capabilities"])
            ?: modalitiesOf(obj["modalities"] as? JsonObject)
            ?: boolOf(obj["supports_vision"]),
        costInputPerMillion = priceOf(obj["pricing"], "prompt")
            ?: millionOf(obj["cost"], "input")
            ?: perTokenOf(obj["input_cost_per_token"]),
        costOutputPerMillion = priceOf(obj["pricing"], "completion")
            ?: millionOf(obj["cost"], "output")
            ?: perTokenOf(obj["output_cost_per_token"]),
    )
}

private fun firstLong(obj: JsonObject, vararg keys: String): Long? {
    for (key in keys) {
        longOf(obj[key])?.let { return it }
    }
    return null
}

private fun longOf(element: JsonElement?): Long? =
    (element as? JsonPrimitive)?.content?.trim()?.toLongOrNull()

private fun boolOf(element: JsonElement?): Boolean? {
    val raw = (element as? JsonPrimitive)?.content?.trim()?.lowercase() ?: return null
    return when (raw) {
        "true", "1", "yes" -> true
        "false", "0", "no" -> false
        else -> null
    }
}

private fun stringsOf(element: JsonElement?): List<String>? {
    val array = (element as? JsonArray) ?: return null
    return array.mapNotNull { (it as? JsonPrimitive)?.content?.trim()?.lowercase() }
}

/** `architecture.input_modalities` 是字符串数组；它缺席 = 厂商没表态 = null。 */
private fun imagesOf(architecture: JsonObject?): Boolean? {
    val modalities = stringsOf(architecture?.get("input_modalities")) ?: return null
    return modalities.any { it == "image" || it == "vision" }
}

/** Ollama / LM Studio 的 `capabilities: ["completion","vision","tools"]`。 */
private fun capabilitiesOf(element: JsonElement?): Boolean? {
    val capabilities = stringsOf(element) ?: return null
    return capabilities.any { it == "vision" || it == "image" }
}

/** models.dev 的 `modalities.input`。 */
private fun modalitiesOf(modalities: JsonObject?): Boolean? {
    val input = stringsOf(modalities?.get("input")) ?: return null
    return input.any { it == "image" || it == "vision" }
}

/**
 * 厂商价是 **per token** 的字符串（OpenRouter：`"0.000003"`），pi 的 `cost` 是
 * **per million**（模型选择器显示 `$3 / M`）。这个 ×1_000_000 就是本函数存在的理由：
 * 忘了它，价格会小一百万倍，而屏幕上只是一个小数。
 */
private fun priceOf(pricing: JsonElement?, key: String): Double? {
    val raw = (pricing as? JsonObject)?.get(key) as? JsonPrimitive ?: return null
    val perToken = raw.content.trim().toDoubleOrNull() ?: return null
    return perToken * 1_000_000.0
}

/** models.dev 的 `cost.input` / `cost.output` 本身**就是每百万美元**，不再换算。 */
private fun millionOf(cost: JsonElement?, key: String): Double? {
    val raw = (cost as? JsonObject)?.get(key) as? JsonPrimitive ?: return null
    return raw.content.trim().toDoubleOrNull()
}

/** LiteLLM 的 `input_cost_per_token` / `output_cost_per_token`，per token → per million。 */
private fun perTokenOf(element: JsonElement?): Double? {
    val raw = (element as? JsonPrimitive)?.content?.trim()?.toDoubleOrNull() ?: return null
    return raw * 1_000_000.0
}
