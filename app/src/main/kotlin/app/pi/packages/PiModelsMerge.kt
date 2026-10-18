package app.pi.packages

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The merge rules `PiModelsFile` writes `<agentDir>/models.json` with.
 *
 * ## Why this is its own object
 *
 * It is the half of that writer that can be wrong **silently**: pi's schema allows
 * keys this app does not model, and a reader-modifier-writer that rebuilds a block
 * from its own data class deletes every one of them. That is exactly what happened
 * — `PiModelsFile.upsert` used to assign the freshly built block over the old one,
 * so saving a provider discarded its `headers`, `compat`, `oauth`, `apiKey` and
 * `modelOverrides`, and saving a model discarded its `thinkingLevelMap`,
 * `samplingParams`, `headers`, `compat`, `baseUrl` and `cost.tiers`
 * (`docs/known-gaps.md` §M12).
 *
 * Keeping the rules here — no files, no Compose, no Android — is what lets
 * `PackagesPureLogicCheck` run them on a bare JVM, which is the only kind of test
 * this repository can run for the app module.
 *
 * ## The rule, in one line
 *
 * **The app owns some keys; pi owns the rest.** Every key in the written block wins; a key
 * the app models authoritatively but did **not** write (`models`, `baseUrl`, `api`) is
 * removed from the file rather than left behind; every other key already in the file is
 * carried over untouched.
 *
 * @see PiConfigFiles for the file-level read/write and the schema citations.
 */
internal object PiModelsMerge {

    /**
     * One provider block: [written] laid over [existing].
     *
     * `models`, `baseUrl` and `api` are special-cased: their **absence** in [written] is
     * itself a statement, so the key is removed rather than left as the merge would.
     * Leaving one behind would let a copy the app deliberately stopped writing win over
     * pi's own definition — see [declaredConnection] for the connection pair and
     * `docs/known-gaps.md` §M11 for `models` ("pi's own catalog is the only declaration
     * for this provider", which is exactly what an absent `models` says).
     *
     * `name`, `authHeader` and every key the app does not model stay on the old rule:
     * absent means "no opinion", and the file's value is carried over untouched.
     */
    fun provider(existing: JsonObject?, written: JsonObject): JsonObject {
        if (existing == null) return written
        val merged = LinkedHashMap<String, JsonElement>(existing)
        for ((key, value) in written) merged[key] = value
        val declaredModels = written["models"]
        if (declaredModels != null) {
            merged["models"] = models(existing["models"], declaredModels)!!
        } else {
            merged.remove("models")
        }
        for (key in ABSENCE_IS_A_STATEMENT) {
            if (key !in written) merged.remove(key)
        }
        return JsonObject(merged)
    }

    /**
     * 整份 `models.json`：[providers] 换成写好的那份，**根上其余键原样保留**。
     *
     * 为什么要单独一条：`PiModelsFile.upsert` / `remove` 以前是
     * `buildJsonObject { put("providers", …) }` —— 把整份文档重建成只有一个键。pi 的
     * `models.json` 允许根上有别的键（`$schema` 是最常见的那个，编辑器与 schema 校验都写它），
     * 保存一次厂商就把它抹掉了。规矩与 [provider] 同一条：**App 只拥有它写的那一块**。
     *
     * 注释不在这个保证里：写入是重新序列化（`docs/models.md` 允许注释是给**读**用的），
     * 根键与厂商块里的未知字段保留，注释会丢 —— 那是本写入器一贯的行为，不是这一条引入的。
     */
    fun root(existing: JsonObject?, providers: JsonObject): JsonObject {
        val merged = LinkedHashMap<String, JsonElement>(existing ?: emptyMap())
        merged["providers"] = providers
        return JsonObject(merged)
    }

    /**
     * The keys [provider] treats as authoritative: absent from the written block means
     * "delete what is in the file". `authHeader` is deliberately **not** here — the form has
     * no field for it, so `PiCredentialService.save` always writes it (one key that keeps a
     * block pi's composer accepts; see `declaredConnection`).
     */
    private val ABSENCE_IS_A_STATEMENT = setOf("baseUrl", "api")

    // ------------------------------------------- 只声明 pi 不知道的东西（连接字段）

    /**
     * pi 自己对一个厂商的连接定义 —— `providers/<id>.js` 的 `baseUrl` 与 `api`
     * （例如 1.3.0 的 `kimi-coding.js:10` / `:21`）。
     */
    data class OfficialConnection(val baseUrl: String, val api: String)

    /**
     * 这次要为一个厂商块写的连接字段；`null` = **不写这个键**（让 pi 用它自己的定义）。
     */
    data class Connection(val baseUrl: String?, val api: String?)

    /**
     * 一个厂商块要写哪些连接字段。规矩和 `name` 那条**是同一条**
     * （`PiCredentialService.save` 的 `name = if (preset.builtInPi) null else preset.displayName`）：
     * **App 只声明 pi 不知道的东西。**
     *
     * [official] 非空（pi 自己有这个厂商）时，值等于 pi 那份定义的那个字段**不写** —— 用户没
     * 表达过意见，让 pi 用它自己的定义。抄一份的下场是官方一改、副本赢、而且是过期的那份：
     * Kimi 在 1.3.0 把 `baseUrl` 换成 `…/coding/v1`、`api` 换成 `openai-completions`，一份
     * 1.2.0 抄下来的副本就是坏的，而它还压着官方定义不放。
     *
     * 不等于时才写 —— relay / 代理 / 自建端点，那是真信息，必须原样保留。此时它**赢**过 pi 的
     * 定义（[provider] 的规则），这正是用户要的。
     *
     * [baseUrl] 先规范化再比、再写（`trim().trimEnd('/')`，与这一行以前写出去的完全一样）：
     * 一个尾斜杠是同一条 URL 的另一种拼法，不是用户的意见。`api` 逐字节比较 —— 它不是 URL，
     * 没有等价拼法。
     *
     * [official] 为空（App 自建的 ollama / llama.cpp / 自定义）时两个字段照旧**无条件**写：
     * 那里 `models.json` 是唯一的定义，省掉任何一个都会让 pi 拼不出这个厂商。
     */
    fun declaredConnection(
        baseUrl: String,
        api: String,
        official: OfficialConnection?,
    ): Connection {
        val normalisedBaseUrl = baseUrl.trim().trimEnd('/')
        if (official == null) return Connection(baseUrl = normalisedBaseUrl, api = api)
        return Connection(
            baseUrl = normalisedBaseUrl.takeUnless { it == official.baseUrl.trim().trimEnd('/') },
            api = api.takeUnless { it == official.api },
        )
    }

    /**
     * The same rule one level down: each declared entry is laid over the entry that
     * already carries its `id`, so the per-model keys this app does not model
     * survive. An entry the file did not already have is written as-is.
     */
    fun models(existing: JsonElement?, declared: JsonElement?): JsonElement? {
        val list = declared as? JsonArray ?: return declared
        val prior = (existing as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?.mapNotNull { obj -> idOf(obj)?.let { id -> id to obj } }
            ?.toMap()
            .orEmpty()
        if (prior.isEmpty()) return list
        return JsonArray(
            list.map { element ->
                val obj = element as? JsonObject ?: return@map element
                val before = idOf(obj)?.let { prior[it] } ?: return@map element
                JsonObject(LinkedHashMap<String, JsonElement>(before).apply { putAll(obj) })
            },
        )
    }

    private fun idOf(obj: JsonObject): String? = (obj["id"] as? JsonPrimitive)?.content
}
