package app.pi.host

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** `host.hello` 的应答。[capabilities] 决定哪些入口亮起，不要硬编码「有没有」。 */
data class HostHello(
    val protocol: Int,
    val engine: String,
    val node: String?,
    val commands: Set<String>,
    val capabilities: Set<String>,
) {
    companion object {
        /** 解析 `host.hello` 应答或 `host.ready` 内嵌握手载荷；缺少协议号或命令表时返回 null。 */
        fun fromJsonOrNull(d: JsonObject): HostHello? {
            val protocol = (d["protocol"] as? JsonPrimitive)?.longOrNull?.toInt() ?: return null
            val commands = (d["commands"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                ?.toSet()
                .orEmpty()
            if (protocol <= 0 || commands.isEmpty()) return null
            val engine = (d["engine"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "coding-agent"
            val node = (d["node"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val capabilities = (d["capabilities"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                ?.toSet()
                .orEmpty()
            return HostHello(
                protocol = protocol,
                engine = engine,
                node = node,
                commands = commands,
                capabilities = capabilities,
            )
        }
    }
}

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
    val tools: List<String>? = null,
    val excludeTools: List<String>? = null,
    val noTools: String? = null,
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
        tools?.let { put("tools", JsonArray(it.map(::JsonPrimitive))) }
        excludeTools?.let { put("excludeTools", JsonArray(it.map(::JsonPrimitive))) }
        noTools?.let { put("noTools", JsonPrimitive(it)) }
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

    suspend fun hello(timeoutMs: Long? = null): HostHello {
        val d = client.callObject("host.hello", timeoutMs = timeoutMs)
        return HostHello(
            protocol = d.long("protocol")?.toInt() ?: 0,
            engine = d.str("engine") ?: "coding-agent",
            node = d.str("node"),
            commands = d.strList("commands").toSet(),
            capabilities = d.strList("capabilities").toSet(),
        )
    }

    suspend fun ping(): JsonObject = client.callObject("host.ping")
    suspend fun shutdown(timeoutMs: Long = 3_000L) { client.call("host.shutdown", timeoutMs = timeoutMs) }

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

    suspend fun newSession(parentSession: String? = null, cwd: String? = null): HostReply =
        reply("session.new", obj {
            parentSession?.let { put("parentSession", JsonPrimitive(it)) }
            cwd?.let { put("cwd", JsonPrimitive(it)) }
        })

    suspend fun switchSession(sessionPath: String, cwd: String? = null): HostReply =
        reply("session.switch", obj {
            put("sessionPath", JsonPrimitive(sessionPath))
            cwd?.let { put("cwd", JsonPrimitive(it)) }
        })

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

    /** 项目信任的表态：[remember] 取 `folder` / `parent` / `session` / `reset` / `reset-inherited`（宿主校验）。应答是新的信任状态。 */
    suspend fun setTrust(trusted: Boolean, remember: String = "folder"): HostReply =
        reply("project.trust", obj { put("trusted", JsonPrimitive(trusted)); put("remember", JsonPrimitive(remember)) }, LONG_TIMEOUT_MS)

    suspend fun trustState(): HostReply = reply("project.trustState")

    /** 一批目录（guest 路径）各自的信任结论：`{trust:{<cwd>:{trusted,undecided,source}}}`。 */
    suspend fun trustOf(cwds: List<String>): HostReply =
        reply("project.trustOf", obj { put("cwds", JsonArray(cwds.map { JsonPrimitive(it) })) })

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

    /** 调运行时 `SettingsManager` 的 setter（`settings.set`），改完立刻 flush 并广播 `settings.changed`。 */
    suspend fun settingsSet(method: String, params: List<JsonElement> = emptyList()): JsonObject =
        client.callObject("settings.set", obj {
            put("method", JsonPrimitive(method))
            put("params", JsonArray(params))
        })

    /** 让运行中会话的 `SettingsManager` 从盘上重读 `settings.json`（`settings.reload`）。 */
    suspend fun settingsReload(): JsonObject = client.callObject("settings.reload")

    // ---- ui 回路 ----

    /** 扩展工具的渲染结果：[call] / [result] 是带 ANSI 的行；[shell] 为 `"self"` 时由扩展自绘外框。 */
    data class ToolRender(
        val call: List<String>?,
        val result: List<String>?,
        val shell: String = "default",
        val hasRenderer: Boolean = call != null || result != null,
        val label: String? = null,
    )

    /**
     * `render.tool`：让宿主调扩展工具自己的 `renderCall` / `renderResult`，按 [width] 列渲染成行。
     * [result] 为 null 时只渲染调用部分（工具还没出结果）。
     */
    suspend fun renderTool(
        toolName: String,
        toolCallId: String,
        args: JsonObject?,
        resultText: String?,
        isError: Boolean,
        expanded: Boolean,
        isPartial: Boolean,
        durationMs: Long?,
        width: Int,
        details: JsonElement? = null,
        images: List<app.pi.protocol.PiImage> = emptyList(),
        argsComplete: Boolean = true,
    ): ToolRender {
        val data = client.call("render.tool", obj {
            put("toolName", JsonPrimitive(toolName))
            put("toolCallId", JsonPrimitive(toolCallId))
            put("args", args ?: JsonObject(emptyMap()))
            if (resultText != null || details != null || images.isNotEmpty()) {
                put("result", obj {
                    // pi 的 result.content 是「文本块 + 图片块」；图片块一并交给渲染器（扩展按 type 筛）。
                    val contentBlocks = buildList {
                        if (resultText != null || images.isEmpty()) {
                            add(obj { put("type", JsonPrimitive("text")); put("text", JsonPrimitive(resultText.orEmpty())) })
                        }
                        for (img in images) {
                            add(obj { put("type", JsonPrimitive("image")); put("data", JsonPrimitive(img.base64)); put("mimeType", JsonPrimitive(img.mimeType)) })
                        }
                    }
                    put("content", JsonArray(contentBlocks))
                    // 扩展的 renderResult 读的主要是 `result.details`（进度、计数、查询列表…）；
                    // 不带它，渲染器只能看到 undefined（「undefined/undefined queries」）。
                    if (details != null) put("details", details)
                    put("isError", JsonPrimitive(isError))
                })
            }
            put("expanded", JsonPrimitive(expanded))
            put("isPartial", JsonPrimitive(isPartial))
            // 参数还在流式生成时（`toolcall_start` 占位、`toolcall_end` 才有完整参数）官方组件两者皆 false。
            put("argsComplete", JsonPrimitive(argsComplete))
            put("executionStarted", JsonPrimitive(argsComplete))
            durationMs?.let { put("durationMs", JsonPrimitive(it)) }
            put("width", JsonPrimitive(width))
        }) as? JsonObject
        fun lines(key: String) = (data?.get(key) as? JsonArray)?.map { (it as? JsonPrimitive)?.content ?: it.toString() }
        val callLines = lines("call")
        val resultLines = lines("result")
        val hasRenderer = (data?.get("hasRenderer") as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
            ?: (data?.str("reason") != "no_renderer" && (callLines != null || resultLines != null))
        val shell = data?.str("shell") ?: "default"
        val label = data?.str("label")?.takeIf { it.isNotBlank() }
        return ToolRender(call = callLines, result = resultLines, shell = shell, hasRenderer = hasRenderer, label = label)
    }

    /**
     * `render.message`：让宿主调扩展为某个 `customType` 注册的消息渲染器（`registerMessageRenderer`），
     * 按 [width] 列渲染成行。没有渲染器返回 null（调用方回落到 markdown）。
     */
    suspend fun renderMessage(
        customType: String,
        text: String,
        details: JsonElement?,
        expanded: Boolean,
        width: Int,
    ): List<String>? {
        val data = client.call("render.message", obj {
            put("customType", JsonPrimitive(customType))
            put("message", obj {
                put("role", JsonPrimitive("custom"))
                put("customType", JsonPrimitive(customType))
                put("content", JsonPrimitive(text))
                put("display", JsonPrimitive(true))
                if (details != null) put("details", details)
                put("timestamp", JsonPrimitive(System.currentTimeMillis()))
            })
            put("expanded", JsonPrimitive(expanded))
            put("width", JsonPrimitive(width))
        }) as? JsonObject
        return (data?.get("lines") as? JsonArray)?.map { (it as? JsonPrimitive)?.content ?: it.toString() }
    }

    /** `render.entry` 的结果：[hasRenderer] 为 false = 没有扩展给这个 customType 注册渲染器（pi 此时什么也不画）。 */
    data class EntryRender(val lines: List<String>?, val hasRenderer: Boolean)

    /** `render.entry`：扩展 `registerEntryRenderer` 注册的渲染器（官方 `CustomEntryComponent`），按 [width] 列渲染成行。 */
    suspend fun renderEntry(customType: String, id: String, data: JsonElement?, expanded: Boolean, width: Int): EntryRender {
        val out = client.call("render.entry", obj {
            put("customType", JsonPrimitive(customType))
            put("id", JsonPrimitive(id))
            if (data != null) put("data", data)
            put("expanded", JsonPrimitive(expanded))
            put("width", JsonPrimitive(width))
        }) as? JsonObject
        val lines = (out?.get("lines") as? JsonArray)?.map { (it as? JsonPrimitive)?.content ?: it.toString() }
        val reason = (out?.get("reason") as? JsonPrimitive)?.content
        return EntryRender(lines, hasRenderer = reason != "no_renderer")
    }

    /** `render.markdown` 的结果；[count] 为 0 = 没有扩展注册 markdown transformer，调用方可以不再问。 */
    data class MarkdownRender(val markdown: String, val count: Int)

    /** `render.markdown`：把一段 markdown 交给扩展的 `registerMarkdownTransformer` 链（与 TUI 同序同上下文）。 */
    suspend fun renderMarkdown(markdown: String, messageType: String, isStreaming: Boolean, width: Int, messageId: String? = null): MarkdownRender {
        val out = client.call("render.markdown", obj {
            put("markdown", JsonPrimitive(markdown))
            put("messageType", JsonPrimitive(messageType))
            put("isStreaming", JsonPrimitive(isStreaming))
            put("width", JsonPrimitive(width))
            if (messageId != null) put("messageId", JsonPrimitive(messageId))
        }) as? JsonObject
        return MarkdownRender(
            (out?.get("markdown") as? JsonPrimitive)?.content ?: markdown,
            (out?.get("count") as? JsonPrimitive)?.content?.toIntOrNull() ?: 0,
        )
    }

    suspend fun uiInput(surfaceId: String, data: String) {
        client.call("ui.input", obj { put("surfaceId", JsonPrimitive(surfaceId)); put("data", JsonPrimitive(data)) })
    }

    suspend fun uiMouse(
        surfaceId: String,
        x: Int,
        y: Int,
        type: String = "click",
        width: Int? = null,
        height: Int? = null,
        wheelDelta: Int? = null,
    ): Boolean {
        val res = client.callObject("ui.mouse", obj {
            put("surfaceId", JsonPrimitive(surfaceId))
            put("x", JsonPrimitive(x))
            put("y", JsonPrimitive(y))
            put("type", JsonPrimitive(type))
            width?.let { put("width", JsonPrimitive(it)) }
            height?.let { put("height", JsonPrimitive(it)) }
            wheelDelta?.let { put("wheelDelta", JsonPrimitive(it)) }
        })
        return (res["handled"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false
    }

    suspend fun uiResize(width: Int, height: Int? = null, surfaceId: String? = null) {
        client.call("ui.resize", obj {
            put("width", JsonPrimitive(width))
            height?.let { put("height", JsonPrimitive(it)) }
            surfaceId?.let { put("surfaceId", JsonPrimitive(it)) }
        })
    }

    suspend fun uiClose(surfaceId: String) { client.call("ui.close", obj { put("surfaceId", JsonPrimitive(surfaceId)) }) }

    suspend fun uiEditorState(text: String) {
        client.call("ui.editorState", obj { put("text", JsonPrimitive(text)) })
    }

    suspend fun uiSetToolsExpanded(expanded: Boolean) {
        client.call("ui.setToolsExpanded", obj { put("expanded", JsonPrimitive(expanded)) })
    }

    /** 扩展补全链（`ctx.ui.addAutocompleteProvider`）的一次查询结果。 */
    data class ExtensionCompletion(val value: String, val label: String, val description: String?)
    data class AutocompleteReply(val prefix: String, val items: List<ExtensionCompletion>)

    suspend fun uiAutocomplete(
        lines: List<String>,
        cursorLine: Int,
        cursorCol: Int,
        force: Boolean = false,
    ): AutocompleteReply {
        val res = client.callObject("ui.autocomplete", obj {
            put("lines", JsonArray(lines.map(::JsonPrimitive)))
            put("cursorLine", JsonPrimitive(cursorLine))
            put("cursorCol", JsonPrimitive(cursorCol))
            if (force) put("force", JsonPrimitive(true))
        })
        val prefix = res.str("prefix").orEmpty()
        val items = (res["items"] as? JsonArray)?.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val value = o.str("value") ?: return@mapNotNull null
            ExtensionCompletion(
                value = value,
                label = o.str("label") ?: value,
                description = o.str("description"),
            )
        }.orEmpty()
        return AutocompleteReply(prefix = prefix, items = items)
    }

    data class AutocompleteApplyResult(val lines: List<String>, val cursorLine: Int, val cursorCol: Int)

    suspend fun uiAutocompleteApply(
        lines: List<String>,
        cursorLine: Int,
        cursorCol: Int,
        item: ExtensionCompletion,
        prefix: String,
    ): AutocompleteApplyResult {
        val res = client.callObject("ui.autocompleteApply", obj {
            put("lines", JsonArray(lines.map(::JsonPrimitive)))
            put("cursorLine", JsonPrimitive(cursorLine))
            put("cursorCol", JsonPrimitive(cursorCol))
            put("prefix", JsonPrimitive(prefix))
            put("item", obj {
                put("value", JsonPrimitive(item.value))
                put("label", JsonPrimitive(item.label))
                item.description?.let { put("description", JsonPrimitive(it)) }
            })
        })
        val nextLines = (res["lines"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: lines
        val nextLine = (res["cursorLine"] as? JsonPrimitive)?.content?.toIntOrNull() ?: cursorLine
        val nextCol = (res["cursorCol"] as? JsonPrimitive)?.content?.toIntOrNull() ?: cursorCol
        return AutocompleteApplyResult(lines = nextLines, cursorLine = nextLine, cursorCol = nextCol)
    }

    suspend fun uiPalette(palette: Map<String, String>) {
        client.call("ui.palette", obj { put("palette", JsonObject(palette.mapValues { JsonPrimitive(it.value) })) })
    }

    /** 触发扩展通过 `pi.registerShortcut(shortcut, ...)` 注册的快捷键动作。 */
    suspend fun triggerShortcut(shortcut: String) {
        client.call("catalog.triggerShortcut", obj { put("shortcut", JsonPrimitive(shortcut)) })
    }

    /** 将一段原始终端输入送过扩展通过 `ctx.ui.onTerminalInput` 注册的监听链。 */
    suspend fun uiTerminalInput(data: String): Pair<Boolean, String> {
        val res = client.callObject("ui.terminalInput", obj { put("data", JsonPrimitive(data)) })
        val consumed = (res["consumed"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false
        val next = res.str("data") ?: data
        return consumed to next
    }

    /** 按需把扩展注册的自定义编辑器（`setEditorComponent`）作为交互表面打开。 */
    suspend fun uiOpenCustomEditor(): Boolean {
        val res = client.callObject("ui.openCustomEditor")
        return (res["opened"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false
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
        fun imagesJson(images: List<app.pi.protocol.PiImage>): JsonArray = JsonArray(
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
fun sendArgs(message: String, images: List<app.pi.protocol.PiImage>): JsonObject = JsonObject(
    buildMap {
        put("message", JsonPrimitive(message))
        if (images.isNotEmpty()) put("images", HostCommands.imagesJson(images))
    },
)
