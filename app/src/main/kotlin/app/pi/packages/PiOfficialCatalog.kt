package app.pi.packages

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * pi 官方随包发的**模型目录**：`…/pi-ai/dist/providers/data/` 下每份 `<provider>.json`
 * （0.99.2 实测 42 份），加一份 `.manifest.json`（`schemaVersion` + 每份文件的 SHA-256）。
 * **1.0.0 与 1.0.1 都逐项复核过：仍是 42 份、`schemaVersion` 仍是 6、仍是
 * `api → ${type}:${id}`；条目 0.99.2 是 1529/57/15，1.0.0 是 1532/57/15，
 * 1.0.1 是 1536 chat / 59 image / 20 classifier。1.0.3 仍是 42 份、`schemaVersion` 仍是 6，
 * 条目 1539 chat / 59 image / 23 classifier；只有跟着 provider 改名的那份文件名从
 * `azure-openai-responses.json` 变成 `azure.json`（读取器按目录遍历，不看具体文件名，
 * 所以本文件不用改）。**
 *
 * 0.99.2 起 `schemaVersion` 是 6（0.87.1 是 3），且数据格式变了三处：条目新增 `type`
 * 判别字段，内层键从 `<id>` 变成 `` `${type}:${id}` ``，同一份文件里混进 image /
 * classifier 条目。这个读法只认 **chat** 条目，版本号对不上会进 problems —— 见
 * [MODEL_DATA_SCHEMA_VERSION] 和 [providerOf] 的 KDoc。旧版这段 KDoc 把
 * `schemaVersion: 3` 当成既定事实抄了下来，正是这次升级要修掉的那种假事实。
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
 * `manifest.schemaVersion` 不等于 pi 的 [MODEL_DATA_SCHEMA_VERSION] 也进 problems：
 * 那意味着「这套读法所依据的数据格式可能已经变了」，而目录仍然按已知规则尽力解析
 * —— 升一次 pi 不该让整张表凭空消失，但这句话必须看得见。
 *
 * 纯逻辑 + `java.io` + `java.security`，没有任何 Android 依赖：`official-catalog`
 * harness 用合成的清单把上面每一条都钉住。
 */
object PiOfficialCatalog {

    /**
     * pi 的 `MODEL_DATA_SCHEMA_VERSION`，也就是 `.manifest.json` 里 `schemaVersion` 应有的值。
     *
     * 来源：pi 0.99.2 `packages/ai/scripts/model-data.ts:5`（0.87.1 是 `3`）；pi 自己也是这样
     * 校验的 —— `model-data.ts:232-234` 在 `manifest?.schemaVersion !== MODEL_DATA_SCHEMA_VERSION`
     * 时直接抛错。App 抄这个数字是因为它**变过一次**：0.99.2 把内层键从 `<id>` 改成
     * `` `${type}:${id}` ``（`packages/ai/scripts/generate-models.ts:3506-3511`）、给每条加上
     * `type`（`:3443`），并让 image / classifier 条目混进同一份文件。版本号是解析之前唯一
     * 能发现「这套读法可能已经不成立」的信号，所以它进 [Catalog.problems] 而不是被忽略。
     */
    const val MODEL_DATA_SCHEMA_VERSION = 6

    /** 进 App 导入表单的模型类型。判别值与 pi 的 `ModelType` 一致（见 [Model.type]）。 */
    private const val MODEL_TYPE_CHAT = "chat"

