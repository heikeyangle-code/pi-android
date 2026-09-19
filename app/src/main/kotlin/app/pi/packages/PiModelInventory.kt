package app.pi.packages

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 这台设备上"配了哪些厂商、每个厂商下有哪些模型、它们从哪来、现在能不能用" ——
 * 全部从 pi 的文件与引擎读出来，**App 不维护第二份表**（`docs/pi-sourced-lists.md`）。
 *
 * ## 为什么需要它
 *
 * 以前设置页里根本没有"已导入的模型"这个东西：`models.json` 的内容只在凭证表单里
 * 按厂商显示一次（`PiCredentialScreen` 的 `existingIds`），而对话页的模型选择器只读
 * `get_available_models`（`ui/chat/ChatSheets.kt`）。于是两个问题都没有答案：
 *
 *  - "我导入过的模型在哪？" —— 没有一处能列出来；
 *  - "它为什么不在模型列表里？" —— 选择器只显示结果，不显示为什么。
 *
 * 第二个问题的答案有且只有三种，本文件把它们区分开（[Status]）：**缺凭证**、**引擎还
 * 没加载**、**已经生效**。前两种在界面上是两句不同的话，也是两条不同的下一步动作。
 *
 * ## 输入全是"文件的原文"，不是路径
 *
 * 四个文件（`models.json`、`auth.json`、`models-store.json`、`settings.json`）由调用者
 * 读成文本后传进来，本对象不碰文件系统。这样它可以进 bare-JVM harness
 * （`tools/run-app-pure-checks.sh` 的 `models-inventory`），而"读哪个目录、用哪个副本"
 * 这条本来就属于 `AgentLayout` 的知识留在 IO 层。
 *
 * ## 与 pi 的对应关系（每个判定都有出处）
 *
 *  - **模型从哪里来**：`models.json` 的 `providers[id].models[]`（**声明**，pi 对同 id 的
 *    条目是整条替换：`core/provider-composer.ts:203-206`）、`providers[id].modelOverrides`
 *    （**覆盖**，合并：`:106-118`）、pi 自己的目录 `<agentDir>/models-store.json`
 *    （`core/model-runtime.ts:180`）、以及引擎的 `get_available_models`
 *    （`modes/rpc/rpc-mode.ts:490-493`）。
 *  - **有没有凭证**：`auth.json` 里有这个厂商的条目（`core/auth-storage.ts:52`，任何类型，
 *    包括 app 不去编辑的 `oauth`），或 `models.json` 的厂商块自带 `apiKey`
 *    （`core/provider-composer.ts:567-580`）。这是**文件层面的近似**：pi 真正用的是
 *    `configuredProviders`（`core/model-runtime.ts:290-313`），其中还包含环境变量——
 *    所以 [Model.status] 优先看引擎的答案，凭证标记只用来解释"为什么引擎里没有"。
 *  - **引擎有没有加载**：`ModelConfig.load` 只在 `ModelRuntime.create`
 *    （`core/model-runtime.ts:176`）与 `ModelRuntime.refresh`（`:699`）里调用，而
 *    `--mode rpc` 下没有任何命令会 refresh（唯一一次是启动期 `main.ts:925-936` 的
 *    `void refresh(...)`）。所以"文件里有、引擎列表里没有、且有凭证"= **要重启引擎**。
 *  - **哪个被启用**：`settings.json` 的 `enabledModels`（`core/settings-manager.ts:139`），
 *    是**模式**（minimatch 语法，对 `provider/modelId` 或裸 id 匹配，见
 *    `core/model-resolver.ts:311-317`），不是 id 清单。
 *  - **哪个是默认**：`defaultProvider` / `defaultModel`（`core/settings-manager.ts:108-109`）。
 *
 * ## 有意偏离 / 够不着的地方
 *
 *  - **glob 实现 `*`、`?` 与 `[...]` 字符类**，与 pi 的选择一致（`core/model-resolver.ts:291`
 *    把 `*`/`?`/`[` 任一都当作 glob）；扩展 minimatch 的其余语法（`{}`/`!`/`()`）不实现，
 *    遇到就不猜：它进 [Inventory.unjudgedPatterns]，界面照着说"还有 N 条规则无法在这里
 *    判断"，而不是把它当成"没启用"。
 *  - **没有 glob 字符的模式按 pi 的"子串"回退**（`core/model-resolver.ts:338` →
 *    `tryMatchModel` 的 `id.includes(pattern)`）。原先这里只做精确匹配，于是
 *    `enabledModels: ["sonnet"]` 这种手写模式在本页显示成"没启用"，而 pi 其实选中了它。
 *    pi 还会拿模型 **name** 做同样的子串匹配，本页的这条判据只有 id/provider（`Model` 的
 *    名字在另一条读取路径上），所以 name 那一半够不着——这是**少报**而不是多报。
 *  - **`:思考等级` 后缀**照 pi 的解析剥掉（`core/model-resolver.ts:295-301`），只影响
 *    匹配，不改写用户的值。
 *  - **项目级 `settings.json`** 与全局的合并只做这三个键：它们分别是标量/数组，而 pi 的
 *    合并对数组是**替换**（`PiSettingsFileStore` 头部），所以"项目有就用项目的"与 pi 的
 *    深合并在这三个键上**结果相同**，不需要引入一份合并实现。
 *  - [Status.PENDING_RESTART] 是**推断**而不是 pi 的答案：RPC 没有任何命令能回答"引擎
 *    读过这个文件了吗"，可观测的只有"引擎现在列不列它"。这个方法在两种情况下会误报成
 *    "要重启"：凭证其实不可用（例如 `models.json` 里写的是 `$ENV_VAR` 而变量没设），
 *    以及 `models.json` 本身解析失败（那种情况由 [Inventory.modelsJsonError] 单独说明）。
 */
