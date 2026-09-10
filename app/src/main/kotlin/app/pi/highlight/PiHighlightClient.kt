package app.pi.highlight

import app.pi.ui.render.PiCodeSpan
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

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

    /**
     * One HTTP/1.1 request over a raw loopback socket.
     *
     * A socket rather than `HttpURLConnection` on purpose. Android's network
     * security policy is enforced by the HTTP stacks and not by `Socket`, and for
     * `targetSdk >= 28` cleartext HTTP is refused unless the manifest opts in —
     * the manifest is not this change's to edit, and whether the policy exempts
     * `127.0.0.1` is not something this code should have to bet on. A socket also
     * sidesteps connection pooling and DNS for what is a fixed, one-shot request
     * to a service on the same device.
     */
    private fun post(credentials: PiHighlightCredentials, code: String, language: String): Reply {
        val payload = JSONObject()
            .put("code", code)
            .put("language", language)
            .toString()
            .toByteArray(Charsets.UTF_8)

        val head = buildString {
            append("POST /highlight HTTP/1.1\r\n")
            append("Host: 127.0.0.1:").append(credentials.port).append("\r\n")
            append("Authorization: Bearer ").append(credentials.token).append("\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ").append(payload.size).append("\r\n")
            // The service closes every connection; saying so avoids it having to
            // wait out a keep-alive timeout on our socket.
            append("Connection: close\r\n")
            append("\r\n")
        }

        Socket().use { socket ->
            // A request that cannot answer inside this budget is not worth
            // waiting for: the block is already on screen, and plain text is the
            // correct fallback (spec: short timeout, always fall back).
            socket.soTimeout = READ_TIMEOUT_MS
            socket.connect(InetSocketAddress(InetAddress.getByName(LOOPBACK), credentials.port), CONNECT_TIMEOUT_MS)
            socket.getOutputStream().apply {
                write(head.toByteArray(Charsets.ISO_8859_1))
                write(payload)
                flush()
            }
            val reply = readReply(socket.getInputStream())
            if (reply.status == HTTP_UNAUTHORIZED) return Reply.Stale
            if (reply.body == null) return Reply.Failed("响应过大或读取失败（HTTP ${reply.status}）")
            val json = runCatching { JSONObject(reply.body) }.getOrElse {
                return Reply.Failed("响应不是 JSON（HTTP ${reply.status}）")
            }
            if (reply.status != HTTP_OK || !json.optBoolean("ok", false)) {
                return Reply.Failed(json.optString("reason", "HTTP ${reply.status}"))
            }
            val data = json.optJSONObject("data") ?: return Reply.Failed("响应缺少 data")
            if (!data.optBoolean("known", false)) {
                // Unknown language and "the language is still loading" are
                // different answers from the service, but both mean "no colour"
                // to the caller, and neither should be cached as a failure.
                return Reply.Spans(emptyList())
            }
            if (data.optInt("codeUnits", -1) != code.length) {
                // The offsets were measured against a different string than the
                // one on screen. Dropping them is the only safe move: applying
                // them would colour the wrong characters.
                return Reply.Failed("codeUnits 与代码长度不一致，偏移量不可信")
            }
            return Reply.Spans(parseSpans(data.optJSONArray("spans"), code.length))
        }
    }

    private class HttpReply(val status: Int, val body: String?)

    /** Status line, headers (ignored), then Content-Length bytes or EOF. */
    private fun readReply(input: InputStream): HttpReply {
        fun readLine(): String? {
            val line = ByteArrayOutputStream()
            while (line.size() <= MAX_HEADER_BYTES) {
                val byte = input.read()
                if (byte == -1) return if (line.size() == 0) null else line.toString("ISO-8859-1")
                if (byte == '\n'.code) return line.toString("ISO-8859-1").trimEnd('\r')
                line.write(byte)
            }
            return null
        }

        val statusLine = readLine() ?: return HttpReply(0, null)
        val status = statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: 0
        var contentLength = -1
        while (true) {
            val line = readLine() ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            if (line.substring(0, colon).trim().equals("content-length", ignoreCase = true)) {
                contentLength = line.substring(colon + 1).trim().toIntOrNull() ?: -1
            }
        }
        if (contentLength > MAX_RESPONSE_BYTES) return HttpReply(status, null)

        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (contentLength < 0 || out.size() < contentLength) {
            val read = input.read(buffer)
            if (read <= 0) break
            val wanted = if (contentLength < 0) read else minOf(read, contentLength - out.size())
            if (out.size() + wanted > MAX_RESPONSE_BYTES) return HttpReply(status, null)
            out.write(buffer, 0, wanted)
        }
        return HttpReply(status, out.toString("UTF-8"))
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

    private companion object {
        const val LOOPBACK = "127.0.0.1"

        /**
         * Loopback on the same device: a connect that takes longer than this
         * means nothing is listening, and a read that takes longer means the
         * engine is busy enough that the block should stay plain.
         */
        const val CONNECT_TIMEOUT_MS = 100
        const val READ_TIMEOUT_MS = 150
        const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
        const val MAX_HEADER_BYTES = 16 * 1024
        const val HTTP_OK = 200
        const val HTTP_UNAUTHORIZED = 401
    }
}
