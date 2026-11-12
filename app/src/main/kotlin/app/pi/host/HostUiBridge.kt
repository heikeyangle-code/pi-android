package app.pi.host

import app.pi.rpc.PiEvent
import app.pi.rpc.PiEvents
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * **过渡件**：把宿主的 `ui.*` 提问/事件译成界面现在认识的 `PiEvent.ExtensionUiRequest`，
 * 让旧的对话框与状态/小组件渲染在新宿主上继续工作。
 *
 * 为什么存在：扩展 UI 的完整重做是 ui-redesign.md 的「表面 / PAUI」一步（最后一步）；会话层切换
 * （migration 步骤 3）不该因此被卡住或让对话框先坏掉一轮。
 * 为什么能放心删：它只依赖一个函数 [toLegacy]，调用点只有 `PiEngineSession` 的两处；表面 UI 落地后
 * 删掉本文件和那两处即可，没有别的东西依赖它。
 *
 * 实现上不手造 `ExtensionUiRequest`，而是拼出旧线协议的 JSON 再交给 `PiEvents.parse`：
 * 字段规则只在解析器里有一份。
 */
object HostUiBridge {

    /** 宿主的提问 → 旧对话框事件。认不得的（`auth.prompt`、将来的 `ui.custom`）返回 null，不假装懂。 */
    fun requestToLegacy(request: HostRequest): PiEvent.ExtensionUiRequest? {
        val method = when (request.method) {
            "ui.select" -> "select"
            "ui.confirm" -> "confirm"
            "ui.input" -> "input"
            "ui.editor" -> "editor"
            else -> return null
        }
        val p = request.params
        return parse(
            obj {
                put("type", JsonPrimitive("extension_ui_request"))
                put("id", JsonPrimitive(request.id))
                put("method", JsonPrimitive(method))
                p["title"]?.let { put("title", it) }
                p["message"]?.let { put("message", it) }
                p["options"]?.let { put("options", it) }
                p["placeholder"]?.let { put("placeholder", it) }
                p["prefill"]?.let { put("prefill", it) }
                p["timeout"]?.takeIf { it !is JsonNull }?.let { put("timeout", it) }
            },
        )
    }

    /** 宿主的 `ui.*` 事件 → 旧「通知 / 状态 / 标题 / 编辑器文本」事件。小组件走 [SurfaceWidgets]。其余返回 null。 */
    fun eventToLegacy(name: String, data: JsonElement?): PiEvent.ExtensionUiRequest? {
        val d = data as? JsonObject ?: return null
        val body: JsonObject = when (name) {
            "ui.notify" -> obj {
                put("method", JsonPrimitive("notify"))
                d["message"]?.let { put("message", it) }
                d["tone"]?.let { put("notifyType", it) }
            }
            "ui.status" -> obj {
                put("method", JsonPrimitive("setStatus"))
                d["key"]?.let { put("statusKey", it) }
                d["text"]?.takeIf { it !is JsonNull }?.let { put("statusText", it) }
            }
            "ui.title" -> obj {
                put("method", JsonPrimitive("setTitle"))
                d["title"]?.let { put("title", it) }
            }
            "ui.editorText" -> obj {
                put("method", JsonPrimitive("set_editor_text"))
                d["text"]?.let { put("text", it) }
            }
            else -> return null
        }
        return parse(JsonObject(body + ("type" to JsonPrimitive("extension_ui_request"))))
    }

    /** 一次小组件变更 → 旧「setWidget」事件。[SurfaceWidgets.Update.lines] 为空表示清除。 */
    fun widgetToLegacy(update: SurfaceWidgets.Update): PiEvent.ExtensionUiRequest? = parse(
        obj {
            put("type", JsonPrimitive("extension_ui_request"))
            put("method", JsonPrimitive("setWidget"))
            put("widgetKey", JsonPrimitive(update.key))
            put("widgetLines", JsonArray(update.lines.map { JsonPrimitive(it) }))
            update.placement?.let { put("widgetPlacement", JsonPrimitive(it)) }
        },
    )

