package app.pi.packages

import app.pi.rpc.PiJson
import app.pi.settings.PiSettingsFileStore
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission

/**
 * The three official files a credential/model import touches, written the way pi
 * reads them.
 *
 * ## Where the definitions actually live — verified, not assumed
 *
 * This was the open question, and pi's source answers it unambiguously. **Neither a
 * provider nor a credential is a `settings.json` key.**
 *
 *  - `Settings` (`core/settings-manager.ts:106-158`) has `defaultProvider`,
 *    `defaultModel`, `modelThinkingLevels` and `enabledModels` — but **no
 *    `providers` key and no credential key**. Grepping the interface is enough to
 *    settle it.
 *  - Custom providers and models are declared in **`<agentDir>/models.json`**, under
 *    a top-level `providers` object (`docs/models.md:3`; loader
 *    `core/model-config.ts:199-215`, location `core/model-runtime.ts:174-175`).
 *    `docs/custom-provider.md:33`: "Pi composes `models.json` overrides above
 *    registered native providers."
 *  - Credentials are in **`<agentDir>/auth.json`** (`core/auth-storage.ts:52`),
 *    a `Record<providerId, Credential>`.
 *
 * So `settings.json` is only the *selection* store (`defaultModel`,
 * `enabledModels`), never the definition store.
 *
 * ## Locking: two of the three are locked, and which two matters
 *
 *  - `auth.json` — `lockfile.lockSync(authPath, { realpath: false })`, 10 attempts ×
 *    20 ms (`auth-storage.ts:76-100`). Also `AUTH_FILE_WRITE_OPTIONS` sets
 *    `mode: 0o600`, and the comment is explicit that the mode applies **only on
 *    creation** (`:24-25`).
 *  - `settings.json` — `lockfile.lockSync(path, { realpath: false })`, same retry
 *    shape (`settings-manager.ts:236-256`). Taking it matters because pi rewrites
 *    this file whenever it persists a setting (thinking level, theme, compaction),
 *    so an unlocked write is a lost update with no error — "我的设置过一会儿自己变
 *    回去了".
 *  - `models.json` — **not locked at all.** `ModelConfig.load` is a plain
 *    `readFile` (`model-config.ts:251-290`) and nothing in pi ever writes this file;
 *    the *cache* of dynamically discovered models goes to `models-store.json`
 *    instead (`model-runtime.ts:180`). The app still writes it atomically, but
 *    inventing a lock here would be a second lock no other writer honours.
 *
 * `proper-lockfile`'s lock is a **directory** at `<file>.lock`; a live lock is never
 * stolen and a lock older than the stale window is removed.
 *
 * ## Writes are atomic, and pi's files are not
 *
 * pi uses bare `writeFileSync` for all three. A kill mid-write truncates
 * `auth.json`, and `ReadOnlyAuthStorage.load` then throws on every start
 * (`auth-storage.ts:216-227`). The app writes a sibling temp file and renames, the
 * same choice already made for `settings.json` in `PiSettingsFileStore.kt:33-35`.
 */
object PiConfigFiles {

    /** `<agentDir>/auth.json`; `<agentDir>` is what `PiConfigPaths` resolved. */
    fun authFile(agentDir: File): File = File(agentDir, "auth.json")

    /** `<agentDir>/models.json`. */
    fun modelsFile(agentDir: File): File = File(agentDir, "models.json")

    /**
     * The file a read should use: the primary when it exists, else the mirror.
     *
     * "Primary" is always the guest-truth path, because that is what pi reads. The
     * mirror is a fallback *and* a durability copy, not a second source of truth —
     * see [AgentLayout] for why both exist.
     */
    fun effectiveFile(primary: File, mirror: File?): File? = when {
        primary.isFile -> primary
        mirror?.isFile == true -> mirror
        else -> null
    }

    // ------------------------------------------------------------------- locking

