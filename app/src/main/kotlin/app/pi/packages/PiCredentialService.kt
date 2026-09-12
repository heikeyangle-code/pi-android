package app.pi.packages

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
 * cached snapshot (`:422-424`) — so a new provider does not appear until the process
 * restarts. (pi's docs say `models.json` "reloads each time you open `/model`"
 * (`docs/models.md:80`); that is true of the **TUI**, which triggers a refresh, and
 * false over `--mode rpc`.) `auth.json` is different — pi's reader is revision-checked
 * (`auth-storage.ts:39`, `getFileRevision`), so an externally added key is noticed
 * without a restart; the provider list is what needs one.
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
     * Write everything. [defaultModelId] becomes `defaultModel`; every id in
     * [choices] becomes an entry in `enabledModels` (qualified `provider/id`, which
     * is the syntax `settings-manager.ts:139` documents for `--models`).
     */
    fun save(
        preset: PiProviderPresets.Preset,
        apiKey: String,
        baseUrl: String,
        api: String,
        choices: List<ModelChoice>,
        defaultModelId: String?,
    ): SaveResult {
        val steps = mutableListOf<String>()
        if (choices.isEmpty()) {
            return SaveResult(false, listOf("没有选择任何模型"), null)
        }
        if (preset.scanStyle == PiProviderPresets.ScanStyle.Keyless && apiKey.isBlank()) {
            // pi hides a provider with no credential even when the endpoint needs
            // none (`docs/models.md:34-36`), so a placeholder is written and the
            // screen says so.
            steps += "本地端点无鉴权，已按 pi 的要求写入占位 Key（${PiProviderPresets.KEYLESS_PLACEHOLDER}）"
        }
        val effectiveKey = if (apiKey.isBlank() && preset.scanStyle == PiProviderPresets.ScanStyle.Keyless) {
            PiProviderPresets.KEYLESS_PLACEHOLDER
        } else {
            apiKey
        }

        auth().setApiKey(preset.id, effectiveKey)?.let { error ->
            return SaveResult(false, steps + error, null)
        }
        steps += "凭证已保存（仅本 App 可读）：${preset.id}"

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
            name = preset.displayName,
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

        preferences().selectModel(
            providerId = preset.id,
            modelId = defaultModelId ?: choices.first().id,
            enabledModelIds = choices.map { it.id },
        )?.let { error ->
            return SaveResult(false, steps + error, null)
        }
        steps += "已设为默认模型，并加入可切换的模型列表"

        return SaveResult(
            ok = true,
            steps = steps,
            restart = PiPackageService.RestartRequired(
                changes = listOf("新增厂商 ${preset.displayName}（${choices.size} 个模型）"),
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
}
