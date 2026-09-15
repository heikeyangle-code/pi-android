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
    fun scroll(
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
        val action = beginAction(service)
        val start: AccessibilityNodeInfo? = when {
            selector != null && !selector.isEmpty -> bestMatch(service, selector)
            index != null -> lastSnapshot.firstOrNull { it.index == index }?.let { resolve(service, it) }
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
        } ?: throw DeviceActionException(
            DeviceDenial(
                code = DeviceDenial.UNSUPPORTED,
                reason = "没有找到可滚动的控件" +
                    (if (selector != null && !selector.isEmpty) "：${selector.describe()}" else "") + "。",
                hint = "改用 android_swipe 坐标滑动，或找带 scrollable 的控件。",
            ),
        )
        val performed = target.performAction(
            if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,
        )
        if (!performed) {
            throw DeviceActionException(
                DeviceDenial(
                    code = DeviceDenial.BUSY,
                    reason = "控件拒绝滚动（可能已到尽头或正在动画）。",
                    hint = "稍等重试；反复如此说明已到列表尽头。",
                    retryable = true,
                ),
            )
        }
        return action.finish(
            JSONObject().apply {
                put("mode", if (forward) "action_scroll_forward" else "action_scroll_backward")
                put("direction", if (forward) "forward" else "backward")
                put("target", nodeJson(target))
            },
        )
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
        val target: AccessibilityNodeInfo? = when {
            selector != null && !selector.isEmpty && index == null -> bestMatch(service, selector)
            index != null -> {
                val record = lastSnapshot.firstOrNull { it.index == index }
                    ?: throw DeviceActionException(
                        DeviceDenial(
                            code = DeviceDenial.NOT_FOUND,
                            reason = "找不到编号 $index 的输入框：屏幕已变化。",
                            hint = "重新 dump 后写入最新输入框，或用 resourceId/text。",
                        ),
                    )
                resolve(service, record)
            }
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
        fun finish(payload: JSONObject): JSONObject {
            // Qualified: a nested class has no implicit receiver for the enclosing
            // object's members.
            val after = DeviceUiAutomation.awaitForeground(service, before)
            val afterSignature = DeviceUiAutomation.signature(service)
            payload.put("before", before ?: JSONObject.NULL)
            payload.put("after", after ?: JSONObject.NULL)
            payload.put("changed", before != after || beforeSignature != afterSignature)
            payload.put("coordinateSpace", SPACE)
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

    // ------------------------------------------------------- live inspection ----

    /** For the diagnostics card: are we mid-gesture, and how big is the cursor. */
    fun busy(): Boolean = gestureBusy.get()

    fun lastSnapshotInfo(): JSONObject = JSONObject().apply {
        put("snapshotId", snapshotId)
        put("packageName", lastPackage)
        put("nodeCount", lastSnapshot.size)
        put("gestureBusy", gestureBusy.get())
    }
}

/** A bridge failure that already knows how to describe itself to the model. */
class DeviceActionException(val denial: DeviceDenial) : Exception(denial.toMessage())
