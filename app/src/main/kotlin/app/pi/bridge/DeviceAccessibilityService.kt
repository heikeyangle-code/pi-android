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
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // The bridge pulls the tree on demand; event streaming would burn battery
        // and there is no consumer for it.
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

    companion object {
        @Volatile
        private var instance: DeviceAccessibilityService? = null

        /** The live service, or null when the user has not enabled it. */
        fun running(): DeviceAccessibilityService? = instance

        fun isRunning(): Boolean = instance != null

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

    /** Collapse whitespace and clip, so one node never becomes a wall of text. */
    fun clip(raw: CharSequence?, max: Int = 160): String {
        val value = raw?.toString().orEmpty().trim()
        if (value.isEmpty()) return ""
        val collapsed = value.replace(Regex("\\s+"), " ")
        return if (collapsed.length <= max) collapsed else collapsed.substring(0, max) + "…"
    }

    fun isBlank(raw: CharSequence?): Boolean = TextUtils.isEmpty(raw?.toString()?.trim())
}
