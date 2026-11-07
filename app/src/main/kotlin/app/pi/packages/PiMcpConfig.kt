package app.pi.packages

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicLong

/**
 * pi 的 MCP 服务器配置（`mcp.json`）：解析、校验、修改、落盘。
 *
 * 规则全部来自 pi 自己的文档 `docs/mcp.md`「Configure servers / Configuration rules」，不是我们的发明：
 *
 *  - 用户级 `<agentDir>/mcp.json`，工程级 `<项目>/.pi/mcp.json`（工程级要项目被信任才读）。
 *    根对象的 `mcpServers` 是 `名字 → 配置`；工程里的同名条目**整条替换**用户级的。
 *  - 工程里一条**没有** `command` / `url` / `type` 的条目是「覆盖」：只改用户级同名服务器的
 *    `enabled`、`exposure`、`toolExposure`，其余（`env`、`headers`、`auth`…）沿用 —— 这就是
 *    「在本项目里关掉某个全局服务器」的写法。
 *  - 名字只许字母、数字、`_`、`-`；只差 `-` 与 `_` 的两个名字算同一个服务器，第二个会被 pi 拒绝。
 *  - `command` 是**一个可执行文件**，参数放 `args`，不是 shell 命令串；`url` 是 streamable HTTP，
 *    `sse` 不支持；`type` 可省，写就只能是 `stdio` / `http` / `streamable-http`。
 *  - `exposure`：`codemode`（默认）/ `deferred` / `direct` / `hidden`，`codemode-deferred` 是 `codemode` 的别名。
 *  - `timeout` 是秒（默认 60）。`env` / `headers` 的值可以写 `${变量}`，或整条写成 `!命令`。
 *
 * **未知的键一个都不动。** 编辑一条服务器只改表单上有的那几个键（command/args/env、url/headers、
 * description、timeout、exposure、enabled），`oauth`、`cwd`、`toolExposure`、`type`、
 * 以及将来 pi 加的键都原样留着；根上 `mcpServers` 之外的键（例如 `autoEnableCodemode`）同样保留。
 *
 * 本文件不引 Android、不引 Compose：纯 JVM harness 可以单独编译它。
 */
object PiMcpConfig {

    /** `exposure` 的四个取值，按文档的顺序。 */
    val EXPOSURES: List<String> = listOf("codemode", "deferred", "direct", "hidden")

    /** 文档里 `codemode-deferred` 是 `codemode` 的别名，读到时按 `codemode` 看。 */
    fun normalizeExposure(raw: String?): String = when (raw) {
        null, "", "codemode-deferred" -> "codemode"
        else -> raw
    }

    /** 服务器是哪一类。覆盖条目（没有 command/url/type）单独算 [Override]。 */
    enum class Kind { Stdio, Http, Override }

    /** 一条服务器：名字 + 它在文件里的原始对象（未知键都在里面）。 */
    data class Server(val name: String, val config: JsonObject) {
        val kind: Kind
            get() = when {
                config["command"] != null -> Kind.Stdio
                config["url"] != null -> Kind.Http
                (config["type"] as? JsonPrimitive)?.contentOrNull == "stdio" -> Kind.Stdio
                (config["type"] as? JsonPrimitive)?.contentOrNull in setOf("http", "streamable-http") -> Kind.Http
                config["type"] != null -> Kind.Http
                else -> Kind.Override
            }

        /** 缺省为开：pi 只认 `enabled: false` 是关。 */
        val enabled: Boolean
            get() = (config["enabled"] as? JsonPrimitive)?.booleanOrNull != false

        val exposure: String
            get() = normalizeExposure((config["exposure"] as? JsonPrimitive)?.contentOrNull)

        val description: String
            get() = (config["description"] as? JsonPrimitive)?.contentOrNull.orEmpty()

