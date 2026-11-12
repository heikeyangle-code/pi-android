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
 *  - 事件 `auth.event {loginId, providerId, event}`：要打开的链接、设备码、进度、提示；
 *  - 提问 `auth.prompt {loginId, providerId, prompt}`：要用户输入的东西，答复用 `PiHostClient.respond`。
 * [AuthEvent] / [AuthPrompt] 把这两类消息解成类型。
 */
class HostAuth(private val commands: HostCommands) {

    data class Method(val type: String, val name: String, val isSubscription: Boolean, val loginLabel: String?) {
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
    ) {
        val supportsApiKey get() = methods.any { it.isApiKey }
        val oauth: Method? get() = methods.firstOrNull { it.isOauth }
        val isSubscription get() = oauth?.isSubscription == true
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

    /** `auth.event` 的内容。[kind] 是 SDK 事件的类型名，[raw] 留全，界面按需取字段。 */
    data class AuthEvent(val loginId: String, val providerId: String, val kind: String, val url: String?, val code: String?, val message: String?, val raw: JsonObject)

    /** `auth.prompt` 的内容：要用户输入什么。[requestId] 是应答时要用的提问 id。 */
    data class AuthPrompt(val requestId: String, val loginId: String, val providerId: String, val message: String, val placeholder: String?, val secret: Boolean, val raw: JsonObject)

    companion object {
        const val LOGIN_TIMEOUT_MS = 15 * 60_000L
        const val REFRESH_TIMEOUT_MS = 120_000L

        fun authEvent(event: HostSignal.Event): AuthEvent? {
            if (event.name != "auth.event") return null
            val d = event.data as? JsonObject ?: return null
            val e = d["event"] as? JsonObject ?: return null
            return AuthEvent(
                loginId = str(d["loginId"]).orEmpty(),
                providerId = str(d["providerId"]).orEmpty(),
                kind = str(e["type"]) ?: str(e["kind"]) ?: "info",
                url = str(e["url"]) ?: str(e["verificationUri"]) ?: str(e["verificationUrl"]),
                code = str(e["userCode"]) ?: str(e["code"]),
                message = str(e["message"]) ?: str(e["instructions"]) ?: str(e["text"]),
                raw = e,
            )
        }

        fun authPrompt(request: HostRequest): AuthPrompt? {
            if (request.method != "auth.prompt") return null
            val p = request.params["prompt"] as? JsonObject ?: JsonObject(emptyMap())
            return AuthPrompt(
                requestId = request.id,
                loginId = str(request.params["loginId"]).orEmpty(),
                providerId = str(request.params["providerId"]).orEmpty(),
                message = str(p["message"]) ?: str(p["title"]) ?: str(p["label"]) ?: "",
                placeholder = str(p["placeholder"]),
                secret = (p["secret"] as? JsonPrimitive)?.content == "true" || (p["password"] as? JsonPrimitive)?.content == "true",
                raw = p,
            )
        }

        private fun str(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it.isString }?.content
    }

    // ---- 内部 ----
    private fun optProvider(d: JsonObject): Provider? = (d["provider"] as? JsonObject)?.let(::provider)

    private fun provider(o: JsonObject) = Provider(
        id = str(o["id"]).orEmpty(),
        name = str(o["name"]) ?: str(o["id"]).orEmpty(),
        methods = rows(o["methods"]).map {
            Method(str(it["type"]).orEmpty(), str(it["name"]).orEmpty(), (it["isSubscription"] as? JsonPrimitive)?.content == "true", str(it["loginLabel"]))
        },
        configured = (o["configured"] as? JsonPrimitive)?.content == "true",
        usingOAuth = (o["usingOAuth"] as? JsonPrimitive)?.content == "true",
        status = o["status"]?.takeIf { it !is JsonNull },
    )

    private fun rows(e: JsonElement?): List<JsonObject> = (e as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
    private fun str(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it.isString }?.content
}
