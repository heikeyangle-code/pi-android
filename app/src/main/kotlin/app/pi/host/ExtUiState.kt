package app.pi.host

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 扩展 UI 的全部**状态**（不含一次性事件：通知、对话框、编辑器填充）。
 *
 * 宿主把扩展对 `ctx.ui` 的每次调用发成一个 `ui.*` 事件；[ExtUiReducer] 把它们收成这一份不可变状态，
 * 界面只画状态、不认事件。没有 Compose、没有 Android，所以 harness 能在裸 JVM 上钉住每条规则。
 */
data class ExtStatus(val key: String, val text: String)

/** 一块常驻面板：一组带 ANSI 的行。[placement] 是 `aboveEditor` / `belowEditor`（header/footer 不用）。 */
data class ExtPanel(
    val key: String,
    val lines: List<String>,
    val placement: String? = null,
    val surfaceId: String? = null,
    val interactive: Boolean = false,
    val supportsMouse: Boolean = false,
    val hasTextCursor: Boolean = false,
)

/** 官方 `OverlayOptions` 的手机端投影（尺寸/锚点/非拦截标记）。 */
data class ExtOverlayOptions(
    val widthSpec: String? = null,
    val minWidth: Int? = null,
    val maxHeightSpec: String? = null,
    val anchor: String? = null,
    val nonCapturing: Boolean = false,
)

/** `ctx.ui.custom()` 弹出的组件：浮层（[overlay]）或整页；[interactive] 时 App 要给按键条与触控点击。 */
data class ExtCustom(
    val surfaceId: String,
    val overlay: Boolean,
    val interactive: Boolean,
    val lines: List<String>,
    val blocked: String? = null,
    val blockedKind: String? = null,
    val hidden: Boolean = false,
    val focused: Boolean = true,
    /** 官方 `OverlayOptions.nonCapturing`：非拦截型悬浮层，不抢占聊天视口和输入焦点。 */
    val nonCapturing: Boolean = false,
    /** 组件是否真正实现了官方 `Component.handleMouse`（未实现时以手势/手柄/快捷键驱动，避免误导点按）。 */
    val supportsMouse: Boolean = false,
    /** 组件渲染帧里是否包含官方 `CURSOR_MARKER` / `FAKE_CURSOR`（代表正在等待用户输入文本）。 */
    val hasTextCursor: Boolean = false,
    /** 官方 `OverlayOptions` 尺寸与定位选项。 */
    val overlayOptions: ExtOverlayOptions? = null,
)

data class ExtWorking(
    val message: String? = null,
    val visible: Boolean = true,
    val frames: List<String>? = null,
    val intervalMs: Long? = null,
)

data class ExtUiState(
    val statuses: List<ExtStatus> = emptyList(),
    val widgets: List<ExtPanel> = emptyList(),
    val header: ExtPanel? = null,
    val footer: ExtPanel? = null,
    /** 有扩展想接管输入框（终端编辑器组件），手机上保留原生输入体验并显示提示。 */
    val editorTakeover: Boolean = false,
    /** 支持叠加浮层（`tui.showOverlay` / 嵌套 `ctx.ui.custom`），按打开顺序入栈。 */
    val customStack: List<ExtCustom> = emptyList(),
    val working: ExtWorking = ExtWorking(),
    val thinkingLabel: String? = null,
    /** 扩展通过 `ctx.ui.setToolsExpanded` 设置的工具展开态；null 表示未由扩展驱动。 */
    val toolsExpanded: Boolean? = null,
    /** 扩展通过 `ctx.ui.addAutocompleteProvider` 注册的补全提供者数量。 */
    val autocompleteCount: Int = 0,
    /** 扩展通过 `ctx.ui.onTerminalInput` 注册的终端输入监听器数量。 */
    val terminalInputCount: Int = 0,
    /** 扩展通过 `ctx.ui.setTitle` / OSC 0/2 设置的标题。 */
    val title: String? = null,
    /** 终端响铃（BEL `\x07` / OSC 通知）单调计数，用于触发原生提示音。 */
    val bellSeq: Long = 0L,
) {
    /** 当前最顶层、可见、且为拦截型（非 nonCapturing）的 custom 表面。 */
    val custom: ExtCustom? get() = customStack.lastOrNull { !it.hidden && !it.nonCapturing }

    /** 当前最顶层、可见、且为非拦截型（`overlayOptions.nonCapturing = true`）的悬浮 HUD 表面。 */
    val nonCapturingCustom: ExtCustom? get() = customStack.lastOrNull { !it.hidden && it.nonCapturing }
}

