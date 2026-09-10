package app.pi.highlight

import app.pi.ui.render.PiCodeSpan
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Credentials the guest published for its highlight service.
 *
 * @param source the file this was read from, for diagnostics only.
 */
internal class PiHighlightCredentials(
    val port: Int,
    val token: String,
    val source: String,
)

/**
 * Client for the `pi-highlight` guest extension.
 *
 * The extension (`app/src/main/assets/pi-extensions/pi-highlight/`) runs inside
 * the proot guest next to pi and serves `POST /highlight` on loopback with a
 * bearer token. This class is the Android half: find the token, ask, translate.
 *
 * It is a mirror of the device bridge's client, with the roles reversed — there
 * the guest reads `device-bridge.json` out of the guest filesystem, here the app
 * reads `highlight-bridge.json` out of the *host* filesystem at the paths that
 * guest file maps onto. The extension publishes to two guest paths for the same
 * reason `DeviceBridgeController` writes two host paths: which one survives
 * depends on the engine's bind mounts, and a missing token file is
 * indistinguishable from "no engine running".
 *
 * **Everything here is allowed to fail.** A missing token file, a connection
 * refused because the engine is not up, a timeout, a non-JSON reply, a span
 * outside the code — every one of them returns `null` and the caller renders the
 * block with pi's plain `mdCodeBlock` colour. Highlighting is a decoration; it
 * must never be able to throw into composition or paint the wrong characters.
 */
internal class PiHighlightClient(private val credentialFiles: List<File>) {

    @Volatile
    private var cached: PiHighlightCredentials? = null

    private val lock = Any()

    /** Diagnostics: the last reason a request could not be served. */
    @Volatile
    var lastFailure: String? = null
        private set

    /**
     * Read the credentials, preferring the cached copy.
     *
     * @param force re-read from disk. Used after a 401 (the engine mints a new
     *   token on every start) so one stale read cannot disable highlighting for
     *   the rest of the session.
     */
    fun credentials(force: Boolean = false): PiHighlightCredentials? {
        if (!force) {
            cached?.let { return it }
        }
        synchronized(lock) {
            if (!force) {
                cached?.let { return it }
            }
            for (file in credentialFiles) {
                val parsed = readCredentials(file) ?: continue
                cached = parsed
                return parsed
            }
            return null
        }
    }

    private fun readCredentials(file: File): PiHighlightCredentials? = runCatching {
        if (!file.isFile) return@runCatching null
        val json = JSONObject(file.readText())
        val token = json.optString("token", "")
        if (token.isEmpty()) return@runCatching null
        val port = json.optInt("port", 0)
        if (port <= 0 || port > 65535) return@runCatching null
        PiHighlightCredentials(port = port, token = token, source = file.absolutePath)
    }.getOrNull()

    /**
     * One request.
     *
     * @return the spans (possibly empty, which is a definitive answer and is
     *   cached by the caller), or `null` when the answer cannot be trusted —
     *   transport failure, malformed reply, or a reply whose character count
     *   disagrees with the code that was sent.
     */
    fun fetch(code: String, language: String): List<PiCodeSpan>? {
        for (attempt in 0..1) {
            val credentials = credentials(force = attempt > 0) ?: run {
                lastFailure = "读不到 highlight-bridge.json（引擎可能未运行）"
                return null
            }
            val reply = runCatching { post(credentials, code, language) }.getOrElse { error ->
                lastFailure = "${error::class.java.simpleName}: ${error.message}"
                return null
            }
            when (reply) {
                is Reply.Spans -> {
                    lastFailure = null
                    return reply.spans
                }
                is Reply.Failed -> {
                    lastFailure = reply.reason
                    return null
                }
                is Reply.Stale -> {
                    // The engine mints a new token on every start, so a 401 means
                    // the file is worth re-reading — exactly once. A second 401 is
                    // a real failure and the block stays plain.
                    lastFailure = "token 被拒绝（HTTP 401）"
                    if (attempt == 0) cached = null else return null
                }
            }
        }
        return null
    }

    private sealed interface Reply {
        /** Definitive: these spans (or none) are the answer. */
        class Spans(val spans: List<PiCodeSpan>) : Reply

