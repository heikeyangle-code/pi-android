package app.pi.highlight

import app.pi.ui.render.PiCodeHighlight
import app.pi.ui.render.PiCodeSpan
import app.pi.ui.render.PiMermaidArt
import app.pi.ui.render.PiMermaidReply
import app.pi.ui.render.PiMermaidRun
import app.pi.ui.render.piMermaidClassOf
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
     * @return the answer — possibly no spans, which is definitive and is cached by
     *   the caller, and whose [PiCodeHighlight.languageKnown] is the service's own
     *   `known` flag, i.e. pi's `supportsLanguage`. `null` means the answer cannot
     *   be trusted at all: transport failure, malformed reply, or a reply whose
     *   character count disagrees with the code that was sent. The difference
     *   matters — `known = false` renders as pi's *uncoloured* branch, while a
     *   failure must not be cached as if it were an answer.
     */
    fun fetch(code: String, language: String): PiCodeHighlight? {
        for (attempt in 0..1) {
            val credentials = credentials(force = attempt > 0) ?: run {
                lastFailure = "读不到 highlight-bridge.json（引擎可能未运行）"
                return null
            }
            val payload = JSONObject().put("code", code).put("language", language)
            val reply = runCatching { post(credentials, "/highlight", payload) }.getOrElse { error ->
                lastFailure = "${error::class.java.simpleName}: ${error.message}"
                // A transport failure is how a *restarted* engine announces itself:
                // the port and token in the credentials we cached belong to a process
                // that is gone, and nothing will ever answer 401 for them (see the
                // 401 branch below). Dropping the cache costs one file read on the
                // next request and is what makes highlighting come back by itself
                // after the engine is replaced — without it, a single restart would
                // leave every code block uncoloured for the life of the app process.
                cached = null
                return null
            }
            if (reply.status == HTTP_UNAUTHORIZED) {
                // The engine mints a new token on every start, so a 401 means the
                // file is worth re-reading — exactly once. A second 401 is a real
                // failure and the block stays uncoloured.
                lastFailure = "token 被拒绝（HTTP 401）"
                if (attempt == 0) {
                    cached = null
                    continue
                }
                return null
            }
            val body = reply.body ?: run {
                lastFailure = "响应过大或读取失败（HTTP ${reply.status}）"
                return null
            }
            val json = runCatching { JSONObject(body) }.getOrElse {
                lastFailure = "响应不是 JSON（HTTP ${reply.status}）"
                return null
            }
            if (reply.status != HTTP_OK || !json.optBoolean("ok", false)) {
                lastFailure = json.optString("reason", "HTTP ${reply.status}")
                return null
            }
            val data = json.optJSONObject("data") ?: run {
                lastFailure = "响应缺少 data"
                return null
            }
            if (!data.optBoolean("known", false)) {
                // `known = false` is the service repeating pi's own
                // `supportsLanguage` answer: this language is not one highlight.js
                // has, so there is nothing to colour and the block must render in
                // pi's *uncoloured* branch. It is a definitive answer, not a
                // failure — which is why it is carried out as a flag rather than
                // collapsed into "empty spans" (an empty span list is also what a
                // real, known language can legitimately produce).
                lastFailure = null
                return PiCodeHighlight()
            }
            if (data.optInt("codeUnits", -1) != code.length) {
                // The offsets were measured against a different string than the
                // one on screen. Dropping them is the only safe move: applying
                // them would colour the wrong characters.
                lastFailure = "codeUnits 与代码长度不一致，偏移量不可信"
                return null
            }
            lastFailure = null
            return PiCodeHighlight(
                spans = parseSpans(data.optJSONArray("spans"), code.length),
                languageKnown = true,
            )
        }
        return null
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
    private fun post(credentials: PiHighlightCredentials, path: String, payload: JSONObject): HttpReply {
        val body = payload.toString().toByteArray(Charsets.UTF_8)

        val head = buildString {
            append("POST ").append(path).append(" HTTP/1.1\r\n")
            append("Host: 127.0.0.1:").append(credentials.port).append("\r\n")
            append("Authorization: Bearer ").append(credentials.token).append("\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
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
                write(body)
                flush()
            }
            return readReply(socket.getInputStream())
        }
    }

    /**
     * One mermaid render request.
     *
     * Same envelope and the same failure contract as [fetch], but the answer is typed,
     * because the caller's next move depends on *why* there is no drawing
     * ([PiMermaidReply]): [PiMermaidReply.NoArt] is pi's own "there is no art to show"
     * (`renderable: false`, i.e. `render()` returned `null`) and is definitive;
     * [PiMermaidReply.Unavailable] is everything that a retry might fix — the engine is
     * not up yet, or is still importing `grok-mermaid` on this first request, or the
     * reply was malformed or truncated.
     *
     * There is nothing to validate against the input the way `codeUnits` guards a
     * highlight: these runs are a *drawing* of the source rather than offsets into it,
     * so a wrong answer cannot mis-colour anything — it can only fail to arrive.
     */
    fun mermaid(source: String): PiMermaidReply {
        for (attempt in 0..1) {
            val credentials = credentials(force = attempt > 0) ?: run {
                lastFailure = "读不到 highlight-bridge.json（引擎可能未运行）"
                return PiMermaidReply.Unavailable
            }
            val reply = runCatching { post(credentials, "/mermaid", JSONObject().put("source", source)) }
                .getOrElse { error ->
                    lastFailure = "${error::class.java.simpleName}: ${error.message}"
                    // Same reason as in `fetch`: a dead port means the credentials are
                    // stale, and only re-reading the file can find the new engine.
                    cached = null
                    return PiMermaidReply.Unavailable
                }
            when {
                reply.status == HTTP_UNAUTHORIZED -> {
                    lastFailure = "token 被拒绝（HTTP 401）"
                    if (attempt == 0) {
                        cached = null
                    } else {
                        return PiMermaidReply.Unavailable
                    }
                }
                reply.body == null -> {
                    lastFailure = "响应过大或读取失败（HTTP ${reply.status}）"
                    return PiMermaidReply.Unavailable
                }
                else -> {
                    val json = runCatching { JSONObject(reply.body) }.getOrElse {
                        lastFailure = "响应不是 JSON（HTTP ${reply.status}）"
                        return PiMermaidReply.Unavailable
                    }
                    if (reply.status != HTTP_OK || !json.optBoolean("ok", false)) {
                        lastFailure = json.optString("reason", "HTTP ${reply.status}")
                        return PiMermaidReply.Unavailable
                    }
                    val data = json.optJSONObject("data") ?: run {
                        lastFailure = "响应缺少 data"
                        return PiMermaidReply.Unavailable
                    }
                    if (!data.optBoolean("renderable", false)) {
                        // Definitive: pi keeps the original fence here too, so asking
                        // again would only repeat the answer.
                        lastFailure = null
                        return PiMermaidReply.NoArt
                    }
                    lastFailure = null
                    val art = parseMermaid(data)
                    // A reply that parsed to nothing drawable is treated as "no art"
                    // rather than as a failure: half a drawing is worse than the source.
                    return if (art == null) PiMermaidReply.NoArt else PiMermaidReply.Art(art)
                }
            }
        }
        return PiMermaidReply.Unavailable
    }

    /**
     * The wire shape of `POST /mermaid`'s rows, checked the way [parseSpans] checks
     * spans: anything malformed is dropped rather than drawn. A row that is not an
     * array, a run without text, an unknown class — all of them become "no art" (or,
     * for a single run, an uncoloured one), because a half-parsed drawing is worse
     * than the source the user can still read.
     */
    private fun parseMermaid(data: JSONObject): PiMermaidArt? {
        val rowsJson = data.optJSONArray("rows") ?: return null
        if (rowsJson.length() == 0) return null
        val rows = ArrayList<List<PiMermaidRun>>(rowsJson.length())
        for (rowIndex in 0 until rowsJson.length()) {
            val rowJson = rowsJson.optJSONArray(rowIndex) ?: continue
            val runs = ArrayList<PiMermaidRun>(rowJson.length())
            for (runIndex in 0 until rowJson.length()) {
                val runJson = rowJson.optJSONObject(runIndex) ?: continue
                if (!runJson.has("text")) continue
                runs.add(
                    PiMermaidRun(
                        text = runJson.optString("text", ""),
                        kind = piMermaidClassOf(runJson.optString("cls", "")),
                    ),
                )
            }
            rows.add(runs)
        }
        if (rows.isEmpty()) return null
        val warningsJson = data.optJSONArray("warnings")
        val warnings = ArrayList<String>(warningsJson?.length() ?: 0)
        if (warningsJson != null) {
            for (index in 0 until warningsJson.length()) {
                warningsJson.optString(index, "").takeIf { it.isNotEmpty() }?.let { warnings.add(it) }
            }
        }
        return PiMermaidArt(rows = rows, warnings = warnings)
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
