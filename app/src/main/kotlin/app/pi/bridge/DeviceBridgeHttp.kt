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
                DeviceDenial.DISABLED, DeviceDenial.BLOCKED_BY_POLICY -> 403
                DeviceDenial.NO_PERMISSION -> 403
                DeviceDenial.NOT_FOUND -> 404
                DeviceDenial.BAD_REQUEST -> 400
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
        var request: BridgeHttpRequest? = null
        try {
            client.soTimeout = 20_000
            val parsed = readRequest(client.getInputStream()) ?: return
            request = parsed
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
            audit(
                BridgeAuditEvent(
                    requestId = id,
                    method = parsed.method,
                    path = parsed.path,
                    status = response.status,
                    ok = response.status in 200..299,
                    note = noteOf(response),
                ),
            )
        } catch (timeout: SocketTimeoutException) {
            audit(BridgeAuditEvent(id, "?", "?", 408, false, "请求超时"))
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
                BridgeAuditEvent(
                    id,
                    request?.method ?: "?",
                    request?.path ?: "?",
                    500,
                    false,
                    error::class.java.simpleName,
                ),
            )
        } finally {
            runCatching { client.close() }
        }
    }

    private fun noteOf(response: BridgeHttpResponse): String {
        if (response.status in 200..299) return ""
        return runCatching {
            val json = JSONObject(response.body)
            (json.optString("code") + " " + json.optString("reason")).trim()
        }.getOrDefault("")
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
        else -> "Internal Server Error"
    }

    companion object {
        private const val CR = '\r'.code
        private const val LF = '\n'.code
        private const val MAX_HEADER_BYTES = 16 * 1024
        private const val MAX_BODY_BYTES = 8 * 1024 * 1024
    }
}

/** One audited bridge request (design §23.6: 设备操作全进本地审计日志). */
data class BridgeAuditEvent(
    val requestId: Int,
    val method: String,
    val path: String,
    val status: Int,
    val ok: Boolean,
    val note: String,
)

/**
 * Append-only audit trail. Bounded, because a runaway agent loop must not be able
 * to fill the user's storage — the log is a diagnostic, not a database.
 */
class DeviceAuditLog(private val file: java.io.File) {

    /** Where the trail lives, for the diagnostics card and the health endpoint. */
    val path: String get() = file.absolutePath

    fun record(event: BridgeAuditEvent) {
        val line = buildString {
            append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date()))
            append("  #").append(event.requestId)
            append(' ').append(event.method).append(' ').append(event.path)
            append(" -> ").append(event.status)
            if (!event.ok && event.note.isNotEmpty()) append("  ").append(event.note)
            append('\n')
        }
        synchronized(this) {
            runCatching {
                file.parentFile?.mkdirs()
                file.appendText(line)
                if (file.length() > MAX_BYTES) rotate()
            }
        }
    }

    fun tail(maxLines: Int): List<String> = synchronized(this) {
        if (!file.isFile) return emptyList()
        val lines = runCatching { file.readLines() }.getOrDefault(emptyList())
        lines.takeLast(maxLines.coerceIn(1, 2000))
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
