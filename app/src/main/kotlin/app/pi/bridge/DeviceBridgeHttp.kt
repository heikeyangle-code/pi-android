package app.pi.bridge

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** One parsed HTTP request. Bodies are small; the largest is a base64 export. */
class BridgeHttpRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    private val rawBody: ByteArray,
) {
    val bodyText: String get() = if (rawBody.isEmpty()) "" else String(rawBody, Charsets.UTF_8)

    /** Parsed JSON body, or an empty object when there is none. */
    fun json(): JSONObject {
        val text = bodyText.trim()
        if (text.isEmpty()) return JSONObject()
        return try {
            JSONObject(text)
        } catch (error: Exception) {
            throw DeviceActionException(
                DeviceDenial(
                    code = DeviceDenial.BAD_REQUEST,
                    reason = "请求体不是合法 JSON：${error.message}",
                ),
            )
        }
    }

    fun header(name: String): String? = headers[name.lowercase()]

    fun queryValue(name: String): String? = query[name]
}

/** One HTTP response. Always JSON; the screenshot travels as base64 inside it. */
class BridgeHttpResponse(
    val status: Int,
    val body: String,
    val contentType: String = "application/json; charset=utf-8",
) {
    companion object {
        fun ok(payload: JSONObject, capability: DeviceCapability? = null): BridgeHttpResponse {
            val body = JSONObject()
            body.put("ok", true)
            if (capability != null) body.put("capability", capability.id)
            body.put("data", payload)
            return BridgeHttpResponse(200, body.toString())
        }

        fun okRaw(payload: JSONObject): BridgeHttpResponse {
            val body = JSONObject()
            body.put("ok", true)
            for (key in payload.keys()) body.put(key, payload.get(key))
            return BridgeHttpResponse(200, body.toString())
        }

        fun denial(denial: DeviceDenial, capability: DeviceCapability? = null): BridgeHttpResponse {
            val status = when (denial.code) {
                DeviceDenial.UNAUTHORIZED -> 401
                DeviceDenial.DISABLED, DeviceDenial.BLOCKED_BY_POLICY, DeviceDenial.BLOCKED_BACKGROUND -> 403
                DeviceDenial.NO_PERMISSION -> 403
                DeviceDenial.NOT_FOUND -> 404
                DeviceDenial.BAD_REQUEST -> 400
                // 409/503 are the two "same call, later" answers. They are distinct
                // on purpose: 409 means another operation holds the channel (wait a
                // second), 503 means a dependency is down (the a11y service is
                // rebinding, or Shizuku/the bridge is not there). A client that
                // treats them as one thing gives the wrong advice for one of them.
                DeviceDenial.BUSY -> 409
                DeviceDenial.NOT_CONNECTED, DeviceDenial.BRIDGE_DOWN -> 503
                else -> 500
            }
            return BridgeHttpResponse(status, denialJson(denial, capability).toString())
        }

        private fun denialJson(denial: DeviceDenial, capability: DeviceCapability?): JSONObject =
            JSONObject().apply {
                put("ok", false)
                put("code", denial.code)
                put("reason", denial.reason)
                if (denial.hint != null) put("hint", denial.hint)
                if (capability != null) put("capability", capability.id)
                // Explicit on every denial, including `false`: the model has to be
                // able to branch on "retry" vs "go find a human" without guessing
                // from the code string.
                put("retryable", denial.retryable)
            }
    }
}

/**
 * The local device bridge: HTTP/1.1 over a loopback-only socket.
 *
 * Why hand-rolled instead of Ktor/NanoHTTPD: the app module's dependency set is
 * frozen (the parent owns `build.gradle.kts`), the surface is a dozen tiny JSON
 * endpoints, and `ServerSocket` is in the platform. The protocol subset is
 * deliberately small — one request per connection, `Connection: close`, a body
 * only when `Content-Length` says so.
 *
 * Security properties, in the order the design doc lists them (design §23):
 *  - bound to `127.0.0.1` explicitly, never `0.0.0.0`, so nothing off-device can
 *    reach it (the app declares INTERNET but no inbound exposure);
 *  - every request must carry the bearer token, compared in constant time;
 *  - a distinct port from the other local bridge on this device (3090) so the two
 *    can never be confused, and the port is *published* in the guest token file
 *    rather than guessed by the extension.
 */
