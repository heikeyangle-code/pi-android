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

    /** 宿主的 `ui.*` 事件 → 旧「通知 / 标题 / 编辑器文本」事件。状态、小组件、表面、工作中提示等走 [ExtUiReducer]。其余返回 null。 */
    fun eventToLegacy(name: String, data: JsonElement?): PiEvent.ExtensionUiRequest? {
        val d = data as? JsonObject ?: return null
        val body: JsonObject = when (name) {
            "ui.notify" -> obj {
                put("method", JsonPrimitive("notify"))
                d["message"]?.let { put("message", it) }
                d["tone"]?.let { put("notifyType", it) }
            }
            "ui.title" -> obj {
                put("method", JsonPrimitive("setTitle"))
                d["title"]?.let { put("title", it) }
            }
            // pasteToEditor：接在草稿后面，不是替换——扩展贴一段上下文，用户已经打的字不能丢。
            "ui.paste" -> obj {
                put("method", JsonPrimitive("paste_editor_text"))
                d["text"]?.let { put("text", it) }
            }
            "ui.editorText" -> obj {
                put("method", JsonPrimitive("set_editor_text"))
                d["text"]?.let { put("text", it) }
            }
            else -> return null
        }
        return parse(JsonObject(body + ("type" to JsonPrimitive("extension_ui_request"))))
    }

    private fun parse(o: JsonObject): PiEvent.ExtensionUiRequest? = PiEvents.parse(o.toString()) as? PiEvent.ExtensionUiRequest

    private inline fun obj(build: MutableMap<String, JsonElement>.() -> Unit): JsonObject =
        JsonObject(LinkedHashMap<String, JsonElement>().apply(build))
}