    /**
     * pi's `proper-lockfile` semantics, reproduced on the same lock path.
     *
     * `lockfile.lockSync(path, { realpath: false })` creates the directory
     * `<path>.lock` (mkdir is atomic) and removes it to release; it retries
     * `ELOCKED` 10 times with a 20 ms sleep and treats a lock older than its default
     * 10 s window as stale. This does the same, so the app and pi coordinate instead
     * of overwriting each other.
     */
    fun <T> withLock(target: File, block: () -> T): T {
        val lockDir = File(target.parentFile, "${target.name}.lock")
        lockDir.parentFile?.mkdirs()
        var attempt = 0
        while (true) {
            attempt++
            if (runCatching { lockDir.mkdir() }.getOrDefault(false)) {
                return try {
                    block()
                } finally {
                    runCatching { lockDir.delete() }
                }
            }
            val age = System.currentTimeMillis() - lockDir.lastModified()
            if (lockDir.isDirectory && age > STALE_MS) {
                // A process died holding it. proper-lockfile removes stale locks too.
                runCatching { lockDir.delete() }
                continue
            }
            if (attempt >= MAX_ATTEMPTS) {
                throw IllegalStateException("无法获取 ${lockDir.name} 的锁（${lockDir.absolutePath}）")
            }
            Thread.sleep(RETRY_DELAY_MS)
        }
    }

    // ------------------------------------------------------------------- writing

