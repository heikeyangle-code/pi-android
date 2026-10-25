package app.pi.packages

import android.content.Context
import android.os.Environment
import app.pi.runtime.GuestTreeReaper
import app.pi.runtime.ProrootLaunchHandle
import app.pi.runtime.RuntimeSelection
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 「订阅登录」的执行端：在 guest 里跑**pi 自己的** OAuth 流程，App 只画提示、收结果。
 *
 * ## 这一层为什么存在（而不是在 Kotlin 里重写九套 OAuth）
 *
 * pi 的订阅登录是九个各不相同的流程：PKCE + 回环回调服务器（anthropic / openai /
 * openrouter / radius）、RFC 8628 设备码（xai / kimi / meta / copilot / openai-codex）、
 * GitHub token 换 Copilot token、Meta 身份 token 换一次 API key、ChatGPT 需要设备 id
 * 的 `urn:uuid:`、以及各家自己的 client id / scope / header。这些都不是 pi 的 API，
 * 是**钉住的那份引擎的内部实现**；在 Kotlin 里抄一份，引擎一升级就变成 `invalid_client`。
 *
 * 所以脚本（[SCRIPT_ASSET]）在 guest 里 `import` 钉住版本的 `pi-ai`，用 pi 的
 * `auth.oauth.login(interaction)` 真跑一遍；App 只做两件事：把 `interaction` 的提示
 * 画出来（[Event.Prompt] / [Event.Notify]），把拿到的 credential 写进 `auth.json`
 * （pi 自己的格式，[PiCredentialService.setOAuthCredential]）。
 *
 * 提供商的清单也不是 App 维护的：脚本里 `builtinProviders()` 过滤 `auth.oauth`，
 * 所以 pi 升级新增订阅提供商时，这一屏**自动**多一行 —— 这正是「不影响升级」的落地方式。
 *
 * ## 进程边界
 *
 * 一次性 `node` 子进程（不是引擎）：起来、跑一个流程、退出。`list` 也是同一个进程，
 * 发一条 `list` 命令收一条 `providers` 事件。stdin/stdout 是一行一个 JSON 的协议，
 * 格式写在脚本头部；这里只负责收发与生命周期（[close] 会连同 guest 里的子进程树一起收掉，
 * 避免 proroot 下泄漏一个还在轮询的 node）。
 */