        /** 列表里那一行小字：命令式显示「命令 参数…」，网址式显示网址。 */
        val summary: String
            get() = when (kind) {
                Kind.Stdio -> buildString {
                    append((config["command"] as? JsonPrimitive)?.contentOrNull.orEmpty())
                    (config["args"] as? JsonArray)?.forEach { arg ->
                        (arg as? JsonPrimitive)?.contentOrNull?.let { append(' ').append(it) }
                    }
                }
                Kind.Http -> (config["url"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                Kind.Override -> "只覆盖启用状态/暴露方式"
            }
    }

    /**
     * 一份读出来的 `mcp.json`。
     *
     * @property corrupt 文件存在但不是合法的 JSON 对象。此时 [servers] 为空，而且**任何写入都被拒绝**
     *   （[PiMcpFile.update]）：把一份读不懂的文件覆盖成「只含这一条」就是把用户的其他服务器弄丢。
     */
    data class Document(
        val root: JsonObject,
        val servers: List<Server>,
        val corrupt: Boolean = false,
    ) {
        fun server(name: String): Server? = servers.firstOrNull { it.name == name }
    }

    private val PRETTY = Json { prettyPrint = true; prettyPrintIndent = "  " }

    /** 文本 → 文档。`null` / 空白 = 文件不存在 = 空文档（不算损坏）。 */
    fun parse(text: String?): Document {
        if (text == null || text.isBlank()) return Document(JsonObject(emptyMap()), emptyList())
        val root = try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return Document(JsonObject(emptyMap()), emptyList(), corrupt = true)
        val table = root["mcpServers"] as? JsonObject
        val servers = table?.entries
            ?.mapNotNull { (name, value) -> (value as? JsonObject)?.let { Server(name, it) } }
            .orEmpty()
        return Document(root, servers)
    }

    /** 文档 → 文本：两格缩进、末尾换行，键序保持。 */
    fun render(root: JsonObject): String = PRETTY.encodeToString(JsonObject.serializer(), root) + "\n"

    // ------------------------------------------------------------------ 校验

    private val NAME = Regex("^[A-Za-z0-9_-]+$")

    private fun canonical(name: String): String = name.replace('-', '_')

    /**
     * 名字是否可用。返回 `null` = 可以；否则是给用户看的一句话。
     *
     * @param others 文件里已有的**其他**服务器名（编辑时要把自己排除掉）。
     */
    fun checkName(name: String, others: Collection<String>): String? {
        if (name.isEmpty()) return "名字不能为空。"
        if (!NAME.matches(name)) return "名字只能用字母、数字、下划线和短横线。"
        val clash = others.firstOrNull { it != name && canonical(it) == canonical(name) }
        if (clash != null) return "和已有的「$clash」重名（pi 把 - 和 _ 当成同一个字符）。"
        if (others.contains(name)) return "已经有一个叫「$name」的服务器。"
        return null
    }

    /** 命令式：`command` 是**一个可执行文件**，不是 shell 命令串。 */
    fun checkCommand(command: String): String? {
        val text = command.trim()
        if (text.isEmpty()) return "命令不能为空。"
        if (text.any { it.isWhitespace() } && !text.contains('/')) {
            return "命令只能是一个可执行文件（比如 npx），参数请写在下面的「参数」里。"
        }
        return null
    }

    /** 网址式：必须是 http(s) 网址。 */
    fun checkUrl(url: String): String? {
        val text = url.trim()
        if (text.isEmpty()) return "网址不能为空。"
        val uri = try {
            URI(text)
        } catch (_: Exception) {
            return "网址格式不对。"
        }
        if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrEmpty()) {
            return "网址要以 http:// 或 https:// 开头。"
        }
        if (uri.path?.trimEnd('/')?.endsWith("/sse") == true) {
            return "pi 不支持 SSE 端点；这类服务通常还有 /mcp 结尾的 HTTP 地址，换那个。"
        }
        return null
    }

    /** 超时（秒）：空 = 用默认 60；否则要是正数。 */
    fun checkTimeout(text: String): String? {
        val value = text.trim()
        if (value.isEmpty()) return null
        val number = value.toDoubleOrNull() ?: return "超时要写数字（秒）。"
        if (number <= 0.0) return "超时要大于 0。"
        return null
    }

    // ------------------------------------------------------------------ 表单 ⇄ 配置