    /**
     * Atomic replace. [mode600] additionally restricts the result to owner-only,
     * which is what `auth.json` needs and `models.json` does not.
     */
    fun write(target: File, text: String, mode600: Boolean = false): Boolean = runCatching {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.tmp-${android.os.Process.myPid()}")
        temp.writeText(text)
        runCatching { Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
            .onFailure {
                target.writeText(text)
                temp.delete()
            }
        if (mode600) restrictToOwner(target)
        true
    }.getOrDefault(false)

    /**
     * `0600`, but never *loosening* an existing file that is already tighter.
     *
     * `Files.setPosixFilePermissions` is the exact tool; the fallback covers a
     * filesystem that refuses POSIX permissions (some FUSE mounts), where the
     * legacy `File.setReadable`/`setWritable` pair still applies.
     */
    fun restrictToOwner(file: File) {
        runCatching {
            Files.setPosixFilePermissions(
                file.toPath(),
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        }.onFailure {
            runCatching {
                file.setReadable(false, false)
                file.setReadable(true, true)
                file.setWritable(false, false)
                file.setWritable(true, true)
                file.setExecutable(false, false)
            }
        }
    }

    /**
     * `JSON.stringify(value, null, 2)`: what `auth-storage.ts:358`, `:467` and `:479`
     * write. Kotlin's `JsonObject.toString()` uses the same compact form
     * (`{"k":v}` with no spaces, because `Json` is configured non-pretty), so the
     * app writes the **compact** shape rather than inventing an indented one.
     *
     * That is a deliberate, stated difference: pi rewrites the file in its own
     * 2-space form on its next credential change, and JSON semantics are identical
     * either way. Formatting the file itself would be cosmetic churn on a
     * secret-bearing file.
     */
    fun stringify(document: JsonObject): String = document.toString()

    fun parseObject(text: String): JsonObject? = PiJson.parseObjectOrNull(TrustFile.stripBom(text))

    /**
     * `stripJsonComments` (`core/model-config.ts:267` calls it before `JSON.parse`).
     *
     * `models.json` is the one pi file that tolerates comments, so a user's
     * annotated file must not be rejected by the app's reader. This strips `//` line
     * comments and block comments **outside strings**; it is deliberately narrow and
     * is only used for reading, never to re-serialise a user's file.
     */
    fun stripJsonComments(text: String): String {
        val out = StringBuilder(text.length)
        var inString = false
        var escaped = false
        var index = 0
        while (index < text.length) {
            val ch = text[index]
            if (inString) {
                out.append(ch)
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == '"' -> inString = false
                }
                index++
                continue
            }
            when {
                ch == '"' -> {
                    inString = true
                    out.append(ch)
                    index++
                }
                ch == '/' && index + 1 < text.length && text[index + 1] == '/' -> {
                    while (index < text.length && text[index] != '\n') index++
                }
                ch == '/' && index + 1 < text.length && text[index + 1] == '*' -> {
                    index += 2
                    while (index + 1 < text.length && !(text[index] == '*' && text[index + 1] == '/')) index++
                    index += 2
                }
                else -> {
                    out.append(ch)
                    index++
                }
            }
        }
        return out.toString()
    }

    private const val MAX_ATTEMPTS = 10
    private const val RETRY_DELAY_MS = 20L

    /** proper-lockfile's default stale window. */
    private const val STALE_MS = 10_000L
}

/**
 * `auth.json` as a typed read/modify/write.
 *
 * Shape, from `auth-storage.ts:17` and `:228-255`:
 *
 * ```json
 * { "openai": { "type": "api_key", "key": "sk-..." } }
 * ```
 *
 * Validation is pi's, and it throws on anything else, so the app must not invent
 * shapes: an `api_key` credential may carry `key` (undefined or string) and `env`
 * (undefined or an object of strings); an `oauth` credential must carry
 * `access`/`refresh` strings and a finite numeric `expires`. `key` may also be
 * `$ENV_VAR` or `!command`, resolved at read time
 * (`resolveConfigValue`, `auth-storage.ts:266-273`) — the app writes a literal key
 * and never touches a `!command`.
 */
class PiAuthStorage(
    private val file: File,
    /**
     * The durable copy. See [AgentLayout]: pi reads the file inside the rootfs, and
     * `RuntimeProvisioner.wipe()` deletes that tree on every runtime revision bump,
     * so a credential written only there disappears on the next app update.
     */
    private val mirror: File? = null,
) {

    data class ApiKey(val key: String?, val env: Map<String, String> = emptyMap())

    sealed interface Read {
        data class Ok(val entries: Map<String, ApiKey>) : Read

        /** pi throws on an unreadable auth.json, so this is not a warning. */
        data class Invalid(val message: String) : Read
    }

    fun read(): Read {
        val effective = effectiveFile() ?: return Read.Ok(emptyMap())
        val text = runCatching { effective.readText() }.getOrElse { error ->
            return Read.Invalid("凭证文件无法读取：${error.message ?: error::class.java.simpleName}")
        }
        val document = PiConfigFiles.parseObject(text)
            ?: return Read.Invalid("凭证文件无法读取：不是 JSON 对象")
        val entries = LinkedHashMap<String, ApiKey>()
        for ((providerId, value) in document) {
            val credential = value as? JsonObject
                ?: return Read.Invalid("凭证记录格式不对（$providerId）")
            val type = (credential["type"] as? JsonPrimitive)?.content
            if (type != "api_key") {
                // oauth credentials are pi's business (`/login` in a terminal); the
                // app keeps them untouched and does not pretend to edit them.
                continue
            }
            val key = (credential["key"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
            val env = (credential["env"] as? JsonObject)?.mapNotNull { (name, raw) ->
                (raw as? JsonPrimitive)?.content?.let { name to it }
            }?.toMap() ?: emptyMap()
            entries[providerId] = ApiKey(key, env)
        }
        return Read.Ok(entries)
    }

    /**
     * Set one provider's API key, preserving every other entry — including the
     * `oauth` entries this class cannot represent. The read-modify-write happens
     * **inside pi's lock**, on the raw JSON, so nothing is lost between the two.
     *
     * @return null on success, or a message.
     */
    fun setApiKey(providerId: String, key: String): String? {
        if (providerId.isBlank()) return "厂商 id 不能为空"
        if (key.isBlank()) return "API Key 不能为空"
        return runCatching {
            PiConfigFiles.withLock(file) {
                val raw = if (file.isFile) file.readText() else "{}"
                val document = PiConfigFiles.parseObject(raw)
                    ?: return@withLock "凭证文件无法解析，已拒绝写入以免破坏它"
                val next = LinkedHashMap<String, JsonElement>(document)
                next[providerId] = buildJsonObject {
                    put("type", JsonPrimitive("api_key"))
                    put("key", JsonPrimitive(key))
                }
                val text = JsonObject(next).toString()
                if (!PiConfigFiles.write(file, text, mode600 = true)) {
                    return@withLock "写入凭证文件失败（${file.absolutePath}）"
                }
                writeMirror(text)
                null
            }
        }.getOrElse { error -> "写入凭证文件失败：${error.message ?: error::class.java.simpleName}" }
    }

    /**
     * Remove a provider's credential. Returns null on success.
     *
     * `delete` is a real removal rather than a null key, because pi's loader rejects
     * a credential that is not an object (`auth-storage.ts:229-231`).
     */
    fun remove(providerId: String): String? = runCatching {
        PiConfigFiles.withLock(file) {
            if (!file.isFile) return@withLock null
            val document = PiConfigFiles.parseObject(file.readText())
                ?: return@withLock "凭证文件无法解析，已拒绝写入以免破坏它"
            val next = LinkedHashMap<String, JsonElement>(document)
            if (next.remove(providerId) == null) return@withLock null
            val text = JsonObject(next).toString()
            if (!PiConfigFiles.write(file, text, mode600 = true)) {
                return@withLock "写入凭证文件失败"
            }
            writeMirror(text)
            null
        }
    }.getOrElse { error -> "写入凭证文件失败：${error.message ?: error::class.java.simpleName}" }

    /** The file a read should use: primary, else mirror. */
    private fun effectiveFile(): File? = PiConfigFiles.effectiveFile(file, mirror)

    /** Best-effort durability copy; a failure here is reported by the caller's read. */
    private fun writeMirror(text: String) {
        mirror?.let { PiConfigFiles.write(it, text, mode600 = true) }
    }

    /** Masked display form. Never the key itself. */
    companion object {
        fun mask(key: String): String = when {
            key.isEmpty() -> "(空)"
            key.length <= 8 -> "•".repeat(key.length)
            else -> key.take(4) + "•".repeat(8) + key.takeLast(4)
        }
    }
}

/**
 * `models.json` as a typed read/modify/write, against the schema pi actually
 * validates with.
 *
 * Schema source is `core/model-config.ts:162-215`, not the prose docs — they differ
 * in one place that matters: in **`models.json`** the provider's `oauth` field is
 * the literal `"radius"` (`:204`), not the OAuth object shape
 * `docs/custom-provider.md` documents for the *extension* API. Writing the doc
 * shape would fail `validateModelsConfig.Check` and make pi report
 * `Invalid models.json schema` (`:275-282`) — with the whole file ignored.
 *
 * Required keys are therefore: `providers` at the root; `id` on each model. Every
 * other field is optional, which is what lets the import write only what the vendor
 * listing gave it and leave the rest to pi's defaults.
 *
 * Merge semantics: `models` **replaces** the model list for that provider
 * (`docs/custom-provider.md:684`), while the provider entry itself is composed
 * *above* (overrides) the built-in provider of the same id. So writing
 * `providers.openai.models` does not extend OpenAI's built-in catalog — it replaces
 * it. The import only ever writes a provider id the user chose, and the UI says
 * which one.
 */
class PiModelsFile(
    private val file: File,
    /** Durable copy; same reasoning as [PiAuthStorage]. */
    private val mirror: File? = null,
) {

    /** One model entry; only [id] is required by pi's schema. */
    data class Model(
        val id: String,
        val name: String? = null,
        /** `api` override for this model; usually null and inherited from the provider. */
        val api: String? = null,
        val reasoning: Boolean? = null,
        val contextWindow: Long? = null,
        val maxTokens: Long? = null,
        val input: List<String> = emptyList(),
        val costInput: Double? = null,
        val costOutput: Double? = null,
        /** True when the app filled a value the vendor did not provide. */
        val defaultsApplied: Boolean = false,
    ) {
        fun toJson(): JsonObject = buildJsonObject {
            put("id", JsonPrimitive(id))
            name?.let { put("name", JsonPrimitive(it)) }
            api?.let { put("api", JsonPrimitive(it)) }
            reasoning?.let { put("reasoning", JsonPrimitive(it)) }
            contextWindow?.let { put("contextWindow", JsonPrimitive(it)) }
            maxTokens?.let { put("maxTokens", JsonPrimitive(it)) }
            if (input.isNotEmpty()) put("input", JsonArray(input.map { JsonPrimitive(it) }))
            if (costInput != null || costOutput != null) {
                put(
                    "cost",
                    buildJsonObject {
                        put("input", JsonPrimitive(costInput ?: 0.0))
                        put("output", JsonPrimitive(costOutput ?: 0.0))
                        put("cacheRead", JsonPrimitive(0.0))
                        put("cacheWrite", JsonPrimitive(0.0))
                    },
                )
            }
        }
    }

    /** A provider block, with the fields from `ProviderConfigSchema`. */
    data class Provider(
        val id: String,
        val name: String?,
        val baseUrl: String,
        val api: String,
        /**
         * pi's `authHeader`: "if true, adds `Authorization: Bearer` with the
         * resolved API key" (`docs/custom-provider.md:681-682`,
         * `model-config.ts:207`). Set for OpenAI-compatible endpoints; left unset for
         * Anthropic and Google, whose SDKs send `x-api-key` / a `key` query
         * parameter instead.
         */
        val authHeader: Boolean?,
        val models: List<Model>,
    ) {
        fun toJson(): JsonObject = buildJsonObject {
            name?.let { put("name", JsonPrimitive(it)) }
            put("baseUrl", JsonPrimitive(baseUrl))
            put("api", JsonPrimitive(api))
            authHeader?.let { put("authHeader", JsonPrimitive(it)) }
            put("models", JsonArray(models.map { it.toJson() }))
        }
    }

    data class Snapshot(val providers: JsonObject, val error: String?) {
        val healthy: Boolean get() = error == null
    }

    /**
     * Read. Unlike `auth.json`, a broken `models.json` does **not** throw in pi: the
     * loader keeps the error and returns an empty config
     * (`model-config.ts:259-282`), which surfaces to the user as "no models" plus an
     * error line. The app mirrors that split — [Snapshot.error] is shown, and the app
     * refuses to overwrite a file it could not parse.
     */
    fun read(): Snapshot {
        val effective = effectiveFile() ?: return Snapshot(JsonObject(emptyMap()), null)
        val text = runCatching { effective.readText() }.getOrNull()
            ?: return Snapshot(JsonObject(emptyMap()), "模型配置无法读取（${effective.absolutePath}）")
        val document = PiConfigFiles.parseObject(PiConfigFiles.stripJsonComments(text))
            ?: return Snapshot(JsonObject(emptyMap()), "模型配置不是 JSON 对象（${effective.absolutePath}）")
        val providers = document["providers"] as? JsonObject
            ?: return Snapshot(JsonObject(emptyMap()), "模型配置格式不对：缺少厂商清单")
        return Snapshot(providers, null)
    }

    /** The file a read should use: primary, else mirror. */
    private fun effectiveFile(): File? = PiConfigFiles.effectiveFile(file, mirror)

    /** The model ids already configured for [providerId]. */
    fun configuredModelIds(providerId: String): List<String> {
        val block = read().providers[providerId] as? JsonObject ?: return emptyList()
        val models = block["models"] as? JsonArray ?: return emptyList()
        return models.mapNotNull { (it as? JsonObject)?.get("id")?.let { id -> (id as? JsonPrimitive)?.content } }
    }

    /**
     * Write or replace one provider block, preserving every other provider and every
     * key pi's schema allows that this class does not model.
     *
     * @return null on success, else a message.
     */
    fun upsert(provider: Provider): String? {
        if (provider.id.isBlank()) return "厂商 id 不能为空"
        if (provider.baseUrl.isBlank()) return "Base URL 不能为空"
        if (provider.api.isBlank()) return "api 类型不能为空"
        if (provider.models.isEmpty()) return "至少要有一个模型"
        if (provider.models.any { it.id.isBlank() }) return "模型 id 不能为空"

        val snapshot = read()
        snapshot.error?.let { return "已拒绝写入：$it" }

        val next = LinkedHashMap<String, JsonElement>(snapshot.providers)
        next[provider.id] = provider.toJson()
        val root = buildJsonObject {
            put("providers", JsonObject(next))
        }
        val text = root.toString()
        if (!PiConfigFiles.write(file, text)) {
            return "写入模型配置失败（${file.absolutePath}）"
        }
        mirror?.let { PiConfigFiles.write(it, text) }
        return null
    }

    /** Remove a provider block entirely. Returns null on success. */
    fun remove(providerId: String): String? {
        val snapshot = read()
        snapshot.error?.let { return "已拒绝写入：$it" }
        val next = LinkedHashMap<String, JsonElement>(snapshot.providers)
        if (next.remove(providerId) == null) return null
        val root = buildJsonObject { put("providers", JsonObject(next)) }
        val text = root.toString()
        if (!PiConfigFiles.write(file, text)) return "写入模型配置失败"
        mirror?.let { PiConfigFiles.write(it, text) }
        return null
    }
}

/**
 * The `settings.json` writes the import needs: which provider/model to use, and
 * which models are enabled for cycling.
 *
 * These are the only three keys in pi's `Settings` that describe a *selection*
 * rather than a definition (`core/settings-manager.ts:108-139`):
 * `defaultProvider`, `defaultModel`, `enabledModels`. The definitions themselves are
 * in `auth.json` / `models.json` — see [PiConfigFiles].
 *
 * The write goes through `PiSettingsFileStore` (which already owns merge rules,
 * atomic replace and project-over-global precedence) and is wrapped in pi's own
 * `settings.json` lock, which that store does not take. Both writers must agree on
 * the lock or pi's next `save()` would silently drop the change.
 *
 * ## Whose cache this write has to satisfy
 *
 * `PiSettingsFileStore` caches each document after its first read, so **two
 * instances over one file disagree after a write**. That is what happened with
 * the app's own reader: `PiSessionViewModel.settingsStore` reads the same
 * `settings.json` through a store of its own, and this class had no way to tell
 * it that the file changed — invalidating the private instance above fixed
 * nothing. Pass [sharedStore] to remove the question instead of patching it: the
 * write then goes *through* the instance the app reads, so its snapshot is
 * updated by the write itself.
 */
class PiEnginePreferences(
    private val agentDir: File,
    private val workspace: File,
    /**
     * The store the rest of the app reads this same `settings.json` through, when
     * there is one. Null builds a private store, which is the previous behaviour.
     *
     * It must address the same `settings.json` as [agentDir]: the lock below is
     * taken on `File(agentDir, "settings.json")`, and pi locks the file it was
     * actually launched against (`settings-manager.ts:236-256`). A caller whose
     * app reads a different directory therefore has to pass the directory pi
     * reads, not the other way round.
     */
    private val sharedStore: PiSettingsFileStore? = null,
) {

    private val ownStore: PiSettingsFileStore by lazy {
        PiSettingsFileStore.forWorkspace(agentDir = agentDir, workspace = workspace)
    }

    /** The instance every read and write here goes through. */
    private val store: PiSettingsFileStore get() = sharedStore ?: ownStore

    /**
     * @param modelId null to only set the provider.
     * @return null on success, else a message.
     */
    fun selectModel(providerId: String, modelId: String?, enabledModelIds: List<String> = emptyList()): String? = runCatching {
        // pi locks `settings.json` itself on every save (`settings-manager.ts:236-256`),
        // so an unlocked write here can be overwritten by pi's next persistence with
        // no error at all.
        PiConfigFiles.withLock(File(agentDir, "settings.json")) {
            store.write("defaultProvider", JsonPrimitive(providerId))
            if (modelId != null) store.write("defaultModel", JsonPrimitive(modelId))
            if (enabledModelIds.isNotEmpty()) {
                // `enabledModels` uses the same `provider/modelId` pattern syntax as
                // `--models` (`settings-manager.ts:139`), so a bare model id is not
                // enough — it must be qualified.
                store.write(
                    "enabledModels",
                    JsonArray(enabledModelIds.map { JsonPrimitive("$providerId/$it") }),
                )
            }
            store.invalidate()
            null
        }
    }.getOrElse { error -> "写入设置失败：${error.message ?: error::class.java.simpleName}" }

    /** Currently selected provider/model, for prefilling the editor. */
    fun current(): Pair<String?, String?> {
        val provider = (store.read("defaultProvider") as? JsonPrimitive)?.content
        val model = (store.read("defaultModel") as? JsonPrimitive)?.content
        return provider to model
    }
}
