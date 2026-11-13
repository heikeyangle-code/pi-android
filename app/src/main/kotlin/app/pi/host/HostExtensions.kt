package app.pi.host

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 扩展包与 MCP 的类型化入口 —— 全是宿主命令（`packages.*` / `mcp.*` / `session.reload`），
 * 背后是 SDK 的 `DefaultPackageManager` 与 pi 自带的 MCP 运行时。
 *
 * 以前这两块是 App 自己的一整套：解析 `pi list` 的文字输出、自己读写 `mcp.json`、在 guest 里跑
 * `pi mcp login` 却没法取消、装完包还要**重启整个引擎**。现在：
 *  - 装 / 卸 / 更新是进程内的函数调用，进度走 `packages.progress` 事件；
 *  - MCP 的配置、连接、OAuth（含可取消）都是宿主命令；
 *  - 改完用 [reload]（`session.reload`）热重载扩展 / 技能 / 提示词 / 主题，不重启进程。
 */
class HostExtensions(private val commands: HostCommands) {

    data class Pkg(val source: String, val scope: String, val filtered: Boolean, val installedPath: String?) {
        val isProject get() = scope == "project"
    }

    data class Update(val source: String, val name: String, val type: String, val scope: String)

    data class McpServer(
        val name: String,
        val scope: String,
        val source: String,
        val override: String?,
        val enabled: Boolean,
        val exposure: String,
        val url: String?,
        val command: String?,
        val args: List<String>,
        val description: String?,
    ) {
        val isProject get() = scope == "project"
        /** 一行说明它怎么连：网址或命令行。 */
        val transport get() = url ?: listOfNotNull(command).plus(args).joinToString(" ")
    }

    data class McpConfig(val servers: List<McpServer>, val errors: List<String>)

    /** `mcp.list` 的一行：真的连过之后的状态。[state]：connected / needs-auth / error / disabled … */
    data class McpReport(val name: String, val state: String, val tools: List<String>, val error: String?, val resources: Boolean)

    // ---- 扩展包 ----

    suspend fun packages(): List<Pkg> = rows(commands.call("packages.list").data["packages"]).map(::pkg)

    /** 官方 `checkForAvailableUpdates()`：只查不装，要联网。 */
    suspend fun updates(): List<Update> =
        rows(commands.call("packages.updates", timeoutMs = LONG).data["updates"]).map {
            Update(str(it["source"]).orEmpty(), str(it["name"]) ?: str(it["displayName"]) ?: str(it["source"]).orEmpty(), str(it["type"]).orEmpty(), str(it["scope"]).orEmpty())
        }

    /** 装包并写进设置。[opId] 用来把 `packages.progress` 事件对上这次操作。 */
    suspend fun install(source: String, project: Boolean, opId: String): List<Pkg> =
        rows(commands.call("packages.install", JsonObject(mapOf("source" to JsonPrimitive(source), "scope" to JsonPrimitive(if (project) "project" else "user"), "opId" to JsonPrimitive(opId))), LONG).data["packages"]).map(::pkg)

    suspend fun remove(source: String, project: Boolean, opId: String): List<Pkg> =
        rows(commands.call("packages.remove", JsonObject(mapOf("source" to JsonPrimitive(source), "scope" to JsonPrimitive(if (project) "project" else "user"), "opId" to JsonPrimitive(opId))), LONG).data["packages"]).map(::pkg)

    /** [source] 为 null = 全部更新。 */
    suspend fun update(source: String?, opId: String): List<Pkg> =
        rows(commands.call("packages.update", JsonObject(buildMap {
            source?.let { put("source", JsonPrimitive(it)) }
            put("opId", JsonPrimitive(opId))
        }), LONG).data["packages"]).map(::pkg)

    /** 装 / 卸 / 改开关之后让改动生效：热重载，不重启进程。 */
    suspend fun reload() { commands.call("session.reload", timeoutMs = LONG) }

    // ---- MCP ----

    /** 只读配置，不连任何服务器（秒回）。 */
    suspend fun mcpConfig(): McpConfig {
        val d = commands.call("mcp.servers").data
        return McpConfig(rows(d["servers"]).map(::server), (d["errors"] as? JsonArray).orEmpty().map { str(it) ?: it.toString() })
    }

    /** 真的连每个已启用的服务器（可能要几秒到几十秒），只在用户点「检测」时调。 */
    suspend fun mcpCheck(): List<McpReport> =
        rows(commands.call("mcp.list", timeoutMs = LONG).data["servers"]).map {
            McpReport(
                name = str(it["name"]).orEmpty(),
                state = str(it["state"]).orEmpty(),
                tools = (it["tools"] as? JsonArray).orEmpty().mapNotNull { t -> str(t) },
                error = str(it["error"]),
                resources = (it["resources"] as? JsonPrimitive)?.content == "true",
            )
        }

