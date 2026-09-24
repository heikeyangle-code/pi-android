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
 * 的 `scan-vendor-meta` 用真实形状（OpenRouter 的 `/models`）把这些规则钉住。
 *
 * 用户裁定：「pi 官方配置文件有数据的，就用 pi 官方的数据；pi 暂时没有的，就用这个 API
 * 扫到的数据。」这里读的就是后半句那份数据：**厂商给了才带上，没给就是 null** ——
 * 调用方于是什么都不写，交给 pi 的默认值，绝不猜。
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
 * 读三种厂商形状里**共同的那几个字段**，每一个都可选：
 *
 *  - `context_length`（OpenRouter）或 `context_window`（部分自建网关）
 *  - `top_provider.max_completion_tokens`（OpenRouter 的最大输出）
 *  - `architecture.input_modalities` 含 `image` → 支持图片；数组在但没 `image` →
 *    **明确不支持**（这是一句厂商说过的话，和"没说"不同）
 *  - `pricing.prompt` / `pricing.completion`：**USD per token 的字符串**
 */
internal fun scanVendorMeta(obj: JsonObject): ScanVendorMeta = ScanVendorMeta(
    contextWindow = longOf(obj["context_length"]) ?: longOf(obj["context_window"]),
    maxTokens = longOf((obj["top_provider"] as? JsonObject)?.get("max_completion_tokens")),
    acceptsImages = imagesOf(obj),
    costInputPerMillion = priceOf(obj, "prompt"),
    costOutputPerMillion = priceOf(obj, "completion"),
)

private fun longOf(element: JsonElement?): Long? =
    (element as? JsonPrimitive)?.content?.trim()?.toLongOrNull()

/** `architecture.input_modalities` 是字符串数组；它缺席 = 厂商没表态 = null。 */
private fun imagesOf(obj: JsonObject): Boolean? {
    val modalities = (obj["architecture"] as? JsonObject)?.get("input_modalities") as? JsonArray
        ?: return null
    return modalities.any { (it as? JsonPrimitive)?.content?.equals("image", ignoreCase = true) == true }
}

/**
 * 厂商价是 **per token** 的字符串（OpenRouter：`"0.000003"`），pi 的 `cost` 是
 * **per million**（模型选择器显示 `$3 / M`）。这个 ×1_000_000 就是本文件存在的理由：
 * 忘了它，价格会小一百万倍，而屏幕上只是一个小数。
 */
private fun priceOf(obj: JsonObject, key: String): Double? {
    val raw = (obj["pricing"] as? JsonObject)?.get(key) as? JsonPrimitive ?: return null
    val perToken = raw.content.trim().toDoubleOrNull() ?: return null
    return perToken * 1_000_000.0
}
