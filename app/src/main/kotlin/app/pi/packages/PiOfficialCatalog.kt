package app.pi.packages

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * pi 官方随包发的**模型目录**：`…/pi-ai/dist/providers/data/` 下的 41 份
 * `<provider>.json`，加一份 `.manifest.json`（`schemaVersion: 3` + 每份文件的 SHA-256）。
 *
 * ## 为什么是它，而不是 App 自己维护一张提供商表
 *
 * 这张目录就在 APK 的运行时载荷里（`tools/fetch-runtime.mjs` 的
 * `{ name: "pi-engine", prefix: "rootfs/opt/pi" }`），解包后是普通文件。用户裁定：
 * 「不能直读官方文件展示出来吗？自己不维护了不行吗？」—— 行：读取它，提供商列表、每个
 * 提供商的模型、以及模型元数据（`baseUrl` / `api` / `reasoning` / `input` 图片能力 /
 * `contextWindow` / `maxTokens` / `cost`）全部来自 pi 自己的文件；**升级 `PI_VERSION`
 * 换载荷，列表自动跟着变**，没有任何一处需要人来抄。
 *
 * ## "没有 bug"靠什么
 *
 * `.manifest.json` 给每份文件记了 SHA-256，所以读取的每一步都有判据：
 * 文件缺失、哈希不符、JSON 解析失败 —— 都记进 [Catalog.problems] 并跳过那一份，
 * 而不是静默少列一个提供商。调用方必须把 problems 显示出来（见导入屏的说明行）。
 *
 * 纯逻辑 + `java.io` + `java.security`，没有任何 Android 依赖：`official-catalog`
 * harness 用合成的清单把上面每一条都钉住。
 */
object PiOfficialCatalog {

    /** 一份目录里的一条模型定义 —— 只取这个 App 用得上的字段，缺席一律 null。 */
    data class Model(
        val id: String,
        val name: String?,
        val api: String?,
        val baseUrl: String?,
        val reasoning: Boolean?,
        /** `input` 里有 `image` = true；数组在但没 image = false；数组缺席 = null（没说）。 */
        val acceptsImages: Boolean?,
        val contextWindow: Long?,
        val maxTokens: Long?,
        /** pi 的单位：**每百万 token 美元**（目录里就是这个单位，不再换算）。 */
        val costInputPerMillion: Double?,
        val costOutputPerMillion: Double?,
    )

    data class Provider(val id: String, val models: List<Model>)

    /**
     * @param providers 成功读取的提供商，按 id 排序。
     * @param problems 每一份读不出来/哈希不符/解析失败的文件各一句 —— **必须显示**，
     *        否则"少了一个提供商"就变成无声的谎。
     */
    data class Catalog(val providers: List<Provider>, val problems: List<String>)

    const val MANIFEST_NAME = ".manifest.json"

    /** 读一个目录（设备上就是 `<rootfs>/opt/pi/…/providers/data`）。 */
    fun readDirectory(dataDir: File): Catalog {
        val manifest = File(dataDir, MANIFEST_NAME)
        val text = runCatching { manifest.readText() }.getOrNull()
            ?: return Catalog(emptyList(), listOf("模型目录清单读不到：${manifest.absolutePath}"))
        return read(text) { name ->
            // 清单里的文件名就是 `<provider>.json`；只允许一个文件名，不允许路径分隔符。
            if (name.contains('/') || name.contains('\\')) {
                null
            } else {
                runCatching { File(dataDir, name).readText() }.getOrNull()
            }
        }
    }

    /** 纯函数版：清单原文 + 一个按文件名取原文的读取器。 */
    fun read(manifestText: String, readFile: (String) -> String?): Catalog {
        val manifest = runCatching { Json.parseToJsonElement(manifestText) as? JsonObject }.getOrNull()
            ?: return Catalog(emptyList(), listOf("模型目录清单解析失败（不是 JSON 对象）"))
        val files = manifest["files"] as? JsonObject
            ?: return Catalog(emptyList(), listOf("模型目录清单里没有 files 表"))
        val providers = mutableListOf<Provider>()
        val problems = mutableListOf<String>()
        for ((name, hashElement) in files) {
            val expected = (hashElement as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            val text = readFile(name)
            if (text == null) {
                problems += "$name 读不到（运行时可能还没解包）"
                continue
            }
            if (expected != null && sha256Hex(text) != expected) {
                problems += "$name 与清单里的哈希不符（文件损坏，已跳过）"
                continue
            }
            val provider = providerOf(name, text)
            if (provider == null) {
                problems += "$name 解析失败"
            } else {
                providers += provider
            }
        }
        return Catalog(providers.sortedBy { it.id }, problems)
    }

    private fun providerOf(fileName: String, text: String): Provider? {
        val root = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        // 一份文件按 **api** 分组（`{"anthropic-messages": { "<id>": {...} }}`），
        // 所以两层都要走；同 id 只留第一条。
        val models = LinkedHashMap<String, Model>()
        for ((_, group) in root) {
            val byId = group as? JsonObject ?: continue
            for ((_, element) in byId) {
                val parsed = modelOf(element as? JsonObject ?: continue) ?: continue
                models.putIfAbsent(parsed.id, parsed)
            }
        }
        return Provider(id = fileName.removeSuffix(".json"), models = models.values.toList())
    }

    private fun modelOf(obj: JsonObject): Model? {
        val id = (obj["id"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: return null
        val cost = obj["cost"] as? JsonObject
        return Model(
            id = id,
            name = (obj["name"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() },
            api = (obj["api"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() },
            baseUrl = (obj["baseUrl"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() },
            reasoning = (obj["reasoning"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull(),
            acceptsImages = (obj["input"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.content }
                ?.let { inputs -> "image" in inputs },
            contextWindow = (obj["contextWindow"] as? JsonPrimitive)?.content?.toLongOrNull(),
            maxTokens = (obj["maxTokens"] as? JsonPrimitive)?.content?.toLongOrNull(),
            costInputPerMillion = (cost?.get("input") as? JsonPrimitive)?.content?.toDoubleOrNull(),
            costOutputPerMillion = (cost?.get("output") as? JsonPrimitive)?.content?.toDoubleOrNull(),
        )
    }

    /** 清单里的哈希就是它；harness 用它合成 fixture。 */
    internal fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
}
