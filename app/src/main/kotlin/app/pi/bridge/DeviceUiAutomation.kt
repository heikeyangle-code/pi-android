package app.pi.bridge

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.min

/**
 * Screen reading and input injection, expressed as plain JSON.
 *
 * Why an index rather than raw coordinates: a model looking at a phone screen has
 * no reliable way to guess a tap target — one pixel off and the tap lands on the
 * wrong row. So [dump] assigns every node a stable-enough index and a *path* of
 * child positions, and [tap] resolves that index against the last snapshot,
 * preferring a real `ACTION_CLICK` on the node (which survives layout shifts and
 * respects the app's own hit testing) and falling back to a coordinate gesture on
 * the node's last known bounds.
 *
 * The snapshot is process-wide and single-user by construction: one bridge server
 * serves one agent session, so a "last dump" is a meaningful cursor.
 */
object DeviceUiAutomation {

    /** A node as seen by the last [dump]. */
    private data class NodeRecord(
        val index: Int,
        val path: List<Int>,
        val bounds: Rect,
        val text: String,
        val description: String,
        val viewId: String?,
        val className: String,
        val clickable: Boolean,
        val longClickable: Boolean,
        val scrollable: Boolean,
        val editable: Boolean,
        val enabled: Boolean,
    )

    @Volatile
    private var lastSnapshot: List<NodeRecord> = emptyList()

    @Volatile
    private var lastPackage: String = ""

    /** Cap on a single dump. A chatty screen has thousands of nodes; a model does not. */
    const val DEFAULT_MAX_NODES = 400
    private const val MAX_NODES_LIMIT = 2000
    private const val DEFAULT_MAX_DEPTH = 32