class PiOAuthHost(
    private val context: Context,
    private val layout: AgentLayout,
) {

    /** 一行订阅提供商，字段就是 pi `auth.oauth` 上那几个。 */
    data class Provider(
        val id: String,
        val name: String,
        /** pi 自己的显示名，例如「Anthropic (Claude Pro/Max)」。 */
        val oauthName: String,
        val loginLabel: String?,
        /** `isSubscription`：pi 认为这是"订阅"而不是 OpenRouter/Radius 那种网关授权。 */
        val subscription: Boolean,
    )

    /** pi `AuthEvent` 的直通，加上 App 自己的几种。 */
    sealed interface Event {
        data class Providers(val providers: List<Provider>) : Event

        /**
         * pi 的 `notify` 原样：`type` 是 `auth_url` / `device_code` / `progress` / `info`。
         * 设备码流程的 `userCode` / `verificationUri` / 轮询间隔都在这里。
         */
        data class Notify(val raw: JsonObject) : Event

        data class Prompt(
            val promptId: Int,
            val type: String,
            val message: String,
            val placeholder: String?,
            val options: List<Option>,
        ) : Event {
            data class Option(val id: String, val label: String, val description: String?)
        }

        /** 登录成功：`credential` 是 pi 的 `OAuthCredential`（`type: "oauth"` + tokens）。 */
        data class Credential(val provider: String, val credential: JsonObject) : Event

        data object Cancelled : Event

        data class Error(val message: String) : Event
    }

    /**
     * 订阅登录的交互会话语义：一个进程、一个流程。
     *
     * [events] 只能收一次（`callbackFlow`）；[answer] 回答当前提示（`select` 传选项 id，
     * 其余传文本）；[cancel] 让 pi 的流程自己抛 `Login cancelled`；[close] 无条件收进程树。
     */
    class Session internal constructor(
        private val process: Process,
        val events: Flow<Event>,
        private val onClose: () -> Unit,
        /** 这个进程最后几行 stderr：桥没响应时，错误只在这里看得到。 */
        val diagnostics: () -> String = { "" },
    ) {
        fun answer(promptId: Int, value: String) {
            send(JsonObject(mapOf("cmd" to JsonPrimitive("answer"), "promptId" to JsonPrimitive(promptId), "value" to JsonPrimitive(value))))
        }

        fun cancel() {
            send(JsonObject(mapOf("cmd" to JsonPrimitive("cancel"))))
        }

        fun close() {
            runCatching { process.outputStream.close() }
            onClose()
        }

        private fun send(command: JsonObject) {
            runCatching {
                process.outputStream.write((command.toString() + "\n").toByteArray())
                process.outputStream.flush()
            }
        }
    }

    /** 启动器：[guestCommand] 的拼法与引擎/包命令同一套（[RuntimeSelection]）。 */
    private fun start(commands: List<JsonObject>, extraEnv: Map<String, String>): Session {
        val allJs = officialAllJs()
            ?: error("运行时里找不到 pi 的 OAuth 模块（pi-ai 的 dist/providers/all.js）。先启动一次引擎再试。")
        val script = installScript()
        val guestScript = "/" + script.relativeTo(layout.paths.rootfs).path
        val guestAllJs = "/" + allJs.relativeTo(layout.paths.rootfs).path
        val selection = RuntimeSelection.of(context, layout.paths)
        val plan = selection.plan(
            guestCommand = "exec /opt/node/bin/node $guestScript $guestAllJs",
            cwd = layout.guestWorkspace,
            storage = Environment.getExternalStorageDirectory(),
            extraEnv = extraEnv,
        )
        // Armed **before** the process exists, exactly like the engine and `GuestCommand`:
        // proroot's launcher writes a config table after this moment, and that table is
        // the only way to learn the launcher's pid on this platform (`Process.pid()` is
        // not available — see `ProrootLaunchHandle`). Without it a cancelled login on
        // proroot would leave `bash` + a polling Node behind.
        val handle = if (plan.usingProroot) {
            ProrootLaunchHandle.arm(layout.paths.prorootTmpDir(), plan.launchToken)
        } else {
            null
        }
        val process = ProcessBuilder(plan.argv)
            .directory(layout.paths.runtime)
            .also { builder -> builder.environment().putAll(plan.environment) }
            .start()
        handle?.resolveLauncherPid()
        // stderr 以前是直接丢掉的：node 起不来/`import` 失败时，那几十行是唯一的线索，
        // 而界面只会停在"正在读取"。留下最后 40 行，失败时带出去。
        val stderr = java.util.Collections.synchronizedList(mutableListOf<String>())
        Thread {
            runCatching {
                process.errorStream.bufferedReader().forEachLine { line ->
                    if (line.isNotBlank()) {
                        synchronized(stderr) {
                            stderr.add(line)
                            if (stderr.size > 40) stderr.removeAt(0)
                        }
                    }
                }
            }
        }.apply { isDaemon = true }.start()
        runCatching {
            commands.forEach { process.outputStream.write((it.toString() + "\n").toByteArray()) }
            process.outputStream.flush()
        }
        val events = readEvents(process)
        return Session(
            process = process,
            events = events,
            diagnostics = {
                synchronized(stderr) { stderr.joinToString(" / ") }
            },
            onClose = {
                // Capture **before** killing: once the launcher dies its children are
                // reparented to init and the tree can no longer be told apart from any
                // other process (`GuestTreeReaper.capture`).
                val captured = handle?.launcherPid?.let { pid ->
                    runCatching { GuestTreeReaper.capture(pid, expectedStartTime = handle.launcherStartTime) }.getOrNull()
                }
                runCatching { process.destroy() }
                handle?.deleteConfig()
                if (captured != null) GuestTreeReaper.reapInBackground(captured)
            },
        )
    }

    /**
     * 列出 pi 支持的订阅提供商。失败时返回错误信息（不是空列表：空列表会被读成"pi 不支持"）。
     *
     * ## 为什么有缓存
     *
     * `list` 要起一个 node 进程并 `import` 整份 `pi-ai`（42 个厂商 + 模型目录，主机的
     * 一次干净跑就要 1.5 s 上下，手机上走 proot 只会更慢）。而清单**只随 pi 版本变**：
     * 缓存键就是 `pi-ai/package.json` 的 version，升级引擎后自动失效重读，因此新版本
     * 新增的订阅提供商仍然会自动出现 —— 缓存没有削弱「跟 pi 对齐」这一点。
     */
    suspend fun providers(): Result<List<Provider>> = withContext(Dispatchers.IO) {
        val version = piAiVersion()
        readCachedProviders(version)?.let { return@withContext Result.success(it) }
        runCatching {
            val session = start(listOf(JsonObject(mapOf("cmd" to JsonPrimitive("list")))), deviceIdEnv())
            try {
                // `first` rather than `collect`: the bridge stays alive after answering
                // (it is written as one process for list + login), so waiting for the flow
                // to complete would wait for a process that is waiting for us.
                val terminal = withTimeoutOrNull(PROVIDER_LIST_TIMEOUT_MS) {
                    session.events.first { it is Event.Providers || it is Event.Error }
                } ?: throw IllegalStateException(
                    "读取订阅列表超时。运行时回显：" +
                        session.diagnostics().ifEmpty { "（没有输出，可能是 node 没起来或 pi-ai 路径不对）" },
                )
                val providers = when (terminal) {
                    is Event.Providers -> terminal.providers
                    is Event.Error -> throw IllegalStateException(terminal.message)
                    else -> throw IllegalStateException("读不到 pi 的订阅提供商列表")
                }
                writeCachedProviders(version, providers)
                providers
            } finally {
                session.close()
            }
        }
    }

    // ------------------------------------------------------------- cache

    /** `pi-ai/package.json` 的 version，缓存键；读不到时退化成"不快照"。 */
    private fun piAiVersion(): String? {
        val allJs = officialAllJs() ?: return null
        val packageJson = File(allJs.parentFile.parentFile.parentFile, "package.json")
        val text = runCatching { packageJson.readText() }.getOrNull() ?: return null
        val document = PiConfigFiles.parseObject(text) ?: return null
        return (document["version"] as? JsonPrimitive)?.content
    }

    private fun readCachedProviders(version: String?): List<Provider>? {
        if (version == null) return null
        val prefs = context.getSharedPreferences("pi-oauth", Context.MODE_PRIVATE)
        if (prefs.getString(KEY_CACHE_VERSION, null) != version) return null
        val text = prefs.getString(KEY_CACHE_PROVIDERS, null) ?: return null
        val array = runCatching { Json.parseToJsonElement(text) as? JsonArray }.getOrNull() ?: return null
        return array.mapNotNull { element -> (element as? JsonObject)?.let { providerOf(it) } }
            .takeIf { it.isNotEmpty() }
    }

    private fun writeCachedProviders(version: String?, providers: List<Provider>) {
        if (version == null) return
        val array = JsonArray(
            providers.map { provider ->
                buildJsonObject {
                    put("id", provider.id)
                    put("name", provider.name)
                    put("oauthName", provider.oauthName)
                    put("loginLabel", provider.loginLabel)
                    put("subscription", provider.subscription)
                }
            },
        )
        context.getSharedPreferences("pi-oauth", Context.MODE_PRIVATE).edit()
            .putString(KEY_CACHE_VERSION, version)
            .putString(KEY_CACHE_PROVIDERS, array.toString())
            .apply()
    }

    /**
     * 运行时读不到时的兜底清单：pi 1.3.0 的九个 `auth.oauth` 提供商。
     *
     * 只在 [providers] 失败/超时时用，并且界面必须说明「这是内置清单」——它可能比设备上
     * 的 pi 旧。它保证的是**这一页不空转**：登录仍然把 id 交给运行时，pi 不认识就会给出
     * 自己的错误，而不是由这张表假装成功。
     */
    val fallbackProviders: List<Provider> = listOf(
        Provider("anthropic", "Anthropic", "Anthropic (Claude Pro/Max)", null, true),
        Provider("openai", "OpenAI", "OpenAI (ChatGPT subscription)", "Sign in with ChatGPT", true),
        Provider("openai-codex", "OpenAI Codex (legacy)", "OpenAI (ChatGPT Plus/Pro)", null, true),
        Provider("github-copilot", "GitHub Copilot", "GitHub Copilot", null, true),
        Provider("openrouter", "OpenRouter", "OpenRouter OAuth", "Sign in with OpenRouter", false),
        Provider("kimi-coding", "Kimi For Coding", "Kimi Code (subscription)", "Sign in with Kimi Code", true),
        Provider("meta", "Meta", "Meta (Muse subscription)", "Sign in with Meta", true),
        Provider("xai", "xAI", "xAI (Grok/X subscription)", "Sign in with SuperGrok or X Premium", true),
        Provider("radius", "Radius", "Radius", null, false),
    )

    /** 开始一次登录。调用方必须消费 [Session.events] 并最终 [Session.close]。 */
    fun login(providerId: String): Session = start(
        listOf(JsonObject(mapOf("cmd" to JsonPrimitive("login"), "provider" to JsonPrimitive(providerId)))),
        deviceIdEnv(),
    )

    // ------------------------------------------------------------- internals

    /**
     * ChatGPT 订阅登录要一个 `urn:uuid:` 设备 id（`openai-chatgpt.js` 的
     * `agentHostId`），pi 的 TUI 用自己 settings 里的 `deviceId`。App 没有理由去写
     * pi 的 settings，所以自己发一个稳定的 UUID（同一台安装永远同一个）。
     */
    private fun deviceIdEnv(): Map<String, String> {
        val prefs = context.getSharedPreferences("pi-oauth", Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_DEVICE_ID, null)
        val id = existing ?: UUID.randomUUID().toString().also { prefs.edit().putString(KEY_DEVICE_ID, it).apply() }
        return mapOf("PI_OAUTH_DEVICE_ID" to id)
    }

    /**
     * `pi-ai` 的 `dist/providers/all.js`，路径由 [PiOfficialCatalog.ROOTFS_DATA_DIRECTORIES]
     * 推出来而不是写死：依赖树是 npm 的布局决定，1.3.0 把它提升到顶层、之前嵌在
     * `pi-coding-agent/node_modules` 下。与官方模型目录共用同一份候选表，于是
     * 「目录读得到、all.js 读不到」这种半升级状态不会出现。
     */
    private fun officialAllJs(): File? {
        val rootfs = layout.paths.rootfs
        for (dataDir in PiOfficialCatalog.ROOTFS_DATA_DIRECTORIES) {
            val allJs = File(rootfs, dataDir.removeSuffix("/providers/data") + "/providers/all.js")
            if (allJs.isFile) return allJs
        }
        return null
    }

    /**
     * 把脚本写到 `<rootfs>/opt/pi/pi-oauth.mjs`，**每次启动都写**。
     *
     * 与 `scandir-fix.mjs` 同一个理由：目标在易失树里（显式修复会删掉整个 rootfs，
     * 载荷重解也会替换它），每次一小份文件换掉「上次有、这次没有」这一整类问题。
     * 先写临时名再改名：半截脚本交给 node 只会得到一句语法错误。
     */
    private fun installScript(): File {
        val target = File(layout.paths.rootfs, "opt/pi/pi-oauth.mjs")
        val bytes = context.assets.open(SCRIPT_ASSET).use { it.readBytes() }
        target.parentFile?.mkdirs()
        val staging = File(target.parentFile, target.name + ".partial")
        staging.writeBytes(bytes)
        if (!staging.renameTo(target)) {
            target.writeBytes(bytes)
            staging.delete()
        }
        return target
    }

    /**
     * stdout 一行一个 JSON → [Event]。读到进程结束（或解析失败）就关闭 flow。
     *
     * 解析失败只是**这一行**不认识：pi 的流程可能同时打印别的东西（node 的
     * experimental 警告），一行噪音不该让整次登录失败。
     */
    private fun readEvents(process: Process): Flow<Event> = callbackFlow {
        val reader = BufferedReader(InputStreamReader(process.inputStream))
        launch(Dispatchers.IO) {
            try {
                while (true) {
                    val line = reader.readLine() ?: break
                    val text = line.trim()
                    if (!text.startsWith("{")) continue
                    val json = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: continue
                    parse(json)?.let { trySend(it) }
                }
            } catch (_: Throwable) {
                // 进程被收掉时读端会抛：那是 close() 的正常路径。
            } finally {
                close()
            }
        }
        awaitClose { reader.close() }
    }

    private fun providerOf(entry: JsonObject): Provider? {
        val id = entry["id"]?.jsonPrimitive?.contentOrNullSafe() ?: return null
        val name = entry["name"]?.jsonPrimitive?.contentOrNullSafe() ?: ""
        return Provider(
            id = id,
            name = name,
            oauthName = entry["oauthName"]?.jsonPrimitive?.contentOrNullSafe() ?: name,
            loginLabel = entry["loginLabel"]?.jsonPrimitive?.contentOrNullSafe(),
            subscription = (entry["subscription"] as? JsonPrimitive)?.content == "true",
        )
    }

    private fun parse(json: JsonObject): Event? {
        // Block body, not `= when`: the prompt branch uses `?: return null`, and a `return`
        // is not allowed in a function with an expression body.
        return when (json["ev"]?.jsonPrimitive?.contentOrNullSafe()) {
        "providers" -> Event.Providers(
            (json["providers"] as? JsonArray).orEmpty().mapNotNull { element ->
                (element as? JsonObject)?.let { providerOf(it) }
            },
        )

        "notify" -> Event.Notify(json)

        "prompt" -> Event.Prompt(
            promptId = (json["promptId"] as? JsonPrimitive)?.intOrNull ?: return null,
            type = json["type"]?.jsonPrimitive?.contentOrNullSafe() ?: "text",
            message = json["message"]?.jsonPrimitive?.contentOrNullSafe() ?: "",
            placeholder = json["placeholder"]?.jsonPrimitive?.contentOrNullSafe(),
            options = (json["options"] as? JsonArray).orEmpty().mapNotNull { element ->
                val option = element as? JsonObject ?: return@mapNotNull null
                Event.Prompt.Option(
                    id = option["id"]?.jsonPrimitive?.contentOrNullSafe() ?: return@mapNotNull null,
                    label = option["label"]?.jsonPrimitive?.contentOrNullSafe() ?: "",
                    description = option["description"]?.jsonPrimitive?.contentOrNullSafe(),
                )
            },
        )

        "credential" -> Event.Credential(
            provider = json["provider"]?.jsonPrimitive?.contentOrNullSafe() ?: "",
            credential = json["credential"] as? JsonObject ?: return null,
        )

        "cancelled" -> Event.Cancelled
        "error" -> Event.Error(json["message"]?.jsonPrimitive?.contentOrNullSafe() ?: "登录失败")
        else -> null
        }
    }

    private fun JsonPrimitive.contentOrNullSafe(): String? = if (this is kotlinx.serialization.json.JsonNull) null else content

    private companion object {
        /** 与 `scandir-fix.mjs` 同一套投放方式（`assets/guest/`）。 */
        const val SCRIPT_ASSET = "guest/pi-oauth.mjs"
        const val KEY_DEVICE_ID = "deviceId"
        const val KEY_CACHE_VERSION = "providersVersion"
        const val KEY_CACHE_PROVIDERS = "providers"

        /** 读一次清单的上限。node 在手机上冷启动约 1–3 s，20 s 足够把"卡死"和"慢"分开。 */
        const val PROVIDER_LIST_TIMEOUT_MS = 20_000L
    }
}

