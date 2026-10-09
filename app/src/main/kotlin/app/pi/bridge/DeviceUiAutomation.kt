package app.pi.bridge

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The one pixel space every payload from this file is expressed in: the
 * *unscaled* grid of the default display, which is what
 * `AccessibilityNodeInfo.getBoundsInScreen` returns and what a gesture path
 * takes. Top-level (not an object member) so the small nested `ActionContext`
 * can use it too.
 */
private const val SPACE = "display"

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
 *
 * ### One coordinate space
 *
 * There is exactly one pixel space in every payload this object produces:
 * **`display`** — the *unscaled* pixel grid of the default display, which is what
 * `AccessibilityNodeInfo.getBoundsInScreen` returns, what a gesture path takes,
 * and what `android_screenshot`'s `region` accepts. A screenshot that was scaled
 * or cropped reports `sourceWidth`/`sourceHeight`/`scale`/`region`, so an image
 * pixel can always be mapped back: `display = region.topLeft + image / scale`.
 * Before this, a 1280-wide screenshot of a 1440-wide screen had no field saying
 * so, and an agent that tapped at an image pixel tapped ~11% off.
 */
object DeviceUiAutomation {

    /**
     * A node as seen by the last [dump].
     *
     * Public (not `private`) only because [Selector.matches] is a public member that
     * takes one; every other use of it stays inside this object.
     */
    data class NodeRecord(
        val index: Int,
        val path: List<Int>,
        val bounds: Rect,
        val text: String,
        val description: String,
        val viewId: String?,
        val className: String,
        val packageName: String,
        val clickable: Boolean,
        val longClickable: Boolean,
        val scrollable: Boolean,
        val editable: Boolean,
        val enabled: Boolean,
    ) {
        /** Identity for diffing: the child-index path is stable across redraws. */
        val key: String get() = path.joinToString(".")

        /** Everything a diff has to notice; bounds included, because a moved
         *  button is a change an agent needs to see. */
        fun contentKey(): String = buildString {
            append(text).append('|').append(description).append('|').append(viewId.orEmpty())
            append('|').append(className).append('|').append(packageName).append('|')
            append(bounds.left).append(',').append(bounds.top).append(',')
            append(bounds.right).append(',').append(bounds.bottom).append('|')
            append(clickable).append(longClickable).append(scrollable).append(editable).append(enabled)
        }
    }

    /**
     * A device-side selector. Resolving text/id on the device removes the
     * `dump → read index → tap` round trip *and* the staleness window between the
     * two: the node is matched against the tree as it is at action time.
     */
    data class Selector(
        val text: String? = null,
        val description: String? = null,
        val resourceId: String? = null,
        val packageName: String? = null,
        val className: String? = null,
        val clickableOnly: Boolean = false,
    ) {
        val isEmpty: Boolean
            get() = text == null && description == null && resourceId == null &&
                packageName == null && className == null && !clickableOnly

        fun matches(record: NodeRecord): Boolean {
            if (clickableOnly && !record.clickable) return false
            text?.let { if (!record.text.contains(it, ignoreCase = true)) return false }
            description?.let { if (!record.description.contains(it, ignoreCase = true)) return false }
            resourceId?.let { id ->
                val view = record.viewId ?: return false
                if (!view.contains(id, ignoreCase = true)) return false
            }
            packageName?.let { pkg ->
                if (record.packageName != pkg && !record.packageName.endsWith(".$pkg")) return false
            }
            className?.let { wanted ->
                if (!record.className.contains(wanted, ignoreCase = true)) return false
            }
            return true
        }

        fun describe(): String = buildList {
            text?.let { add("text~\"$it\"") }
            description?.let { add("desc~\"$it\"") }
            resourceId?.let { add("id~$it") }
            packageName?.let { add("pkg=$it") }
            className?.let { add("class~$it") }
            if (clickableOnly) add("clickable")
        }.joinToString(" & ").ifEmpty { "（空选择器）" }
    }

    @Volatile
    private var lastSnapshot: List<NodeRecord> = emptyList()

    @Volatile
    private var lastPackage: String = ""

    @Volatile
    private var snapshotId: Long = 0

    /** Cap on a single dump. A chatty screen has thousands of nodes; a model does not. */
    const val DEFAULT_MAX_NODES = 400
    private const val MAX_NODES_LIMIT = 2000
    private const val DEFAULT_MAX_DEPTH = 32

    /**
     * A gesture that has neither completed nor been cancelled within this long is
     * treated as dead. 2 s is longer than a tap or long-press needs, and short
     * enough that a leaked busy flag blocks the next call for one retry, not
     * forever.
     */
    private const val GESTURE_HARD_TIMEOUT_MS = 2_000L

    /** One gesture at a time: the platform serialises them anyway. */
    private val gestureBusy = AtomicBoolean(false)

