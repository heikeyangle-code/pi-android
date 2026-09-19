package app.pi.bridge

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityEvent

/**
 * The one piece of the bridge that needs a system-granted privilege rather than
 * a permission: reading the screen and injecting input.
 *
 * Declared in the manifest with `android:exported="false"` plus a
 * `meta-data` pointing at `res/xml/accessibility_service_config.xml` (the task's
 * requirement, and also the only way the platform learns that this service may
 * perform gestures and take screenshots).
 *
 * The service is deliberately *dumb*: it holds no state and makes no decisions.
 * All policy lives in [DeviceCapabilityStore], all work in [DeviceUiAutomation],
 * so a request that arrives while the service is up is still refused if the user
 * has not opted in.
 */
class DeviceAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        DeviceAccessibilitySignals.publish()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // The tree is still pulled on demand, but the *fact* that something
        // changed is what `ui/wait` waits on: re-reading a 400-node tree every
        // 200 ms to answer "did the screen update yet" is both slower and more
        // expensive than being woken by the event that changed it.
        DeviceAccessibilitySignals.publish()
    }

    override fun onInterrupt() {
        // Nothing to interrupt: gestures are dispatched one at a time by callers.
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /**
     * The three states the UI and the model both need to tell apart.
     *
     * `isRunning()` alone conflates the second and third: a service the user has
     * enabled in Settings but which the platform has not bound yet reports
     * "not running", which reads as "your switch did nothing".
     */
    enum class State { NOT_ENABLED, ENABLED_NOT_CONNECTED, CONNECTED }

    companion object {
        @Volatile
        private var instance: DeviceAccessibilityService? = null

        /** The live service, or null when the user has not enabled it. */
        fun running(): DeviceAccessibilityService? = instance

        fun isRunning(): Boolean = instance != null

        /** Which of the three states the platform reports right now. */
        fun state(context: Context): State = when {
            instance != null -> State.CONNECTED
            isEnabledInSettings(context) -> State.ENABLED_NOT_CONNECTED
            else -> State.NOT_ENABLED
        }

        /** Stable wire name for [state], so /app/health and the UI agree. */
        fun stateName(context: Context): String = when (state(context)) {
            State.CONNECTED -> "connected"
            State.ENABLED_NOT_CONNECTED -> "enabled_not_connected"
            State.NOT_ENABLED -> "not_enabled"
        }

        /**
         * Wait up to [attempts] × [delayMs] for the platform to bind the service.
         *
         * This is the "已启用但还没连上" grace period, not a retry loop around a
         * real failure: the bind normally lands within a few hundred ms of the
         * user flipping the switch, and a request that arrived one frame early
         * used to be refused as if the service were off.
         */
        fun awaitRunning(attempts: Int = 2, delayMs: Long = 500): DeviceAccessibilityService? {
            instance?.let { return it }
            repeat(attempts.coerceIn(0, 5)) {
                runCatching { Thread.sleep(delayMs.coerceIn(0, 2000)) }
                instance?.let { return it }
            }
            return instance
        }

        /**
         * Where the user has to go to turn this on. The bridge cannot ask for it
         * itself — no runtime permission exists for accessibility — so the UI
         * offers this intent and the model is told to send the user there.
         */
        fun settingsIntent(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /** Whether the platform reports this service as enabled for [context]. */
        fun isEnabledInSettings(context: Context): Boolean {
            val expected = "${context.packageName}/${DeviceAccessibilityService::class.java.name}"
            val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE)
                as? android.view.accessibility.AccessibilityManager ?: return false
            val enabled = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            for (info in enabled) {
                val id = info.id ?: continue
                if (id == expected || id.startsWith(context.packageName)) return true
            }
            return false
        }
    }
}

/**
 * "Something on screen changed" — the event-driven half of `ui/wait`.
 *
 * Deliberately a monotonically increasing counter plus a monitor, not a queue of
 * events: `ui/wait` only needs to know *that* it should re-read the tree, never
 * *what* changed, so there is nothing to parse and nothing to leak. A waiter that
 * misses a notification still re-reads the tree on its next 200 ms tick — the
 * same behaviour as the old pure polling — so the counter can only make the
 * common case (an app redraws in 40 ms) faster, never break the fallback.
 */
object DeviceAccessibilitySignals {

    private val monitor = Object()

    @Volatile
    private var revision: Long = 0

    val current: Long get() = revision

    fun publish() {
        synchronized(monitor) {
            revision += 1
            monitor.notifyAll()
        }
    }

    /** Sleep until [revision] moves past [since], or [timeoutMs] elapses. */
    fun awaitChange(since: Long, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs.coerceAtLeast(0)
        synchronized(monitor) {
            while (revision == since) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) return
                runCatching { monitor.wait(remaining.coerceAtMost(200L)) }
            }
        }
    }
}

/**
 * Small helpers shared by the automation code. Compose and WebView produce long
 * class names and empty labels; both get normalised here so a dump stays
 * readable for a model that is looking at a phone screen through text.
 */
object DeviceUiText {

    fun simpleClassName(raw: CharSequence?): String {
        val value = raw?.toString().orEmpty()
        if (value.isEmpty()) return ""
        val cut = value.lastIndexOf('.')
        return if (cut >= 0 && cut < value.length - 1) value.substring(cut + 1) else value
    }

    /**
     * Collapse whitespace and clip, so one node never becomes a wall of text.
     *
     * The pattern is compiled once ([WHITESPACE_ANY]) rather than per call: this runs
     * twice per node on every dump (`DeviceUiAutomation.collect` for `text` and
     * `contentDescription`, and again in `walk` for selector matching), so a 400-node
     * dump asked for one regex object 800 times. Measured at ~1 ms per 800
     * `Pattern.compile`s on a desktop JVM — small, but it is pure waste on the path
     * whose whole job is to be fast enough for a model to poll.
     */
    fun clip(raw: CharSequence?, max: Int = 160): String {
        val value = raw?.toString().orEmpty().trim()
        if (value.isEmpty()) return ""
        val collapsed = WHITESPACE_ANY.matcher(value).replaceAll(" ")
        return if (collapsed.length <= max) collapsed else collapsed.substring(0, max) + "…"
    }

    fun isBlank(raw: CharSequence?): Boolean = TextUtils.isEmpty(raw?.toString()?.trim())

    /** One compiled `\s+`, for [clip]. `String.replaceAll` recompiles it every call. */
    private val WHITESPACE_ANY: java.util.regex.Pattern = java.util.regex.Pattern.compile("\\s+")
}