    /**
     * 表单里的一条服务器。多行文本框的内容原样放在字符串里，由 [toConfig] 解析：
     * 参数一行一个；环境变量 `名=值`；请求头 `名: 值`。
     */
    data class Draft(
        val name: String = "",
        val http: Boolean = false,
        val command: String = "",
        val args: String = "",
        val env: String = "",
        /** 相对路径按会话目录解析；`~/` 是家目录。留空 = 不写。 */
        val cwd: String = "",
        val url: String = "",
        val headers: String = "",
        /**
         * 访问令牌，对应 `Authorization: Bearer …` 请求头（`pi mcp add --bearer-token-env-var` 的等价物）。
         * 写 `${变量}` 或直接粘令牌；以 `!` 开头 = 整条是一条取值命令（pi 的 `!command` 语法）。
         */
        val bearer: String = "",
        val description: String = "",
        val timeoutSeconds: String = "",
        val exposure: String = "codemode",
        /** 单个工具的暴露方式，每行 `工具名或带 * 的模式=暴露方式`；精确名优先，模式按先后。 */
        val toolExposure: String = "",
        // —— OAuth（只对网址式、且没有 Authorization 请求头的服务器有意义）——
        val oauthClientId: String = "",
        val oauthClientSecret: String = "",
        val oauthCallbackPort: String = "",
        val oauthCallbackUrl: String = "",
        val oauthScope: String = "",
        val oauthClientName: String = "",
        /** `clientRegistration: "cimd"`：用 pi 的 Client ID Metadata Document 标识自己，不做动态注册。 */
        val oauthCimd: Boolean = false,
        val oauthAuthServerUrl: String = "",
    )

    private fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun JsonObject.lines(key: String, join: (String, String) -> String): String =
        (this[key] as? JsonObject)?.entries
            ?.joinToString("\n") { (k, v) -> join(k, (v as? JsonPrimitive)?.contentOrNull.orEmpty()) }
            .orEmpty()

    private const val BEARER = "Bearer "

    /** 配置 → 表单。读不出来的字段给空串，不抛。 */
    fun toDraft(server: Server): Draft {
        val config = server.config
        val oauth = config["oauth"] as? JsonObject ?: JsonObject(emptyMap())
        // Authorization 头单独拎成「访问令牌」栏，其余请求头留在文本框里。
        val headerMap = (config["headers"] as? JsonObject)?.entries
            ?.associate { (k, v) -> k to ((v as? JsonPrimitive)?.contentOrNull.orEmpty()) }
            .orEmpty()
        val authKey = headerMap.keys.firstOrNull { it.equals("Authorization", ignoreCase = true) }
        val authValue = authKey?.let { headerMap[it] }.orEmpty()
        val bearer = when {
            authKey == null -> ""
            authValue.startsWith(BEARER) -> authValue.removePrefix(BEARER)
            authValue.startsWith("!") -> authValue
            else -> ""
        }
        val keepAuthInHeaders = authKey != null && bearer.isEmpty()
        val otherHeaders = headerMap.filterKeys { it != authKey || keepAuthInHeaders }
        return Draft(
            name = server.name,
            http = server.kind == Kind.Http,
            command = config.text("command"),
            args = (config["args"] as? JsonArray)
                ?.joinToString("\n") { (it as? JsonPrimitive)?.contentOrNull.orEmpty() }
                .orEmpty(),
            env = config.lines("env") { k, v -> "$k=$v" },
            cwd = config.text("cwd"),
            url = config.text("url"),
            headers = otherHeaders.entries.joinToString("\n") { (k, v) -> "$k: $v" },
            bearer = bearer,
            description = config.text("description"),
            timeoutSeconds = (config["timeout"] as? JsonPrimitive)?.let { primitive ->
                primitive.doubleOrNull?.let { number ->
                    if (number == Math.floor(number)) number.toLong().toString() else number.toString()
                } ?: primitive.contentOrNull
            }.orEmpty(),
            exposure = server.exposure,
            toolExposure = config.lines("toolExposure") { k, v -> "$k=$v" },
            oauthClientId = oauth.text("clientId"),
            oauthClientSecret = oauth.text("clientSecret"),
            oauthCallbackPort = oauth.text("callbackPort"),
            oauthCallbackUrl = oauth.text("callbackUrl"),
            oauthScope = oauth.text("scope"),
            oauthClientName = oauth.text("clientName"),
            oauthCimd = oauth.text("clientRegistration") == "cimd",
            oauthAuthServerUrl = oauth.text("authServerMetadataUrl"),
        )
    }

