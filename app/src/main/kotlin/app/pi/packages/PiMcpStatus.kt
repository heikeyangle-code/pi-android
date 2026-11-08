package app.pi.packages

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * `pi mcp list --json` 的结果：每个服务器此刻连不连得上、有哪些工具、失败原因。
 *
 * 输出格式来自 pi 自己（`dist/extensions/mcp/cli.js` 的 `list`）：
 * `{servers:[{name,scope,enabled,exposure,transport,state,tools[],toolExposure?,resources?,error?}],errors[],note?}`。
 * `state` 取 `connected` / `needs-auth` / `disabled` / 其他失败态。pi 在有服务器连不上时退出码为 1，
 * 但 JSON 照样打印，所以这里不看退出码、只看能不能解析出来。
 */
object PiMcpStatus {
    data class Server(
        val name: String,
        val scope: String,
        val enabled: Boolean,
        val state: String,
        val tools: List<String>,
        val toolExposure: Map<String, String>,
        val resources: Boolean,
        val error: String?,
    ) {
        val connected: Boolean get() = state == "connected"
        val needsAuth: Boolean get() = state == "needs-auth"
    }

    data class Report(val servers: List<Server>, val errors: List<String>, val note: String?) {
        fun of(name: String): Server? = servers.firstOrNull { it.name == name }
    }

    /** 文本里第一个 `{` 到最后一个 `}`：容忍前面混进的启动提示。解析不出返回 null。 */
    fun parse(output: String): Report? {
        val start = output.indexOf('{')
        val end = output.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val root = try {
            Json.parseToJsonElement(output.substring(start, end + 1)) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return null
        val servers = (root["servers"] as? JsonArray).orEmpty().mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            fun text(key: String) = (o[key] as? JsonPrimitive)?.contentOrNull
            Server(
                name = text("name") ?: return@mapNotNull null,
                scope = text("scope") ?: "global",
                enabled = (o["enabled"] as? JsonPrimitive)?.booleanOrNull != false,
                state = text("state") ?: "unknown",
                tools = (o["tools"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
                toolExposure = (o["toolExposure"] as? JsonObject)
                    ?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }
                    ?.toMap().orEmpty(),
                resources = (o["resources"] as? JsonPrimitive)?.booleanOrNull == true,
                error = text("error"),
            )
        }
        val errors = (root["errors"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        return Report(servers, errors, (root["note"] as? JsonPrimitive)?.contentOrNull)
    }

    /** 给人看的一个词。 */
    fun stateLabel(server: Server): String = when {
        !server.enabled || server.state == "disabled" -> "已停用"
        server.connected -> "已连接 · ${server.tools.size} 个工具"
        server.needsAuth -> "需要登录"
        else -> "连接失败"
    }
}