/**
 * 事件 → [ExtUiState]。
 *
 *  - **组件表面**：宿主把工厂组件渲染成 `ui.surface` 帧（含 ANSI 的行，`rev` 只增）。帧**可能先于**
 *    「这个表面属于谁」的宣布（`ui.widget{surfaceId}` / `ui.chrome{surfaceId}`）到达——宿主先 paint 再宣布，
 *    所以帧先缓存，宣布时补出；旧帧（`rev` 不增）忽略；表面被关或被换，迟到的帧不复活。
 *  - 表面的 `key`：widget 是 `widget:<key>`，header/footer 就是槽名，custom 是随机 id（见 surfaces.mjs）。
 *  - 状态按 key upsert，空文本清除；小组件按首次出现的顺序稳定，不因扩展每轮更新而跳位。
 */
class ExtUiReducer {
    var state = ExtUiState()
        private set

    private class Frame(
        val rev: Long,
        val lines: List<String>,
        val overlay: Boolean,
        val interactive: Boolean,
        val blocked: String? = null,
        val blockedKind: String? = null,
        val hidden: Boolean = false,
        val focused: Boolean = true,
        val nonCapturing: Boolean = false,
        val supportsMouse: Boolean = false,
        val hasTextCursor: Boolean = false,
        val overlayOptions: ExtOverlayOptions? = null,
    )

    private val frames = HashMap<String, Frame>()
    private val widgetSurface = HashMap<String, String>()
    private val widgetPlacement = HashMap<String, String?>()
    private val slotSurface = HashMap<String, String>()

    /** 返回应用后的状态；这条事件与扩展 UI 无关或没有可见变化时返回 null。 */
    fun apply(name: String, data: JsonElement?): ExtUiState? {
        val d = data as? JsonObject ?: return null
        val before = state
        when (name) {
            "ui.reset" -> reset()
            "ui.status" -> status(d)
            "ui.widget" -> widget(d)
            "ui.chrome" -> chrome(d)
            "ui.surface" -> surface(d)
            "ui.close" -> close(d)
            "ui.working" -> state = state.copy(working = state.working.copy(message = str(d["message"])))
            "ui.workingVisible" -> state = state.copy(working = state.working.copy(visible = bool(d["visible"], true)))
            "ui.workingIndicator" -> state = state.copy(
                working = state.working.copy(
                    frames = (d["frames"] as? JsonArray)?.mapNotNull { str(it) },
                    intervalMs = (d["intervalMs"] as? JsonPrimitive)?.content?.toLongOrNull(),
                ),
            )
            "ui.thinkingLabel" -> state = state.copy(thinkingLabel = str(d["label"]))
            "ui.toolsExpanded" -> state = state.copy(toolsExpanded = bool(d["expanded"], false))
            "ui.autocomplete" -> state = state.copy(
                autocompleteCount = (d["registered"] as? JsonPrimitive)?.content?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
            )
            "ui.terminalInput" -> state = state.copy(
                terminalInputCount = (d["registered"] as? JsonPrimitive)?.content?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
            )
            "ui.title" -> state = state.copy(title = str(d["title"])?.takeIf { it.isNotBlank() })
            "ui.bell" -> state = state.copy(bellSeq = state.bellSeq + 1L)
            else -> return null
        }
        return state.takeIf { it != before }
    }

    /**
     * 官方 `interactive-mode.js` 的 `resetExtensionUI`（会话被替换时）：状态、小组件、header/footer、自定义编辑器、
     * 工作消息 / 可见性 / 指示器、思考标签、自动补全提供者全部回到默认；**工具展开**不在其中，保留。
     */
    private fun reset() {
        frames.clear()
        widgetSurface.clear()
        widgetPlacement.clear()
        slotSurface.clear()
        closedSurfaces.clear()
        state = ExtUiState(toolsExpanded = state.toolsExpanded, bellSeq = state.bellSeq)
    }