    /** 一份目录里的一条模型定义 —— 只取这个 App 用得上的字段，缺席一律 null。 */
    data class Model(
        val id: String,
        /**
         * pi 0.99.2 条目自带的判别字段（`generate-models.ts:3443` 的 chat 分支、
         * `:3445-3456` 的 image / classifier 分支）。缺席 = `"chat"`，与 pi 自己的读法一致：
         * `packages/ai/src/utils/model-operations.ts:17-19` `return model.type ?? "chat";`。
         * 带上它是因为 [providerOf] 必须按它过滤，而过滤掉的是 image / classifier —— 这两种
         * 条目没有 `reasoning` / `maxTokens`（[acceptsImages] 之外的能力会静默显示成"未设置"）。
         */
        val type: String,
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

    /**
     * @param models 至少一条 **chat** 模型 —— 整份文件只有 image / classifier 条目的厂商
     *        （0.99.2 的 `typesafe.json`）不会出现在 [Catalog.providers] 里，理由见 [providerOf]。
     */
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
        val problems = mutableListOf<String>()
        // 版本先对：下面「按 type 过滤」的读法是按 [MODEL_DATA_SCHEMA_VERSION] 写的。
        // 不等**不是致命错误** —— 仍然尽力解析，否则升一次 pi 整张模型表就会凭空消失；
        // 但这句话必须进 problems，让「数据格式又变了」在屏幕上可见，而不是静默继续猜。
        val schemaVersion = (manifest["schemaVersion"] as? JsonPrimitive)?.content?.toIntOrNull()
        if (schemaVersion != MODEL_DATA_SCHEMA_VERSION) {
            problems += "模型目录清单的 schemaVersion 是 ${schemaVersion?.toString() ?: "缺席"}，" +
                "App 认的是 $MODEL_DATA_SCHEMA_VERSION（pi 的 MODEL_DATA_SCHEMA_VERSION）：" +
                "数据格式可能已变，下面按已知格式解析"
        }
        val files = manifest["files"] as? JsonObject
            ?: return Catalog(emptyList(), problems + "模型目录清单里没有 files 表")
        val providers = mutableListOf<Provider>()
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
                continue
            }
            // 整份文件只有 image / classifier 条目 —— 0.99.2 的 `typesafe.json` 就是
            // （只有一个 `classifier:jev-latest`）。这**不是错误**：哈希对、JSON 也解析
            // 得开，所以不进 problems；但也不列成一个可选厂商 —— 这个 App 只能导入
            // chat 模型（`models.json` 的 schema 里没有 `type`，report-B §3.3），列出来
            // 只会得到一个 `baseUrl` 为空、0 个模型的幽灵厂商（`ModelProviderScreen.kt`
            // 的 `presetFromOfficial` 会用空串兜底）。
            if (provider.models.isEmpty()) continue
            providers += provider
        }
        return Catalog(providers.sortedBy { it.id }, problems)
    }

    private fun providerOf(fileName: String, text: String): Provider? {
        val root = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        // 一份文件按 **api** 分组，内层键在 0.99.2 是 `` `${model.type}:${model.id}` ``
        // （`packages/ai/scripts/generate-models.ts:3506-3511`），所以两层都要走：
        //   {"openai-completions": {"chat:deepseek-flash": {...}}}
        // 键名本身可以忽略（真 id 在条目的 `id` 字段里），但**条目的 `type` 不能忽略**：
        // 只有 chat 条目能进这个 App 的导入表单。
        //
        // **为什么不能只靠下面那个 putIfAbsent 去重**：去重只认 id，而 0.99.2 的 image /
        // classifier 条目有自己的 id（`image:gpt-image-1`、`classifier:jev-latest`），没有任何
        // 一条同 id 的 chat 条目会与它们撞上；`typesafe.json` 整份更是只有 classifier。
        // 键序只保证 `chat:` 排在 `image:` / `classifier:` 前面（`generate-models.ts:3468-3476`
        // 按 key 排序），对 image-only / classifier-only 的 id 零保护 —— 不显式判 type，
        // 这些条目会被原样放进 chat 列表，变成一条能力残缺的 chat 模型定义。
        //
        // 同 id 在多个 api 分组里重复时（格式允许）仍然只留第一条。
        val models = LinkedHashMap<String, Model>()
        for ((_, group) in root) {
            val byId = group as? JsonObject ?: continue
            for ((_, element) in byId) {
                val parsed = modelOf(element as? JsonObject ?: continue) ?: continue
                if (parsed.type != MODEL_TYPE_CHAT) continue
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
            // 0.99.2 给条目显式写了 `type`（`packages/ai/scripts/generate-models.ts:3443` 给
            // chat 条目补 `type: "chat"`）。缺席 = chat —— pi 自己的读法就是如此
            // （`packages/ai/src/utils/model-operations.ts:17-19` `return model.type ?? "chat";`）。
            type = (obj["type"] as? JsonPrimitive)?.content ?: MODEL_TYPE_CHAT,
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
