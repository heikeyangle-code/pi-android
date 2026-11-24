package app.pi.host

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 认证的类型化入口：供应商、API Key、OAuth/订阅登录、刷新模型目录。
 *
 * 全是宿主命令（`auth.*`），宿主背后是 SDK 的 `ModelRuntime`：协议细节（回调服务器、设备码、刷新令牌、
 * 落盘到 `auth.json`）都是官方的，这里只有「读」「写」和**登录会话**的收发。
 *
 * 登录是长事务：`auth.login` 在用户去浏览器授权期间一直不应答，所以 [login] 的超时很长，
 * 中途的两类消息走宿主的事件/提问流（`PiEngineSession.hostEvents` / `hostRequests`）：
 *  - 事件 `auth.event {loginId, providerId, event}`：要打开的链接、设备码、进度、提示（含 links）；
 *  - 提问 `auth.prompt {loginId, providerId, prompt}`：text / secret / select / manual_code，答复用 `PiHostClient.respond`；
 *  - 事件 `ui.cancel {id}`：OAuth 本地回调赢下竞态或登录结束时，宿主收回挂起的提问。
 * [AuthEvent] / [AuthPrompt] 把这些消息解成强类型。
 */
class HostAuth(private val commands: HostCommands) {

    data class Method(
        val type: String,
        val name: String,
        val isSubscription: Boolean,
        val loginLabel: String?,
        /** `api_key` 方式是否真的支持交互式保存 Key（某些供应商仅支持环境变量/隐式凭据）。 */
        val interactive: Boolean = true,
    ) {
        val isOauth get() = type == "oauth"
        val isApiKey get() = type == "api_key"
    }

    data class Provider(
        val id: String,
        val name: String,
        val methods: List<Method>,
        val configured: Boolean,
        val usingOAuth: Boolean,
        /** SDK 的 `getProviderAuthStatus` 原样：有的是字符串，有的是对象，界面只用它的文字形式。 */
        val status: JsonElement?,
        /**
         * SDK 的 `isUsingSubscription`：正在用 OAuth **且**该供应商的 OAuth 是订阅账号。
         * 「订阅登录」标签只能看它——`usingOAuth` 对非订阅型 OAuth 也是 true。
         */
        val usingSubscription: Boolean = false,
        /** 是否为内置或扩展注册的供应商（false 表示纯 `models.json` 自定义供应商）。 */
        val builtin: Boolean = true,
    ) {
        /** `getProviderAuthStatus().source`：`runtime`（本进程临时）/ `stored`（auth.json）/ `environment` / 配置里写的。 */
        val statusSource: String? get() = ((status as? JsonObject)?.get("source") as? JsonPrimitive)?.takeIf { it.isString }?.content

        val supportsApiKey get() = methods.any { it.isApiKey }
        /** 真正支持在界面输入并保存 API Key 的供应商。 */
        val supportsApiKeyInput get() = methods.any { it.isApiKey && it.interactive }
        /** 仅通过环境变量/隐式凭据工作的供应商（无 apiKey.login）。 */
        val hasAmbientApiKey get() = methods.any { it.isApiKey && !it.interactive }
        val oauth: Method? get() = methods.firstOrNull { it.isOauth }
        val isSubscription get() = oauth?.isSubscription == true
        /** 非订阅型的 OAuth 网页登录（如 OpenRouter / Radius）。 */
        val isWebLogin get() = oauth != null && !isSubscription
    }

    data class Credential(val providerId: String, val type: String)

    suspend fun providers(): List<Provider> = rows(commands.call("auth.providers").data["providers"]).map(::provider)

    suspend fun credentials(): List<Credential> =
        rows(commands.call("auth.credentials").data["credentials"]).map { Credential(str(it["providerId"]).orEmpty(), str(it["type"]).orEmpty()) }

    /** 持久化到 `auth.json`（官方 `login(…, "api_key")` 同一条路）。空 Key 宿主会拒绝。 */
    suspend fun setApiKey(providerId: String, apiKey: String): Provider? =
        optProvider(commands.call("auth.setApiKey", JsonObject(mapOf("providerId" to JsonPrimitive(providerId), "apiKey" to JsonPrimitive(apiKey)))).data)

    suspend fun logout(providerId: String): Provider? =
        optProvider(commands.call("auth.logout", JsonObject(mapOf("providerId" to JsonPrimitive(providerId)))).data)

    /**
     * 登录并等到结束：成功返回更新后的供应商；用户取消 / 失败抛 [HostException]，原因是宿主给的原话。
     * [loginId] 由调用方给，之后 [cancel] 用它中止。
     */
    suspend fun login(providerId: String, type: String, loginId: String): Provider? =
        optProvider(
            commands.call(
                "auth.login",
                JsonObject(mapOf("providerId" to JsonPrimitive(providerId), "type" to JsonPrimitive(type), "loginId" to JsonPrimitive(loginId))),
                LOGIN_TIMEOUT_MS,
            ).data,
        )

    suspend fun cancel(loginId: String): Boolean =
        (commands.call("auth.cancel", JsonObject(mapOf("loginId" to JsonPrimitive(loginId)))).data["cancelled"] as? JsonPrimitive)?.content == "true"

