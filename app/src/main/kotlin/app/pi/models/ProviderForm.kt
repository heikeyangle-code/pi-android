package app.pi.models

import app.pi.i18n.tr
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * 自定义供应商（`models.json` 的一个 provider）与表单之间的纯转换（对齐 Pi 1.6.1 `ModelConfig` schema）。
 *
 * 这里**不替 SDK 越权校验**：完整 schema 校验由 SDK `models.config.setProvider` / `ModelRuntime`
 * 执行，非法就回滚并带回字段精确的原话（如 `providers.mine.baseUrl: must be string`）。
 * 表单只负责三件事：
 *  1. 读出已有配置时**不丢字段**：认不得的键（`modelOverrides`、`oauth`、`samplingParams`、`compat` 其余高级项…）
 *     原样保留在 [extra] / [CompatForm.extra] / [ModelForm.costExtra]，保存时原样写回；
 *  2. 空的就不写（不写 `""`，不写 `NaN`）；
 *  3. 数字或请求头明显不合法时就地给出明确提示。
 */
data class ProviderForm(
    val id: String = "",
    /** 显示名（`name`）；空 = 用 id。 */
    val name: String = "",
    val baseUrl: String = "",
    val api: String = "",
    val apiKey: String = "",
    /**
     * `authHeader`：把解析出的 API Key 作为 `Authorization: Bearer …` 发出。要求 `apiKey` 存在
     * （SDK 在组装供应商时检查：`authHeader requires a resolved API key`，原话会被带回）。
     * 只有 true 才写；false 是 SDK 默认值，不写。
     */
    val authHeader: Boolean = false,
    /** 每行 `名称: 值`。 */
    val headersText: String = "",
    /** OpenAI 兼容参数（`compat`）；未设的字段不写出，保留由 SDK 按 URL 自动探测。 */
    val compat: CompatForm = CompatForm(),
    val models: List<ModelForm> = emptyList(),
    val extra: JsonObject = JsonObject(emptyMap()),
) {
    /**
     * OpenAI / 兼容中转常用 `compat` 参数（对齐 `@earendil-works/pi-ai/providers/compat-schema`）。
     * 布尔项为三态：`null` = 自动探测（不写），`true` / `false` = 显式覆盖。
     * 表单未覆盖的高级 `compat` 键（如 `openRouterRouting`、`chatTemplateKwargs`）全部保留在 [extra]。
     */
    data class CompatForm(
        val thinkingFormat: String = "",
        val maxTokensField: String = "",
        val supportsDeveloperRole: Boolean? = null,
        val supportsReasoningEffort: Boolean? = null,
        val supportsStore: Boolean? = null,
        val extra: JsonObject = JsonObject(emptyMap()),
    ) {
        val hasUiFields: Boolean
            get() = thinkingFormat.isNotBlank() || maxTokensField.isNotBlank() ||
                supportsDeveloperRole != null || supportsReasoningEffort != null || supportsStore != null
        val isEmpty: Boolean get() = !hasUiFields && extra.isEmpty()

        fun toJsonObject(): JsonObject? {
            if (isEmpty) return null
            val out = LinkedHashMap<String, JsonElement>(extra)
            put(out, "thinkingFormat", thinkingFormat)
            put(out, "maxTokensField", maxTokensField)
            if (supportsDeveloperRole != null) out["supportsDeveloperRole"] = JsonPrimitive(supportsDeveloperRole)
            else out.remove("supportsDeveloperRole")
            if (supportsReasoningEffort != null) out["supportsReasoningEffort"] = JsonPrimitive(supportsReasoningEffort)
            else out.remove("supportsReasoningEffort")
            if (supportsStore != null) out["supportsStore"] = JsonPrimitive(supportsStore)
            else out.remove("supportsStore")
            return if (out.isEmpty()) null else JsonObject(out)
        }

        companion object {
            private val KNOWN_KEYS = setOf(
                "thinkingFormat",
                "maxTokensField",
                "supportsDeveloperRole",
                "supportsReasoningEffort",
                "supportsStore",
            )

            fun from(obj: JsonObject?): CompatForm {
                if (obj == null) return CompatForm()
                return CompatForm(
                    thinkingFormat = str(obj["thinkingFormat"]),
                    maxTokensField = str(obj["maxTokensField"]),
                    supportsDeveloperRole = (obj["supportsDeveloperRole"] as? JsonPrimitive)?.booleanOrNull,
                    supportsReasoningEffort = (obj["supportsReasoningEffort"] as? JsonPrimitive)?.booleanOrNull,
                    supportsStore = (obj["supportsStore"] as? JsonPrimitive)?.booleanOrNull,
                    extra = JsonObject(obj.filterKeys { it !in KNOWN_KEYS }),
                )
            }
        }
    }

    data class ModelForm(
        val id: String = "",
        val name: String = "",
        /** 可选单模型协议覆盖（留空则沿用供应商 `api`）。 */
        val api: String = "",
        /** 可选单模型地址覆盖（留空则沿用供应商 `baseUrl`）。 */
        val baseUrl: String = "",
        val reasoning: Boolean = false,
        val acceptsImages: Boolean = false,
        val contextWindow: String = "",
        val maxTokens: String = "",
        /**
         * 价格，**美元 / 百万 token**（SDK 的 `ModelCostSchema`：`input` `output` `cacheRead` `cacheWrite`
         * 四项**全部必填**）。空 = 没写。只要写了其中任何一项，就必须有输入与输出；缓存读写不填按 0 写出。
         */
        val costInput: String = "",
        val costOutput: String = "",
        val costCacheRead: String = "",
        val costCacheWrite: String = "",
        /** Pi 1.7.0 新增：图片输出单价（美元 / 百万 token，留空则沿用 `output`）。 */
        val costImageOutput: String = "",
        /** `cost` 里表单没有界面的字段（`tiers` 分档价），原样带回。 */
        val costExtra: JsonObject = JsonObject(emptyMap()),
        /** 单模型级 `compat` 覆盖。 */
        val compat: CompatForm = CompatForm(),
        val extra: JsonObject = JsonObject(emptyMap()),
    ) {
        val hasCost: Boolean
            get() = listOf(costInput, costOutput, costCacheRead, costCacheWrite, costImageOutput).any { it.isNotBlank() }
    }

    /** 若该 provider 在 `models.json` 里配置了 `modelOverrides`（用于覆盖内置模型参数），返回覆盖条数。 */
    val modelOverridesCount: Int
        get() = (extra["modelOverrides"] as? JsonObject)?.size ?: 0

    sealed interface Result {
        data class Ok(val json: JsonObject) : Result
        data class Invalid(val message: String) : Result
    }

    fun toJson(): Result {
        if (models.any { it.id.isBlank() }) return Result.Invalid(tr("有模型没有填 ID。", "A model is missing its ID."))
        val ids = models.map { it.id.trim() }
        ids.firstOrNull { id -> ids.count { it == id } > 1 }?.let {
            return Result.Invalid(tr("模型 ID 重复：$it", "Duplicate model ID: $it"))
        }
        val headers = parseHeaders(headersText)
            ?: return Result.Invalid(tr("请求头每行要写成「名称: 值」。", "Headers must be written as \"Name: Value\" per line."))
        val out = LinkedHashMap<String, JsonElement>(extra)
        put(out, "name", name)
        put(out, "baseUrl", baseUrl)
        put(out, "api", api)
        put(out, "apiKey", apiKey)
        if (authHeader) out["authHeader"] = JsonPrimitive(true) else out.remove("authHeader")
        if (headers.isEmpty()) out.remove("headers") else out["headers"] = JsonObject(headers.mapValues { JsonPrimitive(it.value) })
        if (!extra.containsKey("compat")) {
            val c = compat.toJsonObject()
            if (c == null) out.remove("compat") else out["compat"] = c
        }
        val modelJson = ArrayList<JsonElement>()
        for (m in models) {
            val o = LinkedHashMap<String, JsonElement>(m.extra)
            o["id"] = JsonPrimitive(m.id.trim())
            put(o, "name", m.name)
            put(o, "api", m.api)
            put(o, "baseUrl", m.baseUrl)
            if (m.reasoning) o["reasoning"] = JsonPrimitive(true) else o.remove("reasoning")
            // extra 里有 input = 它有表单表达不了的取值：整个原样留着，开关不再碰它。
            if (!m.extra.containsKey("input")) {
                if (m.acceptsImages) o["input"] = JsonArray(listOf(JsonPrimitive("text"), JsonPrimitive("image"))) else o.remove("input")
            }
            for ((key, label, text) in listOf(
                Triple("contextWindow", tr("上下文窗口", "Context Window"), m.contextWindow),
                Triple("maxTokens", tr("最大输出", "Max Output"), m.maxTokens),
            )) {
                val t = text.trim()
                if (t.isEmpty()) { o.remove(key); continue }
                val n = t.toLongOrNull()?.takeIf { it > 0 }
                    ?: return Result.Invalid(tr("「${m.id}」的$label 要是正整数。", "$label for \"${m.id}\" must be a positive integer."))
                o[key] = JsonPrimitive(n)
            }
            // extra 里有 cost = 它不是对象（表单表达不了）：原样留着，不再碰。
            if (!m.extra.containsKey("cost")) {
                when (val cost = costJson(m)) {
                    is CostResult.Invalid -> return Result.Invalid(cost.message)
                    is CostResult.None -> o.remove("cost")
                    is CostResult.Value -> o["cost"] = cost.json
                }
            }
            if (!m.extra.containsKey("compat")) {
                val mc = m.compat.toJsonObject()
                if (mc == null) o.remove("compat") else o["compat"] = mc
            }
            modelJson += JsonObject(o)
        }
        if (modelJson.isEmpty()) out.remove("models") else out["models"] = JsonArray(modelJson)
        return Result.Ok(JsonObject(out))
    }

    private sealed interface CostResult {
        data object None : CostResult
        data class Value(val json: JsonObject) : CostResult
        data class Invalid(val message: String) : CostResult
    }

    private fun costJson(m: ModelForm): CostResult {
        if (!m.hasCost) return CostResult.None
        val label = m.id.trim()
        fun parse(text: String, what: String): Any? {
            val t = text.trim()
            if (t.isEmpty()) return null
            val d = t.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
                ?: return CostResult.Invalid(tr("「$label」的$what 要是不小于 0 的数字。", "$what for \"$label\" must be a number >= 0."))
            return d
        }
        val parsed = listOf(
            "input" to parse(m.costInput, tr("输入价格", "Input price")),
            "output" to parse(m.costOutput, tr("输出价格", "Output price")),
            "cacheRead" to parse(m.costCacheRead, tr("缓存读取价格", "Cache read price")),
            "cacheWrite" to parse(m.costCacheWrite, tr("缓存写入价格", "Cache write price")),
            "imageOutput" to parse(m.costImageOutput, tr("图片输出价格", "Image output price")),
        )
        parsed.forEach { (_, v) -> if (v is CostResult.Invalid) return v }
        val byKey = parsed.associate { (k, v) -> k to (v as Double?) }
        if (byKey["input"] == null || byKey["output"] == null) {
            return CostResult.Invalid(
                tr(
                    "「$label」的价格要同时填输入和输出（缓存读写不填按 0）。",
                    "Pricing for \"$label\" requires both input and output rates (cache rates default to 0).",
                ),
            )
        }
        val out = LinkedHashMap<String, JsonElement>(m.costExtra)
        for (key in listOf("input", "output", "cacheRead", "cacheWrite")) out[key] = number(byKey[key] ?: 0.0)
        val imgOut = byKey["imageOutput"]
        if (imgOut != null) out["imageOutput"] = number(imgOut) else out.remove("imageOutput")
        return CostResult.Value(JsonObject(out))
    }

    companion object {
        /** 整数价格写成整数（`3` 而不是 `3.0`），其余按原值。 */
        private fun number(d: Double): JsonPrimitive =
            if (d == Math.floor(d) && Math.abs(d) < 1e15) JsonPrimitive(d.toLong()) else JsonPrimitive(d)

        /** 已有 provider → 表单。认得的字段进表单，其余进 extra。 */
        fun from(id: String, provider: JsonObject): ProviderForm {
            val known = setOf("name", "baseUrl", "api", "apiKey", "authHeader", "headers", "compat", "models")
            val compatObj = provider["compat"] as? JsonObject
            val headers = (provider["headers"] as? JsonObject)?.entries
                ?.joinToString("\n") { (k, v) -> "$k: ${(v as? JsonPrimitive)?.content ?: v}" }.orEmpty()
            val models = (provider["models"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map { m ->
                val knownModel = setOf("id", "name", "api", "baseUrl", "reasoning", "input", "contextWindow", "maxTokens", "cost", "compat")
                val costObj = m["cost"] as? JsonObject
                val mCompatObj = m["compat"] as? JsonObject
                val costKeys = setOf("input", "output", "cacheRead", "cacheWrite", "imageOutput")
                val input = (m["input"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
                // input 里除了 text/image 还有别的值时，原样留在 extra，不被「只认图片」吞掉。
                val inputIsPlain = input.all { it == "text" || it == "image" }
                ModelForm(
                    id = str(m["id"]),
                    name = str(m["name"]),
                    api = str(m["api"]),
                    baseUrl = str(m["baseUrl"]),
                    reasoning = (m["reasoning"] as? JsonPrimitive)?.content == "true",
                    acceptsImages = "image" in input,
                    contextWindow = str(m["contextWindow"]),
                    maxTokens = str(m["maxTokens"]),
                    costInput = str(costObj?.get("input")),
                    costOutput = str(costObj?.get("output")),
                    costCacheRead = str(costObj?.get("cacheRead")),
                    costCacheWrite = str(costObj?.get("cacheWrite")),
                    costImageOutput = str(costObj?.get("imageOutput")),
                    costExtra = JsonObject(costObj?.filterKeys { it !in costKeys }.orEmpty()),
                    compat = CompatForm.from(mCompatObj),
                    extra = JsonObject(
                        m.filterKeys {
                            it !in knownModel ||
                                (it == "input" && !inputIsPlain) ||
                                (it == "cost" && costObj == null) ||
                                (it == "compat" && mCompatObj == null)
                        },
                    ),
                )
            }
            return ProviderForm(
                id = id,
                name = str(provider["name"]),
                baseUrl = str(provider["baseUrl"]),
                api = str(provider["api"]),
                apiKey = str(provider["apiKey"]),
                authHeader = (provider["authHeader"] as? JsonPrimitive)?.content == "true",
                headersText = headers,
                compat = CompatForm.from(compatObj),
                models = models,
                extra = JsonObject(
                    provider.filterKeys {
                        it !in known || (it == "compat" && compatObj == null)
                    },
                ),
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

        /**
         * 对齐 Pi 1.7.0 `provider-composer.js` (`applyModelsJson`) 的硬校验规则：
         * 仅填 `name` 或顶层 `api` 对内置供应商无效，至少必须提供以下字段之一：
         * `baseUrl`、`headers`、`compat`、`modelOverrides`、`models`、`apiKey`、`oauth`、`authHeader`。
         */
        fun hasEffectiveOverride(json: JsonObject): Boolean {
            val models = json["models"] as? JsonArray
            val overrides = json["modelOverrides"] as? JsonObject
            return !models.isNullOrEmpty() ||
                json.containsKey("baseUrl") ||
                json.containsKey("headers") ||
                json.containsKey("compat") ||
                !overrides.isNullOrEmpty() ||
                json.containsKey("apiKey") ||
                json.containsKey("oauth") ||
                json.containsKey("authHeader")
        }

        /**
         * 清理 Pi SDK 返回的重复前缀（如 `Provider "deepseek": Provider deepseek: ...`）
         * 并把高频校验错误翻译成可操作的中文 / 英文提示。
         */
        fun formatSdkProviderError(
            raw: String?,
            providerId: String,
            overriding: Boolean,
            hasExistingOverride: Boolean,
        ): String {
            val cleaned = raw.orEmpty()
                .trim()
                .replace(Regex("""^Provider\s+"?[^":]+"?:\s*Provider\s+"?[^":]+"?:\s*"""), "")
                .replace(Regex("""^Provider\s+"?[^":]+"?:\s*"""), "")
                .trim()
            return when {
                cleaned.contains("must specify \"baseUrl\", \"headers\", \"compat\", \"modelOverrides\", or \"models\"") -> {
                    val base = if (overriding) {
                        tr(
                            "Pi 要求至少填写 Base URL、请求头、兼容参数、API Key 或自定义模型中的一项。注意：单独修改显示名或顶层「接口协议 (api)」不会改变「$providerId」的内置模型；如需为某个内置模型更换协议，请在下方「添加模型」填入同名模型 ID 并指定协议。",
                            "Pi requires at least one of Base URL, headers, compat, API key, or custom models. Note: changing only Display Name or top-level API does not change built-in models for \"$providerId\" — add a model entry below with the same ID to override a built-in model's protocol.",
                        )
                    } else {
                        tr(
                            "至少需要填写 Base URL、请求头、兼容参数、API Key 或自定义模型中的一项。",
                            "Specify at least one of Base URL, headers, compat, API key, or custom models.",
                        )
                    }
                    if (hasExistingOverride) {
                        base + tr(" 如需恢复内置默认，请点左下角「移除覆盖」。", " To restore defaults, tap \"Remove\" at the bottom left.")
                    } else {
                        base
                    }
                }
                cleaned.contains("authHeader requires a resolved API key") -> tr(
                    "已开启「Authorization Bearer 头发密钥」，但当前未能解析出 API Key。请在外层凭据或上方 API Key 中填写密钥，或关闭该开关。",
                    "\"Authorization Bearer\" requires a resolved API key. Add one in credentials/above, or turn this switch off.",
                )
                cleaned.contains("\"baseUrl\" is required when defining custom models") -> tr(
                    "定义了自定义模型时，必须填写供应商 Base URL（或在每个模型的高级配置中填写单模型 Base URL）。",
                    "\"Base URL\" is required when defining custom models (either at provider level or per model).",
                )
                cleaned.isNotEmpty() -> cleaned
                else -> tr("保存失败，请检查配置。", "Save failed. Please check your configuration.")
            }
        }

        private fun str(e: JsonElement?): String = (e as? JsonPrimitive)?.content.orEmpty()
    }
}