object PiModelInventory {

    /** 一条模型定义的来源。可以同时有多个——它们是叠起来的，不是互斥的。 */
    enum class Origin {
        /** pi 自己的目录 `models-store.json` 里有它。 */
        PI_CATALOG,

        /** `models.json` 的 `providers[id].models[]` 里手写了它（pi 视为整条替换）。 */
        DECLARED,

        /** `models.json` 的 `providers[id].modelOverrides` 里覆盖了它（pi 视为合并）。 */
        OVERRIDDEN,
    }

    /** 这个模型现在处于哪一步。三种"不在引擎的列表里"的原因必须分开说。 */
    enum class Status {
        /** 引擎的 `get_available_models` 里有它：现在就能用。 */
        READY,

        /** 有凭证、文件里也写好了，但引擎的列表里没有 —— 引擎还没重新读文件。 */
        PENDING_RESTART,

        /** 这个厂商没有凭证，pi 因此不列出它的任何模型（`docs/models.md:34-36`）。 */
        MISSING_CREDENTIAL,

        /** 引擎的列表读不到（引擎没在跑），于是"在不在里面"无法判断。 */
        UNKNOWN,
    }

    /** 引擎的一个回答项，形状取自 `get_available_models` 的 `{provider, id, name}`。 */
    data class EngineModel(val providerId: String, val id: String, val name: String? = null)

    data class Model(
        val providerId: String,
        val id: String,
        val name: String?,
        val origins: Set<Origin>,
        /** 命中 `enabledModels` 里的某条模式：Ctrl+P 循环时算它一个。 */
        val enabled: Boolean,
        val isDefault: Boolean,
        val status: Status,
    )

    data class Provider(
        val id: String,
        /** `models.json` 里的 `name`；没有就是 null，界面自己决定怎么称呼它。 */
        val name: String?,
        val api: String?,
        val baseUrl: String?,
        /** `auth.json` 里有条目，或厂商块自带 `apiKey`。 */
        val hasCredential: Boolean,
        /** `models.json` 里有这个厂商的块（App 或用户手工申报过）。 */
        val configured: Boolean,
        val models: List<Model>,
    )

    /** `settings.json` 里那三个"选择"键（`core/settings-manager.ts:108-139`）。 */
    data class Selection(
        val defaultProvider: String? = null,
        val defaultModel: String? = null,
        val enabledPatterns: List<String> = emptyList(),
    )

    data class Inventory(
        val providers: List<Provider>,
        /** 非 null 表示 `models.json` 读不了/看不懂；文案里没有路径（用户可见）。 */
        val modelsJsonError: String?,
        val authJsonError: String?,
        /** 引擎的回答是否拿到了。false 时所有状态是 [Status.UNKNOWN]。 */
        val engineKnown: Boolean,
        val selection: Selection,
        /** 含本实现不支持的 glob 语法、因而无法判断的模式。 */
        val unjudgedPatterns: List<String>,
    ) {
        val models: List<Model> get() = providers.flatMap { it.models }

        fun count(status: Status): Int = models.count { it.status == status }

        /** 有几个厂商"文件里写好了但引擎还没加载" —— 重启横幅用的就是它。 */
        val providersPendingRestart: List<Provider>
            get() = providers.filter { provider -> provider.models.any { it.status == Status.PENDING_RESTART } }
    }