        /** The token was rejected; the caller re-reads and tries once more. */
        object Stale : Reply

        /** The service answered with a refusal or something unusable. */
        class Failed(val reason: String) : Reply
    }

    private fun post(credentials: PiHighlightCredentials, code: String, language: String): Reply {
        val payload = JSONObject()
            .put("code", code)
            .put("language", language)
            .toString()
            .toByteArray(Charsets.UTF_8)

        val connection = URL("http://127.0.0.1:${credentials.port}/highlight")
            .openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            // A request that cannot answer inside this budget is not worth
            // waiting for: the block is already on screen, and plain text is the
            // correct fallback (spec: short timeout, always fall back).
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Authorization", "Bearer ${credentials.token}")
            // No `Accept-Encoding`: gzip would buy nothing on loopback and only
            // add a decode step to a hot path.
            connection.setFixedLengthStreamingMode(payload.size)
            connection.outputStream.use { it.write(payload) }

            val status = connection.responseCode
            val body = readBounded(connection)
            if (status == HttpURLConnection.HTTP_UNAUTHORIZED) return Reply.Stale
            if (body == null) return Reply.Failed("响应过大或读取失败（HTTP $status）")
            val json = runCatching { JSONObject(body) }.getOrElse {
                return Reply.Failed("响应不是 JSON（HTTP $status）")
            }
            if (status != HttpURLConnection.HTTP_OK || !json.optBoolean("ok", false)) {
                return Reply.Failed(json.optString("reason", "HTTP $status"))
            }
            val data = json.optJSONObject("data") ?: return Reply.Failed("响应缺少 data")
            if (!data.optBoolean("known", false)) {
                // The engine loading the language is a different answer from not
                // shipping it, but both mean "no colour" to the caller.
                return Reply.Spans(emptyList())
            }
            if (data.optInt("codeUnits", -1) != code.length) {
                // The offsets were measured against a different string than the
                // one on screen. Dropping them is the only safe move: applying
                // them would colour the wrong characters.
                return Reply.Failed("codeUnits 与代码长度不一致，偏移量不可信")
            }
            return Reply.Spans(parseSpans(data.optJSONArray("spans"), code.length))
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    private fun parseSpans(array: JSONArray?, codeLength: Int): List<PiCodeSpan> {
        if (array == null || array.length() == 0) return emptyList()
        val spans = ArrayList<PiCodeSpan>(array.length())
        for (index in 0 until array.length()) {
            val span = array.optJSONObject(index) ?: continue
            if (!span.has("start") || !span.has("end")) continue
            val start = span.optInt("start", -1)
            val end = span.optInt("end", -1)
            // Defensive range check. The reply is same-device and authenticated,
            // but a bug on either side must degrade to plain text, never to a
            // coloured block that is quietly wrong.
            if (start < 0 || end <= start || end > codeLength) continue
            val scopes = span.optJSONArray("scopes") ?: continue
            val names = ArrayList<String>(scopes.length())
            for (scopeIndex in 0 until scopes.length()) {
                val name = scopes.optString(scopeIndex, "")
                if (name.isNotEmpty()) names.add(name)
            }
            val token = PiHighlightScopes.tokenFor(names) ?: continue
            spans.add(PiCodeSpan(start = start, end = end, token = token))
        }
        return spans
    }

    /** Read at most [MAX_RESPONSE_BYTES]; a runaway reply must not be buffered. */
    private fun readBounded(connection: HttpURLConnection): String? {
        val stream = runCatching {
            if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        }.getOrNull() ?: return null
        return stream.use { input ->
            val buffer = ByteArray(16 * 1024)
            val out = java.io.ByteArrayOutputStream()
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                if (out.size() + read > MAX_RESPONSE_BYTES) return null
                out.write(buffer, 0, read)
            }
            out.toString("UTF-8")
        }
    }

    private companion object {
        /**
         * Loopback on the same device: a connect that takes longer than this
         * means nothing is listening, and a read that takes longer means the
         * engine is busy enough that the block should stay plain.
         */
        const val CONNECT_TIMEOUT_MS = 100
        const val READ_TIMEOUT_MS = 150
        const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
    }
}