    private fun isLoopback(host: String?): Boolean = host == "localhost" || host == "127.0.0.1" || host == "[::1]" || host == "::1"

    /** 表单整体校验。返回 `null` = 可以保存；否则是第一条问题。规则逐条来自 `docs/mcp.md`。 */
    fun check(draft: Draft, others: Collection<String>): String? {
        checkName(draft.name.trim(), others)?.let { return it }
        if (draft.http) checkUrl(draft.url)?.let { return it } else checkCommand(draft.command)?.let { return it }
        checkTimeout(draft.timeoutSeconds)?.let { return it }
        parseEnv(draft.env).error?.let { return it }
        parseHeaders(draft.headers).error?.let { return it }
        parseToolExposure(draft.toolExposure).error?.let { return it }
        if (draft.http) {
            val hasAuthHeader = draft.bearer.isNotBlank() ||
                parseHeaders(draft.headers).map.keys.any { it.equals("Authorization", ignoreCase = true) }
            val oauthUsed = listOf(
                draft.oauthClientId, draft.oauthClientSecret, draft.oauthCallbackPort, draft.oauthCallbackUrl,
                draft.oauthScope, draft.oauthClientName, draft.oauthAuthServerUrl,
            ).any { it.isNotBlank() } || draft.oauthCimd
            if (oauthUsed && hasAuthHeader) {
                return "已经填了访问令牌/Authorization 头，就不会再走 OAuth 登录；二选一。"
            }
            if (draft.oauthCimd && (draft.oauthClientId.isNotBlank() || draft.oauthClientName.isNotBlank())) {
                return "「CIMD 登记」不能和客户端 ID、客户端名称同时用。"
            }
            if (draft.oauthCallbackPort.isNotBlank()) {
                val port = draft.oauthCallbackPort.trim().toIntOrNull()
                if (port == null || port !in 1..65535) return "回调端口要是 1–65535 的整数。"
            }
            if (draft.oauthCallbackUrl.isNotBlank()) {
                val uri = try { URI(draft.oauthCallbackUrl.trim()) } catch (_: Exception) { null }
                if (uri == null || uri.scheme?.lowercase() != "http" || !isLoopback(uri.host)) {
                    return "回调地址必须是 localhost、127.0.0.1 或 [::1] 上的 http 地址。"
                }
                if (draft.oauthCimd && uri.path != "/callback") return "CIMD 登记的回调地址路径必须是 /callback。"
            }
            if (draft.oauthAuthServerUrl.isNotBlank()) {
                val uri = try { URI(draft.oauthAuthServerUrl.trim()) } catch (_: Exception) { null }
                val ok = uri != null && (uri.scheme?.lowercase() == "https" ||
                    (uri.scheme?.lowercase() == "http" && isLoopback(uri.host)))
                if (!ok) return "授权服务器元数据地址必须是 https（本机回环地址可用 http）。"
            }
        }
        return null
    }

    /** 解析结果：`error` 非空时 `map` 作废。 */
    data class Parsed(val map: Map<String, String>, val error: String?)

    fun parseEnv(text: String): Parsed = parsePairs(text, '=') { "环境变量要写成 名称=值，这一行不对：$it" }

    fun parseHeaders(text: String): Parsed = parsePairs(text, ':') { "请求头要写成 名称: 值，这一行不对：$it" }

    /** 单工具暴露：`名称=codemode|deferred|direct|hidden`。 */
    fun parseToolExposure(text: String): Parsed {
        val parsed = parsePairs(text, '=') { "工具暴露要写成 工具名=暴露方式，这一行不对：$it" }
        if (parsed.error != null) return parsed
        parsed.map.forEach { (tool, value) ->
            if (value !in EXPOSURES && value != "codemode-deferred") {
                return Parsed(emptyMap(), "工具「$tool」的暴露方式「$value」不对，只能是 ${EXPOSURES.joinToString(" / ")}。")
            }
        }
        return parsed
    }