    // ------------------------------------------------------------------ 组装

    /**
     * 把四份文件原文 + 引擎的回答合成一张清单。
     *
     * @param engineModels null 表示**读不到引擎的回答**（引擎没在跑），与"引擎回答了一个
     *        空列表"是两件事：前者所有状态 [Status.UNKNOWN]，后者是正常的"还没有任何可用
     *        模型"。这个区分不能用空列表代替。
     */
    fun assemble(
        modelsJson: String?,
        authJson: String?,
        catalogJson: String?,
        selection: Selection = Selection(),
        engineModels: List<EngineModel>? = null,
    ): Inventory {
        val modelsRead = readProviders(modelsJson)
        val authRead = readCredentials(authJson)
        val catalog = readCatalog(catalogJson)
        val engine = engineModels?.map { it.providerId to it.id }?.toSet()
        val engineNames = engineModels
            ?.associate { model -> (model.providerId to model.id) to model.name }
            .orEmpty()
        val engineIdsByProvider = engineModels?.groupBy({ it.providerId }, { it.id }).orEmpty()

        val providerIds = LinkedHashSet<String>()
        providerIds += authRead.providerIds
        providerIds += modelsRead.blocks.keys
        providerIds += engineIdsByProvider.keys

        val providers = providerIds.sortedWith(compareBy<String>({ it.lowercase() }, { it })).map { providerId ->
            val block = modelsRead.blocks[providerId]
            val declared = declaredModels(block)
            val overridden = overrideIds(block)
            val catalogModels = catalog[providerId].orEmpty()
            val catalogIds = catalogModels.map { it.id }
            val engineIds = engineIdsByProvider[providerId].orEmpty()

            val hasCredential = providerId in authRead.providerIds || block?.get("apiKey") != null

            val ids = LinkedHashSet<String>()
            ids += declared.map { it.id }
            ids += overridden
            ids += catalogIds
            ids += engineIds

            val declaredById = declared.associateBy { it.id }
            val catalogById = catalogModels.associateBy { it.id }

            val models = ids
                .filter { it.isNotBlank() }
                .sortedWith(compareBy<String>({ it.lowercase() }, { it }))
                .map { id ->
                    val origins = buildSet {
                        if (id in catalogIds) add(Origin.PI_CATALOG)
                        if (id in declaredById) add(Origin.DECLARED)
                        if (id in overridden) add(Origin.OVERRIDDEN)
                    }
                    Model(
                        providerId = providerId,
                        id = id,
                        name = declaredById[id]?.name
                            ?: catalogById[id]?.name
                            ?: engineNames[providerId to id],
                        origins = origins,
                        enabled = selection.enabledPatterns.any { matches(it, providerId, id) },
                        isDefault = isDefaultModel(selection, providerId, id),
                        // `engine != null &&` in front of the membership test is not
                        // redundant: inside the right-hand side of `&&` the compiler
                        // smart-casts `engine` to non-null, while a subjectless
                        // `when { engine == null -> …; x in engine -> }` does not narrow.
                        status = when {
                            engine == null -> Status.UNKNOWN
                            engine != null && (providerId to id) in engine -> Status.READY
                            !hasCredential -> Status.MISSING_CREDENTIAL
                            else -> Status.PENDING_RESTART
                        },
                    )
                }

            Provider(
                id = providerId,
                name = (block?.get("name") as? JsonPrimitive)?.takeIf { it.isString }?.content,
                api = (block?.get("api") as? JsonPrimitive)?.takeIf { it.isString }?.content,
                baseUrl = (block?.get("baseUrl") as? JsonPrimitive)?.takeIf { it.isString }?.content,
                hasCredential = hasCredential,
                configured = block != null,
                models = models,
            )
        }

        return Inventory(
            providers = providers,
            modelsJsonError = modelsRead.error,
            authJsonError = authRead.error,
            engineKnown = engineModels != null,
            selection = selection,
            unjudgedPatterns = selection.enabledPatterns.filter { hasUnsupportedGlobSyntax(it) },
        )
    }

    // -------------------------------------------------------- settings 的三个键