class DeviceBridgeHttpServer(
    private val port: Int,
    private val token: String,
    private val handler: (BridgeHttpRequest) -> BridgeHttpResponse,
    private val audit: (BridgeAuditEvent) -> Unit,
) {
    private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private val pool = Executors.newFixedThreadPool(4) { runnable ->
        Thread(runnable, "pi-device-bridge").apply { isDaemon = true }
    }
    private val requestCounter = AtomicInteger(0)
    private var acceptThread: Thread? = null

    fun start() {
        if (running.getAndSet(true)) return
        val socket = ServerSocket()
        socket.reuseAddress = true
        // Loopback only. Binding to the wildcard address would expose the phone's
        // clipboard, screen and notifications to the whole LAN.
        socket.bind(java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 16)
        serverSocket = socket
        val thread = Thread({ acceptLoop(socket) }, "pi-device-bridge-accept")
        thread.isDaemon = true
        thread.start()
        acceptThread = thread
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptThread = null
        // The pool has to go with the socket, and this was the leak: a fixed pool
        // keeps its four threads alive until they are shut down, and
        // `DeviceBridgeController.start()` builds a **new** server (with a new pool)
        // on every call — and it calls this `stop()` first. `start()` runs from
        // `PiEngineHost.bootLocked` on every engine boot, so every app launch and
        // every engine restart used to leak four threads for the life of the process.
        //
        // `shutdownNow` also interrupts a request in flight, which is the honest
        // outcome of tearing the server down: `handle` closes its client socket in a
        // `finally`, so the caller sees a dropped connection rather than a wrong
        // answer. A `stopped` instance is never started again (`DeviceBridgeController`
        // constructs a fresh server instead), so a shut-down pool cannot be asked to
        // run anything: `acceptLoop`'s `catch (rejected)` would close the client.
        runCatching { pool.shutdownNow() }
    }

    fun isRunning(): Boolean = running.get()

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get()) {
            val client = try {
                socket.accept()
            } catch (closed: Exception) {
                break
            }
            try {
                pool.execute { handle(client) }
            } catch (rejected: Exception) {
                runCatching { client.close() }
            }
        }
    }

    private fun handle(client: Socket) {
        val id = requestCounter.incrementAndGet()
        val startedAt = System.currentTimeMillis()
        var request: BridgeHttpRequest? = null
        try {
            client.soTimeout = 20_000
            val parsed = readRequest(client.getInputStream())
            if (parsed == null) {
                // A connection that carried no parseable request used to disappear
                // without a trace: no response, no audit line. That makes "why is the
                // bridge not answering?" unanswerable from the log, so the drop is
                // recorded even though there is nobody to answer.
                audit(
                    BridgeAuditEvent(
                        requestId = id,
                        method = "?",
                        path = "?",
                        status = 400,
                        ok = false,
                        note = "请求无法解析（空连接或请求头超过上限）",
                        denialCode = DeviceDenial.BAD_REQUEST,
                    ),
                )
                return
            }
            request = parsed
            // Write-ahead: the start line lands *before* the handler runs, so a
            // request that dies inside a handler (a crash, an OOM kill, a process
            // restart mid-gesture) still leaves evidence of what was attempted.
            // Without this, the last thing an agent did before killing the bridge
            // was the one thing the audit could not show.
            audit(parsed, id, phase = "start", status = 0, ok = true, note = "", durationMs = -1, facts = null)
            val response = when {
                !authorized(parsed) -> BridgeHttpResponse.denial(
                    DeviceDenial(
                        code = DeviceDenial.UNAUTHORIZED,
                        reason = "设备桥 token 无效或缺失。",
                        hint = "请让维护者确认 token 文件与 App 内的一致；扩展会自动从 guest 内的 token 文件读取。",
                    ),
                )
                else -> handler(parsed)
            }
            write(client.getOutputStream(), response)
            val facts = factsOf(response)
            audit(
                parsed,
                id,
                phase = "result",
                status = response.status,
                ok = response.status in 200..299,
                note = facts.note,
                durationMs = System.currentTimeMillis() - startedAt,
                facts = facts,
            )
        } catch (timeout: SocketTimeoutException) {
            audit(
                request,
                id,
                phase = "result",
                status = 408,
                ok = false,
                note = "请求超时",
                durationMs = System.currentTimeMillis() - startedAt,
                facts = null,
            )
        } catch (denial: DeviceActionException) {
            // `readRequest` refuses an oversized body by throwing a BAD_REQUEST denial
            // (`:277-284`). Falling into the generic branch below turned that 400 into
            // a 500 "internal error" — the caller was told the bridge was broken when
            // it had actually rejected the request on purpose.
            val response = BridgeHttpResponse.denial(denial.denial)
            runCatching { write(client.getOutputStream(), response) }
            val facts = factsOf(response)
            audit(
                request,
                id,
                phase = "result",
                status = response.status,
                ok = false,
                note = facts.note,
                durationMs = System.currentTimeMillis() - startedAt,
                facts = facts,
            )
        } catch (error: Exception) {
            runCatching {
                write(
                    client.getOutputStream(),
                    BridgeHttpResponse.denial(
                        DeviceDenial(
                            code = DeviceDenial.ERROR,
                            reason = "设备桥内部错误：${error::class.java.simpleName}: ${error.message}",
                        ),
                    ),
                )
            }
            audit(
                request,
                id,
                phase = "result",
                status = 500,
                ok = false,
                note = "${error::class.java.simpleName}: ${error.message}",
                durationMs = System.currentTimeMillis() - startedAt,
                facts = null,
            )
        } finally {
            runCatching { client.close() }
        }
    }

    private fun audit(
        request: BridgeHttpRequest?,
        id: Int,
        phase: String,
        status: Int,
        ok: Boolean,
        note: String,
        durationMs: Long,
        facts: ResponseFacts?,
    ) {
        audit(
            BridgeAuditEvent(
                requestId = id,
                method = request?.method ?: "?",
                path = request?.path ?: "?",
                status = status,
                ok = ok,
                note = note,
                phase = phase,
                durationMs = durationMs,
                targetPackage = facts?.targetPackage,
                backend = facts?.backend,
                exitCode = facts?.exitCode,
                denialCode = facts?.denialCode,
            ),
        )
    }

    private class ResponseFacts(
        val note: String,
        val denialCode: String?,
        val targetPackage: String?,
        val backend: String?,
        val exitCode: Int?,
    )

    /**
     * The structured half of an audit line: a denial's code, and the fields the
     * caller actually cares about later (which package, which backend, which exit
     * code). Read out of the response body rather than threaded through every
     * handler, because the body is already the contract the model sees.
     */
    private fun factsOf(response: BridgeHttpResponse): ResponseFacts {
        if (response.status in 200..299) {
            val data = runCatching { JSONObject(response.body).optJSONObject("data") }.getOrNull()
            return ResponseFacts(
                note = "",
                denialCode = null,
                targetPackage = data?.optString("packageName")?.takeIf { it.isNotEmpty() },
                backend = data?.optString("backend")?.takeIf { it.isNotEmpty() },
                exitCode = data?.takeIf { it.has("exitCode") }?.optInt("exitCode"),
            )
        }
        val json = runCatching { JSONObject(response.body) }.getOrNull()
        return ResponseFacts(
            note = ((json?.optString("code") ?: "") + " " + (json?.optString("reason") ?: "")).trim(),
            denialCode = json?.optString("code")?.takeIf { it.isNotEmpty() },
            targetPackage = null,
            backend = null,
            exitCode = null,
        )
    }

    private fun authorized(request: BridgeHttpRequest): Boolean {
        val bearer = request.header("authorization")
        val supplied = when {
            bearer != null && bearer.startsWith("Bearer ", ignoreCase = true) -> bearer.substring(7).trim()
            request.header("x-pi-android-token") != null -> request.header("x-pi-android-token")!!.trim()
            request.queryValue("token") != null -> request.queryValue("token")!!
            else -> return false
        }
        return MessageDigest.isEqual(
            supplied.toByteArray(Charsets.UTF_8),
            token.toByteArray(Charsets.UTF_8),
        )
    }

    private fun readRequest(input: InputStream): BridgeHttpRequest? {
        val buffered = if (input is BufferedInputStream) input else BufferedInputStream(input)
        val header = ByteArrayOutputStream()
        var state = 0
        while (header.size() < MAX_HEADER_BYTES) {
            val byte = buffered.read()
            if (byte == -1) return null
            header.write(byte)
            state = when {
                byte == CR && state == 0 -> 1
                byte == LF && state == 1 -> 2
                byte == CR && state == 2 -> 3
                byte == LF && state == 3 -> 4
                byte == CR -> 1
                else -> 0
            }
            if (state == 4) break
        }
        val lines = String(header.toByteArray(), Charsets.ISO_8859_1).split("\r\n").filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null
        val requestLine = lines.first().split(' ')
        if (requestLine.size < 2) return null
        val method = requestLine[0].uppercase()
        val target = requestLine[1]
        val path = target.substringBefore('?')
        val query = parseQuery(target.substringAfter('?', ""))

        val headers = HashMap<String, String>()
        for (line in lines.drop(1)) {
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }

        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length < 0 || length > MAX_BODY_BYTES) {
            throw DeviceActionException(
                DeviceDenial(
                    code = DeviceDenial.BAD_REQUEST,
                    reason = "请求体过大（$length 字节，上限 $MAX_BODY_BYTES）。",
                ),
            )
        }
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val count = buffered.read(body, read, length - read)
            if (count <= 0) break
            read += count
        }
        val actual = if (read == length) body else body.copyOf(read)
        return BridgeHttpRequest(method, path, query, headers, actual)
    }

    private fun parseQuery(raw: String): Map<String, String> {
        if (raw.isEmpty()) return emptyMap()
        val out = HashMap<String, String>()
        for (pair in raw.split('&')) {
            if (pair.isEmpty()) continue
            val index = pair.indexOf('=')
            val key = if (index < 0) pair else pair.substring(0, index)
            val value = if (index < 0) "" else pair.substring(index + 1)
            out[decode(key)] = decode(value)
        }
        return out
    }

    private fun decode(value: String): String = runCatching {
        URLDecoder.decode(value, "UTF-8")
    }.getOrDefault(value)

    private fun write(output: OutputStream, response: BridgeHttpResponse) {
        val payload = response.body.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 ").append(response.status).append(' ').append(statusText(response.status)).append("\r\n")
            append("Content-Type: ").append(response.contentType).append("\r\n")
            append("Content-Length: ").append(payload.size).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        output.write(head.toByteArray(Charsets.ISO_8859_1))
        output.write(payload)
        output.flush()
    }

    private fun statusText(status: Int): String = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        408 -> "Request Timeout"
        409 -> "Conflict"
        503 -> "Service Unavailable"
        else -> "Internal Server Error"
    }

    companion object {
        private const val CR = '\r'.code
        private const val LF = '\n'.code
        private const val MAX_HEADER_BYTES = 16 * 1024
        private const val MAX_BODY_BYTES = 8 * 1024 * 1024
    }
}