    /** Bounded tombstone set preventing late `ui.surface` frames from resurrecting closed surfaces. */
    private val closedSurfaces = LinkedHashSet<String>()

    private fun markClosed(id: String) {
        closedSurfaces.add(id)
        if (closedSurfaces.size > 256) {
            val oldest = closedSurfaces.iterator().next()
            closedSurfaces.remove(oldest)
        }
    }

    private fun status(d: JsonObject) {
        val key = str(d["key"]) ?: return
        val text = str(d["text"])?.takeIf { it.isNotBlank() }
        val rest = state.statuses.filterNot { it.key == key }
        state = state.copy(
            statuses = when {
                text == null -> rest
                state.statuses.any { it.key == key } -> state.statuses.map { if (it.key == key) ExtStatus(key, text) else it }
                else -> rest + ExtStatus(key, text)
            },
        )
    }

    private fun widget(d: JsonObject) {
        val key = str(d["key"]) ?: return
        val placement = str(d["placement"])
        val sid = str(d["surfaceId"])
        if (sid != null) {
            widgetSurface[key]?.takeIf { it != sid }?.let { oldId ->
                frames.remove(oldId)
                markClosed(oldId)
            }
            widgetSurface[key] = sid
            widgetPlacement[key] = placement
            frames[sid]?.let {
                setWidget(
                    key, it.lines, placement,
                    surfaceId = sid,
                    interactive = it.interactive,
                    supportsMouse = it.supportsMouse,
                    hasTextCursor = it.hasTextCursor,
                )
            }
            return
        }
        widgetSurface.remove(key)?.let { oldId ->
            frames.remove(oldId)
            markClosed(oldId)
        }
        widgetPlacement.remove(key)
        setWidget(key, linesOf(d["lines"]), placement, surfaceId = null, interactive = false)
    }

    private fun setWidget(
        key: String,
        lines: List<String>,
        placement: String?,
        surfaceId: String?,
        interactive: Boolean,
        supportsMouse: Boolean = false,
        hasTextCursor: Boolean = false,
    ) {
        val current = state.widgets
        val next = ExtPanel(
            key, lines, placement,
            surfaceId = surfaceId,
            interactive = interactive,
            supportsMouse = supportsMouse,
            hasTextCursor = hasTextCursor,
        )
        state = state.copy(
            widgets = when {
                lines.isEmpty() -> current.filterNot { it.key == key }
                current.any { it.key == key } -> current.map { if (it.key == key) next else it }
                else -> current + next
            },
        )
    }