    /**
     * `defaultProvider` / `defaultModel` / `enabledModels`，项目级覆盖全局。
     *
     * 只做这三个键，理由见文件头部：在这里深合并与"项目有就赢"是同一件事。
     */
    fun selection(globalSettings: String?, projectSettings: String? = null): Selection {
        val global = parseObject(globalSettings)
        val project = parseObject(projectSettings)
        fun value(key: String) = (project?.takeIf { it.containsKey(key) } ?: global)?.get(key)
        val provider = (value("defaultProvider") as? JsonPrimitive)?.takeIf { it.isString }?.content
        val model = (value("defaultModel") as? JsonPrimitive)?.takeIf { it.isString }?.content
        val patterns = (value("enabledModels") as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            .orEmpty()
        return Selection(provider, model, patterns)
    }

    // ------------------------------------------------------------------ 模式匹配

    /**
     * `enabledModels` 的一条模式是否命中这个模型。
     *
     * pi 的规则（`core/model-resolver.ts:281-361` `resolveModelScopeFromModels`）：
     *
     *  1. 含 `*`/`?`/`[` 的模式 → 先试精确引用，再对 `provider/modelId` **或**裸 `modelId`
     *     做大小写不敏感的 minimatch；`*` 不跨 `/`（minimatch 的默认），这一条照做。
     *  2. 不含 glob 字符的模式 → `parseModelPattern` → `tryMatchModel`：先精确，再**子串**
     *     （`m.id.toLowerCase().includes(pattern)`；pi 还看 name，本方法只看得到 id/provider）。
     *
     * 其余语法（`{}`/`!`/`()`…）由 [hasUnsupportedGlobSyntax] 标出来，而不是猜。
     */
    fun matches(pattern: String, providerId: String, modelId: String): Boolean {
        val trimmed = pattern.trim()
        if (trimmed.isEmpty()) return false
        val glob = stripThinkingSuffix(trimmed)
        if (glob.isEmpty()) return false
        val fullId = "$providerId/$modelId"
        if (glob.equals(fullId, ignoreCase = true)) return true
        if (glob.equals(modelId, ignoreCase = true)) return true
        val hasGlobSyntax = glob.any { it == '*' || it == '?' || it == '[' }
        if (!hasGlobSyntax) {
            // pi 的"部分匹配"回退；`ignoreCase` 与 minimatch 的 `nocase: true` 一致。
            return fullId.contains(glob, ignoreCase = true) || modelId.contains(glob, ignoreCase = true)
        }
        if (hasUnsupportedGlobSyntax(glob)) return false
        val regex = globToRegex(glob) ?: return false
        return regex.matches(fullId) || regex.matches(modelId)
    }

    /**
     * 这条模式用了本实现不支持的 minimatch 语法（`{}`/`!`/`()`/`+`/`@`/`|`）。
     * 界面据此说"无法在这里判断"，而不是把它当成"没启用"。
     *
     * `[...]` **不在**这里：pi 把 `[` 当作 glob 的起始（`core/model-resolver.ts:291`），
     * [globToRegex] 已经实现字符类。
     */
    fun hasUnsupportedGlobSyntax(pattern: String): Boolean {
        val glob = stripThinkingSuffix(pattern.trim())
        var index = 0
        while (index < glob.length) {
            val ch = glob[index]
            if (ch == '[') {
                // 字符类整体跳过：`[!abc]` 里的 `!` 是**取反**（minimatch 与正则都这样），
                // 不是 extglob 的否定号，不能按"不支持"处理。
                val close = glob.indexOf(']', index + 1)
                if (close <= index + 1) return true
                index = close + 1
                continue
            }
            if (ch in "{}()!+@|\\") return true
            index++
        }
        return false
    }

    /** `sonnet:high` → `sonnet`；后缀不是思考等级时原样保留（pi 只在有效等级时剥）。 */
    private fun stripThinkingSuffix(pattern: String): String {
        val colon = pattern.lastIndexOf(':')
        if (colon <= 0) return pattern
        val suffix = pattern.substring(colon + 1)
        return if (suffix.lowercase() in THINKING_LEVELS) pattern.substring(0, colon) else pattern
    }

    /**
     * minimatch 的一个近似：`*` 与 `?` **不跨 `/`**（minimatch 默认），`[...]` 是字符类
     * （`[!abc]` / `[^abc]` 取反）。pi 对 `provider/modelId` 与裸 id 各匹配一次，所以
     * 「星号不跨斜杠」正是它能把带厂商前缀的通配写法与裸 id 两种写法都服务好的原因。
     */
    private fun globToRegex(glob: String): Regex? {
        val out = StringBuilder("^")
        var index = 0
        while (index < glob.length) {
            when (val ch = glob[index]) {
                '*' -> out.append("[^/]*")
                '?' -> out.append("[^/]")
                '[' -> {
                    val close = glob.indexOf(']', index + 1)
                    if (close <= index + 1) {
                        // 没有闭合的 `[`：minimatch 会把它当普通字符。
                        out.append(Regex.escape("["))
                        index++
                        continue
                    }
                    val body = glob.substring(index + 1, close)
                    val negated = body.startsWith("!") || body.startsWith("^")
                    val characters = if (negated) body.substring(1) else body
                    out.append('[')
                    if (negated) out.append('^')
                    out.append(characters.replace("\\", "\\\\"))
                    out.append(']')
                    index = close + 1
                    continue
                }

                else -> out.append(Regex.escape(ch.toString()))
            }
            index++
        }
        out.append('$')
        return runCatching { Regex(out.toString(), RegexOption.IGNORE_CASE) }.getOrNull()
    }

    private fun isDefaultModel(selection: Selection, providerId: String, modelId: String): Boolean {
        if (selection.defaultProvider != null && !selection.defaultProvider.equals(providerId, ignoreCase = true)) {
            return false
        }
        val configured = selection.defaultModel ?: return false
        return configured.equals(modelId, ignoreCase = true) ||
            configured.equals("$providerId/$modelId", ignoreCase = true)
    }

    // ------------------------------------------------------------------ 读文件

    private class Declared(val id: String, val name: String?)

    private class ProvidersRead(val blocks: Map<String, JsonObject>, val error: String?)

    private class CredentialsRead(val providerIds: Set<String>, val error: String?)

    private class CatalogEntry(val id: String, val name: String?)

    private fun readProviders(text: String?): ProvidersRead {
        if (text == null || text.isBlank()) return ProvidersRead(emptyMap(), null)
        val document = parseObject(text)
            ?: return ProvidersRead(emptyMap(), "模型配置读不出来：不是有效的 JSON")
        val providers = document["providers"] as? JsonObject
            ?: return ProvidersRead(emptyMap(), "模型配置缺少厂商清单")
        return ProvidersRead(providers.mapValues { (_, value) -> value as? JsonObject ?: JsonObject(emptyMap()) }, null)
    }

    /**
     * `auth.json` 里出现过的厂商 id，**不挑类型**。
     *
     * 挑类型是错的：`oauth` 条目同样是"这个厂商配过了"，而 App 只是不编辑它
     * （`PiAuthStorage.read` 的注释）。pi 的 `storedProviders` 也是按"有没有条目"算的
     * （`core/model-runtime.ts:305`）。
     */
    private fun readCredentials(text: String?): CredentialsRead {
        if (text == null || text.isBlank()) return CredentialsRead(emptySet(), null)
        val document = parseObject(text)
            ?: return CredentialsRead(emptySet(), "凭证文件读不出来：不是有效的 JSON")
        return CredentialsRead(
            document.filterValues { it is JsonObject }.keys,
            null,
        )
    }

    private fun readCatalog(text: String?): Map<String, List<CatalogEntry>> {
        val document = parseObject(text) ?: return emptyMap()
        return document.mapValues { (_, value) ->
            val models = (value as? JsonObject)?.get("models") as? JsonArray ?: return@mapValues emptyList()
            models.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val id = (obj["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (id.isNullOrBlank()) return@mapNotNull null
                CatalogEntry(id, (obj["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content)
            }
        }
    }

    private fun declaredModels(block: JsonObject?): List<Declared> {
        val models = block?.get("models") as? JsonArray ?: return emptyList()
        return models.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = (obj["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (id.isNullOrBlank()) return@mapNotNull null
            Declared(id, (obj["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content)
        }
    }

    private fun overrideIds(block: JsonObject?): Set<String> =
        (block?.get("modelOverrides") as? JsonObject)?.keys.orEmpty()

    /** `models.json` 允许注释（`core/model-config.ts:267` 先 strip 再解析）。 */
    private fun parseObject(text: String?): JsonObject? {
        if (text == null || text.isBlank()) return null
        return runCatching {
            Json.parseToJsonElement(PiJsonComments.strip(text)) as? JsonObject
        }.getOrNull()
    }

    /** `cli/args.ts:60` 的 `VALID_THINKING_LEVELS`，逐字一致。 */
    private val THINKING_LEVELS = setOf("off", "minimal", "low", "medium", "high", "xhigh", "max")
}
