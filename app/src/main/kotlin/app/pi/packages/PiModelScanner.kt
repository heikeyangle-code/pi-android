package app.pi.packages

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException
import java.net.SocketTimeoutException

/**
 * Asks a vendor which models the key can see — the one step that turns "paste a key"
 * into a working setup.
 *
 * ## What is verifiable here, and what is not
 *
 * This has to be said plainly, because it decides how much the rest of the app may
 * claim:
 *
 *  - **pi has no model-listing code.** `createProvider` accepts an optional
 *    `fetchModels` hook (`packages/ai/src/models.ts:760-763`, called at `:831`), and
 *    **not one provider implements it** — a repo-wide grep for `fetchModels` finds
 *    only `models.ts` and two test files. pi's own catalogs are the static
 *    per-provider model tables under `packages/ai/src/providers/`, refreshed from
 *    pi.dev, not from the vendor.
 *  - Consequently the HTTP shapes below are **app-side knowledge**, and the app says
 *    so in the UI rather than presenting them as "what pi does". What pi's source
 *    *does* settle is the credential shape (`auth.json`, [PiAuthStorage]) and the
 *    provider/model shape (`models.json`, [PiModelsFile]) — those are not guesses.
 *  - Anthropic's `x-api-key` + `anthropic-version` pair is not in pi either: pi
 *    delegates to `@anthropic-ai/sdk` inside `api/anthropic-messages.ts`
 *    (`assertRequestAuth` only *checks* that one of them is present, `:298-302`).
 *
 * ## The behaviour that matters more than the parsing
 *
 * **The scan can always be skipped.** A 404, a gateway that does not list models, an
 * air-gapped LAN — none of them may leave the user stuck, so every failure returns
 * `allowManual = true` and the manual model-id field is always available. A failed
 * probe with no way forward is the dead end this class is written to avoid: the
 * model ids are also the only thing a vendor listing gives you, so the scan is a
 * *convenience* over typing one id.
 *
 * Failures are classified so the message can be actionable rather than "失败":
 * `401`/`403` is a bad key, `404` is a wrong base URL or a non-listing endpoint,
 * a timeout and an unknown host are different sentences.
 */
