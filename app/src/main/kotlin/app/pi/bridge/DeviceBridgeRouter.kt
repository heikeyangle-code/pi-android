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
                errorBody(DeviceDenial(DeviceDenial.BAD_REQUEST, "只支持 GET/POST，收到 ${request.method}。")).toString(),
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
                    // `waitMs` turns a dump into a wait-then-dump: the selector is
                    // resolved on the device, so "wait for the list to load, then
                    // read it" is one call instead of a poll loop from the guest.
                    val selector = params.selector()
                    val waitMs = params.int("waitMs", 0)
                    if (waitMs > 0 || params.bool("requireMatch", false)) {
                        if (selector.isEmpty) {
                            throw DeviceActionException(
                                DeviceDenial(
                                    DeviceDenial.BAD_REQUEST,
                                    "waitMs 需要选择器：text/desc/resourceId/package。",
                                    hint = "去掉 waitMs，或用 selector 指定要等的控件。",
                                ),
                            )
                        }
                        DeviceUiAutomation.wait(
                            service = service,
                            selector = selector,
                            timeoutMs = if (waitMs > 0) waitMs else 1,
                            requireGone = params.bool("gone", false),
                        )
                    }
                    DeviceUiAutomation.dump(
                        service = service,
                        filter = params.str("filter"),
                        maxNodes = params.int("maxNodes", DeviceUiAutomation.DEFAULT_MAX_NODES),
                        maxDepth = params.int("maxDepth", 32),
                        diff = params.bool("diff", false),
                    )
                }

                "/app/ui/wait" -> withCapability(DeviceCapability.Accessibility) {
                    val service = requireAccessibilityService()
                    DeviceUiAutomation.wait(
                        service = service,
                        selector = params.selector(),
                        timeoutMs = params.int("timeoutMs", 5000),
                        requireGone = params.bool("gone", false),
                    )
                }

                "/app/ui/verify" -> withCapability(DeviceCapability.Accessibility) {
                    val service = requireAccessibilityService()
                    DeviceUiAutomation.verify(
                        service = service,
                        x = params.intRequired("x"),
                        y = params.intRequired("y"),
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
                            selector = params.selector(),
                        )
                    }
                }

                "/app/ui/scroll" -> withCapability(DeviceCapability.Accessibility) {
                    val service = requireAccessibilityService()
                    DeviceUiAutomation.scroll(
                        service = service,
                        selector = params.selector(),
                        index = params.intOrNull("index"),
                        direction = params.str("direction") ?: "forward",
                    )
                }

                "/app/ui/input" -> withCapability(DeviceCapability.Accessibility) {
                    val service = requireAccessibilityService()
                    DeviceUiAutomation.input(
                        service = service,
                        text = params.str("text").orEmpty(),
                        index = params.intOrNull("index"),
                        submit = params.bool("submit", false),
                        // The flat `text` key is the string to type here, so only a
                        // nested selector (or the non-colliding keys) may select a node.
                        selector = params.selector(allowFlatText = false),
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
                        region = params.rect("region"),
                        marks = params.bool("marks", false),
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
                        service = DeviceAccessibilityService.running(),
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
                    reason = "处理 ${request.path} 出错：${error.message}",
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

    /**
     * The service, waiting briefly for a rebind before refusing.
     *
     * "已启用但还没连上" is a real and common state (the switch was just flipped,
     * a reboot, an app update), and it is not the same failure as "the user never
     * enabled it": one resolves itself in about a second, the other needs the user
     * in Settings. The old code collapsed both into NO_PERMISSION + "go to
     * Settings", which is why the surrounding text was wrong half the time.
     */
    private fun requireAccessibilityService(): android.accessibilityservice.AccessibilityService =
        DeviceAccessibilityService.awaitRunning(attempts = 2, delayMs = 500) ?: run {
            when (DeviceAccessibilityService.state(context)) {
                DeviceAccessibilityService.State.ENABLED_NOT_CONNECTED -> throw DeviceActionException(
                    DeviceDenial(
                        code = DeviceDenial.NOT_CONNECTED,
                        reason = "无障碍已启用但系统未连上（正在重连）。",
                        hint = "等约 1 秒重试同一次调用，不用改设置。",
                        retryable = true,
                    ),
                )

                DeviceAccessibilityService.State.NOT_ENABLED -> throw DeviceActionException(
                    DeviceDenial(
                        code = DeviceDenial.NO_PERMISSION,
                        reason = "无障碍服务未启用，无法读取或操作屏幕。",
                        hint = "让用户在「设置 → 无障碍」启用 PI 设备桥后重试。",
                    ),
                )

                DeviceAccessibilityService.State.CONNECTED -> throw DeviceActionException(
                    DeviceDenial(
                        code = DeviceDenial.NOT_CONNECTED,
                        reason = "无障碍服务刚断开（被系统重启）。",
                        hint = "请稍等约 1 秒后重试。",
                        retryable = true,
                    ),
                )
            }
        }

    private fun health(): JSONObject = JSONObject().apply {
        put("service", "pi-android-device-bridge")
        put("version", BRIDGE_VERSION)
        put("port", port)
        put("tokenFile", tokenFileLabel)
        put("capabilities", capabilityArray())
        put("accessibilityRunning", DeviceAccessibilityService.isRunning())
        put("accessibilityEnabledInSettings", DeviceAccessibilityService.isEnabledInSettings(context))
        // Three states, one stable name: the model needs to tell "you never turned
        // it on" from "it is coming back up" — the two call for opposite actions.
        put("accessibilityState", DeviceAccessibilityService.stateName(context))
        // Decisive context for an agent: which app is on screen right now. Null
        // (not a guess) when the accessibility service is not connected.
        put(
            "foreground",
            DeviceAccessibilityService.running()?.let { service ->
                val root = service.rootInActiveWindow
                JSONObject()
                    .put("packageName", root?.packageName?.toString() ?: JSONObject.NULL)
                    .put(
                        "class",
                        DeviceUiText.simpleClassName(root?.className).takeIf { it.isNotEmpty() } ?: JSONObject.NULL,
                    )
            } ?: JSONObject.NULL,
        )
        DeviceUiAutomation.lastSnapshotInfo().let { put("uiSnapshot", it) }
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
        put(
            "audit",
            JSONObject()
                .put("path", auditLog.path)
                // Set at bridge start from the write-ahead lines: a `start` with no
                // `result` is the trace of a request that killed the process.
                .put("lastUnpaired", DeviceBridgeController.lastUnpairedReport() ?: JSONObject.NULL),
        )
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

        /**
         * The selector for the UI primitives. Two spellings, one meaning:
         *  - `selector: { text: "发送", resourceId: "btn_send" }` in the body, or
         *  - the flat keys `selectorText` / `desc` / `resourceId` / `packageName` /
         *    `className` / `clickable`, which also work in the query string.
         *
         * The nested form exists because `/app/ui/input` already uses `text` for
         * the string to type; [allowFlatText] turns the flat `text` fallback off
         * there so a typed message can never be mistaken for a node selector.
         */
        fun selector(allowFlatText: Boolean = true): DeviceUiAutomation.Selector {
            val nested = json.optJSONObject("selector")
            fun pick(vararg names: String): String? {
                if (nested != null) {
                    for (name in names) {
                        val candidate = nested.optString(name)
                        if (candidate.isNotEmpty()) return candidate
                    }
                }
                for (name in names) str(name)?.let { return it }
                return null
            }
            return DeviceUiAutomation.Selector(
                text = if (allowFlatText) pick("selectorText", "text") else pick("selectorText"),
                description = pick("desc", "description"),
                resourceId = pick("resourceId"),
                packageName = pick("packageName", "pkg"),
                className = pick("className"),
                clickableOnly = nested?.optBoolean("clickable") == true || bool("clickable", false),
            )
        }

        /** A display-pixel rectangle from `region=[l,t,r,b]`, `x,y,w,h`, or a JSON object. */
        fun rect(name: String): android.graphics.Rect? {
            val raw = value(name) ?: return null
            val numbers = ArrayList<Int>(4)
            when (raw) {
                is JSONArray -> for (i in 0 until minOf(4, raw.length())) numbers.add(raw.optInt(i))
                is JSONObject -> {
                    numbers.add(raw.optInt("x"))
                    numbers.add(raw.optInt("y"))
                    numbers.add(raw.optInt("x") + raw.optInt("width"))
                    numbers.add(raw.optInt("y") + raw.optInt("height"))
                }
                else -> {
                    // A comma-separated string carries the same two shapes; four
                    // numbers are left/top/right/bottom, so `x,y,w,h` must be
                    // spelled as an array to stay unambiguous.
                    for (part in raw.toString().split(',')) {
                        numbers.add(part.trim().toIntOrNull() ?: return null)
                    }
                }
            }
            if (numbers.size != 4) return null
            return android.graphics.Rect(numbers[0], numbers[1], numbers[2], numbers[3])
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
            "POST /app/ui/wait",
            "POST /app/ui/verify",
            "POST /app/ui/tap",
            "POST /app/ui/scroll",
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