    private fun parse(o: JsonObject): PiEvent.ExtensionUiRequest? = PiEvents.parse(o.toString()) as? PiEvent.ExtensionUiRequest

    private inline fun obj(build: MutableMap<String, JsonElement>.() -> Unit): JsonObject =
        JsonObject(LinkedHashMap<String, JsonElement>().apply(build))
}

/**
 * 小组件的状态机：把宿主的三种事件收成「某个 key 现在是这几行 / 清除」。
 *
 *  - `ui.widget{lines}`：扩展给的字符串行；`lines:null,surfaceId:null` 是清除；
 *  - `ui.widget{surfaceId}`：组件工厂。宿主把它渲染成 `ui.surface` 帧（行里含 ANSI），
 *    **帧可能先于 `ui.widget` 到**（宿主先 paint 再宣布），所以帧先缓存，宣布时补发；
 *  - `ui.surface{mode:"widget"}`：一帧，`rev` 只增，旧帧忽略；
 *  - `ui.close{mode:"widget"}`：组件被关，清除。
 *
 * 表面的 key 是 `widget:<key>`（宿主的约定，见 surfaces.mjs）。没有 Compose、没有 Android。
 */
class SurfaceWidgets {
    data class Update(val key: String, val lines: List<String>, val placement: String?)

    private class Frame(val rev: Long, val lines: List<String>)

    private val frames = HashMap<String, Frame>()
    private val surfaceOfKey = HashMap<String, String>()
    private val placementOfKey = HashMap<String, String?>()

    /** 空列表 = 这条事件与小组件无关，或没有可见变化。 */
    fun onEvent(name: String, data: JsonElement?): List<Update> {
        val d = data as? JsonObject ?: return emptyList()
        return when (name) {
            "ui.widget" -> widget(d)
            "ui.surface" -> if (str(d["mode"]) == "widget") surface(d) else emptyList()
            "ui.close" -> if (str(d["mode"]) == "widget") close(d) else emptyList()
            else -> emptyList()
        }
    }

    private fun widget(d: JsonObject): List<Update> {
        val key = str(d["key"]) ?: return emptyList()
        val placement = str(d["placement"])
        val surfaceId = str(d["surfaceId"])
        if (surfaceId != null) {
            surfaceOfKey[key] = surfaceId
            placementOfKey[key] = placement
            val frame = frames[surfaceId] ?: return emptyList()
            return listOf(Update(key, frame.lines, placement))
        }
        surfaceOfKey.remove(key)?.let { frames.remove(it) }
        placementOfKey.remove(key)
        val lines = (d["lines"] as? JsonArray)?.map { str(it) ?: it.toString() } ?: emptyList()
        return listOf(Update(key, lines, placement))
    }

    private fun surface(d: JsonObject): List<Update> {
        val id = str(d["surfaceId"]) ?: return emptyList()
        val key = (str(d["key"]) ?: return emptyList()).removePrefix(KEY_PREFIX)
        val rev = (d["rev"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L
        if ((frames[id]?.rev ?: -1L) >= rev) return emptyList()
        val lines = (d["lines"] as? JsonArray)?.map { str(it) ?: it.toString() } ?: emptyList()
        frames[id] = Frame(rev, lines)
        if (surfaceOfKey[key] != id) return emptyList()
        return listOf(Update(key, lines, placementOfKey[key]))
    }

    private fun close(d: JsonObject): List<Update> {
        val id = str(d["surfaceId"]) ?: return emptyList()
        val key = (str(d["key"]) ?: return emptyList()).removePrefix(KEY_PREFIX)
        frames.remove(id)
        if (surfaceOfKey[key] != id) return emptyList()
        surfaceOfKey.remove(key)
        placementOfKey.remove(key)
        return listOf(Update(key, emptyList(), null))
    }

    private fun str(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it.isString }?.content

    private companion object {
        const val KEY_PREFIX = "widget:"
    }
}