    private fun parsePairs(text: String, separator: Char, message: (String) -> String): Parsed {
        val out = LinkedHashMap<String, String>()
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val cut = line.indexOf(separator)
            if (cut <= 0) return Parsed(emptyMap(), message(line))
            val key = line.substring(0, cut).trim()
            if (key.isEmpty() || (separator != '=' && key.any { it.isWhitespace() })) return Parsed(emptyMap(), message(line))
            out[key] = line.substring(cut + 1).trim()
        }
        return Parsed(out, null)
    }

    /**
     * 把表单套回原配置：**只改表单上有的键**，别的键（`type`、`enabled`、未来的新键）原样留下；
     * `oauth` 对象同理，只动表单上的那几项，其余子键保留。换了类型（命令式 ⇄ 网址式）时，另一类的键
     * 与不再适用的 `type` 一起去掉，否则 pi 会看到「既有 command 又有 url」。
     *
     * 空字段 = 删掉那个键（`timeout` 空 = 用 pi 的 60 秒默认；`exposure` 选了 `codemode` = 不写，
     * 因为那是默认值，写出来只会让文件里多一行噪音）。
     */
    fun toConfig(draft: Draft, base: JsonObject?): JsonObject {
        val out = LinkedHashMap<String, JsonElement>(base?.toMap().orEmpty())
        fun put(key: String, value: JsonElement?) {
            if (value == null) out.remove(key) else out[key] = value
        }
        fun textOrNull(value: String): JsonPrimitive? = value.trim().takeIf { it.isNotEmpty() }?.let { JsonPrimitive(it) }
        fun obj(map: Map<String, String>): JsonObject? =
            map.takeIf { it.isNotEmpty() }?.let { JsonObject(it.mapValues { (_, v) -> JsonPrimitive(v) }) }

        if (draft.http) {
            put("command", null); put("args", null); put("env", null); put("cwd", null)
            if ((out["type"] as? JsonPrimitive)?.contentOrNull == "stdio") out.remove("type")
            put("url", textOrNull(draft.url))
            val headers = LinkedHashMap(parseHeaders(draft.headers).map)
            val token = draft.bearer.trim()
            if (token.isNotEmpty() && headers.keys.none { it.equals("Authorization", ignoreCase = true) }) {
                headers["Authorization"] = if (token.startsWith("!")) token else BEARER + token
            }
            put("headers", obj(headers))
            // oauth：在原对象上改，不认识的子键保留。
            val oauth = LinkedHashMap<String, JsonElement>((base?.get("oauth") as? JsonObject)?.toMap().orEmpty())
            fun oauthText(key: String, value: String) {
                value.trim().takeIf { it.isNotEmpty() }?.let { oauth[key] = JsonPrimitive(it) } ?: oauth.remove(key)
            }
            oauthText("clientId", draft.oauthClientId)
            oauthText("clientSecret", draft.oauthClientSecret)
            oauthText("callbackUrl", draft.oauthCallbackUrl)
            oauthText("scope", draft.oauthScope)
            oauthText("clientName", draft.oauthClientName)
            oauthText("authServerMetadataUrl", draft.oauthAuthServerUrl)
            draft.oauthCallbackPort.trim().toIntOrNull()?.let { oauth["callbackPort"] = JsonPrimitive(it) }
                ?: oauth.remove("callbackPort")
            if (draft.oauthCimd) oauth["clientRegistration"] = JsonPrimitive("cimd") else oauth.remove("clientRegistration")
            put("oauth", oauth.takeIf { it.isNotEmpty() }?.let { JsonObject(it) })
        } else {
            put("url", null); put("headers", null); put("oauth", null)
            if ((out["type"] as? JsonPrimitive)?.contentOrNull in setOf("http", "streamable-http")) out.remove("type")
            put("command", textOrNull(draft.command))
            val args = draft.args.lines().map { it.trim() }.filter { it.isNotEmpty() }
            put("args", args.takeIf { it.isNotEmpty() }?.let { JsonArray(it.map { arg -> JsonPrimitive(arg) }) })
            put("env", obj(parseEnv(draft.env).map))
            put("cwd", textOrNull(draft.cwd))
        }
        put("description", textOrNull(draft.description))
        val timeout = draft.timeoutSeconds.trim().toDoubleOrNull()
        put(
            "timeout",
            timeout?.let { number ->
                if (number == Math.floor(number)) JsonPrimitive(number.toLong()) else JsonPrimitive(number)
            },
        )
        put("exposure", draft.exposure.takeIf { it in EXPOSURES && it != "codemode" }?.let { JsonPrimitive(it) })
        put("toolExposure", obj(parseToolExposure(draft.toolExposure).map))
        return JsonObject(out)
    }

