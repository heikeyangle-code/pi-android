package app.pi.host

import app.pi.rpc.PiJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/**
 * 线协议 v1 的一行（docs/sdk-host/protocol.md）。
 *
 * 解析规则只有一条要紧：**认不出来的一律忽略，不抛**。协议升级的约定是「加命令 / 加事件 / 给帧
 * 加字段不升版本」，所以未知的 `t`、缺字段的帧都得安静地跳过，否则宿主加一个事件就能把 App 打崩。
 */
sealed interface HostFrame {

    /** 命令应答。每条命令恰好一个。 */
    data class Res(val id: String, val ok: Boolean, val data: JsonElement?, val error: HostError?) : HostFrame

    /** 事件。`seq` 进程内单调递增，`epoch` 随会话替换递增。 */
    data class Evt(val seq: Long, val epoch: Long, val name: String, val data: JsonElement?) : HostFrame

    /** 宿主向 App 提问（对话框）。回答走命令 `ui.respond`。 */
    data class Req(val id: String, val method: String, val params: JsonObject) : HostFrame

    companion object {
        /** 解析一行。不是 JSON 对象、没有可识别的 `t`、缺必需字段 → null。 */
        fun parse(line: String): HostFrame? {
            val obj = PiJson.parseObjectOrNull(line) ?: return null
            return when (obj.str("t")) {
                "res" -> {
                    val id = obj.str("id") ?: return null
                    val ok = (obj["ok"] as? JsonPrimitive)?.booleanOrNull == true
                    val err = (obj["error"] as? JsonObject)?.let {
                        HostError(
                            code = it.str("code") ?: "internal",
                            message = it.str("message") ?: "",
                            detail = it.str("detail"),
                        )
                    }
                    Res(id, ok, obj["data"], if (ok) null else err ?: HostError("internal", "宿主返回失败但没有 error"))
                }
                "evt" -> {
                    val name = obj.str("name") ?: return null
                    val seq = (obj["seq"] as? JsonPrimitive)?.longOrNull ?: return null
                    val epoch = (obj["epoch"] as? JsonPrimitive)?.longOrNull ?: 0L
                    Evt(seq, epoch, name, obj["data"])
                }
                "req" -> {
                    val id = obj.str("id") ?: return null
                    val method = obj.str("method") ?: return null
                    Req(id, method, obj["params"] as? JsonObject ?: JsonObject(emptyMap()))
                }
                else -> null
            }
        }

        private fun JsonObject.str(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}

/** 协议里的错误体。App 按 [code] 分支，不要匹配 [message] 文案。 */
data class HostError(val code: String, val message: String, val detail: String? = null)

/** 命令失败。[code] 是协议错误码，外加客户端自己的 `timeout` / `closed` / `io`。 */
class HostException(val code: String, message: String, val detail: String? = null) : Exception(message) {
    constructor(error: HostError) : this(error.code, error.message, error.detail)

    companion object {
        const val TIMEOUT = "timeout"
        const val CLOSED = "closed"
        const val IO = "io"
    }
}