class PiModelScanner(
    /** Injectable so the classification logic is testable without a network. */
    private val transport: (Request) -> Response = ::httpTransport,
) {

    data class Request(
        val url: String,
        val headers: Map<String, String>,
        val timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    )

    data class Response(val status: Int, val body: String, val error: String? = null)

    /** One model id as the vendor reported it, plus whatever else it volunteered. */
    data class ScannedModel(
        val id: String,
        val displayName: String? = null,
        /** True when the vendor's own payload said so; most do not. */
        val vendorSuppliedMetadata: Boolean = false,
    )

    sealed interface Result {
        /**
         * The key works. That is what "测试连接" means — the scan **is** the test, so
         * the UI never asks the user to press two buttons.
         */
        data class Ok(
            val models: List<ScannedModel>,
            /** Shown verbatim so a wrong-looking list can be attributed. */
            val endpoint: String,
            val note: String? = null,
        ) : Result

        data class Failed(
            val kind: Kind,
            val message: String,
            /** What to do about it. Never empty. */
            val suggestion: String,
            val endpoint: String,
        ) : Result {
            /** Always true: a failed scan must never block the manual path. */
            val allowManual: Boolean get() = true
        }
    }

    enum class Kind {
        /** 401/403 — the key is wrong, expired, or for another host. */
        Unauthorized,

        /** 404/405 — wrong base URL, or the endpoint does not list models. */
        NoListing,

        /** 429. */
        RateLimited,

        /** 5xx. */
        ServerError,

        Timeout,
        Network,
        BadResponse,
    }

    suspend fun scan(
        preset: PiProviderPresets.Preset,
        apiKey: String,
        baseUrlOverride: String? = null,
    ): Result = withContext(Dispatchers.IO) {
        val base = (baseUrlOverride ?: preset.baseUrl).trim().trimEnd('/')
        if (base.isEmpty()) {
            return@withContext Result.Failed(
                kind = Kind.NoListing,
                message = "Base URL 为空。",
                suggestion = "先填 Base URL（自定义端点必填）。",
                endpoint = "",
            )
        }

        val endpoint = when (preset.scanStyle) {
            // pi's own baseUrls already carry the version prefix for these
            // (`openai.ts:10` is `.../v1`, `groq.ts:10` is `.../openai/v1`), so the
            // path is appended, not replaced.
            PiProviderPresets.ScanStyle.OpenAiCompatible,
            PiProviderPresets.ScanStyle.Keyless,
            -> "$base/models"

            // Anthropic's base has no version segment (`anthropic.ts:47`).
            PiProviderPresets.ScanStyle.Anthropic -> "$base/v1/models"

            // Google's base already ends in `/v1beta` (`google.ts:10`) and takes the
            // key as a query parameter.
            PiProviderPresets.ScanStyle.Google -> "$base/models?key=${urlEncode(apiKey)}"
        }

        val headers = when (preset.scanStyle) {
            PiProviderPresets.ScanStyle.OpenAiCompatible ->
                mapOf("Authorization" to "Bearer $apiKey", "Accept" to "application/json")

            PiProviderPresets.ScanStyle.Anthropic -> mapOf(
                "x-api-key" to apiKey,
                "anthropic-version" to ANTHROPIC_VERSION,
                "Accept" to "application/json",
            )

            PiProviderPresets.ScanStyle.Google -> mapOf("Accept" to "application/json")

            PiProviderPresets.ScanStyle.Keyless ->
                if (apiKey.isBlank()) emptyMap()
                else mapOf("Authorization" to "Bearer $apiKey", "Accept" to "application/json")
        }

        val response = runCatching { transport(Request(endpoint, headers)) }.getOrElse { error ->
            return@withContext when (error) {
                is SocketTimeoutException -> Result.Failed(
                    Kind.Timeout,
                    "请求超时（${DEFAULT_TIMEOUT_MS / 1000} 秒）：$endpoint",
                    "检查网络，或该地址在当前网络下不可达（公司网关、需要代理）。手动填模型名不受影响。",
                    endpoint,
                )
                is UnknownHostException ->
                    Result.Failed(
                        Kind.Network,
                        "无法解析主机名：${error.message ?: endpoint}",
                        "Base URL 的域名可能写错了，或设备当前没有 DNS。",
                        endpoint,
                    )
                else -> Result.Failed(
                    Kind.Network,
                    "网络请求失败：${error::class.java.simpleName}: ${error.message}",
                    "确认设备联网，且该地址可达。手动填模型名不受影响。",
                    endpoint,
                )
            }
        }

        response.error?.let {
            return@withContext Result.Failed(
                Kind.Network,
                "网络请求失败：$it",
                "确认设备联网，且该地址可达。手动填模型名不受影响。",
                endpoint,
            )
        }

        when (response.status) {
            200 -> Unit
            401, 403 -> return@withContext Result.Failed(
                Kind.Unauthorized,
                "HTTP ${response.status}：Key 被拒绝（$endpoint）",
                "Key 不对、已失效、或不属于这个厂商。回到上一步重新粘贴，注意不要带多余空格。",
                endpoint,
            )
            404, 405 -> return@withContext Result.Failed(
                Kind.NoListing,
                "HTTP ${response.status}：该地址没有模型清单端点（$endpoint）",
                "Base URL 可能不对，或该端点不提供模型列表。请手动填写模型名——这不会影响保存和使用。",
                endpoint,
            )
            429 -> return@withContext Result.Failed(
                Kind.RateLimited,
                "HTTP 429：请求过于频繁",
                "稍后重试，或直接手动填写模型名。",
                endpoint,
            )
            in 500..599 -> return@withContext Result.Failed(
                Kind.ServerError,
                "HTTP ${response.status}：厂商服务端错误",
                "不是你的配置问题。稍后重试，或手动填写模型名。",
                endpoint,
            )
            else -> return@withContext Result.Failed(
                Kind.BadResponse,
                "HTTP ${response.status}：无法识别",
                "手动填写模型名，或换一个 Base URL。",
                endpoint,
            )
        }

        val models = parseModels(response.body)
        if (models.isEmpty()) {
            return@withContext Result.Failed(
                Kind.BadResponse,
                "Key 可用，但返回里没有模型（$endpoint）",
                "端点响应格式不是预期的 data[].id / models[].name。请手动填写模型名。",
                endpoint,
            )
        }
        Result.Ok(
            models = models,
            endpoint = endpoint,
            note = "清单只提供模型 id；上下文长度、价格等元数据厂商通常不给，" +
                "未匹配到 pi 内置目录的会用默认值并在界面上标注。",
        )
    }

    /**
     * Three response shapes, tried in order:
     *
     *  - OpenAI-compatible: `{"data":[{"id":"gpt-4o"}]}`
     *  - Anthropic: `{"data":[{"id":"claude-…","display_name":"Claude …"}]}`
     *  - Google: `{"models":[{"name":"models/gemini-2.0-flash"}]}` (the `models/`
     *    prefix is stripped, because that is the *resource name*, and
     *    `models.json` wants the bare id).
     */
    fun parseModels(body: String): List<ScannedModel> {
        val document = PiConfigFiles.parseObject(body) ?: return emptyList()
        val data = document["data"] as? JsonArray
        if (data != null) {
            return data.mapNotNull { entry ->
                val object0 = entry as? JsonObject ?: return@mapNotNull null
                val id = (object0["id"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val name = (object0["display_name"] as? JsonPrimitive)?.content
                    ?: (object0["name"] as? JsonPrimitive)?.content
                ScannedModel(id = id, displayName = name)
            }
        }
        val models = document["models"] as? JsonArray ?: return emptyList()
        return models.mapNotNull { entry ->
            val object0 = entry as? JsonObject ?: return@mapNotNull null
            val raw = (object0["name"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            val id = raw.removePrefix("models/").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ScannedModel(id = id, displayName = (object0["displayName"] as? JsonPrimitive)?.content)
        }
    }

    private fun urlEncode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8")

    companion object {
        /** Header value Anthropic's REST API requires. App-side, see the class KDoc. */
        const val ANTHROPIC_VERSION = "2023-06-01"

        private const val DEFAULT_TIMEOUT_MS = 15_000

        /**
         * `HttpURLConnection` and nothing else: the app has no HTTP client dependency
         * and adding one for a single GET would be a new dependency to maintain
         * (`app/build.gradle.kts` has no OkHttp/Retrofit).
         *
         * `errorStream` is read on a non-2xx status: without that, a 401 body — which
         * is where vendors put the actual reason — is silently dropped.
         */
        fun httpTransport(request: Request): Response = runCatching {
            val connection = (URL(request.url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = request.timeoutMs
                readTimeout = request.timeoutMs
                instanceFollowRedirects = true
                request.headers.forEach { (name, value) -> setRequestProperty(name, value) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            connection.disconnect()
            Response(status = status, body = body, error = null)
        }.getOrElse { error ->
            Response(status = 0, body = "", error = "${error::class.java.simpleName}: ${error.message}")
        }
    }
}