    // ------------------------------------------------------------------ 从别的客户端导入

    /** 导入结果：[added] 是新增（含改名）的服务器名，[skipped] 是跳过的及原因，[error] 非空 = 整体失败。 */
    data class Imported(
        val root: JsonObject,
        val added: List<String>,
        val skipped: List<String>,
        val error: String? = null,
    )

    /**
     * 把别处复制来的配置并进 [root]。接受 pi / Claude Desktop / Claude Code / Cursor 的 `mcpServers`，
     * VS Code 的 `servers`，OpenCode 的 `type: local|remote`（按 `docs/mcp.md`「Migrate configuration」转换），
     * 也接受只有一条服务器的裸对象（需要 [fallbackName]）。同名（含 `-`/`_` 视为同名）的**不覆盖**，列入 skipped。
     * VS Code 的 `${input:…}` 提示变量 pi 没有，整条跳过并说明，免得写进去一个永远连不上的服务器。
     */
    fun importJson(root: JsonObject, text: String, fallbackName: String): Imported {
        val parsed = try { Json.parseToJsonElement(text.trim()) as? JsonObject } catch (_: Exception) { null }
            ?: return Imported(root, emptyList(), emptyList(), "粘贴的内容不是 JSON 对象。")
        val table = (parsed["mcpServers"] ?: parsed["servers"]) as? JsonObject
        val entries: Map<String, JsonElement> = when {
            table != null -> table
            parsed["command"] != null || parsed["url"] != null || parsed["type"] != null -> mapOf(fallbackName to parsed)
            else -> return Imported(root, emptyList(), emptyList(), "没找到 mcpServers（或 servers）。")
        }
        var current = root
        val added = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        for ((rawName, value) in entries) {
            val name = rawName.replace(Regex("[^A-Za-z0-9_-]"), "_")
            val entry = value as? JsonObject
            if (entry == null) { skipped += "$rawName（不是对象）"; continue }
            if (value.toString().contains("\${input:")) { skipped += "$rawName（用了 \${input:…} 提示变量，请改成 \${环境变量名} 再导入）"; continue }
            val converted = convertForeign(entry) ?: run { skipped += "$rawName（无法识别：需要 command 或 url）"; continue }
            val urlText = (converted["url"] as? JsonPrimitive)?.contentOrNull
            val urlProblem = urlText?.let { checkUrl(it) }
            if ((converted["type"] as? JsonPrimitive)?.contentOrNull == "sse" || urlProblem != null) {
                skipped += "$rawName（${urlProblem ?: "pi 不支持 SSE；换成以 /mcp 结尾的 HTTP 地址"}）"; continue
            }
            val existing = (current["mcpServers"] as? JsonObject)?.keys.orEmpty()
            val clash = checkName(name, existing)
            if (clash != null) { skipped += "$rawName（$clash）"; continue }
            current = upsert(current, null, name, converted)
            added += name
        }
        return Imported(current, added, skipped)
    }