/**
 * One audited bridge request (design §23.6: 设备操作全进本地审计日志).
 *
 * [phase] is what makes the trail crash-proof: `start` is written before the
 * handler runs, `result` after it answers. A `start` with no matching `result` is
 * the signature of a request that killed the process — the one failure mode a
 * post-hoc-only log cannot express.
 */
data class BridgeAuditEvent(
    val requestId: Int,
    val method: String,
    val path: String,
    val status: Int,
    val ok: Boolean,
    val note: String,
    val phase: String = "result",
    val durationMs: Long = -1,
    val targetPackage: String? = null,
    val backend: String? = null,
    val exitCode: Int? = null,
    val denialCode: String? = null,
)

/**
 * Append-only audit trail, one JSON object per line.
 *
 * Why JSON per line rather than the previous free-text line: the log is read by
 * three very different consumers — a human in 设置 → 设备能力, the model through
 * `/app/audit`, and `lastUnpaired()` at bridge start — and only a structured
 * record can answer "how long did it take / which backend / which package / which
 * denial code" without a regex. Bounded, because a runaway agent loop must not be
 * able to fill the user's storage: the log is a diagnostic, not a database.
 */
class DeviceAuditLog(private val file: java.io.File) {

    /** Where the trail lives, for the diagnostics card and the health endpoint. */
    val path: String get() = file.absolutePath

