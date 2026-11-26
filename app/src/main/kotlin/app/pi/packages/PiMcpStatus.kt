package app.pi.packages

/**
 * 宿主 `mcp.list` 的结果：每个服务器此刻连不连得上、有哪些工具、失败原因。
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

    /** 给人看的状态摘要（含工具数与 MCP 资源能力）。 */
    fun stateLabel(server: Server): String = when {
        !server.enabled || server.state == "disabled" -> app.pi.i18n.tr("已停用", "Disabled")
        server.connected -> {
            val toolsPart = app.pi.i18n.tr("已连接 · ${server.tools.size} 个工具", "Connected · ${server.tools.size} tools")
            if (server.resources) {
                toolsPart + app.pi.i18n.tr(" · 支持资源", " · Resources")
            } else {
                toolsPart
            }
        }
        server.needsAuth -> app.pi.i18n.tr("需要登录授权", "Sign-in required")
        else -> app.pi.i18n.tr("连接失败", "Connection failed")
    }
}