    private val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }
    private val mainExecutor: Executor = Executor { command -> mainHandler.post(command) }

    private const val FOREGROUND_POLL_MS = 500L

    // ---------------------------------------------------------------- dump ----

    fun dump(
        service: AccessibilityService,
        filter: String?,
        maxNodes: Int,
        maxDepth: Int,
        diff: Boolean = false,
    ): JSONObject {
        val limit = maxNodes.coerceIn(1, MAX_NODES_LIMIT)
        val depthLimit = maxDepth.coerceIn(1, 64)
        val root = activeRoot(service)
            ?: throw DeviceActionException(
                DeviceDenial(
                    code = DeviceDenial.NOT_FOUND,
                    reason = "没有可读窗口（可能锁屏或没有前台界面）。",
                    hint = "让用户解锁并回到可见界面后重试。",
                ),
            )

        val all = ArrayList<NodeRecord>()
        val packageName = root.packageName?.toString().orEmpty()
        collect(root, packageName, emptyList(), 0, limit, depthLimit, all)

        val selected = if (filter.isNullOrBlank()) {
            all
        } else {
            applyFilter(all, filter.trim())
        }
        val previous = lastSnapshot
        lastSnapshot = selected
        lastPackage = packageName
        snapshotId += 1

        val metrics = service.resources.displayMetrics
        val nodes = JSONArray()
        val lines = StringBuilder()

        if (diff) {
            val before = previous.associateBy { it.key }
            val after = selected.associateBy { it.key }
            val added = ArrayList<NodeRecord>()
            val changed = ArrayList<NodeRecord>()
            for (record in selected) {
                val old = before[record.key]
                if (old == null) {
                    added.add(record)
                } else if (old.contentKey() != record.contentKey()) {
                    changed.add(record)
                }
            }
            val removed = previous.filter { !after.containsKey(it.key) }
            for (record in added + changed) {
                nodes.put(recordToJson(record))
                lines.append(if (before[record.key] == null) "+ " else "~ ")
                    .append('[').append(record.index).append("] ")
                    .append(render(record)).append('\n')
            }
            for (record in removed) {
                lines.append("- [").append(record.index).append("] ")
                    .append(render(record)).append("（已消失）").append('\n')
            }
            return basePayload(packageName, metrics, service).apply {
                put("nodeCount", selected.size)
                put("totalNodeCount", all.size)
                put("filtered", !filter.isNullOrBlank())
                put("truncated", all.size >= limit)
                put("diff", true)
                put("added", added.size)
                put("changed", changed.size)
                put("removed", removed.size)
                put("unchanged", selected.size - added.size - changed.size)
                put("nodes", nodes)
                put(
                    "text",
                    if (lines.isEmpty()) {
                        "（无变化：控件树与上一次 dump 完全一致）"
                    } else {
                        lines.toString().trimEnd('\n')
                    },
                )
            }
        }

        for (record in selected) {
            nodes.put(recordToJson(record))
            lines.append('[').append(record.index).append(']')
                .append(' ').append("  ".repeat(record.path.size))
                .append(render(record))
                .append('\n')
        }

        return basePayload(packageName, metrics, service).apply {
            put("nodeCount", selected.size)
            put("totalNodeCount", all.size)
            put("filtered", !filter.isNullOrBlank())
            put("truncated", all.size >= limit)
            put("nodes", nodes)
            put("text", lines.toString().trimEnd('\n'))
        }
    }

    /** The header fields every dump carries, including the space it is in. */
    private fun basePayload(
        packageName: String,
        metrics: android.util.DisplayMetrics,
        service: AccessibilityService,
    ): JSONObject = JSONObject().apply {
        put("packageName", packageName)
        put("coordinateSpace", SPACE)
        put("snapshotId", snapshotId)
        put("screen", JSONObject().put("width", metrics.widthPixels).put("height", metrics.heightPixels))
        put(
            "display",
            JSONObject()
                .put("width", metrics.widthPixels)
                .put("height", metrics.heightPixels)
                .put("density", metrics.density.toDouble())
                .put("densityDpi", metrics.densityDpi)
                .put("rotation", rotationOf(service)),
        )
        put("hint", "所有 bounds / x / y / region 都在 coordinateSpace=display 的像素里；截图若缩放过，按其 scale 换算。")
    }

    private fun rotationOf(service: AccessibilityService): Int = runCatching {
        val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            service.display?.rotation
        } else {
            @Suppress("DEPRECATION")
            (service.getSystemService(android.content.Context.WINDOW_SERVICE) as? android.view.WindowManager)
                ?.defaultDisplay?.rotation
        }
        rotation ?: 0
    }.getOrDefault(0)

    private fun collect(
        node: AccessibilityNodeInfo,
        packageName: String,
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
                packageName = node.packageName?.toString() ?: packageName,
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
            collect(child, packageName, path + i, depth + 1, limit, depthLimit, out)
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
        put("packageName", record.packageName)
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

    // ------------------------------------------------------------ selector ----

    /** Every node in the live tree that matches [selector], capped at [limit]. */
    private fun liveMatches(
        service: AccessibilityService,
        selector: Selector,
        limit: Int = 200,
        maxDepth: Int = 64,
    ): List<AccessibilityNodeInfo> {
        val root = activeRoot(service) ?: return emptyList()
        val out = ArrayList<AccessibilityNodeInfo>()
        walk(root, selector, 0, maxDepth, limit, out)
        return out
    }

    private fun walk(
        node: AccessibilityNodeInfo,
        selector: Selector,
        depth: Int,
        depthLimit: Int,
        limit: Int,
        out: MutableList<AccessibilityNodeInfo>,
    ) {
        if (out.size >= limit || depth > depthLimit) return
        val rect = Rect().also { node.getBoundsInScreen(it) }
        val record = NodeRecord(
            index = -1,
            path = emptyList(),
            bounds = rect,
            text = DeviceUiText.clip(node.text),
            description = DeviceUiText.clip(node.contentDescription),
            viewId = node.viewIdResourceName,
            className = DeviceUiText.simpleClassName(node.className),
            packageName = node.packageName?.toString().orEmpty(),
            clickable = node.isClickable,
            longClickable = node.isLongClickable,
            scrollable = node.isScrollable,
            editable = node.isEditable,
            enabled = node.isEnabled,
        )
        if (selector.matches(record)) out.add(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            walk(child, selector, depth + 1, depthLimit, limit, out)
        }
    }

    /** The best match for an action: smallest matching bounds (the most specific node). */
    private fun bestMatch(service: AccessibilityService, selector: Selector): AccessibilityNodeInfo? {
        val matches = liveMatches(service, selector)
        if (matches.isEmpty()) return null
        return matches.minByOrNull { node ->
            val rect = Rect().also { node.getBoundsInScreen(it) }
            rect.width().toLong() * rect.height().toLong()
        }
    }

    /** Describe a live node the way a dump would, for selector-shaped replies. */
    private fun nodeJson(node: AccessibilityNodeInfo): JSONObject {
        val rect = Rect().also { node.getBoundsInScreen(it) }
        return JSONObject().apply {
            put("packageName", node.packageName?.toString().orEmpty())
            put("class", DeviceUiText.simpleClassName(node.className))
            DeviceUiText.clip(node.text).takeIf { it.isNotEmpty() }?.let { put("text", it) }
            DeviceUiText.clip(node.contentDescription).takeIf { it.isNotEmpty() }?.let { put("description", it) }
            node.viewIdResourceName?.let { put("viewId", it) }
            put("bounds", JSONArray(listOf(rect.left, rect.top, rect.right, rect.bottom)))
            put("coordinateSpace", SPACE)
            if (node.isClickable) put("clickable", true)
            if (node.isScrollable) put("scrollable", true)
            if (node.isEditable) put("editable", true)
        }
    }

    private fun indexOfBounds(bounds: Rect): Int? =
        lastSnapshot.firstOrNull { it.bounds == bounds }?.index

    // -------------------------------------------------------------- waiting ----

    /**
     * Wait until [selector] matches (or, with [requireGone], until it no longer
     * does), or the deadline passes.
     *
     * Event-driven first: the accessibility service bumps
     * [DeviceAccessibilitySignals] on every window-content change, so the common
     * case (the app redraws in ~40 ms) returns immediately instead of on the next
     * poll tick. The 200 ms tick stays as the fallback for the event a given ROM
     * decides not to send.
     */
    fun wait(
        service: AccessibilityService,
        selector: Selector,
        timeoutMs: Int,
        requireGone: Boolean,
    ): JSONObject {
        if (selector.isEmpty) {
            throw DeviceActionException(
                DeviceDenial(
                    DeviceDenial.BAD_REQUEST,
                    "至少给一个选择器：text/desc/resourceId/package。",
                ),
            )
        }
        val timeout = timeoutMs.coerceIn(200, 30_000)
        val started = System.currentTimeMillis()
        val deadline = started + timeout
        var polls = 0
        while (true) {
            val revision = DeviceAccessibilitySignals.current
            val hit = runCatching { liveMatches(service, selector, limit = 1).firstOrNull() }.getOrNull()
            polls += 1
            if (!requireGone && hit != null) {
                return JSONObject().apply {
                    put("found", true)
                    put("selector", selector.describe())
                    put("elapsedMs", System.currentTimeMillis() - started)
                    put("polls", polls)
                    put("node", nodeJson(hit))
                    put("coordinateSpace", SPACE)
                }
            }
            if (requireGone && hit == null) {
                return JSONObject().apply {
                    put("gone", true)
                    put("selector", selector.describe())
                    put("elapsedMs", System.currentTimeMillis() - started)
                    put("polls", polls)
                }
            }
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) break
            DeviceAccessibilitySignals.awaitChange(revision, min(remaining, 200L))
        }
        val waited = System.currentTimeMillis() - started
        val what = if (requireGone) {
            "等待「${selector.describe()}」消失超时（${waited}ms）：它一直还在。"
        } else {
            "等待「${selector.describe()}」出现超时（${waited}ms）：屏幕上没有匹配的控件。"
        }
        throw DeviceActionException(
            DeviceDenial(
                code = DeviceDenial.NOT_FOUND,
                reason = what,
                hint = "先用 android_ui_dump 看屏幕或调大 timeoutMs；弹窗先处理。",
            ),
        )
    }

    // -------------------------------------------------------------- verify ----

    /**
     * What is at `(x, y)`? The self-check an agent needs before and after a
     * coordinate action: a gesture that "succeeded" says nothing about whether the
     * intended control was hit, and the node a model *thinks* is there comes from
     * a dump that may be seconds old.
     *
     * The hit test is a tree walk for the *deepest* node containing the point,
     * which mirrors the platform's dispatch order closely enough for a sanity
     * check (overlapping windows are the one case it cannot order).
     */
    fun verify(service: AccessibilityService, x: Int, y: Int): JSONObject {
        val root = activeRoot(service)
            ?: throw DeviceActionException(
                DeviceDenial(
                    DeviceDenial.NOT_FOUND,
                    "没有可读窗口，无法验证坐标 ($x, $y)。",
                    hint = "让用户解锁并回到可见界面。",
                ),
            )
        val all = ArrayList<NodeRecord>()
        collect(root, root.packageName?.toString().orEmpty(), emptyList(), 0, MAX_NODES_LIMIT, 64, all)
        val hit = all.filter { it.bounds.contains(x, y) }.maxByOrNull { it.path.size }
        return JSONObject().apply {
            put("x", x)
            put("y", y)
            put("coordinateSpace", SPACE)
            put("packageName", hit?.packageName ?: root.packageName?.toString().orEmpty())
            put("hit", hit != null)
            if (hit != null) {
                put("node", recordToJson(hit))
                put("index", indexOfBounds(hit.bounds) ?: JSONObject.NULL)
                put("clickableChain", clickableChainJson(service, hit))
            }
        }
    }

    /**
     * Does the point actually lead to a click? A node may be non-clickable while
     * an ancestor handles the tap — which is why [tap] walks up. Reporting that
     * chain is what makes a coordinate action verifiable before it is sent.
     */
    private fun clickableChainJson(service: AccessibilityService, record: NodeRecord): JSONArray {
        val out = JSONArray()
        var current: AccessibilityNodeInfo? = resolve(service, record)
        var hops = 0
        while (current != null && hops < 6) {
            if (current.isClickable && current.isEnabled) {
                out.put(nodeJson(current))
                break
            }
            current = current.parent
            hops++
        }
        return out
    }

    // ------------------------------------------------------------- tapping ----

    suspend fun tap(
        service: AccessibilityService,
        index: Int?,
        x: Int?,
        y: Int?,
        longPress: Boolean,
        selector: Selector? = null,
    ): JSONObject {
        val action = beginAction(service)

        if (selector != null && !selector.isEmpty && index == null) {
            val node = bestMatch(service, selector)
                ?: throw DeviceActionException(
                    DeviceDenial(
                        code = DeviceDenial.NOT_FOUND,
                        reason = "按选择器找不到可点控件：${selector.describe()}。",
                        hint = "先用 android_ui_dump 看屏幕，或用 waitFor 等它出现。",
                    ),
                )
            // 记下目标身份，动作完成后由 ActionContext 复核它是否还在——
            // 「点了且手势成功」不等于「点中了会导航的东西」。
            action.markTarget(selector = selector)
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            val target = if (longPress) nearestLongClickable(node) ?: node else nearestClickable(node) ?: node
            val performed = target.performAction(
                if (longPress) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK,
            )
            if (performed) {
                return action.finish(
                    JSONObject().apply {
                        put("mode", if (longPress) "action_long_click" else "action_click")
                        put("selector", selector.describe())
                        put("target", nodeJson(node))
                    },
                )
            }
            if (bounds.isEmpty) {
                throw DeviceActionException(
                    DeviceDenial(
                        DeviceDenial.UNSUPPORTED,
                        "命中的控件不可点击且无可见区域：${selector.describe()}。",
                        hint = "重新 dump 后改点带 clickable 的控件，或直接给 x/y。",
                    ),
                )
            }
            dispatchTap(service, bounds.centerX(), bounds.centerY(), longPress)
            return action.finish(
                JSONObject().apply {
                    put("mode", if (longPress) "gesture_long_press" else "gesture_tap")
                    put("selector", selector.describe())
                    put("x", bounds.centerX())
                    put("y", bounds.centerY())
                    put("target", nodeJson(node))
                },
            )
        }

        if (index != null) {
            val record = lastSnapshot.firstOrNull { it.index == index }
                ?: throw DeviceActionException(
                    DeviceDenial(
                        code = DeviceDenial.NOT_FOUND,
                        reason = "找不到编号 $index 的控件：屏幕已变化。",
                        hint = "重新 dump 取新编号，或给 text/desc/resourceId。",
                    ),
                )
            action.markTarget(path = record.path)
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
                    val performed = target.performAction(
                        if (longPress) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK,
                    )
                    if (performed) {
                        return action.finish(
                            JSONObject().apply {
                                put("mode", if (longPress) "action_long_click" else "action_click")
                                put("index", index)
                                put("target", render(record))
                            },
                        )
                    }
                }
            }
            // The node has no click action (or is stale): fall back to a gesture on
            // the last known bounds, which is what a human finger would do.
            if (record.bounds.isEmpty) {
                throw DeviceActionException(
                    DeviceDenial(
                        code = DeviceDenial.UNSUPPORTED,
                        reason = "编号 $index 不可点击且无可见区域。",
                        hint = "重新 dump，改点带 clickable 的控件。",
                    ),
                )
            }
            val cx = record.bounds.centerX()
            val cy = record.bounds.centerY()
            dispatchTap(service, cx, cy, longPress)
            return action.finish(
                JSONObject().apply {
                    put("mode", if (longPress) "gesture_long_press" else "gesture_tap")
                    put("index", index)
                    put("x", cx)
                    put("y", cy)
                    put("target", render(record))
                },
            )
        }

        if (x == null || y == null) {
            throw DeviceActionException(
                DeviceDenial(
                    code = DeviceDenial.BAD_REQUEST,
                    reason = "点按需要 index 或 x+y，也可用选择器。",
                    hint = "推荐用选择器：不怕编号过期。",
                ),
            )
        }
        dispatchTap(service, x, y, longPress)
        return action.finish(
            JSONObject().apply {
                put("mode", if (longPress) "gesture_long_press" else "gesture_tap")
                put("x", x)
                put("y", y)
            },
        )
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

    // ------------------------------------------------------------ scrolling ----

    /**
     * Scroll a *node* instead of swiping a coordinate.
     *
     * `ACTION_SCROLL_FORWARD` reaches the scrollable container the platform
     * considers focused, needs no guessed distance, and cannot be swallowed by
     * system gesture navigation the way an edge swipe can. The coordinate swipe
     * stays for the cases where there is no scrollable node (canvas surfaces).
     */
    suspend fun scroll(
        service: AccessibilityService,
        selector: Selector?,
        index: Int?,
        direction: String,
    ): JSONObject {
        val forward = when (direction.trim().lowercase()) {
            "forward", "down", "next" -> true
            "backward", "up", "previous", "prev" -> false
            else -> throw DeviceActionException(
                DeviceDenial(
                    DeviceDenial.BAD_REQUEST,
                    "方向只能是 forward/backward，收到「$direction」。",
                ),
            )
        }
        val indexed = if (index != null) lastSnapshot.firstOrNull { it.index == index } else null
        val start: AccessibilityNodeInfo? = when {
            selector != null && !selector.isEmpty -> bestMatch(service, selector)
            indexed != null -> resolve(service, indexed)
            else -> service.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        }
        val target = when {
            start == null -> null
            start.isScrollable -> start
            else -> {
                var current: AccessibilityNodeInfo? = start
                var hops = 0
                while (current != null && hops < 6 && !current.isScrollable) {
                    current = current.parent
                    hops++
                }
                current
            }
        }

        if (target != null) {
            val action = beginAction(service)
            if (selector != null && !selector.isEmpty) action.markTarget(selector = selector)
            if (indexed != null) action.markTarget(path = indexed.path)
            if (target.performAction(
                    if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,
                )
            ) {
                return action.finish(
                    JSONObject().apply {
                        put("mode", if (forward) "action_scroll_forward" else "action_scroll_backward")
                        put("direction", if (forward) "forward" else "backward")
                        put("target", nodeJson(target))
                    },
                )
            }
        }

        // 降级：改用坐标滑动。
        //
        // 走到这里有两种情形，两种都不该报错：
        //   1. 找不到 isScrollable 的节点。**这在 MIUI / HyperOS 上是常态，不是异常** ——
        //      实测无障碍设置页 42 个节点、scrollable 为 0（列表是 RecyclerView，只是不上报
        //      那个属性）。旧代码在这里直接抛 UNSUPPORTED，把调用方推到 android_swipe 上，
        //      可「把这一屏滚一下」本来就是同一个意图，没有理由换一个工具。
        //   2. 节点存在但拒绝滚动（到尽头，或正在动画）。
        // 坐标滑动不依赖任何节点属性，是同一件事的另一条路。
        val metrics = service.resources.displayMetrics
        val centreX = metrics.widthPixels / 2
        val near = (metrics.heightPixels * 0.62f).toInt()
        val far = (metrics.heightPixels * 0.32f).toInt()
        val (fromY, toY) = if (forward) near to far else far to near
        val result = swipe(service, centreX, fromY, centreX, toY, SCROLL_GESTURE_MS)
        result.put("mode", "gesture_swipe")
        result.put("direction", if (forward) "forward" else "backward")
        result.put(
            "note",
            if (target == null) {
                "没有找到带 scrollable 的控件，已改用坐标滑动（部分 ROM 不上报该属性）。"
            } else {
                "控件拒绝滚动（可能已到尽头或正在动画），已改用坐标滑动。"
            },
        )
        return result
    }

    /** 降级坐标滑动的时长：够长不会被当成 fling，够短不像慢拖。 */
    private const val SCROLL_GESTURE_MS = 360

    // ------------------------------------------------------------- swiping ----

    suspend fun swipe(
        service: AccessibilityService,
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        durationMs: Int,
    ): JSONObject {
        val action = beginAction(service)
        val duration = durationMs.coerceIn(40, 6000).toLong()
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        dispatch(service, path, duration, "滑动")
        return action.finish(
            JSONObject().apply {
                put("from", JSONArray(listOf(x1, y1)))
                put("to", JSONArray(listOf(x2, y2)))
                put("durationMs", duration)
            },
        )
    }

    /**
     * One gesture, with the three outcomes the platform can produce kept apart:
     * dispatched-and-completed (success), refused because another gesture is in
     * flight, and accepted-but-never-called-back.
     *
     * Every path clears [gestureBusy] — the old code cleared it on `onCompleted`
     * only, and a flag leaked by the other two paths was unrecoverable for the
     * life of the process. A request also retries itself once, because "another
     * gesture was in flight" is a race, not a verdict.
     */
    private suspend fun dispatch(service: AccessibilityService, path: Path, durationMs: Long, what: String) {
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
            .build()
        var attempts = 0
        var lastFailure = "busy"
        while (attempts < 2) {
            if (!gestureBusy.compareAndSet(false, true)) {
                // Someone else is mid-gesture. Give them a moment, then either take
                // the flag or report busy.
                delay(250)
                if (!gestureBusy.compareAndSet(false, true)) {
                    lastFailure = "busy"
                    attempts++
                    continue
                }
            }
            try {
                val outcome = withTimeoutOrNull(durationMs + GESTURE_HARD_TIMEOUT_MS) {
                    dispatchOnce(service, gesture)
                }
                when (outcome) {
                    true -> return
                    null -> lastFailure = "timeout"
                    false -> lastFailure = "busy"
                }
            } finally {
                // Every path: refused dispatch, onCompleted, onCancelled, timeout.
                gestureBusy.set(false)
            }
            attempts++
            if (attempts < 2) delay(120)
        }
        val reason = if (lastFailure == "timeout") {
            "$what 手势在 ${durationMs + GESTURE_HARD_TIMEOUT_MS}ms 内没有回调（既不完成也不取消），已强制复位。"
        } else {
            "$what 手势没有下发：另一个手势正在进行（上一次手势可能还在动画中）。"
        }
        throw DeviceActionException(
            DeviceDenial(
                code = DeviceDenial.BUSY,
                reason = reason,
                hint = "等 1 秒重试；仍失败让用户重开「设置 → 无障碍 → PI 设备桥」。",
                retryable = true,
            ),
        )
    }

    /** One raw dispatch. `true` completed, `false` cancelled or refused. */
    private suspend fun dispatchOnce(
        service: AccessibilityService,
        gesture: GestureDescription,
    ): Boolean = suspendCancellableCoroutine { continuation ->
        val dispatched = try {
            service.dispatchGesture(
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
        } catch (error: IllegalStateException) {
            false
        }
        if (!dispatched && continuation.isActive) continuation.resume(false)
    }

    // -------------------------------------------------------------- text ------

    fun input(
        service: AccessibilityService,
        text: String,
        index: Int?,
        submit: Boolean,
        selector: Selector? = null,
    ): JSONObject {
        val action = beginAction(service)
        if (selector != null && !selector.isEmpty) action.markTarget(selector = selector)
        val indexed = if (index != null) lastSnapshot.firstOrNull { it.index == index } else null
        if (indexed != null) action.markTarget(path = indexed.path)
        val target: AccessibilityNodeInfo? = when {
            selector != null && !selector.isEmpty && index == null -> bestMatch(service, selector)
            index != null -> indexed?.let { resolve(service, it) }
                ?: throw DeviceActionException(
                    DeviceDenial(
                        code = DeviceDenial.NOT_FOUND,
                        reason = "找不到编号 $index 的输入框：屏幕已变化。",
                        hint = "重新 dump 后写入最新输入框，或用 resourceId/text。",
                    ),
                )
            else -> activeRoot(service)?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        }

        val node = target ?: throw DeviceActionException(
            DeviceDenial(
                code = DeviceDenial.NOT_FOUND,
                reason = "没有焦点输入框，无法写入。",
                hint = "先 dump 找到输入框点按，再传 index 或 resourceId。",
            ),
        )

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        // Two mechanisms, in the order that costs the user least:
        //  1. ACTION_SET_TEXT replaces the field's content without touching the
        //     clipboard, but Compose/WebView/custom editors often refuse it (the
        //     review of this layer named exactly that as the reason to consider a
        //     custom IME).
        //  2. clipboard + ACTION_PASTE is the fallback that needs no IME and no
        //     extra permission: paste goes through the view's own
        //     onTextContextMenuItem, which far more editors implement.
        // The cost of (2) is that the user's clipboard is replaced; the reply says
        // so, because a silent clipboard overwrite is the kind of side effect this
        // project refuses to hide.
        var mechanism = "action_set_text"
        var written = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (!written) {
            if (pasteInto(service, node, text)) {
                mechanism = "clipboard_paste"
                written = true
            }
        }
        if (!written) {
            throw DeviceActionException(
                DeviceDenial(
                    code = DeviceDenial.UNSUPPORTED,
                    reason = "输入框拒绝写入和粘贴（可能是自绘或只读控件）。",
                    hint = "改用 android_shell 的 input text（需 Shizuku），或用户手输。",
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
            if (!submitted) {
                // Enter is `input keyevent 66` through the ADB identity; the
                // fallback exists only when that identity is actually available.
                val backend = DeviceShellGuard.active()
                if (backend.id == ShizukuShellBackend.id) {
                    submitted = runCatching {
                        backend.run("input keyevent 66", 10_000).exitCode == 0
                    }.getOrDefault(false)
                    if (submitted) mechanism = "$mechanism+keyevent_enter"
                }
            }
        }

        return action.finish(
            JSONObject().apply {
                put("chars", text.length)
                put("target", DeviceUiText.clip(node.text, 40))
                put("mechanism", mechanism)
                put("submitted", submitted)
                if (mechanism == "clipboard_paste") {
                    put(
                        "clipboardNote",
                        "直接写入被拒绝，已改用「剪贴板 + 粘贴」完成；系统剪贴板里原来的内容被这次写入替换了。",
                    )
                }
                if (submit && !submitted) {
                    put(
                        "submitHint",
                        "已写入文本，但没有可用的「回车」动作；请点按发送按钮。",
                    )
                }
            },
        )
    }

    /**
     * Put [text] on the clipboard and ask [node] to paste it.
     *
     * The clipboard write is posted to the main looper for the same reason
     * `DeviceSystemActions.clipboardSet` does it: the bridge serves requests from a
     * pool thread with no `Looper`, and the platform's clipboard service is not
     * documented as thread-safe there.
     */
    private fun pasteInto(service: AccessibilityService, node: AccessibilityNodeInfo, text: String): Boolean {
        val manager = service.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as? android.content.ClipboardManager ?: return false
        val posted = java.util.concurrent.CountDownLatch(1)
        runCatching {
            mainHandler.post {
                runCatching {
                    manager.setPrimaryClip(android.content.ClipData.newPlainText("pi", text))
                }
                posted.countDown()
            }
        }.onFailure { posted.countDown() }
        runCatching { posted.await(2, java.util.concurrent.TimeUnit.SECONDS) }
        return runCatching { node.performAction(AccessibilityNodeInfo.ACTION_PASTE) }.getOrDefault(false)
    }

    // --------------------------------------------------------------- keys -----

    /** Keys the accessibility service can actually perform without an IME. */
    private val globalActions: Map<String, Int> = buildMap {
        put("back", AccessibilityService.GLOBAL_ACTION_BACK)
        put("home", AccessibilityService.GLOBAL_ACTION_HOME)
        put("recents", AccessibilityService.GLOBAL_ACTION_RECENTS)
        put("notifications", AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
        put("quicksettings", AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
        put("powermenu", AccessibilityService.GLOBAL_ACTION_POWER_DIALOG)
        // API-gated constants: adding them here is how the channel gains "lock the
        // screen" and "take a screenshot" without any new permission.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            put("lock", AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            put("screenshot", AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            put("split", AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
        }
    }

    fun key(service: AccessibilityService, key: String): JSONObject {
        val action = beginAction(service)
        val normalized = key.trim().lowercase().replace("-", "").replace("_", "")
        val globalAction = globalActions[normalized]
        if (globalAction != null) {
            val ok = service.performGlobalAction(globalAction)
            if (!ok) {
                throw DeviceActionException(
                    DeviceDenial(
                        code = DeviceDenial.UNSUPPORTED,
                        reason = "系统拒绝了全局动作「$key」。",
                        hint = "让用户确认无障碍服务已启用后重试。",
                    ),
                )
            }
            return action.finish(
                JSONObject().apply {
                    put("key", normalized)
                    put("mode", "global_action")
                },
            )
        }
        throw DeviceActionException(
            DeviceDenial(
                code = DeviceDenial.UNSUPPORTED,
                reason = "无障碍不能注入按键「$key」。可用：" + globalActions.keys.sorted().joinToString("、") + "。",
                hint = "原始按键改用 android_keyevent（需 Shizuku）。",
            ),
        )
    }

    // ------------------------------------------------------- raw key events ----

    /**
     * `input keyevent` for the keys the accessibility channel cannot produce.
     *
     * This exists because the accessibility route can only fire a handful of
     * `GLOBAL_ACTION`s, and `input` — like every other `InputManager` client — needs
     * `INJECT_EVENTS`, a signature permission the app will never hold. uid 2000
     * does, so with Shizuku this endpoint finally is the "arbitrary keyevent"
     * capability the design's `android_shell` note promised. Without Shizuku it
     * refuses and says why, instead of returning a confusing permission error.
     */
    fun keyEvent(
        keys: String,
        repeat: Int,
        backend: DeviceShellBackend,
        service: AccessibilityService? = null,
    ): JSONObject {
        val tokens = keys.trim()
            .uppercase()
            .split(Regex("[\\s,]+"))
            .filter { it.isNotEmpty() }
            .map { it.removePrefix("KEYCODE_") }
        if (tokens.isEmpty()) {
            throw DeviceActionException(DeviceDenial(DeviceDenial.BAD_REQUEST, "keyevent 需要至少一个按键名。"))
        }
        // The characters are validated rather than escaped: the names go into a
        // command string, and `input` has no shell-quoting of its own.
        for (token in tokens) {
            if (!Regex("^[A-Z0-9_]{1,24}$").matches(token)) {
                throw DeviceActionException(
                    DeviceDenial(
                        code = DeviceDenial.BAD_REQUEST,
                        reason = "非法按键名：$token（只用 A-Z0-9_，如 ENTER、DEL）。",
                        hint = "Android KeyEvent 名，KEYCODE_ 可省略。",
                    ),
                )
            }
        }
        val times = repeat.coerceIn(1, 20)
        if (backend.id != ShizukuShellBackend.id) {
            throw DeviceActionException(
                DeviceDenial(
                    code = DeviceDenial.UNSUPPORTED,
                    reason = "原始按键需要 ADB 身份（uid=2000），当前后端是应用自身身份。",
                    hint = "启用 Shizuku（设置 → 设备能力 → Shell）或改用 android_key。",
                ),
            )
        }
        val action = service?.let { beginAction(it) }
        val command = buildString {
            append("input keyevent")
            repeat(times) {
                for (token in tokens) append(' ').append(token)
            }
        }
        val result = backend.run(command, 20_000)
        val failed = result.exitCode != 0 ||
            result.stderr.contains("Exception", ignoreCase = true) ||
            result.stderr.contains("Error:", ignoreCase = true)
        if (failed) {
            throw DeviceActionException(
                DeviceDenial(
                    code = DeviceDenial.ERROR,
                    reason = "input keyevent 失败（码 ${result.exitCode}）：" +
                        result.stderr.trim().ifEmpty { result.stdout.trim() }.take(400),
                    hint = "可用 android_shell 手动跑同一条命令看输出。",
                ),
            )
        }
        val payload = JSONObject().apply {
            put("keys", JSONArray(tokens))
            put("repeat", times)
            put("mode", "input keyevent")
            put("backend", result.backend)
        }
        return action?.finish(payload) ?: payload
    }

    // ---------------------------------------------------------- screenshot ----

    /**
     * @param region optional display-pixel rectangle to crop to *before* scaling.
     *   Cropping first is what makes a small UI detail legible: a 200×80 region
     *   stays 200×80 instead of being scaled down with the whole screen.
     * @param marks draw the dump's clickable indices (set-of-marks) onto the image,
     *   so the model can point at "12" instead of estimating a pixel.
     */
    fun screenshot(
        service: AccessibilityService,
        format: String,
        maxDimension: Int,
        quality: Int,
        region: Rect? = null,
        marks: Boolean = false,
    ): JSONObject {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            throw DeviceActionException(
                DeviceDenial(
                    code = DeviceDenial.UNSUPPORTED,
                    reason = "无障碍截图需要 Android 11。",
                    hint = "让用户用系统截图，或用 android_shell screencap（需 Shizuku）。",
                ),
            )
        }
        val raw = takeScreenshotBlocking(service)
        val sourceWidth = raw.width
        val sourceHeight = raw.height

        var working = raw
        val appliedRegion = region?.let {
            val left = it.left.coerceIn(0, sourceWidth - 1)
            val top = it.top.coerceIn(0, sourceHeight - 1)
            val right = it.right.coerceIn(left + 1, sourceWidth)
            val bottom = it.bottom.coerceIn(top + 1, sourceHeight)
            val cropped = try {
                Bitmap.createBitmap(raw, left, top, right - left, bottom - top)
            } catch (error: IllegalArgumentException) {
                throw DeviceActionException(
                    DeviceDenial(
                        DeviceDenial.BAD_REQUEST,
                        "裁剪超出屏幕（display）：${it.toShortString()}。",
                    ),
                )
            }
            if (cropped !== raw) raw.recycle()
            working = cropped
            Rect(left, top, right, bottom)
        }

        val scaled = scaleDown(working, maxDimension.coerceIn(240, 4096))
        if (scaled !== working) working.recycle()
        val scale = scaled.width.toFloat() / sourceWidth.toFloat()

        val marked = if (marks && appliedRegion == null) drawMarks(scaled, service) else 0

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
            put("coordinateSpace", SPACE)
            // Everything needed to map an image pixel back to a display pixel:
            //   display = region.topLeft + image / scale
            put("sourceWidth", sourceWidth)
            put("sourceHeight", sourceHeight)
            put("scale", scale.toDouble())
            put(
                "region",
                appliedRegion?.let { JSONArray(listOf(it.left, it.top, it.right, it.bottom)) } ?: JSONObject.NULL,
            )
            if (marks) {
                put("markedIndices", marked)
                if (appliedRegion != null) {
                    put(
                        "marksNote",
                        "裁剪与 set-of-marks 不能同时用：裁剪后的图上没有完整编号（只需要编号时去掉 region）。",
                    )
                }
            }
        }
    }

    /**
     * Set-of-marks: outline every clickable node from the last dump and print its
     * index on it. Returns how many were drawn so the caller can say so.
     */
    private fun drawMarks(target: Bitmap, service: AccessibilityService): Int {
        val snapshot = lastSnapshot.filter { it.clickable && !it.bounds.isEmpty }
        if (snapshot.isEmpty()) return 0
        val canvas = Canvas(target)
        val displayWidth = service.resources.displayMetrics.widthPixels.coerceAtLeast(1)
        val scale = target.width.toFloat() / displayWidth.toFloat()
        val outline = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = max(1.5f, 2f)
            color = Color.argb(220, 255, 64, 64)
            isAntiAlias = true
        }
        val text = Paint().apply {
            color = Color.argb(235, 255, 255, 255)
            isAntiAlias = true
            textSize = max(11f, min(34f, 14f / scale.coerceAtLeast(0.2f)))
            typeface = Typeface.DEFAULT_BOLD
        }
        val textBack = Paint().apply {
            color = Color.argb(200, 20, 20, 20)
            isAntiAlias = true
            style = Paint.Style.FILL
        }
        var drawn = 0
        for (record in snapshot) {
            val left = record.bounds.left * scale
            val top = record.bounds.top * scale
            val right = record.bounds.right * scale
            val bottom = record.bounds.bottom * scale
            if (right < 0 || bottom < 0 || left > target.width || top > target.height) continue
            canvas.drawRect(left, top, right, bottom, outline)
            val label = record.index.toString()
            val labelTop = (top - text.textSize).coerceAtLeast(0f)
            canvas.drawRect(
                left,
                labelTop,
                left + text.measureText(label) + 6f,
                labelTop + text.textSize + 4f,
                textBack,
            )
            canvas.drawText(label, left + 3f, labelTop + text.textSize, text)
            drawn++
        }
        return drawn
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
                    code = DeviceDenial.NOT_CONNECTED,
                    reason = "无障碍服务不可用，截图未开始。",
                    hint = "稍等 1 秒重试；仍失败让用户重开「设置 → 无障碍 → PI 设备桥」。",
                    retryable = true,
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
            hint = "安全窗口让用户退出后重试；频率限制则稍等 1 秒。",
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

    // ------------------------------------------------------ action envelope ----

    /**
     * What a UI action returns *besides* "I sent it":
     * `before`/`after` foreground package and whether anything changed.
     *
     * "已点按" without this is an unverifiable claim — a stale index, a blocking
     * dialog and an overlay all produce a perfectly successful gesture that does
     * nothing the caller wanted. This is the self-check that makes an action report
     * falsifiable, and it costs one tree read before the action.
     */
    private class ActionContext(
        private val service: AccessibilityService,
        val before: String?,
        private val beforeSignature: String,
    ) {
        /** The selector an action was aimed at, if its call site recorded one. */
        private var targetSelector: Selector? = null

        /** The child-index path an action was aimed at, if its call site recorded one. */
        private var targetPath: List<Int>? = null

        /**
         * Record what the action was aimed at, so [finish] can answer「目标还在吗」.
         *
         * This is the missing half of「动作后验证」: `before`/`after` says whether the
         * *screen* moved, but not whether the control the caller asked for is still
         * there — a navigation that consumed the tapped row is the normal, correct
         * outcome, and it must read differently from a tap that hit an overlay.
         */
        fun markTarget(selector: Selector? = null, path: List<Int>? = null): ActionContext {
            if (selector != null && !selector.isEmpty) targetSelector = selector
            if (path != null) targetPath = path
            return this
        }

        fun finish(payload: JSONObject): JSONObject {
            // Qualified: a nested class has no implicit receiver for the enclosing
            // object's members.
            val after = DeviceUiAutomation.awaitForeground(service, before)
            val afterSignature = DeviceUiAutomation.signature(service)
            payload.put("before", before ?: JSONObject.NULL)
            payload.put("after", after ?: JSONObject.NULL)
            payload.put("changed", before != after || beforeSignature != afterSignature)
            payload.put("coordinateSpace", SPACE)
            val selector = targetSelector
            val path = targetPath
            if (selector != null || path != null) {
                // 能标记目标，说明动作前它就在屏幕上——所以 before 恒为 true，只有 after
                // 需要实时复核。
                val presentAfter = when {
                    selector != null -> runCatching {
                        DeviceUiAutomation.liveMatches(service, selector, limit = 1).isNotEmpty()
                    }.getOrDefault(false)

                    path != null -> DeviceUiAutomation.pathExists(service, path)
                    else -> false
                }
                payload.put("targetPresentBefore", true)
                payload.put("targetPresentAfter", presentAfter)
                payload.put("targetDisappeared", !presentAfter)
            } else {
                payload.put("targetPresentBefore", JSONObject.NULL)
                payload.put("targetPresentAfter", JSONObject.NULL)
            }
            return payload
        }
    }

    private fun beginAction(service: AccessibilityService): ActionContext =
        ActionContext(service, foregroundPackage(service), signature(service))

    /** The package of the active window, read live (not from a snapshot). */
    private fun foregroundPackage(service: AccessibilityService): String? =
        runCatching { activeRoot(service)?.packageName?.toString() }.getOrNull()

    /** A cheap fingerprint of the current screen, for "did anything change". */
    private fun signature(service: AccessibilityService): String = runCatching {
        val root = activeRoot(service) ?: return ""
        val counter = intArrayOf(0)
        val text = StringBuilder()
        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 40 || counter[0] > 600) return
            counter[0]++
            text.append(node.className).append('|')
            node.text?.let { text.append(it) }
            node.contentDescription?.let { text.append(it) }
            text.append(';')
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { walk(it, depth + 1) }
            }
        }
        walk(root, 0)
        counter[0].toString() + ":" + text.length + ":" + text.toString().hashCode()
    }.getOrDefault("")

    /**
     * Give the UI up to [FOREGROUND_POLL_MS] to settle, preferring the first
     * moment the package actually differs from [previous].
     *
     * A successful `ACTION_CLICK` does not guarantee the next window is up yet, so
     * reading immediately would report "no change" for every navigation.
     */
    private fun awaitForeground(service: AccessibilityService, previous: String?): String? {
        val started = System.currentTimeMillis()
        val deadline = started + FOREGROUND_POLL_MS
        var last: String? = foregroundPackage(service)
        while (System.currentTimeMillis() < deadline) {
            if (last != null && last != previous) return last
            // Same package is the common case; give it a short grace period for a
            // redraw to land, then stop rather than always burning the full window.
            if (last == previous && System.currentTimeMillis() - started > FOREGROUND_POLL_MS / 2) break
            runCatching { Thread.sleep(60) }
            last = foregroundPackage(service) ?: last
        }
        return last
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
    private fun resolve(service: AccessibilityService, record: NodeRecord): AccessibilityNodeInfo? =
        resolvePath(service, record.path)

    /** The same child-index walk as [resolve], but from a raw path (macros and diffs keep paths). */
    private fun resolvePath(service: AccessibilityService, path: List<Int>): AccessibilityNodeInfo? {
        var node = activeRoot(service) ?: return null
        for (step in path) {
            if (step >= node.childCount) return null
            node = node.getChild(step) ?: return null
        }
        return node
    }

    /** Whether the node a recorded path pointed at still exists (used by target verification). */
    private fun pathExists(service: AccessibilityService, path: List<Int>): Boolean =
        resolvePath(service, path) != null

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

    // ------------------------------------------------------- live inspection ----

    /** For the diagnostics card: are we mid-gesture, and how big is the cursor. */
    fun busy(): Boolean = gestureBusy.get()

    fun lastSnapshotInfo(): JSONObject = JSONObject().apply {
        put("snapshotId", snapshotId)
        put("packageName", lastPackage)
        put("nodeCount", lastSnapshot.size)
        put("gestureBusy", gestureBusy.get())
    }

    // =====================================================================
    // 代理B · 无障碍可靠性层
    //
    // 上面是「能 dump / 能点 / 能输入」的原语；这一层把它们变成可验证、可自愈的
    // 操作面：结构化元素表、差分指纹、多策略选择器、事件驱动的等待、动作后校验、
    // UI 宏与 App 记忆。全部只依赖本文件已有的工具（DeviceUiText /
    // DeviceAccessibilitySignals / DeviceDenial / 手势与树遍历），不引第三方依赖，
    // 也不改动上面任何公开签名。
    // =====================================================================

    /**
     * 等待原语用尽的超时码。
     *
     * 为什么是第二个码而不是复用 `NOT_FOUND`：`DeviceDenial.code` 本来就是自由
     * 字符串，[DeviceDenial] 又是公共文件不能改。而「屏幕上没有」和「等超时了仍没有」
     * 对上层是两个动作——前者该换选择器，后者该调大 timeoutMs 或先清弹窗——所以
     * 旧代码把两者都写成 `NOT_FOUND` 的做法在这里被拆开。
     */
    const val TIMEOUT = "TIMEOUT"

    /** 宏单步默认重试次数：有界，不是无脑循环。 */
    private const val DEFAULT_STEP_RETRIES = 3
    private const val MAX_STEP_RETRIES = 5

    /**
     * 一个多策略查询。字段本身就是自愈的候选顺序，越靠前优先级越高：
     * [resourceId]（精确）→ [text]（精确）→ [description]（精确）→ [anchorText]
     * （邻居文本）→ [className]/[occurrence]/[direction]（相对位置）→ [x]/[y]（坐标）。
     *
     * 为什么不是一个 [Selector]：屏幕改版时最稳的键会变——今天有 resourceId，明天
     * 变纯文本；本地化又会反过来让文本失配。带多个键，解析器就能在某个键失效时
     * 退到下一个，而不是让整步失败——这正是 DroidRun 那一类 90% 成功率系统的核心。
     */
    data class Query(
        val resourceId: String? = null,
        val text: String? = null,
        val description: String? = null,
        val packageName: String? = null,
        val className: String? = null,
        val anchorText: String? = null,
        val direction: String? = null,
        val occurrence: Int? = null,
        val clickableOnly: Boolean = false,
        val x: Int? = null,
        val y: Int? = null,
    ) {
        val isEmpty: Boolean
            get() = resourceId == null && text == null && description == null && className == null &&
                anchorText == null && direction == null && x == null && y == null

        fun describe(): String = buildList {
            resourceId?.let { add("id=\"$it\"") }
            text?.let { add("text=\"$it\"") }
            description?.let { add("desc=\"$it\"") }
            className?.let { add("class=\"$it\"" + (occurrence?.let { n -> "#$n" } ?: "")) }
            anchorText?.let { add("anchor=\"$it\"") }
            direction?.let { add("direction=$it") }
            packageName?.let { add("pkg=$it") }
            x?.let { add("x=$it") }
            y?.let { add("y=$it") }
            if (clickableOnly) add("clickable")
        }.joinToString(" & ").ifEmpty { "（空查询）" }
    }

    /** 命中结果：哪个策略命中的 + 命中的元素（记录带 path，可直接 resolve）。 */
    private class SelectHit(val strategy: String, val record: NodeRecord)

    /** 一次 [selectOnce] 的完整结果：命中（可能为 null）+ 每个策略的成败原因。 */
    private class SelectAttemptResult(val hit: SelectHit?, val attempts: JSONArray)

    /** 一次差分的元素引用，用来比对前后字段。 */
    private class ElementRef(
        val json: JSONObject,
        val key: String,
        val text: String,
        val description: String,
        val bounds: String,
    ) {
        fun brief(): JSONObject = JSONObject().apply {
            put("key", key)
            put("text", text)
            put("bounds", bounds)
            put("resourceId", json.opt("resourceId") ?: JSONObject.NULL)
        }
    }

    /** 屏幕指纹：包名单列，指纹是排序后元素表描述的哈希。 */
    private class ScreenFingerprint(val packageName: String, val value: String, val elementCount: Int)

    @Volatile
    private var lastElementsJson: JSONObject? = null

    @Volatile
    private var lastElementFingerprint: String = ""

    @Volatile
    private var lastElementPackage: String = ""

    @Volatile
    private var lastElementCount: Int = 0

    // ----------------------------------------------------- 结构化屏幕模型 ----

    /**
     * 把节点树压成「可操作元素表」：只留可见、去掉纯装饰节点。
     *
     * 解决的失败模式：dump 出来的几百个节点里绝大多数是布局容器（FrameLayout、
     * ViewGroup…），模型在噪声里挑可点项既慢又容易挑错。这里在设备侧就把「不可见」
     * 与「没有任何语义/交互」的节点剔除，并把每个元素的中心点算好，模型直接读表即可。
     *
     * 元素字段：index / text / description / resourceId / class / bounds / center /
     * clickable / editable / scrollable / enabled / packageName / key。
     */
    fun elements(
        service: AccessibilityService,
        maxNodes: Int = DEFAULT_MAX_NODES,
        maxDepth: Int = DEFAULT_MAX_DEPTH,
    ): JSONObject {
        val limit = maxNodes.coerceIn(1, MAX_NODES_LIMIT)
        val depthLimit = maxDepth.coerceIn(1, 64)
        val root = activeRoot(service)
            ?: throw DeviceActionException(
                DeviceDenial(
                    code = DeviceDenial.NOT_FOUND,
                    reason = "没有可读窗口，无法建立元素表（可能锁屏或没有前台界面）。",
                    hint = "让用户解锁并回到可见界面后重试。",
                ),
            )
        val packageName = root.packageName?.toString().orEmpty()
        val records = ArrayList<NodeRecord>()
        collectActionable(root, packageName, emptyList(), 0, limit, depthLimit, records)
        val metrics = service.resources.displayMetrics
        val occurrences = HashMap<String, Int>()
        val elements = JSONArray()
        val lines = StringBuilder()
        for (record in records) {
            val key = elementKey(record, occurrences)
            elements.put(elementToJson(record, key, elementSignature(record)))
            lines.append('[').append(record.index).append("] ").append(render(record))
                .append("  (key=").append(key).append(')').append('\n')
        }
        val descriptorList = elementDescriptors(records, metrics.widthPixels, metrics.heightPixels)
        val fingerprint = fingerprintOf(descriptorList)
        val payload = JSONObject().apply {
            put("packageName", packageName)
            put("count", records.size)
            put("fingerprint", fingerprint)
            put("coordinateSpace", SPACE)
            put("screen", JSONObject().put("width", metrics.widthPixels).put("height", metrics.heightPixels))
            put("elements", elements)
            put("text", lines.toString().trimEnd('\n').ifEmpty { "（没有可操作元素）" })
            put("truncated", records.size >= limit)
            put("empty", records.isEmpty())
        }
        synchronized(this) {
            lastElementsJson = payload
            lastElementFingerprint = fingerprint
            lastElementPackage = packageName
            lastElementCount = records.size
        }
        rememberScreen(packageName, records)
        return payload
    }

    /** Only for `waitForIdle` / `screenFingerprint`: the same table, no JSON building. */
    private fun collectActionable(
        node: AccessibilityNodeInfo,
        packageName: String,
        path: List<Int>,
        depth: Int,
        limit: Int,
        depthLimit: Int,
        out: MutableList<NodeRecord>,
    ) {
        if (out.size >= limit || depth > depthLimit) return
        // 不可见节点的子树也不可见：整枝剪掉，既快又不会把离屏内容带进指纹。
        if (!node.isVisibleToUser) return
        val rect = Rect().also { node.getBoundsInScreen(it) }
        val text = DeviceUiText.clip(node.text)
        val description = DeviceUiText.clip(node.contentDescription)
        val actionable = node.isClickable || node.isLongClickable || node.isEditable ||
            node.isScrollable || node.isCheckable || node.isFocused ||
            text.isNotEmpty() || description.isNotEmpty()
        if (actionable && !rect.isEmpty) {
            out.add(
                NodeRecord(
                    index = out.size,
                    path = path,
                    bounds = rect,
                    text = text,
                    description = description,
                    viewId = node.viewIdResourceName,
                    className = DeviceUiText.simpleClassName(node.className),
                    packageName = node.packageName?.toString() ?: packageName,
                    clickable = node.isClickable,
                    longClickable = node.isLongClickable,
                    scrollable = node.isScrollable,
                    editable = node.isEditable,
                    enabled = node.isEnabled,
                ),
            )
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectActionable(child, packageName, path + i, depth + 1, limit, depthLimit, out)
        }
    }

    /**
     * 元素身份：优先 resourceId，否则 class + 同类出现序号。
     *
     * 刻意**不**把 text 放进身份：文本变化应该被 diff 报成「属性变化」，而不是
     * 一条「消失」加一条「新增」。重复的无 id 同 class 节点按先后顺序区分，这是
     * 一个够用的启发式（局限在 KDoc 里说明，不假装是唯一解）。
     */
    private fun elementKey(record: NodeRecord, occurrences: MutableMap<String, Int>): String {
        val base = record.viewId?.takeIf { it.isNotEmpty() } ?: record.className.ifEmpty { "Node" }
        val count = (occurrences[base] ?: 0) + 1
        occurrences[base] = count
        return "$base#$count"
    }

    /** 属性指纹：文本 / 描述 / 边界。用于「位置或文本变化」的判定。 */
    private fun elementSignature(record: NodeRecord): String = buildString {
        append(record.text).append('|').append(record.description).append('|')
        append(record.bounds.left).append(',').append(record.bounds.top).append(',')
        append(record.bounds.right).append(',').append(record.bounds.bottom)
    }

    /**
     * 元素描述串（供整表指纹用）：`resourceId|class|归一化bounds|文本哈希`。
     *
     * bounds 归一化到千分比，是为了让指纹对绝对像素不敏感：同一屏幕在不同分辨率/
     * 密度下应得到同一个指纹「这是一样的界面」。
     */
    private fun elementDescriptor(record: NodeRecord, screenWidth: Int, screenHeight: Int): String {
        val b = record.bounds
        val normalized = buildString {
            append(normalize(b.left, screenWidth)).append(',')
            append(normalize(b.top, screenHeight)).append(',')
            append(normalize(b.right, screenWidth)).append(',')
            append(normalize(b.bottom, screenHeight))
        }
        val label = record.text + "/" + record.description
        return record.viewId.orEmpty() + "|" + record.className + "|" + normalized + "|" + label.hashCode()
    }

    /** 排序后的整表描述：指纹与元素出现顺序无关。 */
    private fun elementDescriptors(records: List<NodeRecord>, screenWidth: Int, screenHeight: Int): List<String> =
        records.map { elementDescriptor(it, screenWidth, screenHeight) }.sorted()

    private fun normalize(value: Int, size: Int): Long =
        if (size <= 0) value.toLong() else value.toLong() * 1000L / size

    private fun fingerprintOf(descriptors: List<String>): String =
        descriptors.size.toString() + ":" + descriptors.joinToString("\n").hashCode()

    private fun elementToJson(record: NodeRecord, key: String, signature: String): JSONObject = JSONObject().apply {
        val rect = record.bounds
        put("index", record.index)
        put("key", key)
        put("signature", signature)
        put("class", record.className)
        put("resourceId", record.viewId ?: JSONObject.NULL)
        put("text", record.text)
        put("description", record.description)
        put("packageName", record.packageName)
        put("bounds", JSONArray(listOf(rect.left, rect.top, rect.right, rect.bottom)))
        put("center", JSONArray(listOf(rect.centerX(), rect.centerY())))
        put("clickable", record.clickable)
        put("longClickable", record.longClickable)
        put("editable", record.editable)
        put("scrollable", record.scrollable)
        put("enabled", record.enabled)
    }

    // --------------------------------------------------------- 差分跟踪 ----

    /**
     * 比对两张元素表，只回报三类：新增 / 消失 / 属性变化（每类都带前后值）。
     *
     * 解决的失败模式：模型记不住上一屏，于是每一步都要重 dump 整棵树。带着上一次的
     * 元素表，就能只告诉它「多了什么、少了什么、哪个按钮挪了位」，prompt 短、模型稳。
     */
    fun diff(previous: JSONObject, current: JSONObject): JSONObject {
        val before = indexElements(previous)
        val after = indexElements(current)
        val added = JSONArray()
        val removed = JSONArray()
        val changed = JSONArray()
        for ((key, currentRef) in after) {
            val previousRef = before[key]
            if (previousRef == null) {
                added.put(JSONObject().put("before", JSONObject.NULL).put("after", currentRef.brief()))
                continue
            }
            val fields = JSONArray()
            if (previousRef.text != currentRef.text) fields.put("text")
            if (previousRef.description != currentRef.description) fields.put("description")
            if (previousRef.bounds != currentRef.bounds) fields.put("bounds")
            if (fields.length() > 0) {
                changed.put(
                    JSONObject()
                        .put("key", key)
                        .put("fields", fields)
                        .put("before", previousRef.brief())
                        .put("after", currentRef.brief()),
                )
            }
        }
        for ((key, previousRef) in before) {
            if (!after.containsKey(key)) {
                removed.put(JSONObject().put("before", previousRef.brief()).put("after", JSONObject.NULL))
            }
        }
        val packageBefore = previous.optString("packageName")
        val packageAfter = current.optString("packageName")
        return JSONObject().apply {
            put("packageName", packageAfter)
            put("packageChanged", packageBefore != packageAfter)
            put("fingerprintBefore", previous.optString("fingerprint"))
            put("fingerprintAfter", current.optString("fingerprint"))
            put(
                "same",
                added.length() == 0 && removed.length() == 0 && changed.length() == 0 &&
                    packageBefore == packageAfter,
            )
            put("added", added)
            put("removed", removed)
            put("changed", changed)
            put(
                "counts",
                JSONObject().put("added", added.length()).put("removed", removed.length())
                    .put("changed", changed.length()),
            )
        }
    }

    /**
     * 对「上一次建过元素表」做差分：先取当前屏，再和记忆里的指纹比。
     *
     * 这是给「每一步之后我该知道屏幕怎么变了」用的：一次调用返回新增/消失/属性变化，
     * 而不是让上层自己保存并比对两份 dump。
     */
    fun diffSinceLast(
        service: AccessibilityService,
        maxNodes: Int = DEFAULT_MAX_NODES,
        maxDepth: Int = DEFAULT_MAX_DEPTH,
    ): JSONObject {
        val previous = synchronized(this) { lastElementsJson }
        val current = elements(service, maxNodes, maxDepth)
        if (previous == null) {
            return JSONObject().apply {
                put("first", true)
                put("packageName", current.optString("packageName"))
                put("fingerprint", current.optString("fingerprint"))
                put("counts", JSONObject().put("added", current.optInt("count")).put("removed", 0).put("changed", 0))
            }
        }
        return diff(previous, current).put("first", false)
    }

    private fun indexElements(payload: JSONObject): LinkedHashMap<String, ElementRef> {
        val out = LinkedHashMap<String, ElementRef>()
        val array = payload.optJSONArray("elements") ?: JSONArray()
        for (i in 0 until array.length()) {
            val json = array.optJSONObject(i) ?: continue
            val key = json.optString("key")
            if (key.isEmpty()) continue
            out[key] = ElementRef(
                json = json,
                key = key,
                text = json.optString("text"),
                description = json.optString("description"),
                bounds = json.optJSONArray("bounds")?.toString().orEmpty(),
            )
        }
        return out
    }

    // --------------------------------------------- 多策略选择器 + 自愈 ----

    /** 一次性把整棵树读成记录表（选择器需要几何信息，所以用记录而不是节点）。 */
    private fun readTreeAll(service: AccessibilityService): List<NodeRecord> {
        val root = activeRoot(service) ?: return emptyList()
        val out = ArrayList<NodeRecord>()
        collect(root, root.packageName?.toString().orEmpty(), emptyList(), 0, MAX_NODES_LIMIT, 64, out)
        return out
    }

    private fun isActionable(record: NodeRecord): Boolean =
        record.clickable || record.longClickable || record.editable || record.scrollable

    /** 匹配池里挑最小（最具体）的一个：更可能是真正被点的那个控件。 */
    private fun pickBest(matches: List<NodeRecord>): NodeRecord? {
        if (matches.isEmpty()) return null
        val enabled = matches.filter { it.enabled }
        val pool = enabled.ifEmpty { matches }
        val actionable = pool.filter { isActionable(it) }
        return actionable.ifEmpty { pool }.minByOrNull {
            it.bounds.width().toLong() * it.bounds.height().toLong()
        }
    }

    private fun packageMatches(actual: String, wanted: String): Boolean =
        actual == wanted || actual.endsWith(".$wanted")

    /**
     * 单一轮的自愈选择：按稳定性从高到低依次尝试，每个策略失败都记下原因。
     *
     * 顺序（资源 ID 精确 → 文本精确 → 描述精确 → 邻居文本 → 相对位置 → 坐标）就是
     * 这里的 when/if 顺序；屏幕改版时，前一个键失效会让解析器退到下一个，而不是
     * 直接失败。
     */
    private fun selectOnce(service: AccessibilityService, query: Query): SelectAttemptResult {
        val attempts = JSONArray()
        val all = readTreeAll(service)
        val nodes = if (query.packageName != null) {
            all.filter { packageMatches(it.packageName, query.packageName) }
        } else {
            all
        }

        fun succeeded(strategy: String, record: NodeRecord): SelectAttemptResult {
            attempts.put(JSONObject().put("strategy", strategy).put("ok", true).put("matched", recordToJson(record)))
            return SelectAttemptResult(SelectHit(strategy, record), attempts)
        }

        fun failed(strategy: String, reason: String) {
            attempts.put(JSONObject().put("strategy", strategy).put("ok", false).put("reason", reason))
        }

        fun eligible(record: NodeRecord, predicate: (NodeRecord) -> Boolean): Boolean {
            if (!record.enabled) return false
            if (query.clickableOnly && !record.clickable) return false
            return predicate(record)
        }

        query.resourceId?.let { wanted ->
            val hit = pickBest(
                nodes.filter { eligible(it) { record ->
                    val id = record.viewId ?: return@eligible false
                    id == wanted || id.endsWith("/$wanted")
                } },
            )
            if (hit != null) return succeeded("resourceId", hit)
            failed("resourceId", "没有 resourceId 精确等于「$wanted」的可用元素")
        }
        query.text?.let { wanted ->
            val hit = pickBest(nodes.filter { eligible(it) { record -> record.text == wanted } })
            if (hit != null) return succeeded("text", hit)
            failed("text", "没有 text 精确等于「$wanted」的可用元素")
        }
        query.description?.let { wanted ->
            val hit = pickBest(nodes.filter { eligible(it) { record -> record.description == wanted } })
            if (hit != null) return succeeded("description", hit)
            failed("description", "没有 contentDescription 精确等于「$wanted」的可用元素")
        }
        query.anchorText?.let { anchor ->
            val hit = neighborByText(nodes, anchor, query.direction)
            if (hit != null) return succeeded("neighbor", hit)
            failed("neighbor", "找不到「$anchor」附近（${query.direction ?: "任意方向"}）的可操作元素")
        }
        if (query.className != null || query.direction != null) {
            val hit = relativePosition(nodes, query)
            if (hit != null) return succeeded("relative", hit)
            val why = if (query.className != null) {
                "同屏没有第 ${query.occurrence ?: 1} 个「${query.className}」可操作元素"
            } else {
                "参考元素${query.direction}方向没有可操作元素"
            }
            failed("relative", why)
        }
        if (query.x != null && query.y != null) {
            val hit = coordinateHit(nodes, query.x, query.y)
            if (hit != null) return succeeded("coordinate", hit)
            failed("coordinate", "坐标 (${query.x}, ${query.y}) 上没有元素")
        }
        return SelectAttemptResult(null, attempts)
    }

    /**
     * 公开的多策略选择。失败**不抛异常**，而是返回 `found=false` 与每个策略的失败原因——
     * 「按 ID 没找到、按文本也没找到…」正是上层切换手段所需的信息，抛出去反而丢了。
     */
    fun select(service: AccessibilityService, query: Query, timeoutMs: Int = 0): JSONObject {
        if (query.isEmpty) {
            throw DeviceActionException(
                DeviceDenial(DeviceDenial.BAD_REQUEST, "select 需要一个选择器：resourceId/text/desc/anchor/class/坐标。"),
            )
        }
        val timeout = timeoutMs.coerceIn(0, 60_000)
        val started = System.currentTimeMillis()
        var result = selectOnce(service, query)
        while (result.hit == null && timeout > 0) {
            val remaining = timeout - (System.currentTimeMillis() - started)
            if (remaining <= 0) break
            // 事件驱动：等一次界面变化再重试，而不是固定 sleep。
            DeviceAccessibilitySignals.awaitChange(DeviceAccessibilitySignals.current, min(remaining, 200L))
            result = selectOnce(service, query)
        }
        return JSONObject().apply {
            put("query", query.describe())
            put("elapsedMs", System.currentTimeMillis() - started)
            put("attempts", result.attempts)
            put("coordinateSpace", SPACE)
            val hit = result.hit
            if (hit != null) {
                put("found", true)
                put("strategy", hit.strategy)
                put("node", recordToJson(hit.record))
            } else {
                put("found", false)
                put("code", if (timeout > 0) TIMEOUT else DeviceDenial.NOT_FOUND)
                put("reason", "所有选择器策略都没命中：${query.describe()}。")
                put("visualFallbackAvailable", true)
                put("hint", "可以调 visualFallback 生成一次视觉兜底请求，交给上层视觉模型再决定坐标。")
            }
        }
    }

    /**
     * 邻居文本策略：先用锚点文本定位参照元素，再找它最近的可操作祖先（列表行常常
     * 就是文字节点的父节点），否则退到方向最近的兄弟/表亲。
     */
    private fun neighborByText(nodes: List<NodeRecord>, anchorText: String, direction: String?): NodeRecord? {
        val anchors = nodes.filter { it.text == anchorText || it.description == anchorText }
            .ifEmpty {
                nodes.filter {
                    it.text.contains(anchorText, ignoreCase = true) ||
                        it.description.contains(anchorText, ignoreCase = true)
                }
            }
        if (anchors.isEmpty()) return null
        for (anchor in anchors.sortedBy { it.path.size }) {
            val ancestor = nodes
                .filter {
                    it.path.size < anchor.path.size &&
                        anchor.path.take(it.path.size) == it.path &&
                        isActionable(it) && it.enabled
                }
                .maxByOrNull { it.path.size }
            if (ancestor != null) return ancestor
        }
        val anchor = anchors.minByOrNull { it.bounds.width().toLong() * it.bounds.height().toLong() } ?: return null
        return nearestInDirection(nodes, anchor.bounds, direction, anchor.path)
    }

    /**
     * 相对位置策略：同屏同类第 N 个（[Query.occurrence]，1 起），或按方向最近。
     *
     * 解决的失败模式：控件既没 id 也没稳定文本（自绘/无标签图标），但「从右到左
     * 第二个 ImageButton」是稳定的——用类名 + 序号定位。
     */
    private fun relativePosition(nodes: List<NodeRecord>, query: Query): NodeRecord? {
        query.className?.let { wanted ->
            val sameClass = nodes
                .filter { it.className == wanted && it.enabled && isActionable(it) }
                .sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
            if (sameClass.isNotEmpty()) {
                val ordinal = (query.occurrence ?: 1).coerceIn(1, sameClass.size)
                return sameClass[ordinal - 1]
            }
        }
        val direction = query.direction ?: return null
        val reference = query.anchorText?.let { anchor ->
            nodes.firstOrNull { it.text == anchor || it.description == anchor }?.bounds
        } ?: return null
        return nearestInDirection(nodes, reference, direction, null)
    }

    /** 在参照矩形的某个方向里挑中心最近的可操作元素。 */
    private fun nearestInDirection(
        nodes: List<NodeRecord>,
        reference: Rect,
        direction: String?,
        excludePath: List<Int>?,
    ): NodeRecord? {
        val dir = direction?.lowercase()
        val refX = reference.centerX()
        val refY = reference.centerY()
        val candidates = nodes.filter { record ->
            if (!isActionable(record) || !record.enabled) return@filter false
            if (excludePath != null && record.path == excludePath) return@filter false
            when (dir) {
                "above", "up" -> record.bounds.bottom <= reference.top
                "below", "down" -> record.bounds.top >= reference.bottom
                "left" -> record.bounds.right <= reference.left
                "right" -> record.bounds.left >= reference.right
                else -> true
            }
        }
        return candidates.minByOrNull { record ->
            abs(record.bounds.centerX() - refX).toLong() + abs(record.bounds.centerY() - refY).toLong()
        }
    }

    /** 坐标策略：命中最深的、包含该点的元素（与 verify 的命中判定一致）。 */
    private fun coordinateHit(nodes: List<NodeRecord>, x: Int, y: Int): NodeRecord? =
        nodes.filter { it.bounds.contains(x, y) }.maxByOrNull { it.path.size }

    /** 给宏回放用：只要命中元素，不要中间 JSON。 */
    private fun resolveQuery(service: AccessibilityService, query: Query): SelectHit? {
        if (query.isEmpty) return null
        return selectOnce(service, query).hit
    }

    // ------------------------------------------------------ 等待原语 ----

    /**
     * 通用的「等一个条件成立」：事件驱动轮询 + 明确超时。
     *
     * 区分两种失败：[probe] 一次也没成立且超时为 0 → `NOT_FOUND`（现在就看看有没有）；
     * 超时用尽 → [TIMEOUT]。这就是「明确的 NOT_FOUND / TIMEOUT」。
     */
    private fun awaitCondition(
        what: String,
        timeoutMs: Int,
        hint: String,
        probe: () -> JSONObject?,
    ): JSONObject {
        val timeout = timeoutMs.coerceIn(0, 60_000)
        val started = System.currentTimeMillis()
        val deadline = started + timeout
        var polls = 0
        while (true) {
            val revision = DeviceAccessibilitySignals.current
            polls++
            val detail = probe()
            if (detail != null) {
                return JSONObject().apply {
                    put("ok", true)
                    put("elapsedMs", System.currentTimeMillis() - started)
                    put("polls", polls)
                    put("coordinateSpace", SPACE)
                    put("match", detail)
                }
            }
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) break
            DeviceAccessibilitySignals.awaitChange(revision, min(remaining, 200L))
        }
        val waited = System.currentTimeMillis() - started
        val tail = if (timeout <= 0) "：当前屏幕上没有" else "超时（${waited}ms）：仍然没有"
        throw DeviceActionException(
            DeviceDenial(
                code = if (timeout <= 0) DeviceDenial.NOT_FOUND else TIMEOUT,
                reason = "等待$what$tail。",
                hint = hint,
            ),
        )
    }

    /**
     * 等屏幕空闲：用「元素表指纹连续两次相同」判定，而不是固定 sleep。
     *
     * 解决的失败模式：动作之后的动画/加载有长有短，`Thread.sleep(500)` 对慢设备太短、
     * 对快设备又白等。指纹法只在真正稳定时返回，且用无障碍事件唤醒，快的界面几乎
     * 立刻返回。
     */
    fun waitForIdle(service: AccessibilityService, timeoutMs: Int = 5000): JSONObject {
        val timeout = timeoutMs.coerceIn(200, 60_000)
        val started = System.currentTimeMillis()
        val deadline = started + timeout
        var previous = screenFingerprint(service)
        var reads = 1
        while (true) {
            val revision = DeviceAccessibilitySignals.current
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) break
            DeviceAccessibilitySignals.awaitChange(revision, min(remaining, 200L))
            val current = screenFingerprint(service)
            reads++
            if (current.value == previous.value && current.packageName == previous.packageName) {
                return JSONObject().apply {
                    put("idle", true)
                    put("elapsedMs", System.currentTimeMillis() - started)
                    put("reads", reads)
                    put("elementCount", current.elementCount)
                    put("fingerprint", current.value)
                    put("packageName", current.packageName)
                }
            }
            previous = current
        }
        throw DeviceActionException(
            DeviceDenial(
                code = TIMEOUT,
                reason = "屏幕在 ${timeout}ms 内没有出现连续两次相同的指纹（一直在变）。",
                hint = "等动画/加载结束再试，或调大 timeoutMs；一直不停说明有轮播或视频。",
            ),
        )
    }

    /** 等文本出现；[exact] 为真时要求完全相等（本地化/前后缀敏感时用）。 */
    fun waitForText(
        service: AccessibilityService,
        text: String,
        timeoutMs: Int = 5000,
        exact: Boolean = false,
    ): JSONObject {
        val needle = text.trim()
        if (needle.isEmpty()) {
            throw DeviceActionException(DeviceDenial(DeviceDenial.BAD_REQUEST, "waitForText 需要非空文本。"))
        }
        return awaitCondition(
            what = "文本「$needle」出现",
            timeoutMs = timeoutMs,
            hint = "确认文本没变（本地化/大小写），或先用 elements() 看当前屏幕。",
        ) {
            val hit = readTreeAll(service).firstOrNull { record ->
                if (record.bounds.isEmpty) return@firstOrNull false
                if (exact) {
                    record.text == needle || record.description == needle
                } else {
                    record.text.contains(needle, ignoreCase = true) ||
                        record.description.contains(needle, ignoreCase = true)
                }
            }
            hit?.let {
                JSONObject().put("text", it.text.ifEmpty { it.description }).put("node", recordToJson(it))
            }
        }
    }

    /** 等某个 resourceId 出现（精确匹配，或匹配 `pkg:id/xxx` 的尾部）。 */
    fun waitForId(service: AccessibilityService, resourceId: String, timeoutMs: Int = 5000): JSONObject {
        val needle = resourceId.trim()
        if (needle.isEmpty()) {
            throw DeviceActionException(DeviceDenial(DeviceDenial.BAD_REQUEST, "waitForId 需要非空 resourceId。"))
        }
        return awaitCondition(
            what = "resourceId「$needle」出现",
            timeoutMs = timeoutMs,
            hint = "resourceId 可能已改名，改用 waitForText 或 select 的多策略。",
        ) {
            val hit = readTreeAll(service).firstOrNull { record ->
                val id = record.viewId ?: return@firstOrNull false
                id == needle || id.endsWith("/$needle")
            }
            hit?.let { JSONObject().put("resourceId", it.viewId).put("node", recordToJson(it)) }
        }
    }

    /** 等前台包名切换（例如等一个 App 真正起来）。 */
    fun waitForWindow(service: AccessibilityService, packageName: String, timeoutMs: Int = 5000): JSONObject {
        val wanted = packageName.trim()
        if (wanted.isEmpty()) {
            throw DeviceActionException(DeviceDenial(DeviceDenial.BAD_REQUEST, "waitForWindow 需要非空包名。"))
        }
        return awaitCondition(
            what = "前台切到「$wanted」",
            timeoutMs = timeoutMs,
            hint = "确认包名；当前前台包名见 /app/health 的 foreground。",
        ) {
            val current = foregroundPackage(service)
            if (current != null && packageMatches(current, wanted)) {
                JSONObject().put("packageName", current)
            } else {
                null
            }
        }
    }

    /** 只读一次当前指纹（供 waitForIdle 与诊断用）。 */
    private fun screenFingerprint(service: AccessibilityService): ScreenFingerprint {
        val root = activeRoot(service)
        val packageName = root?.packageName?.toString().orEmpty()
        val metrics = service.resources.displayMetrics
        val records = ArrayList<NodeRecord>()
        if (root != null) {
            collectActionable(root, packageName, emptyList(), 0, MAX_NODES_LIMIT, 64, records)
        }
        if (records.isEmpty()) {
            // 元素表为空（比如全屏画布）时退回整棵树签名；否则「空 == 空」会让
            // waitForIdle 在任何界面上都立刻误判 idle。
            return ScreenFingerprint(packageName, "tree:" + signature(service), 0)
        }
        return ScreenFingerprint(
            packageName,
            fingerprintOf(elementDescriptors(records, metrics.widthPixels, metrics.heightPixels)),
            records.size,
        )
    }

    // -------------------------------------------------------- UI 宏 ----

    @Volatile
    private var recording = false

    @Volatile
    private var recordingName = ""

    @Volatile
    private var recordingStartedAt = 0L

    private val recordedSteps = java.util.Collections.synchronizedList(ArrayList<JSONObject>())

    @Volatile
    private var lastRecipeJson: JSONObject? = null

    /** 开始录制。已经在录时不打断，明确回报。 */
    fun startRecording(name: String): JSONObject {
        synchronized(recordedSteps) {
            if (recording) {
                return JSONObject().apply {
                    put("recording", true)
                    put("name", recordingName)
                    put("steps", recordedSteps.size)
                    put("note", "已经在录制「$recordingName」；先 stopRecording 再开新的。")
                }
            }
            recordedSteps.clear()
            recording = true
            recordingName = name.ifBlank { "recipe" }
            recordingStartedAt = System.currentTimeMillis()
            return JSONObject().apply {
                put("recording", true)
                put("name", recordingName)
                put("steps", 0)
            }
        }
    }

    /**
     * 追加一条「选择器 + 动作」。
     *
     * 录的是**选择器**而不是坐标：屏幕一换分辨率或布局，坐标就失效，而选择器可以在
     * 回放时按当前屏幕重新解析（锚点自愈）。
     */
    fun recordStep(step: JSONObject): JSONObject {
        if (!recording) {
            return JSONObject().apply {
                put("recorded", false)
                put("reason", "当前没有在录制；先调用 startRecording(name)。")
            }
        }
        val copy = JSONObject(step.toString())
        synchronized(recordedSteps) { recordedSteps.add(copy) }
        return JSONObject().apply {
            put("recorded", true)
            put("name", recordingName)
            put("steps", synchronized(recordedSteps) { recordedSteps.size })
        }
    }

    /** 停止录制并返回 recipe（写入内存，见 [recipe]）。 */
    fun stopRecording(): JSONObject {
        val active = recording
        val steps = synchronized(recordedSteps) { ArrayList(recordedSteps) }
        recording = false
        val result = JSONObject().apply {
            put("name", recordingName)
            put("steps", JSONArray(steps))
            put("packageName", lastElementPackage)
            put("recordedAt", recordingStartedAt)
            put("durationMs", System.currentTimeMillis() - recordingStartedAt)
        }
        lastRecipeJson = result
        synchronized(recordedSteps) { recordedSteps.clear() }
        if (!active) result.put("note", "当时并没有在录制，recipe 为空。")
        return result
    }

    fun recordingState(): JSONObject = JSONObject().apply {
        put("recording", recording)
        put("name", recordingName)
        put("steps", synchronized(recordedSteps) { recordedSteps.size })
        put("since", recordingStartedAt)
    }

    /** 最近一次 [stopRecording] 的 recipe，或 null。 */
    fun recipe(): JSONObject? = lastRecipeJson

    /**
     * 回放一串 recipe 步骤，每一步都按当前屏幕重新解析选择器（锚点自愈）。
     *
     * 有界重试：单步失败最多重试 [DEFAULT_STEP_RETRIES]（可用步骤里的 `retries` 调，
     * 上限 [MAX_STEP_RETRIES]），重试之间先等 UI 空闲，**不**无脑死循环。步骤可标
     * `optional: true` 允许跳过；未标 optional 的步骤失败即中止，返回已完成到哪一步。
     */
    suspend fun play(recipe: JSONObject, service: AccessibilityService): JSONObject {
        val steps = recipe.optJSONArray("steps") ?: JSONArray()
        val results = JSONArray()
        val started = System.currentTimeMillis()
        var completed = 0
        var ok = true
        var abortedAt = -1
        for (i in 0 until steps.length()) {
            val step = steps.optJSONObject(i) ?: continue
            val optional = step.optBoolean("optional", false)
            val maxAttempts = step.optInt("retries", DEFAULT_STEP_RETRIES).coerceIn(1, MAX_STEP_RETRIES)
            val stepResult = JSONObject()
            var succeeded = false
            var lastError = "未知失败"
            var attempt = 0
            while (attempt < maxAttempts) {
                attempt++
                val payload: JSONObject? = try {
                    executeMacroStep(service, step)
                } catch (error: Throwable) {
                    // 协程取消必须原样传出，不能被重试逻辑吞掉。
                    if (error is kotlin.coroutines.cancellation.CancellationException) throw error
                    lastError = describeError(error)
                    null
                }
                if (payload != null && payload.optBoolean("ok", false)) {
                    succeeded = true
                    stepResult.put("result", payload)
                    break
                }
                if (payload != null) {
                    lastError = payload.optString("error").ifEmpty { "步骤返回失败" }
                }
                if (attempt < maxAttempts) {
                    // 有界重试前先给界面一个稳定窗口；超时说明还在动，直接进入下一轮。
                    try {
                        waitForIdle(service, timeoutMs = 300 * attempt)
                    } catch (_: Throwable) {
                        // 还在变化：重试本身已经是兜底，这里不打断流程。
                    }
                }
            }
            stepResult.put("index", i)
            stepResult.put("action", step.optString("action"))
            stepResult.put("attempts", attempt)
            stepResult.put("ok", succeeded)
            if (!succeeded) stepResult.put("error", lastError)
            results.put(stepResult)
            if (succeeded) {
                completed++
            } else if (!optional) {
                ok = false
                abortedAt = i
                break
            }
        }
        return JSONObject().apply {
            put("ok", ok)
            put("name", recipe.optString("name"))
            put("steps", steps.length())
            put("completed", completed)
            put("abortedAt", if (abortedAt >= 0) abortedAt else JSONObject.NULL)
            put("elapsedMs", System.currentTimeMillis() - started)
            put("results", results)
        }
    }

    /** 把一步执行里的异常翻译成一句可回报的中文原因。 */
    private fun describeError(error: Throwable): String =
        (error as? DeviceActionException)?.denial?.toMessage()
            ?: (error.message ?: error.javaClass.simpleName)

    /** 把 recipe 的一步真正执行掉；失败以 `ok=false` 或异常回报，交给 [play] 重试。 */
    private suspend fun executeMacroStep(service: AccessibilityService, step: JSONObject): JSONObject {
        val action = step.optString("action").trim().lowercase().replace("_", "")
        val query = queryFromStep(step)
        return when (action) {
            "tap", "click" -> {
                val hit = resolveQuery(service, query)
                when {
                    hit != null -> performTap(service, hit, query, longPress = false)
                    query.x != null && query.y != null -> {
                        dispatchTap(service, query.x, query.y, longPress = false)
                        JSONObject().apply {
                            put("ok", true)
                            put("mode", "gesture_tap")
                            put("strategy", "coordinate")
                            put("x", query.x)
                            put("y", query.y)
                        }
                    }

                    else -> stepFailure("找不到可点元素：${query.describe()}", query)
                }
            }

            "longpress", "longclick" -> {
                val hit = resolveQuery(service, query)
                when {
                    hit != null -> performTap(service, hit, query, longPress = true)
                    query.x != null && query.y != null -> {
                        dispatchTap(service, query.x, query.y, longPress = true)
                        JSONObject().apply {
                            put("ok", true)
                            put("mode", "gesture_long_press")
                            put("strategy", "coordinate")
                            put("x", query.x)
                            put("y", query.y)
                        }
                    }

                    else -> stepFailure("找不到可长按元素：${query.describe()}", query)
                }
            }

            "input", "settext", "type" -> {
                val hit = resolveQuery(service, query)
                val selector = hit?.let { selectorOf(it.record) } ?: selectorFrom(query)
                if (selector.isEmpty) return stepFailure("input 步骤缺少目标选择器", query)
                val written = input(
                    service = service,
                    text = step.optString("text"),
                    index = null,
                    submit = step.optBoolean("submit", false),
                    selector = selector,
                )
                if (hit != null) rememberObservedAction(hit.record.packageName, hit.record, "input")
                written.put("ok", true).put("strategy", hit?.strategy ?: "fallback")
            }

            "swipe" -> swipe(
                service = service,
                x1 = step.optInt("x1"),
                y1 = step.optInt("y1"),
                x2 = step.optInt("x2"),
                y2 = step.optInt("y2"),
                durationMs = step.optInt("durationMs", 300),
            ).put("ok", true)

            "scroll" -> {
                val hit = resolveQuery(service, query)
                val selector = hit?.let { selectorOf(it.record) } ?: selectorFrom(query)
                val result = scroll(
                    service = service,
                    selector = selector.takeUnless { it.isEmpty },
                    index = null,
                    direction = step.optString("direction", "forward"),
                )
                if (hit != null) rememberObservedAction(hit.record.packageName, hit.record, "scroll")
                result.put("ok", true)
            }

            "key", "press" -> key(service, step.optString("key").ifEmpty { step.optString("keyName") })
                .put("ok", true)

            "waittext" -> waitForText(
                service,
                step.optString("text"),
                step.optInt("timeoutMs", 5000),
                step.optBoolean("exact", false),
            ).put("ok", true)

            "waitid" -> waitForId(service, step.optString("resourceId", step.optString("id")), step.optInt("timeoutMs", 5000))
                .put("ok", true)

            "waitwindow" -> waitForWindow(
                service,
                step.optString("packageName", step.optString("package")),
                step.optInt("timeoutMs", 5000),
            ).put("ok", true)

            "waitidle" -> waitForIdle(service, step.optInt("timeoutMs", 5000)).put("ok", true)

            else -> stepFailure("未知动作类型「$action」", query)
        }
    }

    /** 对命中元素执行点按，优先真实 `ACTION_CLICK`，否则退回中心点手势。 */
    private suspend fun performTap(
        service: AccessibilityService,
        hit: SelectHit,
        query: Query,
        longPress: Boolean,
    ): JSONObject {
        val node = resolve(service, hit.record)
        val clickable = if (node == null) {
            null
        } else if (longPress) {
            nearestLongClickable(node) ?: node
        } else {
            nearestClickable(node) ?: node
        }
        val performed = clickable?.performAction(
            if (longPress) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK,
        ) ?: false
        val mode = if (performed) {
            if (longPress) "action_long_click" else "action_click"
        } else {
            val rect = if (node != null) Rect().also { node.getBoundsInScreen(it) } else hit.record.bounds
            if (rect.isEmpty) return stepFailure("目标不可点击且没有可见区域（${hit.record.className}）", query)
            dispatchTap(service, rect.centerX(), rect.centerY(), longPress)
            if (longPress) "gesture_long_press" else "gesture_tap"
        }
        rememberObservedAction(hit.record.packageName, hit.record, if (longPress) "longPress" else "tap")
        return JSONObject().apply {
            put("ok", true)
            put("mode", mode)
            put("strategy", hit.strategy)
            put("target", recordToJson(hit.record))
            put("center", JSONArray(listOf(hit.record.bounds.centerX(), hit.record.bounds.centerY())))
        }
    }

    private fun stepFailure(reason: String, query: Query): JSONObject = JSONObject().apply {
        put("ok", false)
        put("error", reason)
        if (!query.isEmpty) put("query", query.describe())
    }

    /**
     * 从步骤里取查询。优先嵌套的 `query` 对象；否则只认扁平的选择器字段，**不会**
     * 把 input 的 `text`（要输入的内容）当成选择器。
     */
    private fun queryFromStep(step: JSONObject): Query {
        step.optJSONObject("query")?.let { return parseQuery(it) }
        val flat = JSONObject()
        for (key in listOf(
            "resourceId", "id", "description", "desc", "className", "class",
            "anchorText", "anchor", "direction", "occurrence", "packageName",
            "package", "clickableOnly", "x", "y",
        )) {
            if (step.has(key)) flat.put(key, step.get(key))
        }
        return parseQuery(flat)
    }

    private fun parseQuery(raw: JSONObject): Query = Query(
        resourceId = raw.optString("resourceId").takeIf { it.isNotEmpty() }
            ?: raw.optString("id").takeIf { it.isNotEmpty() },
        text = raw.optString("text").takeIf { it.isNotEmpty() },
        description = raw.optString("description").takeIf { it.isNotEmpty() }
            ?: raw.optString("desc").takeIf { it.isNotEmpty() },
        packageName = raw.optString("packageName").takeIf { it.isNotEmpty() }
            ?: raw.optString("package").takeIf { it.isNotEmpty() },
        className = raw.optString("className").takeIf { it.isNotEmpty() }
            ?: raw.optString("class").takeIf { it.isNotEmpty() },
        anchorText = raw.optString("anchorText").takeIf { it.isNotEmpty() }
            ?: raw.optString("anchor").takeIf { it.isNotEmpty() },
        direction = raw.optString("direction").takeIf { it.isNotEmpty() },
        occurrence = if (raw.has("occurrence")) raw.optInt("occurrence") else null,
        clickableOnly = raw.optBoolean("clickableOnly", false),
        x = if (raw.has("x")) raw.optInt("x") else null,
        y = if (raw.has("y")) raw.optInt("y") else null,
    )

    private fun selectorFrom(query: Query): Selector = Selector(
        text = query.text,
        description = query.description,
        resourceId = query.resourceId,
        packageName = query.packageName,
        className = query.className,
        clickableOnly = query.clickableOnly,
    )

    /** 用命中元素重造一个选择器：回放输入/滚动时复用现有装置做第二次解析。 */
    private fun selectorOf(record: NodeRecord): Selector = Selector(
        text = record.text.takeIf { it.isNotEmpty() },
        description = record.description.takeIf { it.isNotEmpty() },
        resourceId = record.viewId?.substringAfterLast('/')?.takeIf { it.isNotEmpty() },
        className = record.className.takeIf { it.isNotEmpty() },
    )

    // --------------------------------------------------------- App 记忆 ----

    /**
     * 以包名为键记住「某 App 里见过哪些元素、它们能做什么」。
     *
     * 解决的失败模式：同一个 App 每一步都从头猜控件。记住元素 → 动作后，下一次
     * 可以先查表再决定，少 dump、少猜。内存态即可（会话级），不落盘、不跨用户。
     */
    private val elementMemory =
        java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentHashMap<String, MutableSet<String>>>()

    private fun memoryFor(packageName: String): java.util.concurrent.ConcurrentHashMap<String, MutableSet<String>> =
        elementMemory.computeIfAbsent(packageName) { java.util.concurrent.ConcurrentHashMap<String, MutableSet<String>>() }

    private fun memoryKey(record: NodeRecord): String =
        (record.viewId?.takeIf { it.isNotEmpty() } ?: record.className).ifEmpty { "Node" }

    /** 读一屏元素表时顺手记下：每个元素可以执行哪些动作。 */
    private fun rememberScreen(packageName: String, records: List<NodeRecord>) {
        if (packageName.isEmpty()) return
        for (record in records) {
            val inferred = buildList {
                if (record.clickable) add("tap")
                if (record.longClickable) add("longPress")
                if (record.editable) add("input")
                if (record.scrollable) add("scroll")
            }
            if (inferred.isEmpty()) continue
            val actions = memoryFor(packageName).computeIfAbsent(memoryKey(record)) {
                java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
            }
            actions.addAll(inferred)
        }
    }

    /** 一次动作真的执行成功后，把「这个元素被这样用过」记下来。 */
    private fun rememberObservedAction(packageName: String, record: NodeRecord, action: String) {
        if (packageName.isEmpty()) return
        val actions = memoryFor(packageName).computeIfAbsent(memoryKey(record)) {
            java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        }
        actions.add(action)
    }

    /** 导出记忆；给 [packageName] 则只看那一个 App。 */
    fun appMemory(packageName: String? = null): JSONObject {
        var total = 0
        return JSONObject().apply {
            val apps = JSONObject()
            for ((pkg, entries) in elementMemory) {
                if (packageName != null && pkg != packageName) continue
                val actions = JSONObject()
                var count = 0
                for ((key, actionSet) in entries) {
                    actions.put(key, JSONArray(actionSet.toList().sorted()))
                    count++
                }
                total += count
                apps.put(pkg, JSONObject().put("elements", count).put("actions", actions))
            }
            put("apps", apps)
            put("elementCount", total)
        }
    }

    // --------------------------------------------------- 视觉兜底接口 ----

    /**
     * 把「截图 + 目标描述」打包成一次视觉兜底的*请求*，交给上层去问视觉模型。
     *
     * 为什么只做打包、不在这里调模型：DeviceUiAutomation 的职责是设备事实，模型调用
     * 是上层（扩展）的事，也是整个项目里唯一该出现模型调用的地方。这里只负责在
     * 「结构化元素表为空」或「一次点击后 changed=false」时，把视觉模型需要的全部输入
     * ——屏幕、目标、已有元素表、坐标换算——一次性备好，让上层决定要不要问、问谁。
     */
    fun visualFallback(
        service: AccessibilityService,
        target: String,
        screenshotBase64: String? = null,
        maxDimension: Int = 1280,
    ): JSONObject {
        val wanted = target.trim().ifEmpty { "（未指定目标描述）" }
        var image: String? = screenshotBase64
        var captureNote: String? = null
        if (image == null) {
            image = runCatching {
                screenshot(service, format = "jpeg", maxDimension = maxDimension, quality = 80)
                    .optString("base64")
            }.getOrElse { error ->
                captureNote = (error as? DeviceActionException)?.denial?.reason
                    ?: (error.message ?: "截图失败")
                null
            }
        }
        val metrics = service.resources.displayMetrics
        return JSONObject().apply {
            put("target", wanted)
            put("screenshotBase64", image ?: JSONObject.NULL)
            put("mimeType", "image/jpeg")
            put("coordinateSpace", SPACE)
            put("screen", JSONObject().put("width", metrics.widthPixels).put("height", metrics.heightPixels))
            // lastElementsJson 是 @Volatile，直接读即可；不要在这里 synchronized(this) ——
            // apply 里的 this 是正在构造的 JSONObject，不是本对象。
            put("elements", lastElementsJson?.optJSONArray("elements") ?: JSONArray())
            put("fingerprint", lastElementFingerprint)
            put("packageName", foregroundPackage(service) ?: lastElementPackage)
            put("maxDimension", maxDimension)
            if (captureNote != null) put("captureNote", captureNote)
            put("candidates", JSONArray())
            put(
                "promptHint",
                "让视觉模型只返回 JSON：{\"candidates\":[{\"x\":<display像素>,\"y\":<display像素>," +
                    "\"confidence\":0..1,\"label\":\"...\"}]}。坐标必须是 coordinateSpace=display " +
                    "的像素；拿到回复后用 visualFallbackCandidates 归一化。",
            )
        }
    }

    /**
     * 把视觉模型的回复归一化成「带置信度的候选坐标列表」，让上层决定点哪个。
     *
     * 归一化放在设备侧：越界、排序、坐标系这些校验只依赖本机事实，在这里做一次，
     * 上层拿到的就是可直接点按的 display 像素。
     */
    fun visualFallbackCandidates(reply: JSONObject, screenWidth: Int, screenHeight: Int): JSONArray {
        val raw = reply.optJSONArray("candidates") ?: reply.optJSONArray("points") ?: JSONArray()
        val out = ArrayList<JSONObject>()
        for (i in 0 until raw.length()) {
            val item = raw.optJSONObject(i) ?: continue
            if (!item.has("x") || !item.has("y")) continue
            val x = item.optInt("x")
            val y = item.optInt("y")
            val confidence = if (item.has("confidence")) {
                item.optDouble("confidence", 0.0)
            } else {
                item.optDouble("score", 0.0)
            }
            out.add(
                JSONObject().apply {
                    put("x", x)
                    put("y", y)
                    put("confidence", confidence)
                    put("inScreen", x >= 0 && y >= 0 && x < screenWidth && y < screenHeight)
                    put("label", item.optString("label", item.optString("text")))
                    put("coordinateSpace", SPACE)
                },
            )
        }
        out.sortByDescending { it.optDouble("confidence", 0.0) }
        return JSONArray(out)
    }

    // ------------------------------------------------- 对接面（/app/health）----

    /** 给 `/app/health` 用的自述：当前模式、手势占用、记忆与录制状态。 */
    fun status(): JSONObject = JSONObject().apply {
        put("component", "DeviceUiAutomation")
        put("running", DeviceAccessibilityService.isRunning())
        put("gestureBusy", gestureBusy.get())
        put("snapshotId", snapshotId)
        put("lastPackage", lastPackage)
        put("lastSnapshotNodes", lastSnapshot.size)
        put("elementFingerprint", lastElementFingerprint)
        put("elementCount", lastElementCount)
        put("recording", recording)
        put("recordedSteps", synchronized(recordedSteps) { recordedSteps.size })
        put("memorizedPackages", elementMemory.size)
        put("coordinateSpace", SPACE)
        put("timeoutCode", TIMEOUT)
    }

    /**
     * 这个组件现在能不能用：无障碍已启用（含「已启用但平台还没连上」这段宽限期）。
     * 与 `DeviceAccessibilityService.State.NOT_ENABLED` 严格区分——「没开」和「在重连」
     * 需要用户做相反的事。
     */
    fun available(context: android.content.Context): Boolean =
        DeviceAccessibilityService.state(context) != DeviceAccessibilityService.State.NOT_ENABLED
}

/** A bridge failure that already knows how to describe itself to the model. */
class DeviceActionException(val denial: DeviceDenial) : Exception(denial.toMessage())
