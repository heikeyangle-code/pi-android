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
data class ExtPanel(val key: String, val lines: List<String>, val placement: String? = null)

/** `ctx.ui.custom()` 弹出的组件：浮层（[overlay]）或整页；[interactive] 时 App 要给按键条。 */
data class ExtCustom(
    val surfaceId: String,
    val overlay: Boolean,
    val interactive: Boolean,
    val lines: List<String>,
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
    /** 有扩展想接管输入框（终端编辑器组件），手机上做不到：只让界面知道。 */
    val editorTakeover: Boolean = false,
    val custom: ExtCustom? = null,
    val working: ExtWorking = ExtWorking(),
    val thinkingLabel: String? = null,
)

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

    private class Frame(val rev: Long, val lines: List<String>, val overlay: Boolean, val interactive: Boolean)

    private val frames = HashMap<String, Frame>()
    private val widgetSurface = HashMap<String, String>()
    private val widgetPlacement = HashMap<String, String?>()
    private val slotSurface = HashMap<String, String>()

    /** 返回应用后的状态；这条事件与扩展 UI 无关或没有可见变化时返回 null。 */
    fun apply(name: String, data: JsonElement?): ExtUiState? {
        val d = data as? JsonObject ?: return null
        val before = state
        when (name) {
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
            else -> return null
        }
        return state.takeIf { it != before }
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
            widgetSurface[key] = sid
            widgetPlacement[key] = placement
            frames[sid]?.let { setWidget(key, it.lines, placement) }
            return
        }
        widgetSurface.remove(key)?.let { frames.remove(it) }
        widgetPlacement.remove(key)
        setWidget(key, linesOf(d["lines"]), placement)
    }

    private fun setWidget(key: String, lines: List<String>, placement: String?) {
        val current = state.widgets
        state = state.copy(
            widgets = when {
                lines.isEmpty() -> current.filterNot { it.key == key }
                current.any { it.key == key } -> current.map { if (it.key == key) ExtPanel(key, lines, placement) else it }
                else -> current + ExtPanel(key, lines, placement)
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
                    slotSurface.remove(slot)?.let { frames.remove(it) }
                    setSlot(slot, null)
                } else {
                    slotSurface[slot] = sid
                    frames[sid]?.let { setSlot(slot, ExtPanel(slot, it.lines)) }
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
        val mode = str(d["mode"]) ?: return
        val rev = (d["rev"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L
        if ((frames[id]?.rev ?: -1L) >= rev) return
        val frame = Frame(rev, linesOf(d["lines"]), bool(d["overlay"], false), bool(d["interactive"], false))
        frames[id] = frame
        when (mode) {
            "widget" -> {
                val key = (str(d["key"]) ?: return).removePrefix("widget:")
                if (widgetSurface[key] == id) setWidget(key, frame.lines, widgetPlacement[key])
            }
            "header", "footer" -> if (slotSurface[mode] == id) setSlot(mode, ExtPanel(mode, frame.lines))
            "custom" -> state = state.copy(custom = ExtCustom(id, frame.overlay, frame.interactive, frame.lines))
        }
    }

    private fun close(d: JsonObject) {
        val id = str(d["surfaceId"]) ?: return
        frames.remove(id)
        when (str(d["mode"])) {
            "widget" -> {
                val key = (str(d["key"]) ?: return).removePrefix("widget:")
                if (widgetSurface[key] == id) {
                    widgetSurface.remove(key)
                    widgetPlacement.remove(key)
                    setWidget(key, emptyList(), null)
                }
            }
            "header", "footer" -> {
                val slot = str(d["mode"])!!
                if (slotSurface[slot] == id) {
                    slotSurface.remove(slot)
                    setSlot(slot, null)
                }
            }
            "custom" -> if (state.custom?.surfaceId == id) state = state.copy(custom = null)
        }
    }

    private fun linesOf(e: JsonElement?): List<String> = (e as? JsonArray)?.map { str(it) ?: it.toString() } ?: emptyList()

    private fun str(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun bool(e: JsonElement?, default: Boolean): Boolean = (e as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: default
}
