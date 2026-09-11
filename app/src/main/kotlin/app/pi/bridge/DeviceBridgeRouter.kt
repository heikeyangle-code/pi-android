package app.pi.bridge

import android.content.Context
import android.os.Build
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * Path → capability → action.
 *
 * Every device endpoint is expressed as `withCapability(group) { … }`, so the
 * opt-in check cannot be forgotten when a new endpoint is added: without it there
 * is no `JSONObject` to return. The same shape turns any refusal — capability off,
 * Android permission missing, gesture rejected — into one JSON envelope carrying a
 * code and a Chinese sentence the model can relay verbatim.
 *
 * `/app/health`, `/app/capabilities` and `/app/audit` are the only unGated routes,
 * because they are how a caller (and the app's own diagnostics) discovers *why*
 * something else is refused. They still require the bearer token.
 */
class DeviceBridgeRouter(
    private val context: Context,
    private val port: Int,
    private val tokenFileLabel: String,
    private val auditLog: DeviceAuditLog,
) {
    private val store: DeviceCapabilityStore = DeviceCapabilityStore.get(context)

    fun route(request: BridgeHttpRequest): BridgeHttpResponse {
        if (request.method != "GET" && request.method != "POST") {
            return BridgeHttpResponse(
                400,
                errorBody(DeviceDenial(DeviceDenial.BAD_REQUEST, "只支持 GET 与 POST，收到 ${request.method}。")).toString(),
            )
        }
        val path = request.path.trimEnd('/').ifEmpty { "/" }
        val params = Params(request)
        return try {
            when (path) {
                "/app/health" -> BridgeHttpResponse.okRaw(health())
                "/app/capabilities" -> BridgeHttpResponse.okRaw(JSONObject().put("capabilities", capabilityArray()))
                "/app/audit" -> BridgeHttpResponse.okRaw(audit(params.int("lines", 200)))

                "/app/ui/dump" -> withCapability(DeviceCapability.Accessibility) {
                    val service = requireAccessibilityService()
                    DeviceUiAutomation.dump(
                        service = service,
                        filter = params.str("filter"),
                        maxNodes = params.int("maxNodes", DeviceUiAutomation.DEFAULT_MAX_NODES),
                        maxDepth = params.int("maxDepth", 32),
                    )
                }

                "/app/ui/tap" -> withCapability(DeviceCapability.Accessibility) {
                    val service = requireAccessibilityService()
                    runBlocking {
                        DeviceUiAutomation.tap(
                            service = service,
                            index = params.intOrNull("index"),
                            x = params.intOrNull("x"),
                            y = params.intOrNull("y"),
                            longPress = params.bool("longPress", false),
                        )
                    }
                }

                "/app/ui/input" -> withCapability(DeviceCapability.Accessibility) {
                    val service = requireAccessibilityService()
                    DeviceUiAutomation.input(
                        service = service,
                        text = params.str("text").orEmpty(),
                        index = params.intOrNull("index"),
                        submit = params.bool("submit", false),
                    )
                }

                "/app/ui/key" -> withCapability(DeviceCapability.Accessibility) {
                    val service = requireAccessibilityService()
                    DeviceUiAutomation.key(service, params.str("key").orEmpty())
                }

                "/app/ui/swipe" -> withCapability(DeviceCapability.Accessibility) {
                    val service = requireAccessibilityService()
                    runBlocking {
                        DeviceUiAutomation.swipe(
                            service = service,
                            x1 = params.intRequired("x1"),
                            y1 = params.intRequired("y1"),
                            x2 = params.intRequired("x2"),
                            y2 = params.intRequired("y2"),
                            durationMs = params.int("durationMs", 300),
                        )
                    }
                }

                "/app/ui/screenshot", "/app/screenshot" -> withCapability(DeviceCapability.Accessibility) {
                    val service = requireAccessibilityService()
                    DeviceUiAutomation.screenshot(
                        service = service,
                        format = params.str("format") ?: "jpeg",
                        maxDimension = params.int("maxDimension", 1280),
                        quality = params.int("quality", 82),
                    )
                }

                "/app/apps" -> withCapability(DeviceCapability.Basic) {
                    DeviceAppActions.list(
                        context = context,
                        query = params.str("q"),
                        includeSystem = params.bool("includeSystem", false),
                        limit = params.int("limit", 60),
                    )
                }

                "/app/apps/launch" -> withCapability(DeviceCapability.Basic) {
                    DeviceAppActions.launch(context, params.strRequired("package"))
                }

                // Stopping an app is device control with real data-loss potential, so
                // it lives behind the highest-friction group (无障碍) rather than 基础.
                "/app/apps/stop" -> withCapability(DeviceCapability.Accessibility) {
                    DeviceAppActions.stop(context, params.strRequired("package"))
                }

                "/app/clipboard" ->
                    if (request.method == "POST") {
                        withCapability(DeviceCapability.Basic) {
                            DeviceSystemActions.clipboardSet(context, params.str("text").orEmpty())
                        }
                    } else {
                        withCapability(DeviceCapability.Basic) { DeviceSystemActions.clipboardGet(context) }
                    }

                "/app/notify" -> withCapability(DeviceCapability.Basic) {
                    DeviceSystemActions.notify(
                        context = context,
                        title = params.str("title") ?: "pi",
                        text = params.strRequired("text"),
                        id = params.int("id", (System.currentTimeMillis() % 100000).toInt() + 1),
                    )
                }

                "/app/toast" -> withCapability(DeviceCapability.Basic) {
                    DeviceSystemActions.toast(context, params.strRequired("text"), params.bool("long", false))
                }

                "/app/vibrate" -> withCapability(DeviceCapability.Basic) {
                    DeviceSystemActions.vibrate(
                        context = context,
                        milliseconds = params.int("ms", 200),
                        pattern = params.longList("pattern"),
                    )
                }

                "/app/share" -> withCapability(DeviceCapability.Basic) {
                    DeviceSystemActions.share(
                        context = context,
                        text = params.str("text").orEmpty(),
                        subject = params.str("subject"),
                        url = params.str("url"),
                    )
                }

                "/app/open" -> withCapability(DeviceCapability.Basic) {
                    DeviceSystemActions.open(context, params.strRequired("url"))
                }

                "/app/tts" -> withCapability(DeviceCapability.Basic) {
                    DeviceSystemActions.speak(
                        context = context,
                        text = params.strRequired("text"),
                        languageTag = params.str("language").orEmpty(),
                        rate = params.double("rate", 1.0),
                        pitch = params.double("pitch", 1.0),
                        timeoutMs = params.int("timeoutMs", 20_000),
                    )
                }

                "/app/location" -> withCapability(DeviceCapability.Sensors) {
                    DeviceSystemActions.location(context)
                }

                "/app/sensors" -> withCapability(DeviceCapability.Sensors) {
                    DeviceSystemActions.sensors(context)
                }

                "/app/sensor" -> withCapability(DeviceCapability.Sensors) {
                    DeviceSystemActions.sensorSample(
                        context = context,
                        typeName = params.str("typeName").orEmpty(),
                        type = params.int("type", 0),
                        timeoutMs = params.int("timeoutMs", 1500),
                    )
                }

                "/app/battery" -> withCapability(DeviceCapability.Sensors) {
                    DeviceSystemActions.battery(context)
                }

                "/app/torch" -> withCapability(DeviceCapability.Sensors) {
                    DeviceSystemActions.torch(context, params.boolRequired("on"))
                }

                "/app/export" -> withCapability(DeviceCapability.Storage) {
                    DeviceSystemActions.export(
                        context = context,
                        name = params.str("name") ?: "pi-export.txt",
                        text = params.str("content"),
                        base64 = params.str("base64"),
                        mimeType = params.str("mimeType") ?: "text/plain",
                    )
                }

                "/app/import" -> withCapability(DeviceCapability.Storage) {
                    DeviceSystemActions.import(
                        context = context,
                        name = params.strRequired("name"),
                        maxBytes = params.int("maxBytes", 1024 * 1024),
                    )
                }

                "/app/shell" -> withCapability(DeviceCapability.Shell) {
                    val command = params.strRequired("command")
                    // One switch, two enforcers: the TS gate reads the same boolean
                    // from /app/health, so the dialog and this guard cannot disagree.
                    val relaxed = store.isShellSyntaxRelaxed()
                    DeviceWorkspace.refresh(context)
                    DeviceShellGuard.inspect(command, relaxed, DeviceWorkspace)?.let { throw DeviceActionException(it) }
                    val backend = DeviceShellGuard.active()
                    DeviceShellGuard.toJson(
                        result = backend.run(command, params.int("timeoutMs", 15_000)),
                        relaxedShellSyntax = relaxed,
                        boundaryLabel = DeviceWorkspace.shellPath(),
                    )
                }

                // Raw key injection. The accessibility channel can only do the five
                // GLOBAL_ACTIONs; `input keyevent` needs uid 2000, so this endpoint
                // exists only to make the Shizuku payoff reachable.
                "/app/ui/keyevent" -> withCapability(DeviceCapability.Accessibility) {
                    DeviceUiAutomation.keyEvent(
                        keys = params.strRequired("keys"),
                        repeat = params.int("repeat", 1),
                        backend = DeviceShellGuard.active(),
                    )
                }

                "/app/files" -> withCapability(DeviceCapability.Storage) {
                    DeviceSafStore.get(context).describe()
                }

                "/app/files/list" -> withCapability(DeviceCapability.Storage) {
                    DeviceSafStore.get(context).list(params.str("path"))
                }

                "/app/files/read" -> withCapability(DeviceCapability.Storage) {
                    DeviceSafStore.get(context).read(
                        path = params.strRequired("path"),
                        maxBytes = params.int("maxBytes", 1024 * 1024),
                    )
                }

                "/app/files/write" -> withCapability(DeviceCapability.Storage) {
                    DeviceSafStore.get(context).write(
                        path = params.strRequired("path"),
                        text = params.str("content"),
                        base64 = params.str("base64"),
                        mimeType = params.str("mimeType") ?: "text/plain",
                    )
                }

                // The pi-side permission gate reports what it has approved. Display
                // only: nothing here changes policy (the gate's own memory is the
                // enforcement), and the UI labels it as extension-reported.
                "/app/gate/report" -> {
                    DeviceApprovalLedger.report(params.body())
                    BridgeHttpResponse.okRaw(DeviceApprovalLedger.toJson())
                }

                else -> BridgeHttpResponse(404, notFound(path).toString())
            }
        } catch (error: DeviceActionException) {
            BridgeHttpResponse.denial(error.denial)
        } catch (error: Exception) {
            BridgeHttpResponse.denial(
                DeviceDenial(
                    code = DeviceDenial.ERROR,
                    reason = "设备桥处理 ${request.path} 时出错：${error::class.java.simpleName}: ${error.message}",
                ),
            )
        }
    }

    // ------------------------------------------------------------- plumbing ----

    private fun withCapability(
        capability: DeviceCapability,
        block: () -> JSONObject,
    ): BridgeHttpResponse {
        store.check(capability)?.let { return BridgeHttpResponse.denial(it, capability) }
        return try {
            BridgeHttpResponse.ok(block(), capability)
        } catch (error: DeviceActionException) {
            BridgeHttpResponse.denial(error.denial, capability)
        }
    }

    private fun requireAccessibilityService(): android.accessibilityservice.AccessibilityService =
        DeviceAccessibilityService.running() ?: throw DeviceActionException(
            DeviceDenial(
                code = DeviceDenial.NO_PERMISSION,
                reason = "无障碍服务未运行。",
                hint = "请让用户在「设置 → 无障碍」中启用 pi 设备桥。",
            ),
        )

    private fun health(): JSONObject = JSONObject().apply {
        put("service", "pi-android-device-bridge")
        put("version", BRIDGE_VERSION)
        put("port", port)
        put("tokenFile", tokenFileLabel)
        put("capabilities", capabilityArray())
        put("accessibilityRunning", DeviceAccessibilityService.isRunning())
        put("accessibilityEnabledInSettings", DeviceAccessibilityService.isEnabledInSettings(context))
        put("screenshotSupported", Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        put("locationPermissionGranted", store.hasLocationPermission())
        put("notificationPermissionGranted", store.hasNotificationPermission())
        put("vibratePermissionGranted", store.hasVibratePermission())
        put("legacyStoragePermissionGranted", store.hasLegacyStoragePermission())
        // The relaxed-syntax switch is published so the pi-side gate can honour the
        // exact same boolean the Kotlin guard enforces.
        put("shellSyntaxRelaxed", store.isShellSyntaxRelaxed())
        DeviceWorkspace.refresh(context)
        put("workspace", JSONObject().apply {
            put("shellPath", DeviceWorkspace.shellPath() ?: JSONObject.NULL)
            // The engine's spelling, not a constant: the caller here is the extension
            // inside the process the engine spawned, whose cwd is
            // `/workspace/<relative-to-filesDir>` (`PiEngineHost.guestPathFor`). The
            // terminal's plain `/workspace` is a *different* mount of the same host
            // directory, so both are published and neither is guessed.
            put("guestPath", DeviceWorkspace.guestPath())
            put("guestPathAliases", JSONArray(DeviceWorkspace.guestPathAliases()))
            put("known", DeviceWorkspace.isKnown())
        })
        put("shizuku", DeviceShizuku.status(context))
        put("gate", DeviceApprovalLedger.toJson())
        // The whole policy, so a model (and the diagnostics page) can see exactly
        // what is permitted instead of inferring it from refusals.
        put("shellPolicy", JSONObject().apply {
            put("allowedCommands", JSONArray(DeviceShellGuard.allowedCommands))
            put("blocked", JSONArray(DeviceShellGuard.blockedSummary()))
            put("writeBoundary", JSONArray(DeviceShellGuard.writeBoundarySummary()))
            put("syntax", JSONArray(DeviceShellGuard.syntaxSummary(store.isShellSyntaxRelaxed())))
            put("relaxedCost", DeviceShellGuard.relaxedCost())
            put("elevatedBackend", DeviceShellGuard.hasElevatedBackend())
        })
        put("saf", JSONObject().apply {
            val grants = DeviceSafStore.get(context).grants()
            put("count", grants.size)
            put("roots", org.json.JSONArray(grants.map { it.name }))
        })
        put("shellBackends", JSONArray().apply {
            for (backend in DeviceShellGuard.backends()) {
                put(
                    JSONObject()
                        .put("id", backend.id)
                        .put("label", backend.label)
                        .put("available", backend.available),
                )
            }
        })
        put("androidRelease", Build.VERSION.RELEASE)
        put("sdkInt", Build.VERSION.SDK_INT)
        put("auditLogPath", auditLog.path)
    }

    private fun capabilityArray(): JSONArray = JSONArray().apply {
        for (state in store.states()) {
            put(
                JSONObject().apply {
                    put("id", state.capability.id)
                    put("title", state.capability.title)
                    put("summary", state.capability.summary)
                    put("enabled", state.enabled)
                    put("enabledForSession", state.enabledForSession)
                    put("usable", state.usable)
                    if (state.reason != null) put("reason", state.reason)
                    put("allows", JSONArray(state.capability.allows))
                },
            )
        }
    }

    private fun audit(lines: Int): JSONObject = JSONObject().apply {
        put("path", auditLog.path)
        put("lines", JSONArray(auditLog.tail(lines)))
    }

    private fun notFound(path: String): JSONObject = JSONObject().apply {
        put("ok", false)
        put("code", DeviceDenial.NOT_FOUND)
        put("reason", "设备桥没有这个接口：$path")
        put("hint", "可用接口见 pi-android 的 docs/pi-android-app-design.md §21.3。")
        put("endpoints", JSONArray(ENDPOINTS))
    }

    private fun errorBody(denial: DeviceDenial): JSONObject = JSONObject().apply {
        put("ok", false)
        put("code", denial.code)
        put("reason", denial.reason)
    }

    /** Parameter accessor: JSON body wins, then the query string. */
    private class Params(private val request: BridgeHttpRequest) {
        private val json: JSONObject by lazy { request.json() }

        /** The raw request body as JSON, for endpoints that take a whole object. */
        fun body(): JSONObject = json

        private fun value(name: String): Any? {
            if (json.has(name) && !json.isNull(name)) return json.get(name)
            return request.queryValue(name)
        }

        fun str(name: String): String? {
            val raw = value(name) ?: return null
            val text = raw.toString()
            return if (text.isEmpty()) null else text
        }

        fun strRequired(name: String): String = str(name) ?: throw DeviceActionException(
            DeviceDenial(DeviceDenial.BAD_REQUEST, "缺少必填参数「$name」。"),
        )

        fun int(name: String, default: Int): Int = intOrNull(name) ?: default

        fun intOrNull(name: String): Int? {
            val raw = value(name) ?: return null
            return when (raw) {
                is Number -> raw.toInt()
                else -> raw.toString().trim().toIntOrNull()
            }
        }

        fun intRequired(name: String): Int = intOrNull(name) ?: throw DeviceActionException(
            DeviceDenial(DeviceDenial.BAD_REQUEST, "缺少必填数字参数「$name」。"),
        )

        fun double(name: String, default: Double): Double {
            val raw = value(name) ?: return default
            return when (raw) {
                is Number -> raw.toDouble()
                else -> raw.toString().trim().toDoubleOrNull() ?: default
            }
        }

        fun bool(name: String, default: Boolean): Boolean {
            val raw = value(name) ?: return default
            return when (raw) {
                is Boolean -> raw
                is Number -> raw.toInt() != 0
                else -> when (raw.toString().trim().lowercase()) {
                    "true", "1", "yes", "on" -> true
                    "false", "0", "no", "off" -> false
                    else -> default
                }
            }
        }

        fun boolRequired(name: String): Boolean {
            val raw = value(name) ?: throw DeviceActionException(
                DeviceDenial(DeviceDenial.BAD_REQUEST, "缺少必填布尔参数「$name」。"),
            )
            return when (raw) {
                is Boolean -> raw
                is Number -> raw.toInt() != 0
                else -> when (raw.toString().trim().lowercase()) {
                    "true", "1", "yes", "on" -> true
                    "false", "0", "no", "off" -> false
                    else -> throw DeviceActionException(
                        DeviceDenial(DeviceDenial.BAD_REQUEST, "参数「$name」不是布尔值：$raw"),
                    )
                }
            }
        }

        fun longList(name: String): List<Long>? {
            val raw = value(name) ?: return null
            if (raw is JSONArray) {
                if (raw.length() == 0) return null
                val out = ArrayList<Long>(raw.length())
                for (i in 0 until raw.length()) out.add(raw.optLong(i))
                return out
            }
            val text = raw.toString().trim()
            if (text.isEmpty()) return null
            return text.split(',').mapNotNull { it.trim().toLongOrNull() }.ifEmpty { null }
        }
    }

    companion object {
        /** Bumped whenever the endpoint set or a payload shape changes. */
        const val BRIDGE_VERSION = "2"

        /**
         * The port the bridge listens on. Deliberately not 3090: the shipping DSH
         * app on this device owns that port, and two local bridges answering the
         * same paths on one phone is a debugging trap with a security smell.
         */
        const val DEFAULT_PORT = 3175

        val ENDPOINTS = listOf(
            "GET  /app/health",
            "GET  /app/capabilities",
            "GET  /app/audit",
            "POST /app/ui/dump",
            "POST /app/ui/tap",
            "POST /app/ui/input",
            "POST /app/ui/key",
            "POST /app/ui/keyevent",
            "POST /app/ui/swipe",
            "POST /app/screenshot",
            "GET  /app/apps",
            "POST /app/apps/launch",
            "POST /app/apps/stop",
            "GET  /app/clipboard",
            "POST /app/clipboard",
            "POST /app/notify",
            "POST /app/toast",
            "POST /app/vibrate",
            "POST /app/share",
            "POST /app/open",
            "POST /app/tts",
            "GET  /app/location",
            "GET  /app/sensors",
            "GET  /app/sensor",
            "GET  /app/battery",
            "POST /app/torch",
            "POST /app/export",
            "POST /app/import",
            "GET  /app/files",
            "POST /app/files/list",
            "POST /app/files/read",
            "POST /app/files/write",
            "POST /app/shell",
            "POST /app/gate/report",
        )
    }
}
