package app.pi.packages

import app.pi.runtime.PiProjectConfig
import java.io.File

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

    // ------------------------------------------------------------- 已导入清单

    /**
     * 设置 → 模型 的那张清单：厂商、模型、来源、启用、现在能不能用。
     *
     * **直读，不进任何缓存。** 这是这个页面存在的理由之一：`PiSettingsFileStore` 会把文档
     * 缓存到下一次 App 自己写入为止，而这里要回答的恰恰是「外部（pi、终端、AI）刚改完，
     * 现在到底是什么」。读的是 pi 真正读的那一份（[mirrorAgentDir]，见 [AgentLayout]），
     * `models.json`/`auth.json` 在没有的时候回落到 rootfs 那份，与写入端同一套规则
     * （[PiConfigFiles.effectiveFile]）。
     *
     * @param engineModels 引擎的 `get_available_models`；null 表示读不到（引擎没跑），
     *        这时清单里所有模型的状态是「无法判断」而不是「缺凭证」。
     */
    fun inventory(engineModels: List<PiModelInventory.EngineModel>?): PiModelInventory.Inventory {
        val selection = PiModelInventory.selection(
            globalSettings = readTextOrNull(File(mirrorAgentDir, "settings.json")),
            projectSettings = readTextOrNull(PiProjectConfig.settingsFile(workspace)),
        )
        return PiModelInventory.assemble(
            modelsJson = readEffective(
                primary = PiConfigFiles.modelsFile(truthAgentDir),
                mirror = PiConfigFiles.modelsFile(mirrorAgentDir),
            ),
            authJson = readEffective(
                primary = PiConfigFiles.authFile(truthAgentDir),
                mirror = PiConfigFiles.authFile(mirrorAgentDir),
            ),
            catalogJson = readTextOrNull(File(mirrorAgentDir, "models-store.json")),
            selection = selection,
            engineModels = engineModels,
        )
    }

    /**
     * 交给 `PiDirectoryWatch` 的目录：引擎的 agent 目录，以及工作区的 `.pi`（项目级
     * `settings.json` 在那里）。
     *
     * **目录，不是文件**：App 写这些文件是原子的（写临时文件再 `rename`），而 inotify 的
     * 监视挂在 inode 上——监视文件会在自己写完之后失聪。理由写在 `PiFileWatch.kt`。
     */
    fun watchedDirectories(): List<File> = listOf(mirrorAgentDir, PiProjectConfig.root(workspace))

    private fun readEffective(primary: File, mirror: File): String? =
        PiConfigFiles.effectiveFile(primary, mirror)?.let { readTextOrNull(it) }

    private fun readTextOrNull(file: File): String? =
        if (file.isFile) runCatching { file.readText() }.getOrNull() else null

    // -------------------------------------------------------------- prefill

    /**
     * What the editor opens with, so "新增" and "编辑已有" are one screen.
     *
     * A masked key is *presence*, not the secret: the app cannot display a key back
     * to the user without turning a settings screen into a credential dump, and pi
     * itself never renders one. Leaving the field untouched keeps the stored key;
     * typing replaces it.
     */
    data class Existing(
        val providerId: String?,
        val modelId: String?,
        val enabledModelIds: List<String>,
        val maskedKey: String?,
        /**
         * 这个厂商在 `auth.json` 里有没有条目（**任何类型**，含 App 不编辑的 `oauth`）。
         *
         * 与 [maskedKey] 的区别是它要回答的那个问题：留空 Key 保存时，凭证会不会被动？有
         * 条目 = 不动（见 [save] 的规则），所以界面说的是「留空会沿用已保存的凭证」，而不是
         * 让用户为了改一个模型重新粘贴 Key。
         */
        val credentialPresent: Boolean,
        val configuredProviderIds: List<String>,
        val configuredModelIds: List<String>,
        val baseUrl: String?,
        val api: String?,
        /** Non-null when the model config exists but pi would refuse to parse it. */
        val modelsFileError: String?,
        /**
         * Non-null when the credential file exists but cannot be read.
         *
         * Reported rather than swallowed: pi **throws** on an unreadable credential
         * file (`auth-storage.ts:216-227`), so "no providers are configured" would be
         * the wrong sentence — the truth is that the file is broken and pi will not
         * start until it is dealt with. The screen must show this; until it does, the
         * field is the service's contract, not decoration.
         */
        val authFileError: String?,
    )

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
     */
    suspend fun probe(
        preset: PiProviderPresets.Preset,
        apiKey: String,
        baseUrl: String? = null,
    ): PiModelScanner.Result = scanner.scan(preset, apiKey, baseUrl)

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
    )

    data class SaveResult(
        val ok: Boolean,
        /** One line per file, in the order written, for the outcome sheet. */
        val steps: List<String>,
        /** Non-null when a restart is required to make this take effect. */
        val restart: PiPackageService.RestartRequired?,
    )

    /**
     * Write everything: `auth.json` → `models.json` → 以及**只在用户要求时**才写
     * `settings.json` 里的选择键。
     *
     * 选择键（`defaultProvider`/`defaultModel`/`enabledModels`）由
     * [ModelSelectionPlan] 判定，**默认一个都不写**：
     *
     *  - [setAsDefault] 为 true 且 [defaultModelId] 非空 ⇒ 写默认选择；
     *  - 勾选集合与 [configuredModelIds] 不同 ⇒ 重写 `enabledModels`（整表，但别的
     *    厂商的条目保留）。
     *
     * 旧版把这三键绑成一次无条件写入（`modelId ?: choices.first().id`），于是"给已有
     * 厂商加一个模型"会顺手切默认、"导入第二个厂商"会冲掉第一个厂商的循环条目和用户
     * 手写的 pattern —— 用户报的「导入完了也有 bug」（B1）。
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
        /** 用户是否明确点了「设为默认」；没点就不写默认选择。 */
        setAsDefault: Boolean = false,
        /** [setAsDefault] 为 true 时写入的模型 id；false 时忽略。 */
        defaultModelId: String? = null,
    ): SaveResult {
        val steps = mutableListOf<String>()
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
        val effectiveKey: String? = when {
            apiKey.isNotBlank() -> apiKey
            storedCredential -> null
            preset.scanStyle == PiProviderPresets.ScanStyle.Keyless -> PiProviderPresets.KEYLESS_PLACEHOLDER
            else -> return SaveResult(false, steps + "这个厂商还没有保存过凭证，请填 API Key", null)
        }
        if (effectiveKey != null) {
            auth().setApiKey(preset.id, effectiveKey)?.let { error ->
                return SaveResult(false, steps + error, null)
            }
            steps += if (apiKey.isNotBlank()) {
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
        val declared = if (preset.builtInPi) emptyList() else choices

        val provider = PiModelsFile.Provider(
            id = preset.id,
            // pi's own providers already have a name, and this app's label for one
            // ("Google AI Studio" for pi's "Google") is a label for *this* list, not a
            // fact about the provider. Writing it would put the app's wording into
            // `models.json` and rename the provider inside pi.
            name = if (preset.builtInPi) null else preset.displayName,
            baseUrl = baseUrl.trim().trimEnd('/'),
            api = api,
            authHeader = preset.authHeader,
            models = declared.map { choice ->
                PiModelsFile.Model(
                    id = choice.id,
                    name = choice.name,
                    reasoning = choice.reasoning,
                    contextWindow = choice.contextWindow,
                    maxTokens = choice.maxTokens,
                    input = choice.input,
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

        // 选择键：判定（写不写、写什么）全在 ModelSelectionPlan，这里只执行并汇报。
        val plan = ModelSelectionPlan.settings(
            existingPatterns = preferences().enabledModels(),
            providerId = preset.id,
            checkedIds = choices.map { it.id },
            configuredIds = configuredModelIds,
            setAsDefault = setAsDefault,
            requestedDefaultModelId = defaultModelId,
        )
        val defaultId = plan.defaultModelId
        if (plan.writeDefault && defaultId != null) {
            preferences().setSelection(preset.id, defaultId)?.let { error ->
                return SaveResult(false, steps + error, null)
            }
            steps += "已设为默认模型：${preset.id}/$defaultId"
        }
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

    /** Remove a provider from `auth.json` and `models.json`. Returns null on success. */
    fun remove(providerId: String): String? {
        auth().remove(providerId)?.let { return it }
        return models().remove(providerId)
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
