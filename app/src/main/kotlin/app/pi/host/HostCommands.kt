package app.pi.host

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** `host.hello` 的应答。[capabilities] 决定哪些入口亮起，不要硬编码「有没有」。 */
data class HostHello(
    val protocol: Int,
    val engine: String,
    val node: String?,
    val commands: Set<String>,
    val capabilities: Set<String>,
)

/** `chat.send` / `session.prompt` 的预检结果：started 开了新一轮 / queued 进了队列 / handled 被扩展命令吃掉。 */
enum class SendDisposition { Started, Queued, Handled, Unknown }

/** 变更类命令的应答：命令自己的返回 + 宿主附带的最新状态。 */
class HostReply(val data: JsonObject, val state: JsonObject?)

/** `session.open` 的参数。字段与 protocol.md 一一对应；未设置的不发。 */
data class OpenArgs(
    val sessionFile: String? = null,
    val sessionId: String? = null,
    val continueRecent: Boolean = false,
    val sessionDir: String? = null,
    val flags: Map<String, JsonElement> = emptyMap(),
    val noExtensions: Boolean = false,
    val noSkills: Boolean = false,
    val noPromptTemplates: Boolean = false,
    val noThemes: Boolean = false,
    val noContextFiles: Boolean = false,
    /** 例 `mapOf("mcp" to false)`：关掉某个官方内置扩展。默认全开。 */
    val builtins: Map<String, Boolean> = emptyMap(),
    /** null = 让宿主按 pi 的信任库决定；true/false = 用户已决定。 */
    val trust: Boolean? = null,
) {
    fun toJson(): JsonObject = obj {
        val session = obj {
            sessionFile?.let { put("file", JsonPrimitive(it)) }
            sessionId?.let { put("id", JsonPrimitive(it)) }
            if (continueRecent) put("continue", JsonPrimitive(true))
        }
        if (session.isNotEmpty()) put("session", session)
        sessionDir?.let { put("sessionDir", JsonPrimitive(it)) }
        if (flags.isNotEmpty()) put("flags", JsonObject(flags))
        val resources = obj {
            if (noExtensions) put("noExtensions", JsonPrimitive(true))
            if (noSkills) put("noSkills", JsonPrimitive(true))
            if (noPromptTemplates) put("noPromptTemplates", JsonPrimitive(true))
            if (noThemes) put("noThemes", JsonPrimitive(true))
            if (noContextFiles) put("noContextFiles", JsonPrimitive(true))
        }
        if (resources.isNotEmpty()) put("resources", resources)
        if (builtins.isNotEmpty()) put("builtins", JsonObject(builtins.mapValues { JsonPrimitive(it.value) }))
        trust?.let { put("trust", JsonPrimitive(it)) }
    }
}

/**
 * 具名命令层：每条命令一个 suspend 函数，参数是类型化的，返回 [HostReply] / 小 data class。
 *
 * 命名沿用旧 RPC 的（`prompt`、`abort`…）以减少 ViewModel 的改动点，对照表见
 * docs/sdk-host/rpc-to-host.md。**只放最常用的那批**；其余命令一律走 [call] / [sdkCall]，
 * 这是故意的：宿主加命令不该逼着这个文件跟着改（docs/sdk-host/upgrading.md）。
 *
 * 每个变更类命令的应答里的 `state` 都会喂给 [stateStore]，UI 状态因此只有一个来源。
 */