    private fun chrome(d: JsonObject) {
        val slot = str(d["slot"]) ?: return
        val attached = bool(d["attached"], false)
        when (slot) {
            "editor" -> state = state.copy(editorTakeover = attached && !bool(d["supported"], true))
            "header", "footer" -> {
                val sid = str(d["surfaceId"])
                if (!attached || sid == null) {
                    slotSurface.remove(slot)?.let { oldId ->
                        frames.remove(oldId)
                        markClosed(oldId)
                    }
                    setSlot(slot, null)
                } else {
                    slotSurface[slot]?.takeIf { it != sid }?.let { oldId ->
                        frames.remove(oldId)
                        markClosed(oldId)
                    }
                    slotSurface[slot] = sid
                    frames[sid]?.let {
                        setSlot(
                            slot,
                            ExtPanel(
                                slot, it.lines,
                                surfaceId = sid,
                                interactive = it.interactive,
                                supportsMouse = it.supportsMouse,
                                hasTextCursor = it.hasTextCursor,
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun setSlot(slot: String, panel: ExtPanel?) {
        val p = panel?.takeIf { it.lines.isNotEmpty() }
        state = if (slot == "header") state.copy(header = p) else state.copy(footer = p)
    }

    private fun surface(d: JsonObject) {
        val id = str(d["surfaceId"]) ?: return
        if (id in closedSurfaces) return
        val mode = str(d["mode"]) ?: return
        val rev = (d["rev"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L
        if ((frames[id]?.rev ?: -1L) >= rev) return
        val overlayOpts = d["overlayOptions"] as? JsonObject
        val parsedOverlayOpts = overlayOpts?.let { o ->
            ExtOverlayOptions(
                widthSpec = (o["width"] as? JsonPrimitive)?.content,
                minWidth = (o["minWidth"] as? JsonPrimitive)?.content?.toIntOrNull(),
                maxHeightSpec = (o["maxHeight"] as? JsonPrimitive)?.content,
                anchor = str(o["anchor"]),
                nonCapturing = bool(o["nonCapturing"], false),
            )
        }
        val frame = Frame(
            rev = rev,
            lines = linesOf(d["lines"]),
            overlay = bool(d["overlay"], false),
            interactive = bool(d["interactive"], false),
            blocked = str(d["blocked"]),
            blockedKind = str(d["blockedKind"]),
            hidden = bool(d["hidden"], false),
            focused = bool(d["focused"], true),
            nonCapturing = parsedOverlayOpts?.nonCapturing ?: false,
            supportsMouse = bool(d["supportsMouse"], false),
            hasTextCursor = bool(d["hasTextCursor"], false),
            overlayOptions = parsedOverlayOpts,
        )
        frames[id] = frame
        when (mode) {
            "widget" -> {
                val key = (str(d["key"]) ?: return).removePrefix("widget:")
                if (widgetSurface[key] == id) {
                    setWidget(
                        key, frame.lines, widgetPlacement[key],
                        surfaceId = id,
                        interactive = frame.interactive,
                        supportsMouse = frame.supportsMouse,
                        hasTextCursor = frame.hasTextCursor,
                    )
                }
            }
            "header", "footer" -> if (slotSurface[mode] == id) {
                setSlot(
                    mode,
                    ExtPanel(
                        mode, frame.lines,
                        surfaceId = id,
                        interactive = frame.interactive,
                        supportsMouse = frame.supportsMouse,
                        hasTextCursor = frame.hasTextCursor,
                    ),
                )
            }
            "custom" -> {
                val item = ExtCustom(
                    surfaceId = id,
                    overlay = frame.overlay,
                    interactive = frame.interactive,
                    lines = frame.lines,
                    blocked = frame.blocked,
                    blockedKind = frame.blockedKind,
                    hidden = frame.hidden,
                    focused = frame.focused,
                    nonCapturing = frame.nonCapturing,
                    supportsMouse = frame.supportsMouse,
                    hasTextCursor = frame.hasTextCursor,
                    overlayOptions = frame.overlayOptions,
                )
                val stack = state.customStack
                val updated = if (stack.any { it.surfaceId == id }) {
                    stack.map { if (it.surfaceId == id) item else it }
                } else {
                    stack + item
                }
                state = state.copy(customStack = updated)
            }
        }
    }

    private fun close(d: JsonObject) {
        val id = str(d["surfaceId"]) ?: return
        frames.remove(id)
        markClosed(id)
        when (str(d["mode"])) {
            "widget" -> {
                val key = (str(d["key"]) ?: return).removePrefix("widget:")
                if (widgetSurface[key] == id) {
                    widgetSurface.remove(key)
                    widgetPlacement.remove(key)
                    setWidget(key, emptyList(), null, surfaceId = null, interactive = false)
                }
            }
            "header", "footer" -> {
                val slot = str(d["mode"])!!
                if (slotSurface[slot] == id) {
                    slotSurface.remove(slot)
                    setSlot(slot, null)
                }
            }
            "custom" -> {
                if (state.customStack.any { it.surfaceId == id }) {
                    state = state.copy(customStack = state.customStack.filterNot { it.surfaceId == id })
                }
            }
        }
    }

    private fun linesOf(e: JsonElement?): List<String> = (e as? JsonArray)?.map { str(it) ?: it.toString() } ?: emptyList()

    private fun str(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun bool(e: JsonElement?, default: Boolean): Boolean = (e as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: default
}
