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
            // The bound is checked **before** the stale branch, and every path that
            // neither returns nor throws falls through to the single sleep at the
            // bottom of the loop. Both halves are load-bearing. The stale branch used
            // to `continue` without sleeping *and* before the bound test, so a stale
            // lock whose `delete()` fails — a non-empty lock directory, or a parent the
            // caller cannot write — looped mkdir → stat → delete → continue at 100% CPU
            // for the life of the process, which is the one path `MAX_ATTEMPTS` could
            // never reach. A stale lock is still tried first: it is removed here and the
            // lock is taken on the next turn.
            //
            // Stating the timing consequence rather than hiding it: the stale branch now
            // sleeps once before retrying, so recovering a dead process's lock costs one
            // extra `RETRY_DELAY_MS` (20 ms). Staleness itself is `STALE_MS` (10 s), far
            // longer than the whole `MAX_ATTEMPTS` window (10 × 20 ms), so a lock that is
            // live on the first attempt can never become stale inside this loop: the
            // reordering cannot skip a recovery the old code would have performed.
            if (attempt >= MAX_ATTEMPTS) {
                throw IllegalStateException("无法获取 ${lockDir.name} 的锁（${lockDir.absolutePath}）")
            }
            val age = System.currentTimeMillis() - lockDir.lastModified()
            if (lockDir.isDirectory && age > STALE_MS) {
                // A process died holding it. proper-lockfile removes stale locks too.
                runCatching { lockDir.delete() }
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
     * The implementation lives in [PiJsonComments] so the bare-JVM harness can reach
     * it (`PiConfigFiles` itself imports the app's settings layer and cannot compile
     * there) and so this reader and [PiModelInventory] cannot drift apart.
     */
    fun stripJsonComments(text: String): String = PiJsonComments.strip(text)

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
     * that tree is volatile — the explicit repair path
     * (`RuntimeProvisioner.ensureReady(rebuild = true)`) deletes it — so a credential
     * written only there can disappear.
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
     * Whether [providerId] has an entry at all — **any** type, including the `oauth`
     * records [read] deliberately does not represent.
     *
     * This is the question "would leaving the key field blank destroy something?",
     * and it has to be answered from the raw document: an `oauth` provider is
     * perfectly configured, and treating it as empty would ask the user to paste a
     * key they may not have. pi's own `storedProviders` counts a provider by the
     * presence of a credential (`core/model-runtime.ts:305`), not by its shape.
     */
    fun hasAnyEntry(providerId: String): Boolean {
        val effective = effectiveFile() ?: return false
        val text = runCatching { effective.readText() }.getOrNull() ?: return false
        return PiConfigFiles.parseObject(text)?.containsKey(providerId) == true
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
     * 写入**订阅登录**拿到的 `oauth` 凭证 —— 一个已经成形的 pi `OAuthCredential`
     * （`{type:"oauth", access, refresh, expires, …}`），原样落盘。
     *
     * 为什么不是 App 自己拼字段：`openai` 有 `accountId`、`github-copilot` 有
     * `enterpriseUrl`、`radius` 有 `scope`，pi 读回时用的正是这些键
     * （`auth/oauth/…ts` 的返回对象）。App 只校验 pi 加载器会拒绝的那几条
     * （`access`/`refresh` 字符串、`expires` 有限数字，`auth-storage.ts:228-255`），
     * 其余原样保留 —— 这就是「支持 pi 支持的所有订阅」在写盘这一侧的全部要求。
     *
     * 读取方（[readSubscriptionStatuses]）只认类型与过期时间，不碰 token。
     */
    fun setOAuth(providerId: String, credential: JsonObject): String? {
        if (providerId.isBlank()) return "厂商 id 不能为空"
        val access = (credential["access"] as? JsonPrimitive)?.content
        val refresh = (credential["refresh"] as? JsonPrimitive)?.content
        val expires = (credential["expires"] as? JsonPrimitive)?.content?.toDoubleOrNull()
        if (access.isNullOrEmpty()) return "登录结果里没有 access token，已拒绝写入"
        if (refresh == null) return "登录结果里没有 refresh token，已拒绝写入"
        if (expires == null || !expires.isFinite()) return "登录结果里的 expires 不是数字，已拒绝写入"
        return runCatching {
            PiConfigFiles.withLock(file) {
                val raw = if (file.isFile) file.readText() else "{}"
                val document = PiConfigFiles.parseObject(raw)
                    ?: return@withLock "凭证文件无法解析，已拒绝写入以免破坏它"
                val next = LinkedHashMap<String, JsonElement>(document)
                next[providerId] = buildJsonObject {
                    put("type", JsonPrimitive("oauth"))
                    for ((key, value) in credential) {
                        if (key == "type") continue
                        put(key, value)
                    }
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
     * 每个已有 `oauth` 条目的供应商 → 过期时间与是否带 refresh token。
     *
     * 登录状态只能从**文件**读：pi 自己的 `AuthStorage` 在引擎里，RPC 没有查询凭证的
     * 命令（`rpc-types.ts` 无 auth/login 条目）。过期不等于失效 —— pi 在下次请求时用
     * refresh token 自动续（`auth/resolve.ts:129-147`），所以界面只说「已登录（已过期，
     * 下次使用会自动续）」而不把过期画成未登录。
     */
    fun readSubscriptionStatuses(): Map<String, SubscriptionStatus> {
        val effective = effectiveFile() ?: return emptyMap()
        val text = runCatching { effective.readText() }.getOrNull() ?: return emptyMap()
        val document = PiConfigFiles.parseObject(text) ?: return emptyMap()
        val result = LinkedHashMap<String, SubscriptionStatus>()
        for ((providerId, value) in document) {
            val credential = value as? JsonObject ?: continue
            if ((credential["type"] as? JsonPrimitive)?.content != "oauth") continue
            val expires = (credential["expires"] as? JsonPrimitive)?.content?.toLongOrNull()
            val refresh = (credential["refresh"] as? JsonPrimitive)?.content
            result[providerId] = SubscriptionStatus(
                expires = expires,
                hasRefresh = !refresh.isNullOrEmpty(),
            )
        }
        return result
    }

    /** 一条已登录的订阅凭证里 App 会看的两件事。token 本身从不进界面。 */
    data class SubscriptionStatus(val expires: Long?, val hasRefresh: Boolean)

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
        val costCacheRead: Double? = null,
        val costCacheWrite: Double? = null,
        /**
         * pi 的 `samplingParams`：厂商公布的默认采样参数（温度、top_p…）。原样保留，
         * 不解释；没写这个键时 pi 用端点默认。
         */
        val samplingParams: JsonObject? = null,
        /** pi 的 `cost.tiers`：长上下文档位价，原样带着。 */
        val costTiers: JsonArray? = null,
        /**
         * pi 的 `thinkingLevelMap`：档位名 → 厂商的取值（`null` = 这一档不支持）。
         * App 不解释它，只在 models.dev 有 `reasoning_options` 时原样写进来。
         */
        val thinkingLevelMap: JsonObject? = null,
    ) {
        fun toJson(): JsonObject = buildJsonObject {
            put("id", JsonPrimitive(id))
            name?.let { put("name", JsonPrimitive(it)) }
            api?.let { put("api", JsonPrimitive(it)) }
            reasoning?.let { put("reasoning", JsonPrimitive(it)) }
            contextWindow?.let { put("contextWindow", JsonPrimitive(it)) }
            maxTokens?.let { put("maxTokens", JsonPrimitive(it)) }
            if (input.isNotEmpty()) put("input", JsonArray(input.map { JsonPrimitive(it) }))
            samplingParams?.let { put("samplingParams", it) }
            thinkingLevelMap?.let { put("thinkingLevelMap", it) }
            if (costInput != null || costOutput != null) {
                put(
                    "cost",
                    buildJsonObject {
                        put("input", JsonPrimitive(costInput ?: 0.0))
                        put("output", JsonPrimitive(costOutput ?: 0.0))
                        put("cacheRead", JsonPrimitive(costCacheRead ?: 0.0))
                        put("cacheWrite", JsonPrimitive(costCacheWrite ?: 0.0))
                        costTiers?.let { put("tiers", it) }
                    },
                )
            }
        }

        companion object {
            /**
             * 读回一条已经写在 `models.json` 里的模型（[toJson] 的逆操作）。
             *
             * 只认得这个类建模的那几个字段：`thinkingLevelMap`、`samplingParams`、
             * `headers`、`compat`、`cost.tiers` 这些是 pi 的，读的时候不出现在这里，
             * 写回时由 [PiModelsMerge.models] 按 id 原样保留。id 为空的那条整条
             * 跳过（pi 的 schema 也要求 id 非空，但坏文件不该让整屏读不出来）。
             */
            fun fromJson(obj: JsonObject): Model? {
                val id = (obj["id"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                    ?: return null
                val cost = obj["cost"] as? JsonObject
                return Model(
                    id = id,
                    name = stringOrNull(obj["name"]),
                    api = stringOrNull(obj["api"]),
                    reasoning = boolOrNull(obj["reasoning"]),
                    contextWindow = longOrNull(obj["contextWindow"]),
                    maxTokens = longOrNull(obj["maxTokens"]),
                    input = (obj["input"] as? JsonArray)
                        ?.mapNotNull { (it as? JsonPrimitive)?.content }
                        ?: emptyList(),
                    costInput = doubleOrNull(cost?.get("input")),
                    costOutput = doubleOrNull(cost?.get("output")),
                    costCacheRead = doubleOrNull(cost?.get("cacheRead")),
                    costCacheWrite = doubleOrNull(cost?.get("cacheWrite")),
                    samplingParams = (obj["samplingParams"] as? JsonObject)?.takeIf { it.isNotEmpty() },
                    costTiers = (cost?.get("tiers") as? JsonArray)?.takeIf { it.isNotEmpty() },
                    thinkingLevelMap = (obj["thinkingLevelMap"] as? JsonObject)?.takeIf { it.isNotEmpty() },
                )
            }

            private fun stringOrNull(element: JsonElement?): String? =
                (element as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }

            private fun boolOrNull(element: JsonElement?): Boolean? =
                (element as? JsonPrimitive)?.content?.trim()?.lowercase()?.let {
                    when (it) {
                        "true" -> true
                        "false" -> false
                        else -> null
                    }
                }

            private fun longOrNull(element: JsonElement?): Long? =
                (element as? JsonPrimitive)?.content?.trim()?.toLongOrNull()

            private fun doubleOrNull(element: JsonElement?): Double? =
                (element as? JsonPrimitive)?.content?.trim()?.toDoubleOrNull()
        }
    }

    /**
     * A provider block, with the fields from `ProviderConfigSchema`.
     *
     * `name`, `baseUrl` and `api` are nullable on purpose: **`null` means "the app has no
     * opinion, do not write this key"**, which is how a field pi already defines for this
     * provider stays un-copied ([PiModelsMerge.declaredConnection]). `name` has been that
     * shape since the app stopped re-labelling pi's own providers; `baseUrl`/`api` joined it
     * when a copy of an official definition turned out to be the thing that goes stale —
     * pi 1.3.0 changed both for `kimi-coding`, and the stored copy then won over pi's new
     * definition.
     */
    data class Provider(
        val id: String,
        val name: String?,
        val baseUrl: String?,
        val api: String?,
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
            baseUrl?.let { put("baseUrl", JsonPrimitive(it)) }
            api?.let { put("api", JsonPrimitive(it)) }
            authHeader?.let { put("authHeader", JsonPrimitive(it)) }
            // Omitted when empty, which is pi's own shape rather than a shortcut:
            // `models` is `Type.Optional(Type.Array(ModelDefinitionSchema))`
            // (`model-config.ts:208`) — and an empty array would say "this provider has no
            // models", a different and false claim. Leaving the key out says "pi's catalog is
            // the only declaration for this provider" (docs/known-gaps.md §M11).
            if (models.isNotEmpty()) put("models", JsonArray(models.map { it.toJson() }))
        }
    }

    /**
     * @property root 整份文档（注释已剥）。写回时 `providers` 之外**原样保留** —— pi 的
     *   `models.json` 允许根上有别的键（`$schema` 是最常见的那个），重建文档会把它抹掉
     *   （`PiModelsMerge.root`）。
     */
    data class Snapshot(
        val providers: JsonObject,
        val error: String?,
        val root: JsonObject = JsonObject(emptyMap()),
    ) {
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
        return Snapshot(providers, null, document)
    }

    /** The file a read should use: primary, else mirror. */
    private fun effectiveFile(): File? = PiConfigFiles.effectiveFile(file, mirror)

    /**
     * 这个厂商**申报过的**模型（`models[]` 里那条定义），按文件顺序。
     *
     * 读的是 [Model.fromJson]：只取 App 建模的字段。写回不经过这里 ——
     * [upsertModel] 会把这一条合进原文件，未知字段按 id 保留。
     */
    fun declaredModels(providerId: String): List<Model> {
        val block = read().providers[providerId] as? JsonObject ?: return emptyList()
        val models = block["models"] as? JsonArray ?: return emptyList()
        return models.mapNotNull { entry -> (entry as? JsonObject)?.let { Model.fromJson(it) } }
    }

    /**
     * 单条模型的写入：只改 `models[]` 里 **id 相同的那一条**（没有就追加），
     * 其余模型、厂商块上的其它键、根上的键都不动。
     *
     * 与导入保存是同一条语义：写了 `models[]` 一条，pi 就把同 id 的目录定义整条替换成
     * 这一份（`provider-composer.ts:203-206`）。所以调用方给的 [model] 必须**带全**它
     * 知道的能力字段，不能只带用户改的那一项 —— 只带一项等于把其余能力清零。
     *
     * @return null 成功，否则一句给人看的话。
     */
    fun upsertModel(providerId: String, model: Model): String? {
        if (providerId.isBlank()) return "厂商 id 不能为空"
        val snapshot = read()
        snapshot.error?.let { return "已拒绝写入：$it" }
        val block = snapshot.providers[providerId] as? JsonObject
        val next = declaredModels(providerId).filterNot { it.id == model.id } + model
        return upsert(
            Provider(
                id = providerId,
                name = (block?.get("name") as? JsonPrimitive)?.content,
                baseUrl = (block?.get("baseUrl") as? JsonPrimitive)?.content,
                api = (block?.get("api") as? JsonPrimitive)?.content,
                authHeader = (block?.get("authHeader") as? JsonPrimitive)?.content?.toBooleanStrictOrNull(),
                models = next,
            ),
        )
    }

    /**
     * 删掉一条申报（"恢复 pi 的目录定义"）。同 id 不在 `models[]` 里时什么也不写。
     *
     * 注意：删掉最后一条会让 `models` 整个键消失，pi 于是回到"这家厂商的模型完全由
     * 目录决定"（`docs/known-gaps.md` §M11）。
     */
    fun removeDeclaredModel(providerId: String, modelId: String): String? {
        if (providerId.isBlank()) return "厂商 id 不能为空"
        val snapshot = read()
        snapshot.error?.let { return "已拒绝写入：$it" }
        val current = declaredModels(providerId)
        if (current.none { it.id == modelId }) return null
        val block = snapshot.providers[providerId] as? JsonObject
        val remaining = current.filterNot { it.id == modelId }
        // 删掉最后一条后，pi 要求厂商块至少还带一个**连接类**键
        // （baseUrl / headers / compat / modelOverrides / apiKey / oauth /
        // authHeader —— `provider-composer.js:157-165`）。只剩 name/api/models 的块
        // 在 models 被删空之后就是一个 `{}`，pi 会把整份 models.json 判成无效。
        // 这时整个厂商块一起删掉，"回到 pi 目录"才是真的回到目录。
        if (remaining.isEmpty()) {
            val carriesConnection = block?.keys?.any {
                it == "baseUrl" || it == "headers" || it == "compat" ||
                    it == "modelOverrides" || it == "apiKey" || it == "oauth" ||
                    it == "authHeader"
            } == true
            if (!carriesConnection) return remove(providerId)
        }
        return upsert(
            Provider(
                id = providerId,
                name = (block?.get("name") as? JsonPrimitive)?.content,
                baseUrl = (block?.get("baseUrl") as? JsonPrimitive)?.content,
                api = (block?.get("api") as? JsonPrimitive)?.content,
                authHeader = (block?.get("authHeader") as? JsonPrimitive)?.content?.toBooleanStrictOrNull(),
                models = remaining,
            ),
        )
    }

    /** The model ids already configured for [providerId]. */
    fun configuredModelIds(providerId: String): List<String> {
        val block = read().providers[providerId] as? JsonObject ?: return emptyList()
        val models = block["models"] as? JsonArray ?: return emptyList()
        return models.mapNotNull { (it as? JsonObject)?.get("id")?.let { id -> (id as? JsonPrimitive)?.content } }
    }

    /**
     * Write or replace one provider block, preserving every other provider **and
     * every key pi's schema allows that this class does not model**.
     *
     * The second half of that sentence was a lie until `docs/known-gaps.md` §M12:
     * this used to do `next[provider.id] = provider.toJson()`, which rebuilds the
     * block out of the five keys [Provider] knows and therefore deletes the rest.
     * pi's `ProviderConfigSchema` (`core/model-config.ts:201-213`) allows eleven:
     *
     * | key | written by the app | why it matters |
     * |---|---|---|
     * | `name`, `baseUrl`, `api`, `authHeader` | yes | the credential form's own fields |
     * | `models` | yes, authoritatively | see [mergeProvider] |
     * | `headers` | **no** | custom HTTP headers on every request to this provider |
     * | `compat` | **no** | the compatibility flags most non-OpenAI endpoints need (`:60-140`) |
     * | `oauth` | **no** | `"radius"`; `applyModelsJson` throws without a `baseUrl` when set (`provider-composer.ts:182-184`) |
     * | `apiKey` | **no** | a key placed in `models.json` instead of `auth.json` |
     * | `modelOverrides` | **no** | the *merging* way to adjust a model pi already knows (`provider-composer.ts:106-118`) |
     *
     * A per-model entry loses five more the same way — `baseUrl`, `thinkingLevelMap`,
     * `samplingParams`, `headers`, `compat`, plus `cost.tiers`
     * (`core/model-config.ts:154-168`) — which [mergeProvider] also preserves.
     *
     * @return null on success, else a message.
     */
    fun upsert(provider: Provider): String? {
        if (provider.id.isBlank()) return "厂商 id 不能为空"
        // `null` = "pi's own definition stands, do not write this key" and is legal; a blank
        // *string* is still refused, because a written `""` is never a real endpoint and fails
        // pi's `minLength: 1` (`schemas/models.schema.json`).
        if (provider.baseUrl?.isBlank() == true) return "Base URL 不能为空"
        if (provider.api?.isBlank() == true) return "api 类型不能为空"
        // No "at least one model" rule: pi does not have one. `models` is optional
        // (`model-config.ts:208`) and `applyModelsJson` asks only that the block carry one of
        // baseUrl/headers/compat/modelOverrides/models/apiKey/oauth/authHeader
        // (`provider-composer.js:157-165` — and note `api`/`name` do **not** count).
        // `PiCredentialService.save` always writes `authHeader`, so a block it produces
        // satisfies that even when it omits `baseUrl`/`api`; a caller that omits all three must
        // write no block at all, or pi records a composition error and
        // `synchronizeCredentialState` fails right after the save (`model-runtime.js:404-410`).
        // An empty list is a real state — "this provider's models come from pi's catalog and
        // the app is not re-declaring them" (docs/known-gaps.md §M11).
        if (provider.models.any { it.id.isBlank() }) return "模型 id 不能为空"

        val snapshot = read()
        snapshot.error?.let { return "已拒绝写入：$it" }

        val next = LinkedHashMap<String, JsonElement>(snapshot.providers)
        next[provider.id] = mergeProvider(snapshot.providers[provider.id] as? JsonObject, provider)
        // 根也走合并：`providers` 之外（`$schema`、别人写的键）原样保留 —— 以前这里重建
        // 文档，保存一次厂商就把它们全抹了（`PiModelsMerge.root`）。
        val root = PiModelsMerge.root(snapshot.root, JsonObject(next))
        val text = root.toString()
        if (!PiConfigFiles.write(file, text)) {
            return "写入模型配置失败（${file.absolutePath}）"
        }
        mirror?.let { PiConfigFiles.write(it, text) }
        return null
    }

    /**
     * One provider block, written over whatever was already there.
     *
     * The rules live in [PiModelsMerge] rather than here so they can be run on a bare
     * JVM (`PackagesPureLogicCheck`); this call site is only the file plumbing.
     */
    private fun mergeProvider(existing: JsonObject?, provider: Provider): JsonObject =
        PiModelsMerge.provider(existing, provider.toJson())

    /** Remove a provider block entirely. Returns null on success. */
    fun remove(providerId: String): String? {        val snapshot = read()
        snapshot.error?.let { return "已拒绝写入：$it" }
        val next = LinkedHashMap<String, JsonElement>(snapshot.providers)
        if (next.remove(providerId) == null) return null
        val root = PiModelsMerge.root(snapshot.root, JsonObject(next))
        val text = root.toString()
        if (!PiConfigFiles.write(file, text)) return "写入模型配置失败"
        mirror?.let { PiConfigFiles.write(it, text) }
        return null
    }
}

/**
 * The `settings.json` writes the import needs: which models are enabled for cycling.
 *
 * `enabledModels` is the only key that import writes. pi's `Settings` has three
 * *selection* keys (`core/settings-manager.ts:108-139`) — `defaultProvider`,
 * `defaultModel`, `enabledModels` — but the first two are edited on the model screen
 * itself (`DefaultModelPairStore`, which writes them as one pair), not here: the
 * import form never had a "设为默认" control, so the writer that could only be
 * called from that non-existent control was removed rather than left as a dead
 * path. The definitions themselves are in `auth.json` / `models.json` — see
 * [PiConfigFiles].
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
     * `enabledModels` **整表**写入 —— 列表怎么算（保留谁、替换谁、要不要写）是
     * [ModelSelectionPlan] 的判定，这里只是它的执行端。整表替换与 pi 自己一致
     * （`settings-manager.ts:1325` 直接 `= patterns`），条目必须是 `provider/modelId`
     * 限定名或用户自己的 glob（`settings-manager.ts:139`，同 `--models` 语法）。
     *
     * 空列表 = **删键**：pi 把"没有这个键"当全部模型可循环（`main.ts:789` 只在
     * `length > 0` 时解析作用域），`store.remove` 会走 `SettingsDocument.removePath`
     * 把清空后的父节点一起剪掉，文件里不会留下 `[]` 这种与"未设置"等价的空值。
     *
     * @return null on success, else a message.
     */
    fun setEnabledModels(patterns: List<String>): String? = runCatching {
        PiConfigFiles.withLock(File(agentDir, "settings.json")) {
            if (patterns.isEmpty()) {
                store.remove("enabledModels")
            } else {
                store.write("enabledModels", JsonArray(patterns.map { JsonPrimitive(it) }))
            }
            store.invalidate()
            null
        }
    }.getOrElse { error -> "写入循环模型列表失败：${error.message ?: error::class.java.simpleName}" }

    /**
     * 当前 `enabledModels` 的原样条目 —— [ModelSelectionPlan] 合并时要保留的"别人"。
     *
     * 读不到、不是数组、条目不是字符串：一律跳过而不是猜。这是写入前的**输入**，
     * 把坏值当成空列表会让合并把"读失败"误判成"没有要保留的"，所以这里宁可少保留
     * 一条，也不编造：坏 JSON 的场景由 `PiCredentialService.prefill` 的
     * `modelsFileError`/`authFileError` 在界面上单独报，不靠这个方法吞掉。
     */
    // 表达式体函数禁止 `return`（CI 在 a4af7bd 报的正是这一行），改块体。
    fun enabledModels(): List<String> {
        val array = store.read("enabledModels") as? JsonArray ?: return emptyList()
        return array.mapNotNull { entry -> (entry as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } }
    }

    /**
     * 删掉一个厂商后，把它留在 `settings.json` 里的引用一起清掉：
     *
     *  - `defaultProvider` 正是它 → 连 `defaultModel` 一起删（只剩模型名会让 pi 把模型
     *    挂到一个已经不存在的厂商上；只剩厂商则会退回该厂商的默认模型）；
     *  - `enabledModels` 里 `厂商/…` 的条目 → 删掉（它们永远不会再匹配任何模型）。
     *
     * 只删**能确定归属**的条目：裸 id 与通配符可能也匹配别的厂商，原样保留。
     * 返回 null 表示成功，否则一句给人看的话。
     */
    fun clearModelReferences(providerId: String): String? {
        if (providerId.isBlank()) return null
        val defaultProvider = (store.read("defaultProvider") as? JsonPrimitive)?.content
        if (defaultProvider == providerId) {
            store.remove("defaultModel")
            store.remove("defaultProvider")
        }
        val patterns = enabledModels()
        val kept = patterns.filterNot { it.trim().startsWith("$providerId/") }
        if (kept.size != patterns.size) return setEnabledModels(kept)
        return null
    }

    /** Currently selected provider/model, for prefilling the editor. */
    fun current(): Pair<String?, String?> {
        val provider = (store.read("defaultProvider") as? JsonPrimitive)?.content
        val model = (store.read("defaultModel") as? JsonPrimitive)?.content
        return provider to model
    }
}