    /** 新增。stdio 给 [command]（+[args]），HTTP 给 [url]，二选一（宿主校验）。[project] = 写进本项目。 */
    suspend fun mcpAdd(name: String, command: String?, args: List<String>, url: String?, project: Boolean, exposure: String?) {
        commands.call("mcp.add", JsonObject(buildMap {
            put("name", JsonPrimitive(name))
            command?.let { put("command", JsonPrimitive(it)) }
            if (args.isNotEmpty()) put("args", JsonArray(args.map(::JsonPrimitive)))
            url?.let { put("url", JsonPrimitive(it)) }
            put("local", JsonPrimitive(project))
            exposure?.let { put("exposure", JsonPrimitive(it)) }
        }), LONG)
    }

    suspend fun mcpUpdate(name: String, enabled: Boolean? = null, exposure: String? = null, project: Boolean = false) {
        commands.call("mcp.update", JsonObject(buildMap {
            put("name", JsonPrimitive(name))
            enabled?.let { put("enabled", JsonPrimitive(it)) }
            exposure?.let { put("exposure", JsonPrimitive(it)) }
            put("local", JsonPrimitive(project))
        }), LONG)
    }

    suspend fun mcpRemove(name: String, project: Boolean) {
        commands.call("mcp.remove", JsonObject(mapOf("name" to JsonPrimitive(name), "local" to JsonPrimitive(project))), LONG)
    }

    /**
     * OAuth 登录，一直等到完成。授权网址走 `mcp.auth` 事件（[authUrl]），要用户贴回跳地址时宿主会发
     * `mcp.redirect` 提问（[redirectRequest]）。失败 / 取消抛 [HostException]（宿主原话）。
     */
    suspend fun mcpLogin(name: String, loginId: String): List<String> =
        (commands.call("mcp.login", JsonObject(mapOf("name" to JsonPrimitive(name), "loginId" to JsonPrimitive(loginId))), LOGIN_TIMEOUT_MS).data["tools"] as? JsonArray)
            .orEmpty().mapNotNull { str(it) }

    suspend fun mcpCancelLogin(loginId: String): Boolean =
        (commands.call("mcp.cancel", JsonObject(mapOf("loginId" to JsonPrimitive(loginId)))).data["cancelled"] as? JsonPrimitive)?.content == "true"

    suspend fun mcpLogout(name: String): String? =
        str(commands.call("mcp.logout", JsonObject(mapOf("name" to JsonPrimitive(name))), LONG).data["message"])

    // ---- 事件 / 提问 ----

    /** `packages.progress`：一次装 / 卸 / 更新的进度。[type]：start / progress / complete / error。 */
    data class Progress(val opId: String, val type: String, val action: String, val source: String, val message: String?)

    /** `mcp.auth`：要用户去浏览器打开的授权网址。 */
    data class McpAuth(val loginId: String, val name: String, val url: String)

    companion object {
        const val LONG = 10 * 60_000L
        const val LOGIN_TIMEOUT_MS = 6 * 60_000L

        fun progress(event: HostSignal.Event): Progress? {
            if (event.name != "packages.progress") return null
            val d = event.data as? JsonObject ?: return null
            return Progress(str(d["opId"]).orEmpty(), str(d["type"]).orEmpty(), str(d["action"]).orEmpty(), str(d["source"]).orEmpty(), str(d["message"]))
        }

        fun authUrl(event: HostSignal.Event): McpAuth? {
            if (event.name != "mcp.auth") return null
            val d = event.data as? JsonObject ?: return null
            return McpAuth(str(d["loginId"]).orEmpty(), str(d["name"]).orEmpty(), str(d["url"]).orEmpty()).takeIf { it.url.isNotEmpty() }
        }

        /** `mcp.redirect` 提问 → (loginId, 服务器名)；答复用 `PiHostClient.respond(request.id, JsonPrimitive(回跳地址))`。 */
        fun redirectRequest(request: HostRequest): Pair<String, String>? {
            if (request.method != "mcp.redirect") return null
            return str(request.params["loginId"]).orEmpty() to str(request.params["name"]).orEmpty()
        }

        private fun str(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it.isString }?.content
    }

    private fun pkg(o: JsonObject) = Pkg(str(o["source"]).orEmpty(), str(o["scope"]).orEmpty(), (o["filtered"] as? JsonPrimitive)?.content == "true", str(o["installedPath"]))

    private fun server(o: JsonObject) = McpServer(
        name = str(o["name"]).orEmpty(),
        scope = str(o["scope"]) ?: "global",
        source = str(o["source"]).orEmpty(),
        override = str(o["override"]),
        enabled = (o["enabled"] as? JsonPrimitive)?.content != "false",
        exposure = str(o["exposure"]) ?: "codemode",
        url = str(o["url"]),
        command = str(o["command"]),
        args = (o["args"] as? JsonArray).orEmpty().mapNotNull { str(it) },
        description = str(o["description"]),
    )

    private fun rows(e: JsonElement?): List<JsonObject> = (e as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
}