    /** 重新拉各供应商的模型目录（要网络）。 */
    suspend fun refreshModels(): JsonElement? = commands.call("auth.refreshModels", timeoutMs = REFRESH_TIMEOUT_MS).data["result"]

    // ---- 登录中的消息 ----

    data class InfoLink(val url: String, val label: String?)

    /** `auth.event` 的内容。[kind] 是 SDK 事件的类型名（`auth_url` / `device_code` / `info` / `progress`）。 */
    data class AuthEvent(
        val loginId: String,
        val providerId: String,
        val kind: String,
        val url: String?,
        val code: String?,
        val message: String?,
        val links: List<InfoLink> = emptyList(),
        val openBrowserOnConfirm: Boolean = false,
        val copyCodeOnConfirm: Boolean = false,
        val raw: JsonObject = JsonObject(emptyMap()),
    )

    data class PromptOption(val id: String, val label: String, val description: String?)

    /** `auth.prompt` 的内容（`text` / `secret` / `select` / `manual_code`）。[requestId] 是应答时要用的提问 id。 */
    data class AuthPrompt(
        val requestId: String,
        val loginId: String,
        val providerId: String,
        val type: String,
        val message: String,
        val placeholder: String?,
        val secret: Boolean,
        val allowEmpty: Boolean,
        val options: List<PromptOption>,
        val raw: JsonObject,
    ) {
        val isSelect get() = type == "select" && options.isNotEmpty()
        val isManualCode get() = type == "manual_code"
    }

    companion object {
        const val LOGIN_TIMEOUT_MS = 15 * 60_000L
        const val REFRESH_TIMEOUT_MS = 120_000L

        fun authEvent(event: HostSignal.Event): AuthEvent? {
            if (event.name != "auth.event") return null
            val d = event.data as? JsonObject ?: return null
            val e = d["event"] as? JsonObject ?: return null
            val links = (e["links"] as? JsonArray)?.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val u = str(o["url"])?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                InfoLink(url = u, label = str(o["label"]))
            }.orEmpty()
            return AuthEvent(
                loginId = str(d["loginId"]).orEmpty(),
                providerId = str(d["providerId"]).orEmpty(),
                kind = str(e["type"]) ?: str(e["kind"]) ?: "info",
                url = str(e["url"]) ?: str(e["verificationUri"]) ?: str(e["verificationUrl"]),
                code = str(e["userCode"]) ?: str(e["code"]),
                message = str(e["message"]) ?: str(e["instructions"]) ?: str(e["text"]),
                links = links,
                openBrowserOnConfirm = bool(e["openBrowserOnConfirm"], false),
                copyCodeOnConfirm = bool(e["copyCodeOnConfirm"], false),
                raw = e,
            )
        }

        fun authPrompt(request: HostRequest): AuthPrompt? {
            if (request.method != "auth.prompt") return null
            val p = request.params["prompt"] as? JsonObject ?: JsonObject(emptyMap())
            val type = str(p["type"]) ?: "text"
            val options = (p["options"] as? JsonArray)?.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val id = str(o["id"]) ?: return@mapNotNull null
                PromptOption(
                    id = id,
                    label = str(o["label"]) ?: id,
                    description = str(o["description"]),
                )
            }.orEmpty()
            return AuthPrompt(
                requestId = request.id,
                loginId = str(request.params["loginId"]).orEmpty(),
                providerId = str(request.params["providerId"]).orEmpty(),
                type = type,
                message = str(p["message"]) ?: str(p["title"]) ?: str(p["label"]) ?: "",
                placeholder = str(p["placeholder"]),
                secret = type == "secret" || bool(p["secret"], false) || bool(p["password"], false),
                allowEmpty = bool(p["allowEmpty"], false),
                options = options,
                raw = p,
            )
        }

        /** `ui.cancel { id }`：宿主收回了一个挂起的提问（例如回调服务器胜出后收回 manual_code）。 */
        fun cancelId(event: HostSignal.Event): String? {
            if (event.name != "ui.cancel") return null
            val d = event.data as? JsonObject ?: return null
            return str(d["id"])
        }

        private fun str(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it.isString }?.content
        private fun bool(e: JsonElement?, default: Boolean): Boolean = (e as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: default
    }

    // ---- 内部 ----
    private fun optProvider(d: JsonObject): Provider? = (d["provider"] as? JsonObject)?.let(::provider)

    private fun provider(o: JsonObject) = Provider(
        id = str(o["id"]).orEmpty(),
        name = str(o["name"]) ?: str(o["id"]).orEmpty(),
        methods = rows(o["methods"]).map {
            Method(
                type = str(it["type"]).orEmpty(),
                name = str(it["name"]).orEmpty(),
                isSubscription = (it["isSubscription"] as? JsonPrimitive)?.content == "true",
                loginLabel = str(it["loginLabel"]),
                interactive = (it["interactive"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: true,
            )
        },
        configured = (o["configured"] as? JsonPrimitive)?.content == "true",
        usingOAuth = (o["usingOAuth"] as? JsonPrimitive)?.content == "true",
        status = o["status"]?.takeIf { it !is JsonNull },
        usingSubscription = (o["usingSubscription"] as? JsonPrimitive)?.content == "true",
        builtin = (o["builtin"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: true,
    )

    private fun rows(e: JsonElement?): List<JsonObject> = (e as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
    private fun str(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it.isString }?.content
}