    private val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }
    private val mainExecutor: Executor = Executor { command -> mainHandler.post(command) }

    // ---------------------------------------------------------------- dump ----

    fun dump(service: AccessibilityService, filter: String?, maxNodes: Int, maxDepth: Int): JSONObject {
        val limit = maxNodes.coerceIn(1, MAX_NODES_LIMIT)
        val depthLimit = maxDepth.coerceIn(1, 64)
        val root = activeRoot(service)
            ?: throw DeviceActionException(
                DeviceDenial(
                    code = DeviceDenial.NOT_FOUND,
                    reason = "当前没有可读取的窗口（屏幕可能处于锁屏或没有任何前台界面）。",
                    hint = "请让用户先解锁屏幕并回到可见界面，然后重试。",
                ),
            )

        val all = ArrayList<NodeRecord>()
        val packageName = root.packageName?.toString().orEmpty()
        collect(root, emptyList(), 0, limit, depthLimit, all)

        val selected = if (filter.isNullOrBlank()) {
            all
        } else {
            applyFilter(all, filter.trim())
        }
        lastSnapshot = selected
        lastPackage = packageName

        val metrics = service.resources.displayMetrics
        val nodes = JSONArray()
        val lines = StringBuilder()
        for (record in selected) {
            nodes.put(recordToJson(record))
            lines.append('[').append(record.index).append(']')
                .append(' ').append("  ".repeat(record.path.size))
                .append(render(record))
                .append('\n')
        }

        return JSONObject().apply {
            put("packageName", packageName)
            put("nodeCount", selected.size)
            put("totalNodeCount", all.size)
            put("filtered", !filter.isNullOrBlank())
            put("truncated", all.size >= limit)
            put("screen", JSONObject().put("width", metrics.widthPixels).put("height", metrics.heightPixels))
            put("nodes", nodes)
            put("text", lines.toString().trimEnd('\n'))
        }
    }

    private fun collect(
        node: AccessibilityNodeInfo,
        path: List<Int>,
        depth: Int,
        limit: Int,
        depthLimit: Int,
        out: MutableList<NodeRecord>,
    ) {
        if (out.size >= limit || depth > depthLimit) return
        val rect = Rect()
        node.getBoundsInScreen(rect)
        out.add(
            NodeRecord(
                index = out.size,
                path = path,
                bounds = rect,
                text = DeviceUiText.clip(node.text),
                description = DeviceUiText.clip(node.contentDescription),
                viewId = node.viewIdResourceName,
                className = DeviceUiText.simpleClassName(node.className),
                clickable = node.isClickable,
                longClickable = node.isLongClickable,
                scrollable = node.isScrollable,
                editable = node.isEditable,
                enabled = node.isEnabled,
            ),
        )
        val children = node.childCount
        for (i in 0 until children) {
            val child = node.getChild(i) ?: continue
            collect(child, path + i, depth + 1, limit, depthLimit, out)
        }
    }

    /**
     * Keep every node whose own label matches, plus the whole ancestor chain so the
     * result still reads as a tree rather than a bag of hits.
     */
    private fun applyFilter(all: List<NodeRecord>, needle: String): List<NodeRecord> {
        val lower = needle.lowercase()
        val keepPaths = HashSet<List<Int>>()
        for (record in all) {
            val haystack = buildString {
                append(record.text).append(' ').append(record.description).append(' ')
                append(record.viewId.orEmpty()).append(' ').append(record.className)
            }.lowercase()
            if (!haystack.contains(lower)) continue
            var path: List<Int> = record.path
            while (true) {
                keepPaths.add(path)
                if (path.isEmpty()) break
                path = path.subList(0, path.size - 1)
            }
        }
        val filtered = all.filter { keepPaths.contains(it.path) }
        // Re-number so the indices in `text` address the filtered array.
        return filtered.mapIndexed { index, record -> record.copy(index = index) }
    }

    private fun recordToJson(record: NodeRecord): JSONObject = JSONObject().apply {
        put("index", record.index)
        put("depth", record.path.size)
        put("path", JSONArray(record.path))
        put("class", record.className)
        if (record.text.isNotEmpty()) put("text", record.text)
        if (record.description.isNotEmpty()) put("description", record.description)
        if (record.viewId != null) put("viewId", record.viewId)
        put("bounds", JSONArray(listOf(record.bounds.left, record.bounds.top, record.bounds.right, record.bounds.bottom)))
        if (record.clickable) put("clickable", true)
        if (record.longClickable) put("longClickable", true)
        if (record.scrollable) put("scrollable", true)
        if (record.editable) put("editable", true)
        if (!record.enabled) put("enabled", false)
    }

    /** The one-line rendering a model actually reads. */
    private fun render(record: NodeRecord): String = buildString {
        append(record.className.ifEmpty { "Node" })
        val label = when {
            record.text.isNotEmpty() -> "\"${record.text}\""
            record.description.isNotEmpty() -> "desc=\"${record.description}\""
            else -> ""
        }
        if (label.isNotEmpty()) append(' ').append(label)
        record.viewId?.let { append(" id=").append(it) }
        val flags = buildList {
            if (record.clickable) add("clickable")
            if (record.longClickable) add("longClickable")
            if (record.scrollable) add("scrollable")
            if (record.editable) add("editable")
            if (!record.enabled) add("disabled")
        }
        if (flags.isNotEmpty()) append(' ').append(flags.joinToString(","))
        append(" @").append(record.bounds.toShortString())
    }

    // ------------------------------------------------------------- tapping ----

    suspend fun tap(
        service: AccessibilityService,
        index: Int?,
        x: Int?,
        y: Int?,
        longPress: Boolean,
    ): JSONObject {
        if (index != null) {
            val record = lastSnapshot.firstOrNull { it.index == index }
                ?: throw DeviceActionException(
                    DeviceDenial(
                        code = DeviceDenial.NOT_FOUND,
                        reason = "找不到编号 $index 的控件：屏幕内容已经变化。",
                        hint = "请重新调用 android_ui_dump 获取最新的控件编号，再点按。",
                    ),
                )
            val node = resolve(service, record)
            if (node != null) {
                val clickable = if (longPress) {
                    node.isLongClickable || nearestLongClickable(node) != null
                } else {
                    node.isClickable || nearestClickable(node) != null
                }
                if (clickable) {
                    val target = if (longPress) {
                        nearestLongClickable(node) ?: node
                    } else {
                        nearestClickable(node) ?: node
                    }
                    val action = if (longPress) {
                        AccessibilityNodeInfo.ACTION_LONG_CLICK
                    } else {
                        AccessibilityNodeInfo.ACTION_CLICK
                    }
                    if (target.performAction(action)) {
                        return JSONObject().apply {
                            put("mode", if (longPress) "action_long_click" else "action_click")
                            put("index", index)
                            put("target", render(record))
                        }
                    }
                }
            }
            // The node has no click action (or is stale): fall back to a gesture on
            // the last known bounds, which is what a human finger would do.
            if (record.bounds.isEmpty) {
                throw DeviceActionException(
                    DeviceDenial(
                        code = DeviceDenial.UNSUPPORTED,
                        reason = "编号 $index 的控件不可点击，也没有可见区域。",
                        hint = "请重新 dump 屏幕，改点一个带 clickable 标记的控件。",
                    ),
                )
            }
            val cx = record.bounds.centerX()
            val cy = record.bounds.centerY()
            dispatchTap(service, cx, cy, longPress)
            return JSONObject().apply {
                put("mode", if (longPress) "gesture_long_press" else "gesture_tap")
                put("index", index)
                put("x", cx)
                put("y", cy)
                put("target", render(record))
            }
        }

        if (x == null || y == null) {
            throw DeviceActionException(
                DeviceDenial(
                    code = DeviceDenial.BAD_REQUEST,
                    reason = "点按需要提供 index，或者同时提供 x 与 y。",
                ),
            )
        }
        dispatchTap(service, x, y, longPress)
        return JSONObject().apply {
            put("mode", if (longPress) "gesture_long_press" else "gesture_tap")
            put("x", x)
            put("y", y)
        }
    }

    private suspend fun dispatchTap(service: AccessibilityService, x: Int, y: Int, longPress: Boolean) {
        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
            // A one-pixel travel keeps the stroke non-degenerate; a move-only path
            // is rejected by the gesture API on some builds.
            lineTo(x + 1f, y + 1f)
        }
        val duration = if (longPress) 600L else 60L
        dispatch(service, path, duration, "点按")
    }

    // ------------------------------------------------------------- swiping ----

    suspend fun swipe(
        service: AccessibilityService,
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        durationMs: Int,
    ): JSONObject {
        val duration = durationMs.coerceIn(40, 6000).toLong()
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        dispatch(service, path, duration, "滑动")
        return JSONObject().apply {
            put("from", JSONArray(listOf(x1, y1)))
            put("to", JSONArray(listOf(x2, y2)))
            put("durationMs", duration)
        }
    }

    private suspend fun dispatch(service: AccessibilityService, path: Path, durationMs: Long, what: String) {
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
            .build()
        val ok = suspendCancellableCoroutine { continuation ->
            val dispatched = service.dispatchGesture(
                gesture,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(description: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(true)
                    }

                    override fun onCancelled(description: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(false)
                    }
                },
                mainHandler,
            )
            if (!dispatched && continuation.isActive) continuation.resume(false)
        }
        if (!ok) {
            throw DeviceActionException(
                DeviceDenial(
                    code = DeviceDenial.UNSUPPORTED,
                    reason = "$what 手势没有下发成功：无障碍服务可能被系统限制，或另一个手势正在进行。",
                    hint = "请稍后重试；若持续失败，请让用户关闭再打开「设置 → 无障碍 → pi 设备桥」。",
                ),
            )
        }
    }

    // -------------------------------------------------------------- text ------

    fun input(service: AccessibilityService, text: String, index: Int?, submit: Boolean): JSONObject {
        val target: AccessibilityNodeInfo? = if (index != null) {
            val record = lastSnapshot.firstOrNull { it.index == index }
                ?: throw DeviceActionException(
                    DeviceDenial(
                        code = DeviceDenial.NOT_FOUND,
                        reason = "找不到编号 $index 的输入框：屏幕内容已经变化。",
                        hint = "请重新调用 android_ui_dump，再向最新的输入框写入文本。",
                    ),
                )
            resolve(service, record)
        } else {
            activeRoot(service)?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        }

        val node = target ?: throw DeviceActionException(
            DeviceDenial(
                code = DeviceDenial.NOT_FOUND,
                reason = "没有找到处于焦点的输入框，无法写入文本。",
                hint = "请先用 android_ui_dump 找到 EditText 并点按它，再调用 android_input 并传入它的 index。",
            ),
        )

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val written = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (!written) {
            // Last resort: typing through the node's own edit action is unavailable,
            // so report precisely rather than pretending the text landed.
            throw DeviceActionException(
                DeviceDenial(
                    code = DeviceDenial.UNSUPPORTED,
                    reason = "目标输入框拒绝了直接写入文本（可能是 WebView 或自绘控件）。",
                    hint = "可以改用 android_shell 执行 input text（需要 Shell 能力），或让用户手动输入。",
                ),
            )
        }

        var submitted = false
        if (submit) {
            // There is no public int ACTION_IME_ENTER; the action lives on the
            // AccessibilityAction holder and is performed through its id.
            submitted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            } else {
                false
            }
        }

        return JSONObject().apply {
            put("chars", text.length)
            put("target", DeviceUiText.clip(node.text, 40))
            put("submitted", submitted)
            if (submit && !submitted) {
                put(
                    "submitHint",
                    "已写入文本，但没有可用的「回车」动作；请点按发送按钮。",
                )
            }
        }
    }

    // --------------------------------------------------------------- keys -----

    /** Keys the accessibility service can actually perform without an IME. */
    private val globalActions: Map<String, Int> = mapOf(
        "back" to AccessibilityService.GLOBAL_ACTION_BACK,
        "home" to AccessibilityService.GLOBAL_ACTION_HOME,
        "recents" to AccessibilityService.GLOBAL_ACTION_RECENTS,
        "notifications" to AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS,
        "quicksettings" to AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS,
    )

    fun key(service: AccessibilityService, key: String): JSONObject {
        val normalized = key.trim().lowercase().replace("-", "").replace("_", "")
        val action = globalActions[normalized]
        if (action != null) {
            val ok = service.performGlobalAction(action)
            if (!ok) {
                throw DeviceActionException(
                    DeviceDenial(
                        code = DeviceDenial.UNSUPPORTED,
                        reason = "系统拒绝了全局动作「$key」。",
                        hint = "请让用户确认无障碍服务仍然启用，然后重试。",
                    ),
                )
            }
            return JSONObject().apply {
                put("key", normalized)
                put("mode", "global_action")
            }
        }
        throw DeviceActionException(
            DeviceDenial(
                code = DeviceDenial.UNSUPPORTED,
                reason = "无障碍通道不能注入按键「$key」。可用按键：" + globalActions.keys.sorted().joinToString("、") + "。",
                hint = "如需 enter / delete / 方向键等原始按键，请改用 android_shell 执行 input keyevent（需要 Shell 能力），或让用户手动操作。",
            ),
        )
    }

    // ---------------------------------------------------------- screenshot ----

    fun screenshot(
        service: AccessibilityService,
        format: String,
        maxDimension: Int,
        quality: Int,
    ): JSONObject {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            throw DeviceActionException(
                DeviceDenial(
                    code = DeviceDenial.UNSUPPORTED,
                    reason = "无障碍截图需要 Android 11（API 30）及以上，本机是 Android ${Build.VERSION.RELEASE}。",
                    hint = "可以让用户用系统截图，或改用 android_shell 的 screencap（需要 Shell 能力）。",
                ),
            )
        }
        val raw = takeScreenshotBlocking(service)
        val scaled = scaleDown(raw, maxDimension.coerceIn(240, 4096))
        if (scaled !== raw) raw.recycle()

        val wantsPng = format.equals("png", ignoreCase = true)
        val out = ByteArrayOutputStream()
        val ok = scaled.compress(
            if (wantsPng) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG,
            quality.coerceIn(20, 100),
            out,
        )
        val bytes = out.toByteArray()
        val width = scaled.width
        val height = scaled.height
        scaled.recycle()
        if (!ok || bytes.isEmpty()) {
            throw DeviceActionException(
                DeviceDenial(
                    code = DeviceDenial.ERROR,
                    reason = "截图编码失败。",
                ),
            )
        }
        return JSONObject().apply {
            put("mimeType", if (wantsPng) "image/png" else "image/jpeg")
            put("base64", Base64.encodeToString(bytes, Base64.NO_WRAP))
            put("width", width)
            put("height", height)
            put("bytes", bytes.size)
        }
    }

    /**
     * `takeScreenshot` is asynchronous and its callback arrives on the main
     * thread, so this blocks the calling (bridge) thread on a latch rather than
     * threading a coroutine through every caller.
     */
    private fun takeScreenshotBlocking(service: AccessibilityService): Bitmap {
        val latch = java.util.concurrent.CountDownLatch(1)
        // Kotlin forbids @Volatile on locals, and this callback runs on the main
        // thread while the bridge thread waits, so the handoff uses atomics.
        val captured = java.util.concurrent.atomic.AtomicReference<Bitmap?>(null)
        val failure = java.util.concurrent.atomic.AtomicInteger(0)
        try {
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        runCatching {
                            val hardware = result.hardwareBuffer
                            val wrapped = Bitmap.wrapHardwareBuffer(hardware, result.colorSpace)
                            // The hardware bitmap is GPU-resident and cannot be
                            // scaled or encoded directly; copy it into CPU memory.
                            val copy = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                            wrapped?.recycle()
                            hardware.close()
                            captured.set(copy)
                        }.onFailure { failure.set(-1) }
                        latch.countDown()
                    }

                    override fun onFailure(errorCode: Int) {
                        failure.set(errorCode)
                        latch.countDown()
                    }
                },
            )
        } catch (error: IllegalStateException) {
            throw DeviceActionException(
                DeviceDenial(
                    code = DeviceDenial.NO_PERMISSION,
                    reason = "无障碍服务当前不可用，截图没有开始。",
                    hint = "请让用户重新开启「设置 → 无障碍 → pi 设备桥」。",
                ),
            )
        }
        if (!latch.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
            throw DeviceActionException(
                DeviceDenial(
                    code = DeviceDenial.ERROR,
                    reason = "截图超时（10 秒）。",
                ),
            )
        }
        val bitmap = captured.get()
        if (bitmap != null) return bitmap
        throw DeviceActionException(screenshotFailure(failure.get()))
    }

    private fun screenshotFailure(code: Int): DeviceDenial {
        val message = when (code) {
            AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR -> "系统内部错误导致截图失败。"
            AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "无障碍服务没有截图权限。"
            AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "截图过于频繁，系统要求间隔约 1 秒。"
            AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "指定的显示器无效。"
            else -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                    code == AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW
                ) {
                    "当前界面标记为安全窗口（例如支付、密码界面），系统禁止截图。"
                } else {
                    "截图失败（错误码 $code）。"
                }
            }
        }
        return DeviceDenial(
            code = DeviceDenial.UNSUPPORTED,
            reason = message,
            hint = "如果是安全窗口，请让用户退出该界面后重试；如果是频率限制，稍等一秒再截。",
        )
    }

    private fun scaleDown(source: Bitmap, maxDimension: Int): Bitmap {
        val longest = max(source.width, source.height)
        if (longest <= maxDimension) return source
        val ratio = maxDimension.toFloat() / longest.toFloat()
        val width = max(1, (source.width * ratio).toInt())
        val height = max(1, (source.height * ratio).toInt())
        return Bitmap.createScaledBitmap(source, width, height, true)
    }

    // ------------------------------------------------------------ helpers -----

    private fun activeRoot(service: AccessibilityService): AccessibilityNodeInfo? {
        service.rootInActiveWindow?.let { return it }
        // Multi-window: prefer the window that currently has input focus.
        val windows = service.windows ?: return null
        for (window in windows) {
            val root = window.root ?: continue
            if (root.packageName != null) return root
        }
        return null
    }

    /**
     * Re-walk the stored child-index path. The tree may have changed since the
     * dump; a null result is normal and callers fall back to coordinates.
     */
    private fun resolve(service: AccessibilityService, record: NodeRecord): AccessibilityNodeInfo? {
        var node = activeRoot(service) ?: return null
        for (step in record.path) {
            if (step >= node.childCount) return null
            node = node.getChild(step) ?: return null
        }
        return node
    }

    private fun nearestClickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var hops = 0
        while (current != null && hops < 6) {
            if (current.isClickable && current.isEnabled) return current
            current = current.parent
            hops++
        }
        return null
    }

    private fun nearestLongClickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var hops = 0
        while (current != null && hops < 6) {
            if (current.isLongClickable && current.isEnabled) return current
            current = current.parent
            hops++
        }
        return null
    }

    /** Guard against absurd quality values before they reach the encoder. */
    fun clampQuality(quality: Int): Int = min(100, max(20, quality))
}

/** A bridge failure that already knows how to describe itself to the model. */
class DeviceActionException(val denial: DeviceDenial) : Exception(denial.toMessage())