    private fun convertForeign(entry: JsonObject): JsonObject? {
        val type = (entry["type"] as? JsonPrimitive)?.contentOrNull
        val out = LinkedHashMap<String, JsonElement>(entry.toMap())
        if (type == "local") {
            val cmd = entry["command"] as? JsonArray ?: return null
            val parts = cmd.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            if (parts.isEmpty()) return null
            out.remove("type"); out.remove("command")
            out["command"] = JsonPrimitive(parts.first())
            if (parts.size > 1) out["args"] = JsonArray(parts.drop(1).map { JsonPrimitive(it) })
            (out.remove("environment") as? JsonObject)?.let { env ->
                out["env"] = JsonObject(env.mapValues { (_, v) ->
                    JsonPrimitive(((v as? JsonPrimitive)?.contentOrNull.orEmpty()).replace(Regex("\\{env:([A-Za-z0-9_]+)}"), "\\\${$1}"))
                })
            }
        } else if (type == "remote") {
            out["type"]?.let { out.remove("type") }
        }
        if (out["command"] == null && out["url"] == null) return null
        return JsonObject(out)
    }

    // ------------------------------------------------------------------ 文档级修改

    private fun JsonObject.withServers(transform: (Map<String, JsonElement>) -> Map<String, JsonElement>): JsonObject {
        val current = (this["mcpServers"] as? JsonObject)?.toMap().orEmpty()
        val next = LinkedHashMap<String, JsonElement>(this.toMap())
        next["mcpServers"] = JsonObject(transform(current))
        return JsonObject(next)
    }

    /**
     * 新增或改一条。[oldName] 非空表示编辑：改了名字时在**原来的位置**换名（键序不乱），
     * 否则新条目追加在最后。
     */
    fun upsert(root: JsonObject, oldName: String?, name: String, config: JsonObject): JsonObject =
        root.withServers { servers ->
            val out = LinkedHashMap<String, JsonElement>()
            var placed = false
            for ((key, value) in servers) {
                when {
                    oldName != null && key == oldName -> { out[name] = config; placed = true }
                    key == name && oldName != name -> { /* 同名旧条目被这条替换，落在别处 */ }
                    else -> out[key] = value
                }
            }
            if (!placed) out[name] = config
            out
        }

    fun remove(root: JsonObject, name: String): JsonObject =
        root.withServers { servers -> servers.filterKeys { it != name } }

    /**
     * 开 / 关一条。写 `enabled: false` 或者**删掉**这个键（缺省就是开），不写 `enabled: true`：
     * 文档说 `enabled: false` 才是「保留条目但不连接」，缺省即开；这样文件里不会堆满默认值。
     */
    fun setEnabled(root: JsonObject, name: String, enabled: Boolean): JsonObject =
        root.withServers { servers ->
            val current = servers[name] as? JsonObject ?: return@withServers servers
            val next = LinkedHashMap<String, JsonElement>(current.toMap())
            if (enabled) next.remove("enabled") else next["enabled"] = JsonPrimitive(false)
            LinkedHashMap(servers).also { it[name] = JsonObject(next) }
        }

    /**
     * 工程级覆盖：在工程文件里给某个**用户级**服务器写（或收回）一条只含 `enabled` 的覆盖。
     * 收回 = 覆盖里除了 `enabled` 没别的键时整条删掉，让用户级的设定重新透出来；
     * 覆盖里还有 `exposure` 等键就只去掉 `enabled`。
     */
    fun setProjectOverride(root: JsonObject, name: String, enabled: Boolean, globalEnabled: Boolean): JsonObject =
        root.withServers { servers ->
            val out = LinkedHashMap<String, JsonElement>(servers)
            val existing = servers[name] as? JsonObject
            if (enabled == globalEnabled) {
                // 与用户级一致：不需要覆盖 `enabled`。
                if (existing != null) {
                    val rest = existing.toMap().filterKeys { it != "enabled" }
                    if (rest.isEmpty()) out.remove(name) else out[name] = JsonObject(rest)
                }
            } else {
                val next = LinkedHashMap<String, JsonElement>(existing?.toMap().orEmpty())
                if (enabled) next.remove("enabled") else next["enabled"] = JsonPrimitive(false)
                // 全局关、工程要开：`enabled` 缺省 = 开，但空对象会被 pi 当成「覆盖了什么都没写」，
                // 所以明确写 true。
                if (enabled) next["enabled"] = JsonPrimitive(true)
                out[name] = JsonObject(next)
            }
            out
        }