class HostCommands(
    val client: PiHostClient,
    val stateStore: HostStateStore = HostStateStore(),
) {
    // ---- host ----

    suspend fun hello(): HostHello {
        val d = client.callObject("host.hello")
        return HostHello(
            protocol = d.long("protocol")?.toInt() ?: 0,
            engine = d.str("engine") ?: "coding-agent",
            node = d.str("node"),
            commands = d.strList("commands").toSet(),
            capabilities = d.strList("capabilities").toSet(),
        )
    }

    suspend fun ping(): JsonObject = client.callObject("host.ping")
    suspend fun shutdown() { client.call("host.shutdown") }

    // ---- session ----

    /** 只能调一次。先应答，扩展在后台绑（`ext.bound` 事件）。 */
    suspend fun open(args: OpenArgs, timeoutMs: Long = OPEN_TIMEOUT_MS): HostReply = reply("session.open", args.toJson(), timeoutMs)

    /** 对账：重连、seq 跳号时调。`messages=true` 带全部消息（大）。 */
    suspend fun snapshot(messages: Boolean = false): JsonObject {
        val d = client.callObject("session.snapshot", obj { if (messages) put("messages", JsonPrimitive(true)) })
        stateStore.offer(d)
        return d
    }

    /** 发送按钮用这个：空闲开新一轮，流式中按 [followUp] 排队。预检通过即返回，不等整轮。 */
    suspend fun send(message: String, images: JsonArray? = null, followUp: Boolean = false): SendDisposition {
        val r = reply("chat.send", obj {
            put("message", JsonPrimitive(message))
            if (images != null && images.isNotEmpty()) put("images", images)
            if (followUp) put("behavior", JsonPrimitive("followUp"))
        })
        return when ((r.data["disposition"] as? JsonPrimitive)?.content) {
            "started" -> SendDisposition.Started
            "queued" -> SendDisposition.Queued
            "handled" -> SendDisposition.Handled
            else -> SendDisposition.Unknown
        }
    }

    suspend fun stop(clearQueue: Boolean = false): HostReply =
        reply("chat.stop", obj { if (clearQueue) put("clearQueue", JsonPrimitive(true)) })

    suspend fun newSession(parentSession: String? = null): HostReply =
        reply("session.new", obj { parentSession?.let { put("parentSession", JsonPrimitive(it)) } })

    suspend fun switchSession(sessionPath: String): HostReply =
        reply("session.switch", obj { put("sessionPath", JsonPrimitive(sessionPath)) })

    suspend fun fork(entryId: String): HostReply = reply("session.fork", obj { put("entryId", JsonPrimitive(entryId)) })
    suspend fun clone(): HostReply = reply("session.clone")
    /** 会话树内跳转（官方 `/tree`，`navigateTree`）：叶子移到更早的条目，可选为被离开的分支写摘要。 */
    suspend fun navigate(targetId: String, summarize: Boolean = false, customInstructions: String? = null, replaceInstructions: Boolean? = null, label: String? = null): HostReply =
        reply("session.navigate", obj {
            put("targetId", JsonPrimitive(targetId))
            if (summarize) put("summarize", JsonPrimitive(true))
            customInstructions?.let { put("customInstructions", JsonPrimitive(it)) }
            replaceInstructions?.let { put("replaceInstructions", JsonPrimitive(it)) }
            label?.let { put("label", JsonPrimitive(it)) }
        }, LONG_TIMEOUT_MS)
    /** 给条目打标签（会话树书签）；`label` 为空 = 清除。 */
    suspend fun setLabel(entryId: String, label: String?): HostReply =
        reply("session.label", obj {
            put("entryId", JsonPrimitive(entryId))
            label?.let { put("label", JsonPrimitive(it)) }
        })

    /** 把更早的条目从模型上下文里隐藏（只影响模型看到的，不删记录）。替换内容用 [replaceInContext]。 */
    suspend fun hideFromContext(targetId: String): HostReply =
        reply("session.contextEdit", obj { put("targetId", JsonPrimitive(targetId)); put("replacement", JsonNull) })

    suspend fun replaceInContext(targetId: String, text: String): HostReply =
        reply("session.contextEdit", obj {
            put("targetId", JsonPrimitive(targetId))
            put("replacement", JsonObject(mapOf("content" to JsonArray(listOf(JsonObject(mapOf("type" to JsonPrimitive("text"), "text" to JsonPrimitive(text))))))))
        })

    /** 当前分支（叶到根的条目，原始 JSON）。 */
    suspend fun branchPath(fromId: String? = null): JsonObject =
        client.callObject("session.path", obj { fromId?.let { put("fromId", JsonPrimitive(it)) } })

    suspend fun compact(): HostReply = reply("session.compact", timeoutMs = LONG_TIMEOUT_MS)
    suspend fun reload(): HostReply = reply("session.reload", timeoutMs = LONG_TIMEOUT_MS)
    suspend fun setName(name: String): HostReply = reply("session.name", obj { put("name", JsonPrimitive(name)) })

    // ---- model / thinking ----

    suspend fun setModel(provider: String, modelId: String): HostReply =
        reply("model.set", obj { put("provider", JsonPrimitive(provider)); put("modelId", JsonPrimitive(modelId)) })

    suspend fun cycleModel(): HostReply = reply("model.cycle")
    suspend fun setThinking(level: String): HostReply = reply("thinking.set", obj { put("level", JsonPrimitive(level)) })
    suspend fun cycleThinking(): HostReply = reply("thinking.cycle")

    // ---- project ----

    suspend fun setTrust(trusted: Boolean): HostReply =
        reply("project.trust", obj { put("trusted", JsonPrimitive(trusted)) }, LONG_TIMEOUT_MS)

    // ---- 通用口（升级的杠杆）----

    /** 任意命令。宿主新增的命令不用等这个文件，直接用它。 */
    suspend fun call(cmd: String, args: JsonObject = JsonObject(emptyMap()), timeoutMs: Long? = null): HostReply = reply(cmd, args, timeoutMs)

    /** 调 SDK 对象上的任意公开方法（`session / runtime / sessionManager / settings / …`）。 */
    suspend fun sdkCall(target: String, method: String, params: List<JsonElement> = emptyList()): JsonObject =
        client.callObject("sdk.call", obj {
            put("target", JsonPrimitive(target)); put("method", JsonPrimitive(method)); put("params", JsonArray(params))
        })

    suspend fun sdkGet(target: String, prop: String): JsonObject =
        client.callObject("sdk.get", obj { put("target", JsonPrimitive(target)); put("prop", JsonPrimitive(prop)) })

    suspend fun busSubscribe(names: List<String>) {
        client.call("bus.subscribe", obj { put("names", JsonArray(names.map { JsonPrimitive(it) })) })
    }

    suspend fun subagentRpc(method: String, params: JsonObject = JsonObject(emptyMap()), timeoutMs: Long? = null): JsonObject =
        client.callObject("subagent.rpc", obj {
            put("method", JsonPrimitive(method)); put("params", params)
            timeoutMs?.let { put("timeoutMs", JsonPrimitive(it)) }
        }, timeoutMs = timeoutMs?.plus(5_000))

    // ---- ui 回路 ----

    suspend fun uiInput(surfaceId: String, data: String) {
        client.call("ui.input", obj { put("surfaceId", JsonPrimitive(surfaceId)); put("data", JsonPrimitive(data)) })
    }

    suspend fun uiResize(width: Int, height: Int? = null) {
        client.call("ui.resize", obj { put("width", JsonPrimitive(width)); height?.let { put("height", JsonPrimitive(it)) } })
    }

    suspend fun uiClose(surfaceId: String) { client.call("ui.close", obj { put("surfaceId", JsonPrimitive(surfaceId)) }) }
    suspend fun uiPalette(palette: Map<String, String>) {
        client.call("ui.palette", obj { put("palette", JsonObject(palette.mapValues { JsonPrimitive(it.value) })) })
    }

    // ---- 内部 ----

    private suspend fun reply(cmd: String, args: JsonObject = JsonObject(emptyMap()), timeoutMs: Long? = null): HostReply {
        val data = client.callObject(cmd, args, timeoutMs)
        val state = data["state"] as? JsonObject
        stateStore.offer(state)
        return HostReply(data, state)
    }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.long(k: String): Long? = (this[k] as? JsonPrimitive)?.content?.toLongOrNull()
    private fun JsonObject.strList(k: String): List<String> =
        (this[k] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.orEmpty()

    companion object {
        /** 附件 → `images` 参数（SDK 的 `ImageContent`：`{type:"image", data(base64), mimeType}`）。 */
        fun imagesJson(images: List<app.pi.rpc.PiImage>): JsonArray = JsonArray(
            images.map {
                JsonObject(mapOf("type" to JsonPrimitive("image"), "data" to JsonPrimitive(it.base64), "mimeType" to JsonPrimitive(it.mimeType)))
            },
        )

        /** `session.open` 要加载 SDK、读配置、建会话，手机上比普通命令慢得多。 */
        const val OPEN_TIMEOUT_MS = 120_000L
        /** compact / reload 会调模型或重载全部资源。 */
        const val LONG_TIMEOUT_MS = 180_000L
    }
}

private inline fun obj(build: MutableMap<String, JsonElement>.() -> Unit): JsonObject =
    JsonObject(LinkedHashMap<String, JsonElement>().apply(build))

/** `session.prompt` / `session.steer` / `session.followUp` 的参数：`{message, images?}`。 */
fun sendArgs(message: String, images: List<app.pi.rpc.PiImage>): JsonObject = JsonObject(
    buildMap {
        put("message", JsonPrimitive(message))
        if (images.isNotEmpty()) put("images", HostCommands.imagesJson(images))
    },
)