    fun record(event: BridgeAuditEvent) {
        val line = JSONObject().apply {
            put("ts", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US)
                .format(java.util.Date()))
            put("id", event.requestId)
            put("phase", event.phase)
            put("method", event.method)
            put("path", event.path)
            put("status", event.status)
            put("ok", event.ok)
            if (event.durationMs >= 0) put("ms", event.durationMs)
            event.targetPackage?.let { put("package", it) }
            event.backend?.let { put("backend", it) }
            event.exitCode?.let { put("exitCode", it) }
            event.denialCode?.let { put("code", it) }
            if (event.note.isNotEmpty()) put("note", event.note.take(400))
        }.toString()
        synchronized(this) {
            runCatching {
                file.parentFile?.mkdirs()
                file.appendText(line + "\n")
                if (file.length() > MAX_BYTES) rotate()
            }
        }
    }

    /** Raw JSON lines — what `/app/audit` returns and what the tool reads. */
    fun tail(maxLines: Int): List<String> = synchronized(this) {
        if (!file.isFile) return emptyList()
        val lines = runCatching { file.readLines() }.getOrDefault(emptyList())
        lines.takeLast(maxLines.coerceIn(1, 2000))
    }

    /**
     * The same trail for a human: `12:03:41.220 #7 POST /app/ui/tap 200 137ms`.
     * Kept next to [tail] rather than parsed in the UI, because the UI is not the
     * only caller that may want a one-line summary and a JSON blob in a LazyColumn
     * reads as noise.
     */
    fun prettyTail(maxLines: Int): List<String> = tail(maxLines).map { line ->
        val json = runCatching { JSONObject(line) }.getOrNull() ?: return@map line
        val time = json.optString("ts").substringAfter(' ')
        buildString {
            append(time.ifEmpty { json.optString("ts") })
            append(" #").append(json.optInt("id"))
            if (json.optString("phase") == "start") append(" → 执行中")
            append(' ').append(json.optString("method")).append(' ').append(json.optString("path"))
            val status = json.optInt("status")
            if (status > 0) append(" ").append(status)
            if (json.has("ms")) append(" ").append(json.optLong("ms")).append("ms")
            json.optString("backend").takeIf { it.isNotEmpty() }?.let { append(" [").append(it).append(']') }
            json.optString("package").takeIf { it.isNotEmpty() }?.let { append(" ").append(it) }
            json.optString("code").takeIf { it.isNotEmpty() }?.let { append(" ").append(it) }
            json.optString("note").takeIf { it.isNotEmpty() }?.let { append("  ").append(it) }
        }
    }

    /**
     * The crash report: a `start` line whose request never produced a `result`.
     *
     * Called once at bridge start, so the very first thing anyone sees after a
     * process death is what was running when it died — instead of the log simply
     * ending.
     */
    fun lastUnpaired(): String? = synchronized(this) {
        if (!file.isFile) return null
        val lines = runCatching { file.readLines() }.getOrDefault(emptyList()).takeLast(KEEP_LINES)
        val started = LinkedHashMap<Int, String>()
        val finished = HashSet<Int>()
        for (line in lines) {
            val json = runCatching { JSONObject(line) }.getOrNull() ?: continue
            val id = json.optInt("id", -1)
            if (id < 0) continue
            if (json.optString("phase") == "start") {
                started[id] = json.optString("ts") + " " + json.optString("method") + " " + json.optString("path")
            } else {
                finished.add(id)
            }
        }
        val pending = started.filterKeys { it !in finished }
        if (pending.isEmpty()) return null
        val last = pending.entries.last()
        "上次有 ${pending.size} 条请求开始后没有结果（很可能在执行中被中断，例如进程被杀）：" +
            "#${last.key} ${last.value}"
    }

    private fun rotate() {
        val lines = runCatching { file.readLines() }.getOrDefault(emptyList())
        val keep = lines.takeLast(KEEP_LINES)
        runCatching { file.writeText(keep.joinToString("\n", postfix = "\n")) }
    }

    companion object {
        private const val MAX_BYTES = 512L * 1024L
        private const val KEEP_LINES = 2000
    }
}