    /** 根上的 `autoEnableCodemode`：缺省 = 自动启用 codemode；只有显式 `false` 才是关。 */
    fun autoEnableCodemode(root: JsonObject): Boolean =
        (root["autoEnableCodemode"] as? JsonPrimitive)?.booleanOrNull != false

    /** 开 = 删键（缺省即开），关 = 写 `false`。键序不动。 */
    fun setAutoEnableCodemode(root: JsonObject, on: Boolean): JsonObject {
        val next = LinkedHashMap<String, JsonElement>(root.toMap())
        if (on) next.remove("autoEnableCodemode") else next["autoEnableCodemode"] = JsonPrimitive(false)
        return JsonObject(next)
    }

    /** 空条目（`JsonNull` 之类）不该出现在文件里；保存前过一遍。 */
    fun dropNulls(root: JsonObject): JsonObject =
        JsonObject(root.filterValues { it !is JsonNull })

    // ------------------------------------------------------------------ 常用模板

    /** 一键添加用的模板：都是 npx 能直接跑的官方参考服务器，运行环境（Node）App 自带。 */
    data class Template(val title: String, val note: String, val draft: Draft)

    val TEMPLATES: List<Template> = listOf(
        Template(
            "文件系统",
            "让模型读写指定目录里的文件（官方参考实现）",
            Draft(
                name = "filesystem",
                command = "npx",
                args = "-y\n@modelcontextprotocol/server-filesystem\n.",
                description = "读写文件系统里的文件",
            ),
        ),
        Template(
            "记忆图谱",
            "把重要事实存成知识图谱，跨会话记住",
            Draft(
                name = "memory",
                command = "npx",
                args = "-y\n@modelcontextprotocol/server-memory",
                description = "持久化的知识图谱记忆",
            ),
        ),
        Template(
            "逐步思考",
            "给复杂问题一个分步推理的草稿本",
            Draft(
                name = "sequential-thinking",
                command = "npx",
                args = "-y\n@modelcontextprotocol/server-sequential-thinking",
                description = "分步骤思考与修正",
            ),
        ),
        Template(
            "网址服务器",
            "已经有现成的 MCP 网址（https://…/mcp），填进去即可",
            Draft(name = "", http = true, url = "https://"),
        ),
    )
}

/**
 * 一份 `mcp.json` 文件的读写。
 *
 * 与 `PiSettingsFileStore` 同一套底线：**原子写**（临时文件 + rename，被杀在写到一半时不会留下截断的
 * 配置，而 pi 读到截断的 JSON 会整份跳过）、**每次改动都是「重读 → 改一处 → 写回」**（别处刚写进去的
 * 条目不会被一份陈旧的内存副本盖掉）、**损坏的文件不覆盖**。
 */
class PiMcpFile(val file: File) {

    /** 读当前文件。每次都读盘：这个文件很小，而且 pi 自己的 `/mcp` 也会改它。 */
    fun read(): PiMcpConfig.Document =
        PiMcpConfig.parse(if (file.isFile) runCatching { file.readText() }.getOrNull() else null)

    /**
     * 重读、套 [transform]、写回。返回 `null` = 成功；否则是给用户看的原因。
     * 文件损坏或写盘失败都不会动原文件。
     */
    @Synchronized
    fun update(transform: (JsonObject) -> JsonObject): String? {
        val before = read()
        if (before.corrupt) return "这份 mcp.json 不是合法的 JSON，为了不弄丢里面的内容，没有改动它。请先在「Pi 文件」里修好或删掉它。"
        val after = PiMcpConfig.dropNulls(transform(before.root))
        return try {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, "${file.name}.tmp-${COUNTER.incrementAndGet()}")
            temp.writeText(PiMcpConfig.render(after))
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            null
        } catch (e: Exception) {
            "写入失败：${e.message ?: e.javaClass.simpleName}"
        }
    }

    private companion object {
        val COUNTER = AtomicLong()
    }
}
