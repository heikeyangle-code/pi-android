package app.pi.packages

import app.pi.runtime.PiProjectConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * The whole "paste a key" flow, in one place: what is already configured, what a
 * probe says, and what gets written.
 *
 * ## Order of writes, and why that order
 *
 * 1. `auth.json` — the credential, `0600`, under pi's lock.
 * 2. `models.json` — the provider block and the chosen models (`id` only required by
 *    pi's schema; see [PiModelsFile]).
 * 3. `settings.json` — the *selection*: `defaultProvider`, `defaultModel`,
 *    `enabledModels`, under pi's lock.
 *
 * Credential first is not arbitrary. If step 2 or 3 fails after step 1 succeeded, the
 * user has a working credential and no provider block — recoverable, and the error
 * names the file. The reverse order would leave a provider block pointing at a
 * credential that does not exist, which pi reports as "not configured" with no hint
 * that a write was interrupted.
 *
 * Each step reports its own outcome, so a partial save is described as partial
 * rather than as success.
 *
 * ## Restart
 *
 * After a successful save the caller **must** offer a restart, for the same reason
 * extension installs do, and for one specific additional reason here: `models.json`
 * is read by `ModelConfig.load`, which is called from `ModelRuntime.refresh()`
 * (`core/model-runtime.ts:699`) and from `ModelRuntime.create` at startup (`:176`).
 * Nothing on the RPC surface calls `refresh` — `get_available_models` returns a
 * cached snapshot (`modes/rpc/rpc-mode.ts:490-493` → `core/model-runtime.ts:422-424`)
 * — so a new provider does not appear until the process restarts. (pi's docs say
 * `models.json` "reloads each time you open `/model`" (`docs/models.md:92`); that is
 * true of the **TUI**, which triggers a refresh, and false over `--mode rpc`.)
 *
 * `auth.json` **also** needs the restart, and the reason is worth stating because the
 * obvious reading is wrong: pi's credential reader *is* revision-checked
 * (`auth-storage.ts:341-342`, `getFileRevision`), so pi would read an externally
 * added key — but the list `get_available_models` answers from is
 * `snapshot.available`, which is only rebuilt by an availability refresh
 * (`model-runtime.ts:290-313`), and nothing on the RPC surface triggers one
 * (`rpc-mode.ts` calls only the `getAvailableSnapshot()` getter, at `:473` and
 * `:491`). Reading the new key and *offering* the model are two different steps, and
 * over RPC only a restart performs the second.
 *
 * ## A blank key means "keep the stored one"
 *
 * Editing an existing provider used to require re-pasting its key: the field was the
 * only source of the credential, so a blank one failed with "API Key 不能为空". That
 * made "add one model to a provider I already configured" — the common case this
 * screen exists for — impossible without the secret at hand. So:
 *
 *  - key typed → it replaces the stored credential;
 *  - key left blank **and** the provider already has a credential of any kind (including
 *    an `oauth` entry this app does not edit) → `auth.json` is not touched at all;
 *  - key left blank, no credential, keyless endpoint → pi's placeholder is written;
 *  - key left blank, no credential → refused, with the reason.
 */
class PiCredentialService(
    private val layout: AgentLayout,
    private val workspace: File,
    private val scanner: PiModelScanner = PiModelScanner(),
) {

    private val truthAgentDir: File get() = layout.agentTruthDir
    private val mirrorAgentDir: File get() = layout.agentMirrorDir

    private fun auth() = PiAuthStorage(
        file = PiConfigFiles.authFile(truthAgentDir),
        mirror = PiConfigFiles.authFile(mirrorAgentDir),
    )

    private fun models() = PiModelsFile(
        file = PiConfigFiles.modelsFile(truthAgentDir),
        mirror = PiConfigFiles.modelsFile(mirrorAgentDir),
    )

    /**
     * The selection file must be the one pi reads, i.e. the **durable** agent dir:
     * `PiEngineHost` binds it over guest `/root/.pi/agent` (`PiEngineHost.kt:285-294`),
     * and the app's own settings store addresses the same directory
     * (`PiSessionViewModel.kt:325-328` reads `host.paths().agentDir`). Writing the
     * rootfs copy instead produced a save that reported success and changed nothing
     * visible: `defaultProvider`/`defaultModel` landed in a file the bind shadows.
     * (`auth.json` and `models.json` below write both copies, which is why only this
     * step had the bug.)
     */
    private fun preferences() = PiEnginePreferences(agentDir = mirrorAgentDir, workspace = workspace)

    /**
     * pi's persisted catalog for [providerId] — `<agentDir>/models-store.json`
     * (`core/model-runtime.ts:180` + `core/agent-session-services.ts:142`) — or an
     * empty list when pi has none yet.
     *
     * This is the app's **capabilities source that exists before the credential
     * does**: `get_available_models` is auth-filtered (`docs/models.md:34-36`), so at
     * the moment a provider is being added it answers nothing, while pi's catalog
     * already describes the models. It feeds the form and the display; it does **not**
     * decide what gets written — [save] decides that with `preset.builtInPi` alone.
     *
     * Read from the agent dir pi actually uses ([mirrorAgentDir]), the one the engine
     * binds over guest `/root/.pi/agent`; the rootfs copy is shadowed by that bind.
     */
    fun catalogModels(providerId: String): List<PiModelCatalog.Entry> {
        val text = runCatching { File(mirrorAgentDir, "models-store.json").readText() }.getOrNull()
            ?: return emptyList()
        return PiModelCatalog.parse(text, providerId)
    }

    /**
     * pi **官方随包发的**模型目录（`<rootfs>/opt/pi/…/pi-ai/dist/providers/data`）——
     * 提供商列表、每个提供商的模型与元数据的唯一来源；App 不再自己维护一张表（用户裁定：
     * 「不能直读官方文件展示出来吗？自己不维护了不行吗？」）。
     *
     * 路径来自 `tools/fetch-runtime.mjs` 的 `{ name: "pi-engine", prefix: "rootfs/opt/pi" }`：
     * 载荷解包后 pi 的 npm 树就在 `<rootfs>/opt/pi` 下。`pi-ai` 的目录数据**在树的哪一层
     * 由 npm 的依赖提升决定、而且变过**（1.3.0 起是顶层 `node_modules/@earendil-works/pi-ai`，
     * 之前嵌在 `pi-coding-agent/node_modules` 下），所以这里交给
     * [PiOfficialCatalog.readFromRootfs] 按候选列表找，而不是写死一种布局 ——
     * 写死的那种在载荷升级后会整张表读不到，屏幕上的厂商就只剩 App 自带的几档。
     *
     * **运行时还没解包时返回一条 problem**（调用方必须显示，且里面带着试过的每个位置），
     * 而不是一个空列表 —— 空列表会被读成"pi 没有提供商"。
     */
    fun officialCatalog(): PiOfficialCatalog.Catalog =
        PiOfficialCatalog.readFromRootfs(layout.paths.rootfs)

    // ------------------------------------------------------------- models.dev 索引
    //
    // 厂商的 `/models` 没有一家回思考档位（`thinkingLevelMap` 是 pi 自己的档位建模）。
    // models.dev 是 pi 目录的上游，有 `reasoning_options`，覆盖 232 家 —— 这就是"所有厂商
    // 都能自动拿到档位"的数据源。缓存 7 天，只在厂商/目录都没数据时才查，失败就是 null，
    // 不影响导入。

    /** `<files>/models-dev.json`：整份 api.json（约 6 MB）的缓存。 */
    private val modelsDevCache: File get() = File(layout.paths.filesDir, "models-dev.json")

    /** 7 天当"够新"：模型目录是月级变化，过期就下次联网再取一份。 */
    private val modelsDevMaxAgeMs = 7L * 24 * 60 * 60 * 1000

    /** 缓存里的索引；没有、过期或读不动都是 null（调用方自己决定要不要联网）。 */
    fun modelsDevIndex(): Pair<Map<String, PiModelsDev.Entry>, Map<String, PiModelsDev.Entry>>? {
        val file = modelsDevCache
        if (!file.isFile) return null
        if (System.currentTimeMillis() - file.lastModified() > modelsDevMaxAgeMs) return null
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        return runCatching { PiModelsDev.parse(text) }.getOrNull()
    }

    /**
     * 取一份可用的 models.dev 索引：缓存新鲜就读缓存，否则联网下载整份 `api.json`
     * 再落盘。**失败返回 null，绝不抛** —— 这个数据只是补全能力，断网/服务挂了不该
     * 让导入失败。
     */
    /** 失败后的冷却：断网时别让每次导入都等一次超时。 */
    private var modelsDevFailedAt = 0L

    fun ensureModelsDevIndex(): Pair<Map<String, PiModelsDev.Entry>, Map<String, PiModelsDev.Entry>>? {
        modelsDevIndex()?.let { return it }
        val now = System.currentTimeMillis()
        if (now - modelsDevFailedAt < MODELS_DEV_RETRY_MS) return null
        val text = runCatching {
            val connection = (URL("https://models.dev/api.json").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 20_000
                readTimeout = 60_000
                instanceFollowRedirects = true
            }
            val status = connection.responseCode
            val body = if (status in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                null
            }
            connection.disconnect()
            body
        }.getOrNull()
        if (text == null || text.length < 100_000) {
            modelsDevFailedAt = now
            return null
        }
        val index = runCatching { PiModelsDev.parse(text) }.getOrNull()
        if (index == null || index.second.isEmpty()) {
            modelsDevFailedAt = now
            return null
        }
        runCatching {
            val temp = File(modelsDevCache.parentFile, "${modelsDevCache.name}.tmp")
            temp.writeText(text)
            if (!temp.renameTo(modelsDevCache)) {
                modelsDevCache.writeText(text)
                temp.delete()
            }
        }
        modelsDevFailedAt = 0L
        return index
    }

    private companion object {
        /** 一次失败后 10 分钟内不再试：断网时 6 MB 的重试只会拖慢导入。 */
        const val MODELS_DEV_RETRY_MS = 10L * 60 * 1000
    }

    // ------------------------------------------------------- 单条模型（导入后也能改）
    //
    // 模型清单一屏里点一条模型，改的就是 `models.json` 的 `models[]` 里那一条。这与导入
    // 保存是同一条语义 —— 写一条同 id 的定义，pi 就把目录里那条整条换掉
    // （`provider-composer.ts:203-206`），所以调用方必须把**知道的全套能力**都带上，
    // 只带用户改的那一项等于把其余能力清零（§M11 那个事故）。写成功即记账"等待重启"：
    // 引擎快照不会自己刷新。

    /** 这个厂商申报过的模型定义（编辑 sheet 预填、判断"是不是官方目录里的"都用它）。 */
    fun declaredModels(providerId: String): List<PiModelsFile.Model> =
        models().declaredModels(providerId)

    /** 一条模型现在申报的定义；没申报过 = pi 目录/默认值在管它。 */
    fun declaredModel(providerId: String, modelId: String): PiModelsFile.Model? =
        models().declaredModels(providerId).firstOrNull { it.id == modelId }

    /** 写一条模型的能力。@return null 成功，否则一句给人看的话。 */
    fun updateModel(providerId: String, model: PiModelsFile.Model): String? {
        models().upsertModel(providerId, model)?.let { return it }
        PendingModelRestart.mark()
        return null
    }

    /** 去掉一条申报，回到 pi 目录里的定义（目录里也没有 = 回到 pi 的默认值）。 */
    fun resetModel(providerId: String, modelId: String): String? {
        models().removeDeclaredModel(providerId, modelId)?.let { return it }
        PendingModelRestart.mark()
        return null
    }

    fun prefill(presetId: String?): Existing {
        val authRead = auth().read()
        val authError = (authRead as? PiAuthStorage.Read.Invalid)?.message
        val keys = (authRead as? PiAuthStorage.Read.Ok)?.entries ?: emptyMap()
        val snapshot = models().read()
        val block = presetId?.let { snapshot.providers[it] as? kotlinx.serialization.json.JsonObject }
        val (currentProvider, currentModel) = preferences().current()
        val id = presetId ?: currentProvider
        return Existing(
            providerId = currentProvider,
            modelId = currentModel,
            enabledModelIds = emptyList(),
            maskedKey = id?.let { keys[it]?.key }?.let { PiAuthStorage.mask(it) },
            credentialPresent = id != null && auth().hasAnyEntry(id),
            configuredProviderIds = keys.keys.sorted(),
            configuredModelIds = id?.let { models().configuredModelIds(it) } ?: emptyList(),
            baseUrl = (block?.get("baseUrl") as? kotlinx.serialization.json.JsonPrimitive)?.content,
            api = (block?.get("api") as? kotlinx.serialization.json.JsonPrimitive)?.content,
            modelsFileError = snapshot.error,
            authFileError = authError,
        )
    }

    // ----------------------------------------------------------------- probe

    /**
     * One step, two outcomes: the key is valid (and here are the models), or here is
     * exactly what went wrong and what to do. There is deliberately **no separate
     * "test connection" button** — a successful listing is the proof, and asking the
     * user to prove it twice is the interaction this replaces.
     *
     * [apiKey] 留空时用 `auth.json` 里**已保存的那条**去探测。导入 sheet 把已配置厂商的 Key
     * 收成一行（「已存凭证 · 留空＝沿用」），而「检测并扫描模型」仍是常驻按钮 —— 拿空 Key 去
     * 探测只会得到 401，"已配置的厂商"反而不能扫描。留空 = 沿用文件里那条，与 [save] 对空
     * Key 的语义是同一条。
     */
    suspend fun probe(
        preset: PiProviderPresets.Preset,
        apiKey: String,
        baseUrl: String? = null,
    ): PiModelScanner.Result {
        // 粘贴来的 Key 常带换行/空格：不 trim 会原样写进 auth.json，pi 拿它去请求就是 401。
        val key = apiKey.trim().ifBlank {
            (auth().read() as? PiAuthStorage.Read.Ok)?.entries?.get(preset.id)?.key.orEmpty()
        }
        return scanner.scan(preset, key, baseUrl)
    }

    // ------------------------------------------------------------------ save

    /**
     * One model the user chose in the credential form.
     *
     * The fields are the *vendor's* facts as far as the app could resolve them (pi's
     * catalog, then the engine's list). They are written only for providers pi ships
     * no catalog for — see [save]. There is deliberately no "are these the app's
     * defaults" flag: the decision does not depend on how much the app knows, only on
     * whether pi already knows the model.
     */
    data class ModelChoice(
        val id: String,
        val name: String? = null,
        val reasoning: Boolean? = null,
        val contextWindow: Long? = null,
        val maxTokens: Long? = null,
        val input: List<String> = emptyList(),
        /**
         * USD **per million tokens** —— pi 的 `models.json` 就按这个单位读 `cost`，App 的
         * 模型选择器也按 `$x / M` 显示。扫描器负责把厂商的 per-token 价换算过来。
         */
        val costInput: Double? = null,
        val costOutput: Double? = null,
        val costCacheRead: Double? = null,
        val costCacheWrite: Double? = null,
        /** 厂商公布的默认采样参数（pi 的 `samplingParams`）；没给就是 null。 */
        val samplingParams: JsonObject? = null,
        /** pi 的思考档位映射；models.dev 有 `reasoning_options` 时才有。 */
        val thinkingLevelMap: JsonObject? = null,
    )

    data class SaveResult(
        val ok: Boolean,
        /** One line per file, in the order written, for the outcome sheet. */
        val steps: List<String>,
        /** Non-null when a restart is required to make this take effect. */
        val restart: PiPackageService.RestartRequired?,
    )

    /**
     * Write everything: `auth.json` → `models.json` → 勾选有变化时 `settings.json` 的
     * `enabledModels`。
     *
     * `defaultProvider`/`defaultModel` **这一条路一个都不写**：默认模型在「模型与供应商」
     * 清单页的「默认模型」那一行里改（`DefaultModelPairStore` 一次写两个键），导入流程
     * 只负责"这个厂商有哪些模型、循环范围是哪些"。旧版把三个键绑成一次无条件写入
     * （`modelId ?: choices.first().id`），于是"给已有厂商加一个模型"会顺手切默认、
     * "导入第二个厂商"会冲掉第一个厂商的循环条目和用户手写的 pattern —— 用户报的
     * 「导入完了也有 bug」（B1）。`enabledModels` 要不要写、写什么，判定全在
     * [ModelSelectionPlan]。
     */
    fun save(
        preset: PiProviderPresets.Preset,
        apiKey: String,
        baseUrl: String,
        api: String,
        choices: List<ModelChoice>,
        /**
         * 打开表单时用户**看到的**初始勾选集合 —— [ModelSelectionPlan] 判「动没动勾选」
         * 的对账基准。**不是** `models.json` 的声明：官方厂商从不申报（`declared` 恒空），
         * 拿声明对账会把每次保存都算成"动了"，于是循环列表次次被重写、用户上次排除的
         * 模型被静默勾回来。
         */
        configuredModelIds: Set<String> = emptySet(),
        /**
         * pi 官方目录（`models-store.json` + 随包 `providers/data` 下那些 `<provider>.json`）
         * 已经认识的本厂商
         * 模型 id。
         *
         * 命中这些的**不写进 `models[]`**：pi 的语义是同 id 整体替换
         * （`provider-composer.ts:203-206`），写一条只会把官方数据换成我们手上的副本 ——
         * 价格归零、上下文退回默认值。用户裁定："pi 官方配置文件有数据的，就用 pi 官方的
         * 数据；pi 暂时没有的，才用这次 API 扫到的数据。"
         */
        officiallyKnownIds: Set<String> = emptySet(),
        /**
         * 官方目录这次**到底读到没有**（[officiallyKnownIds] 非空不等于读到了：目录可能
         * 真的一个模型都没有）。
         *
         * 读到了 → 只申报官方不认识的 id：一个**不在目录里的新模型**是"增加"，不是"替换"，
         * 不申报它就永远出不来（它只会进 `enabledModels`，而 pi 解析不到任何模型）。
         * 没读到 + pi 自带厂商 → 退回什么都不申报（写一条就是把官方数据换成默认值）。
         */
        officialCatalogRead: Boolean = false,
        /**
         * 用户在**导入表单里改过能力**的模型 id：这些必须申报，即使它在官方目录里
         * （目录里的那条整条替换，只有 `models[]` 里的一条才承载得起这次修改）。
         * 传给它的选择项必须带全能力字段，否则缺的会被 pi 的默认值补上。
         */
        forceDeclared: Set<String> = emptySet(),
    ): SaveResult {
        val steps = mutableListOf<String>()
        // 保存前 `models.json` 里为这个厂商申报过哪些模型：`declared` 为空时下面的 upsert 会
        // **删掉 `models` 这个键**（`PiModelsMerge.provider` 的 ABSENCE_IS_A_STATEMENT），
        // 而用户最常见的动作是「已有模型、只换一个 Key」—— 没有任何提示就把手写定义清掉，
        // 那是数据丢失，不是"沿用官方目录"。所以保存后要把这句话说出来。
        val existingDeclared = models().configuredModelIds(preset.id)
        // 空选择只对「本 App 申报模型」的厂商是错误：那里 `models[]` 是唯一定义，一个都
        // 不勾等于写一个没有模型的空壳。pi 自带目录的厂商不申报任何模型（见下），"只加
        // 一个 Key、别的都不动"是完全合法的保存 —— 以前这里一视同仁地拒绝，把最常见的
        // 「已有模型、换个厂商补凭证」挡在了门外。
        if (choices.isEmpty() && !preset.builtInPi) {
            return SaveResult(false, steps + "没有选择任何模型", null)
        }
        // 留空 = 不动凭证（见类头部）。只有在"本来就没有凭证"时才是一个错误——否则编辑一个
        // 已配好的厂商会被迫重新粘贴 Key，而这一步和"加一个模型"毫无关系。
        val storedCredential = auth().hasAnyEntry(preset.id)
        val typedKey = apiKey.trim()
        val effectiveKey: String? = when {
            typedKey.isNotEmpty() -> typedKey
            storedCredential -> null
            preset.scanStyle == PiProviderPresets.ScanStyle.Keyless -> PiProviderPresets.KEYLESS_PLACEHOLDER
            else -> return SaveResult(false, steps + "这个厂商还没有保存过凭证，请填 API Key", null)
        }
        if (effectiveKey != null) {
            auth().setApiKey(preset.id, effectiveKey)?.let { error ->
                return SaveResult(false, steps + error, null)
            }
            steps += if (typedKey.isNotEmpty()) {
                "凭证已保存：${preset.id}"
            } else {
                // pi 会隐藏没有凭证的厂商（`docs/models.md:34-36`），所以无鉴权端点也要写一个占位值。
                "本地端点无鉴权，已按 pi 的要求写入占位 Key（${PiProviderPresets.KEYLESS_PLACEHOLDER}）"
            }
        } else {
            steps += "沿用已保存的凭证，未改动"
        }

        // ---- the one rule of this write --------------------------------------
        //
        // `models.json` declares **only what pi cannot know**.
        //
        // It is one rule with three fields, not just `models[]`: `name`, `baseUrl` and `api`
        // for a provider pi ships are also pi's own facts, and a copy of one goes stale the day
        // upstream changes it — pi 1.3.0 changed `kimi-coding`'s `baseUrl` *and* `api`, and a
        // stored 1.2.0 copy then overrode the new definition (the app's form also kept
        // *showing* the stale value, so nothing looked wrong). `PiModelsMerge.declaredConnection`
        // holds that half of the rule; see the comment at the construction site below.
        //
        // pi ships a catalog for the providers [PiProviderPresets] marks
        // `builtInPi` (its own `providers/*.ts`, e.g. `providers/deepseek.ts:8-13`).
        // For those, a `models[]` entry is not an addition but a **replacement**:
        // `applyModelsJson` swaps the same-id model wholesale
        // (`provider-composer.ts:203-206`) and `modelFromJson` then fills every field
        // the entry omits from pi's own defaults (`:150-166`) — `input: ["text"]`,
        // `contextWindow: 128000`, `maxTokens: 16384`, `reasoning: false`, `cost: 0`.
        // Writing one therefore *downgrades* a model pi describes correctly, which is
        // what happened on device: images were stripped before the model saw them and
        // a 1M context window read as 128k (docs/known-gaps.md §M11/§M13).
        //
        // So the rule is not "declare what we know" but **"declare only where pi has
        // no definition at all"** — Ollama, llama.cpp, 自定义, the providers this app
        // adds itself. There, `models[]` is the only definition that will ever exist.
        //
        // Picking models for a provider pi *does* ship is still meaningful, and still
        // written: it is the **selection**, `enabledModels` in settings.json
        // (`settings-manager.ts:139`), which is pi's own mechanism for "which models
        // to offer" and says nothing about what a model *is*.
        // **只申报官方目录不认识的 id。**
        //
        //  - 官方认识的：不写 —— 同 id 是整体替换（`provider-composer.ts:203-206`），写了
        //    就把官方数据换成我们的副本（价格 0、上下文退回 128k）；
        //  - 官方**不**认识的（还没更新的新模型）：**必须写** —— `models[]` 是它唯一的定义，
        //    不写它就只存在于 `enabledModels` 里，pi 解析不到、模型永远不出现。这种"增加"
        //    不受替换语义影响；缺的字段由 pi 补默认值（`modelFromJson`：
        //    contextWindow 128000、maxTokens 16384、input ["text"]、reasoning false、cost 0）。
        //  - 目录这次没读到 + pi 自带厂商：退回什么都不申报（否则会把整份官方目录换成默认值）。
        //  - 自建厂商（pi 没有它的目录）：`models[]` 是唯一定义，照旧全写。
        val baseDeclared = when {
            officialCatalogRead -> choices.filterNot { it.id in officiallyKnownIds }
            preset.builtInPi -> emptyList()
            else -> choices
        }
        // 编辑过能力的 id 一律补进来（见 [forceDeclared]），其余规则不变。
        val declared = baseDeclared + choices.filter { choice ->
            choice.id in forceDeclared && baseDeclared.none { it.id == choice.id }
        }

        // 连接字段走与 `name` 同一条规矩：pi 自己有这个厂商时，只声明与它不同的值。
        // 抄一份的代价就在这里 —— pi 1.3.0 把 `kimi-coding` 的 `baseUrl`/`api` 都换了，
        // 一份 1.2.0 抄下来的副本会压着新定义不放，而用户看不出来。
        val connection = PiModelsMerge.declaredConnection(
            baseUrl = baseUrl,
            api = api,
            official = if (preset.builtInPi) {
                PiModelsMerge.OfficialConnection(baseUrl = preset.baseUrl, api = preset.api)
            } else {
                null
            },
        )
        val provider = PiModelsFile.Provider(
            id = preset.id,
            // pi's own providers already have a name, and this app's label for one
            // ("Google AI Studio" for pi's "Google") is a label for *this* list, not a
            // fact about the provider. Writing it would put the app's wording into
            // `models.json` and rename the provider inside pi.
            name = if (preset.builtInPi) null else preset.displayName,
            baseUrl = connection.baseUrl,
            api = connection.api,
            // 故意不跟 `baseUrl`/`api` 走同一条规矩：表单没有这个字段，它的值永远是预设推出来的
            // 那个；而 pi 的 composer 要求厂商块至少带一个 baseUrl/headers/compat/
            // modelOverrides/models/apiKey/oauth/authHeader（`provider-composer.js:157-165`，
            // `api` 不在其中），`authHeader` 是 App 手上唯一能一直满足它的键。
            authHeader = preset.authHeader,
            models = declared.map { choice ->
                PiModelsFile.Model(
                    id = choice.id,
                    name = choice.name,
                    reasoning = choice.reasoning,
                    contextWindow = choice.contextWindow,
                    maxTokens = choice.maxTokens,
                    input = choice.input,
                    costInput = choice.costInput,
                    costOutput = choice.costOutput,
                    costCacheRead = choice.costCacheRead,
                    costCacheWrite = choice.costCacheWrite,
                    samplingParams = choice.samplingParams,
                    thinkingLevelMap = choice.thinkingLevelMap,
                )
            },
        )
        models().upsert(provider)?.let { error ->
            return SaveResult(false, steps + error, null)
        }
        steps += if (declared.isEmpty()) {
            "模型信息沿用 pi 自带的目录，未覆盖模型能力"
        } else {
            "模型清单已保存：${preset.id}（${declared.size} 个模型）"
        }
        // 手写定义被这次保存清掉了：`declared` 为空 + 原来有 → upsert 删掉了 `models` 键。
        // 必须说出来（「模型信息沿用 pi 自带的目录」听起来像什么都没发生）。
        if (declared.isEmpty() && existingDeclared.isNotEmpty()) {
            steps += "models.json 里原有 ${existingDeclared.size} 条手写定义已移除"
        }

        // 选择键：判定（写不写、写什么）全在 ModelSelectionPlan，这里只执行并汇报。
        val plan = ModelSelectionPlan.settings(
            existingPatterns = preferences().enabledModels(),
            providerId = preset.id,
            checkedIds = choices.map { it.id },
            configuredIds = configuredModelIds,
        )
        plan.enabledModels?.let { patterns ->
            preferences().setEnabledModels(patterns)?.let { error ->
                return SaveResult(false, steps + error, null)
            }
            steps += if (patterns.isEmpty()) {
                // pi 把"没有 enabledModels"当全部模型可循环（`main.ts:789`），所以说清楚
                // 这不是"什么都不循环"，免得用户以为自己清空了循环范围。
                "循环列表已清空，回到 pi 的默认（全部模型可循环）"
            } else {
                "循环列表已更新（${patterns.size} 条，其他厂商的条目保留）"
            }
        }

        // 写盘已经成功：从这里开始，引擎快照里的模型与凭证就是旧的了。记账的位置在
        // **所有**落盘之后（凭证、厂商块、循环列表），失败的分支都在上面提前 return，
        // 所以这里置位等价于"这一屏确实改了什么"。清位在引擎开始服务那一刻
        // （`PiEngineSession.probeServing`）—— 重启成功走的就是那条路，卡片自己消失。
        PendingModelRestart.mark()

        return SaveResult(
            ok = true,
            steps = steps,
            restart = PiPackageService.RestartRequired(
                changes = if (choices.isEmpty()) {
                    listOf("${preset.displayName} 的凭证与厂商块（模型沿用 pi 目录）")
                } else {
                    listOf("${preset.displayName} 的模型与凭证（${choices.size} 个模型）")
                },
                detail = "新厂商要重启引擎后才会出现在模型列表里。已保存的配置不会丢失。",
            ),
        )
    }

    /**
     * Remove a provider from `auth.json` and `models.json`. Returns null on success.
     *
     * 成功之后同样置「等重启」标记：删掉的厂商/凭证还在引擎的启动快照里，要一次启动
     * 才会消失（`PendingModelRestart`）。
     */
    fun remove(providerId: String): String? {
        auth().remove(providerId)?.let { return it }
        models().remove(providerId)?.let { return it }
        PendingModelRestart.mark()
        return null
    }

    /** For the UI's "which agent dir is this actually in" disclosure. */
    fun paths(): Pair<String, String> = truthAgentDir.absolutePath to mirrorAgentDir.absolutePath

    companion object {
        /**
         * 模型这一块的界面要盯着的外部改动：这四份文件一变，"已导入的模型"这张清单就可能不同。
         *
         * 交给 `PiDirectoryWatch` 做**前缀**匹配，所以 `models.json.tmp-123`、
         * `models.json.lock` 这类"有人正在写"的信号也算——它们正是原子写入的中间态。
         */
        val MODEL_FILES = listOf("models.json", "auth.json", "models-store.json", "settings.json")
    }
}
